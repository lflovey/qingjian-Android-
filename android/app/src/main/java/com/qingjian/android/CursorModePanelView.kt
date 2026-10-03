package com.qingjian.android

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast

/**
 * ★ v0.8 光标移动面板（**浅色主题**；承载图二「光标操作面板」与图三「光标平移条」两种形态）。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * 承载方式（**只替换工具栏那一条，绝不覆盖键盘区**）
 * ═══════════════════════════════════════════════════════════════════════════
 *  在 `keyboard_view.xml` 中，本 View 位于 `contentColumn` 内、与 `candidatesContainer`
 *  **平级**（`wrap_content`、初始 `GONE`），由 [QingjianImeService] 在激活时设为
 *  `VISIBLE` 并同时把 `candidatesContainer` 置 `GONE`。
 *  → 视觉上「原工具栏所在的一条被替换为本面板」，而键盘字母区（panelHost）**始终可见可点**。
 *  → **不是** AlertDialog；**不用** MATCH_PARENT 覆盖整屏；**不动** candidates_bar_height。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * 两种形态（互斥，同时只显示一个）
 * ═══════════════════════════════════════════════════════════════════════════
 *  [Mode.OPERATION]（图二，**单击**工具栏「<I>」触发）
 *     两行 5 列宫格（细边框圆角矩形 + 浅色底）：
 *       行 1：选择 | < | > | ^ | v
 *       行 2：全选 | 复制 | 粘贴 | 剪贴板 | 删除
 *     长按方向键 → 连续移动（300ms 后触发，每 50ms 一次，步长渐进加速）。
 *
 *  [Mode.SHIFT]（图三，**长按**工具栏「<I>」触发）
 *     一排三项：[<<<] [●] [>>>]，其中 ● 为浅灰**实心**圆（按图，兼作点击退出热区）。
 *     长按 <<< / >>> → 连续移动。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * 职责边界（重要）
 * ═══════════════════════════════════════════════════════════════════════════
 *  本 View **只负责 UI 与手势**，不做任何 `InputConnection` 操作、
 *  不持有光标状态、不判断边界。所有光标/编辑动作一律通过 [Listener] 回调给
 *  [QingjianImeService] 执行（服务层是唯一的光标真相源，见其 `moveCursor()`）。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * 视觉纪律
 * ═══════════════════════════════════════════════════════════════════════════
 *  ★ 当前开发版为**浅色主题**：所有配色取自 `colors.xml` 的 `cursor_*` 组
 *    （底 #FAFAFA / 按钮白底 + #C5CDD3 描边 / 文字 #37474F），
 *    **绝不写死深色**（如 #1E1E1E）——夜间模式属后续独立迭代。
 *  ★ 触控目标：宫格按钮高 44dp、平移条按钮 48×48dp、指示器热区 48dp（≥ M3 要求）。
 *
 * 日志 tag 统一 `QingjianIME`。
 */
class CursorModePanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /** 面板形态。 */
    enum class Mode {
        /** 图二：方向键 + 编辑操作宫格。 */
        OPERATION,

        /** 图三：<<< ● >>> 平移条。 */
        SHIFT
    }

    /**
     * 方向枚举（避免在回调里传裸 Int，减少误用）。
     * @param dx 水平偏移（左 -1 / 右 +1 / 无 0）
     * @param dy 垂直偏移（上 -1 / 下 +1 / 无 0）；垂直用于 `moveToLineStart/End` 之外的
     *           上下移动，由服务层按行首/行尾语义解释。
     */
    enum class Direction(val dx: Int, val dy: Int) {
        LEFT(-1, 0), RIGHT(1, 0), UP(0, -1), DOWN(0, 1)
    }

    /**
     * 面板交互回调。由宿主 [QingjianImeService] 实现。
     * 本 View 不持有任何光标状态，全部动作交由服务层执行。
     */
    interface Listener {
        /**
         * 需要移动光标。**单击**与**长按连续**都走本方法。
         * @param dir 方向
         * @param step 步长（单击恒为 1；长按连续时由本 View 渐进放大，见 [computeHoldStep]）
         */
        fun onMoveCursor(dir: Direction, step: Int)

        /** 请求退出光标模式（点指示器 / 图二的收起点 / 取消态）。 */
        fun onDismissRequested()

        /** 「选择」：进入文本选择模式（配合方向键扩展 selection）。 */
        fun onSelectModeRequested()

        /** 「全选」：`setSelection(0, textLength)`。 */
        fun onSelectAllRequested()

        /** 「复制」：把当前选区文本写入系统剪贴板。 */
        fun onCopyRequested()

        /** 「粘贴」：读取系统剪贴板文本并上屏。 */
        fun onPasteRequested()

        /** 「剪贴板」：打开本输入法的剪贴板历史面板（复用 v0.7 面板）。 */
        fun onOpenClipboardRequested()

        /** 「删除」：删除当前选中的文本（无选区时按退格）。 */
        fun onDeleteSelectionRequested()
    }

    /** 交互监听（宿主注入）。 */
    var listener: Listener? = null

    /** 当前形态。 */
    private var mode: Mode = Mode.OPERATION

    /** 是否处于「选择」扩展模式（由「选择」按钮切换；仅影响 UI 高亮，语义在服务层）。 */
    private var selectModeActive = false

    /** 宫格按钮：动作键 → Button（用于「选择」键的高亮切换）。 */
    private val actionButtons = LinkedHashMap<String, Button>()

    /** 形态根容器（两个互斥）。 */
    private var operationRoot: LinearLayout? = null
    private var shiftRoot: LinearLayout? = null

    /**
     * 长按连续移动的调度器。**面板自持一份**：
     *  - 每次按下（[ACTION_DOWN]）记录锚点并投递延迟任务；
     *  - 到 [HOLD_START_DELAY_MS] 后开始 [HOLD_REPEAT_INTERVAL_MS] 周期触发；
     *  - 抬手 / 取消 / 面板隐藏时立即移除，保证「抬手即停」。
     *  ⚠️ 与工具栏「<I>」入口的单击/长按判定**共用同一组常量**（见 companion object）。
     */
    private val holdHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 当前按住的方向（null 表示未按住）。 */
    private var holdDirection: Direction? = null

    /** 已连续触发的次数（用于步长渐进加速）。 */
    private var holdTickCount = 0

    /** 长按触发的 Runnable（每次重投递自身）。 */
    private val holdRunnable = object : Runnable {
        override fun run() {
            val dir = holdDirection ?: return
            holdTickCount++
            val step = computeHoldStep(holdTickCount)
            Log.d(
                TAG,
                "shift hold tick #$holdTickCount -> dir=$dir, step=$step " +
                    "(interval=${HOLD_REPEAT_INTERVAL_MS}ms)"
            )
            listener?.onMoveCursor(dir, step)
            holdHandler.postDelayed(this, HOLD_REPEAT_INTERVAL_MS)
        }
    }

    init {
        // 面板自带浅色底，避免与下方键盘底色断层
        setBackgroundResource(R.color.cursor_panel_surface)
        buildOperationPanel()
        buildShiftPanel()
        applyModeVisibility()
        Log.i(TAG, "CursorModePanelView init: mode=$mode, opsBuilt=${operationRoot != null}, shiftBuilt=${shiftRoot != null}")
    }

    // =========================================================================
    // 对外 API
    // =========================================================================

    /**
     * 切换到指定形态并刷新可见性。
     * 切换时**强制停止**正在进行的连续移动，避免跨形态残留 Runnable。
     */
    fun setMode(target: Mode) {
        if (mode != target) {
            Log.i(TAG, "cursor panel mode: $mode -> $target")
            stopHold("mode switch")
        }
        mode = target
        applyModeVisibility()
    }

    /** 当前形态。 */
    fun currentMode(): Mode = mode

    /** 重置临时 UI 状态（退出模式时调用：「选择」高亮、连按计时器一并复位）。 */
    fun resetUiState() {
        stopHold("resetUiState")
        if (selectModeActive) {
            selectModeActive = false
            updateSelectButtonHighlight()
        }
        Log.d(TAG, "cursor panel: resetUiState done (selectModeActive=false)")
    }

    /**
     * 宿主在隐藏面板时调用：确保**抬手即停**，绝不留下孤儿 Runnable
     * （否则面板已隐藏但光标仍在后台连续移动，属于严重副作用）。
     */
    fun onPanelHidden() {
        stopHold("panel hidden")
    }

    // =========================================================================
    // 形态构建：图二 —— 光标操作面板（两行 5 列宫格）
    // =========================================================================

    /**
     * 构建图二宫格。
     *
     * 布局：外层 [LinearLayout]（垂直）；内层两行各一个 [LinearLayout]（水平），
     * 每行 5 个等宽（`weight=1`）按钮。行内按钮间距 [R.dimen.cursor_key_gap]，
     * 上下行同样留该间距（8dp 网格的邻居值，视觉紧凑且不粘连）。
     */
    private fun buildOperationPanel() {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pv = res(R.dimen.cursor_panel_padding_v)
            val ph = res(R.dimen.cursor_panel_padding_h)
            setPadding(ph, pv, ph, pv)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }

        // ── 行 1：选择 | < | > | ^ | v ──────────────────────────────────────
        val row1 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        row1.addView(
            makeGridButton(
                key = "select",
                label = getStringRes(R.string.cursor_action_select),
                textSizeSp = 15f,
                descRes = R.string.cursor_action_select,
                isArrow = false
            ) { onActionTapped("select") }
        )
        row1.addView(makeDirectionButton("‹", "‹", Direction.LEFT, R.string.cursor_desc_left))
        row1.addView(makeDirectionButton("›", "›", Direction.RIGHT, R.string.cursor_desc_right))
        row1.addView(makeDirectionButton("^", "^", Direction.UP, R.string.cursor_desc_up))
        row1.addView(makeDirectionButton("v", "v", Direction.DOWN, R.string.cursor_desc_down))
        column.addView(row1)

        // ── 行 2：全选 | 复制 | 粘贴 | 剪贴板 | 删除 ────────────────────────
        val row2 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = res(R.dimen.cursor_key_gap) }
        }
        row2.addView(
            makeGridButton(
                key = "selectAll",
                label = getStringRes(R.string.cursor_action_select_all),
                textSizeSp = 15f,
                descRes = R.string.cursor_action_select_all,
                isArrow = false
            ) { onActionTapped("selectAll") }
        )
        row2.addView(
            makeGridButton(
                key = "copy",
                label = getStringRes(R.string.cursor_action_copy),
                textSizeSp = 15f,
                descRes = R.string.cursor_action_copy,
                isArrow = false
            ) { onActionTapped("copy") }
        )
        row2.addView(
            makeGridButton(
                key = "paste",
                label = getStringRes(R.string.cursor_action_paste),
                textSizeSp = 15f,
                descRes = R.string.cursor_action_paste,
                isArrow = false
            ) { onActionTapped("paste") }
        )
        row2.addView(
            makeGridButton(
                key = "clipboard",
                label = getStringRes(R.string.cursor_action_clipboard),
                textSizeSp = 13f,   // 「剪贴板」三字，略缩字号避免换行
                descRes = R.string.cursor_action_clipboard,
                isArrow = false
            ) { onActionTapped("clipboard") }
        )
        row2.addView(
            makeGridButton(
                key = "delete",
                label = "⌫",
                textSizeSp = 20f,
                descRes = R.string.cursor_action_delete,
                isArrow = true
            ) { onActionTapped("delete") }
        )
        column.addView(row2)

        operationRoot = column
        addView(column)
    }

    /**
     * 创建一个宫格动作按钮（细边框圆角矩形，浅色底）。
     *
     * @param key 动作标识（用于高亮与分发）
     * @param label 按钮文字
     * @param textSizeSp 文字大小（sp）
     * @param descRes 无障碍描述（M3 要求：图标/符号按钮必须有 contentDescription）
     * @param isArrow 是否为箭头/符号样式（用 [R.color.cursor_key_text]，不加粗）
     */
    private fun makeGridButton(
        key: String,
        label: String,
        textSizeSp: Float,
        descRes: Int,
        isArrow: Boolean,
        onClick: () -> Unit
    ): Button {
        val btn = Button(context).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
            setTextColor(context.getColor(R.color.cursor_key_text))
            typeface = if (isArrow) Typeface.DEFAULT else Typeface.DEFAULT_BOLD
            background = context.getDrawable(R.drawable.cursor_key_bg)
            gravity = Gravity.CENTER
            isAllCaps = false
            contentDescription = getStringRes(descRes)
            // 触控目标：宽度由 weight 撑满（≥48dp）；高度显式 44dp + 上下无额外间隔
            minWidth = 0
            minHeight = 0
            setPadding(0, 0, 0, 0)
            includeFontPadding = false
        }
        // 等分宽度；水平间距由相邻按钮的 marginStart 提供（首个为 0）
        btn.layoutParams = LinearLayout.LayoutParams(
            0,
            res(R.dimen.cursor_key_height),
            1f
        ).apply {
            val i = actionButtons.size
            if (i > 0) marginStart = res(R.dimen.cursor_key_gap)
        }
        btn.setOnClickListener { v ->
            v.playSoundEffect(android.view.SoundEffectConstants.CLICK)
            onClick()
        }
        actionButtons[key] = btn
        return btn
    }

    /**
     * 创建一个方向键（图二行 1 的 `<` `>` `^` `v`）。
     *
     * 行为（任务要求）：
     *  - **单击** → 移动 1 个字符（[Direction] 决定方向）；
     *  - **长按** → 300ms 后进入连续移动，每 50ms 一次，步长渐进放大；
     *  - **抬手** → 立即停止（移除 Runnable）。
     *
     * ⚠️ 这里**不用** `setOnLongClickListener`：系统长按阈值固定 ~500ms，且一旦触发
     *   会吞掉 click 事件，与「单击也要生效」冲突。改用 `OnTouchListener` 手动计时，
     *   与工具栏「<I>」入口共用同一套常量，行为可预期。
     */
    private fun makeDirectionButton(
        label: String,
        arrow: String,
        dir: Direction,
        descRes: Int
    ): Button {
        val btn = Button(context).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTextColor(context.getColor(R.color.cursor_key_text))
            typeface = Typeface.DEFAULT
            background = context.getDrawable(R.drawable.cursor_key_bg)
            gravity = Gravity.CENTER
            isAllCaps = false
            contentDescription = getStringRes(descRes)
            minWidth = 0
            minHeight = 0
            setPadding(0, 0, 0, 0)
            includeFontPadding = false
        }
        btn.layoutParams = LinearLayout.LayoutParams(
            0,
            res(R.dimen.cursor_key_height),
            1f
        ).apply { marginStart = res(R.dimen.cursor_key_gap) }

        attachHoldTouchHandler(btn, dir)
        return btn
    }

    // =========================================================================
    // 形态构建：图三 —— 光标平移条（<<< ● >>>）
    // =========================================================================

    /**
     * 构建图三平移条。
     *
     * 布局（**一排三项，整体居中**）：
     *   [<<< 48×48 圆角方形] [实心圆 ● 24dp，热区 48dp] [>>> 48×48 圆角方形]
     *
     * ★ 中间圆点为**实心浅灰圆**（按参考图三；用户已裁定「按图做实心」），
     *   无描边；同时是「点击退出光标模式」的热区（contentDescription 已说明）。
     */
    private fun buildShiftPanel() {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val pv = res(R.dimen.cursor_panel_padding_v)
            val ph = res(R.dimen.cursor_panel_padding_h)
            setPadding(ph, pv, ph, pv)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }

        // 左：<<<
        row.addView(makeShiftButton(R.drawable.ic_cursor_triple_left, Direction.LEFT, R.string.cursor_desc_shift_left))

        // 中：实心圆指示器（外框 48dp 热区，内部 24dp 圆）
        val indicatorHost = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                res(R.dimen.cursor_indicator_touch),
                res(R.dimen.cursor_indicator_touch)
            )
            contentDescription = getStringRes(R.string.cursor_desc_indicator)
            isClickable = true
        }
        val dot = View(context).apply {
            background = context.getDrawable(R.drawable.cursor_indicator_bg)
            layoutParams = LayoutParams(
                res(R.dimen.cursor_indicator_size),
                res(R.dimen.cursor_indicator_size),
                Gravity.CENTER
            )
        }
        indicatorHost.addView(dot)
        indicatorHost.setOnClickListener { v ->
            v.playSoundEffect(android.view.SoundEffectConstants.CLICK)
            Log.i(TAG, "cursor shift bar: indicator tapped -> dismiss requested")
            listener?.onDismissRequested()
        }
        row.addView(indicatorHost)

        // 右：>>>
        row.addView(makeShiftButton(R.drawable.ic_cursor_triple_right, Direction.RIGHT, R.string.cursor_desc_shift_right))

        shiftRoot = row
        addView(row)
    }

    /**
     * 创建一个平移条按钮（矢量图标 + 圆角底，长按连续移动）。
     * 图标用 VectorDrawable（[ic_cursor_triple_left] / [ic_cursor_triple_right]），
     * **不用 Unicode 字符拼接**——不同字体下三箭头比例会崩，真机不可控。
     */
    private fun makeShiftButton(iconRes: Int, dir: Direction, descRes: Int): Button {
        val btn = Button(context).apply {
            // 用 drawableTop 承载矢量图；文字留空
            setCompoundDrawablesWithIntrinsicBounds(0, iconRes, 0, 0)
            compoundDrawableTintList = android.content.res.ColorStateList.valueOf(
                context.getColor(R.color.cursor_shift_key_text)
            )
            background = context.getDrawable(R.drawable.cursor_shift_key_bg)
            gravity = Gravity.CENTER
            isAllCaps = false
            contentDescription = getStringRes(descRes)
            text = ""
            minWidth = 0
            minHeight = 0
            setPadding(0, 0, 0, 0)
            includeFontPadding = false
        }
        btn.layoutParams = LinearLayout.LayoutParams(
            res(R.dimen.cursor_shift_button_size),
            res(R.dimen.cursor_shift_button_size)
        )
        attachHoldTouchHandler(btn, dir)
        return btn
    }

    // =========================================================================
    // 长按连续移动（统一实现，宫格方向键与平移条共用）
    // =========================================================================

    /**
     * 给按钮挂「单击 + 长按连续」手势。
     *
     * 时序：
     *   ACTION_DOWN  → 记录方向，投递 [HOLD_START_DELAY_MS] 延迟任务；
     *   （若在延迟内抬手）ACTION_UP → 取消延迟任务 → **执行一次单击移动（步长 1）**；
     *   （若超过延迟）holdRunnable 启动 → 每 [HOLD_REPEAT_INTERVAL_MS] 触发一次；
     *   ACTION_UP / ACTION_CANCEL → 移除 Runnable，[holdDirection] 置 null。
     *
     * ★ 关键：`performClick()` 在 ActionUp 时手动调用一次，既满足无障碍服务，
     *   也保证「长按结束后系统不会再补一次 click」——因为这里**没有** setOnClickListener，
     *   全程由 touch 事件独占，不存在双击式重复触发。
     *
     * @return 恒 true（本 View 消费全部触摸事件）
     */
    private fun attachHoldTouchHandler(btn: Button, dir: Direction) {
        btn.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    holdDirection = dir
                    holdTickCount = 0
                    Log.d(
                        TAG,
                        "hold DOWN: dir=$dir (startDelay=${HOLD_START_DELAY_MS}ms, " +
                            "interval=${HOLD_REPEAT_INTERVAL_MS}ms)"
                    )
                    holdHandler.postDelayed(holdRunnable, HOLD_START_DELAY_MS)
                    true
                }

                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    val wasHolding = holdTickCount > 0
                    if (wasHolding) {
                        // 已进入连续移动：抬手只停，不再补一次单击（避免多移一格）
                        Log.d(TAG, "hold UP: stop after $holdTickCount ticks, dir=$dir")
                    } else {
                        // 未进入长按 → 判定为**单击**：移动 1 个字符
                        Log.d(TAG, "tap UP (no hold): single move, dir=$dir, step=1")
                        listener?.onMoveCursor(dir, 1)
                    }
                    stopHold("ACTION_UP")
                    v.performClick()
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    Log.d(TAG, "hold CANCEL: dir=$dir, ticks=$holdTickCount")
                    stopHold("ACTION_CANCEL")
                    true
                }

                else -> false
            }
        }
    }

    /** 停止连续移动并复位计时状态（幂等）。 */
    private fun stopHold(reason: String) {
        if (holdDirection != null || holdHandler.hasCallbacks(holdRunnable)) {
            Log.d(TAG, "hold STOP: reason=$reason, dir=$holdDirection, ticks=$holdTickCount")
        }
        holdHandler.removeCallbacks(holdRunnable)
        holdDirection = null
        holdTickCount = 0
    }

    /**
     * 长按加速：**渐进放大步长**，让「快速连续移动」覆盖更远距离。
     *
     * 规则（分段，避免早期就跳格导致手感失控）：
     *   tick 1–5   → 1 个字符（精细微调期，覆盖前 250ms）
     *   tick 6–12  → 2 个字符
     *   tick 13–20 → 3 个字符
     *   tick 21+   → 5 个字符（长按高速平移）
     *
     * ★ 与任务要求「初始步长为 1 个字符，快速连续移动时步长可以适当放大」一致。
     * ★ 步长由**服务层**最终裁决边界（`Math.max(0, Math.min(...))`），此处只给建议值。
     */
    private fun computeHoldStep(tick: Int): Int = when {
        tick <= 5 -> 1
        tick <= 12 -> 2
        tick <= 20 -> 3
        else -> 5
    }

    // =========================================================================
    // 图二 动作键分发
    // =========================================================================

    /** 宫格动作键统一入口（已在按钮 click 里播放按键音）。 */
    private fun onActionTapped(key: String) {
        Log.i(TAG, "cursor ops: action tapped '$key' (selectModeActive=$selectModeActive)")
        when (key) {
            "select" -> {
                selectModeActive = !selectModeActive
                updateSelectButtonHighlight()
                Log.i(TAG, "cursor ops: select (extend selection) mode -> $selectModeActive")
                listener?.onSelectModeRequested()
            }
            "selectAll" -> listener?.onSelectAllRequested()
            "copy" -> listener?.onCopyRequested()
            "paste" -> listener?.onPasteRequested()
            "clipboard" -> listener?.onOpenClipboardRequested()
            "delete" -> listener?.onDeleteSelectionRequested()
            else -> Log.w(TAG, "cursor ops: unhandled action key '$key'")
        }
    }

    /**
     * 「选择」键高亮：激活时切换为蓝底白字（复用 v0.6 `mode_card_current_bg` 的蓝），
     * 明确告知用户「方向键现在是扩展选区，而不是移动插入点」。
     * 未激活时恢复默认浅色描边样式。
     */
    private fun updateSelectButtonHighlight() {
        val btn = actionButtons["select"] ?: return
        if (selectModeActive) {
            btn.background = null
            btn.setBackgroundColor(context.getColor(R.color.mode_card_current_bg))
            btn.setTextColor(context.getColor(R.color.mode_card_current_text))
        } else {
            btn.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            btn.background = context.getDrawable(R.drawable.cursor_key_bg)
            btn.setTextColor(context.getColor(R.color.cursor_key_text))
        }
        btn.isSelected = selectModeActive
    }

    /** 供服务层查询：当前是否处于「选择扩展」模式（决定方向键语义）。 */
    fun isSelectModeActive(): Boolean = selectModeActive

    // =========================================================================
    // 形态可见性
    // =========================================================================

    /** 两个形态根互斥显示。 */
    private fun applyModeVisibility() {
        operationRoot?.visibility = if (mode == Mode.OPERATION) View.VISIBLE else View.GONE
        shiftRoot?.visibility = if (mode == Mode.SHIFT) View.VISIBLE else View.GONE
    }

    // =========================================================================
    // 小工具
    // =========================================================================

    private fun res(id: Int): Int = resources.getDimensionPixelSize(id)

    private fun getStringRes(id: Int): String = context.getString(id)

    /** 本地 Toast（本 View 只做 UI 反馈，不参与业务判定）。 */
    @Suppress("unused")
    private fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val TAG = "QingjianIME"

        /**
         * 长按判定阈值：按下后 [HOLD_START_DELAY_MS] 毫秒开始连续移动。
         * ★ 与工具栏「<I>」入口的单击/长按判定**共用同一常量**（任务要求）。
         */
        const val HOLD_START_DELAY_MS = 300L

        /** 连续移动间隔。 */
        const val HOLD_REPEAT_INTERVAL_MS = 50L
    }
}
