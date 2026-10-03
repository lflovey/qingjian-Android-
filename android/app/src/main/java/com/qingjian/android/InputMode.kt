package com.qingjian.android

/**
 * 输入方式模型（v0.6 规范化字段名）。
 *
 * ★ 双维度设计（v0.5 引入、v0.6 **保留**，这是正确的、不退回单一维度）：
 *
 *  native `nativeSetMode(mode, scheme)` 的方式码**只有 4 个**（0=全拼 1=双拼 2=五笔 3=英文），
 *  且**不得改动 native 层**。「26 键 / 9 键」的差异只是**键盘布局层**的差异
 *  （QWERTY 全键盘 vs T9 九宫格），对应**同一个 native 码**。因此每一项同时携带两个正交维度：
 *
 *   - [nativeCode]：传给引擎的方式码（0/1/2/3）；
 *   - [panel]：本地键盘面板（QWERTY / T9），决定 display 哪套键位、按键如何分发。
 *
 *  例：PINYIN_26 与 PINYIN_9 的 nativeCode **都是 0**（引擎侧同是全拼），但 panel 分别是
 *  QWERTY / T9。这样「两种输入方式」在 UI 上是两张卡、在引擎侧是一套方案，语义清晰、无假切换。
 *
 * ── v0.6 字段命名对齐新规格 ────────────────────────────────────────────────────
 *  新增/补全为规格要求的一组字段：[id] / [label] / [badge] / [supported] / [unavailableHint]，
 *  同时保留 v0.5 的 [nativeCode] / [panel] 两个维度字段。旧名迁移：
 *    title    → label
 *    subtitle → （并入 unavailableHint / 保留作展示说明，见 [desc]）
 *    isPlaceholder / requiresWubiData → supported（统一可用性）+ unavailableHint（不可用原因）
 *
 * ── 可用性总表（supported 语义）────────────────────────────────────────────────
 *  | 卡片          | supported | 说明                                                        |
 *  |---------------|-----------|-------------------------------------------------------------|
 *  | 拼音26键      | true      | 全拼（native 0），默认方式，蓝色高亮                         |
 *  | 拼音9键       | true      | 全拼（native 0），九宫格布局                                 |
 *  | 双拼          | true      | 引擎内置 7 套方案键位表，native 1，**真实可用（不假置灰）**  |
 *  | 英文26键      | true      | Kotlin commitText 直输，native 3                             |
 *  | 英文9键       | true      | 同英文26（T9 布局），native 3                                |
 *  | 五笔26 / 五笔9| 探测而定  | 依赖 wubi86.tsv；装载成功即可用，失败置灰提示「五笔数据缺失」|
 *  | 手写/笔画/    | false     | 置灰，提示「敬请期待」                                       |
 *  | 文字扫描      | false     | 置灰，提示「敬请期待」                                       |
 *  | 键盘手写/     | false     | 置灰，提示「敬请期待」                                       |
 *  | 更多语言      | false     | 置灰，提示「敬请期待」                                       |
 */
enum class InputMode(
    /** 稳定标识（日志/测试/持久化用，不含中文，跨版本不变）。 */
    val id: String,
    /** 卡片下方文字标签（如「拼音26键」）。 */
    val label: String,
    /** 卡片右上角角标（拼/英/五/笔/写/文/🌐）。 */
    val badge: String,
    /** 是否「数据支持」的方式：false = 无数据、置灰、点按仅 Toast、不可切换。 */
    val supported: Boolean,
    /** 不可用时的提示文案（supported=false 或探测失败时展示；可用项为 null）。 */
    val unavailableHint: String?,
    /** 卡片中央图标字符（用系统内可用字形，避免依赖外部图标资源）。 */
    val icon: String,
    /** 卡片第二行说明（可用项展示；便于用户理解方式含义）。 */
    val desc: String,
    /** ★ 保留（v0.5 维度一）：native `nativeSetMode` 的方式码：0=全拼 1=双拼 2=五笔 3=英文。 */
    val nativeCode: Int,
    /** ★ 保留（v0.5 维度二）：本地键盘面板 QWERTY / T9。 */
    val panel: KeyboardPanel,
    /** 是否走引擎的候选通道（英文模式不走，直接 commitText）。 */
    val usesEngine: Boolean,
    /** 数据依赖：true = 依赖 `data_dir/wubi86.tsv`，探测失败需置灰。 */
    val requiresWubiData: Boolean
) {
    // ---- 可用项（真实实现）----
    PINYIN_26(
        "pinyin26", "拼音26键", "拼", true, null, "拼", "全拼 · 26 键",
        nativeCode = 0, panel = KeyboardPanel.QWERTY, usesEngine = true, requiresWubiData = false
    ),
    PINYIN_9(
        "pinyin9", "拼音9键", "拼", true, null, "拼", "全拼 · 九宫格",
        nativeCode = 0, panel = KeyboardPanel.T9, usesEngine = true, requiresWubiData = false
    ),
    SHUANGPIN(
        "shuangpin", "双拼", "拼", true, null, "双", "内置 7 方案键位",
        nativeCode = 1, panel = KeyboardPanel.QWERTY, usesEngine = true, requiresWubiData = false
    ),
    // 五笔：supported=true 表示「数据存在则支持」；真实可用性由 Service 探测 wubi86.tsv 决定
    //       （探测失败时 Service 会在 UI 上强制置灰并提示 unavailableHint）。
    WUBI_26(
        "wubi26", "五笔26键", "五", true, "五笔数据缺失，暂未支持", "五", "86 码表 · 26 键",
        nativeCode = 2, panel = KeyboardPanel.QWERTY, usesEngine = true, requiresWubiData = true
    ),
    WUBI_9(
        "wubi9", "五笔9键", "五", true, "五笔数据缺失，暂未支持", "五", "86 码表 · 九宫格",
        nativeCode = 2, panel = KeyboardPanel.T9, usesEngine = true, requiresWubiData = true
    ),
    ENGLISH_26(
        "english26", "英文26键", "英", true, null, "英", "直输 · 26 键",
        nativeCode = 3, panel = KeyboardPanel.QWERTY, usesEngine = false, requiresWubiData = false
    ),
    ENGLISH_9(
        "english9", "英文9键", "英", true, null, "英", "直输 · 九宫格",
        nativeCode = 3, panel = KeyboardPanel.T9, usesEngine = false, requiresWubiData = false
    ),

    // ---- 无数据支持项（置灰，点按仅 Toast，不可切换）----
    WRITING(
        "writing", "手写", "写", false, "敬请期待", "✍", "手写 · 暂未支持",
        nativeCode = 4, panel = KeyboardPanel.QWERTY, usesEngine = true, requiresWubiData = false
    ),
    STROKE(
        "stroke", "笔画", "笔", false, "敬请期待", "丨", "笔画 · 暂未支持",
        nativeCode = 5, panel = KeyboardPanel.QWERTY, usesEngine = true, requiresWubiData = false
    ),
    TEXT_SCAN(
        "textscan", "文字扫描", "文", false, "敬请期待", "⛶", "OCR · 暂未支持",
        nativeCode = 6, panel = KeyboardPanel.QWERTY, usesEngine = true, requiresWubiData = false
    ),
    KEYBOARD_WRITING(
        "kbdwriting", "键盘手写", "写", false, "敬请期待", "✎", "键盘手写 · 暂未支持",
        nativeCode = 7, panel = KeyboardPanel.QWERTY, usesEngine = true, requiresWubiData = false
    ),
    MORE_LANGS(
        "morelangs", "更多语言", "🌐", false, "敬请期待", "🌐", "多语言 · 暂未支持",
        nativeCode = 8, panel = KeyboardPanel.QWERTY, usesEngine = true, requiresWubiData = false
    );

    /**
     * 「无数据支持」判定（v0.6 规格：置灰项 supported=false）。
     * 与旧 [isPlaceholder] 语义等价，保留别名以兼容既有调用点。
     */
    val isPlaceholder: Boolean
        get() = !supported

    /** 旧字段别名（v0.5 调用点兼容）：title == label。 */
    val title: String
        get() = label

    /** 旧字段别名（v0.5 调用点兼容）：subtitle == desc。 */
    val subtitle: String
        get() = desc

    companion object {
        /** 默认输入方式（全拼 26 键）。 */
        val DEFAULT: InputMode = PINYIN_26

        /**
         * 卡片网格展示顺序（4 列铺开，严格照百度输入法排布）：
         *  第一排 拼音26键  拼音9键  双拼    手写
         *  第二排 五笔26键  五笔9键  英文26键 英文9键
         *  第三排 文字扫描  键盘手写 更多语言
         */
        val gridOrder: List<InputMode> = listOf(
            PINYIN_26, PINYIN_9, SHUANGPIN, WRITING,
            WUBI_26, WUBI_9, ENGLISH_26, ENGLISH_9,
            TEXT_SCAN, KEYBOARD_WRITING, MORE_LANGS
        )

        /** v0.5 旧名别名：PANEL_ORDER == gridOrder（兼容既有调用点）。 */
        val PANEL_ORDER: List<InputMode>
            get() = gridOrder

        /** 网格每行列数（4 列）。 */
        const val GRID_COLUMNS: Int = 4

        /** 由 native 码 + 键盘面板反查唯一的 InputMode；未知组合回退 [DEFAULT]。 */
        fun resolve(nativeCode: Int, panel: KeyboardPanel): InputMode =
            entries.firstOrNull { it.nativeCode == nativeCode && it.panel == panel && it.supported }
                ?: DEFAULT

        /** 默认双拼方案名（引擎侧 `ShuangpinScheme` 的 key；内置表见 `shuangpin/scheme.rs`）。 */
        const val DEFAULT_SHUANGPIN_SCHEME = "xiaohe"

        /** 双拼方案名 → 界面标签（面板/日志用；引擎侧方案名见 `shuangpin/scheme.rs`）。 */
        val SHUANGPIN_SCHEMES: List<Pair<String, String>> = listOf(
            "xiaohe" to "小鹤双拼",
            "ziranma" to "自然码",
            "microsoft" to "微软双拼",
            "sogou" to "搜狗双拼",
            "abc" to "智能ABC",
            "xiaolang" to "小浪双拼",
            "shoudao" to "首道双拼"
        )
    }
}

/**
 * 本地键盘面板（与 native 方式码正交的第二维度）。
 * - [QWERTY]：26 键全键盘（`keyboard_qwerty.xml`）；
 * - [T9]：9 键九宫格（`keyboard_t9.xml`）。
 */
enum class KeyboardPanel { QWERTY, T9 }
