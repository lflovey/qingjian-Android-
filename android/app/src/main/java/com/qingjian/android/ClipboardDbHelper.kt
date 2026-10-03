package com.qingjian.android

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

/**
 * ★ v0.7 剪贴板历史持久化（**原生 `SQLiteOpenHelper`，零第三方依赖**）。
 *
 * ── 表结构 DDL ────────────────────────────────────────────────────────────────
 * ```
 * CREATE TABLE clipboard_history (
 *     _id        INTEGER PRIMARY KEY AUTOINCREMENT,
 *     text       TEXT    NOT NULL,          -- 完整文本，无长度上限（不截断）
 *     created_at INTEGER NOT NULL,          -- 毫秒时间戳，用于倒序
 *     pinned     INTEGER NOT NULL DEFAULT 0 -- 0/1，v0.7 预留（恒 0）
 * );
 * CREATE INDEX idx_clipboard_created_at ON clipboard_history(created_at DESC);
 * ```
 *
 * ── 表结构 DDL（v0.7 验收后增强：单行配置表）─────────────────────────────────────
 * ```
 * CREATE TABLE IF NOT EXISTS settings (
 *     key   TEXT PRIMARY KEY,               -- 配置键（如 'locked'）
 *     value TEXT NOT NULL                   -- 配置值（'true' / 'false'）
 * );
 * ```
 * 用途：承载**跨进程重启**需要保持的小型开关（当前仅锁定态 `locked`）。与历史同库同源，
 * 复用同一后台线程池访问，**不新增任何文件/依赖**。配置表与「无限制」规格**互不相关**：
 * settings 只有固定几行（key 为主键），不受条数/字数语义影响。
 *
 * ── 契约 ──────────────────────────────────────────────────────────────────────
 *  - **无条数上限**：本类**不含**任何 DELETE-BY-LIMIT 逻辑；[delete]/[deleteAll] 仅由用户主动触发。
 *  - **无字数上限**：`insert` 直接把整串写入 `text` 列；`queryAll` 用 `Cursor.getString` 完整读出。
 *  - **线程**：所有方法均在 [ClipboardHistoryManager] 的**单线程池**里调用（不再自行加锁）。
 *  - 库文件名 [DB_NAME]、版本 [DB_VERSION]；[onCreate] 建表 + 建索引。
 */
class ClipboardDbHelper(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        Log.i(TAG, "ClipboardDbHelper.onCreate: creating schema v$DB_VERSION")
        db.execSQL(SQL_CREATE_TABLE)
        db.execSQL(SQL_CREATE_INDEX)
        db.execSQL(SQL_CREATE_SETTINGS)  // ★ v0.7 验收后增强：单行配置表（跨重启持久化锁定态）
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        Log.i(TAG, "ClipboardDbHelper.onUpgrade: $oldVersion -> $newVersion")
        // v0.7 为首个版本，无历史升级路径；此处保守地重建（不保留旧数据，v0.7 前无该表）。
        db.execSQL("DROP TABLE IF EXISTS $TABLE_NAME")
        db.execSQL("DROP TABLE IF EXISTS $SETTINGS_TABLE")
        onCreate(db)
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        Log.w(TAG, "ClipboardDbHelper.onDowngrade: $oldVersion -> $newVersion; recreate")
        onUpgrade(db, oldVersion, newVersion)
    }

    /**
     * 插入一条记录（**完整文本，不截断**）。
     * @return 新行 `_id`；失败返回 -1。
     */
    fun insert(text: String): Long {
        val values = ContentValues().apply {
            put(COL_TEXT, text)
            put(COL_CREATED_AT, System.currentTimeMillis())
            put(COL_PINNED, 0)
        }
        return try {
            writableDatabase.insert(TABLE_NAME, null, values)
        } catch (t: Throwable) {
            Log.e(TAG, "ClipboardDbHelper.insert failed (len=${text.length})", t)
            -1L
        }
    }

    /**
     * 查询**全部**记录（无上限），按时间倒序（同毫秒再按 `_id` 倒序，保证稳定）。
     * ★ 超长文本用 `Cursor.getString` 完整读取，不做任何截断。
     */
    fun queryAll(): List<ClipboardEntry> {
        val result = ArrayList<ClipboardEntry>()
        var cursor: Cursor? = null
        try {
            cursor = readableDatabase.query(
                TABLE_NAME,
                PROJECTION,
                null, null, null, null,
                "$COL_CREATED_AT DESC, $COL_ID DESC"
            )
            val idxId = cursor.getColumnIndexOrThrow(COL_ID)
            val idxText = cursor.getColumnIndexOrThrow(COL_TEXT)
            val idxCreated = cursor.getColumnIndexOrThrow(COL_CREATED_AT)
            val idxPinned = cursor.getColumnIndexOrThrow(COL_PINNED)
            while (cursor.moveToNext()) {
                result.add(
                    ClipboardEntry(
                        id = cursor.getLong(idxId),
                        text = cursor.getString(idxText) ?: "",
                        createdAt = cursor.getLong(idxCreated),
                        pinned = cursor.getInt(idxPinned) != 0
                    )
                )
            }
        } catch (t: Throwable) {
            Log.e(TAG, "ClipboardDbHelper.queryAll failed", t)
        } finally {
            cursor?.close()
        }
        return result
    }

    /** 按 `_id` 删除单条。@return 受影响行数。 */
    fun delete(id: Long): Int {
        return try {
            writableDatabase.delete(TABLE_NAME, "$COL_ID = ?", arrayOf(id.toString()))
        } catch (t: Throwable) {
            Log.e(TAG, "ClipboardDbHelper.delete failed (id=$id)", t)
            0
        }
    }

    /** 清空全部（用户主动）。@return 受影响行数。 */
    fun deleteAll(): Int {
        return try {
            writableDatabase.delete(TABLE_NAME, "1", null)
        } catch (t: Throwable) {
            Log.e(TAG, "ClipboardDbHelper.deleteAll failed", t)
            0
        }
    }

    // =========================================================================
    // 单行配置表（v0.7 验收后增强：锁定态跨进程重启持久化）
    // =========================================================================

    /**
     * 读取字符串配置项（不存在则返回 [default]）。
     * ★ 与历史读写共用同一单线程池调用，天然串行、无并发。
     *
     * @param key     配置键（如 [KEY_LOCKED]）
     * @param default 缺省值
     * @return 已存值；不存在或读取失败时返回 [default]
     */
    fun readSetting(key: String, default: String): String {
        var cursor: Cursor? = null
        return try {
            cursor = readableDatabase.query(
                SETTINGS_TABLE,
                arrayOf(COL_SETTING_VALUE),
                "$COL_SETTING_KEY = ?",
                arrayOf(key),
                null, null, null, "1"
            )
            if (cursor.moveToFirst()) {
                cursor.getString(0) ?: default
            } else {
                default
            }
        } catch (t: Throwable) {
            Log.e(TAG, "ClipboardDbHelper.readSetting failed (key=$key)", t)
            default
        } finally {
            cursor?.close()
        }
    }

    /**
     * 写入字符串配置项（UPSERT：`CONFLICT_REPLACE` 覆盖同键旧值）。
     *
     * @param key   配置键（如 [KEY_LOCKED]）
     * @param value 配置值
     * @return 成功 true / 失败 false
     */
    fun writeSetting(key: String, value: String): Boolean {
        val values = ContentValues().apply {
            put(COL_SETTING_KEY, key)
            put(COL_SETTING_VALUE, value)
        }
        return try {
            writableDatabase.insertWithOnConflict(
                SETTINGS_TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE
            )
            true
        } catch (t: Throwable) {
            Log.e(TAG, "ClipboardDbHelper.writeSetting failed (key=$key, value=$value)", t)
            false
        }
    }

    companion object {
        private const val TAG = ClipboardHistoryManager.TAG

        /** 库文件名（独立库，与输入法其它数据隔离）。 */
        const val DB_NAME = "qingjian_clipboard.db"

        /** 库版本。 */
        const val DB_VERSION = 1

        /** 表名。 */
        const val TABLE_NAME = "clipboard_history"

        const val COL_ID = "_id"
        const val COL_TEXT = "text"
        const val COL_CREATED_AT = "created_at"
        const val COL_PINNED = "pinned"

        /** 单行配置表名（v0.7 验收后增强）。 */
        const val SETTINGS_TABLE = "settings"

        const val COL_SETTING_KEY = "key"
        const val COL_SETTING_VALUE = "value"

        /** 锁定态配置键（持久化于 [SETTINGS_TABLE]）。 */
        const val KEY_LOCKED = "locked"

        private val PROJECTION = arrayOf(COL_ID, COL_TEXT, COL_CREATED_AT, COL_PINNED)

        /** 建表 DDL（完整文本列，无长度约束）。 */
        private const val SQL_CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS $TABLE_NAME (
                $COL_ID        INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_TEXT      TEXT    NOT NULL,
                $COL_CREATED_AT INTEGER NOT NULL,
                $COL_PINNED    INTEGER NOT NULL DEFAULT 0
            )
        """

        /** 时间倒序索引（加速「最近」Top-N 与主列表排序）。 */
        private const val SQL_CREATE_INDEX =
            "CREATE INDEX IF NOT EXISTS idx_clipboard_created_at ON $TABLE_NAME($COL_CREATED_AT DESC)"

        /** 单行配置表 DDL（key 主键，value 非空）—— 与历史同库同源，无额外文件/依赖。 */
        private const val SQL_CREATE_SETTINGS = """
            CREATE TABLE IF NOT EXISTS $SETTINGS_TABLE (
                $COL_SETTING_KEY   TEXT PRIMARY KEY,
                $COL_SETTING_VALUE TEXT NOT NULL
            )
        """
    }
}
