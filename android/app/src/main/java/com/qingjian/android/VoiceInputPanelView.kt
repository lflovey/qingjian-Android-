package com.qingjian.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * ★ v1.0 语音输入状态栏（**浅色主题**；承载「倾听中，点击结束」的极简状态条）。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * 承载方式（**只替换工具栏那一条，绝不覆盖键盘区**）
 * ═══════════════════════════════════════════════════════════════════════════
 *  在 `keyboard_view.xml` 中，本 View 位于 `contentColumn` 内、与 `candidatesContainer`
 *  **平级**（`wrap_content`、初始 `GONE`），由 [QingjianImeService] 在激活时设为
 *  `VISIBLE` 并同时把 `candidatesContainer` 置 `GONE`。
 *  → 视觉上「原工具栏所在的一条被替换为语音状态栏」，而键盘字母区（panelHost）**始终可见**。
 *  → **不是** AlertDialog；**不用** MATCH_PARENT 覆盖整屏；**不动** candidates_bar_height。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * 内部结构（一排三项）
 * ═══════════════════════════════════════════════════════════════════════════
 *   [ 左：紫色动态波纹指示器 WaveformView ]  [ 中：倾听中，点击结束 ]  [ 右：圆形「...」按钮 ]
 *
 *   · 左 + 中：同一个可点区域 —— 点击 → [Listener.onStopRequested]（停止倾听）。
 *   · 右：**占位功能** —— 点击 → [Listener.onMoreRequested]（服务层弹「暂未开放」Toast）。
 *   · 编号①的互斥说明：本面板的显示/隐藏完全由服务层控制，本 View 只负责 UI 与动画。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * 职责边界（重要）
 * ═══════════════════════════════════════════════════════════════════════════
 *  本 View **只负责 UI 与波纹动画**，不持有 `SpeechRecognizer`、不持有
 *  `InputConnection`、不做任何权限/识别判定。所有语音逻辑一律通过 [Listener]
 *  回调给 [QingjianImeService] 执行（与 [CursorModePanelView] 的职责划分一致）。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * 视觉纪律
 * ═══════════════════════════════════════════════════════════════════════════
 *  ★ 当前开发版为**浅色主题**：底取自 `colors.xml` 的 `voice_panel_surface`(#FAFAFA)，
 *    与候选栏 `candidates_bg` 一致；紫色波纹取 `voice_wave_*` 组。
 *    **绝不写死深色**（如 #1E1E1E）——夜间模式属后续独立迭代。
 *  ★ 触控目标：波纹热区高 48dp（≥ M3 要求），「...」按钮 32dp（圆钮视觉），
 *    热区由外层 [FrameLayout] 撑满栏高。
 *
 * 日志 tag 统一 `QingjianIME`。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * ★ v1.10 四态状态条（本轮核心改造）
 * ═══════════════════════════════════════════════════════════════════════════
 *  问题：旧版把「模型加载中 / 正在倾听 / 正在识别」全部显示成同一句「倾听中，点击结束」，
 *  用户看不出「现在到底能不能说话」，首启拷贝 228MB（数秒）期间尤其像「卡住的幻灯片」。
 *
 *  解决：同一面板按真实状态切换 [State]，合法状态机：
 *
 *    IDLE ──showLoading(…)──→ LOADING ──startListening()──→ LISTENING
 *      ▲                         │                              │
 *      │                         │ (用户取消)                    └──showDecoding()──→ DECODING
 *      │                         ▼                                                  │
 *      └───── onPanelHidden() ───┴──────────── showError(msg) ──→ ERROR ─────────────┘
 *
 *  · LOADING   → ① 静态波纹 + 「正在准备离线模型 N%」；不播动画（还没在录音）
 *  · LISTENING → ② 紫色波纹动画 + 「倾听中，点击结束」（与旧版**完全一致**）
 *  · DECODING  → ③ 波纹停 + 「识别中…」（采集已停，动画会误导「仍在听」）
 *  · ERROR     → ④ 波纹停 + 错误/空结果文案
 *
 *  转换合法性：[showLoading]/[startListening]/[showDecoding]/[showError] 可从任意态切入
 *  （服务层可能因时序乱序回调），但每次转换都会记日志 `QJ-SV-PANEL state: A -> B(…)`；
 *  [onPanelHidden] 是**唯一**回到 IDLE（初始态）的入口，保证下次打开不残留百分比/错误文案。
 */
class VoiceInputPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /**
     * ★ v1.10 面板状态（四态状态条）。
     * 与服务层 [QingjianImeService] 的语音逻辑一一对应，见类 KDoc 的状态机图。
     */
    enum class State {
        /** 初始态：面板未激活 / 已复位。 */
        IDLE,

        /** ① 模型加载中（带百分比）。 */
        LOADING,

        /** ② 倾听中（波纹动画）。 */
        LISTENING,

        /** ③ 正在识别（VAD 已断句，ASR 推理中）。 */
        DECODING,

        /** ④ 错误 / 空结果（3 秒后由服务层收面板）。 */
        ERROR,
    }

    /**
     * 面板交互回调。由宿主 [QingjianImeService] 实现。
     * 本 View 不持有任何语音状态，全部动作交由服务层执行。
     */
    interface Listener {
        /**
         * 请求停止倾听（点击左侧波纹 **或** 中间文字）。
         * **仅在 [State.LISTENING] 下触发**——点击等价「提前松开🎤」，走正常收口上屏
         * （服务层冲刷尾段 → 上屏最后一段，行为与松开完全一致）。
         * 其余态分派见 [onCancelRequested] 及实现处注释（★ v1.11 起 DECODING 态点击为 no-op）。
         */
        fun onStopRequested()

        /**
         * ★ v1.10 请求**取消本次语音输入**（点击波纹+文字区域，但不在倾听/识别态）。
         *
         * 选择与理由：需求要求「加载态下点击不应触发『结束识别』语义」，因为此时根本
         * 没在录音。为让服务层能区分「停止采集」与「放弃本次」，本 View 在
         * LOADING / ERROR / IDLE 态点击时调本回调，服务层映射到 `cancelVoiceInput()`。
         * 这样语义清晰、无需服务层反查面板状态；EW 上等价于「取消」，而非「结束」。
         *
         * ★ v1.11 QA 修复（丢尾句）：**DECODING 态点击改为 no-op、不再走本回调**——
         * 识别中取消会置终结闸门，丢弃尚在解码的尾段结果（中间句已上屏、最后一句
         * 丢失 → 文本残缺）。识别为毫秒~秒级，点击等结果自然收口即可。
         */
        fun onCancelRequested()

        /** 请求「更多语音设置」（点击右侧「...」；当前为占位功能）。 */
        fun onMoreRequested()
    }

    /** 交互监听（宿主注入）。 */
    var listener: Listener? = null

    /** 左侧紫色波纹指示器（自定义 View，见 [WaveformView]）。 */
    private var waveformView: WaveformView? = null

    /** 中间状态文字。 */
    private var hintText: TextView? = null

    /**
     * ★ v1.10 水平进度条（仅 [State.LOADING] 可见）。
     * 用**原生** `android.widget.ProgressBar` + 系统水平样式（不引 androidx）。
     * 置于文字下方，高度受 [R.dimen.voice_progress_height] 约束，不撑破 48dp 栏位。
     */
    private var progressBar: ProgressBar? = null

    /** 右侧圆形「...」按钮。 */
    private var moreButton: Button? = null

    /** ★ v1.10 当前状态（初值 IDLE）。所有转换都经 [transitionTo]。 */
    private var state: State = State.IDLE

    init {
        // 面板自带浅色底（与候选栏同色），避免替换工具栏后与键盘区断层
        setBackgroundResource(R.color.voice_panel_surface)
        buildContent()
    }

    // =========================================================================
    // 对外 API
    // =========================================================================

    /** 当前状态（供服务层查询；不改变任何状态）。 */
    fun getState(): State = state

    /**
     * ★ v1.10 进入「模型加载中」态，可**重复调用**刷新百分比。
     *
     * @param percent 0..100 的整数；传入 [PERCENT_UNKNOWN](-1) 表示进度未知，
     *   此时显示无百分比的文案 [R.string.voice_model_loading]。
     * 说明：加载态**不播波纹动画**（此处直接复用 WaveformView「停表 → phase=0 静态基线」逻辑），
     *   因为尚未开始录音，动起来会误导用户以为已能说话。
     */
    fun showLoading(percent: Int = PERCENT_UNKNOWN) {
        val p = percent.coerceIn(PERCENT_UNKNOWN, 100)
        transitionTo(State.LOADING) {
            // 加载态不播动画：尚未录音，波纹动起来会误导「已能说话」。
            // 复用 WaveformView「停表 → phase=0 静态基线」逻辑展示静态波纹。
            waveformView?.stopAnimation()
            hintText?.text = if (p in 0..100) {
                context.getString(R.string.voice_model_loading_percent, p)
            } else {
                getStringRes(R.string.voice_model_loading)
            }
            progressBar?.apply {
                if (p in 0..100) {
                    visibility = View.VISIBLE
                    isIndeterminate = false
                    this.progress = p
                } else {
                    // 百分比未知 → 走系统不确定态（跑马灯），同样给用户「在动」的反馈
                    visibility = View.VISIBLE
                    isIndeterminate = true
                }
            }
        }
    }

    /**
     * 进入「倾听中」状态：启动波纹动画 + 显示「倾听中，点击结束」。
     * 由服务层在**成功启动**语音识别后调用（模型就绪 + 权限通过）。
     * 与旧版视觉**完全一致**（本轮不改倾听态外观）。
     */
    fun startListening() {
        transitionTo(State.LISTENING) {
            hintText?.setText(R.string.voice_listening_hint)
            waveformView?.startAnimation()
        }
    }

    /**
     * ★ v1.10 进入「识别中」状态：文字改「识别中…」，**停止**波纹动画。
     * 由服务层在收到 [VoiceRecognizer.Callback.onDecoding] 时调用。
     */
    fun showDecoding() {
        transitionTo(State.DECODING) {
            hintText?.setText(R.string.voice_decoding_hint)
            // 采集已停，继续动波纹会误导「仍在听」
            waveformView?.stopAnimation()
        }
    }

    /**
     * ★ v1.10 进入「错误 / 空结果」状态：显示 [message]，停止波纹动画。
     * 由服务层在 onError / onModelError / 空结果时调用；随后服务层停留 3 秒再收面板。
     */
    fun showError(message: String) {
        transitionTo(State.ERROR) {
            hintText?.text = message
            waveformView?.stopAnimation()
        }
    }

    /**
     * 退出「倾听中」状态：停止波纹动画。
     * 幂等——面板未激活时重复调用是安全的。
     */
    fun stopListening() {
        waveformView?.stopAnimation()
        android.util.Log.i(TAG, "voice panel: stopListening -> waveform animation stopped")
    }

    /** 当前是否正在倾听（供服务层查询；不改变任何状态）。 */
    fun isListening(): Boolean = waveformView?.isAnimating() == true

    /**
     * 宿主在隐藏面板时调用：停止动画并把 UI **复位到初始态 IDLE**。
     * 幂等；由 [QingjianImeService.hideVoicePanel] 调用。
     * ★ 复位是硬要求：下次打开不得残留上次的百分比 / 错误文案 / 进度条可见性。
     */
    fun onPanelHidden() {
        waveformView?.stopAnimation()
        // 复位所有可视元素（不依赖 transitionTo 的日志语义，直接回 IDLE）
        state = State.IDLE
        hintText?.setText(R.string.voice_listening_hint)
        progressBar?.visibility = View.GONE
        progressBar?.progress = 0
        android.util.Log.d(TAG, "QJ-SV-PANEL state: -> IDLE (onPanelHidden, UI reset)")
    }

    // =========================================================================
    // 生命周期（动画泄漏防护）
    // =========================================================================

    /**
     * ★ 视图从窗口分离时**强制**停止动画。
     * 这是防泄漏的**最后一道保险**：即使服务层因异常/时序问题漏调 [stopListening]，
     * 只要本 View 被 detach（键盘销毁、布局重建），Handler 回调也会在此被移除，
     * 不会出现「面板已不可见但仍在后台 invalidate/耗电」的孤儿动画。
     */
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        waveformView?.stopAnimation()
        android.util.Log.d(TAG, "voice panel: onDetachedFromWindow -> waveform animation stopped (leak guard)")
    }

    // =========================================================================
    // 内部构建
    // =========================================================================

    /**
     * 构建一排三项：
     *   [ 波纹 48dp ] [ 文字 扩张 ] [ 「...」 32dp ]
     *
     * 「波纹 + 文字」共用一个可点区域 [LinearLayout]（weight=1），
     * 这样用户点左侧波纹或中间文字都落到同一 [OnClickListener] → onStopRequested()。
     * ——— 需求原文：「点击左侧波纹**或**中间文字 → 停止倾听」。
     */
    private fun buildContent() {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                res(R.dimen.voice_panel_padding_h),
                res(R.dimen.voice_panel_padding_v),
                res(R.dimen.voice_panel_padding_h),
                res(R.dimen.voice_panel_padding_v)
            )
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }

        // ── 可点区域：波纹 + 文字（weight=1，撑满剩余宽度）───────────────────
        val stopZone = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            isClickable = true
            isFocusable = true
            contentDescription = getStringRes(R.string.voice_stop_desc)
            setOnClickListener { v ->
                v.playSoundEffect(android.view.SoundEffectConstants.CLICK)
                // ★ v1.11 QA 修复（丢尾句）：热区分派由 v1.10 的「倾听 vs 其余」二分支
                //   改为**三分支**。旧逻辑在 DECODING 态点击会走 cancelVoiceInput，
                //   服务层置 voiceSessionFinished=true 闸门 → 尚在解码的**尾段结果被拦截
                //   丢弃**，而中间句已上屏 → 用户看到的文本残缺（最后一句丢失）。
                when (state) {
                    State.LISTENING -> {
                        // 倾听中点击 = 提前松开🎤，等价正常收口（结果会全部上屏）
                        android.util.Log.i(TAG, "voice panel: stop zone tapped (state=LISTENING) -> onStopRequested")
                        listener?.onStopRequested()
                    }

                    State.DECODING -> {
                        // 识别中点击：**no-op**——不取消、不重置，等结果自然收口。
                        // 识别为毫秒~秒级，取消的代价（丢尾句）远大于多等这一下。
                        android.util.Log.i(TAG, "voice panel: stop zone tapped (state=DECODING) -> ignored, wait for final result")
                    }

                    else -> {
                        // LOADING / ERROR / IDLE：本次既无尾段也无待上屏内容，取消安全
                        // 且符合直觉（服务层映射 cancelVoiceInput，收面板复位）。
                        android.util.Log.i(TAG, "voice panel: stop zone tapped (state=$state) -> onCancelRequested")
                        listener?.onCancelRequested()
                    }
                }
            }
        }

        // 左：紫色动态波纹指示器
        val wave = WaveformView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                res(R.dimen.voice_wave_width),
                res(R.dimen.voice_wave_height)
            )
        }
        waveformView = wave
        stopZone.addView(wave)

        // 中：文字 +（加载态才可见的）水平进度条（竖排容器）
        val centerColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = res(R.dimen.voice_hint_margin_start) }
        }

        val hint = TextView(context).apply {
            text = getStringRes(R.string.voice_listening_hint)
            setTextColor(context.getColor(R.color.voice_hint_text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, VOICE_HINT_TEXT_SP)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            isAllCaps = false
            includeFontPadding = false
        }
        hintText = hint
        centerColumn.addView(hint)

        // 进度条：默认 GONE（仅加载态显示）；高度受 dimen 约束，不撑破 48dp 栏位。
        // ★ 用原生 ProgressBar + 系统水平样式（不引 androidx）；进度色走 tint（框架 API21+）。
        val progress = ProgressBar(
            context,
            null,
            android.R.attr.progressBarStyleHorizontal
        ).apply {
            visibility = View.GONE
            max = 100
            progress = 0
            isIndeterminate = false
            // 进度色复用紫色（voice_wave_line），无新增颜色
            progressTintList = android.content.res.ColorStateList.valueOf(
                context.getColor(R.color.voice_wave_line)
            )
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(
                context.getColor(R.color.voice_more_stroke)
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                res(R.dimen.voice_progress_height)
            ).apply { topMargin = res(R.dimen.voice_progress_margin_top) }
        }
        progressBar = progress
        centerColumn.addView(progress)

        stopZone.addView(centerColumn)

        row.addView(stopZone)

        // ── 右：圆形「...」按钮（占位功能）────────────────────────────────────
        val more = Button(context).apply {
            text = VOICE_MORE_LABEL
            setTextColor(context.getColor(R.color.voice_more_icon))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, VOICE_MORE_TEXT_SP)
            background = context.getDrawable(R.drawable.voice_more_bg)
            gravity = Gravity.CENTER
            isAllCaps = false
            minWidth = 0
            minHeight = 0
            setPadding(0, 0, 0, 0)
            includeFontPadding = false
            contentDescription = getStringRes(R.string.voice_more_desc)
            layoutParams = LinearLayout.LayoutParams(
                res(R.dimen.voice_more_button_size),
                res(R.dimen.voice_more_button_size)
            )
            setOnClickListener { v ->
                v.playSoundEffect(android.view.SoundEffectConstants.CLICK)
                android.util.Log.i(TAG, "voice panel: more button tapped -> onMoreRequested")
                listener?.onMoreRequested()
            }
        }
        moreButton = more
        row.addView(more)

        addView(row)

        android.util.Log.i(
            TAG,
            "VoiceInputPanelView init: waveform=${waveformView != null}, " +
                "hint=${hintText != null}, more=${moreButton != null}, " +
                "listening=${isListening()}"
        )
    }

    // =========================================================================
    // 小工具
    // =========================================================================

    /**
     * ★ v1.10 状态转换统一入口：记录日志 `QJ-SV-PANEL state: A -> B` 后执行 UI 变更。
     *
     * 为什么集中在此：四态状态条最容易出的问题是「某条路径改了文字却没改动画」，
     * 把「置状态 + 打日志」收敛到一处，可以让每条路径的副作用（波纹/文字/进度条）
     * 都在 [applyUi] 里显式写全，避免遗漏。
     *
     * 合法性：[showLoading]/[startListening]/[showDecoding]/[showError] 允许从任意态切入
     * （服务层回调时序可能乱序，例如 onProgress 迟到）；同态重复调用也安全（幂等 UI）。
     * [onPanelHidden] **不**走本方法（它直接回 IDLE 并清空所有元素）。
     */
    private inline fun transitionTo(target: State, applyUi: () -> Unit) {
        val from = state
        state = target
        // 非加载态默认隐藏进度条，避免残留（加载态在 showLoading 内自行置 VISIBLE）
        if (target != State.LOADING) {
            progressBar?.visibility = View.GONE
        }
        applyUi()
        android.util.Log.i(TAG, "QJ-SV-PANEL state: $from -> $target")
    }

    private fun res(id: Int): Int = resources.getDimensionPixelSize(id)

    private fun getStringRes(id: Int): String = context.getString(id)

    companion object {
        private const val TAG = "QingjianIME"

        /**
         * 进度未知哨兵值（-1）：[showLoading] 收到它时显示无百分比文案 + 不确定态进度条。
         */
        const val PERCENT_UNKNOWN = -1

        /** 「...」按钮文字（用字符而非资源：非本地化语义文案，且需随字号微调）。 */
        private const val VOICE_MORE_LABEL = "..."

        /** 中间状态文字字号（sp）。 */
        private const val VOICE_HINT_TEXT_SP = 15f

        /** 「...」按钮文字字号（sp）。 */
        private const val VOICE_MORE_TEXT_SP = 16f

        /**
         * 波纹竖线数量（5 条）：与百度语音条观感接近，且足够宽以呈现「动态」。
         * 提为常量便于 [WaveformView] 与注释共享同一事实。
         */
        const val WAVE_BAR_COUNT = 5
    }

    /**
     * ★ 紫色动态波纹指示器（自定义 View）。
     *
     * ═══════════════════════════════════════════════════════════════════════════
     * 实现方式（**无第三方依赖**，纯 `Canvas` + `Paint`）
     * ═══════════════════════════════════════════════════════════════════════════
     *  画 [WAVE_BAR_COUNT] 条圆角竖线，高度按正弦函数随时间周期变化：
     *      heightScale(t, i) = 0.30 + 0.20 * sin(phase(t) + i * PHASE_STEP)
     *  其中 phase(t) = (SystemClock.uptimeMillis() % PERIOD_MS) / PERIOD_MS * 2π。
     *  → 每条竖线相位错开 [PHASE_STEP]，形成「波浪」观感；
     *  → 高度占比恒在 [0.10, 0.50]∪…… 化简后为 [0.10, 0.50] 之外仍有下限 0.10，
     *     避免出现高度为 0 的「消失」竖线（观感更连续）。
     *
     * ═══════════════════════════════════════════════════════════════════════════
     * 动画驱动与泄漏防护（关键）
     * ═══════════════════════════════════════════════════════════════════════════
     *  · 用**主线程 [Handler]** 每 [FRAME_MS]（≈30fps）投递一次 [frameRunnable]，
     *    回调内 `invalidate()` 并重投自身；**不用** `ValueAnimator`（避免对象生命周期
     *    与 View 绑定不当导致的泄漏，也无需处理 start/cancel 时序）。
     *  · [stopAnimation] 会 `removeCallbacks(frameRunnable)` 并复位状态 → **抬手即停**。
     *  · 兜底：View [onDetachedFromWindow] 时宿主（[VoiceInputPanelView]）也会调 stopAnimation，
     *    双重保险，绝不会留下孤儿 Runnable。
     *  · 只有 [animating] == true 时才继续重投，`startAnimation` 幂等（已在跑则忽略）。
     */
    class WaveformView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0
    ) : View(context, attrs, defStyleAttr) {

        /** 竖线画笔（圆头，紫色），复用以避免每帧分配对象。 */
        private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = context.getColor(R.color.voice_wave_line)
            strokeCap = Paint.Cap.ROUND
            strokeWidth = context.resources.getDimension(R.dimen.voice_wave_line_width)
        }

        /** 动画驱动 Handler（主线程）。 */
        private val handler = Handler(Looper.getMainLooper())

        /** 是否正在动画（决定 [frameRunnable] 是否重投自身）。 */
        private var animating = false

        /** 每帧回调：`invalidate()` 触发重绘，并在动画中时重投自身形成循环。 */
        private val frameRunnable = object : Runnable {
            override fun run() {
                if (!animating) return
                invalidate()
                handler.postDelayed(this, FRAME_MS)
            }
        }

        /** 启动动画（幂等）：已在动画中则不重复投递。 */
        fun startAnimation() {
            if (animating) return
            animating = true
            handler.removeCallbacks(frameRunnable)
            handler.post(frameRunnable)
            android.util.Log.d(TAG, "WaveformView: animation started (bars=$WAVE_BAR_COUNT)")
        }

        /** 停止动画（幂等）：移除回调并复位。 */
        fun stopAnimation() {
            if (!animating) return
            animating = false
            handler.removeCallbacks(frameRunnable)
            // 回到「静态基线」画面：竖线均匀且较短，避免残留一帧高矮不一
            invalidate()
            android.util.Log.d(TAG, "WaveformView: animation stopped")
        }

        /** 当前是否正在动画。 */
        fun isAnimating(): Boolean = animating

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            // 最后一道保险：视图分离时无条件停表，杜绝孤儿 Runnable
            animating = false
            handler.removeCallbacks(frameRunnable)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            val count = WAVE_BAR_COUNT
            // 竖线水平等距排布：每条中心 x = (i + 0.5) / count * w
            val slot = w / count
            val centerY = h / 2f
            val minH = h * MIN_HEIGHT_RATIO
            val maxH = h * MAX_HEIGHT_RATIO

            // 相位：随时间在 [0, 2π) 内循环；停表时 phase 取 0（静态基线）
            val phase = if (animating) {
                val t = (android.os.SystemClock.uptimeMillis() % PERIOD_MS).toFloat() / PERIOD_MS
                t * TWO_PI
            } else {
                0f
            }

            for (i in 0 until count) {
                // 0..1 的起伏系数：sin 的 [-1,1] 映射到 [0,1]
                val wave = (kotlin.math.sin(phase + i * PHASE_STEP) + 1f) / 2f
                val barH = minH + (maxH - minH) * wave
                val cx = slot * (i + 0.5f)
                val top = centerY - barH / 2f
                val bottom = centerY + barH / 2f
                canvas.drawLine(cx, top, cx, bottom, barPaint)
            }
        }

        companion object {
            private const val TAG = "QingjianIME"

            /** 帧间隔（毫秒）：≈30fps，足够顺滑且省电。 */
            private const val FRAME_MS = 33L

            /** 一个完整起伏周期（毫秒）。 */
            private const val PERIOD_MS = 900L

            /** 相邻竖线的相位差（弧度）：形成波浪推进感。 */
            private const val PHASE_STEP = (2.0 * kotlin.math.PI / 3.0).toFloat()

            /** 静止/动画下限高度占比。 */
            private const val MIN_HEIGHT_RATIO = 0.12f

            /** 动画上限高度占比。 */
            private const val MAX_HEIGHT_RATIO = 0.80f

            private const val TWO_PI = (2.0 * kotlin.math.PI).toFloat()
        }
    }
}
