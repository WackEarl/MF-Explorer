/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.sftp.client

import android.util.Base64
import me.zhanghai.android.files.app.application
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.io.File
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Properties

/**
 * Remembers the first key for a host and rejects a later one that does not match.
 * The first connection is still trusted; a changed key is not.
 */
internal object HostKeys : HostKeyVerifier {
    private val file: File
        get() = File(application.filesDir, "sftp-host-keys.properties")

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        val fingerprint = fingerprint(key)
        val id = "$hostname:$port"
        synchronized(this) {
            val stored = read()
            val previous = stored.getProperty(id)
            if (previous == null) {
                stored.setProperty(id, fingerprint)
                write(stored)
                return true
            }
            return previous == fingerprint
        }
    }

    fun forget(hostname: String, port: Int) {
        val id = "$hostname:$port"
        synchronized(this) {
            val stored = read()
            if (stored.remove(id) != null) {
                write(stored)
            }
        }
    }

    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()

    private fun fingerprint(key: PublicKey): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.encoded)
        return Base64.encodeToString(digest, Base64.NO_WRAP)
    }

    private fun read(): Properties {
        val properties = Properties()
        val hostKeys = file
        if (hostKeys.isFile) {
            hostKeys.inputStream().use { properties.load(it) }
        }
        return properties
    }

    private fun write(properties: Properties) {
        file.outputStream().use { properties.store(it, null) }
    }
}
