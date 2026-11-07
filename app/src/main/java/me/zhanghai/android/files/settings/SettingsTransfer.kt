/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.settings

import android.content.SharedPreferences
import android.net.Uri
import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.compat.PreferenceManagerCompat
import me.zhanghai.android.files.provider.sftp.client.HostKeys
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

object SettingsTransfer {
    private const val FORMAT = 1
    private const val MAX_BYTES = 8 * 1024 * 1024

    fun exportTo(uri: Uri) {
        val json = JSONObject()
            .put("format", FORMAT)
            .put("preferences", encodePreferences(defaultPreferences()))
            .put("pathPreferences", encodePreferences(pathPreferences()))
            .put("sftpHostKeys", HostKeys.exportText() ?: JSONObject.NULL)
        val temporary = File(application.cacheDir, "settings-export.json")
        temporary.writeText(json.toString())
        try {
            application.contentResolver.openOutputStream(uri)?.use { output ->
                temporary.inputStream().use { it.copyTo(output) }
            } ?: throw IOException("Could not open the settings file")
        } finally {
            temporary.delete()
        }
    }

    fun read(uri: Uri): String {
        val text = application.contentResolver.openInputStream(uri)?.use { input ->
            String(input.readAtMost(MAX_BYTES), Charsets.UTF_8)
        } ?: throw IOException("Could not open the settings file")
        validate(text)
        return text
    }

    fun importFrom(text: String) {
        val json = validate(text)
        putAll(defaultPreferences(), decodePreferences(json.getJSONObject("preferences")))
        putAll(pathPreferences(), decodePreferences(json.getJSONObject("pathPreferences")))
        HostKeys.replaceText(
            if (json.isNull("sftpHostKeys")) null else json.getString("sftpHostKeys")
        )
    }

    private fun validate(text: String): JSONObject {
        val json = parse(text)
        try {
            decodePreferences(json.getJSONObject("preferences"))
            decodePreferences(json.getJSONObject("pathPreferences"))
            if (!json.isNull("sftpHostKeys")) {
                json.getString("sftpHostKeys")
            }
        } catch (e: TransferException) {
            throw e
        } catch (e: Exception) {
            throw TransferException(TransferException.Kind.INVALID)
        }
        return json
    }

    private fun parse(text: String): JSONObject {
        val json = try {
            JSONObject(text)
        } catch (e: JSONException) {
            throw TransferException(TransferException.Kind.INVALID)
        }
        val format = try {
            json.getInt("format")
        } catch (e: JSONException) {
            throw TransferException(TransferException.Kind.INVALID)
        }
        if (format > FORMAT) {
            throw TransferException(TransferException.Kind.UNSUPPORTED)
        }
        if (format != FORMAT || !json.has("preferences") || !json.has("pathPreferences")
            || !json.has("sftpHostKeys")
        ) {
            throw TransferException(TransferException.Kind.INVALID)
        }
        return json
    }

    private fun encodePreferences(preferences: SharedPreferences): JSONObject {
        val encoded = JSONObject()
        for ((key, value) in preferences.all) {
            if (value != null) {
                encoded.put(key, encodeValue(value))
            }
        }
        return encoded
    }

    private fun encodeValue(value: Any): JSONObject {
        val encoded = JSONObject()
        when (value) {
            is String -> {
                encoded.put("t", "string")
                encoded.put("v", value)
            }
            is Set<*> -> {
                encoded.put("t", "stringSet")
                encoded.put("v", org.json.JSONArray(value.map { it as String }))
            }
            is Int -> {
                encoded.put("t", "int")
                encoded.put("v", value)
            }
            is Long -> {
                encoded.put("t", "long")
                encoded.put("v", value.toString())
            }
            is Float -> {
                encoded.put("t", "float")
                encoded.put("v", value.toString())
            }
            is Boolean -> {
                encoded.put("t", "boolean")
                encoded.put("v", value)
            }
            else -> throw IllegalStateException("Cannot export ${value.javaClass.name}")
        }
        return encoded
    }

    private fun decodePreferences(encoded: JSONObject): Map<String, Any> {
        val values = LinkedHashMap<String, Any>(encoded.length())
        val keys = encoded.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val entry = try {
                encoded.getJSONObject(key)
            } catch (e: JSONException) {
                throw TransferException(TransferException.Kind.INVALID)
            }
            values[key] = decodeValue(entry)
        }
        return values
    }

    private fun decodeValue(entry: JSONObject): Any =
        try {
            when (entry.getString("t")) {
                "string" -> entry.getString("v")
                "stringSet" -> {
                    val array = entry.getJSONArray("v")
                    val values = LinkedHashSet<String>(array.length())
                    for (index in 0 until array.length()) {
                        values.add(array.getString(index))
                    }
                    values
                }
                "int" -> entry.getInt("v")
                "long" -> entry.getString("v").toLong()
                "float" -> entry.getString("v").toFloat()
                "boolean" -> entry.getBoolean("v")
                else -> throw TransferException(TransferException.Kind.INVALID)
            }
        } catch (e: TransferException) {
            throw e
        } catch (e: Exception) {
            throw TransferException(TransferException.Kind.INVALID)
        }

    private fun putAll(preferences: SharedPreferences, values: Map<String, Any>) {
        val editor = preferences.edit()
        editor.clear()
        for ((key, value) in values) {
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
                else -> throw TransferException(TransferException.Kind.INVALID)
            }
        }
        if (!editor.commit()) {
            throw IOException("Could not store settings")
        }
    }

    private fun defaultPreferences(): SharedPreferences =
        application.getSharedPreferences(
            PreferenceManagerCompat.getDefaultSharedPreferencesName(application),
            PreferenceManagerCompat.defaultSharedPreferencesMode
        )

    private fun pathPreferences(): SharedPreferences =
        application.getSharedPreferences(
            "${PreferenceManagerCompat.getDefaultSharedPreferencesName(application)}_path",
            PreferenceManagerCompat.defaultSharedPreferencesMode
        )

    private fun InputStream.readAtMost(maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) {
                break
            }
            total += count
            if (total > maxBytes) {
                throw TransferException(TransferException.Kind.INVALID)
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    class TransferException(val kind: Kind) : Exception() {
        enum class Kind {
            INVALID,
            UNSUPPORTED
        }
    }
}
