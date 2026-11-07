/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import java8.nio.file.Path
import me.zhanghai.android.files.navigation.NavigationRootMapLiveData

class BreadcrumbLiveData : MediatorLiveData<BreadcrumbData>() {
    private var trailLiveData: LiveData<TrailData>? = null

    init {
        addSource(NavigationRootMapLiveData) { loadValue() }
    }

    fun setTrail(trailLiveData: LiveData<TrailData>) {
        this.trailLiveData?.let { removeSource(it) }
        this.trailLiveData = trailLiveData
        addSource(trailLiveData) { loadValue() }
    }

    private fun loadValue() {
        val trailData = trailLiveData?.value ?: return
        val navigationRootMap = NavigationRootMapLiveData.value ?: return
        val paths = mutableListOf<Path>()
        val nameProducers = mutableListOf<(Context) -> String>()
        var selectedIndex = trailData.currentIndex
        for (path in trailData.trail) {
            val navigationRoot = navigationRootMap[path]
            val itemCount = nameProducers.size
            if (navigationRoot != null && selectedIndex >= itemCount) {
                selectedIndex -= itemCount
                paths.clear()
                paths.add(navigationRoot.path)
                nameProducers.clear()
                nameProducers.add { navigationRoot.getName(it) }
            } else {
                paths.add(path)
                nameProducers.add { path.name }
            }
        }
        value = BreadcrumbData(paths, nameProducers, selectedIndex)
    }
}
