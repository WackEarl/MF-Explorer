/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.settings

import android.os.Build
import android.os.Bundle
import android.os.Process
import androidx.activity.result.contract.ActivityResultContracts
import androidx.preference.Preference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import me.zhanghai.android.files.R
import me.zhanghai.android.files.settings.SettingsTransfer.TransferException
import me.zhanghai.android.files.theme.custom.CustomThemeHelper
import me.zhanghai.android.files.theme.custom.ThemeColor
import me.zhanghai.android.files.theme.night.NightMode
import me.zhanghai.android.files.theme.night.NightModeHelper
import me.zhanghai.android.files.ui.PreferenceFragmentCompat
import me.zhanghai.android.files.util.showToast

class SettingsPreferenceFragment : PreferenceFragmentCompat() {
    private lateinit var localePreference: LocalePreference
    private var pendingImport: String? = null

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) {
            return@registerForActivityResult
        }
        try {
            SettingsTransfer.exportTo(uri)
            showToast(R.string.settings_export_success)
        } catch (e: Exception) {
            showToast(e.toString())
        }
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            return@registerForActivityResult
        }
        val text = try {
            SettingsTransfer.read(uri)
        } catch (e: TransferException) {
            showToast(transferError(e))
            return@registerForActivityResult
        } catch (e: Exception) {
            showToast(e.toString())
            return@registerForActivityResult
        }
        pendingImport = text
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(R.string.settings_import_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val body = pendingImport ?: return@setPositiveButton
                pendingImport = null
                try {
                    SettingsTransfer.importFrom(body)
                } catch (e: TransferException) {
                    showToast(transferError(e))
                    return@setPositiveButton
                } catch (e: Exception) {
                    showToast(e.toString())
                    return@setPositiveButton
                }
                Process.killProcess(Process.myPid())
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> pendingImport = null }
            .setOnCancelListener { pendingImport = null }
            .show()
    }

    override fun onCreatePreferencesFix(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.settings)

        localePreference = preferenceScreen.findPreference(getString(R.string.pref_key_locale))!!
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            localePreference.setApplicationLocalesPre33 = { locales ->
                val activity = requireActivity() as SettingsActivity
                activity.setApplicationLocalesPre33(locales)
            }
        }
        findPreference<Preference>(getString(R.string.pref_key_settings_export))!!
            .setOnPreferenceClickListener {
                MaterialAlertDialogBuilder(requireContext())
                    .setMessage(R.string.settings_export_password_warning)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        exportLauncher.launch("mf-explorer-settings.json")
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                true
            }
        findPreference<Preference>(getString(R.string.pref_key_settings_import))!!
            .setOnPreferenceClickListener {
                importLauncher.launch(arrayOf("application/json", "application/octet-stream", "*/*"))
                true
            }
    }

    private fun transferError(exception: TransferException): Int =
        when (exception.kind) {
            TransferException.Kind.INVALID -> R.string.settings_transfer_invalid
            TransferException.Kind.UNSUPPORTED -> R.string.settings_transfer_unsupported
        }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        val viewLifecycleOwner = viewLifecycleOwner
        // The following may end up passing the same lambda instance to the observer because it has
        // no capture, and result in an IllegalArgumentException "Cannot add the same observer with
        // different lifecycles" if activity is finished and instantly started again. To work around
        // this, always use an instance method reference.
        // https://stackoverflow.com/a/27524543
        //Settings.THEME_COLOR.observe(viewLifecycleOwner) { CustomThemeHelper.sync() }
        //Settings.MATERIAL_DESIGN_3.observe(viewLifecycleOwner) { CustomThemeHelper.sync() }
        //Settings.NIGHT_MODE.observe(viewLifecycleOwner) { NightModeHelper.sync() }
        //Settings.BLACK_NIGHT_MODE.observe(viewLifecycleOwner) { CustomThemeHelper.sync() }
        Settings.THEME_COLOR.observe(viewLifecycleOwner, this::onThemeColorChanged)
        Settings.MATERIAL_DESIGN_3.observe(viewLifecycleOwner, this::onMaterialDesign3Changed)
        Settings.NIGHT_MODE.observe(viewLifecycleOwner, this::onNightModeChanged)
        Settings.BLACK_NIGHT_MODE.observe(viewLifecycleOwner, this::onBlackNightModeChanged)
    }

    private fun onThemeColorChanged(themeColor: ThemeColor) {
        CustomThemeHelper.sync()
    }

    private fun onMaterialDesign3Changed(isMaterialDesign3: Boolean) {
        CustomThemeHelper.sync()
    }

    private fun onNightModeChanged(nightMode: NightMode) {
        NightModeHelper.sync()
    }

    private fun onBlackNightModeChanged(blackNightMode: Boolean) {
        CustomThemeHelper.sync()
    }

    override fun onResume() {
        super.onResume()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Refresh locale preference summary because we aren't notified for an external change
            // between system default and the locale that's the current system default.
            localePreference.notifyChanged()
        }
    }
}
