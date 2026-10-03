package com.qingjian.android

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.AbsListView
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast

/**
 * ★ v0.7 剪贴板历史面板（深色主题，与 v0.6 输入方式面板同级、同架构）。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * 承载方式（满足「整键盘区被替换、完全遮挡原键盘/候选栏」）
 * ═══════════════════════════════════════════════════════════════════════════
 *  在 `keyboard_view.xml` 中，本 View 是根 [FrameLayout] 的**第三个直接子项**
 *  （与 [InputModePanelView] 同级），`layout_width/height = MATCH_PARENT`、初始 `GONE`。
 *  由 [QingjianImeService] 设为 `VISIBLE` 并 `bringToFront()`，天然叠在「键盘内容列」之上。
 *  → **不是** AlertDialog，**不是**在候选栏内 addView。
 *
 *  ⚠️ 与输入方式面板互斥：同时只能显示一个（由宿主 [QingjianImeService] 保证——
 *     显示本面板前先 hide 输入方式面板，反之亦然）。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * 对外契约（[Listener]）
 * ═══════════════════════════════════════════════════════════════════════════
 *  - [Listener.onItemPicked]      点卡片主体 → 提交**完整文本**（超长不截断）；
 *  - [Listener.onDismissRequested]点返回箭头 / 点 Tab 外区域 → 请求收起；
 *  - [Listener.onClearRequested]  点汉堡菜单「清空」 → 请求清空全部；
 *  - [Listener.onDeleteRequested] 长按卡片 → 请求删除单条（本 View 内弹确认）。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * 数据与分页
 * ═══════════════════════════════════════════════════════════════════════════
 *  - 面板不直接持有 DB；通过 [historyManager] 读取（后台线程 + 主线程回调）。
 *  - 列表采用 [ClipboardPanelAdapter]（BaseAdapter + 分页增量加载），
 *    滚动接近底部自动加载下一页，规避「无限制条数」下的 UI 卡顿。
 *
 * 日志 tag 统一 `QingjianIME`。
 */
class ClipboardPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /**
     * 面板交互回调。由宿主 [QingjianImeService] 实现。
     */
    interface Listener {
        /** 选中某条记录 → 提交完整文本（超长文本完整上屏）。 */
        fun onItemPicked(entry: ClipboardEntry)

        /** 请求收起面板（返回箭头）。 */
        fun onDismissRequested()

        /** 请求清空全部历史（用户已确认）。 */
        fun onClearRequested()

        /** 请求删除单条（用户已确认）。 */
        fun onDeleteRequested(entry: ClipboardEntry)

        /** 请求锁定状态切换。 */
        fun onLockToggled(locked: Boolean)
    }

    /** 交互监听（宿主注入）。 */
    var listener: Listener? = null

    /** 历史管理器（宿主注入；未注入时列表为空）。 */
    var historyManager: ClipboardHistoryManager? = null

    private var backButton: ImageButton? = null
    private var menuButton: ImageButton? = null
    private var lockButton: ImageButton? = null
    private var cloudButton: ImageButton? = null
    private var searchButton: ImageButton? = null
    private var settingsButton: ImageButton? = null

    private var tabClipboard: TextView? = null
    private var tabPhrases: TextView? = null
    private var tabSuggest: TextView? = null

    private var categoryTagsContainer: LinearLayout? = null
    private var countView: TextView? = null

    private var listView: ListView? = null
    private var emptyView: TextView? = null

    private lateinit var adapter: ClipboardPanelAdapter

    /** 二级分类标签：分类 → TextView（用于高亮切换）。 */
    private val categoryTags = LinkedHashMap<ClipboardCategory, TextView>()

    /** 当前二级分类。 */
    private var currentCategory: ClipboardCategory = ClipboardCategory.ALL

    /** 当次数据源「全量」（分页追加的来源）。 */
    private var fullSource: List<ClipboardEntry> = emptyList()

    /** 历史变更监听（面板可见期间注册）。 */
    private val historyChangedListener = ClipboardHistoryManager.OnHistoryChangedListener {
        Log.i(TAG, "panel: history changed -> reload")
        reload()
    }

    /** 是否已注册历史监听（避免重复注册）。 */
    private var listenerRegistered = false

    init {
        LayoutInflater.from(context).inflate(R.layout.clipboard_panel, this, true)
        wireInternalViews()
        buildCategoryTags()
        Log.i(TAG, "ClipboardPanelView init: category tags=${categoryTags.size}")
    }

    // =========================================================================
    // 内部接线
    // =========================================================================

    /** 找到子视图并挂「返回 / 汉堡 / 锁定 / 占位图标」监听。 */
    private fun wireInternalViews() {
        backButton = findViewById(R.id.btnClipboardBack)
        menuButton = findViewById(R.id.btnClipboardMenu)
        lockButton = findViewById(R.id.btnClipboardLock)
        cloudButton = findViewById(R.id.btnClipboardCloud)
        searchButton = findViewById(R.id.btnClipboardSearch)
        settingsButton = findViewById(R.id.btnClipboardSettings)

        tabClipboard = findViewById(R.id.tabClipboard)
        tabPhrases = findViewById(R.id.tabPhrases)
        tabSuggest = findViewById(R.id.tabSuggest)

        categoryTagsContainer = findViewById(R.id.clipboardCategoryTags)
        countView = findViewById(R.id.clipboardCount)
        listView = findViewById(R.id.clipboardList)
        emptyView = findViewById(R.id.clipboardEmpty)

        // 返回箭头 → 请求收起
        backButton?.setOnClickListener {
            Log.i(TAG, "clipboard panel: back arrow tapped -> dismiss requested")
            listener?.onDismissRequested()
        }

        // 汉堡菜单 → 弹出「清空剪贴板」选项
        menuButton?.setOnClickListener {
            Log.i(TAG, "clipboard panel: hamburger tapped -> show menu (clear)")
            showMenu()
        }

        // 顶部三 Tab：剪贴板（当前，真实）；常用语 / 推荐（占位）
        tabClipboard?.setOnClickListener {
            Log.d(TAG, "clipboard panel: tab 'clipboard' (already active)")
        }
        tabPhrases?.setOnClickListener {
            Log.i(TAG, "clipboard panel: tab 'phrases' is placeholder in v0.7")
            toast("「常用语」敬请期待")
        }
        tabSuggest?.setOnClickListener {
            Log.i(TAG, "clipboard panel: tab 'suggest' is placeholder in v0.7")
            toast("「推荐」敬请期待")
        }

        // 底部：锁定（★ 状态真实切换）/ 云同步 / 搜索 / 设置（占位）
        lockButton?.setOnClickListener {
            val mgr = historyManager
            if (mgr == null) {
                toast("剪贴板不可用")
                return@setOnClickListener
            }
            val locked = mgr.toggleLock()
            applyLockVisual(locked)
            toast(if (locked) "已锁定：新复制内容不再加入历史" else "已解锁：恢复记录剪贴板")
            Log.i(TAG, "clipboard panel: lock toggled -> locked=$locked")
            listener?.onLockToggled(locked)
        }
        cloudButton?.setOnClickListener {
            Log.i(TAG, "clipboard panel: cloud button is placeholder in v0.7")
            toast("云同步敬请期待")
        }
        searchButton?.setOnClickListener {
            Log.i(TAG, "clipboard panel: search button is placeholder in v0.7")
            toast("搜索敬请期待")
        }
        settingsButton?.setOnClickListener {
            Log.i(TAG, "clipboard panel: settings button is placeholder in v0.7")
            toast("设置敬请期待")
        }

        // 列表适配器
        adapter = ClipboardPanelAdapter(context, object : ClipboardPanelAdapter.Listener {
            override fun onItemPicked(entry: ClipboardEntry) {
                Log.i(TAG, "clipboard panel: item picked id=${entry.id}, len=${entry.text.length}")
                listener?.onItemPicked(entry)
            }

            override fun onToggleExpand(entry: ClipboardEntry) {
                Log.d(TAG, "clipboard panel: toggle expand id=${entry.id}")
            }

            override fun onItemLongPressed(entry: ClipboardEntry) {
                Log.i(TAG, "clipboard panel: item long-pressed id=${entry.id}, len=${entry.text.length}")
                confirmDelete(entry)
            }

            override fun onLoadMore() {
                if (adapter.hasMore()) {
                    val appended = adapter.appendPage()
                    Log.d(
                        TAG,
                        "clipboard panel: loadMore appended=$appended, " +
                            "loaded=${adapter.loadedCount()}/${adapter.sourceTotal()}"
                    )
                }
            }
        })
        listView?.adapter = adapter
        listView?.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) { /* no-op */ }

            override fun onScroll(
                view: AbsListView?, firstVisibleItem: Int,
                visibleItemCount: Int, totalItemCount: Int
            ) {
                // 滚到底部附近 → 分页加载
                if (firstVisibleItem + visibleItemCount >= totalItemCount - LOAD_MORE_THRESHOLD &&
                    adapter.hasMore()
                ) {
                    val appended = adapter.appendPage()
                    Log.d(
                        TAG,
                        "clipboard panel: onScroll loadMore appended=$appended, " +
                            "loaded=${adapter.loadedCount()}/${adapter.sourceTotal()}"
                    )
                }
            }
        })
    }

    /** 按 [ClipboardCategory.barOrder] 动态构建二级分类标签（单一数据源）。 */
    private fun buildCategoryTags() {
        val container = categoryTagsContainer ?: run {
            Log.e(TAG, "buildCategoryTags: container not found")
            return
        }
        container.removeAllViews()
        categoryTags.clear()
        for (category in ClipboardCategory.barOrder) {
            val tv = TextView(context).apply {
                text = category.label
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dp(10f).toInt(), dp(4f).toInt(), dp(10f).toInt(), dp(4f).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(4f).toInt() }
                setOnClickListener {
                    Log.i(TAG, "clipboard panel: category '${category.label}' tapped")
                    selectCategory(category)
                }
            }
            container.addView(tv)
            categoryTags[category] = tv
        }
        applyCategoryHighlight(currentCategory)
    }

    // =========================================================================
    // 对外 API（宿主调用）
    // =========================================================================

    /**
     * 显示前准备：注册历史监听、复位到「全部」分类、并触发首次加载。
     * 由宿主在 `visibility = VISIBLE` 之前/之后调用（幂等）。
     */
    fun onShow() {
        val mgr = historyManager
        if (mgr != null && !listenerRegistered) {
            mgr.addOnHistoryChangedListener(historyChangedListener)
            listenerRegistered = true
        }
        applyLockVisual(mgr?.locked == true)
        selectCategory(currentCategory)
        reload()
        Log.i(TAG, "clipboard panel onShow: category=${currentCategory.id}, locked=${mgr?.locked}")
    }

    /** 隐藏时清理：注销历史监听（避免长期持有）。 */
    fun onHide() {
        val mgr = historyManager ?: return
        if (listenerRegistered) {
            mgr.removeOnHistoryChangedListener(historyChangedListener)
            listenerRegistered = false
        }
        Log.i(TAG, "clipboard panel onHide: listener unregistered")
    }

    /** 切换二级分类并刷新列表。 */
    fun selectCategory(category: ClipboardCategory) {
        currentCategory = category
        applyCategoryHighlight(category)
        reload()
        Log.i(TAG, "clipboard panel: selectCategory -> ${category.id}")
    }

    /** 当前二级分类。 */
    fun currentCategory(): ClipboardCategory = currentCategory

    /** 重新加载（后台读取 → 主线程渲染 + 分页初始化 + 空态）。 */
    fun reload() {
        val mgr = historyManager ?: run {
            Log.w(TAG, "reload: historyManager null; show empty")
            renderEmpty()
            return
        }
        val limit: Int? = when (currentCategory) {
            ClipboardCategory.RECENT -> ClipboardCategory.RECENT_LIMIT
            else -> null
        }
        mgr.loadEntries(currentCategory, limit) { entries ->
            fullSource = entries
            adapter.setFullSource(entries)
            adapter.setSource(entries, currentCategory)
            renderEmptyState(entries.isEmpty())
            updateCount(entries.size)
            Log.i(
                TAG,
                "clipboard panel reload: category=${currentCategory.id}, " +
                    "sourceSize=${entries.size}, loaded=${adapter.loadedCount()}, empty=${entries.isEmpty()}"
            )
        }
    }

    // =========================================================================
    // 渲染辅助
    // =========================================================================

    /** 高亮当前分类：选中 = 蓝字 + 下划线 + 加粗；未选中 = 次级字色。 */
    private fun applyCategoryHighlight(selected: ClipboardCategory) {
        for ((category, tv) in categoryTags) {
            val isSel = category == selected
            tv.setTextColor(
                context.getColor(
                    if (isSel) R.color.clipboard_tag_selected else R.color.clipboard_topbar_text_inactive
                )
            )
            tv.typeface = if (isSel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            // 下划线：用背景 drawable 表达（蓝字下划线）
            tv.background = if (isSel) context.getDrawable(R.drawable.clipboard_tag_underline) else null
        }
    }

    /** 更新右侧条数文案。 */
    private fun updateCount(loaded: Int) {
        countView?.text = getStringRes(
            R.string.clipboard_count_unlimited, loaded.toString()
        )
    }

    /** 空态渲染（entries 为空 → 显示「暂无剪贴板历史」）。 */
    private fun renderEmptyState(isEmpty: Boolean) {
        emptyView?.visibility = if (isEmpty) View.VISIBLE else View.GONE
        listView?.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    private fun renderEmpty() {
        fullSource = emptyList()
        adapter.setFullSource(emptyList())
        adapter.setSource(emptyList(), currentCategory)
        renderEmptyState(true)
        updateCount(0)
    }

    /** 锁定图标视觉：锁定 → 蓝色 + 旋转 0；解锁 → 次级灰。 */
    private fun applyLockVisual(locked: Boolean) {
        lockButton?.setColorFilter(
            context.getColor(
                if (locked) R.color.clipboard_tag_selected else R.color.clipboard_topbar_text_inactive
            )
        )
        lockButton?.alpha = if (locked) 1f else 0.45f
    }

    /** 汉堡菜单：清空剪贴板（弹确认）。 */
    private fun showMenu() {
        val items = arrayOf(getStringRes(R.string.clipboard_menu_clear))
        AlertDialog.Builder(context)
            .setTitle(getStringRes(R.string.clipboard_menu_title))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> confirmClear()
                }
            }
            .show()
    }

    /** 清空确认。 */
    private fun confirmClear() {
        AlertDialog.Builder(context)
            .setTitle(getStringRes(R.string.clipboard_clear_title))
            .setMessage(getStringRes(R.string.clipboard_clear_message))
            .setPositiveButton(getStringRes(R.string.clipboard_action_confirm)) { _, _ ->
                Log.i(TAG, "clipboard panel: clear confirmed -> onClearRequested")
                listener?.onClearRequested()
            }
            .setNegativeButton(getStringRes(R.string.clipboard_action_cancel), null)
            .show()
    }

    /** 删除单条确认（长按卡片触发）。 */
    private fun confirmDelete(entry: ClipboardEntry) {
        val preview = entry.preview.take(60)
        AlertDialog.Builder(context)
            .setTitle(getStringRes(R.string.clipboard_delete_title))
            .setMessage(getStringRes(R.string.clipboard_delete_message, preview))
            .setPositiveButton(getStringRes(R.string.clipboard_action_delete)) { _, _ ->
                Log.i(TAG, "clipboard panel: delete confirmed id=${entry.id}")
                listener?.onDeleteRequested(entry)
            }
            .setNegativeButton(getStringRes(R.string.clipboard_action_cancel), null)
            .show()
    }

    // =========================================================================
    // 小工具
    // =========================================================================

    private fun dp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private fun getStringRes(id: Int): String = context.getString(id)

    private fun getStringRes(id: Int, vararg args: Any): String = context.getString(id, *args)

    private fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val TAG = ClipboardHistoryManager.TAG

        /** 距底部多少条触发分页加载（与适配器阈值呼应）。 */
        private const val LOAD_MORE_THRESHOLD = 5
    }
}
