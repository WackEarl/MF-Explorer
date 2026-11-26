/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.image

import android.content.Context
import me.zhanghai.android.files.R
import me.zhanghai.android.files.provider.nfs.client.ClientException
import me.zhanghai.android.files.util.findCauseByClass
import me.zhanghai.android.files.util.toUserMessage

/**
 * The text of the viewer's error page: the translated reason of an NFS error, e.g. that the
 * server is unreachable, and a generic message for anything else. The exception itself belongs
 * in the log.
 */
internal fun Throwable.toImageErrorMessage(context: Context): String =
    if (findCauseByClass<ClientException>() != null) {
        toUserMessage()
    } else {
        context.getString(R.string.image_viewer_error)
    }
