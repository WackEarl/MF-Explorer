/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import java8.nio.file.Path
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.valueCompat

/**
 * Decides when the open tabs are written or read. While [isEnabled] is false nothing is saved or
 * restored, and anything already saved is deleted by [purgeIfDisabled].
 */
internal class OpenTabsPersistence(
    private val isEnabled: () -> Boolean,
    private val read: () -> OpenTabs?,
    private val write: (OpenTabs?) -> Unit
) {
    /** Saves the tabs, unless the setting is off, a picker is shown or there are no tabs. */
    fun save(paths: List<Path>, selectedIndex: Int, picking: Boolean): Boolean {
        if (!isEnabled() || picking || paths.isEmpty()) {
            return false
        }
        write(OpenTabs(paths, selectedIndex))
        return true
    }

    /** The tabs to restore, or null if the setting is off or nothing usable was saved. */
    fun restorable(): OpenTabs? {
        if (!isEnabled()) {
            return null
        }
        return read()?.takeIf { it.paths.isNotEmpty() }
    }

    /** Deletes the saved tabs if the setting is off, and writes nothing if there are none. */
    fun purgeIfDisabled(): Boolean {
        if (isEnabled() || read() == null) {
            return false
        }
        write(null)
        return true
    }

    companion object {
        val app = OpenTabsPersistence(
            { Settings.FILE_LIST_RESTORE_TABS.valueCompat },
            { Settings.FILE_LIST_OPEN_TABS.value },
            { Settings.FILE_LIST_OPEN_TABS.putValue(it) }
        )
    }
}

/** Settings touch the file-system provider, so this must run after it is installed. */
fun observeOpenTabsPersistence() {
    // The observer is called right away, so this also deletes tabs left behind by an older
    // version or an imported settings file while the setting is off.
    Settings.FILE_LIST_RESTORE_TABS.observeForever { OpenTabsPersistence.app.purgeIfDisabled() }
}
