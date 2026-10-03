package com.qingjian.android

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.SoundEffectConstants

/**
 * ★ v1.3 删除键（`special:backspace`）手势状态机。
 *
 * 需求：**按住连续删除** + **上滑一键清空**。把「手势判定」与「业务动作」彻底分离：
 *   · 本类只负责：计时（长按 500ms、连删 80ms）、位移判定（上滑 50dp）、状态标志、
 *     气泡显隐回调、`view.isPressed` 视觉反馈；
 *   · 真正的删除 / 清空 / 引擎缓冲处理全部由 [Listener] 的实现方（QingjianImeService）完成。
 * 因此本类**不持有** `InputConnection`、不碰引擎，纯手势层，易于单测与复用。
 *
 * ── 为什么必须是 `View.OnTouchListener` + `Handler` 自计时，而不是 `setOnLongClickListener` ──
 *   `View.dispatchTouchEvent` 的长按检测位于 `OnTouchListener` **之后**：只要
 *   `OnTouchListener.onTouch` 返回 `true`（本类为了持续接收 ACTION_MOVE / ACTION_UP
 *   **必须**返回 true），View 就会**跳过**自身的长按/点击检测链路，
 *   `setOnLongClickListener` 永远不被调用 → 长按分支是死代码。
 *   这是 v0.8 已踩过的工程硬纪律（详见 `wireCursorEntryButton` 注释），本类沿用
 *   「OnTouchListener + Handler 自计时」范式（对标 `wireCursorEntryButton`）。
 *   ⚠️ 由于本类返回 true 并自行处理，**绝不能**再给删除键挂 `setOnLongClickListener`。
 *
 * ── 手势状态机 ────────────────────────────────────────────────────────────────
 *   ACTION_DOWN   : 记时 + 复位全部标志（含 [hasTriggeredClear]）；postDelayed([longPressRunnable], 500ms)
 *   ACTION_MOVE   : 仅看 **Y 轴**；上滑位移 > 阈值 → 【不可逆锁死】连删 + 进清空模式 + 气泡；
 *                   滑回阈值内 → 仅收气泡，**保持锁死**（绝无 postDelayed 恢复；横向滑动不触发，与键盘横滑不冲突）
 *   ACTION_UP     : removeCallbacks；**按最终 dy 判定**：锁死且最终 dy 超阈值 → 清空；
 *                   锁死但已滑回（最终 dy 在阈值内）→ 取消清空；未锁死 → 短按单删 / 长按结束不额外操作
 *   ACTION_CANCEL : removeCallbacks（防泄漏、防后台瞎删）
 *
 * ── ★ v1.5「上滑锁死」语义（覆盖 v1.4 B4）────────────────────────────────────
 *   用户规格：**上滑操作具有最高优先级且不可逆**。一旦上滑打断，当前整个手势周期内的长按
 *   连续删除逻辑必须**彻底死锁**；只有手指完全抬起、重新点击（新的 ACTION_DOWN）才允许
 *   重新触发连删。**绝对不能在滑回时恢复连续删除。**
 *   因果（为何不可逆）：上滑表达的是「清空」这一更高优先级的**终态意图**；一旦用户做出上滑，
 *   后续无论手指回到哪里，都不应再退回「逐字连删」这一低优先级子行为——否则用户以为已经
 *   取消上滑、其实文字正在被后台连删（v1.4 的现象）。因此用不可逆锁 [hasTriggeredClear]
 *   一次性切断整个手势周期的连删路径，仅允许「清空」（最终位置在阈值外）或「什么都不做」
 *   （最终位置在阈值内）两种终态。
 *
 * ── 边界（光标在 0 / 空框 / 无 InputConnection）────────────────────────────────
 *   连续删除的每一步回调 [Listener.onContinuousDelete]；其返回 `false` 表示已达边界
 *   （光标在 0 或删无可删）→ 本类**立即停止循环**（removeCallbacks）并打日志，不报错。
 *
 * ── 视觉模式说明（与需求 2.5 的单次删除合并）──────────────────────────────────
 *   需求要求「清空的是光标之前的**所有**内容」。实现上：一旦在**任何一次抬手**判定为
 *   清空模式，就对「光标之前」执行 deleteSurroundingText(pos, 0)，其中 pos 来自光标
 *   绝对位置（getExtractedText + startOffset）。若本次按下尚未触发过任何删除
 *   （纯长按上滑），则单次清空即可；若已触发连续删除（已上屏删除了若干字符），
 *   再执行可见清空即可（无需重复补删已删部分）——二者统一定义在
 *   [Listener.onClearBeforeCursor]，由服务层落地。本类只负责「是否进入清空模式」。
 */
class BackspaceGestureHandler(
    private val listener: Listener
) {

    /** 业务回调：手势层 → 服务层。所有回调都在主线程（触摸事件线程即主线程）。 */
    interface Listener {
        /** 短按（未达长按阈值且未触发连删）→ 单次删除。对应 Service 的 onBackspace 语义。 */
        fun onSingleDelete()

        /**
         * 连续删除的**一步**（每 80ms 调一次，含首次触发那一步）。
         * @return `false` 表示已达边界（光标在 0 / 删无可删）→ 手势层停止循环；
         *         `true` 表示本步删除成功，可继续。
         */
        fun onContinuousDelete(): Boolean

        /**
         * 上滑清空：执行「光标之前所有内容」的清空，并同步清引擎拼音缓冲。
         * @param charsSinceDeleteStart 本次按下期间连删已上屏删除的字符数（日志用）。
         */
        fun onClearBeforeCursor(charsSinceDeleteStart: Int)

        /** 清空模式切换（true=进入→弹气泡；false=取消/抬起→收气泡）。用于控制气泡显隐。 */
        fun onClearModeChanged(active: Boolean)
    }

    // ---- 状态标志 ----
    /** 本次按下手势的起始时刻（uptimeMillis）；-1 表示当前无按下触点。 */
    private var downElapsed = -1L

    /** 首次按下的 Y 坐标（像素）。 */
    private var downY = 0f

    /**
     * 首次按下的 X 坐标（像素）。
     * ★ v1.4 修复 B3：v1.3 的 onMove 里 `abs(event.x - 0f)` 把「绝对 X 坐标」当成
     *   横向位移打印，日志完全误导排查。此处记录 downX，位移一律用 `downX/upX - event.x`。
     */
    private var downX = 0f

    /** 本次按下迄今记录到的**最大上滑位移**（像素，向上为正）。 */
    private var maxUpDy = 0f

    /** 是否已触发长按（连续删除已开始）。用于 ACTION_UP 分流：已长按 → 不再补单次删除。 */
    private var longPressFired = false

    /** 是否处于「清空模式」（上滑超阈值，气泡可见）。滑回时置 false 隐藏气泡，但不清除 [hasTriggeredClear]。 */
    private var clearMode = false

    /**
     * ★ v1.5 新增：不可逆锁——本次按下周期内是否已触发过「上滑打断」。
     *
     * 设计说明（为何新增而非复用 clearMode）：
     *   v1.4 的 `clearMode` 是**可变**语义（上滑 true → 滑回 false），它同时承担了
     *   「连删是否被打断」与「气泡是否可见」两个职责。本轮用户要求上滑一旦触发就连删死锁，
     *   于是把两个概念**分离**：
     *     · [hasTriggeredClear]：不可逆，true 后本次手势内**永远保持 true**（连删死锁 + UP 走清空语义）；
     *     · [clearMode]       ：可变，仅表示「气泡是否可见」（滑回要隐藏气泡）。
     *   分离后 [ACTION_DOWN] 复位、[longPressRunnable] 守卫、[onUp] 清空判定三处都更清晰，
     *   也避免了 v1.4「滑回置 clearMode=false 后无法区分『从未上滑』与『上滑又滑回』」的歧义。
     */
    private var hasTriggeredClear = false

    /** 本次按下期间连删已上屏删除的字符数（仅用于日志与清空说明）。 */
    private var deletedCount = 0

    /** 主线程 Handler（IME 触摸回调天然在主线程，显式指定 Looper 保证一致）。 */
    private val handler = Handler(Looper.getMainLooper())

    /** 长按触发 Runnable：500ms 后开始连续删除。 */
    private val longPressRunnable = object : Runnable {
        override fun run() {
            // 已抬手（安全冗余：removeCallbacks 理论上已阻止，但双重保险）
            if (downElapsed < 0) {
                Log.d(TAG, "backspace gesture: longPressRunnable fired but no active touch; skip")
                return
            }
            // ★ v1.5 新增守卫：本次手势已上滑锁死连删 → 任何残余的长按触发一律跳过。
            //   因果：上滑是不可逆的高优先级意图，锁一旦置位，绝不允许连删在这一手势周期内复活。
            if (hasTriggeredClear) {
                Log.i(
                    TAG,
                    "上滑已锁死，忽略长按触发 | backspace gesture: longPressRunnable SKIPPED " +
                        "(hasTriggeredClear=true, continuous delete locked for this gesture)"
                )
                return
            }
            longPressFired = true
            Log.i(
                TAG,
                "backspace gesture: CONTINUOUS DELETE START at ${LONG_PRESS_DELAY_MS}ms " +
                    "(interval=${REPEAT_INTERVAL_MS}ms)"
            )
            // 一步删除；返回 false 表示已达边界 → 停止，不再 postDelayed
            deleteOnceThenMaybeRepeat()
        }
    }

    /** 单步删除 + 视情况续投下一帧。边界时 removeCallbacks 并打日志。 */
    private fun deleteOnceThenMaybeRepeat() {
        val ok = runCatching { listener.onContinuousDelete() }.getOrDefault(false)
        if (!ok) {
            Log.i(
                TAG,
                "backspace gesture: CONTINUOUS DELETE STOP — boundary reached " +
                    "(cursor at 0 or nothing to delete); deleted=$deletedCount this press"
            )
            handler.removeCallbacks(longPressRunnable)
            return
        }
        deletedCount++
        Log.d(TAG, "backspace gesture: continuous delete step #$deletedCount (interval=${REPEAT_INTERVAL_MS}ms)")
        handler.postDelayed(longPressRunnable, REPEAT_INTERVAL_MS)
    }

    /**
     * 绑定到删除键 Button。⚠️ 调用方（`wireKeys`）必须按 tag(`special:backspace`)
     * 跳过该按钮，否则会被统一的 `setOnClickListener` 覆盖掉本监听。
     */
    fun attach(view: View) {
        // 阈值：50dp → px（**必须按 density 换算**，不得直接用 50）
        val density = view.resources.displayMetrics.density
        val touchSlopPx = (SWIPE_UP_THRESHOLD_DP * density).toInt()

        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    onDown(v, event)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    onMove(v, event, touchSlopPx)
                    true
                }

                MotionEvent.ACTION_UP -> {
                    onUp(v, event, touchSlopPx)
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    onCancel(v)
                    true
                }

                else -> false
            }
        }
        Log.i(
            TAG,
            "backspace gesture: attach ready (longPress=${LONG_PRESS_DELAY_MS}ms, " +
                "interval=${REPEAT_INTERVAL_MS}ms, swipeUp=${SWIPE_UP_THRESHOLD_DP}dp=" +
                "${touchSlopPx}px @density=$density)"
        )
    }

    /** ACTION_DOWN：记时、复位（含解锁）、投递 500ms 长按定时器。 */
    private fun onDown(v: View, event: MotionEvent) {
        v.isPressed = true
        downElapsed = SystemClock.uptimeMillis()
        downY = event.y
        downX = event.x   // ★ v1.4：记录按下 X，供 B3 位移计算（不再用绝对坐标）
        maxUpDy = 0f
        longPressFired = false
        deletedCount = 0
        // ★ v1.5：新的 ACTION_DOWN = 唯一允许重新触发连删的时机 → 解锁。
        hasTriggeredClear = false
        if (clearMode) {
            // 理论上不会出现（上抬手会复位）；防御性复位并收气泡
            clearMode = false
            listener.onClearModeChanged(false)
        }
        handler.postDelayed(longPressRunnable, LONG_PRESS_DELAY_MS)
        Log.i(
            TAG,
            "backspace gesture: DOWN (y=${event.y}, x=${event.x}, longPress=${LONG_PRESS_DELAY_MS}ms," +
                " interval=${REPEAT_INTERVAL_MS}ms, hasTriggeredClear reset=false)"
        )
    }

    /** ACTION_MOVE：只看 Y 轴；上滑锁死连删；滑回只收气泡、保持锁死。 */
    private fun onMove(v: View, event: MotionEvent, touchSlopPx: Int) {
        val dy = downY - event.y        // 向上为正
        val dx = downX - event.x        // ★ v1.4 修复 B3：真正的横向位移（v1.3 是 abs(event.x - 0f) = 绝对 X）
        if (dy > maxUpDy) maxUpDy = dy
        when {
            dy > touchSlopPx && !hasTriggeredClear -> {
                // ★ v1.5 核心：上滑 = 不可逆锁死连续删除（最高优先级）。
                //   v1.4 缺陷：进入清空模式只清定时器 + 置 clearMode，滑回时 B4 又「恢复」长按定时器
                //   → 用户以为取消了上滑，实际连删在后台复活继续删字。
                //   本轮按用户规格：上滑一旦触发，本次手势周期内连删彻底死锁，只有新的 ACTION_DOWN 才能解锁。
                //   幂等守卫 `!hasTriggeredClear` 保证本分支在一次手势内最多进入一次（避免重复触发）。
                //   removeCallbacksAndMessages(null)：一并干掉「已投递的下一帧连删」与「尚未到点的 500ms
                //   长按定时器」，这是 B1(主因)+B2 的合流修复，必须保留。
                hasTriggeredClear = true
                handler.removeCallbacksAndMessages(null)
                clearMode = true
                Log.i(
                    TAG,
                    "上滑触发，锁死连续删除 | backspace gesture: SWIPE-UP LOCK " +
                        "(dy=${dy.toInt()}px > threshold=${touchSlopPx}px, dx=${dx.toInt()}px, " +
                        "timers cleared, hasTriggeredClear=true, longPressFired=$longPressFired)"
                )
                v.isPressed = true   // 保持按下视觉反馈
                listener.onClearModeChanged(true)   // 立即弹「松开清空」气泡，不等任何计时
            }

            dy <= touchSlopPx && hasTriggeredClear && clearMode -> {
                // ★ v1.5：滑回阈值内 → 仅隐藏气泡，**保持 hasTriggeredClear=true 锁死**。
                //   绝不做任何 postDelayed(longPressRunnable) 恢复（v1.4 的 B4 逻辑已彻底删除）。
                //   因果：上滑是不可逆意图；滑回只表示「用户不想清空了」，绝不代表「退回逐字连删」。
                clearMode = false
                listener.onClearModeChanged(false)
                Log.i(
                    TAG,
                    "上滑撤回，保持锁死，等待重新按下 | backspace gesture: SWIPE-BACK (dy=${dy.toInt()}px " +
                        "<= threshold=${touchSlopPx}px): bubble hidden, LOCK HELD " +
                        "(hasTriggeredClear=true, NO timer requeue, no postDelayed(longPressRunnable))"
                )
            }

            dy > touchSlopPx && hasTriggeredClear && !clearMode -> {
                // ★ v1.5「滑回后再上滑」场景的显式设计决策：
                //   锁已置位（连删绝对不复活），但用户既然再次越阈上滑，说明其意图回到「清空」→
                //   重新显示气泡，让视觉与最终 ACTION_UP 的清空判定保持一致（避免「手指在阈值外、
                //   气泡却没了」的错觉）。此处**只翻气泡**，绝不重触发连删、也不重投任何定时器。
                //   ACTION_UP 仍按最终 dy 判定：最终在阈值外 → 清空；滑回后再松开 → 取消清空。
                clearMode = true
                listener.onClearModeChanged(true)
                Log.i(
                    TAG,
                    "上滑再次越阈，重新显示气泡（锁仍然保持）| backspace gesture: RE-SWIPE-UP while locked " +
                        "(dy=${dy.toInt()}px > threshold=${touchSlopPx}px): bubble shown again, " +
                        "LOCK HELD (no continuous delete re-trigger, no timer requeue)"
                )
            }

            else -> {
                // 阈值内的普通移动：保持按下视觉；debug 级记录便于排查横滑误触发
                v.isPressed = true
                Log.d(
                    TAG,
                    "backspace gesture: MOVE (dy=${dy.toInt()}px, dx=${dx.toInt()}px, " +
                        "threshold=${touchSlopPx}px, clearMode=$clearMode, hasTriggeredClear=$hasTriggeredClear)"
                )
            }
        }
    }

    /** ACTION_UP：撤定时器 → 分流（锁死清空 / 取消清空 / 单次删除 / 长按结束）。 */
    private fun onUp(v: View, event: MotionEvent, touchSlopPx: Int) {
        v.isPressed = false
        // ★ 必须先撤掉长按定时器（含已投递的下一帧连删）：只要抬手就不再触发连续删除。
        //   v1.4：由 removeCallbacks(longPressRunnable) 升级为 removeCallbacksAndMessages(null)，
        //   一次性清空本 Handler 全部待执行消息，杜绝任何残留续投在抬手后继续删字。
        handler.removeCallbacksAndMessages(null)

        val dy = downY - event.y                 // 最终 dy（向上为正）
        val dx = downX - event.x                 // ★ v1.4 修复 B3：按位移计算（v1.3 用绝对坐标）
        val elapsed = if (downElapsed < 0) -1L else SystemClock.uptimeMillis() - downElapsed
        val locked = hasTriggeredClear           // ★ v1.5：是否上滑锁死（不可逆）
        val wasClear = clearMode
        val wasFired = longPressFired
        val deleted = deletedCount

        // 复位状态（含解锁——本次手势彻底结束，下次 ACTION_DOWN 重新开始）
        downElapsed = -1L
        longPressFired = false
        clearMode = false
        hasTriggeredClear = false
        deletedCount = 0

        Log.i(
            TAG,
            "backspace gesture: UP (elapsed=${elapsed}ms, 最终 dy=${dy.toInt()}px, " +
                "threshold=${touchSlopPx}px, hasTriggeredClear=$locked, clearMode=$wasClear, " +
                "longPressFired=$wasFired, deleted=$deleted)"
        )

        if (locked) {
            // ★ v1.5 新语义：锁死分支按【最终手指位置 dy】判定是否清空（v1.4 用 wasClear）。
            //   因果：上滑已打断了连删（不可逆），本次手势只剩两种终态——清空 or 不做任何删除。
            //   · 最终 dy 仍 > 阈值（手指停在阈值外）→ 执行清空「光标之前所有内容」。
            //   · 最终 dy <= 阈值（已滑回后松手）→ 视为取消清空，**不执行任何删除**。
            //   两种情况都**不做任何连删补充**（锁死保证连删路径已彻底关闭）。
            listener.onClearModeChanged(false)   // 收气泡
            if (dy > touchSlopPx) {
                Log.i(
                    TAG,
                    "backspace gesture: UP [locked] 最终 dy=${dy.toInt()}px > threshold=${touchSlopPx}px " +
                        "-> CLEAR before cursor"
                )
                runCatching { listener.onClearBeforeCursor(deleted) }
                    .onFailure { Log.e(TAG, "backspace gesture: onClearBeforeCursor failed", it) }
            } else {
                Log.i(
                    TAG,
                    "backspace gesture: UP [locked] 最终 dy=${dy.toInt()}px <= threshold=${touchSlopPx}px " +
                        "-> CANCEL CLEAR (no delete, no continuous delete)"
                )
            }
            v.performClick()
            return
        }

        if (!wasFired) {
            // 短按（<500ms 且连删未触发）→ 单次删除
            Log.i(TAG, "backspace gesture: UP short tap (elapsed=${elapsed}ms < ${LONG_PRESS_DELAY_MS}ms) -> single delete")
            runCatching { listener.onSingleDelete() }
                .onFailure { Log.e(TAG, "backspace gesture: onSingleDelete failed", it) }
        } else {
            // 长按结束（连删已触发）→ 不额外操作
            Log.i(TAG, "backspace gesture: UP after LONG PRESS (deleted=$deleted) -> no extra action")
        }
        v.performClick()
    }

    /** ACTION_CANCEL：撤定时器、复位（含解锁）、收气泡。 */
    private fun onCancel(v: View) {
        v.isPressed = false
        // ★ v1.4：升级为 removeCallbacksAndMessages(null)，清空全部待执行消息（防泄漏、防后台瞎删）。
        handler.removeCallbacksAndMessages(null)
        val wasClear = clearMode
        downElapsed = -1L
        longPressFired = false
        clearMode = false
        hasTriggeredClear = false   // ★ v1.5：CANCEL 亦彻底复位锁，本次手势终结
        Log.d(
            TAG,
            "backspace gesture: CANCEL (clearMode=$wasClear, deleted=$deletedCount); " +
                "all callbacks removed, hasTriggeredClear reset"
        )
        deletedCount = 0
        if (wasClear) listener.onClearModeChanged(false)
    }

    companion object {
        /** 长按触发延时（毫秒）。 */
        const val LONG_PRESS_DELAY_MS = 500L

        /** 连续删除间隔（毫秒）。 */
        const val REPEAT_INTERVAL_MS = 80L

        /** 上滑清空阈值（dp，需按 density 换算成 px）。 */
        const val SWIPE_UP_THRESHOLD_DP = 50
    }
}
