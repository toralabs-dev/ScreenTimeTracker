package com.screentimetracker.app.ui.chart

import android.content.Context
import android.widget.TextView
import com.github.mikephil.charting.components.MarkerView
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.utils.MPPointF
import com.screentimetracker.app.R
import com.screentimetracker.app.data.model.DailyUsageEntry

/**
 * Custom MarkerView for showing tooltips when user taps on chart points.
 * Displays the date and formatted time for the selected data point.
 *
 * This is a top-level class (not an inner class) to avoid memory leaks
 * by preventing implicit references to Activity instances.
 */
class UsageMarkerView(
    context: Context,
    private val entriesProvider: () -> List<DailyUsageEntry>
) : MarkerView(context, R.layout.chart_marker_view) {

    private val textView: TextView = findViewById(R.id.markerText)

    override fun refreshContent(e: Entry?, highlight: Highlight?) {
        e?.let { entry ->
            val index = entry.x.toInt()
            val entries = entriesProvider()
            if (index in entries.indices) {
                val dailyEntry = entries[index]
                textView.text = "${dailyEntry.getFormattedDate()}: ${dailyEntry.getFormattedTime()}"
            }
        }
        super.refreshContent(e, highlight)
    }

    override fun getOffset(): MPPointF {
        // Center the marker above the highlighted point
        return MPPointF(-(width / 2f), -height.toFloat() - 10f)
    }
}
