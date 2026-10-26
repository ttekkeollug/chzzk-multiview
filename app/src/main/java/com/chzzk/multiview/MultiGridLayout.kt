package com.chzzk.multiview

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.view.DragEvent
import android.view.View
import android.view.ViewGroup
import kotlin.math.ceil
import kotlin.math.min

/**
 * 방송 타일을 화면 크기에 맞춰 자동 배치하는 레이아웃.
 * - 일반 모드: 16:9 영상이 가장 크게 보이는 열/행 수를 계산해 격자 배치
 * - 포커스 모드: 선택한 방송을 크게, 나머지는 옆(가로)/아래(세로)에 작게 배치
 * - 드래그 앤 드롭: 타일을 길게 눌러 끌면 원하는 자리로 순서를 바꿉니다.
 *
 * 화면 순서는 자식 뷰 순서가 아니라 [order] 목록으로 관리합니다.
 * 자식을 떼었다 붙이면 영상 Surface가 다시 만들어져 끊기므로, 위치 계산만 바꿉니다.
 */
class MultiGridLayout(context: Context) : ViewGroup(context) {

    var focused: View? = null
        set(value) {
            field = value
            requestLayout()
        }

    /** 드래그로 순서가 바뀐 뒤 호출됩니다. 인자는 새 화면 순서. */
    var onOrderChanged: ((List<View>) -> Unit)? = null

    private val order = ArrayList<View>()
    private val gap = (2 * resources.displayMetrics.density).toInt()
    private val rects = HashMap<View, Rect>()
    private var dragging: View? = null
    private val highlight = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 3 * resources.displayMetrics.density
        color = 0xFF00FFA3.toInt()
    }

    init {
        setWillNotDraw(false)
        setOnDragListener { _, e -> handleDrag(e) }
    }

    val displayOrder: List<View> get() = order.toList()

    override fun onViewAdded(child: View) {
        super.onViewAdded(child)
        if (child !in order) order.add(child)
    }

    override fun onViewRemoved(child: View) {
        super.onViewRemoved(child)
        order.remove(child)
        rects.remove(child)
        if (focused === child) focused = null
    }

    // ---------- 드래그 앤 드롭 ----------

    /** 타일을 길게 눌렀을 때 호출해 드래그를 시작합니다. */
    fun startDrag(tile: View, label: String) {
        val shadow = TileShadow(tile, label)
        val started = tile.startDragAndDrop(null, shadow, tile, 0)
        if (started) {
            dragging = tile
            tile.alpha = 0.35f
        }
    }

    private fun handleDrag(e: DragEvent): Boolean {
        val tile = e.localState as? View ?: return false
        if (tile.parent !== this) return false
        when (e.action) {
            DragEvent.ACTION_DRAG_STARTED -> return true
            DragEvent.ACTION_DRAG_LOCATION -> {
                val target = viewAt(e.x.toInt(), e.y.toInt())
                if (target != null && target !== tile) {
                    val to = order.indexOf(target)
                    order.remove(tile)
                    order.add(to, tile)
                    requestLayout()
                }
                invalidate()
            }
            DragEvent.ACTION_DROP -> return true
            DragEvent.ACTION_DRAG_ENDED -> {
                tile.alpha = 1f
                dragging = null
                invalidate()
                onOrderChanged?.invoke(order.toList())
            }
        }
        return true
    }

    private fun viewAt(x: Int, y: Int): View? =
        order.firstOrNull { rects[it]?.contains(x, y) == true }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val r = dragging?.let { rects[it] } ?: return
        canvas.drawRect(
            r.left.toFloat() + highlight.strokeWidth / 2, r.top.toFloat() + highlight.strokeWidth / 2,
            r.right.toFloat() - highlight.strokeWidth / 2, r.bottom.toFloat() - highlight.strokeWidth / 2,
            highlight,
        )
    }

    /** 영상 Surface는 그림자에 찍히지 않으므로 채널 이름이 적힌 카드로 그림자를 만듭니다. */
    private class TileShadow(view: View, private val label: String) : DragShadowBuilder(view) {
        private val density = view.resources.displayMetrics.density
        private val w = (view.width * 0.6f).toInt().coerceIn((140 * density).toInt(), (320 * density).toInt())
        private val h = (w * 9 / 16)
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE61F2023.toInt() }
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2 * density
            color = 0xFF00FFA3.toInt()
        }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 16 * density
            textAlign = Paint.Align.CENTER
        }

        override fun onProvideShadowMetrics(size: Point, touch: Point) {
            size.set(w, h)
            touch.set(w / 2, h / 2)
        }

        override fun onDrawShadow(canvas: Canvas) {
            val r = RectF(density, density, w - density, h - density)
            canvas.drawRoundRect(r, 10 * density, 10 * density, bg)
            canvas.drawRoundRect(r, 10 * density, 10 * density, border)
            val shown = if (text.measureText(label) > w - 20 * density) {
                label.take(12) + "…"
            } else label
            canvas.drawText(shown, w / 2f, h / 2f + text.textSize / 3, text)
        }
    }

    // ---------- 배치 ----------

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        computeRects(w, h)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val r = rects[child] ?: Rect()
            child.measure(
                MeasureSpec.makeMeasureSpec(r.width().coerceAtLeast(0), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(r.height().coerceAtLeast(0), MeasureSpec.EXACTLY),
            )
        }
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val rc = rects[child] ?: Rect()
            child.layout(rc.left, rc.top, rc.right, rc.bottom)
        }
    }

    private fun computeRects(w: Int, h: Int) {
        rects.clear()
        val n = order.size
        if (n == 0) return
        val focus = focused?.takeIf { it in order }

        if (focus == null || n == 1) {
            grid(n, 0, 0, w, h).forEachIndexed { i, r -> rects[order[i]] = r }
            return
        }

        val others = order.filter { it !== focus }
        val rest: List<Rect>
        if (w >= h) {
            val mainW = (w * 0.74f).toInt()
            rects[focus] = Rect(0, 0, mainW, h)
            rest = grid(n - 1, mainW + gap, 0, w - mainW - gap, h, preferColumn = true)
        } else {
            val mainH = (h * 0.6f).toInt()
            rects[focus] = Rect(0, 0, w, mainH)
            rest = grid(n - 1, 0, mainH + gap, w, h - mainH - gap)
        }
        others.forEachIndexed { i, v -> rects[v] = rest[i] }
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
