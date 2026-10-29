package com.example.mynavi.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.example.mynavi.R
import kotlin.math.min

/**
 * 차로 안내 그림.
 * - 목표 차로: 파란 바탕 + 회전 방향 화살표
 * - 나머지 차로: 회색 직진 화살표
 * - 현재 차로(알 때만): 아래쪽에 자동차
 */
class LaneGuideView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var totalLanes = 0
    private var targetLanes: Set<Int> = emptySet()
    private var currentLane: Int? = null
    private var maneuver: String? = null

    private val dp = resources.displayMetrics.density

    private val arrowOnColor = ContextCompat.getColor(context, R.color.navi_primary)
    private val arrowOffColor = ContextCompat.getColor(context, R.color.lane_arrow_off)

    private val roadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.lane_road)
    }
    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.navi_primary_soft)
    }
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.lane_divider)
        style = Paint.Style.STROKE
        strokeWidth = 2 * dp
        pathEffect = DashPathEffect(floatArrayOf(7 * dp, 6 * dp), 0f)
    }
    private val carPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.lane_car)
    }
    private val carShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x26000000
    }
    private val carWheelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3A3F47.toInt()
    }
    private val carWindowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xE6FFFFFF.toInt()
    }
    private val carLightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFF3C4.toInt()
    }

    private val roadRect = RectF()
    private val tmpRect = RectF()
    private val clipPath = Path()

    fun setLanes(total: Int, targets: List<Int>, current: Int?, maneuver: String?) {
        val heightChanged = (currentLane == null) != (current == null)
        totalLanes = total
        targetLanes = targets.toSet()
        currentLane = current
        this.maneuver = maneuver
        if (heightChanged) requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // 자동차를 그릴 때만 아래 공간을 더 쓴다: 자동차가 있으면 95dp, 없으면 60dp
        val desired = ((if (currentLane != null) 95 else 60) * dp).toInt()
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            resolveSize(desired, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val n = totalLanes
        if (n <= 0) return

        val laneW = min(width.toFloat() / n, 72 * dp)
        val left = (width - laneW * n) / 2f
        val bottom = height.toFloat()
        val radius = 14 * dp
        roadRect.set(left, 0f, left + laneW * n, bottom)

        // 도로 바탕
        canvas.drawRoundRect(roadRect, radius, radius, roadPaint)

        // 목표 차로 바탕 (도로의 둥근 모서리 안쪽만)
        canvas.save()
        clipPath.reset()
        clipPath.addRoundRect(roadRect, radius, radius, Path.Direction.CW)
        canvas.clipPath(clipPath)
        for (i in 1..n) {
            if (i in targetLanes) {
                canvas.drawRect(left + (i - 1) * laneW, 0f, left + i * laneW, bottom, targetPaint)
            }
        }
        canvas.restore()

        // 차선 (점선)
        for (i in 1 until n) {
            val x = left + i * laneW
            canvas.drawLine(x, 8 * dp, x, bottom - 8 * dp, dividerPaint)
        }

        // 차로별 화살표
        val arrowSize = min(laneW * 0.5f, 35 * dp)
        val arrowTop = if (currentLane != null) 10 * dp else (height - arrowSize) / 2f
        for (i in 1..n) {
            val cx = left + (i - 0.5f) * laneW
            val isTarget = i in targetLanes
            ArrowPainter.draw(
                canvas,
                if (isTarget) maneuver else "STRAIGHT",
                cx - arrowSize / 2f,
                arrowTop,
                arrowSize,
                if (isTarget) arrowOnColor else arrowOffColor
            )
        }

        // 현재 차로의 자동차
        val cur = currentLane
        if (cur != null && cur in 1..n) {
            val carW = min(laneW * 0.30f, 20 * dp)
            val carH = carW * 1.75f
            drawCar(canvas, left + (cur - 0.5f) * laneW, bottom - 8 * dp - carH, carW, carH)
        }
    }

    /** 위에서 내려다본 자동차 (앞이 위쪽). */
    private fun drawCar(c: Canvas, cx: Float, top: Float, w: Float, h: Float) {
        val l = cx - w / 2f
        val r = cx + w / 2f
        val corner = w * 0.38f

        tmpRect.set(l + 1 * dp, top + 2 * dp, r + 1 * dp, top + h + 2 * dp)
        c.drawRoundRect(tmpRect, corner, corner, carShadowPaint)

        // 바퀴 (차체 양옆으로 살짝 튀어나오게, 차체보다 먼저 그림)
        val wheelW = w * 0.20f
        val wheelR = wheelW * 0.4f
        for (wy in floatArrayOf(0.14f, 0.66f)) {
            tmpRect.set(l - w * 0.08f, top + h * wy, l - w * 0.08f + wheelW, top + h * (wy + 0.17f))
            c.drawRoundRect(tmpRect, wheelR, wheelR, carWheelPaint)
            tmpRect.set(r + w * 0.08f - wheelW, top + h * wy, r + w * 0.08f, top + h * (wy + 0.17f))
            c.drawRoundRect(tmpRect, wheelR, wheelR, carWheelPaint)
        }

        tmpRect.set(l, top, r, top + h)
        c.drawRoundRect(tmpRect, corner, corner, carPaint)

        // 앞 유리
        val glass = w * 0.12f
        tmpRect.set(l + w * 0.16f, top + h * 0.22f, r - w * 0.16f, top + h * 0.42f)
        c.drawRoundRect(tmpRect, glass, glass, carWindowPaint)
        // 뒷 유리
        tmpRect.set(l + w * 0.18f, top + h * 0.72f, r - w * 0.18f, top + h * 0.84f)
        c.drawRoundRect(tmpRect, glass, glass, carWindowPaint)

        // 전조등
        val light = w * 0.08f
        c.drawCircle(l + w * 0.24f, top + h * 0.08f, light, carLightPaint)
        c.drawCircle(r - w * 0.24f, top + h * 0.08f, light, carLightPaint)
    }
}
