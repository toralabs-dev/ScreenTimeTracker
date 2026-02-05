package com.screentimetracker.app.focus.ui.schedule

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.screentimetracker.app.R
import com.screentimetracker.app.databinding.ItemScheduleBinding

/**
 * RecyclerView adapter for displaying Focus Mode schedules.
 */
class ScheduleAdapter(
    private val onItemClick: (scheduleId: Long) -> Unit,
    private val onToggleEnabled: (scheduleId: Long, enabled: Boolean) -> Unit
) : ListAdapter<ScheduleListViewModel.ScheduleWithApps, ScheduleAdapter.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemScheduleBinding.inflate(
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
        private val binding: ItemScheduleBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ScheduleListViewModel.ScheduleWithApps) {
            val schedule = item.schedule
            val context = binding.root.context

            binding.scheduleName.text = schedule.name
            binding.daysSummary.text = ScheduleListViewModel.formatDaysSummary(schedule.daysOfWeekMask)

            val startTime = ScheduleListViewModel.formatTime(schedule.startTimeMinutes)
            val endTime = ScheduleListViewModel.formatTime(schedule.endTimeMinutes)
            binding.timeRange.text = "$startTime - $endTime"

            binding.blockedAppsCount.text = context.getString(
                R.string.focus_schedule_apps_count,
                item.blockedAppCount
            )

            // Remove listener before setting checked state to avoid triggering callback
            binding.enableSwitch.setOnCheckedChangeListener(null)
            binding.enableSwitch.isChecked = schedule.isEnabled

            binding.enableSwitch.setOnCheckedChangeListener { _, isChecked ->
                onToggleEnabled(schedule.id, isChecked)
            }

            binding.root.setOnClickListener {
                onItemClick(schedule.id)
            }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<ScheduleListViewModel.ScheduleWithApps>() {
        override fun areItemsTheSame(
            oldItem: ScheduleListViewModel.ScheduleWithApps,
            newItem: ScheduleListViewModel.ScheduleWithApps
        ): Boolean = oldItem.schedule.id == newItem.schedule.id

        override fun areContentsTheSame(
            oldItem: ScheduleListViewModel.ScheduleWithApps,
            newItem: ScheduleListViewModel.ScheduleWithApps
        ): Boolean = oldItem == newItem
    }
}
