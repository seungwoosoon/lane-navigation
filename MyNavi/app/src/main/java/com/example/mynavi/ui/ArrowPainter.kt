package com.example.mynavi.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * 회전 화살표를 정사각형 칸(x, y, 한 변 s) 안에 그린다.
 * type: LEFT, RIGHT, UTURN, ARRIVE(체크 표시), 그 외는 직진.
 */
object ArrowPainter {

    private val path = Path()
    private val oval = RectF()

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    // 화살촉: 모서리를 살짝 둥글게 하려고 FILL_AND_STROKE 사용
    private val head = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL_AND_STROKE
        strokeJoin = Paint.Join.ROUND
    }

    fun draw(c: Canvas, type: String?, x: Float, y: Float, s: Float, color: Int) {
        stroke.color = color
        stroke.strokeWidth = s * 0.15f
        head.color = color
        head.strokeWidth = s * 0.04f

        when (type) {
            "LEFT" -> drawLeft(c, x, y, s)
            "RIGHT" -> {
                // 좌회전 화살표를 좌우 반전
                c.save()
                c.scale(-1f, 1f, x + s / 2f, y + s / 2f)
                drawLeft(c, x, y, s)
                c.restore()
            }
            "UTURN" -> drawUTurn(c, x, y, s)
            "ARRIVE" -> drawCheck(c, x, y, s)
            else -> drawStraight(c, x, y, s)
        }
    }

    private fun drawStraight(c: Canvas, x: Float, y: Float, s: Float) {
        val cx = x + s / 2f
        path.reset()
        path.moveTo(cx, y + s * 0.92f)
        path.lineTo(cx, y + s * 0.34f)
        c.drawPath(path, stroke)
        headUp(c, cx, y + s * 0.02f, s)
    }

    private fun drawLeft(c: Canvas, x: Float, y: Float, s: Float) {
        val sx = x + s * 0.66f      // 세로 줄기 위치
        val yh = y + s * 0.38f      // 꺾인 뒤 가로 줄기 높이
        val r = s * 0.24f           // 꺾이는 부분 둥글기
        path.reset()
        path.moveTo(sx, y + s * 0.92f)
        path.lineTo(sx, yh + r)
        path.quadTo(sx, yh, sx - r, yh)
        path.lineTo(x + s * 0.34f, yh)
        c.drawPath(path, stroke)
        headLeft(c, x + s * 0.02f, yh, s)
    }

    private fun drawUTurn(c: Canvas, x: Float, y: Float, s: Float) {
        val cx = x + s / 2f
        val r = s * 0.21f
        val rx = cx + r             // 올라가는 줄기
        val lx = cx - r             // 내려오는 줄기
        val ay = y + s * 0.40f      // 반원 중심 높이
        path.reset()
        path.moveTo(rx, y + s * 0.92f)
        path.lineTo(rx, ay)
        oval.set(lx, ay - r, rx, ay + r)
        path.arcTo(oval, 0f, -180f, false)
        path.lineTo(lx, y + s * 0.64f)
        c.drawPath(path, stroke)
        headDown(c, lx, y + s * 0.96f, s)
    }

    private fun drawCheck(c: Canvas, x: Float, y: Float, s: Float) {
        path.reset()
        path.moveTo(x + s * 0.18f, y + s * 0.52f)
        path.lineTo(x + s * 0.42f, y + s * 0.75f)
        path.lineTo(x + s * 0.84f, y + s * 0.28f)
        c.drawPath(path, stroke)
    }

    // ---- 화살촉 (꼭짓점 위치 기준) ----
    private const val HEAD_LEN = 0.36f
    private const val HEAD_HALF = 0.27f

    private fun headUp(c: Canvas, ax: Float, ay: Float, s: Float) {
        path.reset()
        path.moveTo(ax, ay)
        path.lineTo(ax + s * HEAD_HALF, ay + s * HEAD_LEN)
        path.lineTo(ax - s * HEAD_HALF, ay + s * HEAD_LEN)
        path.close()
        c.drawPath(path, head)
    }

    private fun headLeft(c: Canvas, ax: Float, ay: Float, s: Float) {
        path.reset()
        path.moveTo(ax, ay)
        path.lineTo(ax + s * HEAD_LEN, ay - s * HEAD_HALF)
        path.lineTo(ax + s * HEAD_LEN, ay + s * HEAD_HALF)
        path.close()
        c.drawPath(path, head)
    }

    private fun headDown(c: Canvas, ax: Float, ay: Float, s: Float) {
        path.reset()
        path.moveTo(ax, ay)
        path.lineTo(ax - s * HEAD_HALF, ay - s * HEAD_LEN)
        path.lineTo(ax + s * HEAD_HALF, ay - s * HEAD_LEN)
        path.close()
        c.drawPath(path, head)
    }
}
