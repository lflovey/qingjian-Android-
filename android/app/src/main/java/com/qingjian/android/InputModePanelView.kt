package com.qingjian.android

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.TextView

/**
 * ★ v0.6 输入方式选择面板（深色卡片网格，百度输入法深色主题）。
 *
 * 这是「切换输入方式」新规格的**对外唯一入口**：继承 [FrameLayout]，以代码 inflate
 * `mode_panel.xml`（顶部栏 + 4 列 `GridLayout` 卡片网格），并以 [InputMode.gridOrder]
 * 动态填充卡片（单一数据源，不在 XML 硬编码清单）。
 *
 * ── 承载方式（满足「整键盘区被替换、完全遮挡原键盘/候选栏」）──────────────────
 *  在 `keyboard_view.xml` 中，本 View 是根 [FrameLayout] 的**第二个直接子项**，
 *  `layout_width/height = MATCH_PARENT`、初始 `GONE`。展开时由 [QingjianImeService]
 *  设为 `VISIBLE` 并 `bringToFront()`，天然叠在「键盘内容列」之上、铺满整个键盘区。
 *  → **不是** AlertDialog，**不是**在候选栏内 addView。
 *
 * ── 三态视觉（深色规格）──────────────────────────────────────────────────────
 *  - 当前方式：卡片蓝底 #1A73E8，图标/文字白色，角标反色（白底蓝字）；
 *  - 可用未选中：卡片 #2C2C2C；
 *  - 无数据支持：卡片 #222222、文字 #666666，点击仅回调 [Listener.onModePicked]
 *    由宿主弹 Toast，**不可切换**（宿主按 [InputMode.supported] 与探测结果判定）。
 *
 * ── 对外契约（[Listener]）─────────────────────────────────────────────────────
 *  - [Listener.onModePicked]：点击某张卡片（含禁用卡——由宿主决定是否切换 / 弹 Toast）。
 *  - [Listener.onDismissRequested]：点返回箭头 / 点面板空白处 → 请求收起（输入方式不变）。
 *
 * ★ 注意：物理返回键不由本 View 处理，由 [QingjianImeService.onBackPressed] /
 *   [QingjianImeService.onKeyDown] 双通道兜住（HyperOS 两条通道行为不一致）。
 *
 * 日志 tag 统一 [TAG]（QingjianIME）。
 */
class InputModePanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /**
     * 面板交互回调。由宿主 [QingjianImeService] 实现：
     *  - [onModePicked]：用户点了某张卡（宿主负责判定可用性、切换或 Toast）。
     *  - [onDismissRequested]：用户在面板内请求收起（返回箭头 / 点空白处）。
     */
    interface Listener {
        /** 点击某输入方式卡片。 */
        fun onModePicked(mode: InputMode)

        /** 请求收起面板（回到原键盘，输入方式不变）。 */
        fun onDismissRequested()
    }

    /** 交互监听（宿主注入；未注入时点击仅打日志）。 */
    var listener: Listener? = null

    /** 顶部返回箭头（暴露便于宿主复用，正常由本类内部挂监听）。 */
    private var backButton: ImageButton? = null

    /** 顶部设置齿轮（宿主如需打开设置页可复用此 View 挂监听）。 */
    var settingsButton: ImageButton? = null
        private set

    /** 中间「键盘」标签（当前高亮）。 */
    private var tabKeyboard: TextView? = null

    /** 中间「布局」标签（占位）。 */
    private var tabLayout: TextView? = null

    /** 4 列网格容器。 */
    private var grid: GridLayout? = null

    /** 面板根（点空白处收起）。 */
    private var panelRoot: View? = null

    /** 卡片 View 缓存：InputMode → 卡片（刷新状态时复用，避免重复 inflate）。 */
    private val cardViews = LinkedHashMap<InputMode, ModeCardButton>()

    /** 当前输入方式（宿主通过 [setCurrentMode] 同步；用于高亮）。 */
    private var currentMode: InputMode = InputMode.DEFAULT

    /** 可用性判定回调：宿主返回某方式当前是否可用（含五笔探测结果）。 */
    var usabilityProvider: ((InputMode) -> Boolean)? = null

    /** 不可用原因回调：宿主返回某方式的置灰原因文案。 */
    var unavailableReasonProvider: ((InputMode) -> String)? = null

    init {
        LayoutInflater.from(context).inflate(R.layout.mode_panel, this, true)
        wireInternalViews()
        renderCards()
        Log.i(TAG, "InputModePanelView init: cards=${cardViews.size}")
    }

    /** 找到子视图并挂「返回 / 标签 / 设置 / 空白处」监听。 */
    private fun wireInternalViews() {
        panelRoot = findViewById(R.id.modePanelRoot)
        grid = findViewById(R.id.modePanelGrid)
        backButton = findViewById(R.id.btnModePanelBack)
        settingsButton = findViewById(R.id.btnModePanelSettings)
        tabKeyboard = findViewById(R.id.tabKeyboard)
        tabLayout = findViewById(R.id.tabLayout)

        // 返回箭头 → 请求收起（输入方式不变）
        backButton?.setOnClickListener {
            Log.i(TAG, "input mode panel: back arrow tapped -> dismiss requested")
            listener?.onDismissRequested()
        }

        // 「键盘」标签：当前已是选中态，点按无副作用
        tabKeyboard?.setOnClickListener {
            Log.d(TAG, "input mode panel: tab 'keyboard' (already active)")
        }
        // 「布局」标签：占位
        tabLayout?.setOnClickListener {
            Log.d(TAG, "input mode panel: tab 'layout' is placeholder in v0.6")
        }

        // 点面板空白处 → 请求收起（子卡片会先消费点击，不会冒泡到这里）
        panelRoot?.setOnClickListener {
            Log.i(TAG, "input mode panel: blank area tapped -> dismiss requested")
            listener?.onDismissRequested()
        }
    }

    /**
     * 按 [InputMode.gridOrder] 渲染 4 列卡片网格。
     * 幂等：重复调用先清空再重建，保证与枚举顺序一致。
     */
    private fun renderCards() {
        val g = grid ?: run {
            Log.e(TAG, "renderCards: grid not found! panel cards unavailable")
            return
        }
        g.removeAllViews()
        cardViews.clear()

        val density = resources.displayMetrics.density
        val gap = (10 * density).toInt()
        val columns = InputMode.GRID_COLUMNS

        for ((index, mode) in InputMode.gridOrder.withIndex()) {
            val card = ModeCardButton(context).apply {
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    height = (68 * density).toInt()
                    // 4 列等宽：columnSpec 均分权重 1；行列间距用 margins 表达
                    columnSpec = GridLayout.spec(index % columns, 1f)
                    rowSpec = GridLayout.spec(index / columns)
                    setMargins(gap / 2, gap / 2, gap / 2, gap / 2)
                }
            }
            // ★ 修订版：通过卡片自持回调接线（含禁用卡）。
            //   禁用卡在修订前因 isEnabled=false 导致框架不派发触摸 → 监听永不触发 → Toast 弹不出；
            //   现卡片恒 enabled=true 且自挂 [ModeCardButton.performClick] 转发，点击可达。
            //   可用性判断下沉到宿主：onModePicked → Service.onModeCardClicked 判定「切换 or 仅 Toast」。
            card.onCardClickListener = { c ->
                Log.i(
                    TAG,
                    "input mode panel: card tapped id=${mode.id}, label=${mode.label}, " +
                        "usable=${c.isUsable} (delegate to host)"
                )
                listener?.onModePicked(mode)
            }
            g.addView(card)
            cardViews[mode] = card
        }
        refresh()
        Log.i(
            TAG,
            "renderCards: built ${cardViews.size} cards, order=${InputMode.gridOrder.map { it.id }}"
        )
    }

    /** 设置当前高亮方式并刷新三态。 */
    fun setCurrentMode(mode: InputMode) {
        currentMode = mode
        refresh()
    }

    /**
     * 刷新所有卡片的三态：当前方式高亮 / 可用 / 禁用。
     * 可用性与原因来自宿主注入的 [usabilityProvider] / [unavailableReasonProvider]；
     * 未注入时回退到枚举自带的 [InputMode.supported] 与 [InputMode.unavailableHint]。
     */
    fun refresh() {
        for ((mode, card) in cardViews) {
            val usable = usabilityProvider?.invoke(mode) ?: mode.supported
            val reason = unavailableReasonProvider?.invoke(mode) ?: mode.unavailableHint
            card.bind(
                mode = mode,
                isCurrent = (mode == currentMode),
                isUsable = usable,
                disabledReason = reason
            )
        }
        Log.d(
            TAG,
            "refresh: current=${currentMode.id}, " +
                cardViews.map { "${it.key.id}=${(usabilityProvider?.invoke(it.key) ?: it.key.supported)}" }
        )
    }

    /** dp→px 便捷换算（供子类/调用方复用）。 */
    fun dpToPx(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
}
