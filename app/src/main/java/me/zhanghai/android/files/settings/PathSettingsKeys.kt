/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.settings

import android.content.SharedPreferences
import androidx.core.content.edit
import java8.nio.file.Path
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import me.zhanghai.android.files.provider.FileSystemProviders
import me.zhanghai.android.files.provider.common.UriAuthority
import me.zhanghai.android.files.provider.common.create
import me.zhanghai.android.files.provider.common.toByteString

/**
 * Preference key suffixes for per-folder view and sort settings.
 *
 * The suffix is SHA-256 (hex) of [Path.toUri], so two NFS shares with the same remote path do not
 * collide, and the folder list is not stored in the clear. The hash has no secret, so an exported
 * settings file works on another device without extra logic. Import of a 2.1.7 file still needs
 * [migrateLegacyKeys] because those entries used the raw [Path.toString].
 */
object PathSettingsKeys {
    private const val HEX_DIGITS = "0123456789abcdef"
    private val HASHED_SUFFIX = Regex("^[0-9a-f]{64}$")

    // Path.toString() of a remote share is only the remote path, so a value such as "/media"
    // cannot be mapped back to a host or export and is dropped. Local Android paths stay
    // recognizable by these prefixes.
    private val LOCAL_PATH_PREFIXES = listOf("/storage/", "/sdcard/", "/mnt/", "/data/", "/system/")

    fun canonicalUri(path: Path): String = path.toUri().toString()

    fun suffixFor(path: Path): String = sha256Hex(canonicalUri(path))

    fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
        val chars = CharArray(digest.size * 2)
        for (index in digest.indices) {
            val byte = digest[index].toInt() and 0xFF
            chars[index * 2] = HEX_DIGITS[byte ushr 4]
            chars[index * 2 + 1] = HEX_DIGITS[byte and 0x0F]
        }
        return String(chars)
    }

    fun isHashedSuffix(suffix: String): Boolean = HASHED_SUFFIX.matches(suffix)

    fun isMigratableLocalPath(path: String): Boolean {
        if (path == "/sdcard") {
            return true
        }
        return LOCAL_PATH_PREFIXES.any { path.startsWith(it) }
    }

    /**
     * Canonical URI for a legacy [Path.toString] preference suffix, or null to drop the entry.
     * NFS, SMB and other remote [Path.toString] values have no host, so they cannot be recovered.
     */
    fun canonicalUriForLegacySuffix(suffix: String): String? {
        if (isHashedSuffix(suffix)) {
            return null
        }
        if (suffix.contains("://")) {
            return canonicalUriFromLegacyUri(suffix)
        }
        if (isMigratableLocalPath(suffix)) {
            return linuxFileUri(suffix)
        }
        return null
    }

    /**
     * Rewrites plaintext path suffixes to SHA-256 keys and deletes entries that cannot be
     * recovered. Already hashed keys are left untouched. The operation is idempotent.
     *
     * [keyPrefixes] are the raw preference key names, such as `key_file_list_sort_options`.
     */
    fun migrateLegacyKeys(preferences: SharedPreferences, vararg keyPrefixes: String): Int {
        val prefixes = keyPrefixes.map { "${it}_" }
        var changed = 0
        val values = preferences.all
        preferences.edit(commit = true) {
            for ((key, value) in values) {
                val prefix = prefixes.firstOrNull { key.startsWith(it) } ?: continue
                val suffix = key.substring(prefix.length)
                if (suffix.isEmpty() || isHashedSuffix(suffix)) {
                    continue
                }
                val canonical = canonicalUriForLegacySuffix(suffix)
                remove(key)
                changed++
                if (canonical == null || value == null) {
                    continue
                }
                val newKey = prefix + sha256Hex(canonical)
                if (newKey == key || values.containsKey(newKey)) {
                    continue
                }
                putPreferenceValue(this, newKey, value)
            }
        }
        return changed
    }

    private fun canonicalUriFromLegacyUri(suffix: String): String? {
        val uri = try {
            URI(suffix)
        } catch (e: Exception) {
            return null
        }
        val scheme = uri.scheme ?: return null
        return try {
            FileSystemProviders[scheme].getPath(uri).toUri().toString()
        } catch (e: Exception) {
            suffix
        }
    }

    private fun linuxFileUri(absolutePath: String): String =
        URI::class.create("file", UriAuthority.EMPTY, absolutePath.toByteString(), null).toString()

    private fun putPreferenceValue(editor: SharedPreferences.Editor, key: String, value: Any) {
        when (value) {
            is String -> editor.putString(key, value)
            is Set<*> -> {
                @Suppress("UNCHECKED_CAST")
                editor.putStringSet(key, value as Set<String>)
            }
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Boolean -> editor.putBoolean(key, value)
        }
    }
}
