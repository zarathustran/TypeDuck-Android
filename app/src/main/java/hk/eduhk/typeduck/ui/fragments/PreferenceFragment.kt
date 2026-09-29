package hk.eduhk.typeduck.ui.fragments

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.get
import com.blankj.utilcode.util.ResourceUtils
import hk.eduhk.typeduck.R
import hk.eduhk.typeduck.core.Rime
import hk.eduhk.typeduck.data.AppPrefs
import hk.eduhk.typeduck.data.DataManager
import hk.eduhk.typeduck.ime.core.Trime
import hk.eduhk.typeduck.ime.text.Language
import hk.eduhk.typeduck.ime.text.Size
import hk.eduhk.typeduck.ui.components.PaddingPreferenceFragment
import hk.eduhk.typeduck.ui.components.TestIMEPreference
import hk.eduhk.typeduck.util.DiagnosticLogger
import hk.eduhk.typeduck.util.withLoadingDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.EnumSet

class PreferenceFragment :
    PaddingPreferenceFragment(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var languageNames: Array<String>
    private lateinit var sizeNames: Array<String>

    var testIMEPreference: TestIMEPreference? = null

    override fun onResume() {
        super.onResume()
        preferenceScreen.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
        testIMEPreference?.blur()
    }

    override fun onPause() {
        super.onPause()
        preferenceScreen.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
        testIMEPreference?.blur()
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preference, rootKey)
        languageNames = resources.getStringArray(R.array.pref_languages)
        sizeNames = resources.getStringArray(R.array.pref_sizes)
        with(preferenceScreen) {
            get<Preference>("pref_about")?.setOnPreferenceClickListener {
                findNavController().navigate(R.id.action_preferenceFragment_to_aboutFragment)
                true
            }
            get<Preference>("pref_refresh")?.setOnPreferenceClickListener {
                lifecycleScope.withLoadingDialog(context) {
                    withContext(Dispatchers.IO) {
                        ResourceUtils.copyFileFromAssets("rime", DataManager.sharedDataDir.absolutePath)
                        Rime.syncRimeUserData()
                        Rime.deployRime()
                    }
                }
                true
            }
            get<Preference>("pref_export_diagnostics")?.setOnPreferenceClickListener {
                val fragmentContext = context ?: return@setOnPreferenceClickListener false
                lifecycleScope.launch {
                    try {
                        val bundle = withContext(Dispatchers.IO) {
                            DiagnosticLogger.exportBundle(fragmentContext)
                        }
                        Toast.makeText(
                            fragmentContext,
                            getString(R.string.diagnostics_saved, bundle.fileName),
                            Toast.LENGTH_LONG
                        ).show()
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "application/zip"
                            putExtra(Intent.EXTRA_STREAM, bundle.uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(
                            Intent.createChooser(
                                shareIntent,
                                getString(R.string.pref_export_diagnostics)
                            )
                        )
                    } catch (error: Exception) {
                        DiagnosticLogger.event(
                            "diagnostic export failed: %s",
                            error.javaClass.name
                        )
                        Toast.makeText(
                            fragmentContext,
                            R.string.diagnostics_export_failed,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                true
            }
            sharedPreferences?.run {
                val typeDuck = AppPrefs(this).typeDuck
                val displayLanguages = typeDuck.displayLanguages
                val mainLanguage = typeDuck.mainLanguage
                val candidateFontSize = typeDuck.candidateFontSize
                val candidateGap = typeDuck.candidateGap
                val languageValues = Language.values().map { it.name }.toTypedArray()
                get<MultiSelectListPreference>(AppPrefs.TypeDuck.DISPLAY_LANGUAGES)?.also {
                    it.entries = languageNames
                    it.entryValues = languageValues
                    it.values = displayLanguages.map { it.name }.toSet()
                    it.summary = displayLanguages.joinToString { languageNames[it.ordinal] }
                    it.setOnPreferenceChangeListener { _, value ->
                        !(value as Set<*>).isEmpty().also { empty ->
                            if (empty) {
                                typeDuck.displayLanguages = EnumSet.of(mainLanguage)
                                it.values = setOf(mainLanguage.name)
                            }
                        }
                    }
                }
                get<ListPreference>(AppPrefs.TypeDuck.MAIN_LANGUAGE)?.also {
                    it.entries = languageNames
                    it.entryValues = languageValues
                    it.value = mainLanguage.name
                    it.summary = languageNames[mainLanguage.ordinal]
                }
                val sizeValues = Size.values().map { it.name }.toTypedArray()
                get<ListPreference>(AppPrefs.TypeDuck.CANDIDATE_FONT_SIZE)?.also {
                    it.entries = sizeNames
                    it.entryValues = sizeValues
                    it.value = candidateFontSize.name
                    it.summary = sizeNames[candidateFontSize.ordinal]
                }
                get<ListPreference>(AppPrefs.TypeDuck.CANDIDATE_GAP)?.also {
                    it.entries = sizeNames
                    it.entryValues = sizeValues
                    it.value = candidateGap.name
                    it.summary = sizeNames[candidateGap.ordinal]
                }
            }
            testIMEPreference = get("pref_test_ime")
        }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (sharedPreferences == null) return
        val typeDuck = AppPrefs.initDefault(sharedPreferences).typeDuck
        with(preferenceScreen) {
            when (key) {
                AppPrefs.TypeDuck.INTERFACE_LANGUAGE -> {
                    activity?.apply {
                        finish()
                        startActivity(intent)
                    }
                }
                AppPrefs.TypeDuck.DISPLAY_LANGUAGES -> {
                    val displayLanguages = typeDuck.displayLanguages
                    var mainLanguage = typeDuck.mainLanguage
                    get<MultiSelectListPreference>(AppPrefs.TypeDuck.DISPLAY_LANGUAGES)?.also {
                        it.summary = displayLanguages.joinToString { languageNames[it.ordinal] }
                    }
                    if (mainLanguage !in displayLanguages) {
                        get<ListPreference>(AppPrefs.TypeDuck.MAIN_LANGUAGE)?.also {
                            mainLanguage = displayLanguages.first()
                            typeDuck.mainLanguage = mainLanguage
                            it.value = mainLanguage.name
                            it.summary = languageNames[mainLanguage.ordinal]
                        }
                    }
                    true
                }
                AppPrefs.TypeDuck.MAIN_LANGUAGE -> {
                    val displayLanguages = typeDuck.displayLanguages
                    val mainLanguage = typeDuck.mainLanguage
                    get<ListPreference>(AppPrefs.TypeDuck.MAIN_LANGUAGE)?.also {
                        it.summary = languageNames[mainLanguage.ordinal]
                    }
                    if (mainLanguage !in displayLanguages) {
                        get<MultiSelectListPreference>(AppPrefs.TypeDuck.DISPLAY_LANGUAGES)?.also {
                            displayLanguages.add(mainLanguage)
                            typeDuck.displayLanguages = displayLanguages
                            it.values = displayLanguages.map { it.name }.toSet()
                            it.summary = displayLanguages.joinToString { languageNames[it.ordinal] }
                        }
                    }
                    true
                }
                AppPrefs.TypeDuck.CANDIDATE_FONT_SIZE -> {
                    get<ListPreference>(AppPrefs.TypeDuck.CANDIDATE_FONT_SIZE)?.also {
                        it.summary = sizeNames[typeDuck.candidateFontSize.ordinal]
                    }
                }
                AppPrefs.TypeDuck.CANDIDATE_GAP -> {
                    get<ListPreference>(AppPrefs.TypeDuck.CANDIDATE_GAP)?.also {
                        it.summary = sizeNames[typeDuck.candidateGap.ordinal]
                    }
                }
                else -> {}
            }
        }
        Trime.getServiceOrNull()?.initKeyboard()
    }
}
