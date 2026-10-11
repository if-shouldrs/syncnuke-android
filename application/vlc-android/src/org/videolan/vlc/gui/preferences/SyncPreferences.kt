package org.videolan.vlc.gui.preferences

import android.text.InputType
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import org.videolan.vlc.sync.defaultSyncUsername

/** Apply the same input defaults and validation wherever synchronization is configured. */
internal fun PreferenceFragmentCompat.configureSyncPreferences() {
    findPreference<EditTextPreference>("syncUsername")?.apply {
        if (text == null) text = defaultSyncUsername(requireContext())
    }
    findPreference<EditTextPreference>("syncPassword")?.setOnBindEditTextListener {
        it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    }
    findPreference<EditTextPreference>("syncPort")?.apply {
        setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_NUMBER }
        setOnPreferenceChangeListener { _, value ->
            (value as? String)?.toIntOrNull()?.let { it in 1..65535 } == true
        }
    }
}
