package com.qingjian.android

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * ★ v0.6 输入方式「卡片」View（深色主题，模仿百度输入法深色网格卡片）。
 *
 * 每个卡片是一枚**圆角矩形**，内部竖向三段：
 *
 *     ┌───────────────┐
 *     │            (角标)│  ← 右上角圆形角标：拼/英/五/写/文/🌐
 *     │      ✍ 图标     │  ← 中央图标字符
 *     │   拼音26键      │  ← 标签（label）
 *     │  全拼 · 26 键   │  ← 说明（desc，小字）
 *     └───────────────┘
 *
 * ── 三态由 [bind] 依据「是否当前方式 / 是否可用」自动切换（深色）──────────────
 *  - **当前方式**：`isSelected = true` → 规格蓝底 #1A73E8 + 白字；角标**反色**（白底蓝字）；
 *  - **可用未选中**：卡底 #2C2C2C + 浅字；
 *  - **禁用（无数据/占位）**：卡底 #222222 + 文字 #666666（**视觉置灰**）。
 *
 * ── ★ v0.6 修订版：禁用卡「可点击」缺陷修复（方案 A）─────────────────────────
 *  背景缺陷：**Android 框架契约**规定 `View.isEnabled == false` 时，
 *  `View.dispatchTouchEvent()` 对 `ACTION_DOWN` **直接返回 false、不消费触摸事件**，
 *  甚至连 `onTouchEvent()` 都不触发，因此 `OnClickListener` **永远不会被调用**
 *  → 置灰卡点击弹不出 Toast，规格第二条「点击仅弹 Toast 提示」未实现。
 *
 *  修复（方案 A，采用理由见 CHANGELOG_V0.6.md「验收后修复」）：
 *   1. **卡片恒 `isEnabled = true`**（保证触摸事件被消费、点击可达）；
 *   2. **禁用外观改由代码显式渲染**——不再依赖 `state_enabled=false` selector 分支，
 *      而是把「禁用态底/字/角标」直接写进 [applyVisualState]（背景走 [mode_card_bg] 的
 *      `state_selected` / 默认 两分支，禁用时另铺 `mode_card_disabled_bg`；
 *      文字统一用 `mode_card_disabled_text`）；
 *   3. **可用性判断下沉到点击处理**——`supported == false` 时只回调宿主弹 Toast，
 *      **绝不调用 `applyMode` / 改 `inputMode` / 调 `native_set_mode`**（宿主 [QingjianImeService] 侧保证）。
 *
 *  【为什么不用 `setOnClickListener` 直接挂禁用卡？】见 [onTouchEvent] 注释：
 *  `View.isClickable == true` 时框架会把 `ACTION_DOWN` 当作按下态、消费它，而
 *  `ACTION_UP` 由框架转成 `performClick()`。若我们在 `ACTION_DOWN` 里就执行逻辑，
 *  会破坏「按下 — 抬起」的语义（也丢失点按反馈）；故这里**保留框架默认点击路径**，
 *  仅在 `ACTION_UP` 时**不消费事件以允许冒泡**（禁用卡不响应、也不误触面板收起），
 *  真正的逻辑统一挂在 [onCardClickListener] 上（框架 [performClick] 会回调它）。
 *
 *  卡片本身是 [FrameLayout]：底层是背景 drawable，其上叠一个竖向 LinearLayout（图标+文字）
 *  和一个定位到右上角的角标 TextView。整体对**所有卡片**（含禁用卡）`clickable=true`。
 *
 *  该 View 由 `InputModePanelView.renderCards()` 代码创建，走 [bind] 单一入口，
 *  保证卡片与 [InputMode] 枚举单一数据源。
 */
class ModeCardButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val iconView: TextView
    private val titleView: TextView
    private val subtitleView: TextView
    private val badgeView: TextView

    /** 本卡片对应的输入方式（由 [bind] 设置；未绑定前为 null）。 */
    var mode: InputMode? = null
        private set

    /** ★ 修订版：本卡片当前是否可用（决定禁用外观；可用性判断已下沉到宿主点击处理）。 */
    var isUsable: Boolean = true
        private set

    /**
     * ★ 修订版：卡片点击回调（对**所有**卡片生效，含禁用卡）。
     *
     * 由 [InputModePanelView.renderCards] 注入；宿主在回调里按可用性判定「切换 or 仅 Toast」。
     * 之所以自持回调（而非让调用方 `setOnClickListener`），是为了让 [onTouchEvent] /
     * [performClick] 与点击语义在**同一模块内闭环**，避免外部误覆盖带来的行为漂移。
     */
    var onCardClickListener: ((ModeCardButton) -> Unit)? = null

    init {
        // 卡片根：★ 恒可点击（修订版关键）——禁用卡若 isEnabled=false 会导致框架不派发
        // 触摸事件，OnClickListener 永不触发，Toast 弹不出（见类注释背景缺陷说明）。
        isClickable = true
        isFocusable = true
        // 背景：默认铺 mode_card_bg（含 pressed / selected / enabled=false / 默认 分支；
        // 修订版下 enabled 恒为 true，故实际命中 pressed / selected / 默认 三分支，
        // 禁用态底由 applyVisualState() 显式覆盖为 mode_card_disabled_bg）。
        setBackgroundResource(R.drawable.mode_card_bg)
        clipToPadding = false
        setPadding(dp(6f).toInt(), dp(6f).toInt(), dp(6f).toInt(), dp(6f).toInt())

        // 竖向内容：图标 / 标签 / 说明
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }

        iconView = TextView(context).apply {
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(context.getColor(R.color.mode_card_text))
            isClickable = false   // 子 View 不拦截点击，保证整个卡片区域可点
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        titleView = TextView(context).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(context.getColor(R.color.mode_card_text))
            isClickable = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2f).toInt() }
        }
        subtitleView = TextView(context).apply {
            textSize = 10f
            gravity = Gravity.CENTER
            maxLines = 1
            setTextColor(context.getColor(R.color.mode_card_badge_text))
            isClickable = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(1f).toInt() }
        }

        column.isClickable = false
        column.addView(iconView)
        column.addView(titleView)
        column.addView(subtitleView)
        addView(column)

        // 右上角圆形角标
        badgeView = TextView(context).apply {
            textSize = 10f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(context.getColor(R.color.mode_card_badge_text))
            setBackgroundResource(R.drawable.mode_card_badge_bg)
            isClickable = false
            val size = dp(18f).toInt()
            layoutParams = LayoutParams(size, size).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = dp(4f).toInt()
                rightMargin = dp(4f).toInt()
            }
        }
        addView(badgeView)

        // 卡片固定高度兜底（实际由 GridLayout.LayoutParams 覆盖）
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(68f).toInt()
        )
    }

    /**
     * 绑定一个输入方式并刷新三态。
     *
     * @param mode       对应输入方式（决定图标/角标/文字）。
     * @param isCurrent  是否为**当前正在使用**的方式 → 高亮（规格蓝底 #1A73E8 + 白字）。
     * @param isUsable   是否可用（true 正常卡；false 置灰卡，点击由宿主仅弹 Toast、不切换）。
     * @param disabledReason 置灰原因（无数据项为 null，走「敬请期待」文案）。
     */
    fun bind(mode: InputMode, isCurrent: Boolean, isUsable: Boolean, disabledReason: String?) {
        this.mode = mode
        this.isUsable = isUsable
        isSelected = isCurrent
        // ★ 修订版：恒置 enabled=true（保证触摸事件被消费、点击可达）。
        //   禁用外观不再依赖 state_enabled=false 分支，改由 applyVisualState() 显式渲染。
        isEnabled = true

        iconView.text = mode.icon
        titleView.text = mode.label
        badgeView.text = mode.badge

        // 说明行：可用 → 正常说明；不可用 → 原因（无数据项显示「敬请期待」）
        subtitleView.text = when {
            isUsable -> mode.desc
            mode.isPlaceholder -> "敬请期待"
            else -> disabledReason ?: "暂未支持"
        }

        // 三态配色（当前 / 可用 / 禁用）——深色规格（统一走 applyVisualState 单一入口）
        badgeView.isSelected = isCurrent
        applyVisualState(isCurrent = isCurrent, isUsable = isUsable)

        // 供无障碍/调试：内容描述含状态
        contentDescription = buildString {
            append(mode.label)
            if (isCurrent) append("（当前）")
            if (!isUsable) append("（不可用）")
        }
    }

    /** 别名：v0.6 规格字段名 label 的矩形绑定入口（内部与 [bind] 等价）。 */
    fun bindMode(mode: InputMode, isCurrent: Boolean, isUsable: Boolean, disabledReason: String?) =
        bind(mode, isCurrent, isUsable, disabledReason)

    /**
     * ★ 修订版：单一入口显式渲染三态外观（深色规格）。
     *
     * 关键约束——**禁用外观必须保持**：即使本卡片 `isEnabled == true`（为了可点击），
     * 只要 `isUsable == false` 就必须渲染成禁用态：
     *  - 卡底 = `mode_card_disabled_bg`（#222222）；
     *  - 图标/标签/说明 = `mode_card_disabled_text`（#666666）；
     *  - 角标底 = `mode_card_disabled_bg`（#222222）+ 1dp `mode_card_stroke` 描边（与旧 selector 一致）；
     *  - 再压 alpha=0.85（与修订前一致，强化区分）。
     *
     * 可用卡：当前项蓝底白字、角标白底蓝字（反色）；未选中项走默认卡底（#2C2C2C）+ 浅字。
     */
    private fun applyVisualState(isCurrent: Boolean, isUsable: Boolean) {
        // 1) 文字色
        val mainTextColor: Int = when {
            isCurrent -> context.getColor(R.color.mode_card_current_text)   // 当前：白
            isUsable -> context.getColor(R.color.mode_card_text)            // 可用：浅灰白
            else -> context.getColor(R.color.mode_card_disabled_text)       // 禁用：#666666
        }
        iconView.setTextColor(mainTextColor)
        titleView.setTextColor(mainTextColor)
        subtitleView.setTextColor(
            when {
                isCurrent -> context.getColor(R.color.mode_card_current_text)
                isUsable -> context.getColor(R.color.mode_card_badge_text)
                else -> context.getColor(R.color.mode_card_disabled_text)
            }
        )

        // 2) 角标文字色：当前项「反色」→ 蓝字；禁用 → #666666；可用 → 浅字。
        badgeView.setTextColor(
            when {
                isCurrent -> context.getColor(R.color.mode_card_badge_current_text) // 蓝
                isUsable -> context.getColor(R.color.mode_card_badge_text)           // 浅
                else -> context.getColor(R.color.mode_card_disabled_text)            // #666666
            }
        )

        // 3) 禁用外观（显式渲染，不依赖 selector 的 state_enabled 分支）
        if (!isUsable) {
            if (!isSelected) {
                // 仅当不是「当前项」（可用性互斥：当前项必然可用，此分支一般不会命中）才铺禁用底
                setBackgroundColor(context.getColor(R.color.mode_card_disabled_bg))
            }
            // 角标：禁用圆底 #222222 + 协调描边（与修订前 selector 的 state_enabled=false 分支一致）
            badgeView.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(context.getColor(R.color.mode_card_disabled_bg))
                setStroke(dp(1f).toInt(), context.getColor(R.color.mode_card_stroke))
            }
        } else {
            // 可用卡：恢复 selector 背景（含 pressed / selected / 默认 分支）
            setBackgroundResource(R.drawable.mode_card_bg)
            badgeView.setBackgroundResource(R.drawable.mode_card_badge_bg)
        }

        // 4) 视觉再压一档（禁用项更暗），与修订前保持一致
        alpha = if (isUsable) 1f else 0.85f
    }

    /**
     * ★ 修订版：触摸处理。
     *
     * `isClickable == true` 时框架 `dispatchTouchEvent` 会把 `ACTION_DOWN` 交给
     * `onTouchEvent`：默认实现会置 pressed 态并消费 DOWN，随后 UP 转 `performClick()`。
     * 由于本类**禁用卡也是 `isEnabled=true`**，框架会正常派发事件，点击可达。
     *
     * 这里对 **禁用卡**额外做一处处理：`ACTION_UP` **不消费事件**（`return false`），
     * 使事件按规则冒泡（不触发空白处收起逻辑，因为父 [InputModePanelView] 的 grid 不处理触摸、
     * 面板空白收起只挂在 modePanelRoot 的点击上——禁用卡消费/不消费都不影响其收起；
     * 此处 `return false` 仅为语义清晰：禁用卡不「吞掉」事件）。可用卡的 DOWN 已消费，
     * 事件不会冒泡，UP 由框架照常转 `performClick()`。
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handled = super.onTouchEvent(event)
        if (!isUsable && event.actionMasked == MotionEvent.ACTION_UP) {
            return false
        }
        return handled
    }

    /**
     * 点击回调：框架在 DOWN+UP 命中时调用 [performClick]，这里转交给 [onCardClickListener]。
     * 可用卡与禁用卡**都会**走到这里（这正是修复目标：禁用卡也能弹 Toast）。
     */
    override fun performClick(): Boolean {
        val clicked = super.performClick()
        onCardClickListener?.invoke(this)
        return clicked
    }

    private fun dp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
}
