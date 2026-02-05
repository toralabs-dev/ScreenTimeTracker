package com.screentimetracker.app.ui.appdetail

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.screentimetracker.app.data.model.SessionInfo
import com.screentimetracker.app.databinding.ItemSessionBinding

/**
 * RecyclerView Adapter for displaying session items in App Detail screen.
 *
 * Shows sessions with their REAL start/end times. For cross-midnight sessions
 * (started before windowStart), displays "Today: Xm" to show only today's portion.
 */
class SessionsAdapter : ListAdapter<SessionInfo, SessionsAdapter.ViewHolder>(DiffCallback) {

    /** Window start time (e.g., midnight today) */
    private var windowStart: Long = 0L

    /** Window end time (e.g., now) */
    private var windowEnd: Long = 0L

    /**
     * Sets the window times for overlap duration calculation.
     * Must be called before submitting list.
     */
    fun setWindow(start: Long, end: Long) {
        windowStart = start
        windowEnd = end
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSessionBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), windowStart, windowEnd)
    }

    class ViewHolder(
        private val binding: ItemSessionBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(session: SessionInfo, windowStart: Long, windowEnd: Long) {
            binding.timeRange.text = session.getFormattedTimeRange()

            // For cross-midnight sessions, show "Today: Xm" to indicate
            // only today's portion is counted
            val durationText = if (session.startedBeforeWindow(windowStart)) {
                "Today: ${session.getFormattedOverlapDuration(windowStart, windowEnd)}"
            } else {
                session.getFormattedDuration()
            }
            binding.duration.text = durationText
        }
    }

    companion object {
        private val DiffCallback = object : DiffUtil.ItemCallback<SessionInfo>() {
            override fun areItemsTheSame(oldItem: SessionInfo, newItem: SessionInfo): Boolean {
                return oldItem.startTimeMillis == newItem.startTimeMillis &&
                        oldItem.packageName == newItem.packageName
            }

            override fun areContentsTheSame(oldItem: SessionInfo, newItem: SessionInfo): Boolean {
                return oldItem == newItem
            }
        }
    }
}
