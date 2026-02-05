package com.screentimetracker.app.ui.main

import android.content.Context
import android.graphics.Color
import com.github.mikephil.charting.charts.LineChart
import com.google.android.material.color.MaterialColors
import com.github.mikephil.charting.components.LimitLine
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.screentimetracker.app.R
import com.screentimetracker.app.data.model.DailyUsageEntry
import com.screentimetracker.app.ui.chart.UsageMarkerView

/**
 * Controller responsible for managing the trend chart in the dashboard.
 * Extracted from MainActivity to reduce god class complexity.
 */
class DashboardChartController(
    private val context: Context,
    private val lineChart: LineChart,
    private val entriesProvider: () -> List<DailyUsageEntry>
) {

    /**
     * Configures the LineChart appearance and behavior.
     */
    fun setupChart() {
        lineChart.apply {
            // General chart settings
            description.isEnabled = false
            setTouchEnabled(true)
            setDrawGridBackground(false)
            isDragEnabled = true
            setScaleEnabled(false)
            setPinchZoom(false)

            // Disable legend (we have summary text instead)
            legend.isEnabled = false

            // Configure X-axis (dates)
            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                setDrawGridLines(false)
                granularity = 1f
                textColor = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY)
                textSize = 10f
            }

            // Configure left Y-axis (time in minutes)
            axisLeft.apply {
                setDrawGridLines(true)
                gridColor = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOutlineVariant, Color.LTGRAY)
                textColor = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY)
                textSize = 10f
                axisMinimum = 0f
                // Format Y-axis labels as hours/minutes
                valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float): String {
                        val minutes = value.toInt()
                        return if (minutes >= 60) {
                            "${minutes / 60}h"
                        } else {
                            "${minutes}m"
                        }
                    }
                }
            }

            // Disable right Y-axis
            axisRight.isEnabled = false

            // Add 2-hour limit line
            val errorColor = MaterialColors.getColor(context, com.google.android.material.R.attr.colorError, Color.RED)
            val limitLine = LimitLine(120f, "2h limit").apply {
                lineWidth = 1f
                lineColor = errorColor
                textColor = errorColor
                textSize = 10f
                enableDashedLine(10f, 10f, 0f)
            }
            axisLeft.addLimitLine(limitLine)

            // Set custom marker for tap interaction
            marker = UsageMarkerView(context, entriesProvider)

            // Extra padding for better appearance
            setExtraOffsets(8f, 8f, 8f, 8f)
        }
    }

    /**
     * Updates the LineChart with daily usage data.
     */
    fun updateChart(dailyEntries: List<DailyUsageEntry>) {
        // Convert entries to chart data points
        // X-axis: index (0, 1, 2, ...), Y-axis: usage in minutes
        val entries = dailyEntries.mapIndexed { index, entry ->
            Entry(index.toFloat(), (entry.totalMillis / 1000 / 60).toFloat())
        }

        // Create dataset with Material 3 styling
        val primaryColor = MaterialColors.getColor(context, com.google.android.material.R.attr.colorPrimary, Color.BLUE)
        val surfaceColor = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurface, Color.WHITE)
        val dataSet = LineDataSet(entries, "Daily Usage").apply {
            color = primaryColor
            lineWidth = 2.5f
            setDrawCircles(true)
            circleRadius = 4f
            setCircleColor(primaryColor)
            circleHoleColor = surfaceColor
            circleHoleRadius = 2f
            setDrawValues(false)
            setDrawFilled(true)
            fillColor = primaryColor
            fillAlpha = 30
            mode = LineDataSet.Mode.CUBIC_BEZIER
            cubicIntensity = 0.2f
            setDrawHighlightIndicators(true)
            highLightColor = primaryColor
            highlightLineWidth = 1f
        }

        // Update X-axis labels with dates
        lineChart.xAxis.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                val index = value.toInt()
                return if (index in dailyEntries.indices) {
                    dailyEntries[index].getFormattedDate()
                } else {
                    ""
                }
            }
        }

        // Set label count based on data size to avoid crowding
        val labelCount = when {
            dailyEntries.size <= 7 -> dailyEntries.size
            dailyEntries.size <= 14 -> 7
            else -> 6
        }
        lineChart.xAxis.labelCount = labelCount

        // Set data and refresh chart
        lineChart.data = LineData(dataSet)
        lineChart.invalidate()
        lineChart.animateX(500)
    }
}
