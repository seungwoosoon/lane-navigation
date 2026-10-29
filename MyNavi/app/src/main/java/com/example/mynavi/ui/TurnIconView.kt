package com.example.mynavi.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/** 안내 카드 머리 부분의 큰 회전 화살표. */
class TurnIconView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** LEFT, RIGHT, UTURN, ARRIVE, STRAIGHT */
    var type: String? = "STRAIGHT"
        set(value) {
            field = value
            invalidate()
        }

    var arrowColor: Int = Color.WHITE
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width - paddingLeft - paddingRight
        val h = height - paddingTop - paddingBottom
        val s = min(w, h).toFloat()
        if (s <= 0f) return
        val x = paddingLeft + (w - s) / 2f
        val y = paddingTop + (h - s) / 2f
        ArrowPainter.draw(canvas, type, x, y, s, arrowColor)
    }
}
