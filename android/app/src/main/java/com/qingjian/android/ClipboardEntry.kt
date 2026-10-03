package com.qingjian.android

/**
 * ★ v0.7 剪贴板历史「单条记录」数据模型（纯数据，无 Android 依赖）。
 *
 * ── 核心约束「无限制」─────────────────────────────────────────────────────────
 *  - **字数无限制**：[text] 为**完整**原始文本，任何环节都**不得** `substring`/截断。
 *    SQLite TEXT 无长度上限（受设备存储约束），`Cursor.getString` 亦完整返回。
 *  - **条数无限制**：[ClipboardHistoryManager] 永不因条数上限删除记录，
 *    故本模型不含「序号 / 淘汰位」等与上限相关的字段。
 *
 * ── 分类判定（与 [ClipboardHistoryManager] 的过滤规则严格同源，单一数据源）──────
 *  - [ClipboardCategory.NUMBER]：纯数字（含小数点/正负号），见 [isNumeric]。
 *  - [ClipboardCategory.LINK]  ：URL（`http(s)://` 或 `www.` 开头），见 [isLink]。
 *  - [ClipboardCategory.TEXT]  ：非纯数字、非 URL 的其余文本。
 *
 * @property id     数据库自增主键（`_id`；插入前为 -1）。
 * @property text   完整文本内容（**永不截断**）。
 * @property createdAt 入库时间戳（`System.currentTimeMillis()`，用于「最近」倒序）。
 * @property pinned 是否被「锁定」（锁定后新复制内容不入库；本字段为 v0.7 预留，当前恒 false）。
 */
data class ClipboardEntry(
    val id: Long = -1L,
    val text: String = "",
    val createdAt: Long = 0L,
    val pinned: Boolean = false
) {
    /** 单行预览（换行符折叠为空格，仅用于列表第一行；不影响 [text] 的完整性）。 */
    val preview: String
        get() = text.replace('\n', ' ')

    /** 本记录所属的二级分类（互斥：NUMBER / LINK / TEXT 三选一）。 */
    val category: ClipboardCategory
        get() = when {
            isNumeric(text) -> ClipboardCategory.NUMBER
            isLink(text) -> ClipboardCategory.LINK
            else -> ClipboardCategory.TEXT
        }

    companion object {
        /** URL 判定：`http(s)://` 或 `www.` 开头（忽略大小写、忽略首尾空白）。 */
        fun isLink(s: String): Boolean {
            val t = s.trim().lowercase()
            return t.startsWith("http://") || t.startsWith("https://") || t.startsWith("www.")
        }

        /**
         * 纯数字判定：允许可选正负号 `+/-`，允许唯一一个小数点 `.`。
         * 例：`123`、`-3.14`、`+0.5` → true；`12a`、`1.2.3`、`1,000`、空串 → false。
         */
        fun isNumeric(s: String): Boolean {
            val t = s.trim()
            if (t.isEmpty()) return false
            var hasDigit = false
            var hasDot = false
            for ((i, c) in t.withIndex()) {
                when {
                    c in '0'..'9' -> hasDigit = true
                    (c == '-' || c == '+') && i == 0 -> { /* 允许首位符号 */ }
                    c == '.' && !hasDot -> hasDot = true
                    else -> return false
                }
            }
            return hasDigit
        }
    }
}

/**
 * ★ v0.7 剪贴板二级分类。
 *
 * 「全部」「最近」是**序 / 视图**维度（不按内容判定）；「文本」「数字」「链接」是**内容**维度。
 */
enum class ClipboardCategory(val id: String, val label: String) {
    /** 全部：不设上限，全部记录（按时间倒序）。 */
    ALL("all", "全部"),

    /** 最近：按时间倒序 Top 50（视图数上限 = 50，非存储上限）。 */
    RECENT("recent", "最近"),

    /** 文本：非纯数字、非 URL 的文本。 */
    TEXT("text", "文本"),

    /** 数字：纯数字（含小数点/正负号）。 */
    NUMBER("number", "数字"),

    /** 链接：URL（http(s):// 或 www.）。 */
    LINK("link", "链接");

    companion object {
        /** 二级分类栏展示顺序（单一数据源，供 UI 遍历）。 */
        val barOrder: List<ClipboardCategory> = listOf(ALL, RECENT, TEXT, NUMBER, LINK)

        /** 「最近」视图条数上限（仅视图截取，存储永不删）。 */
        const val RECENT_LIMIT = 50
    }
}
