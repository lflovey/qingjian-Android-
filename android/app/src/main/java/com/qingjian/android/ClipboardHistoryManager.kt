package com.qingjian.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * ★ v0.7 剪贴板历史管理（**原生 SQLiteOpenHelper 持久化，零第三方依赖**）。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * 核心规格：「**无限制字数 + 无限制条数**」
 * ═══════════════════════════════════════════════════════════════════════════
 *  - **字数无限制**：整条文本原样写入 `TEXT` 列、原样 `Cursor.getString` 读出，
 *    **任何路径都不做 `substring` / `length` 限制**。超长文本完整存储、完整上屏。
 *  - **条数无限制**：本类**不存在**按条数删除的逻辑（无「保留最近 N 条」）。
 *    唯一删除入口是用户主动的 [deleteEntry]（删单条）与 [clearAll]（清空）。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * 承载与线程
 * ═══════════════════════════════════════════════════════════════════════════
 *  - 由 [QingjianImeService] 在 `onCreate` 构造（**IME 常驻**），`onDestroy` 调 [shutdown]
 *    注销剪贴板监听并关闭线程池。
 *  - 所有 SQLite 读写与超长文本处理都在**单线程池** [executor]（后台线程）执行；
 *    结果经主线程 [mainHandler] 回调（UI 更新回主线程）。
 *  - **不使用协程**（工程无 kotlinx-coroutines 依赖，遵守「零新增依赖」）。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * 日志
 * ═══════════════════════════════════════════════════════════════════════════
 * 统一 tag `QingjianIME`；关键路径（监听、入库、删除、清空、锁定）均打 log。
 */
class ClipboardHistoryManager(private val context: Context) {

    /** 变更通知：宿主注册后，Manager 在**主线程**回调（面板据此刷新列表）。 */
    fun interface OnHistoryChangedListener {
        fun onHistoryChanged()
    }

    private val listeners = CopyOnWriteArrayList<OnHistoryChangedListener>()

    /** 单线程池：SQLite 与超长文本处理全在此串行执行，天然线程安全。 */
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "qingjian-clipboard-db").apply { isDaemon = true }
    }

    /** 主线程 Handler：把结果/通知切回 UI 线程。 */
    private val mainHandler = Handler(Looper.getMainLooper())

    private val db: ClipboardDbHelper = ClipboardDbHelper(context)

    /** 系统剪贴板管理器（null 时降级为「仅本地历史」）。 */
    private val clipboardManager: ClipboardManager? =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    /** ★ 锁定态：true 时新复制内容**不再入库**（但仍会更新内部去重游标，避免解锁后误加）。
     *  该状态**跨 IME 进程重启持久化**于 [ClipboardDbHelper.SETTINGS_TABLE] 的 `locked` 键：
     *  初始化时在后台线程读入，[toggleLock]/[setLocked] 切换时立即在后台线程写回。 */
    @Volatile
    var locked: Boolean = false
        private set

    /** 最近一次「已被系统剪贴板监听处理过」的文本，用于去重（避免重复入库）。 */
    @Volatile
    private var lastSeenText: String? = null

    /** 系统剪贴板变化监听（IME 常驻注册）。 */
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        onPrimaryClipChanged()
    }

    // =========================================================================
    // 生命周期
    // =========================================================================

    /**
     * ★ v0.7 验收后增强：初始化时从数据库读入持久化的锁定态（**后台线程**，不阻塞主线程）。
     *
     * 读入后 `locked` 即恢复为上次退出前的值，从而「锁定」可跨 IME 进程被杀→重启保持。
     * 读失败/无记录时保守回退为 `false`（未锁定），不影响既有行为。
     */
    private fun loadLockStateAsync() {
        executor.execute {
            val stored = db.readSetting(ClipboardDbHelper.KEY_LOCKED, "false")
            val restored = stored.toBoolean()
            locked = restored
            Log.i(TAG, "lock state loaded: locked=$restored (raw='$stored')")
        }
    }

    /**
     * ★ v0.7 验收后增强：把当前锁定态写回数据库（**后台线程**，不阻塞主线程）。
     * 仅在状态发生真实变化时调用（见 [toggleLock]/[setLocked]）。
     */
    private fun persistLockStateAsync(value: Boolean) {
        executor.execute {
            val ok = db.writeSetting(ClipboardDbHelper.KEY_LOCKED, value.toString())
            Log.i(TAG, "lock toggled: locked -> $value (persisted=$ok)")
        }
    }

    /**
     * 注册系统剪贴板监听（IME 常驻调用；重复调用幂等）。
     *
     * 注册后立刻读取一次当前剪贴板（`ClipboardManager.primaryClip` 在聚焦应用可读），
     * 作为「已见」基线，避免把 clipboard 里已有的旧内容在首次改变时重复入库。
     */
    fun start() {
        loadLockStateAsync()  // ★ 先从 DB 恢复锁定态（后台），再注册监听
        try {
            clipboardManager?.addPrimaryClipChangedListener(clipListener)
            lastSeenText = readClipboardText()
            Log.i(TAG, "ClipboardHistoryManager.start: listener registered, baseline=${lastSeenText?.length ?: -1} chars")
        } catch (t: Throwable) {
            Log.e(TAG, "ClipboardHistoryManager.start failed", t)
        }
    }

    /** 注销系统剪贴板监听并关闭线程池（`onDestroy` 调用，幂等）。 */
    fun shutdown() {
        try {
            clipboardManager?.removePrimaryClipChangedListener(clipListener)
            Log.i(TAG, "ClipboardHistoryManager.shutdown: listener unregistered")
        } catch (t: Throwable) {
            Log.w(TAG, "ClipboardHistoryManager.shutdown: unregister error", t)
        }
        try {
            executor.shutdownNow()
        } catch (t: Throwable) {
            Log.w(TAG, "ClipboardHistoryManager.shutdown: executor shutdown error", t)
        }
    }

    // =========================================================================
    // 监听回调
    // =========================================================================

    /**
     * 系统剪贴板变化：
     *  1. 读取当前主剪辑文本（**完整读取，不截断**）；
     *  2. 与 [lastSeenText] 去重；
     *  3. 未锁定 → 后台入库；已锁定 → 仅记录游标、不入库（符合规格「锁定后新复制内容不入库」）。
     */
    private fun onPrimaryClipChanged() {
        val text = readClipboardText()
        if (text == null) {
            Log.d(TAG, "clipboard changed: no text clip (non-text or empty); ignore")
            return
        }
        if (text.isNotEmpty() && text == lastSeenText) {
            Log.d(TAG, "clipboard changed: duplicate of last seen; ignore (${text.length} chars)")
            return
        }
        lastSeenText = text
        Log.i(TAG, "clipboard changed: len=${text.length} chars, locked=$locked")

        if (locked) {
            Log.i(TAG, "clipboard changed: LOCKED -> skip persist (len=${text.length})")
            return
        }
        if (text.isEmpty()) {
            Log.d(TAG, "clipboard changed: empty text; skip persist")
            return
        }
        addEntry(text)
    }

    /**
     * 读取系统剪贴板主剪辑文本。
     * @return 剪贴板文本；无文本剪辑/不可读时返回 null。
     */
    private fun readClipboardText(): String? {
        val cm = clipboardManager ?: return null
        return try {
            val clip: ClipData? = cm.primaryClip
            if (clip == null || clip.itemCount == 0) return null
            val item = clip.getItemAt(0)
            item.coerceToText(context)?.toString()
        } catch (t: Throwable) {
            // Android 10+ 后台不可读剪贴板时会抛 SecurityException/异常，安全降级。
            Log.w(TAG, "readClipboardText failed (may be background restriction)", t)
            null
        }
    }

    /**
     * 判断 IME 当前是否为「系统默认输入法 / 聚焦应用」——两者都成立时剪贴板可读。
     * 仅用于日志与诊断，不影响核心逻辑（读失败走 [readClipboardText] 的安全降级）。
     */
    fun canReadClipboard(): Boolean {
        return try {
            val imeId = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            Log.d(TAG, "canReadClipboard: defaultIme=$imeId (our pkg=${context.packageName})")
            imeId?.startsWith(context.packageName) == true
        } catch (t: Throwable) {
            Log.w(TAG, "canReadClipboard failed", t)
            false
        }
    }

    // =========================================================================
    // 锁定
    // =========================================================================

    /**
     * 切换「锁定」状态。
     *  - 锁定后：新复制内容**不再追加**到历史（但不影响既有记录与上屏）。
     *  - 解锁后：恢复监听入库。
     *  ★ 切换后**立即在后台线程持久化**，保证跨 IME 进程重启保持。
     * @return 切换后的锁定状态。
     */
    fun toggleLock(): Boolean {
        locked = !locked
        Log.i(TAG, "toggleLock -> locked=$locked")
        persistLockStateAsync(locked)
        return locked
    }

    /** 显式设置锁定状态（供恢复/测试）。状态真实变化时**后台持久化**。 */
    fun setLocked(value: Boolean) {
        if (locked != value) {
            locked = value
            Log.i(TAG, "setLocked -> $locked")
            persistLockStateAsync(locked)
        }
    }

    // =========================================================================
    // CRUD（全部在后台单线程池执行；结果回主线程）
    // =========================================================================

    /**
     * 添加一条记录（后台）。
     * ★ 无上限：不做任何条数淘汰；★ 无字数上限：整串写入。
     */
    fun addEntry(text: String) {
        if (text.isEmpty()) {
            Log.d(TAG, "addEntry: empty text; skip")
            return
        }
        executor.execute {
            try {
                val id = db.insert(text)
                Log.i(TAG, "addEntry: persisted id=$id, len=${text.length} chars (NO truncation)")
                notifyChanged()
            } catch (t: Throwable) {
                Log.e(TAG, "addEntry failed (len=${text.length})", t)
            }
        }
    }

    /** 删除单条（按 `_id`）。 */
    fun deleteEntry(id: Long) {
        executor.execute {
            try {
                val rows = db.delete(id)
                Log.i(TAG, "deleteEntry: id=$id -> deletedRows=$rows")
                notifyChanged()
            } catch (t: Throwable) {
                Log.e(TAG, "deleteEntry failed (id=$id)", t)
            }
        }
    }

    /** 清空全部历史（用户主动；这是**唯一**的批量删除入口）。 */
    fun clearAll() {
        executor.execute {
            try {
                val rows = db.deleteAll()
                Log.i(TAG, "clearAll: deletedRows=$rows")
                notifyChanged()
            } catch (t: Throwable) {
                Log.e(TAG, "clearAll failed", t)
            }
        }
    }

    /**
     * 读取历史并按分类过滤（后台读取 → 主线程回调）。
     *
     * ★ 过滤规则（与规格一一对应）：
     *   - [ClipboardCategory.ALL]    → 全部（无条数限制），`ORDER BY created_at DESC, _id DESC`；
     *   - [ClipboardCategory.RECENT] → 时间倒序 **Top 50**（[ClipboardCategory.RECENT_LIMIT]）；
     *   - [ClipboardCategory.TEXT]   → 非纯数字、非 URL；
     *   - [ClipboardCategory.NUMBER] → 纯数字（含小数点/正负号）；
     *   - [ClipboardCategory.LINK]   → URL（http(s):// 或 www.）。
     *
     * @param category 二级分类
     * @param limit    可选条数上限（null = 不限制）。「最近」传 50；其余传 null。
     * @param onResult 结果回调（**主线程**）。
     */
    fun loadEntries(
        category: ClipboardCategory,
        limit: Int? = null,
        onResult: (List<ClipboardEntry>) -> Unit
    ) {
        executor.execute {
            val result: List<ClipboardEntry> = try {
                val all = db.queryAll()  // 全量倒序，无上限
                val filtered = when (category) {
                    ClipboardCategory.ALL -> all
                    ClipboardCategory.RECENT -> all
                    ClipboardCategory.TEXT -> all.filter { it.category == ClipboardCategory.TEXT }
                    ClipboardCategory.NUMBER -> all.filter { it.category == ClipboardCategory.NUMBER }
                    ClipboardCategory.LINK -> all.filter { it.category == ClipboardCategory.LINK }
                }
                val capped = if (limit != null && limit > 0) filtered.take(limit) else filtered
                Log.i(
                    TAG,
                    "loadEntries: category=${category.id}, total=${all.size}, " +
                        "filtered=${filtered.size}, returned=${capped.size}, limit=${limit ?: "none"}"
                )
                capped
            } catch (t: Throwable) {
                Log.e(TAG, "loadEntries failed (category=${category.id})", t)
                emptyList()
            }
            mainHandler.post { onResult(result) }
        }
    }

    /**
     * 统计某分类下的条数（用于面板「当前条数」显示），结果回主线程。
     * 全部/最近按上述规则计数；其余按分类计数。
     */
    fun countEntries(category: ClipboardCategory, onResult: (Int) -> Unit) {
        executor.execute {
            val count = try {
                val all = db.queryAll()
                when (category) {
                    ClipboardCategory.ALL -> all.size
                    ClipboardCategory.RECENT -> minOf(all.size, ClipboardCategory.RECENT_LIMIT)
                    ClipboardCategory.TEXT -> all.count { it.category == ClipboardCategory.TEXT }
                    ClipboardCategory.NUMBER -> all.count { it.category == ClipboardCategory.NUMBER }
                    ClipboardCategory.LINK -> all.count { it.category == ClipboardCategory.LINK }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "countEntries failed (category=${category.id})", t)
                0
            }
            mainHandler.post { onResult(count) }
        }
    }

    // =========================================================================
    // 监听器
    // =========================================================================

    /** 注册历史变更监听（宿主持有；建议在面板显示期间注册）。 */
    fun addOnHistoryChangedListener(listener: OnHistoryChangedListener) {
        listeners.addIfAbsent(listener)
        Log.d(TAG, "addOnHistoryChangedListener: listeners=${listeners.size}")
    }

    /** 注销历史变更监听。 */
    fun removeOnHistoryChangedListener(listener: OnHistoryChangedListener) {
        listeners.remove(listener)
        Log.d(TAG, "removeOnHistoryChangedListener: listeners=${listeners.size}")
    }

    /** 主线程通知所有监听者（历史已变更）。 */
    private fun notifyChanged() {
        mainHandler.post {
            for (l in listeners) {
                try {
                    l.onHistoryChanged()
                } catch (t: Throwable) {
                    Log.w(TAG, "notifyChanged: listener threw", t)
                }
            }
        }
    }

    companion object {
        /** 统一日志 tag（规格要求）。 */
        const val TAG = "QingjianIME"
    }
}
