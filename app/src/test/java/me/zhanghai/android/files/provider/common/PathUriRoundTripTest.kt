/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.common

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.URI

class PathUriRoundTripTest {
    @Test
    fun reservedCharactersInFilePathRoundTrip() {
        for (path in listOf(
            "/storage/emulated/0/Urlaub 2026/Bäume #1?.jpg",
            "/storage/emulated/0/50%/a+b=c&d",
            "/storage/emulated/0/ampersand & hash # plus + percent %.png",
            "/storage/emulated/0/space in name.jpg"
        )) {
            val uri = URI::class.create("file", UriAuthority.EMPTY, path.toByteString(), null)
            assertEquals(path, uri.decodedPathByteString?.toString())
        }
    }
}
