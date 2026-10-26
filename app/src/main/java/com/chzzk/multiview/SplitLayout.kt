package com.chzzk.multiview

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 방송 영역(main)과 채팅 패널(side)을 나누는 레이아웃.
 *
 * - 구분선(가운데 손잡이 제외)을 끌면 채팅창 비율이 바뀝니다.
 * - 가운데 손잡이(⠿)를 끌거나 구분선을 길게 누른 뒤 끌면 채팅창을 화면의 왼쪽/오른쪽/위/아래
 *   어디든 원하는 가장자리로 옮길 수 있습니다. 놓기 전에 들어갈 자리가 미리 표시됩니다.
 */
@SuppressLint("ViewConstructor")
class SplitLayout(
    context: Context,
    private val main: View,
    private val side: View,
) : ViewGroup(context) {

    enum class Dock { LEFT, TOP, RIGHT, BOTTOM }

    var dock = Dock.RIGHT
        set(value) {
            field = value
            requestLayout()
        }

    /** 채팅창이 차지하는 비율 (0.15 ~ 0.75) */
    var ratio = 0.3f
        set(value) {
            field = value.coerceIn(MIN_RATIO, MAX_RATIO)
            requestLayout()
        }

    /** 사용자가 크기/위치를 바꾼 뒤 호출됩니다. */
    var onChanged: ((Dock, Float) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val dividerSize = (14 * density).toInt()
    private val divider = DividerView(context)

    /** 위치 이동 중일 때 놓을 자리 미리보기 */
    private var previewDock: Dock? = null
    private val previewFill = Paint().apply { color = 0x5500FFA3 }
    private val previewStroke = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 3 * density
        color = 0xFF00FFA3.toInt()
    }
    private val previewText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 18 * density
        textAlign = Paint.Align.CENTER
        setShadowLayer(4 * density, 0f, 0f, Color.BLACK)
    }

    init {
        addView(main)
        addView(side)
        addView(divider)
        setWillNotDraw(false)
    }

    private val sideVisible get() = side.visibility != View.GONE
    private val horizontal get() = dock == Dock.LEFT || dock == Dock.RIGHT

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        if (!sideVisible) {
            main.measure(exactly(w), exactly(h))
            divider.measure(exactly(0), exactly(0))
            return
        }
        divider.visibility = View.VISIBLE
        val total = if (horizontal) w else h
        val sideSize = sideSizeFor(total)
        val mainSize = (total - sideSize - dividerSize).coerceAtLeast(0)
        if (horizontal) {
            side.measure(exactly(sideSize), exactly(h))
            main.measure(exactly(mainSize), exactly(h))
            divider.measure(exactly(dividerSize), exactly(h))
        } else {
            side.measure(exactly(w), exactly(sideSize))
            main.measure(exactly(w), exactly(mainSize))
            divider.measure(exactly(w), exactly(dividerSize))
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        if (!sideVisible) {
            main.layout(0, 0, w, h)
            divider.layout(0, 0, 0, 0)
            return
        }
        val total = if (horizontal) w else h
        val s = sideSizeFor(total)
        val d = dividerSize
        when (dock) {
            Dock.RIGHT -> {
                main.layout(0, 0, w - s - d, h)
                divider.layout(w - s - d, 0, w - s, h)
                side.layout(w - s, 0, w, h)
            }
            Dock.LEFT -> {
                side.layout(0, 0, s, h)
                divider.layout(s, 0, s + d, h)
                main.layout(s + d, 0, w, h)
            }
            Dock.BOTTOM -> {
                main.layout(0, 0, w, h - s - d)
                divider.layout(0, h - s - d, w, h - s)
                side.layout(0, h - s, w, h)
            }
            Dock.TOP -> {
                side.layout(0, 0, w, s)
                divider.layout(0, s, w, s + d)
                main.layout(0, s + d, w, h)
            }
        }
    }

    private fun sideSizeFor(total: Int) = ((total - dividerSize) * ratio).toInt()

    private fun exactly(size: Int) = MeasureSpec.makeMeasureSpec(size.coerceAtLeast(0), MeasureSpec.EXACTLY)

    // ---------- 미리보기 ----------

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val target = previewDock ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        val r = when (target) {
            Dock.LEFT -> RectF(0f, 0f, w * ratio, h)
            Dock.RIGHT -> RectF(w * (1 - ratio), 0f, w, h)
            Dock.TOP -> RectF(0f, 0f, w, h * ratio)
            Dock.BOTTOM -> RectF(0f, h * (1 - ratio), w, h)
        }
        canvas.drawRect(r, previewFill)
        canvas.drawRect(r, previewStroke)
        canvas.drawText("여기에 채팅창 놓기", r.centerX(), r.centerY(), previewText)
    }

    private fun nearestDock(x: Float, y: Float): Dock {
        val w = width.coerceAtLeast(1).toFloat()
        val h = height.coerceAtLeast(1).toFloat()
        val dist = mapOf(
            Dock.LEFT to x / w,
            Dock.RIGHT to (w - x) / w,
            Dock.TOP to y / h,
            Dock.BOTTOM to (h - y) / h,
        )
        return dist.minByOrNull { it.value }!!.key
    }

    // ---------- 구분선 (크기 조절 + 이동 손잡이) ----------

    private inner class DividerView(context: Context) : View(context) {
        private val line = Paint().apply { color = 0xFF2A2B2F.toInt() }
        private val lineActive = Paint().apply { color = 0xFF00FFA3.toInt() }
        private val knobFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1F2023.toInt() }
        private val knobStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * density
            color = 0xFF00FFA3.toInt()
        }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val knobRadius = 16 * density
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private val handler = Handler(Looper.getMainLooper())

        private var mode = 0 // 0: 없음, 1: 크기 조절, 2: 위치 이동
        private var downX = 0f
        private var downY = 0f
        private var moved = false
        private val enterMoveMode = Runnable {
            if (mode == 1 && !moved) startMove()
        }

        override fun onDraw(canvas: Canvas) {
            val active = mode != 0
            val cx = width / 2f
            val cy = height / 2f
            val thickness = 2 * density
            if (width > height) {
                canvas.drawRect(0f, cy - thickness / 2, width.toFloat(), cy + thickness / 2, if (active) lineActive else line)
            } else {
                canvas.drawRect(cx - thickness / 2, 0f, cx + thickness / 2, height.toFloat(), if (active) lineActive else line)
            }
            canvas.drawCircle(cx, cy, knobRadius, knobFill)
            canvas.drawCircle(cx, cy, knobRadius, knobStroke)
            // ⠿ 모양 6점
            val g = 4.5f * density
            for (i in -1..1) for (j in 0..1) {
                val dx = if (width > height) i * g else (j - 0.5f) * g
                val dy = if (width > height) (j - 0.5f) * g else i * g
                canvas.drawCircle(cx + dx, cy + dy, 1.6f * density, dot)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            val parentX = e.x + left
            val parentY = e.y + top
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    parent.requestDisallowInterceptTouchEvent(true)
                    downX = e.rawX
                    downY = e.rawY
                    moved = false
                    val onKnob = hypot(e.x - width / 2f, e.y - height / 2f) <= knobRadius * 1.4f
                    if (onKnob) {
                        startMove()
                    } else {
                        mode = 1
                        handler.postDelayed(enterMoveMode, ViewConfiguration.getLongPressTimeout().toLong())
                    }
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(e.rawX - downX) > slop || abs(e.rawY - downY) > slop) moved = true
                    when (mode) {
                        1 -> if (moved) {
                            handler.removeCallbacks(enterMoveMode)
                            resizeTo(parentX, parentY)
                        }
                        2 -> {
                            previewDock = nearestDock(parentX, parentY)
                            this@SplitLayout.invalidate()
                        }
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(enterMoveMode)
                    if (mode == 2) {
                        val target = previewDock
                        previewDock = null
                        if (target != null && e.actionMasked == MotionEvent.ACTION_UP && moved) dock = target
                        this@SplitLayout.invalidate()
                    }
                    if (mode != 0) onChanged?.invoke(dock, ratio)
                    mode = 0
                    invalidate()
                    return true
                }
            }
            return super.onTouchEvent(e)
        }

        private fun startMove() {
            mode = 2
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            previewDock = dock
            invalidate()
            this@SplitLayout.invalidate()
        }

        private fun resizeTo(x: Float, y: Float) {
            val w = this@SplitLayout.width.toFloat()
            val h = this@SplitLayout.height.toFloat()
            ratio = when (dock) {
                Dock.RIGHT -> (w - x) / w
                Dock.LEFT -> x / w
                Dock.BOTTOM -> (h - y) / h
                Dock.TOP -> y / h
            }
        }
    }

    companion object {
        const val MIN_RATIO = 0.15f
        const val MAX_RATIO = 0.75f
    }
}
