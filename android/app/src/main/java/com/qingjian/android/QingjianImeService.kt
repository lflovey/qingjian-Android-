package com.qingjian.android

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SoundEffectConstants
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

/**
 * 青简输入法服务（XML 布局 + 自定义 View，无 Compose）。
 *
 * 键规则（需求单）：
 *  - 字母键 → 追加缓冲并 nativePinyinInput 更新候选（拼音/双拼/五笔同一通道）；
 *  - 空格/数字/其它 → 有候选则 nativeSelect 提交 + nativeClear，无候选则提交原串；
 *  - 英文模式 → commitText 直输（引擎不参与）。
 *
 * 版本要点：
 *  - v0.2 候选栏并入主视图；v0.3 删除键 finishComposingText；v0.4 双态容器互斥。
 *  - v0.5 切换输入方式重做为卡片网格面板（作为垂直流第三项）。
 *  - ★ v0.6 切换输入方式**按新规格重构**（本版核心）：
 *      1. **根布局改 FrameLayout**：keyboard_view.xml 根由 LinearLayout 改为 FrameLayout，
 *         原键盘内容归入 contentColumn；输入方式面板 [InputModePanelView] 作为根的
 *         **第二个直接子项、MATCH_PARENT、初始 GONE** —— 展开即「整键盘区被替换」，
 *         完全遮挡原键盘/候选栏/工具栏（非 AlertDialog、非候选栏内 addView）。
 *      2. **对外入口统一为 [InputModePanelView]**（暴露 Listener：onModePicked / onDismissRequested）；
 *         卡片复用深色 [ModeCardButton]，由面板按 [InputMode.gridOrder] 动态填充。
 *      3. **深色主题**：面板底 #1E1E1E、卡片 #2C2C2C、当前项蓝 #1A73E8、禁用 #222222/#666666。
 *      4. **选卡** → 清缓冲（finishComposingText）→ 切键盘布局 → 隐藏面板。
 *      5. **返回键双通道**：[onKeyDown] 与 [onBackPressed] 都兜住（HyperOS 两条行为不一致）。
 *
 *  - ★ v0.7 剪贴板历史面板（本版核心）：
 *      1. 新增 [ClipboardHistoryManager]（原生 SQLiteOpenHelper，**无条数/字数上限**）
 *         与 [ClipboardPanelView]（深色面板，根 FrameLayout 第三子项，MATCH_PARENT，初始 GONE，
 *         与 [InputModePanelView] **同级互斥**）；
 *      2. 工具栏「剪贴板」按钮（tag=special:clipboard）→ 显示面板 + bringToFront；
 *      3. 选条 → `commitText` **完整文本**（不截断）→ 隐藏面板；
 *      4. 返回键优先级链：剪贴板面板 > 输入方式面板 > 系统（见 [handleBack] / [onKeyDown]）。
 *
 *  - ★ v0.8 光标左右移动（本版核心）：
 *      1. 新增 [CursorModePanelView]（**与 candidatesContainer 平级**、wrap_content、初始 GONE），
 *         承载两种形态：
 *           · [CursorModePanelView.Mode.OPERATION]（图二）—— **单击**工具栏「<I>」触发，
 *             两行宫格：选择 / < / > / ^ / v 与 全选 / 复制 / 粘贴 / 剪贴板 / 删除；
 *           · [CursorModePanelView.Mode.SHIFT]（图三）—— **长按**工具栏「<I>」触发，
 *             `<<<` ● `>>>` 平移条（中间为浅灰实心圆指示器，兼作点击退出热区）。
 *      2. **只替换工具栏那一条**：激活时本面板 VISIBLE + `candidatesContainer` GONE；
 *         退出时反向恢复。**键盘字母区（panelHost）全程可见可点，绝不覆盖**；
 *         **不改** candidates_bar_height、**不碰**候选栏双态与删除键逻辑。
 *      3. 光标移动唯一出口 [moveCursor]：`InputConnection` 非空校验 → `getSelectionStart()`
 *         → 边界 `Math.max(0, Math.min(newPos, textLength))` → `setSelection(newPos, newPos)`
 *         → **主线程**执行；有选区时折叠到左/右端（见 [resolveCollapsedTarget]）。
 *      4. 长按连续移动：宫格方向键与平移条共用 `Handler + Runnable`，
 *         按下 300ms 后触发、每 50ms 一次，步长渐进加速（见 CursorModePanelView 的 computeHoldStep）。
 *      5. 退出路径：点指示器 / 物理返回键 / 图二「选择」外的操作完成 → 退出；
 *         **输入字符**（软键盘 `onKey` 字符分支 + 物理键盘 `onKeyDown` 字符分支）也退出。
 *      6. 返回键优先级链更新为：光标模式 > 剪贴板面板 > 输入方式面板 > 系统。
 *
 * 回归红线（不得破坏）：候选栏显示、删除键 finishComposingText、工具栏/候选栏双态互斥、
 * QingjianIME 日志、native 串行调用。
 * 线程纪律：nativeInit 在 onCreate 的一次性后台线程完成；此后全部 native 调用
 * 只发生在 IME 服务主线程（串行）；引擎句柄自身带 Mutex，双保险。
 */
class QingjianImeService : InputMethodService() {

    /** 键盘面板：拼音26键 / 拼音9键 / 符号数字（符号面板为内部临时态，非用户可选输入方式）。 */
    private enum class Panel { QWERTY, T9, SYMBOLS }

    private lateinit var keyboardRoot: View
    private lateinit var panelQwerty: View
    private lateinit var panelT9: View
    private lateinit var panelSymbols: View

    /** ★ v0.4 双态容器宿主（固定 48dp）；同栏位互斥 */
    private var candidatesContainer: View? = null

    /** 状态 A：输入工具栏（无拼音缓冲时显示） */
    private var inputToolbar: View? = null

    /** 状态 B：候选栏自定义 View（拼音缓冲非空时显示） */
    private var candidatesView: CandidatesView? = null

    /** 切换输入方式按钮（工具栏首项，special:switchmode；单击直接弹面板） */
    private var switchModeButton: Button? = null

    private val modeButtons = ArrayList<Button>()

    // ---- ★ v0.6 输入方式面板（根 FrameLayout 的直接子项、MATCH_PARENT、初始 GONE）----
    /** 输入方式面板（自定义 View，承载顶部栏 + 4 列卡片网格，暴露 Listener）。 */
    private var modePanelView: InputModePanelView? = null

    // ---- ★ v0.7 剪贴板面板（根 FrameLayout 的直接子项、MATCH_PARENT、初始 GONE，与输入方式面板同级）----
    /** 剪贴板历史面板（自定义 View，承载顶部栏/分类栏/列表/底栏，暴露 Listener）。 */
    private var clipboardPanelView: ClipboardPanelView? = null

    /** ★ v0.7 剪贴板历史管理器（IME 常驻：onCreate 创建 + start，onDestroy shutdown）。 */
    private var clipboardManager: ClipboardHistoryManager? = null

    // ---- ★ v0.8 光标模式（与候选栏平级、只替换工具栏那一条）----
    /** 光标面板宿主容器（contentColumn 第 0 项，wrap_content，初始 GONE）。 */
    private var cursorModeHost: View? = null

    /** 光标面板本体（承载图二宫格 / 图三平移条两种形态）。 */
    private var cursorPanel: CursorModePanelView? = null

    /** 工具栏「<I>」入口按钮（单击 → 图二；长按 → 图三）。 */
    private var cursorEntryButton: Button? = null

    /** 入口按钮按下时刻（用于日志记录按压时长）；-1 表示当前没有按下的触点。 */
    private var entryDownAt = -1L

    /** 入口按钮本次按压是否已触发长按（触发后抬手不再视为单击）。 */
    private var entryLongPressFired = false

    /**
     * 入口按钮长按定时器。
     * ★ 必须用 Handler 自计时，**不能**用 `setOnLongClickListener`——原因见 [wireCursorEntryButton] 注释。
     */
    private val entryHoldHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 300ms 到点 → 长按成立 → 弹平移条（图三）。 */
    private val entryHoldRunnable = Runnable {
        if (entryDownAt < 0) return@Runnable   // 已抬手（安全冗余）
        entryLongPressFired = true
        Log.i(
            TAG,
            "cursor entry: LONG PRESS fires at ${CURSOR_ENTRY_HOLD_DELAY_MS}ms -> SHIFT bar"
        )
        cursorEntryButton?.playSoundEffect(SoundEffectConstants.CLICK)
        showCursorPanel(CursorModePanelView.Mode.SHIFT)
    }

    // ---- ★ v1.0 语音输入（与候选栏平级、只替换工具栏那一条）----
    /** 语音状态栏宿主容器（contentColumn 第 1 项，wrap_content，初始 GONE）。 */
    private var voiceInputHost: View? = null

    /** 语音状态栏本体（左侧紫色波纹 + 中间提示 + 右侧「...」）。 */
    private var voicePanel: VoiceInputPanelView? = null

    /**
     * ★ v1.9 纯离线语音引擎（sherpa-onnx + SenseVoice-Small + Silero VAD）。
     *
     * ⚠️ 与旧版 [android.speech.SpeechRecognizer]（在线）**彻底隔离**：
     *    · 服务内**不再**持有 `SpeechRecognizer` 字段；旧实现退役于 [LegacyOnlineRecognizer]；
     *    · 语音入口（🎤 按住说话）**只**走本引擎；
     *    · 状态字段唯一来源为本引擎回调（全部在主线程）。
     *
     * 线程：模型加载在 [VoiceRecognizer] 内部子线程完成；采集在独立线程；
     *      本服务字段只在主线程读写（回调已由引擎 post 到主线程）。
     */
    private var voiceEngine: VoiceRecognizer? = null

    /**
     * 模型准备是否已完成（false 且正在 prepare 时，点击「结束」无意义；
     * 用于区分「已就绪可录」与「正准备中」两种 UI 语义）。
     */
    private var voiceModelReady = false

    /** 模型准备是否已发起（防止重复 prepare / 重复 Toast「正在准备」）。 */
    private var voicePrepareTriggered = false

    /** 本次语音会话是否已有结果收口（幂等闸门：结果/错误只处理一次）。 */
    private var voiceSessionFinished = false

    /**
     * ★ v1.11 按住说话：🎤 按钮当前是否处于「按下未松开」状态。
     *
     * 语义：唯一真值来源是 [wireVoiceButton] 的 OnTouchListener（ACTION_DOWN 置 true /
     * ACTION_UP·CANCEL 置 false）。用于两处竞态防御：
     *   · [voiceCallback.onReady] —— 仅当仍按住才 start（手指已松开则不启采集）；
     *   · [onVoiceRelease] 的前置守卫 —— 已 cancel（被返回键/切框打断）后 UP 不再复活会话。
     * 任何「放弃本次」路径（[cancelVoiceInput]）都必须复位本字段。
     */
    private var voiceHoldActive = false

    /**
     * ★ v1.11 按住说话：本次会话是否已有**中间分句**成功上屏（[onSegmentResult] 非空时置 true）。
     *
     * 用途：松开收口（[voiceCallback.onResult]）收到空尾段时——
     *   · 已有分句上屏 → 静默收面板（用户已看到文字，不必再提示「未听到声音」）；
     *   · 无任何分句 → 才提示「未听到声音」+ 错误态 3 秒（快速点按/全程静音）。
     */
    private var voiceCommittedAny = false

    /**
     * ★ v1.10 错误/空结果态「停留 3 秒再收面板」的延迟 Runnable（成员字段）。
     *
     * 为什么做成字段：需求要求「3 秒延迟期间必须可被打断」——返回键/切框/收键盘/其它面板
     * 抢占等 [cancelVoiceInput] 路径若 3 秒后才触发，会「诈尸」重收一次面板。做成字段后，
     * [hideVoicePanel] 与 [cancelVoiceInput] 都能 `removeCallbacks` 精准撤销它。
     */
    private var voicePendingHide: Runnable? = null

    /** 主线程 Handler（延迟收起面板用；服务本身跑在主线程）。 */
    private val voiceUiHandler = Handler(Looper.getMainLooper())

    // ---- ★ v1.3 删除键手势（按住连续删除 + 上滑清空）----
    /** 删除键手势状态机（手势在类内、业务回调到本服务）。见 [BackspaceGestureHandler]。 */
    private var backspaceGesture: BackspaceGestureHandler? = null

    /** 上滑「清空模式」气泡（contentColumn 内、与 candidatesContainer 平级、初始 GONE）。 */
    private var backspaceClearBubble: View? = null

    private val composing = StringBuilder()
    private var candidates: List<Candidate> = emptyList()
    private var selectedIndex = 0

    /** ★ 当前输入方式（native 码 + 键盘面板双维度）。默认全拼 26 键。 */
    private var inputMode = InputMode.DEFAULT

    /** ★ 当前双拼方案名（供 nativeSetMode 装载；默认小鹤）。 */
    private var shuangpinScheme = InputMode.DEFAULT_SHUANGPIN_SCHEME

    /** ★ 各输入方式是否已验证可用（探测成功过）——面板据此置灰。 */
    private val modeUsable = HashMap<InputMode, Boolean>()

    private var panel = Panel.QWERTY

    /** 引擎是否初始化完成；nativeReady=false 时一切 native 调用被跳过（安全降级为直输）。 */
    @Volatile
    private var nativeReady = false

    // ---- 光标跟踪：识别「外部移动光标」以提交拼音原串（见 onUpdateSelection）----
    private var curSelStart = 0
    private var curSelEnd = 0
    private var composingStart = -1
    private var expectedCursor = -1

    // ---- 9键连打（multi-tap）状态 ----
    private var t9KeyId = -1
    private var t9Cycle = 0
    private var t9LastTime = 0L

    // =========================================================================
    // 生命周期
    // =========================================================================

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate begin")
        // ★ v1.1 版本自证指纹：开键盘即在 logcat 暴露「当前装的到底是哪个版本」。
        //   起因：用户反馈「语音按钮没反应 / QingjianIME tag 为空」，实为装载了 v0.9 或更早
        //   无语音代码的旧包。此行让版本一眼可判（grep 锚点：QJ-VER）。
        logBuildFingerprint("onCreate")
        // ★ v1.0 语音输入：检查录音权限（运行时权限）。仅记日志，真正拦截在激活语音时。
        checkAudioPermission()
        // ★ v0.7 剪贴板：IME 常驻创建历史管理器并注册系统剪贴板监听（onDestroy 注销）
        try {
            clipboardManager = ClipboardHistoryManager(this).also { it.start() }
            Log.i(TAG, "onCreate: ClipboardHistoryManager created & started")
        } catch (t: Throwable) {
            Log.e(TAG, "onCreate: ClipboardHistoryManager init failed", t)
            clipboardManager = null
        }
        val dataDir = File(filesDir, DATA_DIR_NAME)
        // 后台线程：assets 不能 mmap，先解压到 filesDir/qingjian-data（目录存在即跳过），再 nativeInit
        thread(name = "qingjian-init") {
            try {
                extractDataIfNeeded(dataDir)
                if (!QingjianNative.libraryLoaded) {
                    Log.e(TAG, "onCreate: native library not loaded; engine disabled")
                    return@thread
                }
                val rc = QingjianNative.nativeInit(dataDir.absolutePath)  // 第一分水岭：1=成功 0=失败
                Log.i(TAG, "onCreate: nativeInit(${dataDir.absolutePath}) -> $rc")
                nativeReady = (rc == 1)
                if (!nativeReady) Log.e(TAG, "onCreate: engine init returned $rc; candidates disabled")
                // 初始化完成后把当前方式（默认全拼 26 键）装配到引擎，并探测五笔数据可用性
                if (nativeReady) {
                    probeModeAvailability()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "onCreate: engine init failed", t)
                nativeReady = false
            }
        }
        Log.i(TAG, "onCreate end (init dispatched to background thread)")
    }

    /**
     * ★ v1.1 版本自证指纹（grep 锚点：`QJ-VER`）。
     *
     * **存在意义**：曾发生「用户报语音无反应，实则装载了无语音代码的旧包（v0.9 及更早）」的
     * 误判事故。本方法把「当前运行的到底是哪个版本」直接打到 logcat，让版本问题一眼可判，
     * 无需再靠反编译排查。
     *
     * **调用点两处，缺一不可**：
     *  · [onCreate]（`whereTag="onCreate"`）——Service 创建时，记录进程级版本；
     *  · [onCreateInputView]（`whereTag="onCreateInputView"`）——**每次唤出键盘**都打，
     *    避免 Service 常驻导致「重启后看 logcat 看不到版本行」。
     *
     * **兼容性**：`getPackageInfo(String, PackageInfoFlags)` 是 API 33+ 重载，本工程 minSdk 24，
     * 故使用旧的 `getPackageInfo(String, int)`。`versionCode` 字段在该重载下被标记 deprecated，
     * 但仍是 minSdk 24 下唯一可用途径 —— 编译期会有一条 deprecation warning，属预期。
     *
     * **健壮性**：整段 `runCatching` 兜底，任何异常都降级为 `unknown` 并记 `Log.e`，**绝不崩溃**。
     */
    private fun logBuildFingerprint(whereTag: String) {
        runCatching {
            val pkgInfo = packageManager.getPackageInfo(packageName, 0)
            val vName = pkgInfo.versionName ?: "unknown"
            val vCode = pkgInfo.versionCode
            Log.i(TAG, "================================")
            Log.i(TAG, "QJ-VER [$whereTag] 青简输入法 Android v$vName (versionCode=$vCode)")
            Log.i(TAG, "QJ-VER [$whereTag] voice-feature=ENABLED (v1.0+)  TAG should be: QingjianIME")
            Log.i(TAG, "QJ-VER [$whereTag] if you see this line, you are running the CORRECT build")
            Log.i(TAG, "================================")
        }.onFailure { t ->
            Log.e(TAG, "QJ-VER [$whereTag] package info unavailable (degrade to unknown)", t)
            Log.i(TAG, "================================")
            Log.i(TAG, "QJ-VER [$whereTag] 青简输入法 Android vunknown (versionCode=unknown)")
            Log.i(TAG, "QJ-VER [$whereTag] voice-feature=ENABLED (v1.0+)  TAG should be: QingjianIME")
            Log.i(TAG, "QJ-VER [$whereTag] if you see this line, you are running the CORRECT build")
            Log.i(TAG, "================================")
        }
    }

    override fun onCreateInputView(): View {
        Log.i(TAG, "onCreateInputView begin")
        // ★ v1.1 版本自证：`onCreate` 是 Service 生命周期方法，**安装后首次选中输入法时只跑一次**，
        //   之后进程常驻。若用户重启手机后直接抓 logcat，很可能错过 onCreate 的那行 QJ-VER。
        //   故此处（**每次唤出键盘都会执行**）再打一次，保证「任何时候看 logcat 都能确认版本」。
        logBuildFingerprint("onCreateInputView")
        keyboardRoot = layoutInflater.inflate(R.layout.keyboard_view, null)
        panelQwerty = keyboardRoot.findViewById(R.id.panelQwerty)
        panelT9 = keyboardRoot.findViewById(R.id.panelT9)
        panelSymbols = keyboardRoot.findViewById(R.id.panelSymbols)

        // ★ v0.4：双态容器（同栏位互斥）——toolbar（状态 A）/ candidates（状态 B）
        candidatesContainer = keyboardRoot.findViewById(R.id.candidatesContainer)
        inputToolbar = keyboardRoot.findViewById(R.id.inputToolbar)
        candidatesView = keyboardRoot.findViewById(R.id.candidatesView)
        candidatesView?.let { v ->
            v.onCandidateClick = { index -> commitCandidate(index) }
            v.onCandidateLongClick = { index -> showTranslation(index) }
        }
        switchModeButton = keyboardRoot.findViewById(R.id.btnSwitchMode)
        wireSwitchButton(switchModeButton)

        // ★ v0.6：输入方式面板（根 FrameLayout 的第二个直接子项、MATCH_PARENT、初始 GONE）
        wireInputModePanel()

        // ★ v0.7：剪贴板面板（根 FrameLayout 的第三个直接子项、MATCH_PARENT、初始 GONE）
        wireClipboardPanel()

        // ★ v0.8：光标模式面板（contentColumn 内、与候选栏平级、wrap_content、初始 GONE）
        //           + 工具栏「<I>」入口（单击/长按分流）
        wireCursorModePanel()
        wireCursorEntryButton(keyboardRoot.findViewById(R.id.btnCursor))

        // ★ v1.0：语音输入状态栏（contentColumn 内、与候选栏平级、wrap_content、初始 GONE）
        wireVoicePanel()

        // ★ v1.11：工具栏「🎤」改「按住说话」——用 OnTouchListener 接管（DOWN 开始录 / UP 上屏）。
        //   ⚠️ 必须在 wireKeys 之前装配；且 wireKeys 已按 tag(`special:voice`) 跳过该键，
        //      否则统一的 setOnClickListener 会覆盖本触摸监听（同 v0.8 special:cursor 的处理）。
        wireVoiceButton(keyboardRoot.findViewById(R.id.btnMic))

        // ★ v1.3：删除键手势（按住连续删除 + 上滑清空）
        //   气泡定位（contentColumn 内、与候选栏平级、初始 GONE）+ 手势装配。
        //   ⚠️ 必须在 wireKeys 之前装配删除键；且 wireKeys 已按 tag(`special:backspace`) 跳过该键，
        //      否则统一的 setOnClickListener 会覆盖本手势监听（同 v0.8 special:cursor 的处理）。
        backspaceClearBubble = keyboardRoot.findViewById(R.id.backspaceClearBubble)
        wireBackspaceGesture(keyboardRoot)

        // 初始无拼音缓冲 → 显示工具栏（状态 A）
        showToolbar()

        modeButtons.clear()
        wireKeys(keyboardRoot)
        updateModeButtons()
        showPanel(panel)
        Log.i(
            TAG,
            "onCreateInputView end: dualStateHost=${candidatesContainer != null}, " +
                "toolbar=${inputToolbar != null}, candidatesView=${candidatesView != null}, " +
                "switchBtn=${switchModeButton != null}, modePanel=${modePanelView != null}, " +
                "clipboardPanel=${clipboardPanelView != null}, " +
                "cursorPanel=${cursorPanel != null}, cursorEntry=${cursorEntryButton != null}, " +
                "voicePanel=${voicePanel != null}"
        )
        return keyboardRoot
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        curSelStart = attribute?.initialSelStart ?: 0
        curSelEnd = attribute?.initialSelEnd ?: 0
        expectedCursor = -1
        // 数字/电话输入框直接给符号数字面板
        val inputClass = attribute?.inputType?.and(InputType.TYPE_MASK_CLASS) ?: 0
        showPanel(
            if (inputClass == InputType.TYPE_CLASS_NUMBER || inputClass == InputType.TYPE_CLASS_PHONE)
                Panel.SYMBOLS else currentKeyboardPanel()
        )
        Log.d(TAG, "onStartInput: inputClass=$inputClass, panel=$panel, restarting=$restarting")
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (composing.isNotEmpty()) {
            currentInputConnection?.finishComposingText()
            resetComposing()
        }
        updateModeButtons()
        // 无缓冲 → 双态容器回到状态 A（工具栏）；并确保两个覆盖面板收起
        hideInputModePanel()
        hideClipboardPanel()
        // ★ v0.8：光标面板也必须收起——切换输入框属于「重新开始」，不能带着上一个框的光标模式
        hideCursorPanel()
        // ★ v1.0：语音状态也必须复位——切换输入框时不能带着语音状态（取消识别、不上屏）
        cancelVoiceInput("onStartInputView")
        showToolbar()
        Log.d(TAG, "onStartInputView: panel=$panel, toolbarVisible=${inputToolbar?.visibility == View.VISIBLE}")
    }

    override fun onFinishInput() {
        super.onFinishInput()
        Log.d(TAG, "onFinishInput: clear composing (nativeClear)")
        // ★ v1.12 修复（真机 Bug：话没说完面板自动收回）：
        //   onFinishInput 仅代表**输入上下文重启**（如分句 commitText 上屏后，三星 One UI
        //   短信会重建输入会话），**并不代表 IME 窗口消失**。此前无条件 cancelVoiceInput，
        //   会在用户手指仍按住 🎤、面板仍显示时被系统回调打断，导致会话被取消、面板提前收回。
        //   因此：语音会话活跃（按住中 或 面板仍显示）时**跳过**取消，只记日志；
        //   窗口真消失的兜底交给 onFinishInputView / onWindowHidden（仍无条件 cancel）。
        if (voiceHoldActive || isVoicePanelShown()) {
            Log.i(TAG, "QJ-SV skip cancel on onFinishInput (hold active / panel shown)")
        } else {
            // ★ v1.0：失焦且无活跃语音会话时，取消语音识别并恢复工具栏（避免键盘隐藏后仍在倾听）
            cancelVoiceInput("onFinishInput")
        }
        resetComposing()  // 内含 nativeClear
    }

    /**
     * ★ v1.6 语音加固：输入视图结束（IME 窗口即将隐藏，如按 Home 键、切换应用）。
     *
     * **为什么必须补**：按 Home 键 / 切到别处时，系统通常只回调 [onFinishInputView]（以及
     * [onWindowHidden]），而**不保证**回调 [onFinishInput]（输入框焦点可能仍在本应用）。
     * 若此处不取消语音，[VoiceRecognizer] 采集线程会**继续占用麦克风**——既持续占用录音资源、
     * 又可能在用户重回输入框时把「隐藏期间说的话」意外上屏，并造成 AudioRecord 泄漏。
     *
     * 幂等：[cancelVoiceInput] 内部已对「不在语音态且面板未显示」直接 return，重复调用无副作用。
     */
    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        Log.d(TAG, "onFinishInputView: finishingInput=$finishingInput -> cancel voice if any")
        // 界面结束即放弃本次语音输入（不上屏），彻底释放麦克风
        cancelVoiceInput("onFinishInputView")
    }

    /**
     * ★ v1.6 语音加固：IME 窗口隐藏（兜底通道，覆盖部分 ROM 只回调本方法的情形）。
     *
     * 与 [onFinishInputView] 同源目的：只要 IME 窗口不可见，就**绝不允许**麦克风继续采集。
     * 幂等：同 [cancelVoiceInput]。
     */
    override fun onWindowHidden() {
        super.onWindowHidden()
        Log.d(TAG, "onWindowHidden -> cancel voice if any (mic must not stay hot)")
        cancelVoiceInput("onWindowHidden")
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int,
        newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        // 我们自己的 setComposingText/commitText 也会回调这里；只有新光标位置不等于
        // 我们预期的位置时，才视为「用户点了别处」→ 提交拼音原串，避免文本丢失。
        val externalMove = composing.isNotEmpty() && newSelEnd != expectedCursor
        curSelStart = newSelStart
        curSelEnd = newSelEnd
        if (externalMove) {
            Log.d(TAG, "cursor moved externally ($oldSelEnd->$newSelEnd); commit raw composing")
            commitRawComposing()
        }
    }

    override fun onComputeInsets(outInsets: Insets?) {
        super.onComputeInsets(outInsets)
        if (!isFullscreenMode && outInsets != null) {
            // 非全屏时让应用内容避开整个输入法区域（含候选栏/工具栏）
            outInsets.contentTopInsets = outInsets.visibleTopInsets
        }
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onDestroy() {
        Log.i(TAG, "onDestroy: nativeDestroy")
        hideInputModePanel()
        hideClipboardPanel()
        // ★ v0.8：收起光标面板（内部 onPanelHidden 会清掉所有长按 Runnable，防泄漏）
        hideCursorPanel()
        // ★ v1.9 语音：释放离线引擎（内部停采集线程 + 释放 VAD/Recognizer native 句柄）
        try {
            voiceEngine?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "onDestroy: voice engine release error", t)
        }
        voiceEngine = null
        voiceModelReady = false
        hideVoicePanel()
        // ★ v0.7 剪贴板：注销系统剪贴板监听 + 关闭后台线程池
        try {
            clipboardManager?.shutdown()
            Log.i(TAG, "onDestroy: ClipboardHistoryManager shutdown")
        } catch (t: Throwable) {
            Log.w(TAG, "onDestroy: clipboardManager shutdown error", t)
        }
        clipboardManager = null
        if (QingjianNative.libraryLoaded) {
            try {
                QingjianNative.nativeDestroy()
            } catch (t: Throwable) {
                Log.w(TAG, "onDestroy: nativeDestroy error", t)
            }
        }
        nativeReady = false
        super.onDestroy()
    }

    // =========================================================================
    // 返回键双通道（★ v0.6：HyperOS 上 onKeyDown 与 onBackPressed 行为不一致，两条都兜住）
    // =========================================================================

    /**
     * 通道一：物理返回键 [KeyEvent.KEYCODE_BACK] 的 ACTION_DOWN。
     * ★ v0.8 返回键优先级链：光标模式 → 剪贴板面板 → 输入方式面板 → 交系统。
     *   光标模式排最前，因为它**只替换了工具栏那一条**，用户此时最可能的意图就是退出它。
     *   三者互斥，谁可见谁优先消费，不会互相抢或漏。
     *
     * ★ 另：光标模式下，**非 BACK 键的字符输入**会先退出光标模式再放行（见 [shouldExitCursorOnKey]）。
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // ★ v1.0：语音状态优先级**最高** —— 取消识别（不上屏）→ 恢复原工具栏
            if (isVoicePanelShown()) {
                Log.i(TAG, "onKeyDown: BACK -> cancel voice input (priority 0)")
                cancelVoiceInput("back key (onKeyDown)")
                return true
            }
            if (isCursorPanelShown()) {
                Log.i(TAG, "onKeyDown: BACK -> dismiss cursor panel (priority 1)")
                hideCursorPanel()
                return true
            }
            if (isClipboardPanelShown()) {
                Log.i(TAG, "onKeyDown: BACK -> dismiss clipboard panel (priority 2)")
                hideClipboardPanel()
                return true
            }
            if (isInputModePanelShown()) {
                Log.i(TAG, "onKeyDown: BACK -> dismiss input mode panel (priority 3)")
                hideInputModePanel()
                return true
            }
        } else if (isCursorPanelShown() && shouldExitCursorOnKey(keyCode, event)) {
            // ★ 物理键盘路径：输入字符 → 先退出光标模式，再放行该键做正常输入
            Log.i(TAG, "onKeyDown: keyCode=$keyCode -> exit cursor panel (typing), then pass through")
            hideCursorPanel()
            // 不 return true：让事件继续走正常输入链路
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * 通道一补强：物理返回键的 ACTION_UP。
     * 少数机型（含 HyperOS）返回键只触发 up 或 down/up 时序不齐，故 up 也兜一层。
     * ★ v0.8：同样纳入光标模式最高优先级。
     */
    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // ★ v1.0：语音状态优先级最高（与 onKeyDown 保持一致）
            if (isVoicePanelShown()) {
                Log.i(TAG, "onKeyUp: BACK -> cancel voice input (priority 0)")
                cancelVoiceInput("back key (onKeyUp)")
                return true
            }
            if (isCursorPanelShown()) {
                Log.i(TAG, "onKeyUp: BACK -> dismiss cursor panel (priority 1)")
                hideCursorPanel()
                return true
            }
            if (isClipboardPanelShown()) {
                Log.i(TAG, "onKeyUp: BACK -> dismiss clipboard panel (priority 2)")
                hideClipboardPanel()
                return true
            }
            if (isInputModePanelShown()) {
                Log.i(TAG, "onKeyUp: BACK -> dismiss input mode panel (priority 3)")
                hideInputModePanel()
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    /**
     * 通道二：物理返回键回调（Android 13+ / HyperOS 部分机型走 OnBackInvoked 路径，
     * 与 [onKeyDown] 行为不一致）。
     *
     * ⚠️ 注意：`InputMethodService` **未**公开 `onBackPressed()`（经 android-34 `android.jar`
     * 反查确认无此方法），故这里**不能** `override`。改为提供一个 public 方法：
     *  - 作为对外「返回」语义的统一入口，供宿主/系统在支持该回调的机型上直接调用；
     *  - HyperOS 若经 `OnBackInvokedCallback` 触发，会经 [dispatchBack] 汇入本方法。
     * 两条通道最终都收敛到 [handleBack]，保证「面板可见 → 消费事件」语义一致。
     */
    fun onBackPressed() {
        Log.i(TAG, "onBackPressed: BACK channel entry")
        handleBack()
    }

    /**
     * 双通道统一收敛：★ v0.8 返回键优先级链——
     *   0. 光标模式可见 → 收起并返回 true（已消费）；
     *   1. 剪贴板面板可见 → 收起并返回 true（已消费）；
     *   2. 输入方式面板可见 → 收起并返回 true（已消费）；
     *   3. 都不可见 → false（交由系统处理）。
     * @return true 表示事件已被本服务消费（不再冒泡给系统）。
     */
    fun handleBack(): Boolean {
        // ★ v1.0：语音状态优先级最高 —— 取消识别（不上屏）→ 恢复原工具栏
        if (isVoicePanelShown()) {
            Log.i(TAG, "handleBack: BACK -> cancel voice input (priority 0)")
            cancelVoiceInput("back key (handleBack)")
            return true
        }
        if (isCursorPanelShown()) {
            Log.i(TAG, "handleBack: BACK -> dismiss cursor panel (priority 1)")
            hideCursorPanel()
            return true
        }
        if (isClipboardPanelShown()) {
            Log.i(TAG, "handleBack: BACK -> dismiss clipboard panel (priority 2)")
            hideClipboardPanel()
            return true
        }
        if (isInputModePanelShown()) {
            Log.i(TAG, "handleBack: BACK -> dismiss input mode panel (priority 3)")
            hideInputModePanel()
            return true
        }
        return false
    }

    /**
     * 判断某物理键是否属于「输入一个字符」——用于「光标模式下输入字符 → 自动退出」。
     *
     * ★ 为什么判定放在这里（物理键盘通道）而非只放 `onKey`：
     *   软键盘走 `onKey(tag)`；而外接/物理键盘走 `onKeyDown`。两条都是真实入口，
     *   故**两处都埋**（与需求「两处都埋」一致）。
     *
     * 白名单式判定，避免误伤「方向/删除/回车」这类**操作键**（它们不该退出光标模式）：
     *   - BACK                 → 由上面的优先级链处理，不在此列；
     *   - DPAD_* / DEL / ENTER → 明确排除（是移动/删除/换行，不是「输入字符」）；
     *   - 其余：若 `event.unicodeChar != 0` 或 keyCode 是字母/数字/空格/标点，即视为输入字符。
     */
    private fun shouldExitCursorOnKey(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_MOVE_HOME,
            KeyEvent.KEYCODE_MOVE_END, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL,
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT,
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT,
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT,
            KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT,
            KeyEvent.KEYCODE_CAPS_LOCK -> return false
        }
        // unicodeChar 非 0 → 确定是可输入字符（含中日韩、标点、空格）
        if ((event?.unicodeChar ?: 0) != 0) return true
        // 兜底：字母/数字/空格 也直接判为输入字符（部分 ROM 的 unicodeChar 为 0）
        return keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z ||
            keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 ||
            keyCode == KeyEvent.KEYCODE_SPACE
    }

    // =========================================================================
    // assets 解压（目录存在即跳过，只解一次）
    // =========================================================================

    private fun extractDataIfNeeded(dir: File) {
        if (dir.isDirectory && dir.list()?.isNotEmpty() == true) {
            Log.i(TAG, "extract: data dir exists, skip extraction: ${dir.absolutePath}")
            // 老版本解压目录里可能没有 wubi86.tsv（v0.3 及以前），补齐以免五笔不可用
            val wubi = File(dir, WUBI_TSV_NAME)
            if (!wubi.exists()) {
                Log.i(TAG, "extract: wubi86.tsv missing in existing dir; extracting incrementally")
                extractAsset(dir, WUBI_TSV_NAME)
            }
            return
        }
        Log.i(TAG, "extract: assets/$ASSET_DIR -> ${dir.absolutePath}")
        dir.mkdirs()
        val names = assets.list(ASSET_DIR).orEmpty()
        if (names.isEmpty()) Log.e(TAG, "extract: assets/$ASSET_DIR is empty or missing!")
        for (name in names) {
            extractAsset(dir, name)
        }
    }

    /** 单个 asset → filesDir 文件；失败仅记日志，不让 IME 崩。 */
    private fun extractAsset(dir: File, name: String) {
        try {
            assets.open("$ASSET_DIR/$name").use { input ->
                File(dir, name).outputStream().use { output -> input.copyTo(output, 64 * 1024) }
            }
            Log.i(TAG, "extract: $name (${File(dir, name).length()} bytes)")
        } catch (t: Throwable) {
            Log.e(TAG, "extract: $name failed", t)
        }
    }

    // =========================================================================
    // 双态容器（同栏位互斥）——v0.4 保留
    // =========================================================================

    /**
     * 双态容器「状态 A」：显示工具栏、隐藏候选栏。
     * 无拼音缓冲时调用（初始态、删除键清空、提交完成、切换方式后）。
     */
    private fun showToolbar() {
        inputToolbar?.visibility = View.VISIBLE
        candidatesView?.visibility = View.GONE
        Log.d(TAG, "dual-state -> TOOLBAR (state A)")
    }

    /**
     * 双态容器「状态 B」：显示候选栏、隐藏工具栏。
     * 拼音缓冲非空时调用（每次刷新候选）。
     */
    private fun showCandidates() {
        inputToolbar?.visibility = View.GONE
        candidatesView?.visibility = View.VISIBLE
        Log.d(TAG, "dual-state -> CANDIDATES (state B)")
    }

    // =========================================================================
    // ★ v0.6 输入方式面板（InputModePanelView）集成
    // =========================================================================

    /**
     * 装配输入方式面板：定位 [InputModePanelView]（根 FrameLayout 第二个子项），
     * 注入 Listener（onModePicked / onDismissRequested）与可用性回调，并首次渲染卡片。
     *
     * 幂等：找不到视图时仅记日志，不阻塞键盘可用。
     */
    private fun wireInputModePanel() {
        val panelView = keyboardRoot.findViewById<InputModePanelView>(R.id.inputModePanelHost)
        if (panelView == null) {
            Log.e(TAG, "wireInputModePanel: inputModePanelHost not found! panel disabled")
            return
        }
        modePanelView = panelView

        // 可用性/原因回调：面板每次 refresh 时向宿主查询（含五笔探测结果）
        panelView.usabilityProvider = { mode -> isModeUsable(mode) }
        panelView.unavailableReasonProvider = { mode -> modeUnavailableReason(mode) }

        // 交互回调（Listener 接口）
        panelView.listener = object : InputModePanelView.Listener {
            override fun onModePicked(mode: InputMode) {
                Log.i(TAG, "panel listener: onModePicked ${mode.id} (current=${inputMode.id})")
                onModeCardClicked(mode)
            }

            override fun onDismissRequested() {
                Log.i(TAG, "panel listener: onDismissRequested (mode unchanged=${inputMode.id})")
                hideInputModePanel()
            }
        }

        // 设置齿轮 → 打开 SettingsActivity
        panelView.settingsButton?.setOnClickListener {
            it.playSoundEffect(SoundEffectConstants.CLICK)
            Log.i(TAG, "input mode panel: settings gear tapped -> SettingsActivity")
            openSettings()
        }

        panelView.setCurrentMode(inputMode)
        Log.i(TAG, "wireInputModePanel: ready")
    }

    /** 某输入方式是否可用（真实实现 + 数据/引擎就绪）。无数据项恒不可用。 */
    private fun isModeUsable(mode: InputMode): Boolean {
        if (mode.isPlaceholder) return false
        // 英文由 Kotlin 直输，恒可用
        if (!mode.usesEngine) return true
        // 引擎方式：需要引擎就绪且该方式探测通过（未探测过时按可用处理，点击时再校验）
        if (!nativeReady) return false
        return modeUsable[mode] != false
    }

    /** 不可用原因文案（置灰时展示；无数据项由卡片侧统一显示「敬请期待」）。 */
    private fun modeUnavailableReason(mode: InputMode): String = when {
        mode.isPlaceholder -> mode.unavailableHint ?: "敬请期待"
        !nativeReady -> "引擎未就绪"
        mode.requiresWubiData -> mode.unavailableHint ?: "五笔数据缺失，暂未支持"
        else -> "数据缺失"
    }

    /**
     * 卡片点击处理：可用则切换；否则提示、**不切换**（绝不假切换）。
     *
     * ★ v0.6 修订版（禁用卡可点击修复）：禁用卡现在**也会**走到这里（卡片恒 enabled=true）。
     *   这里对「无数据支持」的方式做**硬性短路**——`supported == false` 时：
     *     1. 打日志（体现「拒绝切换 + 原因」）；
     *     2. 弹 Toast（文案取 `unavailableHint`）；
     *     3. **直接 return**，绝不调用 [applyMode]、绝不改 [inputMode]、绝不调 native_set_mode。
     *   对「引擎/数据暂不可用」（如五笔探测失败）同样只提示不切换。
     */
    private fun onModeCardClicked(mode: InputMode) {
        val usable = isModeUsable(mode)
        Log.i(
            TAG,
            "mode card tapped: ${mode.label} (id=${mode.id}, current=${inputMode.label}, usable=$usable)"
        )
        // ★ 红线一：无数据支持（supported=false）→ 拒绝切换，仅提示。
        if (!mode.supported) {
            val reason = modeUnavailableReason(mode)
            Log.w(
                TAG,
                "REFUSE mode switch: ${mode.label} (id=${mode.id}) is unsupported " +
                    "(supported=false); refuse applyMode/native_set_mode; reason=$reason"
            )
            toast(disabledToast(mode, reason))
            return
        }
        // 其他不可用（引擎未就绪 / 五笔数据缺失）→ 同样仅提示、不切换。
        if (!usable) {
            val reason = modeUnavailableReason(mode)
            Log.w(
                TAG,
                "REFUSE mode switch: ${mode.label} (id=${mode.id}) unavailable; " +
                    "refuse applyMode/native_set_mode; reason=$reason"
            )
            toast(disabledToast(mode, reason))
            return
        }
        Log.i(TAG, "ALLOW mode switch: ${mode.label} (id=${mode.id}) -> applyMode")
        applyMode(mode)
    }

    /**
     * 置灰卡 Toast 文案：显示该方式的 `unavailableHint`
     * （五笔卡 = 「五笔数据缺失，暂未支持」；占位卡 = 「敬请期待」）。
     */
    private fun disabledToast(mode: InputMode, reason: String): String {
        val hint = mode.unavailableHint ?: reason
        return getString(R.string.mode_panel_unavailable_toast, mode.label, hint)
    }

    /** 面板是否展开（可见）。 */
    private fun isInputModePanelShown(): Boolean = modePanelView?.visibility == View.VISIBLE

    /**
     * 展开输入方式面板：同步当前高亮 → 刷新卡片 → VISIBLE + bringToFront（覆盖整键盘区）。
     * 若已展开则幂等刷新。
     */
    private fun showInputModePanel() {
        val pv = modePanelView ?: run {
            Log.e(TAG, "showInputModePanel: panel view null; cannot show")
            toast("输入方式面板不可用")
            return
        }
        // ★ v1.0 互斥：语音状态栏展开时先取消语音（避免叠影）
        if (isVoicePanelShown()) {
            Log.i(TAG, "showInputModePanel: voice panel visible -> cancel voice first")
            cancelVoiceInput("show input mode panel")
        }
        pv.setCurrentMode(inputMode)   // 高亮当前方式
        pv.refresh()                   // 刷新可用/置灰三态
        pv.visibility = View.VISIBLE
        pv.bringToFront()              // 确保叠在内容列之上
        Log.i(
            TAG,
            "input mode panel shown (current=${inputMode.label}, native=${inputMode.nativeCode}, " +
                "panel=${inputMode.panel}, visible=${pv.visibility == View.VISIBLE})"
        )
    }

    /** 收起输入方式面板（幂等）：VISIBLE → GONE，回到原键盘。输入方式不变。 */
    private fun hideInputModePanel() {
        val pv = modePanelView ?: return
        if (pv.visibility != View.GONE) {
            pv.visibility = View.GONE
            Log.i(TAG, "input mode panel hidden (GONE); mode unchanged=${inputMode.id}")
        }
    }

    /** 打开设置页（IME 内 startActivity 需要新任务栈）。 */
    private fun openSettings() {
        try {
            val intent = Intent(this, SettingsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "openSettings failed", t)
            toast("无法打开设置")
        }
    }

    // =========================================================================
    // ★ v0.7 剪贴板面板（ClipboardPanelView）集成
    // =========================================================================

    /**
     * 装配剪贴板面板：定位 [ClipboardPanelView]（根 FrameLayout 第三个子项），
     * 注入 [ClipboardHistoryManager] 与 [ClipboardPanelView.Listener]。
     *
     * 幂等：找不到视图或管理器时仅记日志，不阻塞键盘可用。
     */
    private fun wireClipboardPanel() {
        val panelView = keyboardRoot.findViewById<ClipboardPanelView>(R.id.clipboardPanelHost)
        if (panelView == null) {
            Log.e(TAG, "wireClipboardPanel: clipboardPanelHost not found! panel disabled")
            return
        }
        clipboardPanelView = panelView
        panelView.historyManager = clipboardManager

        panelView.listener = object : ClipboardPanelView.Listener {
            override fun onItemPicked(entry: ClipboardEntry) {
                Log.i(TAG, "clipboard listener: onItemPicked id=${entry.id}, len=${entry.text.length}")
                commitClipboardEntry(entry)
            }

            override fun onDismissRequested() {
                Log.i(TAG, "clipboard listener: onDismissRequested")
                hideClipboardPanel()
            }

            override fun onClearRequested() {
                Log.i(TAG, "clipboard listener: onClearRequested -> clear all history")
                clipboardManager?.clearAll()
                toast("已清空剪贴板历史")
            }

            override fun onDeleteRequested(entry: ClipboardEntry) {
                Log.i(TAG, "clipboard listener: onDeleteRequested id=${entry.id}")
                clipboardManager?.deleteEntry(entry.id)
                toast("已删除该条记录")
            }

            override fun onLockToggled(locked: Boolean) {
                Log.i(TAG, "clipboard listener: onLockToggled -> locked=$locked")
            }
        }

        Log.i(TAG, "wireClipboardPanel: ready (historyManager=${clipboardManager != null})")
    }

    /** 剪贴板面板是否展开（可见）。 */
    private fun isClipboardPanelShown(): Boolean = clipboardPanelView?.visibility == View.VISIBLE

    /**
     * 展开剪贴板面板：与输入方式面板**互斥**（先收起输入方式面板）→ VISIBLE + bringToFront
     * → 触发表面 onShow（注册历史监听 + 首次加载）。
     */
    private fun showClipboardPanel() {
        val pv = clipboardPanelView ?: run {
            Log.e(TAG, "showClipboardPanel: panel view null; cannot show")
            toast("剪贴板面板不可用")
            return
        }
        // ★ 互斥：先收起输入方式面板（两个覆盖层同时只能显示一个）
        if (isInputModePanelShown()) {
            Log.i(TAG, "showClipboardPanel: input mode panel was visible -> hide it first")
            hideInputModePanel()
        }
        // ★ v1.0 互斥：语音状态栏展开时先取消语音（避免「语音条 + 剪贴板」叠影）
        if (isVoicePanelShown()) {
            Log.i(TAG, "showClipboardPanel: voice panel visible -> cancel voice first")
            cancelVoiceInput("show clipboard panel")
        }
        pv.visibility = View.VISIBLE
        pv.bringToFront()
        pv.onShow()
        Log.i(
            TAG,
            "clipboard panel shown (category=${pv.currentCategory().id}, " +
                "locked=${clipboardManager?.locked}, visible=${pv.visibility == View.VISIBLE})"
        )
    }

    /** 收起剪贴板面板（幂等）：VISIBLE → GONE + 注销历史监听，回到原键盘。 */
    private fun hideClipboardPanel() {
        val pv = clipboardPanelView ?: return
        if (pv.visibility != View.GONE) {
            pv.onHide()
            pv.visibility = View.GONE
            Log.i(TAG, "clipboard panel hidden (GONE)")
        }
    }

    /**
     * 选中一条剪贴板记录：先结束当前拼音合成（若有）→ `commitText` **完整文本**
     * → 收起面板。
     *
     * ★ 关键（无截断）：直接把 `entry.text` 交给 `commitText`，**不做任何 substring**；
     *   超长文本由 InputConnection 完整上屏（受编辑框自身 maxLength 约束，非本实现截断）。
     */
    private fun commitClipboardEntry(entry: ClipboardEntry) {
        val text = entry.text
        Log.i(TAG, "commitClipboardEntry: id=${entry.id}, len=${text.length} chars (NO truncation)")
        // 若有未结束的拼音合成，先收尾，避免剪贴板文本与合成串交错
        if (composing.isNotEmpty()) {
            Log.i(TAG, "commitClipboardEntry: composing non-empty; commit raw composing first")
            commitRawComposing()
        }
        val ic = currentInputConnection
        if (ic == null) {
            Log.w(TAG, "commitClipboardEntry: no input connection; hide panel only")
            hideClipboardPanel()
            return
        }
        try {
            ic.commitText(text, 1)
            // 光标按实际提交长度前移（onUpdateSelection 会再校准）
            curSelEnd += text.length
            curSelStart = curSelEnd
            expectedCursor = curSelEnd
            Log.i(TAG, "commitClipboardEntry: commitText done (len=${text.length}), cursor=$curSelEnd")
        } catch (t: Throwable) {
            Log.e(TAG, "commitClipboardEntry: commitText failed (len=${text.length})", t)
            toast("上屏失败")
        }
        hideClipboardPanel()
    }

    /** 剪贴板工具栏按钮点击入口。 */
    private fun onClipboardClick() {
        Log.i(TAG, "clipboard key clicked -> show clipboard panel")
        showClipboardPanel()
    }

    // =========================================================================
    // ★ v0.9 收起键盘（工具栏最右「⌄」）
    // =========================================================================

    /**
     * 工具栏「收起键盘」点击入口：清空未完成拼音缓冲 → 收起所有覆盖面板 →
     * 调用 [InputMethodService.requestHideSelf] 立即收起键盘。
     *
     * 设计要点（最小改动，全部复用既有逻辑，不新造轮子）：
     *   1. **清缓冲**：直接复用 [resetComposing]（内含 `finishComposingText` +
     *      [QingjianNative.nativeClear]），保证「无半截候选」。
     *      ⚠️ `resetComposing()` 末尾会调 `showToolbar()` 把双态容器切回状态 A，
     *      但这**不会**与随后的隐藏冲突：
     *        · `showToolbar()` 只改 `inputToolbar`/`candidatesView` 的 **visibility**，
     *          是「键盘还开着时该显示哪一条」的布局选择，与「键盘整体是否可见」无关；
     *        · 紧接着 `requestHideSelf(0)` 让整个 IME 窗口隐藏，之后由系统回调
     *          [onFinishInput]，其内部**再次**调用 `resetComposing()`（幂等），
     *          并在下次 [onStartInputView] 时通过 `showToolbar()` 显式复位到状态 A。
     *      故这里「先 showToolbar 再 hide」是安全的，**不删也不改** resetComposing。
     *   2. **面板残留**：显式收起三块面板（输入方式 / 剪贴板 / 光标）。
     *      依据：`requestHideSelf(0)` 只隐藏 IME **窗口**，不会改这些 View 的 visibility；
     *      虽然下次唤出键盘时 [onStartInputView] 会统一 `hideInputModePanel()` /
     *      `hideClipboardPanel()` / `hideCursorPanel()`（见其实现），但那是「下次」；
     *      本方法在隐藏**之前**就主动收干净，避免隐藏瞬间面板仍叠在键盘上被截屏/状态不一致。
     *      —— 这是**防御性**补齐（幂等，方法内部先判 GONE 直接 return），非必需但更稳。
     *   3. **健壮性**：全程无 NPE 风险——[resetComposing] 内 `currentInputConnection?.let{}`
     *      已做空判；面板隐藏方法均对 null view 早返回。整个流程包在 try/catch 兜底，
     *      任何异常只记日志，**绝不 crash**。
     */
    private fun onHideKeyboardClick() {
        Log.i(TAG, "toolbar: hide keyboard clicked")
        try {
            // 1) 清空未完成拼音缓冲（复用既有逻辑；内含 finishComposingText + nativeClear）
            resetComposing()
            // 2) 收起所有覆盖/插槽面板，避免隐藏瞬间面板状态残留
            cancelVoiceInput("hide keyboard")
            hideInputModePanel()
            hideClipboardPanel()
            hideCursorPanel()
            // 3) 请求系统立即收起本 IME 键盘（flags=0：无动画延迟）
            requestHideSelf(0)
            Log.i(TAG, "toolbar: hide keyboard requested (requestHideSelf(0))")
        } catch (t: Throwable) {
            // 焦点变化期间偶发的 InputConnection/窗口异常，兜底不 crash
            Log.w(TAG, "toolbar: hide keyboard failed (swallowed, no crash)", t)
        }
    }

    // =========================================================================
    // ★ v0.8 光标模式（CursorModePanelView）集成
    //
    //   形态与触发：
    //     · 单击工具栏「<I>」 → Mode.OPERATION（图二：两行宫格）
    //     · 长按工具栏「<I>」 → Mode.SHIFT    （图三：<<< ● >>> 平移条）
    //
    //   替换范围（关键）：**只替换工具栏那一条**——
    //     激活：cursorModeHost VISIBLE + candidatesContainer GONE
    //     退出：cursorModeHost GONE    + candidatesContainer VISIBLE（内部双态照旧）
    //   ★ 键盘字母区 panelHost 全程不动、可见可点；★ 不改任何既有布局尺寸。
    //
    //   光标动作唯一出口：[moveCursor] / [extendSelection]（见下），全部在主线程执行。
    // =========================================================================

    /**
     * 装配光标模式面板：定位 [CursorModePanelView]（contentColumn 内、与候选栏平级），
     * 注入 [CursorModePanelView.Listener]，所有回调转译为服务层动作。
     *
     * 幂等：找不到视图时仅记日志，不阻塞键盘可用。
     */
    private fun wireCursorModePanel() {
        val host = keyboardRoot.findViewById<FrameLayout>(R.id.cursorModeHost)
        if (host == null) {
            Log.e(TAG, "wireCursorModePanel: cursorModeHost not found! cursor feature disabled")
            return
        }
        cursorModeHost = host

        val panelView = CursorModePanelView(this)
        host.addView(
            panelView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
        cursorPanel = panelView

        panelView.listener = object : CursorModePanelView.Listener {
            override fun onMoveCursor(dir: CursorModePanelView.Direction, step: Int) {
                onCursorMove(dir, step)
            }

            override fun onDismissRequested() {
                Log.i(TAG, "cursor listener: onDismissRequested")
                hideCursorPanel()
            }

            override fun onSelectModeRequested() {
                onCursorSelectMode()
            }

            override fun onSelectAllRequested() {
                onCursorSelectAll()
            }

            override fun onCopyRequested() {
                onCursorCopy()
            }

            override fun onPasteRequested() {
                onCursorPaste()
            }

            override fun onOpenClipboardRequested() {
                Log.i(TAG, "cursor listener: onOpenClipboardRequested -> switch to clipboard panel")
                // 先退出光标模式，再打开剪贴板面板（两者视觉上同位，避免叠影）
                hideCursorPanel()
                showClipboardPanel()
            }

            override fun onDeleteSelectionRequested() {
                onCursorDeleteSelection()
            }
        }

        Log.i(
            TAG,
            "wireCursorModePanel: ready (host=${host.id}, panel=CursorModePanelView, " +
                "initialVisibility=${host.visibility == View.GONE})"
        )
    }

    /**
     * 给工具栏「<I>」入口挂「单击 / 长按」分流。
     *
     * ★ 实现方式：**纯 OnTouchListener + Handler 自计时**。
     *
     * 为什么**不能**用 `setOnLongClickListener`（这一点必须说清，否则后人会"顺手简化"而埋雷）：
     *   `View.dispatchTouchEvent` 的长按检测位于 `OnTouchListener` **之后**：
     *   只要 `OnTouchListener.onTouch` 返回 `true`（我们为了拿到 ACTION_UP 必须返回 true），
     *   View 就会**跳过**自身的长按/点击检测链路，`OnLongClickListener` **永远不会被调用**。
     *   二者共存 = 长按分支是死代码。故这里彻底抛弃 `setOnLongClickListener`，
     *   用 [entryHoldRunnable] 精确使用 [CursorModePanelView.HOLD_START_DELAY_MS]（300ms）。
     *
     * 时序：
     *   ACTION_DOWN → 记录时刻、置 `entryLongPressFired=false`、投递 [entryHoldRunnable]（延迟 300ms）
     *   300ms 到   → `entryHoldRunnable` 执行：置 `entryLongPressFired=true` → 弹**平移条**（图三）
     *   ACTION_UP  → 移除 Runnable；若 `entryLongPressFired==false` → 判为**单击** → 弹**操作面板**（图二）
     *   CANCEL     → 移除 Runnable，复位
     *
     * ⚠️ 本按钮在 [wireKeys] 中被按 tag(`special:cursor`) 单独跳过，不重复挂 click。
     */
    private fun wireCursorEntryButton(button: Button?) {
        val b = button ?: run {
            Log.e(TAG, "wireCursorEntryButton: btnCursor not found! cursor entry disabled")
            return
        }
        cursorEntryButton = b

        b.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    entryDownAt = SystemClock.uptimeMillis()
                    entryLongPressFired = false
                    // 300ms 后若仍按住 → 长按 → 平移条
                    entryHoldHandler.postDelayed(entryHoldRunnable, CURSOR_ENTRY_HOLD_DELAY_MS)
                    true
                }

                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    // 先撤掉长按定时器：只要抬手就不再触发长按分支
                    entryHoldHandler.removeCallbacks(entryHoldRunnable)
                    val elapsed = if (entryDownAt < 0) -1L else SystemClock.uptimeMillis() - entryDownAt
                    val fired = entryLongPressFired
                    entryDownAt = -1L
                    entryLongPressFired = false
                    if (!fired) {
                        // 未达长按阈值 → **单击** → 光标操作面板（图二）
                        Log.i(
                            TAG,
                            "cursor entry: TAP (elapsed=${elapsed}ms, " +
                                "threshold=${CURSOR_ENTRY_HOLD_DELAY_MS}ms) -> OPERATION panel"
                        )
                        v.playSoundEffect(SoundEffectConstants.CLICK)
                        showCursorPanel(CursorModePanelView.Mode.OPERATION)
                    } else {
                        Log.i(TAG, "cursor entry: release after LONG PRESS (elapsed=${elapsed}ms); no tap action")
                    }
                    v.performClick()
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    entryHoldHandler.removeCallbacks(entryHoldRunnable)
                    Log.d(TAG, "cursor entry: CANCEL (longPressFired=$entryLongPressFired)")
                    entryDownAt = -1L
                    entryLongPressFired = false
                    true
                }

                else -> false
            }
        }

        Log.i(
            TAG,
            "wireCursorEntryButton: ready (holdDelay=${CURSOR_ENTRY_HOLD_DELAY_MS}ms, " +
                "panelHoldDelay=${CursorModePanelView.HOLD_START_DELAY_MS}ms, " +
                "shared=${CURSOR_ENTRY_HOLD_DELAY_MS == CursorModePanelView.HOLD_START_DELAY_MS})"
        )
    }

    /** 光标面板是否已显示（宿主容器可见）。 */
    private fun isCursorPanelShown(): Boolean = cursorModeHost?.visibility == View.VISIBLE

    /**
     * 显示光标面板（指定形态）。
     *
     * 替换语义（**只替换工具栏那一条**）：
     *   1. 与两个整屏覆盖层**互斥**：先收起输入方式面板 / 剪贴板面板；
     *   2. `cursorModeHost` VISIBLE（插在候选栏位置）+ `bringToFront()`；
     *   3. `candidatesContainer` GONE —— 即「原工具栏所在的一条被替换掉」；
     *   4. **不动** `panelHost`（键盘字母区）：全程可见可点。
     *
     * ⚠️ 第 4 点是本版与 v0.6/v0.7 覆盖层的本质差别，也是「不覆盖键盘」的实现保证。
     */
    private fun showCursorPanel(mode: CursorModePanelView.Mode) {
        val host = cursorModeHost
        val panel = cursorPanel
        if (host == null || panel == null) {
            Log.e(TAG, "showCursorPanel: host/panel null; cannot show")
            toast("光标面板不可用")
            return
        }
        // 互斥：两个整屏覆盖层同时只能存在一个
        if (isInputModePanelShown()) {
            Log.i(TAG, "showCursorPanel: input mode panel visible -> hide it first")
            hideInputModePanel()
        }
        if (isClipboardPanelShown()) {
            Log.i(TAG, "showCursorPanel: clipboard panel visible -> hide it first")
            hideClipboardPanel()
        }
        // ★ v1.0 互斥：语音状态栏与光标面板同为「工具栏那一条」，先取消语音再显示光标
        if (isVoicePanelShown()) {
            Log.i(TAG, "showCursorPanel: voice panel visible -> cancel voice first")
            cancelVoiceInput("show cursor panel")
        }

        panel.setMode(mode)
        host.visibility = View.VISIBLE
        host.bringToFront()
        // ★ 只隐藏候选栏容器（原工具栏那一条），键盘字母区完全不动
        candidatesContainer?.visibility = View.GONE

        Log.i(
            TAG,
            "cursor panel shown: mode=$mode, host=${host.visibility == View.VISIBLE}, " +
                "candidatesContainer=${candidatesContainer?.visibility == View.GONE} (replaced)," +
                " keyboardPanelHostUnchanged=${::panelQwerty.isInitialized}"
        )
    }

    /**
     * 收起光标面板（幂等）：回到「工具栏那一条」。
     *   1. 面板内先 `onPanelHidden()` —— **确保抬手即停**，移除所有长按 Runnable；
     *   2. 复位临时 UI（「选择」高亮）；
     *   3. `cursorModeHost` GONE；
     *   4. `candidatesContainer` 恢复 VISIBLE —— 其内部 toolbar/candidates 双态
     *      由既有的 [showToolbar] / [showCandidates] 决定，本方法**不做**任何干预，
     *      保证「候选栏双态零改动」。
     */
    private fun hideCursorPanel() {
        val host = cursorModeHost ?: return
        if (host.visibility == View.GONE) return
        cursorPanel?.onPanelHidden()
        cursorPanel?.resetUiState()
        host.visibility = View.GONE
        candidatesContainer?.visibility = View.VISIBLE
        Log.i(
            TAG,
            "cursor panel hidden (GONE); candidatesContainer restored=" +
                "${candidatesContainer?.visibility == View.VISIBLE}, " +
                "stateA(toolbar)=${inputToolbar?.visibility == View.VISIBLE}, " +
                "stateB(candidates)=${candidatesView?.visibility == View.VISIBLE}"
        )
    }

    // =========================================================================
    // 光标移动核心
    // =========================================================================

    /**
     * 方向键回调入口（单击与长按连续都走这里）。
     * 「选择」模式开启时语义变为**扩展/收缩选区**，否则为**移动插入点**。
     */
    private fun onCursorMove(dir: CursorModePanelView.Direction, step: Int) {
        val selectMode = cursorPanel?.isSelectModeActive() == true
        if (selectMode) extendSelection(dir, step) else moveCursor(dir, step)
    }

    /**
     * ★ 光标移动核心（**唯一出口**，严格按任务纪律）：
     *   1. 取 `InputConnection`，保证非空；
     *   2. `getSelectionStart()` 取当前位置；
     *   3. 计算 `newPos = current + offset`；
     *   4. 边界钳制 `Math.max(0, Math.min(newPos, textLength))`；
     *   5. `setSelection(newPos, newPos)`；
     *   6. **必须在主线程**调用（本方法只从 View 回调进入，天然主线程；仍显式断言）。
     *
     * 边界与选区处理：
     *   · 位置 0 不能再左移、位置末尾不能再右移 → 拦截并打日志（`boundary blocked`），
     *     **不发** `setSelection`（避免无意义的 IPC 与光标闪烁）；
     *   · 若当前有选中文本（`selStart != selEnd`）→ **取消选中并折叠到左/右端**：
     *       向左 → 折叠到选区**左端**；向右 → 折叠到选区**右端**
     *     （符合直觉：从「选中块」的左/右边缘继续走）。
     *
     * 垂直方向（图二的 ^ / v）在本版本**降级为行首/行尾语义**：
     *   Android 的 `InputConnection` **没有**跨行移动插入点的公开 API
     *   （`setSelection` 只能设置绝对索引，`getTextBeforeCursor` 也无法可靠换算行号），
     *   故按「上 = 移到行首、下 = 移到行尾」实现，并在日志中显式标注降级。
     *   —— 这是**如实降级**，不是静默假装支持。
     */
    private fun moveCursor(dir: CursorModePanelView.Direction, step: Int) {
        // 6) 主线程断言：所有 InputConnection 操作必须在主线程
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            Log.e(TAG, "moveCursor: NOT on main thread! posting to main thread (should not happen)")
            cursorModeHost?.post { moveCursor(dir, step) }
            return
        }

        // 1) InputConnection 非空
        val ic = currentInputConnection
        if (ic == null) {
            Log.w(TAG, "moveCursor: no input connection; ABORT (focus would be lost otherwise)")
            return
        }

        // 2) 当前选区
        // ★ v0.8 修复：InputConnection **没有** getSelectionStart()/getSelectionEnd()
        //   （二者是 android.text.Selection 的静态方法，作用于 Spannable/Editable）。
        //   IME 侧读取当前选区的正确途径是 getExtractedText()（API 3+）的
        //   ExtractedText.selectionStart/selectionEnd 公开字段。
        //   ⚠️ ExtractedText 是以「文本窗口」为单位返回的：selectionStart/End 是**窗口内**
        //   的相对索引，需加上 startOffset 才换算为与 setSelection 一致的绝对索引。
        val ext = runCatching {
            ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
        }.getOrNull()
        val selStart = ext?.let { it.selectionStart + it.startOffset } ?: -1
        val selEnd = ext?.let { it.selectionEnd + it.startOffset } ?: -1
        if (selStart < 0 || selEnd < 0 || ext == null) {
            Log.w(TAG, "moveCursor: selection unknown (start=$selStart, end=$selEnd); ABORT")
            return
        }

        // 3) 文本总长（用于边界钳制）
        val textLength = textLengthOf(ic)

        // 垂直方向：降级为行首/行尾
        if (dir.dy != 0) {
            Log.i(
                TAG,
                "moveCursor: UP/DOWN has no cross-line API in InputConnection; " +
                    "degrade to line ${if (dir.dy < 0) "START" else "END"} (step ignored)"
            )
            moveToLineEdge(ic, dir.dy < 0, selStart, selEnd)
            return
        }

        // 有选区 → 先折叠到左/右端（取消选中）
        var base = selStart
        if (selStart != selEnd) {
            base = if (dir.dx < 0) minOf(selStart, selEnd) else maxOf(selStart, selEnd)
            Log.i(
                TAG,
                "moveCursor: selection [$selStart,$selEnd] exists -> collapse to " +
                    "${if (dir.dx < 0) "LEFT" else "RIGHT"} edge=$base (deselect)"
            )
        }

        // 3') 计算新位置
        val newPosRaw = base + dir.dx * step
        // 4) 边界钳制：Math.max(0, Math.min(newPos, textLength))
        val newPos = Math.max(0, Math.min(newPosRaw, textLength))

        // 边界拦截：已达端点且仍在同方向推 → 不打 setSelection（明确记录）
        if (newPos == base && (newPosRaw < 0 || newPosRaw > textLength)) {
            Log.w(
                TAG,
                "moveCursor: boundary blocked. dir=$dir, step=$step, base=$base, " +
                    "raw=$newPosRaw clamped=$newPos, textLength=$textLength " +
                    "(at ${if (newPosRaw < 0) "START" else "END"}; no setSelection issued)"
            )
            return
        }

        // 5) 移动光标
        val ok = ic.setSelection(newPos, newPos)
        // 本地镜像同步（供 onUpdateSelection 与后续提交逻辑校准）
        curSelStart = newPos
        curSelEnd = newPos
        expectedCursor = newPos
        Log.i(
            TAG,
            "moveCursor: dir=$dir, step=$step, base=$base, raw=$newPosRaw -> newPos=$newPos, " +
                "textLength=$textLength, setSelection=$ok, mainThread=true"
        )
    }

    /**
     * 「选择」模式：用方向键**扩展/收缩选区**（`setSelection` 双端）。
     * 语义：以「锚点」为固定端，另一端点随方向键移动。
     *   · 若当前无选区 → 锚点 = 当前位置；
     *   · 若已有选区 → 锚点取**远离移动方向**的那一端（即继续朝同方向扩展、反向则收缩）。
     */
    private fun extendSelection(dir: CursorModePanelView.Direction, step: Int) {
        val ic = currentInputConnection
        if (ic == null) {
            Log.w(TAG, "extendSelection: no input connection; ABORT")
            return
        }
        // ★ v0.8 修复：同 moveCursor，改用 getExtractedText()（InputConnection 无 getSelection*）
        val ext = runCatching {
            ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
        }.getOrNull()
        val selStart = ext?.let { it.selectionStart + it.startOffset } ?: -1
        val selEnd = ext?.let { it.selectionEnd + it.startOffset } ?: -1
        if (selStart < 0 || selEnd < 0 || ext == null) {
            Log.w(TAG, "extendSelection: selection unknown; ABORT")
            return
        }
        val textLength = textLengthOf(ic)

        if (dir.dy != 0) {
            Log.i(
                TAG,
                "extendSelection: UP/DOWN degrade to line ${if (dir.dy < 0) "START" else "END"} " +
                    "(extend selection to line edge)"
            )
            val target = if (dir.dy < 0) lineStartOf(ic, minOf(selStart, selEnd))
            else lineEndOf(ic, maxOf(selStart, selEnd))
            val anchor = if (dir.dy < 0) maxOf(selStart, selEnd) else minOf(selStart, selEnd)
            applySelection(ic, anchor, target, "extend(line-edge)")
            return
        }

        // 锚点：无选区时取自身；有选区时取「不动的那一端」
        val anchor: Int
        val movingEdge: Int
        if (selStart == selEnd) {
            anchor = selStart
            movingEdge = selStart
        } else if (dir.dx < 0) {
            // 向左扩展：右端固定，左端移动
            anchor = maxOf(selStart, selEnd)
            movingEdge = minOf(selStart, selEnd)
        } else {
            anchor = minOf(selStart, selEnd)
            movingEdge = maxOf(selStart, selEnd)
        }
        val targetRaw = movingEdge + dir.dx * step
        val target = Math.max(0, Math.min(targetRaw, textLength))
        if (target == movingEdge && (targetRaw < 0 || targetRaw > textLength)) {
            Log.w(
                TAG,
                "extendSelection: boundary blocked. dir=$dir, edge=$movingEdge, " +
                    "raw=$targetRaw clamped=$target, textLength=$textLength"
            )
            return
        }
        applySelection(ic, anchor, target, "extend")
    }

    /** 统一落地一次双端选区设置并同步本地镜像。 */
    private fun applySelection(ic: android.view.inputmethod.InputConnection, anchor: Int, target: Int, tag: String) {
        val ok = ic.setSelection(anchor, target)
        curSelStart = minOf(anchor, target)
        curSelEnd = maxOf(anchor, target)
        expectedCursor = curSelEnd
        Log.i(
            TAG,
            "extendSelection[$tag]: anchor=$anchor, target=$target -> " +
                "sel=[$curSelStart,$curSelEnd] (len=${kotlin.math.abs(target - anchor)}), setSelection=$ok"
        )
    }

    /** 「选择」按钮：仅切换面板 UI 高亮 + 日志；具体扩展行为在 [extendSelection]。 */
    private fun onCursorSelectMode() {
        val active = cursorPanel?.isSelectModeActive() == true
        Log.i(TAG, "cursor ops: SELECT mode = $active (arrow keys now ${if (active) "EXTEND selection" else "MOVE caret"})")
    }

    /**
     * 「全选」：`setSelection(0, textLength)`。
     * 注意：**不**在光标面板可见时退出面板（用户通常接着要移动/复制）。
     */
    private fun onCursorSelectAll() {
        val ic = currentInputConnection ?: run {
            Log.w(TAG, "onCursorSelectAll: no input connection")
            return
        }
        val len = textLengthOf(ic)
        if (len <= 0) {
            Log.w(TAG, "onCursorSelectAll: text empty; nothing to select")
            toast(getString(R.string.cursor_no_selection))
            return
        }
        val ok = ic.setSelection(0, len)
        curSelStart = 0
        curSelEnd = len
        expectedCursor = len
        Log.i(TAG, "onCursorSelectAll: setSelection(0,$len) -> $ok (len=$len)")
    }

    /**
     * 「复制」：把当前选区文本写入**系统剪贴板**。
     *
     * 取文本用 `getSelectedText` 优先；部分编辑框返回 null 时，回退用
     * `getTextBeforeCursor` 拼接（避免长文本被 `getSelectedText` 的实现限制截断）。
     * ★ 复制的是**全量选区**，不做任何截断（与 v0.7 剪贴板上屏同纪律）。
     */
    private fun onCursorCopy() {
        val ic = currentInputConnection ?: run {
            Log.w(TAG, "onCursorCopy: no input connection")
            return
        }
        // ★ v0.8 修复：同 moveCursor，改用 getExtractedText()（InputConnection 无 getSelection*）
        val ext = runCatching {
            ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
        }.getOrNull()
        val selStart = ext?.let { it.selectionStart + it.startOffset } ?: -1
        val selEnd = ext?.let { it.selectionEnd + it.startOffset } ?: -1
        if (selStart < 0 || selEnd < 0 || selStart == selEnd) {
            Log.w(TAG, "onCursorCopy: no selection (start=$selStart, end=$selEnd); nothing to copy")
            toast(getString(R.string.cursor_copy_empty))
            return
        }
        val selected = try {
            ic.getSelectedText(0)?.toString()
        } catch (t: Throwable) {
            Log.w(TAG, "onCursorCopy: getSelectedText threw", t)
            null
        }
        val text = selected ?: fallbackSelectedText(ic, selStart, selEnd)
        if (text.isNullOrEmpty()) {
            Log.w(TAG, "onCursorCopy: resolved text empty; nothing to copy")
            toast(getString(R.string.cursor_copy_empty))
            return
        }
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("qingjian-cursor", text))
            Log.i(TAG, "onCursorCopy: copied ${text.length} chars to system clipboard (NO truncation)")
            toast(getString(R.string.cursor_copy_done))
        } catch (t: Throwable) {
            Log.e(TAG, "onCursorCopy: setPrimaryClip failed", t)
            toast(getString(R.string.cursor_copy_empty))
        }
    }

    /**
     * 「粘贴」：读取**系统剪贴板**并把**完整文本**交给 `commitText`。
     * ★ 与 v0.7 剪贴板上屏同纪律：`entry.text` 全量、**不做 substring**。
     */
    private fun onCursorPaste() {
        val ic = currentInputConnection ?: run {
            Log.w(TAG, "onCursorPaste: no input connection")
            return
        }
        val text = try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip
            if (clip == null || clip.itemCount == 0) null
            else clip.getItemAt(0)?.coerceToText(this)?.toString()
        } catch (t: Throwable) {
            Log.w(TAG, "onCursorPaste: read clipboard failed", t)
            null
        }
        if (text.isNullOrEmpty()) {
            Log.w(TAG, "onCursorPaste: clipboard empty; nothing to paste")
            toast(getString(R.string.cursor_paste_empty))
            return
        }
        // 有未结束的拼音合成 → 先收尾，避免交错
        if (composing.isNotEmpty()) {
            Log.i(TAG, "onCursorPaste: composing non-empty; commit raw composing first")
            commitRawComposing()
        }
        try {
            ic.commitText(text, 1)
            curSelEnd += text.length
            curSelStart = curSelEnd
            expectedCursor = curSelEnd
            Log.i(TAG, "onCursorPaste: commitText done (len=${text.length} chars, NO truncation)")
        } catch (t: Throwable) {
            Log.e(TAG, "onCursorPaste: commitText failed (len=${text.length})", t)
        }
    }

    /**
     * 「删除」：删除当前**选中文本**；若无选区，则退化为一次退格（[onBackspace]）。
     * 删除走 `commitText("")` 覆盖选区——这是 IME 侧最稳妥的删选区方式
     * （部分编辑框对 `deleteSurroundingText` + 选区组合行为不一致）。
     */
    private fun onCursorDeleteSelection() {
        val ic = currentInputConnection ?: run {
            Log.w(TAG, "onCursorDeleteSelection: no input connection")
            return
        }
        // ★ v0.8 修复：同 moveCursor，改用 getExtractedText()（InputConnection 无 getSelection*）
        val ext = runCatching {
            ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
        }.getOrNull()
        val selStart = ext?.let { it.selectionStart + it.startOffset } ?: -1
        val selEnd = ext?.let { it.selectionEnd + it.startOffset } ?: -1
        if (selStart < 0 || selEnd < 0 || ext == null) {
            Log.w(TAG, "onCursorDeleteSelection: selection unknown; ABORT")
            return
        }
        if (selStart == selEnd) {
            Log.i(TAG, "onCursorDeleteSelection: no selection -> degrade to backspace")
            onBackspace()
            return
        }
        val delLen = kotlin.math.abs(selEnd - selStart)
        val lo = minOf(selStart, selEnd)
        try {
            ic.commitText("", 1)  // 空串覆盖选区 = 删除
            curSelStart = lo
            curSelEnd = lo
            expectedCursor = lo
            Log.i(TAG, "onCursorDeleteSelection: deleted selection [$selStart,$selEnd] (len=$delLen) -> caret=$lo")
            toast(getString(R.string.cursor_delete_ready))
        } catch (t: Throwable) {
            Log.e(TAG, "onCursorDeleteSelection: delete failed", t)
        }
    }

    // ---- 光标/文本读取小工具 ----

    /**
     * 计算文本总长度（用于边界钳制）。
     * 策略：`getTextBeforeCursor(MAX) + getTextAfterCursor(MAX)` 求和。
     * ⚠️ 已知局限（**如实记录**）：`getTextBeforeCursor` 在部分编辑框上会被系统
     *    限制在若干字符内（如 1000/10000），超长文本场景下本值可能偏小，
     *    导致「末尾边界」判定提前触发。本版接受该局限并在日志中标注；
     *    后续如需精确总长，应由宿主 App 侧通过 `EditorInfo`/自定义 API 提供。
     */
    private fun textLengthOf(ic: android.view.inputmethod.InputConnection): Int {
        val before = ic.getTextBeforeCursor(TEXT_PROBE_MAX, 0)?.length ?: 0
        val after = ic.getTextAfterCursor(TEXT_PROBE_MAX, 0)?.length ?: 0
        val total = before + after
        if (total >= TEXT_PROBE_MAX) {
            Log.w(
                TAG,
                "textLengthOf: probe hit cap ($before+$after chars); " +
                    "text length may be UNDERESTIMATED by this IME (known limitation)"
            )
        }
        return total
    }

    /** `getSelectedText` 为 null 时的回退：用前后游标拼接出选区文本（全量、不截断）。 */
    private fun fallbackSelectedText(
        ic: android.view.inputmethod.InputConnection,
        selStart: Int,
        selEnd: Int
    ): String? {
        val lo = minOf(selStart, selEnd)
        val hi = maxOf(selStart, selEnd)
        val before = ic.getTextBeforeCursor(TEXT_PROBE_MAX, 0)?.toString() ?: ""
        val after = ic.getTextAfterCursor(TEXT_PROBE_MAX, 0)?.toString() ?: ""
        // 游标位于选区末端：before 的尾部 + after 的前段 = 选区
        // 但调用时刻的游标位置不定，故用「整段文本 = before + after」按索引切片。
        val whole = before + after
        val baseIndex = TEXT_PROBE_MAX - before.length  // whole 中 before 的起始偏移基准
        val s = lo - baseIndex
        val e = hi - baseIndex
        if (s < 0 || e > whole.length || s > e) {
            Log.w(TAG, "fallbackSelectedText: index out of window (s=$s,e=$e,len=${whole.length}); giving up")
            return null
        }
        val out = whole.substring(s, e)
        Log.i(TAG, "fallbackSelectedText: recovered ${out.length} chars via before+after (fallback path)")
        return out
    }

    /** 当前行行首的绝对索引（用 `getTextBeforeCursor` 找最近的换行）。 */
    private fun lineStartOf(ic: android.view.inputmethod.InputConnection, pos: Int): Int {
        val before = ic.getTextBeforeCursor(TEXT_PROBE_MAX, 0)?.toString() ?: return pos
        val nl = before.lastIndexOf('\n')
        return if (nl < 0) 0 else pos - (before.length - 1 - nl)
    }

    /** 当前行行尾的绝对索引。 */
    private fun lineEndOf(ic: android.view.inputmethod.InputConnection, pos: Int): Int {
        val after = ic.getTextAfterCursor(TEXT_PROBE_MAX, 0)?.toString() ?: return pos
        val nl = after.indexOf('\n')
        return if (nl < 0) pos + after.length else pos + nl
    }

    /** 上下方向：把插入点移到行首/行尾（`toStart=true` 为行首）。 */
    private fun moveToLineEdge(
        ic: android.view.inputmethod.InputConnection,
        toStart: Boolean,
        selStart: Int,
        selEnd: Int
    ) {
        val cur = if (selStart == selEnd) selEnd else if (toStart) minOf(selStart, selEnd) else maxOf(selStart, selEnd)
        val target = if (toStart) lineStartOf(ic, cur) else lineEndOf(ic, cur)
        val ok = ic.setSelection(target, target)
        curSelStart = target
        curSelEnd = target
        expectedCursor = target
        Log.i(
            TAG,
            "moveCursor(line-edge): to ${if (toStart) "LINE_START" else "LINE_END"} " +
                "$cur -> $target, setSelection=$ok [DEGRADED from vertical move]"
        )
    }

    // =========================================================================
    // 切换逻辑（native 码 + 键盘面板双维度）
    // =========================================================================

    /** 给「切换输入方式」按钮挂单击（单击**直接**弹卡片面板）。 */
    private fun wireSwitchButton(button: Button?) {
        val b = button ?: run {
            Log.e(TAG, "wireSwitchButton: btnSwitchMode not found!")
            return
        }
        b.setOnClickListener { v ->
            v.playSoundEffect(SoundEffectConstants.CLICK)
            onSwitchModeClick()
        }
    }

    /** 单击切换键：**直接**弹出输入方式卡片网格面板。 */
    private fun onSwitchModeClick() {
        Log.i(TAG, "switch key clicked -> show input mode panel (current=${inputMode.label})")
        showInputModePanel()
    }

    /**
     * 应用输入方式：先按 **native 码** 装配引擎（nativeSetMode），成功后才改本地状态、
     * 切换 **键盘面板**（QWERTY/T9）并清空缓冲。
     *
     * ★ 关键：native 只有 4 个方式码；26/9 的区别是键盘布局层。故：
     *   - native 装配用 `target.nativeCode`（PINYIN_26 与 PINYIN_9 都是 0）；
     *   - 键盘切换用 `target.panel`（QWERTY / T9）。
     *
     * 失败（如五笔码表缺失）→ 不改状态、置灰该方式并 toast 原因，**绝不假切换**。
     */
    private fun applyMode(target: InputMode) {
        if (target.isPlaceholder) {
            Log.w(TAG, "applyMode: ${target.label} is placeholder; refuse")
            return
        }
        // ★ 关键点：同一 native 码 + 同一键盘面板才算「完全相同的当前方式」；否则需要切换
        val sameNative = (target.nativeCode == inputMode.nativeCode)
        val samePanel = (target.panel == inputMode.panel)
        if (target == inputMode && sameNative && samePanel && isModeUsable(target)) {
            Log.d(TAG, "applyMode: already ${target.label}, no-op (still hide panel)")
            hideInputModePanel()
            return
        }

        // 1) native 装配：仅当引擎方式且 native 码发生变化（或首次装配）时才调用，减少冗余
        val scheme = if (target == InputMode.SHUANGPIN) shuangpinScheme else ""
        val ok: Boolean = if (target.usesEngine) {
            if (!nativeReady) {
                Log.w(TAG, "applyMode: engine not ready; ${target.label} unavailable")
                false
            } else if (sameNative && modeUsable[target] == true) {
                // native 码没变且此前已验证可用（如 PINYIN_26 → PINYIN_9），无需重复 nativeSetMode
                Log.i(TAG, "applyMode: nativeCode ${target.nativeCode} unchanged & usable; skip nativeSetMode")
                true
            } else {
                val rc = try {
                    QingjianNative.nativeSetMode(target.nativeCode, scheme)
                } catch (t: Throwable) {
                    Log.e(TAG, "applyMode: nativeSetMode threw", t)
                    0
                }
                Log.i(TAG, "applyMode: nativeSetMode(${target.nativeCode}, '$scheme') -> $rc")
                rc == 1
            }
        } else {
            // 英文：引擎不参与（Kotlin 直输），恒成功
            Log.i(TAG, "applyMode: ENGLISH direct-input mode (engine bypassed)")
            true
        }

        if (!ok) {
            modeUsable[target] = false
            Log.w(TAG, "applyMode: ${target.label} unavailable; keeping ${inputMode.label}")
            toast(getString(R.string.mode_panel_disabled_hint, target.label, modeUnavailableReason(target)))
            modePanelView?.refresh()
            return
        }

        // 2) 提交切换：更新本地状态 + 键盘面板 + 清缓冲 + 收起面板
        inputMode = target
        modeUsable[target] = true
        Log.i(TAG, "applyMode: input mode -> ${target.label} (native=${target.nativeCode}, panel=${target.panel}); clear buffer")
        resetComposing()                       // 清空拼音缓冲（内含 finishComposingText / nativeClear）
        showPanel(currentKeyboardPanel())      // 切换键盘布局（QWERTY / T9）
        updateModeButtons()                    // 工具栏标签跟随
        modePanelView?.setCurrentMode(inputMode) // 面板高亮跟随
        hideInputModePanel()                   // 收起面板 → 回到新键盘
    }

    /** 当前输入方式应显示的键盘面板（英文/五笔同规则：由 InputMode.panel 决定）。 */
    private fun currentKeyboardPanel(): Panel =
        if (inputMode.panel == KeyboardPanel.T9) Panel.T9 else Panel.QWERTY

    /**
     * 探测各方式可用性（init 后一次）：把当前方式装配好，并尝试探测五笔数据是否可装载。
     * 结果写进 [modeUsable]，供面板置灰；探测后回滚到默认全拼，不改变用户可见状态。
     */
    private fun probeModeAvailability() {
        // 先装配全拼（native 0），PINYIN_26 / PINYIN_9 同码，共用可用性
        try {
            val rc = QingjianNative.nativeSetMode(InputMode.PINYIN_26.nativeCode, "")
            val ok = (rc == 1)
            modeUsable[InputMode.PINYIN_26] = ok
            modeUsable[InputMode.PINYIN_9] = ok
            Log.i(TAG, "probe: PINYIN (native 0) -> $rc")
        } catch (t: Throwable) {
            Log.e(TAG, "probe: PINYIN failed", t)
            modeUsable[InputMode.PINYIN_26] = false
            modeUsable[InputMode.PINYIN_9] = false
        }

        // 探测五笔（native 2）：文件是否落在 filesDir，且引擎能否成功装载
        val wubiFile = File(filesDir, "$DATA_DIR_NAME/$WUBI_TSV_NAME")
        val wubiFileExists = wubiFile.isFile
        Log.i(TAG, "probe: wubi86.tsv exists=$wubiFileExists (${if (wubiFileExists) wubiFile.length() else 0} bytes)")
        var wubiOk = false
        if (wubiFileExists) {
            try {
                // 试切五笔，成功即回滚回全拼（探测不应改变用户当前方式）
                val rc = QingjianNative.nativeSetMode(InputMode.WUBI_26.nativeCode, "")
                wubiOk = (rc == 1)
                Log.i(TAG, "probe: WUBI (native 2) -> $rc (rollback to PINYIN)")
                QingjianNative.nativeSetMode(InputMode.PINYIN_26.nativeCode, "")
            } catch (t: Throwable) {
                Log.e(TAG, "probe: WUBI failed", t)
            }
        } else {
            Log.w(TAG, "probe: wubi86.tsv not extracted yet; WUBI will be disabled")
        }
        modeUsable[InputMode.WUBI_26] = wubiOk
        modeUsable[InputMode.WUBI_9] = wubiOk

        // 探测双拼（native 1）：键位表内置引擎，装配路径与全拼同源；成功即视为可用
        try {
            val rc = QingjianNative.nativeSetMode(InputMode.SHUANGPIN.nativeCode, shuangpinScheme)
            modeUsable[InputMode.SHUANGPIN] = (rc == 1)
            Log.i(TAG, "probe: SHUANGPIN (native 1) -> $rc (rollback to PINYIN)")
            QingjianNative.nativeSetMode(InputMode.PINYIN_26.nativeCode, "")
        } catch (t: Throwable) {
            Log.e(TAG, "probe: SHUANGPIN failed", t)
            modeUsable[InputMode.SHUANGPIN] = false
        }

        // 英文由 Kotlin 直输，恒可用
        modeUsable[InputMode.ENGLISH_26] = true
        modeUsable[InputMode.ENGLISH_9] = true
        Log.i(TAG, "probe: availability = ${modeUsable.map { "${it.key.id}=${it.value}" }}")
    }

    // =========================================================================
    // 键盘装配与按键分发
    // =========================================================================

    /** 递归遍历视图树，给所有带 tag 的 Button 挂统一监听（tag 即键语义，见 onKey） */
    private fun wireKeys(root: View) {
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) wireKeys(root.getChildAt(i))
        }
        if (root is Button) {
            val tag = root.tag as? String ?: return
            if (tag == "special:mode") modeButtons.add(root)
            // 切换键由 wireSwitchButton 单独处理（单击弹面板），这里跳过避免覆盖监听
            if (tag == "special:switchmode") return
            // ★ v0.8 光标入口由 wireCursorEntryButton 单独处理（单击/长按分流 OnTouchListener），
            //   这里必须跳过，否则会被本方法的 setOnClickListener 覆盖掉手势逻辑
            if (tag == "special:cursor") return
            // ★ v1.3 删除键由 wireBackspaceGesture 单独处理（按住连续删除 / 上滑清空，OnTouchListener），
            //   这里必须跳过，否则会被本方法的 setOnClickListener 覆盖掉手势逻辑
            //   （同 v0.8 special:cursor 的处理）。三个键盘面板同名 tag 递归遍历，跳过一次即全部生效。
            if (tag == "special:backspace") return
            // ★ v1.11 语音入口由 wireVoiceButton 单独处理（按住说话，OnTouchListener），
            //   这里必须跳过，否则会被本方法的 setOnClickListener 覆盖掉触摸逻辑
            //   （同 v0.8 special:cursor 的处理）。
            if (tag == "special:voice") return
            root.setOnClickListener { v ->
                v.playSoundEffect(SoundEffectConstants.CLICK)
                onKey(tag)
            }
        }
    }

    /**
     * 软键盘按键分发中枢。
     *
     * ★ v0.8：**光标模式下输入字符 → 自动退出光标模式**（软键盘路径）。
     *   —— 与 [onKeyDown] 的物理键盘路径**两处都埋**（需求明确要求）。
     *   判定放在**字符分支之前**，且只针对「真正输入字符」的 tag
     *   （letter/digit/punct/t9/space/apostrophe），
     *   而 `special:*`（退格、回车、切模式、剪贴板…）与 `toolbar:*` 不算「输入字符」，不退出。
     */
    private fun onKey(tag: String) {
        if (!tag.startsWith("t9:")) resetT9()
        // ★ v0.8：字符输入 → 退出光标模式（先退面板，再照常输入该字符）
        if (isCursorPanelShown() && isCharInputTag(tag)) {
            Log.i(TAG, "onKey: char input '$tag' while cursor panel shown -> exit cursor mode")
            hideCursorPanel()
        }
        when {
            tag.startsWith("letter:") -> onLetter(tag[7])
            tag.startsWith("digit:") -> onDigitKey(tag[6])
            tag.startsWith("punct:") -> onPunct(tag.substring(6))
            tag.startsWith("t9:") -> onT9(tag.substring(3).toInt())
            tag == "special:space" -> onSpace()
            tag == "special:backspace" -> onBackspace()
            tag == "special:enter" -> onEnter()
            tag == "special:mode" -> cycleMode()
            tag == "special:switchmode" -> onSwitchModeClick()
            tag == "special:clipboard" -> onClipboardClick()
            // ★ v0.9 收起键盘（工具栏最右「⌄」）：清缓冲 + requestHideSelf(0)
            tag == "special:hidekb" -> onHideKeyboardClick()
            // ★ v1.9 语音输入（工具栏「🎤」）：v1.11 起改为「按住说话」，
            //   由 wireVoiceButton 的 OnTouchListener 接管（DOWN 开始录 / UP 上屏）。
            //   本 click 分发路径已死（wireKeys 跳过 special:voice），故移除该分支，不留死代码。
            //   若未来误触到此处（如布局 tag 回退），走 unhandled 日志暴露，不静默。
            tag == "special:cursor" -> Log.d(TAG, "special:cursor handled by OnTouchListener (unreachable here)")
            tag == "toolbar:placeholder" -> Log.d(TAG, "toolbar placeholder tapped (inactive)")
            tag == "special:sympanel" -> showPanel(Panel.SYMBOLS)
            tag == "special:qwpanel" -> showPanel(Panel.QWERTY)
            tag == "special:t9panel" -> showPanel(Panel.T9)
            tag == "special:apostrophe" -> onApostrophe()
            else -> Log.w(TAG, "unhandled key tag: $tag")
        }
    }

    /**
     * 该 tag 是否属于「输入一个字符」（用于光标模式自动退出）。
     * 白名单式：字母 / 数字 / 标点 / 9键 / 空格 / 分词符。
     * `special:backspace`、`special:enter`、`special:*panel/inmode/clipboard` 等
     * **操作类**按键不算「输入字符」，不触发退出（避免用户按退格就掉出光标模式）。
     */
    private fun isCharInputTag(tag: String): Boolean = when {
        tag.startsWith("letter:") -> true
        tag.startsWith("digit:") -> true
        tag.startsWith("punct:") -> true
        tag.startsWith("t9:") -> true
        tag == "special:space" -> true
        tag == "special:apostrophe" -> true
        else -> false
    }

    private fun showPanel(p: Panel) {
        panel = p
        if (!::panelQwerty.isInitialized) return  // onStartInput 可能先于 onCreateInputView
        panelQwerty.visibility = if (p == Panel.QWERTY) View.VISIBLE else View.GONE
        panelT9.visibility = if (p == Panel.T9) View.VISIBLE else View.GONE
        panelSymbols.visibility = if (p == Panel.SYMBOLS) View.VISIBLE else View.GONE
    }

    /**
     * 旧版保留键 special:mode —— 与切换键一致（单击弹面板）。
     * 保留它只为不破坏既有布局（回归红线）；新交互走 special:switchmode。
     */
    private fun cycleMode() {
        Log.d(TAG, "special:mode (legacy) -> delegate to switch key click")
        onSwitchModeClick()
    }

    /** 更新工具栏切换键标签，反映当前输入方式。 */
    private fun updateModeButtons() {
        val label = when (inputMode) {
            InputMode.PINYIN_26 -> "中/EN"
            InputMode.PINYIN_9 -> "9键"
            InputMode.SHUANGPIN -> "双拼"
            InputMode.WUBI_26 -> "五笔"
            InputMode.WUBI_9 -> "五9"
            InputMode.ENGLISH_26 -> "EN"
            InputMode.ENGLISH_9 -> "EN9"
            else -> "中/EN"
        }
        switchModeButton?.text = label
        modeButtons.forEach { it.text = label }
        Log.d(TAG, "updateModeButtons: label=$label (mode=${inputMode.label})")
    }

    // =========================================================================
    // 键规则实现
    // =========================================================================

    /** 字母键：引擎模式（拼音/双拼/五笔）追加缓冲并刷新候选；英文模式直输 */
    private fun onLetter(c: Char) {
        if (inputMode.usesEngine) {
            if (composing.isEmpty()) composingStart = curSelEnd
            composing.append(c)
            refreshCandidates()
        } else {
            commitDirect(c.toString())
        }
    }

    /** 空格：有候选提交选中候选，无候选提交原串；无缓冲时输出空格 */
    private fun onSpace() {
        if (composing.isNotEmpty()) commitRule(null) else commitDirect(" ")
    }

    /** 数字键（符号面板）：有缓冲时按规则提交候选/原串后跟数字；否则直输数字 */
    private fun onDigitKey(d: Char) {
        if (composing.isNotEmpty()) commitRule(d.toString()) else commitDirect(d.toString())
    }

    /** 标点：同空格规则，提交后跟上标点本身 */
    private fun onPunct(p: String) {
        if (composing.isNotEmpty()) commitRule(p) else commitDirect(p)
    }

    /** 分词符：有缓冲时追加 ' 进拼音串（如 ni'hao）；否则直输 */
    private fun onApostrophe() {
        if (composing.isNotEmpty()) {
            composing.append('\'')
            refreshCandidates()
        } else {
            commitDirect("'")
        }
    }

    /** 回车：有缓冲时提交拼音原串（逐字母上屏）；否则发送 Enter 键事件 */
    private fun onEnter() {
        if (composing.isNotEmpty()) {
            commitRawComposing()
            return
        }
        val ic = currentInputConnection ?: return
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
    }

    private fun onBackspace() {
        val ic = currentInputConnection ?: return
        if (composing.isNotEmpty()) {
            val before = composing.toString()
            composing.deleteCharAt(composing.length - 1)
            Log.d(TAG, "onBackspace: composing='$before' -> '${composing}'")
            if (composing.isEmpty()) {
                // ★ v0.3 修复：删掉最后一个字母 → 必须结束编辑框里的 composition，
                // 否则残留的合成串处于 composing 状态，后续 deleteSurroundingText 不生效。
                // 先取 composingStart（resetComposing 会把它置 -1），用于校正 expectedCursor。
                val start = composingStart
                resetComposing()  // 内含 finishComposingText，结束编辑框 composition
                expectedCursor = start.coerceAtLeast(0)
                curSelStart = expectedCursor
                curSelEnd = expectedCursor
            } else {
                refreshCandidates()
            }
        } else {
            ic.deleteSurroundingText(1, 0)
            if (curSelEnd > 0) {
                curSelEnd--
                curSelStart = curSelEnd
            }
            expectedCursor = curSelEnd
        }
    }

    // =========================================================================
    // ★ v1.3 删除键手势（按住连续删除 + 上滑清空）
    //
    //   需求：按住删除键 500ms 后每 80ms 连删一次；按住后上滑超 50dp → 清空模式（气泡「松开清空」），
    //         抬手执行「清空光标之前所有内容」并清引擎拼音缓冲。
    //   架构：手势判定封装在 [BackspaceGestureHandler]（Handler + Runnable + 状态机），
    //         本服务只实现其 Listener 的**业务回调**（删除 / 清空 / 气泡显隐）。
    //   ★ 硬纪律：用 OnTouchListener + Handler 自计时，**绝不**用 setOnLongClickListener
    //     （OnTouchListener 返回 true 会跳过 View 长按链路，使其成为死代码——v0.8 实证）。
    //   ★ 三个键盘面板共用同一 tag(`special:backspace`)，wireKeys 跳过后由本方法统一装配，
    //     故全拼 / 符号 / 9 键三个面板**一次装配、全部生效**。
    // =========================================================================

    /**
     * 装配删除键手势：遍历所有 `special:backspace` 按钮并挂 [BackspaceGestureHandler]。
     *
     * 为何要「遍历所有」而不只取一个：三个键盘面板（qwerty / symbols / t9）各有一枚删除键，
     * 它们是**不同的 View 实例**（各自 include），必须逐一 attach，才能三面板全生效。
     */
    private fun wireBackspaceGesture(root: View) {
        val targets = ArrayList<View>()
        collectBackspaceKeys(root, targets)
        if (targets.isEmpty()) {
            Log.e(TAG, "backspace gesture: no special:backspace button found! gesture disabled")
            return
        }
        val handler = BackspaceGestureHandler(object : BackspaceGestureHandler.Listener {
            override fun onSingleDelete() {
                Log.i(TAG, "backspace gesture: single delete")
                onBackspace()
            }

            override fun onContinuousDelete(): Boolean {
                val ic = currentInputConnection
                if (ic == null) {
                    // 无 InputConnection：安全返回 false → 手势层停止循环，不崩
                    Log.w(TAG, "backspace gesture: continuous delete aborted — no input connection")
                    return false
                }
                // 光标在 0（且无拼音缓冲）→ 无权可删 → 停止循环（边界拦截，不报错）
                if (composing.isEmpty() && curSelEnd <= 0) {
                    Log.i(
                        TAG,
                        "backspace gesture: CONTINUOUS DELETE boundary — cursor at 0 " +
                            "(curSelEnd=$curSelEnd); stop loop"
                    )
                    return false
                }
                // ★ 直接复用 onBackspace()：它已含「拼音缓冲优先清缓冲 + deleteSurroundingText +
                //   光标状态同步」全部逻辑；连续删除只是「循环调用」它，绝不另写删除逻辑。
                onBackspace()
                return true
            }

            override fun onClearBeforeCursor(charsSinceDeleteStart: Int) {
                Log.i(
                    TAG,
                    "执行清空操作 | backspace gesture: CLEAR request (alreadyDeletedThisPress=$charsSinceDeleteStart)"
                )
                clearBeforeCursor()
            }

            override fun onClearModeChanged(active: Boolean) {
                backspaceClearBubble?.visibility = if (active) View.VISIBLE else View.GONE
                Log.i(TAG, "backspace gesture: clear-mode bubble ${if (active) "SHOWN" else "HIDDEN"}")
            }
        })
        backspaceGesture = handler
        for (v in targets) handler.attach(v)
        Log.i(
            TAG,
            "wireBackspaceGesture: ready (attached=${targets.size} backspace keys, " +
                "bubble=${backspaceClearBubble != null}, " +
                "longPress=${BackspaceGestureHandler.LONG_PRESS_DELAY_MS}ms, " +
                "interval=${BackspaceGestureHandler.REPEAT_INTERVAL_MS}ms, " +
                "swipeUp=${BackspaceGestureHandler.SWIPE_UP_THRESHOLD_DP}dp)"
        )
    }

    /** 递归收集所有 tag == "special:backspace" 的 Button（三个面板各一枚）。 */
    private fun collectBackspaceKeys(root: View, out: MutableList<View>) {
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) collectBackspaceKeys(root.getChildAt(i), out)
        }
        if (root is Button && (root.tag as? String) == "special:backspace") {
            out.add(root)
        }
    }

    /**
     * 清空「光标之前的**所有**内容」+ 清引擎拼音缓冲。
     *
     * 取光标绝对位置：**必须**用 `getExtractedText()`（`InputConnection` **没有**
     * `getSelectionStart()`——那是 `android.text.Selection` 的静态方法；v0.8 已实证），
     * 且 **必须** 叠加 `startOffset` 换算为与 `deleteSurroundingText` 一致的绝对索引。
     *
     * 纪律：
     *   1. 有拼音缓冲 → 先 [resetComposing]（内含 finishComposingText + nativeClear）清缓冲与状态；
     *   2. 删除用 `deleteSurroundingText(pos, 0)`（pos = 光标绝对位置，**不是**
     *      `getTextBeforeCursor().length`——后者有读取上限、长文本下静默算错）；
     *   3. 光标已在 0 → 边界拦截，不做删除、不报错（仅日志）。
     */
    private fun clearBeforeCursor() {
        val ic = currentInputConnection ?: run {
            Log.w(TAG, "clearBeforeCursor: no input connection; ABORT")
            return
        }
        // 1) 有拼音缓冲 → 先清缓冲（直接删文字会导致引擎状态错乱）
        if (composing.isNotEmpty()) {
            Log.i(TAG, "clearBeforeCursor: composing non-empty -> resetComposing() first")
            resetComposing()
        }
        // 2) 取光标绝对位置（getExtractedText + startOffset）
        val ext = runCatching {
            ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
        }.getOrNull()
        val selEnd = ext?.let { it.selectionEnd + it.startOffset } ?: -1
        if (ext == null || selEnd < 0) {
            Log.w(TAG, "clearBeforeCursor: selection unknown (selEnd=$selEnd); ABORT (no error)")
            return
        }
        // 3) 光标已在 0 → 边界拦截
        if (selEnd == 0) {
            Log.i(TAG, "clearBeforeCursor: cursor already at 0; nothing to clear (boundary blocked)")
            return
        }
        // 4) 清空：删除光标之前的全部内容
        try {
            ic.deleteSurroundingText(selEnd, 0)
            curSelStart = 0
            curSelEnd = 0
            expectedCursor = 0
            Log.i(
                TAG,
                "clearBeforeCursor: cleared $selEnd chars before cursor " +
                    "(deleteSurroundingText($selEnd, 0))"
            )
        } catch (t: Throwable) {
            Log.e(TAG, "clearBeforeCursor: deleteSurroundingText($selEnd, 0) failed", t)
        }
    }

    /** 9键：引擎模式 multi-tap 轮转字母进缓冲；英文模式 multi-tap 直输；0=空格规则；1=标点 */
    private fun onT9(digit: Int) {
        val now = SystemClock.uptimeMillis()
        val sameBurst = digit == t9KeyId && (now - t9LastTime) < T9_TIMEOUT_MS
        when {
            digit == 0 -> {
                resetT9()
                onSpace()
            }
            digit == 1 -> onT9Punct(now, sameBurst)
            inputMode.usesEngine -> {
                val letters = T9_MAP[digit] ?: return
                if (sameBurst && composing.isNotEmpty()) {
                    t9Cycle = (t9Cycle + 1) % letters.length
                    composing[composing.length - 1] = letters[t9Cycle]
                } else {
                    if (composing.isEmpty()) composingStart = curSelEnd
                    t9Cycle = 0
                    composing.append(letters[0])
                }
                t9KeyId = digit
                t9LastTime = now
                refreshCandidates()
            }
            else -> {
                // 英文模式：multi-tap 轮转直输小写字母
                val letters = T9_MAP[digit] ?: return
                val ic = currentInputConnection ?: return
                if (sameBurst) {
                    t9Cycle = (t9Cycle + 1) % letters.length
                    ic.deleteSurroundingText(1, 0)
                    commitDirect(letters[t9Cycle].toString())
                } else {
                    t9Cycle = 0
                    commitDirect(letters[0].toString())
                }
                t9KeyId = digit
                t9LastTime = now
            }
        }
    }

    /** 9键的 1 键：有缓冲时按规则提交并补「，」；否则 multi-tap 轮转常用标点 */
    private fun onT9Punct(now: Long, sameBurst: Boolean) {
        if (composing.isNotEmpty()) {
            resetT9()
            commitRule(T9_PUNCT[0].toString())
            return
        }
        val ic = currentInputConnection ?: return
        if (sameBurst) {
            t9Cycle = (t9Cycle + 1) % T9_PUNCT.length
            ic.deleteSurroundingText(1, 0)
            commitDirect(T9_PUNCT[t9Cycle].toString())
        } else {
            t9Cycle = 0
            commitDirect(T9_PUNCT[0].toString())
        }
        t9KeyId = 1
        t9LastTime = now
    }

    private fun resetT9() {
        t9KeyId = -1
        t9Cycle = 0
    }

    // =========================================================================
    // 候选与提交
    // =========================================================================

    /**
     * 字母入缓冲后刷新候选：调 nativePinyinInput 并更新候选栏。
     * 双态容器：缓冲非空 → 切到状态 B（候选栏）。
     */
    private fun refreshCandidates() {
        Log.d(TAG, "refreshCandidates: enter, composing='${composing}'")
        val ic = currentInputConnection
        if (ic == null) {
            Log.w(TAG, "refreshCandidates: no input connection; skip")
            return
        }
        if (composing.isEmpty()) {
            resetComposing()
            return
        }
        ic.setComposingText(composing, 1)
        expectedCursor = composingStart + composing.length
        candidates = if (nativeReady && inputMode.usesEngine) {
            val json = QingjianNative.nativePinyinInput(composing.toString())
            Log.i(TAG, "refreshCandidates: nativePinyinInput('${composing}') -> ${json?.length ?: 0} chars")
            Candidate.parseArray(json)
        } else {
            Log.w(TAG, "refreshCandidates: engine mode unavailable; no candidates")
            emptyList()
        }
        selectedIndex = 0  // 引擎已排序，首候选即 score 最高项
        candidatesView?.setData(candidates, selectedIndex, composing.toString())
        showCandidates()  // ★ 双态容器 → 状态 B
        Log.i(
            TAG,
            "refreshCandidates: exit, candidates=${candidates.size}, " +
                "stateB=${candidatesView?.visibility == View.VISIBLE}"
        )
    }

    /** 点选候选：nativeSelect（触发学习）→ 上屏 → nativeClear */
    private fun commitCandidate(index: Int) {
        val c = candidates.getOrNull(index) ?: return
        if (nativeReady) {
            val rc = QingjianNative.nativeSelect(index)
            Log.i(TAG, "commitCandidate: nativeSelect($index) -> $rc, word='${c.word}'")
        }
        commitReplacingComposing(c.word)
        resetComposing()
    }

    /** 空格/数字/标点规则：有候选 nativeSelect 提交+nativeClear；无候选提交原串；随后补 trailing */
    private fun commitRule(trailing: String?) {
        if (composing.isNotEmpty()) {
            val pick = candidates.getOrNull(selectedIndex)
            if (pick != null) {
                if (nativeReady) {
                    val rc = QingjianNative.nativeSelect(selectedIndex)
                    Log.i(TAG, "commitRule: nativeSelect($selectedIndex) -> $rc, word='${pick.word}'")
                }
                commitReplacingComposing(pick.word)
            } else {
                Log.d(TAG, "commitRule: no candidate; commit raw composing '${composing}'")
                commitReplacingComposing(composing.toString())
            }
            resetComposing()
        }
        if (!trailing.isNullOrEmpty()) commitDirect(trailing)
    }

    private fun commitRawComposing() {
        if (composing.isEmpty()) return
        commitReplacingComposing(composing.toString())
        resetComposing()
    }

    /** 提交并替换编辑框中的 composing 区域（光标落点 = composingStart + 新文本长度） */
    private fun commitReplacingComposing(text: String) {
        val ic = currentInputConnection ?: return
        ic.commitText(text, 1)
        expectedCursor = composingStart + text.length
        curSelStart = expectedCursor
        curSelEnd = expectedCursor
    }

    /** 无 composing 时的直输（光标按文本长度前移；onUpdateSelection 会再校准） */
    private fun commitDirect(text: String) {
        val ic = currentInputConnection ?: return
        ic.commitText(text, 1)
        curSelEnd += text.length
        curSelStart = curSelEnd
        expectedCursor = curSelEnd
    }

    /**
     * 清空拼音缓冲与候选栏数据，并把双态容器切回状态 A（工具栏）。
     * ★ v0.3 修复：先结束编辑框里的 composition（finishComposingText），再清 Kotlin 状态。
     * ★ v0.4：缓冲清空 → 双态容器回到「状态 A 工具栏」。
     * ★ v0.6：切换输入方式前调用本方法清缓冲（保留 finishComposingText 语义，不得回退）。
     */
    private fun resetComposing() {
        currentInputConnection?.let { ic ->
            ic.finishComposingText()
            Log.d(TAG, "resetComposing: finishComposingText issued")
        }
        composing.clear()
        composingStart = -1
        candidates = emptyList()
        selectedIndex = 0
        if (nativeReady) QingjianNative.nativeClear()
        candidatesView?.setData(emptyList(), 0, "")
        showToolbar()  // ★ 双态容器 → 状态 A
        Log.d(TAG, "resetComposing: cleared (nativeClear issued=${nativeReady})")
    }

    // =========================================================================
    // 长按候选查翻译（nativeTranslate）
    // =========================================================================

    private fun showTranslation(index: Int) {
        val c = candidates.getOrNull(index) ?: return
        Log.i(TAG, "showTranslation: index=$index, word='${c.word}'")
        if (!nativeReady) {
            toast("引擎未就绪")
            return
        }
        val msg = try {
            val raw = QingjianNative.nativeTranslate(c.word)
            Log.d(TAG, "showTranslation: nativeTranslate('${c.word}') raw=${raw?.length ?: 0} chars")
            val o = JSONObject(raw)
            buildString {
                append(c.word)
                for (key in listOf("en", "zh")) {
                    val arr = o.optJSONArray(key) ?: continue
                    if (arr.length() == 0) continue
                    append('\n').append(if (key == "en") "英: " else "中: ")
                    val parts = (0 until minOf(arr.length(), 3)).map { i ->
                        val e = arr.getJSONObject(i)
                        listOf(e.optString("pos"), e.optString("gloss"))
                            .filter { it.isNotEmpty() }
                            .joinToString(" ")
                    }
                    append(parts.joinToString("; "))
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "showTranslation: translate failed", t)
            "翻译查询失败"
        }
        toast(msg)
    }

    // =========================================================================
    // ★ v1.9 语音输入（VoiceInputPanelView + 纯离线 sherpa-onnx / SenseVoice-Small）
    //
    //   入口：工具栏「🎤」（tag=special:voice）→ ★ v1.11 由 [wireVoiceButton] 的
    //         OnTouchListener 接管「按住说话」（DOWN=[onVoicePressDown] / UP=[onVoiceRelease]）
    //   替换范围（关键）：**只替换工具栏那一条**——
    //     激活：voiceInputHost VISIBLE + candidatesContainer GONE
    //     退出：voiceInputHost GONE    + candidatesContainer VISIBLE（内部双态照旧）
    //   ★ 键盘字母区 panelHost 全程不动、可见可点；★ 不改任何既有布局尺寸、不碰候选栏内部。
    //
    //   技术路线（v1.9 变更）：**纯离线**。sherpa-onnx + SenseVoice-Small（int8）+ Silero VAD，
    //     native 由 `libsherpa-onnx-jni.so` 承载（JNI 类见 `com.k2fsa.sherpa.onnx`）。
    //     **不引入任何第三方语音 SDK**，`dependencies {}` 保持为空。
    //     旧版在线 `android.speech.SpeechRecognizer` 已退役于 [LegacyOnlineRecognizer]（隔离）。
    //
    //   引擎：[VoiceRecognizer]（模型子线程加载 + 采集独立线程 + VAD 断句 + 识别）
    //   线程：引擎回调**全部在主线程**（内部 post），故本服务字段仅主线程读写。
    // =========================================================================

    /**
     * 装配语音状态栏：定位 [VoiceInputPanelView]（contentColumn 内、与候选栏平级），
     * 注入 [VoiceInputPanelView.Listener]，所有回调转译为服务层动作。
     *
     * 幂等：找不到视图时仅记日志，不阻塞键盘可用。
     */
    private fun wireVoicePanel() {
        val host = keyboardRoot.findViewById<FrameLayout>(R.id.voiceInputHost)
        if (host == null) {
            Log.e(TAG, "wireVoicePanel: voiceInputHost not found! voice feature disabled")
            return
        }
        voiceInputHost = host

        val panelView = VoiceInputPanelView(this)
        host.addView(
            panelView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
        voicePanel = panelView

        panelView.listener = object : VoiceInputPanelView.Listener {
            override fun onStopRequested() {
                Log.i(TAG, "voice listener: onStopRequested -> stop listening (result will be committed)")
                stopVoiceListening()
            }

            override fun onCancelRequested() {
                // ★ v1.10：加载/识别/错误态下点击波纹+文字 → 「放弃本次」而非「结束识别」。
                //   加载态无采集可停；识别态正在推理；错误态本就在等 3 秒收面板。统一走 cancel 更干净。
                Log.i(TAG, "voice listener: onCancelRequested -> cancel voice input (no commit)")
                cancelVoiceInput("panel tap (non-listening state)")
            }

            override fun onMoreRequested() {
                Log.i(TAG, "voice listener: onMoreRequested -> placeholder toast")
                toast(getString(R.string.voice_more_placeholder))
            }
        }

        Log.i(
            TAG,
            "wireVoicePanel: ready (host=${host.id}, panel=VoiceInputPanelView, " +
                "initialVisibility=${host.visibility == View.GONE})"
        )
    }

    /** 语音状态栏是否已显示（宿主容器可见）。 */
    private fun isVoicePanelShown(): Boolean = voiceInputHost?.visibility == View.VISIBLE

    /**
     * 显示语音状态栏（**只替换工具栏那一条**）：
     *   1. 与其它三个面板**互斥**：先收起输入方式面板 / 剪贴板面板 / 光标面板；
     *   2. `voiceInputHost` VISIBLE + `bringToFront()`；
     *   3. `candidatesContainer` GONE —— 即「原工具栏所在的一条被替换掉」；
     *   4. **不动** `panelHost`（键盘字母区）：全程可见可点。
     *
     * ★ v1.10：新增 [initialState] 参数解决时序问题。旧版在此**无条件** `panel.startListening()`，
     *   导致「首次使用（模型未就绪）」路径也会先闪一下「倾听中」再切加载态。现在由调用方
     *   显式声明初始态：
     *     · 模型已就绪 → [VoiceInputPanelView.State.LISTENING]（等价旧行为）
     *     · 模型未就绪 → [VoiceInputPanelView.State.LOADING]（先显示加载态，不进倾听态）
     *   默认 LISTENING 以保持既有调用点行为不变。
     */
    private fun showVoicePanel(
        initialState: VoiceInputPanelView.State = VoiceInputPanelView.State.LISTENING
    ) {
        val host = voiceInputHost
        val panel = voicePanel
        if (host == null || panel == null) {
            Log.e(TAG, "showVoicePanel: host/panel null; cannot show")
            toast("语音面板不可用")
            return
        }
        // 互斥：其它三条「插槽/覆盖」同时只能存在一个
        if (isInputModePanelShown()) {
            Log.i(TAG, "showVoicePanel: input mode panel visible -> hide it first")
            hideInputModePanel()
        }
        if (isClipboardPanelShown()) {
            Log.i(TAG, "showVoicePanel: clipboard panel visible -> hide it first")
            hideClipboardPanel()
        }
        if (isCursorPanelShown()) {
            Log.i(TAG, "showVoicePanel: cursor panel visible -> hide it first")
            hideCursorPanel()
        }

        host.visibility = View.VISIBLE
        host.bringToFront()
        // ★ 只隐藏候选栏容器（原工具栏那一条），键盘字母区完全不动
        candidatesContainer?.visibility = View.GONE
        // 按调用方声明的初始态渲染（LOADING 时显示「正在准备离线模型 0%」）
        when (initialState) {
            VoiceInputPanelView.State.LOADING -> panel.showLoading(VoiceInputPanelView.PERCENT_UNKNOWN)
            VoiceInputPanelView.State.LISTENING -> panel.startListening()
            else -> panel.startListening() // 其它态不会作为初始态，防御性回落到倾听态
        }

        Log.i(
            TAG,
            "voice panel shown: initialState=$initialState, initial=${panel.getState()}, " +
                "host=${host.visibility == View.VISIBLE}, " +
                "candidatesContainer=${candidatesContainer?.visibility == View.GONE} (replaced), " +
                "keyboardPanelHostUnchanged=${::panelQwerty.isInitialized}"
        )
    }

    /**
     * 收起语音状态栏（幂等）：回到「工具栏那一条」。
     *   1. **先撤销**可能存在的「3 秒延迟收面板」Runnable —— 防自锁/防诈尸（幂等，安全）；
     *   2. 面板内先 `onPanelHidden()` —— **确保动画立即停止 + UI 复位 IDLE**（防泄漏 / 防残留下次）；
     *   3. `voiceInputHost` GONE；
     *   4. `candidatesContainer` 恢复 VISIBLE —— 其内部 toolbar/candidates 双态
     *      由既有的 [showToolbar] / [showCandidates] 决定，本方法**不做**任何干预。
     */
    private fun hideVoicePanel() {
        // ★ v1.10：无条件撤销延迟隐藏。voicePendingHide 是「延迟调用 hideVoicePanel」的 Runnable，
        //   在此 removeCallbacks 是幂等的（未排队时为 no-op），不会自锁：
        //   即使本次正是它触发的，其 run() 已在执行，remove 不影响当前帧（只清未来重投）。
        voicePendingHide?.let {
            voiceUiHandler.removeCallbacks(it)
            voicePendingHide = null
        }
        val host = voiceInputHost ?: return
        if (host.visibility == View.GONE) return
        voicePanel?.onPanelHidden()
        host.visibility = View.GONE
        candidatesContainer?.visibility = View.VISIBLE
        Log.i(
            TAG,
            "voice panel hidden (GONE); candidatesContainer restored=" +
                "${candidatesContainer?.visibility == View.VISIBLE}, " +
                "stateA(toolbar)=${inputToolbar?.visibility == View.VISIBLE}, " +
                "stateB(candidates)=${candidatesView?.visibility == View.VISIBLE}"
        )
    }

    /**
     * ★ v1.10 延迟收起面板（错误/空结果态停留 [VOICE_ERROR_HOLD_MS] 再收）。
     *
     * 为什么这样写：延迟期间任何「放弃」路径（返回键/切框/收键盘/面板抢占）都会调
     * [cancelVoiceInput] → [hideVoicePanel]，其中会 `removeCallbacks(this)` 从而撤销本 Runnable；
     * 若本次已执行（面板已收），再排队也会因 host 已 GONE 而 no-op。故**不会**出现 3 秒后诈尸。
     * 每次调用前先清旧 Runnable，避免连续错误叠加多个延迟。
     */
    private fun scheduleHideVoicePanel() {
        val host = voiceInputHost ?: return
        if (host.visibility != View.VISIBLE) {
            Log.i(TAG, "QJ-SV-PANEL scheduleHide skipped (panel not visible)")
            return
        }
        // 先撤销可能存在的旧延迟，避免叠加
        voicePendingHide?.let { voiceUiHandler.removeCallbacks(it) }
        val r = Runnable {
            voicePendingHide = null
            Log.i(TAG, "QJ-SV-PANEL hold ${VOICE_ERROR_HOLD_MS}ms elapsed -> hide panel now")
            hideVoicePanel()
        }
        voicePendingHide = r
        voiceUiHandler.postDelayed(r, VOICE_ERROR_HOLD_MS)
        Log.i(TAG, "QJ-SV-PANEL scheduleHide -> panel will hide in ${VOICE_ERROR_HOLD_MS}ms (interruptible)")
    }

    /**
     * ★ v1.11 工具栏「🎤」装配「按住说话」（微信式）。
     *
     * 交互：按下 → 开始录音（面板显示「请说话，松开结束」）；
     *       松开 → 冲刷尾段、上屏最后一段、收面板（面板显示「识别中…」直至收口）。
     * 不保留「点按开始 / 点按结束」旧模式：快速点按 = 极短录音（大概率「未听到声音」，可接受）。
     *
     * ★ 实现方式：**纯 OnTouchListener**（与 [wireCursorEntryButton] 同纪律）。
     *   用 `setOnTouchListener` 返回 true 消费全部事件，`onClick` 自然不再触发——
     *   故 [wireKeys] 已跳过 `special:voice`、[onKey] 已移除该分支（不留死代码）。
     *
     * 事件分流：
     *   · ACTION_DOWN  → [onVoicePressDown]（置按住态 + 开引擎/采集）
     *   · ACTION_UP    → [onVoiceRelease]（松开收口）
     *   · ACTION_CANCEL → 等价「丢弃本次」（[cancelVoiceInput]）
     *   · 其它         → false（不消费，交还系统；实际不会到达 UP 之后）
     *
     * @param button 工具栏「🎤」（id=btnMic，tag=special:voice）
     */
    private fun wireVoiceButton(button: Button?) {
        val b = button ?: run {
            Log.e(TAG, "wireVoiceButton: btnMic not found (tag=special:voice)! voice hold-to-talk disabled")
            return
        }
        b.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    onVoicePressDown()
                    true
                }

                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    v.performClick()
                    onVoiceRelease()
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    // 手指滑出控件被父类拦截 / 窗口失焦等 → 等价「丢弃本次」
                    v.isPressed = false
                    Log.i(TAG, "QJ-SV-REC 🎤 ACTION_CANCEL -> treat as discard (holdActive=$voiceHoldActive)")
                    voiceHoldActive = false
                    cancelVoiceInput("touch cancelled")
                    true
                }

                else -> false
            }
        }
        Log.i(TAG, "wireVoiceButton: ready (hold-to-talk, tag=special:voice, id=${b.id})")
    }

    /**
     * ★ v1.11 🎤 **按下**：开始一次「按住说话」会话。
     *
     * 步骤：
     *  1. 防御：已处于按住态（重复 DOWN）→ 忽略；
     *  2. 防御：面板可见 / 正在采集（理论上不可能）→ 先取消残留会话；
     *  3. 权限：无 → Toast 引导 + 跳设置 + **返回**（手指虽按下但无会话；UP 时 [onVoiceRelease]
     *     因 `voiceHoldActive == false` 而 no-op）；
     *  4. 置按住态 / 清会话闸门 / 清「已上屏分句」标记；
     *  5. 显示面板（初始态按模型是否就绪：LISTENING / LOADING）；
     *  6. 就绪 → [VoiceRecognizer.start]；未就绪 → [VoiceRecognizer.prepare]（就绪后在 onReady 里起）。
     *
     * ⚠️ 模型加载在子线程（[VoiceRecognizer.prepare]），主线程不阻塞；
     *    就绪后由 [voiceCallback.onReady] 在**仍按住**时自动开始采集。
     */
    private fun onVoicePressDown() {
        try {
            Log.i(
                TAG,
                "QJ-SV-REC >>> 🎤 ACTION_DOWN (holdActive=$voiceHoldActive, " +
                    "panelShown=${isVoicePanelShown()}, modelReady=$voiceModelReady, " +
                    "capturing=${voiceEngine?.isCapturing() == true})"
            )
            // 1) 防御：已在按住态 → 忽略重复 DOWN
            if (voiceHoldActive) {
                Log.w(TAG, "QJ-SV-REC ACTION_DOWN ignored: hold already active")
                return
            }
            // 2) 防御：残留会话（面板可见 / 正在采集）→ 先取消，保证干净开局
            if (isVoicePanelShown() || voiceEngine?.isCapturing() == true) {
                Log.i(TAG, "QJ-SV-REC stale session on DOWN -> cancel before new hold")
                cancelVoiceInput("stale session on press down")
            }
            // 3) 权限（运行时权限，API23+ 统一走 checkSelfPermission）
            if (!hasAudioPermission()) {
                Log.w(TAG, "QJ-SV-REC RECORD_AUDIO permission denied -> guide user to system settings")
                toast(getString(R.string.voice_need_audio_permission))
                openAppSettingsForPermission()
                return
            }
            // 4) 置会话状态：按住态开、会话闸门清、分句标记清
            voiceHoldActive = true
            voiceSessionFinished = false
            voiceCommittedAny = false

            // 5) 引擎 + 面板初始态
            val engine = ensureVoiceEngine()
            if (engine == null) {
                Log.e(TAG, "QJ-SV-MODEL engine unavailable (native lib/corrupt) -> abort")
                toast(getString(R.string.voice_offline_unavailable))
                voiceHoldActive = false
                showVoicePanel(VoiceInputPanelView.State.LOADING)
                hideVoicePanel()
                return
            }
            if (engine.isReady()) {
                // 模型已就绪 → 面板进倾听态并立刻采集
                Log.i(TAG, "QJ-SV-MODEL model already ready -> show LISTENING + start capture immediately")
                voiceModelReady = true
                showVoicePanel(VoiceInputPanelView.State.LISTENING)
                engine.start(voiceCallback)
            } else {
                // 首次使用：面板进加载态（显示百分比），触发子线程准备；就绪后 onReady 里（若仍按住）自动起
                Log.i(TAG, "QJ-SV-MODEL first use -> show LOADING, prepare (background), auto-start on ready")
                showVoicePanel(VoiceInputPanelView.State.LOADING)
                if (!voicePrepareTriggered) {
                    voicePrepareTriggered = true
                    toast(getString(R.string.voice_model_preparing))
                }
                engine.prepare(voiceCallback)
            }
        } catch (t: Throwable) {
            // 任何未预期异常都必须「可见」：日志 + Toast，绝不静默表现为「按了没反应」
            Log.e(TAG, "QJ-SV-REC voice press-down crashed", t)
            toast("语音启动异常：" + t.message)
            voiceHoldActive = false
            hideVoicePanel()
        }
    }

    /**
     * ★ v1.11 🎤 **松开**：结束本次「按住说话」会话。
     *
     * 步骤：
     *  1. `!voiceHoldActive` → **直接返回**（关键竞态防御：被返回键/切框打断后，或按下即无权限，
     *     UP 不得「复活」会话）；
     *  2. 复位按住态；
     *  3. 模型尚未就绪（本次根本没录到音频）→ 取消 + Toast「模型准备中，请稍后再试」；
     *  4. 否则 [VoiceRecognizer.stop]（冲刷尾段）+ 面板切「识别中…」，最终由
     *     [voiceCallback.onResult] 收口（上屏尾段 + 收面板）。
     */
    private fun onVoiceRelease() {
        // 1) 关键防御：没有活动会话（已 cancel / 权限失败早退）→ UP 不复活
        if (!voiceHoldActive) {
            Log.i(TAG, "QJ-SV-REC 🎤 ACTION_UP ignored: no active hold (already cancelled/released)")
            return
        }
        // 2) 复位按住态（在任何后续分支之前）
        voiceHoldActive = false

        // 3) 模型还没就绪 → 本次没录到任何音频 → 明确告知并取消
        if (!voiceModelReady) {
            Log.i(TAG, "QJ-SV-REC ACTION_UP while model NOT ready -> cancel + toast (no audio captured)")
            toast(getString(R.string.voice_hold_model_preparing))
            cancelVoiceInput("hold released before model ready")
            return
        }

        // 4) 正常收口：冲刷尾段 + 面板切识别中（尾段解码完由 onResult 收面板）
        Log.i(TAG, "QJ-SV-REC ACTION_UP -> engine.stop() (flush tail) + panel.showDecoding")
        voiceEngine?.stop()
        voicePanel?.showDecoding()
    }

    /**
     * 懒创建离线引擎单例（主线程）。
     * @return 可用引擎；native 库未加载返回 null
     */
    private fun ensureVoiceEngine(): VoiceRecognizer? {
        voiceEngine?.let { return it }
        // native 库加载失败探测：直接尝试构造（构造内 System.loadLibrary 会抛 UnsatisfiedLinkError）
        return try {
            VoiceRecognizer(this).also {
                voiceEngine = it
                LegacyOnlineRecognizer.logRetired()
                Log.i(TAG, "QJ-SV-MODEL engine instance created (offline)")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "QJ-SV-MODEL cannot create engine (native lib load failed?)", t)
            null
        }
    }

    /**
     * 引擎回调（**全部在主线程**，由 [VoiceRecognizer] 保证）。
     *   · [onProgress]      → v1.10：模型拷贝进度 → 面板刷新加载百分比；
     *   · [onReady]         → 模型就绪：若面板仍在**且仍按住**则切倾听态 + 开始采集（v1.11 竞态防御）；
     *   · [onDecoding]      → v1.10/v1.11：**收口段**开始 ASR 推理 → 面板切「识别中…」；
     *   · [onSegmentResult] → v1.11：**中间分句**（按住期间）→ 立即上屏，会话继续、不收面板；
     *   · [onResult]        → 会话终结：尾段非空上屏 + 立刻收面板；空且已有分句上屏 → 静默收面板；
     *                         空且无任何分句 → Toast + 错误态 + 停留 3 秒收面板；
     *   · [onError]         → Toast + 错误态 + 停留 3 秒收面板；
     *   · [onModelError]    → 模型损坏/IO 失败：Toast（文案由引擎给）+ 错误态 + 停留 3 秒收面板。
     * 所有**终结**回调（onResult/onError/onModelError）均复位 [voiceHoldActive]（会话已终结）。
     */
    private val voiceCallback = object : VoiceRecognizer.Callback {
        override fun onProgress(percent: Int) {
            // 仅当面板仍显示时刷新；用户已离开/已收面板则忽略（避免无意义 UI 操作）
            if (isVoicePanelShown()) {
                Log.i(TAG, "QJ-SV-MODEL progress=$percent% -> panel.showLoading")
                voicePanel?.showLoading(percent)
            } else {
                Log.d(TAG, "QJ-SV-MODEL progress=$percent% ignored (panel not shown)")
            }
        }

        override fun onReady() {
            voiceModelReady = true
            Log.i(
                TAG,
                "QJ-SV-MODEL onReady -> model ready (panelShown=${isVoicePanelShown()}, " +
                    "holdActive=$voiceHoldActive)"
            )
            // ★ v1.11 按住说话：只有「面板仍显示 **且** 手指仍按住」才开始采集。
            //   三种分支必须写清（见 KDoc 顶部竞态说明）：
            //     · 面板可见 + 仍按住 → 正常开局（切倾听态 + start）；
            //     · 面板可见 + 已松开 → 不 start：松开时模型未就绪，[onVoiceRelease] 已 cancel 并提示，
            //       面板可能因 3 秒延迟尚未收起；此时若 start 会录到「空气」再上屏空结果，体验错乱；
            //     · 面板不可见     → 不 start（用户已离开）。
            if (isVoicePanelShown() && voiceHoldActive) {
                Log.i(TAG, "QJ-SV-MODEL onReady -> panel shown & holding -> startListening + start capture")
                voicePanel?.startListening()
                voiceEngine?.start(this)
            } else if (isVoicePanelShown()) {
                Log.i(TAG, "QJ-SV-MODEL onReady but finger released -> do NOT start (released before ready)")
            } else {
                Log.i(TAG, "QJ-SV-MODEL onReady but panel gone -> do NOT start (user left)")
            }
        }

        override fun onDecoding() {
            // VAD 已断句、进入 ASR 推理（★ v1.11：仅**收口段**触发，见 VoiceRecognizer KDoc）：
            // 面板从「请说话，松开结束」切「识别中…」
            if (isVoicePanelShown()) {
                Log.i(TAG, "QJ-SV-ASR onDecoding -> panel.showDecoding")
                voicePanel?.showDecoding()
            }
        }

        override fun onSegmentResult(text: String) {
            // ★ v1.11 中间分句：按住期间每完成一句即上屏，**不结束会话、不收面板**。
            //   空串（VAD 出段但识别为空，如纯噪声）→ 忽略，不 Toast。
            if (voiceSessionFinished) {
                Log.w(TAG, "QJ-SV-SEG IGNORED (session already finished) text='$text'")
                return
            }
            if (text.isEmpty()) {
                Log.i(TAG, "QJ-SV-SEG empty segment ignored (no commit)")
                return
            }
            Log.i(TAG, "QJ-SV-SEG segment -> commitVoiceResult (len=${text.length}), session continues")
            commitVoiceResult(text)
            voiceCommittedAny = true
        }

        override fun onModelError(message: String) {
            // ★ v1.10 P2-2：与 onResult/onError 完全一致的 session 闸门。
            //   场景：模型加载中用户已取消（cancelVoiceInput 置 voiceSessionFinished=true），
            //   加载线程随后失败仍会回调本方法；无闸门则会补弹一次错误 Toast + 错误态面板。
            //   ★ 闸门必须在 voicePrepareTriggered = false 之前——被忽略时不得触碰 prepare 状态。
            if (voiceSessionFinished) {
                Log.w(TAG, "QJ-SV-MODEL onModelError IGNORED (session already finished): $message")
                return
            }
            Log.e(TAG, "QJ-SV-MODEL onModelError: $message")
            voicePrepareTriggered = false
            voiceSessionFinished = true
            voiceHoldActive = false   // ★ v1.11 会话终结 → 清按住态
            toast(message)
            // v1.10：切错误态并停留 3 秒再收面板（可被打断）
            if (isVoicePanelShown()) {
                voicePanel?.showError(message)
                scheduleHideVoicePanel()
            } else {
                hideVoicePanel()
            }
        }

        override fun onResult(text: String) {
            if (voiceSessionFinished) {
                Log.w(TAG, "QJ-SV-RESULT IGNORED (session already finished) text='$text'")
                return
            }
            voiceSessionFinished = true
            voiceHoldActive = false   // ★ v1.11 会话终结 → 清按住态
            Log.i(
                TAG,
                "QJ-SV-RESULT onResult (session end) text='$text' (len=${text.length}, " +
                    "committedAny=$voiceCommittedAny)"
            )
            when {
                text.isNotEmpty() -> {
                    // 尾段非空 → 上屏 + **立刻**收面板（中间分句已实时上屏，这里是最后一段）
                    commitVoiceResult(text)
                    Log.i(TAG, "QJ-SV-RESULT tail committed -> hide panel immediately")
                    hideVoicePanel()
                }
                voiceCommittedAny -> {
                    // ★ v1.11：尾段为空但**已有中间分句上屏** → 静默收面板，不提示「未听到声音」
                    //   （用户已看到文字，再弹错误反而莫名其妙）
                    Log.i(TAG, "QJ-SV-RESULT empty tail but segments already committed -> hide silently")
                    hideVoicePanel()
                }
                else -> {
                    // 全程无任何有效分句（快速点按/全程静音）→ Toast + 错误态 + 停留 3 秒再收面板
                    Log.i(TAG, "QJ-SV-RESULT empty & nothing committed -> toast + error hold")
                    val msg = getString(R.string.voice_offline_empty)
                    toast(msg)
                    if (isVoicePanelShown()) {
                        voicePanel?.showError(msg)
                        scheduleHideVoicePanel()
                    } else {
                        hideVoicePanel()
                    }
                }
            }
        }

        override fun onError(message: String) {
            if (voiceSessionFinished) {
                Log.w(TAG, "QJ-SV-REC onError IGNORED (session already finished): $message")
                return
            }
            voiceSessionFinished = true
            voiceHoldActive = false   // ★ v1.11 会话终结 → 清按住态
            Log.e(TAG, "QJ-SV-REC onError: $message")
            toast(message)
            // v1.10：切错误态并停留 3 秒再收面板（可被打断）
            if (isVoicePanelShown()) {
                voicePanel?.showError(message)
                scheduleHideVoicePanel()
            } else {
                hideVoicePanel()
            }
        }
    }

    /**
     * **停止倾听**（面板热区被点：LISTENING 态下点波纹/文字）。
     *
     * v1.11 语义：按住说话模式下本方法**不再有「工具栏入口」**——🎤 已由 OnTouchListener 接管，
     *   唯一的调用方是 [VoiceInputPanelView.Listener.onStopRequested]（面板热区点击）。
     *   其行为等价于「提前松开」：通知采集线程冲刷尾段 → [voiceCallback.onResult] 收口上屏。
     *   ⚠️ 本方法**不**复位 [voiceHoldActive]：用户的手指可能仍按在 🎤 上（此时点面板热区几乎
     *   不可能，但保守处理）——真正的按住态由 UP/CANCEL/cancel 路径复位；若此刻已复位也无害
     *   （onResult 终结回调亦会清）。
     *
     * ★ v1.11 移除：v1.10 的「ERROR 态停留期内点 🎤 视为重开新会话」分支（原 P2-1 修复）。
     *   移除理由：该分支的**触发前提已不存在**——按住模式下不可能是「🎤 点一下」，
     *   且面板 ERROR 态时手指不可能同时按住 🎤。ERROR 态停留期用户只能等 3 秒自动收面板，
     *   或按返回键/切框（走 [cancelVoiceInput]）。保留该分支只会成为不可达死代码。
     *
     * ★ 已知局限（沿用 v1.10，如实标注）：模型加载**不可中断**。若在加载态点面板热区，
     *   会走 [VoiceInputPanelView.Listener.onCancelRequested] → [cancelVoiceInput]（非本方法），
     *   `cancel()` 只停采集，不中断 `qj-sv-model` 线程；该线程跑完后 [voiceCallback.onReady]
     *   因面板已隐藏（或已松开）而不启采集。
     */
    private fun stopVoiceListening() {
        Log.i(TAG, "QJ-SV-REC user requested STOP via panel -> engine.stop() (tail will still be decoded)")
        if (!voiceModelReady) {
            // 仍在准备模型：停止等价放弃本次
            Log.i(TAG, "QJ-SV-REC stop while model still preparing -> cancel & hide")
            voiceSessionFinished = true
            voiceHoldActive = false
            voiceEngine?.cancel()
            hideVoicePanel()
            return
        }
        voiceEngine?.stop()
        // 立刻停波纹动画：已停止采集，继续动会误导「仍在听」；并切「识别中…」等待尾部解码
        voicePanel?.showDecoding()
    }

    /**
     * **取消**语音识别（不上屏）：停采集 + 隐藏面板 + 复位状态。
     * 用于返回键 / 切输入框 / 收起键盘 / 其它面板抢占 / 失焦等一切「放弃本次输入」路径，
     * 以及 v1.10 的「非倾听态点击面板热区」、v1.11 的「触摸 CANCEL / 松开时模型未就绪」。
     * 幂等：未在语音态时重复调用无副作用。
     *
     * ★ v1.11：**务必复位 [voiceHoldActive]** —— 返回键/切框/收键盘打断按住会话时，
     *   若不复位，随后手指抬起触发的 [onVoiceRelease] 会误以为会话仍在而调 engine.stop()，
     *   导致「取消后又复活一次收口」，体验错乱。本方法的 `voiceHoldActive = false` 即该防御的一环。
     *
     * ★ v1.10：务必撤销「3 秒延迟收面板」Runnable —— 见 [hideVoicePanel] 内的 removeCallbacks，
     *   本方法经 hideVoicePanel 间接收口，故 3 秒延迟期间任何 cancel 都能打断它。
     */
    private fun cancelVoiceInput(reason: String) {
        val capturing = voiceEngine?.isCapturing() == true
        // ★ v1.11：即使面板/采集都不在，也要复位按住态——「无权限按下后松开」「模型未就绪松开」
        //   这类路径可能面板已收，但按住态仍需清（否则下次 DOWN 的防御会误判）。
        val holding = voiceHoldActive
        if (!capturing && !isVoicePanelShown() && !holding) {
            // 既不在采集、没面板、也没按住 → 无需处理（避免噪音日志）
            return
        }
        Log.i(
            TAG,
            "QJ-SV-REC CANCEL input (reason=$reason, holdActive=$holding) -> " +
                "cancel engine + hide panel, NO commit"
        )
        voiceSessionFinished = true
        voiceHoldActive = false
        voiceEngine?.cancel()
        // hideVoicePanel 内部会 removeCallbacks(voicePendingHide)，从而打断 3 秒延迟
        hideVoicePanel()
    }

    /**
     * 识别结果上屏：先把未完成的拼音合成收尾 → `commitText` 完整识别文本 →
     * 更新内部光标预期。**不做任何截断**（识别文本原样上屏）。
     */
    private fun commitVoiceResult(text: String) {
        val ic = currentInputConnection
        if (ic == null) {
            Log.w(TAG, "QJ-SV-RESULT no input connection at commit -> drop result (len=${text.length})")
            return
        }
        if (composing.isNotEmpty()) {
            Log.i(TAG, "QJ-SV-RESULT composing non-empty; finishComposingText before commit")
            ic.finishComposingText()
            resetComposing()
        }
        try {
            ic.commitText(text, 1)
            curSelEnd += text.length
            curSelStart = curSelEnd
            expectedCursor = curSelEnd
            Log.i(TAG, "QJ-SV-RESULT commitText done (len=${text.length}), cursor=$curSelEnd")
        } catch (t: Throwable) {
            Log.e(TAG, "QJ-SV-RESULT commitText failed (len=${text.length})", t)
            toast("上屏失败")
        }
    }

    /** 录音权限是否已授予（API23+ 统一 `checkSelfPermission`；本工程 minSdk 24 满足）。 */
    private fun hasAudioPermission(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /**
     * `onCreate` 中的权限检查：仅记日志（真正拦截在 [onVoicePressDown]）。
     * ★ 用原生 `checkSelfPermission`（`Context` API23+）而非 `ContextCompat`——
     *   本工程 `dependencies {}` 为空，**不引入 androidx.core**，故改用框架 API。
     */
    private fun checkAudioPermission() {
        val granted = hasAudioPermission()
        Log.i(
            TAG,
            "onCreate: RECORD_AUDIO permission granted=$granted " +
                "(API=${Build.VERSION.SDK_INT}, via framework checkSelfPermission)"
        )
        if (!granted) {
            Log.w(TAG, "onCreate: RECORD_AUDIO NOT granted; voice input will guide user to settings")
        }
    }

    /**
     * 引导用户到**本应用**的系统详情页开启权限。
     * 用 `Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)` + `package:` Uri
     * （IME 进程内起 Activity 必须 `FLAG_ACTIVITY_NEW_TASK`）。
     */
    private fun openAppSettingsForPermission() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            Log.i(TAG, "voice: opened app details settings for permission (pkg=$packageName)")
        } catch (t: Throwable) {
            Log.w(TAG, "voice: openAppSettingsForPermission failed", t)
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        private const val DATA_DIR_NAME = "qingjian-data"
        private const val ASSET_DIR = "qingjian-data"
        private const val WUBI_TSV_NAME = "wubi86.tsv"
        private const val T9_TIMEOUT_MS = 900L
        private const val T9_PUNCT = "，。！？"
        private val T9_MAP = mapOf(
            2 to "abc", 3 to "def", 4 to "ghi", 5 to "jkl",
            6 to "mno", 7 to "pqrs", 8 to "tuv", 9 to "wxyz"
        )

        /**
         * ★ v0.8 读取前后文游标时的探测上限（字符数）。
         * 用于 [textLengthOf] / [lineStartOf] / [lineEndOf] / [fallbackSelectedText]。
         * 取 10000：覆盖绝大多数编辑框；超长文本下的**已知低估**局限见 [textLengthOf] 注释。
         */
        private const val TEXT_PROBE_MAX = 10000

        /**
         * ★ v0.8 工具栏「<I>」入口的**长按判定时长**（毫秒）。
         * 直接引用 [CursorModePanelView.HOLD_START_DELAY_MS]，保证入口长按与
         * 面板内方向键长按连续移动**共用同一套 300ms 常量**（任务硬性要求）。
         * 用 const 引用另一个 const，编译期即可比较（见 wireCursorEntryButton 的 shared 日志）。
         */
        private const val CURSOR_ENTRY_HOLD_DELAY_MS = CursorModePanelView.HOLD_START_DELAY_MS

        /**
         * ★ v1.10 错误/空结果态的**面板停留时长**（毫秒）。
         * 需求原文：「识别失败/空结果停留 3 秒再收」。识别成功仍为「立刻收」，不走此值。
         */
        private const val VOICE_ERROR_HOLD_MS = 3000L
    }
}
