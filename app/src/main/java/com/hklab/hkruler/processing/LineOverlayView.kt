package com.hklab.hkruler.processing

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs

data class LineN(
    val x1: Float, val y1: Float, val x2: Float, val y2: Float,
    val angleDeg: Float,           // 0~180
    val score: Float               // 길이 등 점수
)

class LineOverlayView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }
    private val lines: MutableList<LineN> = mutableListOf()

    /** src 이미지 크기와 현재 View 사이의 좌표 매핑을 위해 보관 */
    private var srcWidth = 0
    private var srcHeight = 0

    fun setSourceSize(w: Int, h: Int) {
        srcWidth = w
        srcHeight = h
    }

    fun update(newLines: List<LineN>) {
        synchronized(lines) {
            lines.clear()
            lines.addAll(newLines)
        }
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (srcWidth == 0 || srcHeight == 0) return

        val sx = width / srcWidth.toFloat()
        val sy = height / srcHeight.toFloat()

        synchronized(lines) {
            for (ln in lines) {
                val isHoriz = ln.angleDeg < 45f || ln.angleDeg > 135f
                val distTo0 = minOf(abs(ln.angleDeg), abs(180f - ln.angleDeg))
                val distTo90 = abs(ln.angleDeg - 90f)

                val okHoriz = distTo0 < 3f
                val okVert = distTo90 < 3f

                // 색상: 목표 각도에 근접하면 RED, 아니면 CYAN
                paint.color = when {
                    isHoriz && okHoriz -> Color.RED
                    !isHoriz && okVert -> Color.RED
                    else -> Color.CYAN
                }

                canvas.drawLine(
                    ln.x1 * sx, ln.y1 * sy,
                    ln.x2 * sx, ln.y2 * sy, paint
                )
            }
        }
    }
}
