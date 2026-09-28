package hk.eduhk.typeduck.data

import com.blankj.utilcode.util.ResourceUtils
import hk.eduhk.typeduck.util.Const
import timber.log.Timber
import java.io.File

object DataManager {
    private val prefs get() = AppPrefs.defaultInstance()
    @JvmStatic
    val sharedDataDir = File(prefs.profile.sharedDataDir)
    @JvmStatic
    val userDataDir = File(prefs.profile.userDataDir)
    val customDefault = File(sharedDataDir, "default.custom.yaml")
    val commonPatch = File(sharedDataDir, "common.custom.yaml")
    @JvmStatic
    val buildDir = File(userDataDir, "build")

    sealed class Diff {
        object New : Diff()
        object Update : Diff()
        object Keep : Diff()
    }

    @JvmStatic
    fun getDataDir(child: String = ""): String {
        return if (File(prefs.profile.sharedDataDir, child).exists()) {
            File(prefs.profile.sharedDataDir, child).absolutePath
        } else {
            File(prefs.profile.userDataDir, child).absolutePath
        }
    }

    private fun diff(old: String, new: String): Diff {
        return when {
            old.isBlank() -> Diff.New
            !new.contentEquals(old) -> Diff.Update
            else -> Diff.Keep
        }
    }

    private val assetsBuildHash = File(sharedDataDir, ".typeduck-assets-git-hash")

    @JvmStatic
    fun sync(): Boolean {
        val newHash = Const.buildGitHash
        val oldHash = runCatching {
            if (assetsBuildHash.isFile) assetsBuildHash.readText().trim() else ""
        }.getOrDefault("")
        val changed = oldHash != newHash

        Timber.d("Rime assets changed=%s (old=%s, new=%s)", changed, oldHash, newHash)
        if (changed) {
            sharedDataDir.mkdirs()
            ResourceUtils.copyFileFromAssets("rime", sharedDataDir.absolutePath)

            // TypeDuck's custom RimeStartQuick() deliberately skips workspace_update.
            // Without clearing generated build files, an APK update can keep executing a
            // stale compiled dictionary even after corrected source YAML is copied.
            if (buildDir.exists()) {
                Timber.i("Clearing stale compiled Rime build cache after app update")
                buildDir.deleteRecursively()
            }

            assetsBuildHash.parentFile?.mkdirs()
            assetsBuildHash.writeText(newHash)
        }

        // FIXME：缺失 default.custom.yaml 会导致方案列表为空
        if (!customDefault.exists()) {
            Timber.d("Creating empty default.custom.yaml ...")
            customDefault.createNewFile()
        }
        // Don't combine candidates
        Timber.d("Creating common.custom.yaml ...")
        commonPatch.writeText(
            """
            |patch:
            |  __patch:
            |    - common:/separate_candidates
            |    - common:/show_full_code
            """.trimMargin()
        )

        Timber.i("Synced!")
        return changed
    }
}
