/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.nfs.client

import android.util.Log
import me.zhanghai.android.files.provider.common.ByteString
import java.io.File
import java.io.IOException
import java.util.Base64

/**
 * Keeps pending deletes of partial NFS files across an app restart: server, path and size only.
 * One line per file, every field Base64 so paths may contain any byte.
 */
internal object PendingDeleteStore {
    private const val TAG = "PendingDeleteStore"
    private const val FIELDS = 7

    fun encode(entries: List<IncompleteFiles.Pending<Authority, ByteString>>): String =
        entries.joinToString("") { entry ->
            val authority = entry.group
            listOf(
                authority.host.toByteArray(),
                authority.nfsPort.toString().toByteArray(),
                authority.export.toByteArray(),
                authority.uid.toString().toByteArray(),
                authority.gid.toString().toByteArray(),
                entry.key.borrowBytes(),
                entry.maxSize.toString().toByteArray()
            ).joinToString(" ", postfix = "\n") { Base64.getEncoder().encodeToString(it) }
        }

    /** Skips lines it cannot read instead of failing. */
    fun decode(text: String): List<IncompleteFiles.Pending<Authority, ByteString>> =
        text.lineSequence().mapNotNull { line ->
            val fields = line.split(' ')
            if (fields.size != FIELDS) {
                return@mapNotNull null
            }
            try {
                val bytes = fields.map { Base64.getDecoder().decode(it) }
                val authority = Authority(
                    String(bytes[0]), String(bytes[1]).toInt(), String(bytes[2]),
                    String(bytes[3]).toInt(), String(bytes[4]).toInt()
                )
                IncompleteFiles.Pending(authority, ByteString(bytes[5]), String(bytes[6]).toLong())
            } catch (e: IllegalArgumentException) {
                null
            }
        }.toList()

    fun load(file: File): List<IncompleteFiles.Pending<Authority, ByteString>> =
        try {
            if (file.exists()) decode(file.readText()) else emptyList()
        } catch (e: IOException) {
            Log.w(TAG, "Cannot read $file", e)
            emptyList()
        }

    fun save(file: File, entries: List<IncompleteFiles.Pending<Authority, ByteString>>) {
        try {
            if (entries.isEmpty()) {
                file.delete()
                return
            }
            val temporary = File(file.path + ".tmp")
            temporary.writeText(encode(entries))
            if (!temporary.renameTo(file)) {
                throw IOException("Cannot rename $temporary")
            }
        } catch (e: IOException) {
            Log.w(TAG, "Cannot write $file", e)
        }
    }
}
