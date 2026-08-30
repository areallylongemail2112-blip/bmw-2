package com.bmw.assistant.ui.common

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.bmw.assistant.R

/** Tiny polyline of recent live-data samples. */
class SparklineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 1.6f
        color = ContextCompat.getColor(context, R.color.primary)
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val path = Path()
    private var samples: List<Float> = emptyList()

    fun setSamples(values: List<Float>) {
        samples = values
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (samples.size < 2) return
        val min = samples.min()
        val max = samples.max()
        val span = (max - min).takeIf { it > 0f } ?: 1f
        val w = width.toFloat() - paddingLeft - paddingRight
        val h = height.toFloat() - paddingTop - paddingBottom
        if (w <= 0f || h <= 0f) return
        path.reset()
        samples.forEachIndexed { i, v ->
            val x = paddingLeft + w * i / (samples.size - 1)
            val y = paddingTop + h * (1f - (v - min) / span)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, paint)
    }
}
