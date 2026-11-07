/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filejob

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.FileJobProgressDialogBinding
import me.zhanghai.android.files.databinding.FileJobProgressItemBinding
import me.zhanghai.android.files.util.layoutInflater

class FileJobProgressDialogFragment : AppCompatDialogFragment() {
    private lateinit var binding: FileJobProgressDialogBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        FileJobProgress.snapshots.observe(this) {
            if (!this::binding.isInitialized) {
                return@observe
            }
            val jobs = FileJobProgress.current()
            if (jobs.isEmpty()) {
                dismissAllowingStateLoss()
            } else {
                bind(jobs)
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        binding = FileJobProgressDialogBinding.inflate(requireContext().layoutInflater)
        return MaterialAlertDialogBuilder(requireContext(), theme)
            .setTitle(R.string.file_job_progress_title)
            .setView(binding.root)
            .create()
    }

    private fun bind(jobs: List<FileJobSnapshot>) {
        val container = binding.jobs
        container.removeAllViews()
        val inflater = requireContext().layoutInflater
        for (job in jobs) {
            val row = FileJobProgressItemBinding.inflate(inflater, container, true)
            row.titleText.text = job.title
            row.detailText.isVisible = !job.text.isNullOrEmpty()
            row.detailText.text = job.text
            if (job.indeterminate) {
                row.progress.isIndeterminate = true
            } else {
                row.progress.isIndeterminate = false
                row.progress.max = job.max.coerceAtLeast(1)
                row.progress.progress = job.progress.coerceIn(0, row.progress.max)
            }
            row.cancelButton.isVisible = job.cancellable
            row.cancelButton.setOnClickListener { FileJobService.cancelJob(job.id) }
        }
    }

    companion object {
        private const val TAG = "FileJobProgressDialogFragment"

        fun show(fragment: Fragment) {
            val manager = fragment.childFragmentManager
            if (manager.findFragmentByTag(TAG) != null) {
                return
            }
            FileJobProgressDialogFragment().show(manager, TAG)
        }
    }
}
