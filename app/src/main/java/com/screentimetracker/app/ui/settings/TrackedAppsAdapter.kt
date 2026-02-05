package com.screentimetracker.app.ui.settings

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.screentimetracker.app.databinding.ItemTrackedAppBinding

/**
 * RecyclerView adapter for the tracked apps list in Settings.
 */
class TrackedAppsAdapter(
    private val onToggle: (packageName: String, isTracked: Boolean) -> Unit
) : ListAdapter<SettingsViewModel.TrackedAppItem, TrackedAppsAdapter.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemTrackedAppBinding.inflate(
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
        private val binding: ItemTrackedAppBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: SettingsViewModel.TrackedAppItem) {
            binding.appNameText.text = item.displayName

            // Set app icon
            if (item.icon != null) {
                binding.appIcon.setImageDrawable(item.icon)
            } else {
                binding.appIcon.setImageResource(android.R.drawable.sym_def_app_icon)
            }

            // Remove listener before setting checked state to avoid triggering callback
            binding.appSwitch.setOnCheckedChangeListener(null)
            binding.appSwitch.isChecked = item.isTracked

            binding.appSwitch.setOnCheckedChangeListener { _, isChecked ->
                onToggle(item.packageName, isChecked)
            }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<SettingsViewModel.TrackedAppItem>() {
        override fun areItemsTheSame(
            oldItem: SettingsViewModel.TrackedAppItem,
            newItem: SettingsViewModel.TrackedAppItem
        ): Boolean = oldItem.packageName == newItem.packageName

        override fun areContentsTheSame(
            oldItem: SettingsViewModel.TrackedAppItem,
            newItem: SettingsViewModel.TrackedAppItem
        ): Boolean = oldItem == newItem
    }
}
