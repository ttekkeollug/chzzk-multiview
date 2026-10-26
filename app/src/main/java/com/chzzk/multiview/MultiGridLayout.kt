package com.chzzk.multiview

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import kotlin.math.ceil
import kotlin.math.min

/**
 * 방송 타일을 화면 크기에 맞춰 자동 배치하는 레이아웃.
 * - 일반 모드: 16:9 영상이 가장 크게 보이는 열/행 수를 계산해 격자 배치
 * - 포커스 모드: 선택한 방송을 크게, 나머지는 옆(가로)/아래(세로)에 작게 배치
 */
class MultiGridLayout(context: Context) : ViewGroup(context) {

    var focused: View? = null
        set(value) {
            field = value
            requestLayout()
        }

    private val gap = (2 * resources.displayMetrics.density).toInt()
    private val rects = ArrayList<Rect>()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        computeRects(w, h)
        for (i in 0 until childCount) {
            val r = rects[i]
            getChildAt(i).measure(
                MeasureSpec.makeMeasureSpec(r.width().coerceAtLeast(0), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(r.height().coerceAtLeast(0), MeasureSpec.EXACTLY),
            )
        }
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val rc = rects[i]
            getChildAt(i).layout(rc.left, rc.top, rc.right, rc.bottom)
        }
    }

    private fun computeRects(w: Int, h: Int) {
        rects.clear()
        val n = childCount
        if (n == 0) return
        val focus = focused
        val focusIndex = if (focus != null) indexOfChild(focus) else -1

        if (focusIndex < 0 || n == 1) {
            rects.addAll(grid(n, 0, 0, w, h))
            return
        }

        val main: Rect
        val rest: List<Rect>
        if (w >= h) {
            val mainW = (w * 0.74f).toInt()
            main = Rect(0, 0, mainW, h)
            rest = grid(n - 1, mainW + gap, 0, w - mainW - gap, h, preferColumn = true)
        } else {
            val mainH = (h * 0.6f).toInt()
            main = Rect(0, 0, w, mainH)
            rest = grid(n - 1, 0, mainH + gap, w, h - mainH - gap)
        }
        var k = 0
        for (i in 0 until n) rects.add(if (i == focusIndex) main else rest[k++])
    }

    private fun grid(
        n: Int, x: Int, y: Int, w: Int, h: Int, preferColumn: Boolean = false,
    ): List<Rect> {
        if (n <= 0) return emptyList()
        var bestCols = 1
        var bestArea = -1.0
        val maxCols = if (preferColumn) 1 else n
        for (cols in 1..maxCols) {
            val rows = ceil(n / cols.toDouble()).toInt()
            val cw = (w - gap * (cols - 1)) / cols.toDouble()
            val ch = (h - gap * (rows - 1)) / rows.toDouble()
            if (cw <= 0 || ch <= 0) continue
            val vw = min(cw, ch * 16.0 / 9.0)
            val area = vw * vw * 9.0 / 16.0
            if (area > bestArea + 1) {
                bestArea = area
                bestCols = cols
            }
        }
        val cols = bestCols
        val rows = ceil(n / cols.toDouble()).toInt()
        val cw = (w - gap * (cols - 1)) / cols
        val ch = (h - gap * (rows - 1)) / rows
        val out = ArrayList<Rect>(n)
        for (i in 0 until n) {
            val row = i / cols
            val col = i % cols
            val inRow = if (row == rows - 1) n - row * cols else cols
            // 마지막 줄에 남는 타일은 가운데 정렬
            val offset = (w - (inRow * cw + (inRow - 1) * gap)) / 2
            val left = x + offset + col * (cw + gap)
            val top = y + row * (ch + gap)
            out.add(Rect(left, top, left + cw, top + ch))
        }
        return out
    }
}
