/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filejob

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

data class FileJobSnapshot(
    val id: Int,
    val title: String,
    val text: String?,
    val max: Int,
    val progress: Int,
    val indeterminate: Boolean,
    val cancellable: Boolean
)

object FileJobProgress {
    private val lock = Any()
    private val jobs = LinkedHashMap<Int, FileJobSnapshot>()
    private val liveData = MutableLiveData<List<FileJobSnapshot>>(emptyList())

    val snapshots: LiveData<List<FileJobSnapshot>> = liveData

    fun upsert(
        id: Int,
        title: CharSequence,
        text: CharSequence?,
        max: Int,
        progress: Int,
        indeterminate: Boolean,
        cancellable: Boolean
    ) {
        synchronized(lock) {
            jobs[id] = FileJobSnapshot(
                id, title.toString(), text?.toString(), max, progress, indeterminate, cancellable
            )
            liveData.postValue(jobs.values.toList())
        }
    }

    fun remove(id: Int) {
        synchronized(lock) {
            if (jobs.remove(id) == null) {
                return
            }
            liveData.postValue(jobs.values.toList())
        }
    }

    fun current(): List<FileJobSnapshot> = synchronized(lock) { jobs.values.toList() }
}
