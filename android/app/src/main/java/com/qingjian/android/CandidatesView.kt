package com.qingjian.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.TextPaint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

/**
 * 候选栏自定义 View。
 *
 * 每个候选显示「词 + pos + gloss」注释（gloss/pos 为空则不显示对应部分），
 * 当前选中项（默认首候选，即 score 最高项）高亮；支持横向滑动浏览、点选上屏、
 * 长按触发翻译查询（nativeTranslate）。
 */
class CandidatesView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** 点选候选回调（index 对应 nativePinyinInput 返回数组下标） */
    var onCandidateClick: ((Int) -> Unit)? = null

    /** 长按候选回调（用于查词翻译） */
    var onCandidateLongClick: ((Int) -> Unit)? = null

    private val wordPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(18f)
        color = 0xFF212121.toInt()
    }
    private val annPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(12f)
        color = 0xFF757575.toInt()
    }
    private val composingPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(16f)
        color = 0xFF2E7D32.toInt()
        typeface = Typeface.DEFAULT_BOLD
    }
    private val highlightPaint = Paint().apply { color = 0x262E7D32 }
    private val dividerPaint = Paint().apply {
        color = 0xFFE0E0E0.toInt()
        strokeWidth = dp(1f)
    }

    private var candidates: List<Candidate> = emptyList()
    private var selected = 0
    private var composingText = ""

    /** 各候选项的逻辑矩形（不含 scrollX 偏移，命中测试时加回 scrollX） */
    private val itemBounds = ArrayList<RectF>()
    private var contentWidth = 0f

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val handler = Handler(Looper.getMainLooper())
    private var downX = 0f
    private var downScroll = 0
    private var moved = false
    private val longPressRunnable = Runnable {
        val idx = hitTest(downX + downScroll)
        if (idx >= 0) onCandidateLongClick?.invoke(idx)
    }

    /** 更新候选数据并回到最左端 */
    fun setData(list: List<Candidate>, selectedIndex: Int, composing: String) {
        candidates = list
        selected = selectedIndex
        composingText = composing
        scrollTo(0, 0)
        invalidate()
    }

    private fun sp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private fun dp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    /**
     * 候选栏高度：
     *  - v0.1 由框架经 onCreateCandidatesView 挂载时给的是 EXACTLY spec；
     *  - v0.2 改为被动加入键盘主视图的 FrameLayout，spec 变为 AT_MOST/UNSPECIFIED。
     *    `resolveSize(desiredH, heightMeasureSpec)` 对两者都能正确解析：
     *    AT_MOST 取 min(desired, size)，UNSPECIFIED 直接用 desired（48dp）。
     * 这里宽度同样用 resolveSize 兜住 AT_MOST 情况。
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredW = MeasureSpec.getSize(widthMeasureSpec)
        val desiredH = (dp(48f) + paddingTop + paddingBottom).toInt()
        setMeasuredDimension(
            resolveSize(desiredW, widthMeasureSpec),
            resolveSize(desiredH, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        itemBounds.clear()
        val padH = dp(12f)
        val gap = dp(6f)
        val fm = wordPaint.fontMetrics
        val baseline = height / 2f - (fm.ascent + fm.descent) / 2f
        var lx = paddingLeft.toFloat()  // 逻辑 x（不含滚动偏移）

        // 左侧回显当前拼音串
        if (composingText.isNotEmpty()) {
            canvas.drawText(composingText, lx + padH - scrollX, baseline, composingPaint)
            lx += padH * 2 + composingPaint.measureText(composingText)
            canvas.drawLine(lx - scrollX, height * 0.25f, lx - scrollX, height * 0.75f, dividerPaint)
        }

        candidates.forEachIndexed { i, c ->
            val ann = c.annotation
            val wordW = wordPaint.measureText(c.word)
            val annW = if (ann.isEmpty()) 0f else gap + annPaint.measureText(ann)
            val itemW = padH * 2 + wordW + annW
            val rect = RectF(lx, 0f, lx + itemW, height.toFloat())
            itemBounds.add(rect)
            if (i == selected) {
                canvas.drawRect(rect.left - scrollX, dp(4f), rect.right - scrollX, height - dp(4f), highlightPaint)
            }
            canvas.drawText(c.word, lx + padH - scrollX, baseline, wordPaint)
            if (ann.isNotEmpty()) {  // gloss/pos 空则不显示注释
                canvas.drawText(ann, lx + padH + wordW + gap - scrollX, baseline, annPaint)
            }
            if (i < candidates.size - 1) {
                canvas.drawLine(lx + itemW - scrollX, height * 0.25f, lx + itemW - scrollX, height * 0.75f, dividerPaint)
            }
            lx += itemW
        }
        contentWidth = lx + paddingRight
    }

    private fun maxScroll(): Float = (contentWidth - width).coerceAtLeast(0f)

    private fun hitTest(logicalX: Float): Int {
        if (logicalX < 0) return -1
        return itemBounds.indexOfFirst { logicalX >= it.left && logicalX < it.right }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downScroll = scrollX
                moved = false
                handler.postDelayed(longPressRunnable, LONG_PRESS_MS)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = downX - event.x
                if (!moved && abs(dx) > touchSlop) {
                    moved = true
                    handler.removeCallbacks(longPressRunnable)
                }
                if (moved) {
                    val target = (downScroll + dx).toInt().coerceIn(0, maxScroll().toInt())
                    scrollTo(target, 0)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPressRunnable)
                if (!moved) {
                    val idx = hitTest(event.x + scrollX)
                    if (idx >= 0) {
                        performClick()
                        onCandidateClick?.invoke(idx)
                    }
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    companion object {
        private const val LONG_PRESS_MS = 500L
    }
}
