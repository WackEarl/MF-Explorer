/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.os.Parcelable
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import java8.nio.file.Path
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.filelist.FileSortOptions.By
import me.zhanghai.android.files.filelist.FileSortOptions.Order
import me.zhanghai.android.files.provider.archive.archiveRefresh
import me.zhanghai.android.files.provider.archive.isArchivePath
import me.zhanghai.android.files.util.CloseableLiveData
import me.zhanghai.android.files.util.Stateful
import me.zhanghai.android.files.util.Success
import me.zhanghai.android.files.util.valueCompat
import java.io.Closeable

// TODO: Use SavedStateHandle to save state.
class FileListViewModel : ViewModel() {
    private val tabs = mutableListOf<FileTab>()
    private var activeIndex = -1
    private var nextTabId = 1L

    private val active: FileTab
        get() = tabs[activeIndex]
    val activeTabId: Long
        get() = active.id
    val canCloseTab: Boolean
        get() = pickOptions == null && tabs.size > 1

    private val _tabsLiveData = MutableLiveData<FileTabs>()
    val tabsLiveData: LiveData<FileTabs> = _tabsLiveData

    private var trailSource: LiveData<TrailData>? = null
    private val currentPathMediator = MediatorLiveData<Path>()
    val currentPathLiveData: LiveData<Path> = currentPathMediator
    val currentPath: Path
        get() = currentPathMediator.valueCompat
    val hasTrail: Boolean
        get() = activeIndex >= 0 && active.trailLiveData.value != null
    val pendingState: Parcelable?
        get() = active.trailLiveData.valueCompat.pendingState

    fun navigateTo(lastState: Parcelable, path: Path) {
        active.trailLiveData.navigateTo(lastState, path)
        publishTabs()
    }

    fun resetTo(path: Path) {
        if (tabs.isEmpty()) {
            val tab = FileTab(nextTabId++)
            tabs.add(tab)
            activeIndex = 0
            tab.trailLiveData.resetTo(path)
            rebind()
        } else {
            active.trailLiveData.resetTo(path)
            publishTabs()
        }
    }

    fun restoreTabs(paths: List<Path>, selectedIndex: Int) {
        if (paths.isEmpty() || tabs.isNotEmpty()) {
            return
        }
        val limited = paths.take(MAX_TABS)
        for (path in limited) {
            val tab = FileTab(nextTabId++)
            tab.trailLiveData.resetTo(path)
            tabs.add(tab)
        }
        activeIndex = selectedIndex.coerceIn(0, tabs.lastIndex)
        rebind()
    }

    fun focusPath(path: Path) {
        val existing = tabs.indexOfFirst { it.trailLiveData.value?.currentPath == path }
        if (existing >= 0) {
            selectTab(tabs[existing].id, null)
            return
        }
        if (!openTab(path, null)) {
            resetTo(path)
        }
    }

    fun tabPath(id: Long): Path? {
        val index = tabs.indexOfFirst { it.id == id }
        if (index < 0) {
            return null
        }
        return tabs[index].trailLiveData.value?.currentPath
    }

    fun navigateUp(): Boolean {
        if (!active.trailLiveData.navigateUp()) {
            return false
        }
        publishTabs()
        return true
    }

    fun openTab(path: Path, scrollState: Parcelable?): Boolean {
        if (pickOptions != null || tabs.size >= MAX_TABS) {
            return false
        }
        saveLeavingScroll(scrollState)
        val tab = FileTab(nextTabId++)
        tab.trailLiveData.resetTo(path)
        tabs.add(tab)
        activeIndex = tabs.lastIndex
        rebind()
        return true
    }

    fun selectTab(id: Long, scrollState: Parcelable?) {
        val index = tabs.indexOfFirst { it.id == id }
        if (index < 0 || index == activeIndex) {
            return
        }
        saveLeavingScroll(scrollState)
        activeIndex = index
        rebind()
    }

    private fun saveLeavingScroll(scrollState: Parcelable?) {
        if (scrollState == null) {
            return
        }
        val trail = active.trailLiveData.value ?: return
        // A load still has to restore this position. The list on screen is empty until then,
        // so the layout state would overwrite it with the top.
        if (_fileListLiveData.value !is Success && trail.hasPendingScroll) {
            return
        }
        active.trailLiveData.saveScrollState(scrollState)
    }

    fun closeTab(id: Long): Boolean {
        if (tabs.size <= 1) {
            return false
        }
        val index = tabs.indexOfFirst { it.id == id }
        if (index < 0) {
            return false
        }
        val closingActive = index == activeIndex
        val removed = tabs.removeAt(index)
        if (closingActive) {
            activeIndex = index.coerceAtMost(tabs.lastIndex)
            rebind()
        } else {
            if (index < activeIndex) {
                activeIndex--
            }
            publishTabs()
        }
        removed.closeList()
        return true
    }

    private var searchSource: LiveData<SearchState>? = null
    private val searchStateMediator = MediatorLiveData<SearchState>()
    val searchStateLiveData: LiveData<SearchState> = searchStateMediator
    val searchState: SearchState
        get() = searchStateMediator.valueCompat

    fun search(query: String) {
        val liveData = active.searchStateLiveData
        val searchState = liveData.valueCompat
        if (searchState.isSearching && searchState.query == query) {
            return
        }
        val directory = if (searchState.isSearching) {
            searchState.directory ?: currentPath
        } else {
            displayedDirectory() ?: currentPath
        }
        liveData.value = SearchState(true, query, directory)
    }

    private fun displayedDirectory(): Path? {
        val files = (_fileListLiveData.value as? Success)?.value ?: return null
        return files.mapNotNull { it.path.parent }.distinct().singleOrNull()
    }

    fun stopSearching() {
        val liveData = active.searchStateLiveData
        val searchState = liveData.valueCompat
        if (!searchState.isSearching) {
            return
        }
        liveData.value = SearchState(false, "")
    }

    private val _fileListLiveData =
        FileListSwitchMapLiveData(currentPathLiveData, searchStateLiveData)
    val fileListLiveData: LiveData<Stateful<List<FileItem>>>
        get() = _fileListLiveData
    val fileListStateful: Stateful<List<FileItem>>
        get() = _fileListLiveData.valueCompat

    fun reload() {
        val path = currentPath
        if (path.isArchivePath) {
            path.archiveRefresh()
        }
        _fileListLiveData.reload()
    }

    private var searchExpandedSource: LiveData<Boolean>? = null
    private val searchExpandedMediator = MediatorLiveData<Boolean>()
    val searchViewExpandedLiveData: LiveData<Boolean> = searchExpandedMediator
    var isSearchViewExpanded: Boolean
        get() = searchExpandedMediator.valueCompat
        set(value) {
            val liveData = active.searchViewExpandedLiveData
            if (liveData.valueCompat == value) {
                return
            }
            liveData.value = value
        }

    private var searchQuerySource: LiveData<String>? = null
    private val searchQueryMediator = MediatorLiveData<String>()
    var searchViewQuery: String
        get() = active.searchViewQueryLiveData.valueCompat
        set(value) {
            val liveData = active.searchViewQueryLiveData
            if (liveData.valueCompat == value) {
                return
            }
            liveData.value = value
        }

    private val _breadcrumbLiveData = BreadcrumbLiveData()
    val breadcrumbLiveData: LiveData<BreadcrumbData> = _breadcrumbLiveData
    val canNavigateUpBreadcrumb: Boolean
        get() = _breadcrumbLiveData.valueCompat.selectedIndex > 0

    private val _viewTypeLiveData = FileViewTypeLiveData(currentPathLiveData)
    val viewTypeLiveData: LiveData<FileViewType> = _viewTypeLiveData
    var viewType: FileViewType
        get() = _viewTypeLiveData.valueCompat
        set(value) {
            _viewTypeLiveData.putValue(value)
        }

    private val _sortOptionsLiveData = FileSortOptionsLiveData(currentPathLiveData)
    val sortOptionsLiveData: LiveData<FileSortOptions> = _sortOptionsLiveData
    val sortOptions: FileSortOptions
        get() = _sortOptionsLiveData.valueCompat

    fun setSortBy(by: By) = _sortOptionsLiveData.putBy(by)

    fun setSortOrder(order: Order) = _sortOptionsLiveData.putOrder(order)

    fun setSortDirectoriesFirst(isDirectoriesFirst: Boolean) =
        _sortOptionsLiveData.putIsDirectoriesFirst(isDirectoriesFirst)

    private val _viewSortPathSpecificLiveData =
        FileViewSortPathSpecificLiveData(currentPathLiveData)
    val viewSortPathSpecificLiveData: LiveData<Boolean>
        get() = _viewSortPathSpecificLiveData
    var isViewSortPathSpecific: Boolean
        get() = _viewSortPathSpecificLiveData.valueCompat
        set(value) {
            _viewSortPathSpecificLiveData.putValue(value)
        }

    private val _pickOptionsLiveData = MutableLiveData<PickOptions?>()
    val pickOptionsLiveData: LiveData<PickOptions?>
        get() = _pickOptionsLiveData
    var pickOptions: PickOptions?
        get() = _pickOptionsLiveData.value
        set(value) {
            _pickOptionsLiveData.value = value
        }

    var isCreateFileNameEditInitialized: Boolean = false

    private var selectedSource: LiveData<FileItemSet>? = null
    private val selectedMediator = MediatorLiveData<FileItemSet>()
    val selectedFilesLiveData: LiveData<FileItemSet> = selectedMediator
    val selectedFiles: FileItemSet
        get() = selectedMediator.valueCompat

    fun selectFile(file: FileItem, selected: Boolean) {
        selectFiles(fileItemSetOf(file), selected)
    }

    fun selectFiles(files: FileItemSet, selected: Boolean) {
        val selectedFiles = active.selectedFilesLiveData
        val selectedFileSet = selectedFiles.valueCompat
        if (selectedFileSet === files) {
            if (!selected && selectedFileSet.isNotEmpty()) {
                selectedFileSet.clear()
                selectedFiles.value = selectedFileSet
            }
            return
        }
        var changed = false
        for (file in files) {
            changed = changed or if (selected) {
                selectedFileSet.add(file)
            } else {
                selectedFileSet.remove(file)
            }
        }
        if (changed) {
            selectedFiles.value = selectedFileSet
        }
    }

    fun replaceSelectedFiles(files: FileItemSet) {
        val selectedFiles = active.selectedFilesLiveData
        val selectedFileSet = selectedFiles.valueCompat
        if (selectedFileSet == files) {
            return
        }
        selectedFileSet.clear()
        selectedFileSet.addAll(files)
        selectedFiles.value = selectedFileSet
    }

    fun clearSelectedFiles() {
        val selectedFiles = active.selectedFilesLiveData
        val selectedFileSet = selectedFiles.valueCompat
        if (selectedFileSet.isEmpty()) {
            return
        }
        selectedFileSet.clear()
        selectedFiles.value = selectedFileSet
    }

    val pasteStateLiveData: LiveData<PasteState> = _pasteStateLiveData
    val pasteState: PasteState
        get() = _pasteStateLiveData.valueCompat

    fun addToPasteState(copy: Boolean, files: FileItemSet) {
        val pasteState = _pasteStateLiveData.valueCompat
        var changed = false
        if (pasteState.copy != copy) {
            changed = pasteState.files.isNotEmpty()
            pasteState.files.clear()
            pasteState.copy = copy
        }
        changed = changed or pasteState.files.addAll(files)
        if (changed) {
            _pasteStateLiveData.value = pasteState
        }
    }

    fun clearPasteState() {
        val pasteState = _pasteStateLiveData.valueCompat
        if (pasteState.files.isEmpty()) {
            return
        }
        pasteState.files.clear()
        _pasteStateLiveData.value = pasteState
    }

    private val _isRequestingStorageAccessLiveData = MutableLiveData(false)
    var isStorageAccessRequested: Boolean
        get() = _isRequestingStorageAccessLiveData.valueCompat
        set(value) {
            _isRequestingStorageAccessLiveData.value = value
        }

    private val _isRequestingNotificationPermissionLiveData = MutableLiveData(false)
    var isNotificationPermissionRequested: Boolean
        get() = _isRequestingNotificationPermissionLiveData.valueCompat
        set(value) {
            _isRequestingNotificationPermissionLiveData.value = value
        }

    override fun onCleared() {
        _fileListLiveData.close()
        for (tab in tabs) {
            tab.closeList()
        }
    }

    private fun rebind() {
        val tab = active
        _fileListLiveData.suspendUpdates()
        try {
            trailSource = retarget(currentPathMediator, trailSource, tab.trailLiveData) {
                currentPathMediator.value = it.currentPath
            }
            tab.trailLiveData.value?.let { currentPathMediator.value = it.currentPath }
            searchSource = retarget(searchStateMediator, searchSource, tab.searchStateLiveData) {
                searchStateMediator.value = it
            }
            tab.searchStateLiveData.value?.let { searchStateMediator.value = it }
            searchExpandedSource = retarget(
                searchExpandedMediator, searchExpandedSource, tab.searchViewExpandedLiveData
            ) { searchExpandedMediator.value = it }
            tab.searchViewExpandedLiveData.value?.let { searchExpandedMediator.value = it }
            searchQuerySource = retarget(
                searchQueryMediator, searchQuerySource, tab.searchViewQueryLiveData
            ) { searchQueryMediator.value = it }
            tab.searchViewQueryLiveData.value?.let { searchQueryMediator.value = it }
            selectedSource = retarget(selectedMediator, selectedSource, tab.selectedFilesLiveData) {
                selectedMediator.value = it
            }
            tab.selectedFilesLiveData.value?.let { selectedMediator.value = it }
            _breadcrumbLiveData.setTrail(tab.trailLiveData)
        } finally {
            _fileListLiveData.resumeUpdates()
        }
        publishTabs()
    }

    private fun <S, T> retarget(
        mediator: MediatorLiveData<T>,
        previous: LiveData<S>?,
        next: LiveData<S>,
        onChanged: (S) -> Unit
    ): LiveData<S> {
        if (previous === next) {
            return next
        }
        if (previous != null) {
            mediator.removeSource(previous)
        }
        mediator.addSource(next, onChanged)
        return next
    }

    private fun publishTabs() {
        _tabsLiveData.value = FileTabs(
            tabs.map { it.id },
            tabs.map { it.trailLiveData.valueCompat.currentPath },
            activeIndex
        )
    }

    private class FileTab(val id: Long) {
        val trailLiveData = TrailLiveData()
        val searchStateLiveData = MutableLiveData(SearchState(false, ""))
        val searchViewExpandedLiveData = MutableLiveData(false)
        val searchViewQueryLiveData = MutableLiveData("")
        val selectedFilesLiveData = MutableLiveData(fileItemSetOf())
        var listLiveData: CloseableLiveData<Stateful<List<FileItem>>>? = null
        var listKey: ListKey? = null

        fun closeList() {
            listLiveData?.close()
            listLiveData = null
            listKey = null
        }
    }

    private data class ListKey(val path: Path, val searching: Boolean, val query: String)

    companion object {
        const val MAX_TABS = 10

        private val _pasteStateLiveData = MutableLiveData(PasteState())
    }

    private inner class FileListSwitchMapLiveData(
        private val pathLiveData: LiveData<Path>,
        private val searchStateLiveData: LiveData<SearchState>
    ) : MediatorLiveData<Stateful<List<FileItem>>>(), Closeable {
        private var liveData: CloseableLiveData<Stateful<List<FileItem>>>? = null
        private var updatesSuspended = false

        init {
            addSource(pathLiveData) { updateSource() }
            addSource(searchStateLiveData) { updateSource() }
        }

        fun suspendUpdates() {
            updatesSuspended = true
        }

        fun resumeUpdates() {
            updatesSuspended = false
            if (hasActiveObservers()) {
                updateSource()
            }
        }

        private fun updateSource() {
            if (updatesSuspended || pathLiveData.value == null || searchStateLiveData.value == null) {
                return
            }
            if (activeIndex < 0) {
                return
            }
            val tab = active
            val path = pathLiveData.valueCompat
            val searchState = searchStateLiveData.valueCompat
            val key = if (searchState.isSearching) {
                ListKey(searchState.directory ?: path, true, searchState.query)
            } else {
                ListKey(path, false, "")
            }
            val existing = tab.listLiveData
            if (existing != null && tab.listKey == key) {
                if (liveData !== existing) {
                    liveData?.let { removeSource(it) }
                    liveData = existing
                    addSource(existing) { value = it }
                }
                return
            }
            existing?.close()
            val created = if (searchState.isSearching) {
                SearchFileListLiveData(key.path, searchState.query)
            } else {
                FileListLiveData(path)
            }
            tab.listLiveData = created
            tab.listKey = key
            liveData?.let { removeSource(it) }
            liveData = created
            addSource(created) { value = it }
        }

        fun reload() {
            when (val liveData = liveData) {
                is FileListLiveData -> liveData.loadValue()
                is SearchFileListLiveData -> liveData.loadValue()
            }
        }

        override fun close() {
            liveData?.let {
                removeSource(it)
                it.close()
                this.liveData = null
            }
        }
    }
}

data class FileTabs(
    val ids: List<Long>,
    val paths: List<Path>,
    val selectedIndex: Int
)
