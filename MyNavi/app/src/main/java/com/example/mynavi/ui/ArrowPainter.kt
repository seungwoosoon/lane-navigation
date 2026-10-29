package com.example.mynavi.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * 회전 화살표를 정사각형 칸(x, y, 한 변 s) 안에 그린다.
 * type: LEFT, RIGHT, UTURN, ARRIVE(체크 표시), 그 외는 직진.
 * drawLane: 한 차로에서 갈 수 있는 방향이 여러 개면(예: 직진+우회전) 줄기 하나에서 갈라지는 화살표로 그린다.
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

    /** 차로 화살표. types 예: [STRAIGHT], [LEFT, STRAIGHT], [STRAIGHT, RIGHT] */
    fun drawLane(c: Canvas, types: List<String>, x: Float, y: Float, s: Float, color: Int) {
        if (types.size <= 1) {
            draw(c, types.firstOrNull(), x, y, s, color)
            return
        }
        stroke.color = color
        stroke.strokeWidth = s * 0.13f
        head.color = color
        head.strokeWidth = s * 0.03f

        val cx = x + s / 2f
        val branchY = y + s * 0.56f   // 줄기가 갈라지는 높이
        val yh = y + s * 0.42f        // 좌/우 가지 높이
        val r = s * 0.14f             // 꺾이는 부분 둥글기

        // 공통 줄기
        path.reset()
        path.moveTo(cx, y + s * 0.94f)
        path.lineTo(cx, branchY)
        c.drawPath(path, stroke)

        for (type in types) {
            path.reset()
            when (type) {
                "LEFT", "RIGHT" -> {
                    val dir = if (type == "LEFT") -1f else 1f
                    path.moveTo(cx, branchY)
                    path.quadTo(cx, yh, cx + dir * r, yh)
                    path.lineTo(cx + dir * s * 0.20f, yh)
                    c.drawPath(path, stroke)
                    if (type == "LEFT") headLeft(c, x + s * 0.04f, yh, s, SMALL_LEN, SMALL_HALF)
                    else headRight(c, x + s * 0.96f, yh, s, SMALL_LEN, SMALL_HALF)
                }
                "UTURN" -> {
                    // 줄기 아래쪽에서 왼쪽으로 돌아 내려오는 작은 유턴
                    val ru = s * 0.12f
                    val ay = y + s * 0.68f
                    path.moveTo(cx, ay)
                    oval.set(cx - 2 * ru, ay - ru, cx, ay + ru)
                    path.arcTo(oval, 0f, -180f, false)
                    path.lineTo(cx - 2 * ru, y + s * 0.80f)
                    c.drawPath(path, stroke)
                    headDown(c, cx - 2 * ru, y + s * 0.98f, s, SMALL_LEN * 0.8f, SMALL_HALF * 0.8f)
                }
                else -> {
                    path.moveTo(cx, branchY)
                    path.lineTo(cx, y + s * 0.28f)
                    c.drawPath(path, stroke)
                    headUp(c, cx, y + s * 0.04f, s, SMALL_LEN, SMALL_HALF)
                }
            }
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
    // 여러 방향 화살표용 작은 화살촉
    private const val SMALL_LEN = 0.24f
    private const val SMALL_HALF = 0.16f

    private fun headUp(c: Canvas, ax: Float, ay: Float, s: Float, len: Float = HEAD_LEN, half: Float = HEAD_HALF) {
        path.reset()
        path.moveTo(ax, ay)
        path.lineTo(ax + s * half, ay + s * len)
        path.lineTo(ax - s * half, ay + s * len)
        path.close()
        c.drawPath(path, head)
    }

    private fun headLeft(c: Canvas, ax: Float, ay: Float, s: Float, len: Float = HEAD_LEN, half: Float = HEAD_HALF) {
        path.reset()
        path.moveTo(ax, ay)
        path.lineTo(ax + s * len, ay - s * half)
        path.lineTo(ax + s * len, ay + s * half)
        path.close()
        c.drawPath(path, head)
    }

    private fun headRight(c: Canvas, ax: Float, ay: Float, s: Float, len: Float, half: Float) {
        path.reset()
        path.moveTo(ax, ay)
        path.lineTo(ax - s * len, ay - s * half)
        path.lineTo(ax - s * len, ay + s * half)
        path.close()
        c.drawPath(path, head)
    }

    private fun headDown(c: Canvas, ax: Float, ay: Float, s: Float, len: Float = HEAD_LEN, half: Float = HEAD_HALF) {
        path.reset()
        path.moveTo(ax, ay)
        path.lineTo(ax - s * half, ay - s * len)
        path.lineTo(ax + s * half, ay - s * len)
        path.close()
        c.drawPath(path, head)
    }
}
