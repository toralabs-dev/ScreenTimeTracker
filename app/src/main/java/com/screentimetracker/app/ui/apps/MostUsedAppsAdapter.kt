package com.screentimetracker.app.ui.apps

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.screentimetracker.app.data.model.AppUsageInfo
import com.screentimetracker.app.databinding.ItemAppUsageBinding

/**
 * RecyclerView Adapter for displaying ranked app usage items.
 *
 * @param onItemClick Callback when an app item is clicked, provides packageName and appName
 */
class MostUsedAppsAdapter(
    private val onItemClick: (packageName: String, appName: String) -> Unit
) : ListAdapter<AppUsageInfo, MostUsedAppsAdapter.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAppUsageBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), position + 1)
    }

    inner class ViewHolder(
        private val binding: ItemAppUsageBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(appUsage: AppUsageInfo, rank: Int) {
            binding.rankBadge.text = rank.toString()
            binding.appName.text = appUsage.appName
            binding.usageTime.text = appUsage.getFormattedTime()

            binding.root.setOnClickListener {
                onItemClick(appUsage.packageName, appUsage.appName)
            }
        }
    }

    companion object {
        private val DiffCallback = object : DiffUtil.ItemCallback<AppUsageInfo>() {
            override fun areItemsTheSame(oldItem: AppUsageInfo, newItem: AppUsageInfo): Boolean {
                return oldItem.packageName == newItem.packageName
            }

            override fun areContentsTheSame(oldItem: AppUsageInfo, newItem: AppUsageInfo): Boolean {
                return oldItem == newItem
            }
        }
    }
}
