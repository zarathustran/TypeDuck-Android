package hk.eduhk.typeduck.util

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.core.content.FileProvider
import hk.eduhk.typeduck.BuildConfig
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Local crash diagnostics for TypeDuck.
 *
 * Runtime logs live in app-private storage. Export copies a ZIP to Downloads on Android 10+
 * and returns a shareable content URI.
 */
object DiagnosticLogger {
    data class ExportedBundle(
        val uri: Uri,
        val fileName: String
    )

    private const val MAX_LOG_BYTES = 2L * 1024L * 1024L
    private const val MAX_JAVA_CRASH_FILES = 5

    private val lock = Any()

    @Volatile
    private var initialized = false

    private lateinit var appContext: Context
    private lateinit var diagnosticsDir: File
    private lateinit var runtimeLog: File
    private lateinit var nativeStderrLog: File

    fun init(context: Context) {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return
            appContext = context.applicationContext
            diagnosticsDir = File(appContext.filesDir, "diagnostics").apply { mkdirs() }
            runtimeLog = File(diagnosticsDir, "typeduck.log")
            nativeStderrLog = File(diagnosticsDir, "native-stderr.log")

            rotate(runtimeLog)
            rotate(nativeStderrLog)

            // Ask glog/librime to write native diagnostics to stderr. The stderr pipe below tees
            // that output to both Android logcat and an app-private file that survives a crash.
            runCatching {
                Os.setenv("GLOG_logtostderr", "1", true)
                Os.setenv("GLOG_colorlogtostderr", "0", true)
            }

            startNativeStderrCapture()
            Timber.plant(DiagnosticTree())
            installUncaughtExceptionHandler()

            initialized = true
            event(
                "diagnostics initialized; version=%s (%s), sdk=%s, device=%s/%s",
                BuildConfig.VERSION_NAME,
                BuildConfig.VERSION_CODE,
                Build.VERSION.SDK_INT,
                Build.MANUFACTURER,
                Build.MODEL
            )
        }
    }

    fun event(message: String, vararg args: Any?) {
        val rendered = runCatching {
            if (args.isEmpty()) message else String.format(Locale.ROOT, message, *args)
        }.getOrElse { message }
        appendLine(runtimeLog, "EVENT", rendered)
    }

    /**
     * Build a ZIP containing app logs, Java crash traces, Android process-exit history, and (when
     * Android provides it) native tombstones/ANR traces.
     */
    fun exportBundle(context: Context): ExportedBundle {
        if (!initialized) init(context)

        event("diagnostic export requested")

        val fileName = "TypeDuck-Diagnostics-\${fileTimestamp(System.currentTimeMillis())}.zip"
        val exportDir = File(context.cacheDir, "diagnostics-export").apply { mkdirs() }
        val tempZip = File(exportDir, fileName)
        if (tempZip.exists()) tempZip.delete()

        ZipOutputStream(FileOutputStream(tempZip)).use { zip ->
            putText(
                zip,
                "README.txt",
                """
                TypeDuck Mandarin diagnostic bundle

                Generated: \${isoTimestamp(System.currentTimeMillis())}
                App version: \${BuildConfig.VERSION_NAME} (\${BuildConfig.VERSION_CODE})
                Git build: \${BuildConfig.BUILD_GIT_HASH}

                Contents may include Android app/package names, stack traces, memory addresses,
                and TypeDuck/Rime log messages. The logger redacts obvious input-text log fields,
                but no automated redaction can guarantee that every user-entered string is absent.

                Native crashes:
                On Android 12+, system-exits/*.pb can contain Android's native tombstone protobuf.
                This is intentionally kept raw so it can be symbolicated against the exact native
                library from the matching TypeDuck build.
                """.trimIndent() + "\n"
            )

            putText(zip, "device.txt", buildDeviceReport(context))

            synchronized(lock) {
                listOf(
                    runtimeLog,
                    File(diagnosticsDir, "typeduck.log.1"),
                    nativeStderrLog,
                    File(diagnosticsDir, "native-stderr.log.1")
                ).filter { it.isFile }.forEach { file ->
                    putFile(zip, "logs/\${file.name}", file)
                }

                diagnosticsDir.listFiles()
                    ?.filter { it.isFile && it.name.startsWith("java-crash-") }
                    ?.sortedByDescending { it.lastModified() }
                    ?.take(MAX_JAVA_CRASH_FILES)
                    ?.forEach { file -> putFile(zip, "java-crashes/\${file.name}", file) }
            }

            putExitHistory(zip, context)
        }

        val uri = saveToDownloads(context, tempZip, fileName)
        event("diagnostic export complete: %s", fileName)
        return ExportedBundle(uri, fileName)
    }

    private fun buildDeviceReport(context: Context): String {
        val pageSize = runCatching { Os.sysconf(OsConstants._SC_PAGESIZE) }.getOrNull()
        val packageInfo = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()

        return buildString {
            appendLine("generated=\${isoTimestamp(System.currentTimeMillis())}")
            appendLine("package=\${context.packageName}")
            appendLine("versionName=\${packageInfo?.versionName ?: BuildConfig.VERSION_NAME}")
            @Suppress("DEPRECATION")
            appendLine("versionCode=\${packageInfo?.versionCode ?: BuildConfig.VERSION_CODE}")
            appendLine("git=\${BuildConfig.BUILD_GIT_HASH}")
            appendLine("androidRelease=\${Build.VERSION.RELEASE}")
            appendLine("sdkInt=\${Build.VERSION.SDK_INT}")
            appendLine("manufacturer=\${Build.MANUFACTURER}")
            appendLine("brand=\${Build.BRAND}")
            appendLine("model=\${Build.MODEL}")
            appendLine("device=\${Build.DEVICE}")
            appendLine("product=\${Build.PRODUCT}")
            appendLine("hardware=\${Build.HARDWARE}")
            appendLine("fingerprint=\${Build.FINGERPRINT}")
            appendLine("supportedAbis=\${Build.SUPPORTED_ABIS.joinToString(",")}")
            appendLine("pageSize=\${pageSize ?: "unknown"}")
        }
    }

    private fun putExitHistory(zip: ZipOutputStream, context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            putText(zip, "system-exits/exit-info.txt", "ApplicationExitInfo requires Android 11+.\n")
            return
        }

        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val exits = runCatching {
            activityManager.getHistoricalProcessExitReasons(null, 0, 10)
        }.getOrElse { error ->
            putText(
                zip,
                "system-exits/exit-info.txt",
                "Unable to query process exits: \${Log.getStackTraceString(error)}\n"
            )
            return
        }

        val report = buildString {
            appendLine("records=\${exits.size}")
            exits.forEachIndexed { index, info ->
                appendLine()
                appendLine("[\$index]")
                appendLine("timestamp=\${isoTimestamp(info.timestamp)}")
                appendLine("reason=\${reasonName(info.reason)} (\${info.reason})")
                appendLine("status=\${info.status}")
                appendLine("description=\${info.description ?: ""}")
                appendLine("processName=\${info.processName}")
                appendLine("pid=\${info.pid}")
                appendLine("importance=\${info.importance}")
                appendLine("pssKb=\${info.pss}")
                appendLine("rssKb=\${info.rss}")
            }
        }
        putText(zip, "system-exits/exit-info.txt", report)

        exits.forEachIndexed { index, info ->
            val trace = runCatching { info.traceInputStream }.getOrNull() ?: return@forEachIndexed
            val extension =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE
                ) {
                    "pb"
                } else {
                    "trace"
                }
            val name =
                "system-exits/exit-\${index}-\${info.timestamp}-\${reasonName(info.reason)}.\$extension"
            runCatching {
                trace.use { input ->
                    zip.putNextEntry(ZipEntry(name))
                    input.copyTo(zip)
                    zip.closeEntry()
                }
            }.onFailure { error ->
                runCatching { zip.closeEntry() }
                putText(
                    zip,
                    "system-exits/exit-\${index}-\${info.timestamp}-trace-error.txt",
                    Log.getStackTraceString(error)
                )
            }
        }
    }

    private fun saveToDownloads(context: Context, source: File, fileName: String): Uri {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                try {
                    resolver.openOutputStream(uri, "w")?.use { output ->
                        FileInputStream(source).use { input -> input.copyTo(output) }
                    } ?: error("Unable to open Downloads output stream")
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    return uri
                } catch (error: Exception) {
                    resolver.delete(uri, null, null)
                    appendLine(runtimeLog, "ERROR", "Saving diagnostic ZIP to Downloads failed", error)
                }
            }
        }

        return FileProvider.getUriForFile(
            context,
            "\${BuildConfig.APPLICATION_ID}.diagnostics.fileprovider",
            source
        )
    }

    private fun startNativeStderrCapture() {
        runCatching {
            val pipe = Os.pipe()
            Os.dup2(pipe[1], 2)
            Os.close(pipe[1])

            Thread(
                {
                    runCatching {
                        FileInputStream(pipe[0]).bufferedReader(Charsets.UTF_8).useLines { lines ->
                            lines.forEach { line ->
                                appendLine(nativeStderrLog, "STDERR", line)
                                Log.e("TypeDuckNative", line)
                            }
                        }
                    }.onFailure { error ->
                        appendLine(
                            runtimeLog,
                            "ERROR",
                            "native stderr capture stopped",
                            error
                        )
                    }
                },
                "TypeDuckNativeStderr"
            ).apply {
                isDaemon = true
                start()
            }
        }.onFailure { error ->
            appendLine(runtimeLog, "ERROR", "unable to capture native stderr", error)
        }
    }

    private fun installUncaughtExceptionHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val crashFile =
                    File(
                        diagnosticsDir,
                        "java-crash-\${fileTimestamp(System.currentTimeMillis())}.txt"
                    )
                val text = buildString {
                    appendLine("timestamp=\${isoTimestamp(System.currentTimeMillis())}")
                    appendLine("thread=\${thread.name}")
                    appendLine("version=\${BuildConfig.VERSION_NAME} (\${BuildConfig.VERSION_CODE})")
                    appendLine("git=\${BuildConfig.BUILD_GIT_HASH}")
                    appendLine()
                    append(Log.getStackTraceString(throwable))
                }
                crashFile.writeText(text)
                appendLine(runtimeLog, "FATAL", "uncaught exception on \${thread.name}", throwable)
                pruneJavaCrashFiles()
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun pruneJavaCrashFiles() {
        diagnosticsDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("java-crash-") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_JAVA_CRASH_FILES)
            ?.forEach { it.delete() }
    }

    private fun putText(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun putFile(zip: ZipOutputStream, name: String, file: File) {
        zip.putNextEntry(ZipEntry(name))
        FileInputStream(file).use { input -> input.copyTo(zip) }
        zip.closeEntry()
    }

    private fun rotate(file: File) {
        if (!file.isFile || file.length() < MAX_LOG_BYTES) return
        val old = File(file.parentFile, "\${file.name}.1")
        if (old.exists()) old.delete()
        file.renameTo(old)
    }

    private fun appendLine(file: File, level: String, message: String, throwable: Throwable? = null) {
        runCatching {
            synchronized(lock) {
                if (::runtimeLog.isInitialized && file.exists() && file.length() >= MAX_LOG_BYTES) {
                    rotate(file)
                }
                file.parentFile?.mkdirs()
                FileWriter(file, true).use { writer ->
                    writer.append(isoTimestamp(System.currentTimeMillis()))
                        .append(' ')
                        .append(level)
                        .append(' ')
                        .append(sanitize(message))
                        .append('\n')
                    if (throwable != null) {
                        PrintWriter(writer).use { printer ->
                            throwable.printStackTrace(printer)
                        }
                    }
                }
            }
        }.onFailure {
            Log.e("TypeDuckDiagnostics", "Unable to write diagnostic log", it)
        }
    }

    private fun sanitize(message: String): String {
        var result = message
        val patterns = listOf(
            Regex("(?i)(input\\s*=\\s*)[^,;]+"),
            Regex("(?i)(commit(?:Text)?\\s*=\\s*)[^,;]+"),
            Regex("(?i)(textToParse\\s*=\\s*)[^,;]+")
        )
        patterns.forEach { pattern ->
            result = result.replace(pattern) { match ->
                val prefix = match.groups[1]?.value ?: ""
                "\${prefix}<redacted>"
            }
        }
        return result
    }

    private fun reasonName(reason: Int): String =
        when (reason) {
            ApplicationExitInfo.REASON_UNKNOWN -> "UNKNOWN"
            ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
            ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
            ApplicationExitInfo.REASON_CRASH -> "CRASH"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
            ApplicationExitInfo.REASON_ANR -> "ANR"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
            ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
            ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
            ApplicationExitInfo.REASON_OTHER -> "OTHER"
            ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
            ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "PACKAGE_STATE_CHANGE"
            ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "PACKAGE_UPDATED"
            else -> "REASON_\$reason"
        }

    private fun isoTimestamp(timestamp: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date(timestamp))

    private fun fileTimestamp(timestamp: Long): String {
        val format = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        format.timeZone = TimeZone.getDefault()
        return format.format(Date(timestamp))
    }

    private class DiagnosticTree : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            if (priority < Log.DEBUG) return
            val level =
                when (priority) {
                    Log.VERBOSE -> "VERBOSE"
                    Log.DEBUG -> "DEBUG"
                    Log.INFO -> "INFO"
                    Log.WARN -> "WARN"
                    Log.ERROR -> "ERROR"
                    Log.ASSERT -> "ASSERT"
                    else -> priority.toString()
                }
            val prefix = if (tag.isNullOrBlank()) "" else "[\$tag] "
            appendLine(runtimeLog, level, prefix + message, t)
        }
    }
}
