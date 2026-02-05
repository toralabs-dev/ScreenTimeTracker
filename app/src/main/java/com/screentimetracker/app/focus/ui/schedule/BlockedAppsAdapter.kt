package com.screentimetracker.app.focus.ui.schedule

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.screentimetracker.app.databinding.ItemBlockedAppBinding

/**
 * RecyclerView adapter for selecting apps to block in a Focus Mode schedule.
 */
class BlockedAppsAdapter(
    private val onToggle: (packageName: String, isBlocked: Boolean) -> Unit,
    private val isAppBlocked: (packageName: String) -> Boolean
) : ListAdapter<ScheduleEditViewModel.AppInfo, BlockedAppsAdapter.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemBlockedAppBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemBlockedAppBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ScheduleEditViewModel.AppInfo) {
            binding.appName.text = item.appName

            // Set app icon if available
            if (item.icon != null) {
                binding.appIcon.setImageDrawable(item.icon)
            } else {
                binding.appIcon.setImageResource(android.R.drawable.sym_def_app_icon)
            }

            // Remove listener before setting checked state to avoid triggering callback
            binding.appCheckbox.setOnCheckedChangeListener(null)
            binding.appCheckbox.isChecked = isAppBlocked(item.packageName)

            binding.appCheckbox.setOnCheckedChangeListener { _, isChecked ->
                onToggle(item.packageName, isChecked)
            }

            // Also toggle checkbox when clicking on the row
            binding.root.setOnClickListener {
                binding.appCheckbox.isChecked = !binding.appCheckbox.isChecked
            }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<ScheduleEditViewModel.AppInfo>() {
        override fun areItemsTheSame(
            oldItem: ScheduleEditViewModel.AppInfo,
            newItem: ScheduleEditViewModel.AppInfo
        ): Boolean = oldItem.packageName == newItem.packageName

        override fun areContentsTheSame(
            oldItem: ScheduleEditViewModel.AppInfo,
            newItem: ScheduleEditViewModel.AppInfo
        ): Boolean = oldItem.packageName == newItem.packageName
    }
}
