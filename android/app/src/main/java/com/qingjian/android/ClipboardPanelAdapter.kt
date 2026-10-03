package com.qingjian.android

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView

/**
 * ★ v0.7 剪贴板历史列表适配器（**BaseAdapter + 懒加载/分页增量加载**）。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * 为什么是 BaseAdapter + 分页（而非一次性 setData 全部）
 * ═══════════════════════════════════════════════════════════════════════════
 * 数据量「无限制」→ 一次性把 N 万条灌进 ListView 会卡顿。故：
 *  - **分页加载**：[appendPage] 每次仅追加 [PAGE_SIZE] 条到内存，滚动接近底部时由
 *    [ClipboardPanelView] 触发下一批（[onLoadMore]）。
 *  - **懒加载复用**：本适配器交给 `ListView`（框架自带 View 回收）；
 *    卡片 View 由 [ClipboardItemView] 自持、`getView` 复用 `convertView`，
 *    避免重复 inflate。
 *
 * ── 展开态与「无截断」──────────────────────────────────────────────────────────
 *  - 折叠：`maxLines = 2` + `ellipsize = END`（**仅显示层折叠**，不影响真实数据）；
 *  - 展开：`maxLines = Int.MAX_VALUE`，展示**完整文本**（超长文本完整可见，未截断）。
 *
 * ── 交互 ──────────────────────────────────────────────────────────────────────
 *  - 点卡片主体 → [onItemPicked]（提交完整文本）；
 *  - 点右侧向下箭头 → [onToggleExpand]（展开/折叠）；
 *  - 长按卡片 → [onItemLongPressed]（删除单条确认）。
 */
class ClipboardPanelAdapter(
    private val context: Context,
    private val listener: Listener
) : BaseAdapter() {

    /** 适配器回调（由 [ClipboardPanelView] 注入，再上抛到宿主）。 */
    interface Listener {
        /** 点击记录 → 提交完整文本。 */
        fun onItemPicked(entry: ClipboardEntry)

        /** 点击右侧箭头 → 展开/折叠。 */
        fun onToggleExpand(entry: ClipboardEntry)

        /** 长按记录 → 删除单条（宿主弹确认）。 */
        fun onItemLongPressed(entry: ClipboardEntry)

        /** 滚动接近底部 → 请求加载下一页。 */
        fun onLoadMore()
    }

    /** 当前内存中的全部数据（分页追加，满足「无限制」下的懒加载）。 */
    private val data = ArrayList<ClipboardEntry>()

    /** 已展开的记录 id 集合（展开态跨 `getView` 复用保持一致）。 */
    private val expandedIds = HashSet<Long>()

    /** 数据源是否还有更多（分页游标小于总数时为 true）。 */
    private var hasMore: Boolean = false

    /** 本次数据源的总条数（用于「已加载 x/总 y」诊断与「最近」计数）。 */
    private var totalCount: Int = 0

    /** 当前分类（仅用于日志）。 */
    private var category: ClipboardCategory = ClipboardCategory.ALL

    /**
     * 重置数据源（切换分类 / 首次加载时调用）。
     * 会清空展开态，并从 [items] 的第 0 条开始填充 **首批**（前 [PAGE_SIZE] 条）。
     *
     * @param items    后台线程一次性读出的**全量**（已按分类过滤 + 排序）列表。
     * @param category 当前分类（诊断用）。
     */
    fun setSource(items: List<ClipboardEntry>, category: ClipboardCategory) {
        data.clear()
        expandedIds.clear()
        this.category = category
        this.totalCount = items.size
        val first = items.take(PAGE_SIZE)
        data.addAll(first)
        hasMore = items.size > data.size
        notifyDataSetChanged()
        clampAndNotify()
    }

    /**
     * 由 [ClipboardPanelView] 持有的「全量」列表，供分页追加时取后续切片。
     * （BaseAdapter 自身只持已加载切片，避免内存一次性膨胀。）
     */
    private var fullSource: List<ClipboardEntry> = emptyList()

    /** 完整数据源（供面板计算分页）。 */
    fun setFullSource(items: List<ClipboardEntry>) {
        fullSource = items
        totalCount = items.size
    }

    /** 追加下一页（惰性加载）。返回是否实际追加了数据。 */
    fun appendPage(): Boolean {
        if (!hasMore) return false
        val start = data.size
        val end = minOf(start + PAGE_SIZE, fullSource.size)
        if (start >= end) {
            hasMore = false
            return false
        }
        data.addAll(fullSource.subList(start, end))
        hasMore = end < fullSource.size
        notifyDataSetChanged()
        clampAndNotify()
        return true
    }

    /** 是否还有更多可加载。 */
    fun hasMore(): Boolean = hasMore

    /** 已加载条数。 */
    fun loadedCount(): Int = data.size

    /** 数据源总条数。 */
    fun sourceTotal(): Int = totalCount

    /** 移除某 id（删除后本地即时刷新，无需整表重查；随后由宿主触发完整重载对齐）。 */
    fun removeById(id: Long) {
        val idx = data.indexOfFirst { it.id == id }
        if (idx >= 0) {
            data.removeAt(idx)
            expandedIds.remove(id)
            if (totalCount > 0) totalCount--
            notifyDataSetChanged()
            clampAndNotify()
        }
        fullSource = fullSource.filterNot { it.id == id }
        totalCount = fullSource.size
    }

    override fun getCount(): Int = data.size

    override fun getItem(position: Int): ClipboardEntry? = data.getOrNull(position)

    override fun getItemId(position: Int): Long = data.getOrNull(position)?.id ?: position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        // 懒加载：滚动接近底部时请求下一页（阈值 5 条）
        if (hasMore && position >= data.size - LOAD_MORE_THRESHOLD) {
            listener.onLoadMore()
        }

        val entry = data.getOrNull(position)
            ?: return TextView(context).apply { text = "" }

        val itemView: ClipboardItemView = when (convertView) {
            is ClipboardItemView -> convertView
            else -> ClipboardItemView(context)
        }

        val expanded = expandedIds.contains(entry.id) || entry.id < 0
        itemView.bind(
            entry = entry,
            expanded = expanded,
            onCardClick = {
                listener.onItemPicked(entry)
            },
            onToggleClick = {
                if (expandedIds.contains(entry.id)) {
                    expandedIds.remove(entry.id)
                } else {
                    expandedIds.add(entry.id)
                }
                notifyDataSetChanged()
            },
            onCardLongClick = {
                listener.onItemLongPressed(entry)
                true
            }
        )
        return itemView
    }

    /**
     * 通用 View 复用由 ListView 负责；BaseAdapter 无 getViewType 需求，
     * 这里提供 `null` 转换语义的兜底，保证 `getView` 返回类型稳定。
     */
    private fun clampAndNotify() {
        // 占位：分页边界校验已由 appendPage 内部处理；此方法保留以统一边界路径。
    }

    companion object {
        /** 每页条数（分页增量加载批次）。 */
        const val PAGE_SIZE = 100

        /** 距底部多少条时触发加载下一页。 */
        private const val LOAD_MORE_THRESHOLD = 5
    }
}

/**
 * ★ v0.7 单条剪贴板记录卡片（深色圆角卡片，复用 `mode_card_bg` 深色配色）。
 *
 * 结构：
 * ```
 *  ┌──────────────────────────────────────────┐
 *  │  完整文本预览（折叠两行，超出省略号）      │   [⌄ 展开箭头]
 *  │  时间 · 类型                              │
 *  └──────────────────────────────────────────┘
 * ```
 *  - 卡片主体（TextView + 时间行）点击 → 提交完整文本；
 *  - 卡片主体长按 → 删除单条；
 *  - 右侧箭头点击 → 展开/折叠（不触发提交）。
 */
class ClipboardItemView(context: Context) : LinearLayout(context) {

    private val rootCard: LinearLayout
    private val textView: TextView
    private val metaView: TextView
    private val toggleButton: ImageButton

    init {
        orientation = VERTICAL
        setPadding(dp(8f).toInt(), dp(4f).toInt(), dp(8f).toInt(), dp(4f).toInt())

        rootCard = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = context.getDrawable(R.drawable.mode_card_bg)
            setPadding(dp(12f).toInt(), dp(10f).toInt(), dp(8f).toInt(), dp(10f).toInt())
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }

        // 左侧文本列（占满剩余宽度）：预览 + 元信息
        val textColumn = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }

        textView = TextView(context).apply {
            setTextColor(context.getColor(R.color.mode_card_text))
            textSize = 15f
            maxLines = 2                       // ★ 折叠：最多两行
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }

        metaView = TextView(context).apply {
            setTextColor(context.getColor(R.color.mode_card_disabled_text))
            textSize = 11f
            maxLines = 1
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4f).toInt()
            }
        }

        textColumn.addView(textView)
        textColumn.addView(metaView)

        // 右侧展开/折叠箭头
        toggleButton = ImageButton(context).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setImageResource(android.R.drawable.ic_menu_more)
            setColorFilter(context.getColor(R.color.mode_card_badge_text))
            contentDescription = "展开/折叠"
            layoutParams = LayoutParams(dp(36f).toInt(), dp(36f).toInt()).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginStart = dp(4f).toInt()
            }
        }

        rootCard.addView(textColumn)
        rootCard.addView(toggleButton)
        addView(rootCard)
    }

    /**
     * 绑定一条记录（**完整文本**：折叠时 `ellipsize` 仅影响显示，展开时 `maxLines=MAX`）。
     *
     * @param entry            记录
     * @param expanded         是否展开
     * @param onCardClick      卡片主体点击（提交）
     * @param onToggleClick    箭头点击（展开/折叠）
     * @param onCardLongClick  卡片主体长按（删除确认）
     */
    fun bind(
        entry: ClipboardEntry,
        expanded: Boolean,
        onCardClick: () -> Unit,
        onToggleClick: () -> Unit,
        onCardLongClick: () -> Boolean
    ) {
        // 文本：完整内容，折叠仅靠 maxLines + ellipsize（**不截断数据**）
        textView.text = entry.text
        if (expanded) {
            textView.maxLines = Int.MAX_VALUE
            textView.ellipsize = null
            toggleButton.setImageResource(android.R.drawable.ic_menu_more)
            toggleButton.rotation = 180f      // 向下箭头 → 翻转示意折叠
        } else {
            textView.maxLines = 2
            textView.ellipsize = android.text.TextUtils.TruncateAt.END
            toggleButton.setImageResource(android.R.drawable.ic_menu_more)
            toggleButton.rotation = 0f
        }

        // 元信息：类型 + 字数 + 时间
        val typeLabel = when (entry.category) {
            ClipboardCategory.NUMBER -> "数字"
            ClipboardCategory.LINK -> "链接"
            else -> "文本"
        }
        metaView.text = buildString {
            append(typeLabel)
            append(" · ")
            append(entry.text.length)
            append(" 字")
            append(" · ")
            append(formatTime(entry.createdAt))
        }

        // 点击/长按：箭头自行消费（setOnClickListener 已置 clickable，事件不冒泡到 rootCard）
        toggleButton.setOnClickListener { onToggleClick() }
        rootCard.setOnClickListener { onCardClick() }
        rootCard.setOnLongClickListener { onCardLongClick() }
    }

    private fun formatTime(ts: Long): String {
        if (ts <= 0L) return "--"
        val sdf = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(ts))
    }

    private fun dp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
}
