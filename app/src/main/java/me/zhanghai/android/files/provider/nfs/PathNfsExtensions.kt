/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.nfs

import java8.nio.file.Path
import me.zhanghai.android.files.provider.nfs.client.Authority

fun Authority.createNfsRootPath(): Path =
    NfsFileSystemProvider.getOrNewFileSystem(this).rootDirectory
