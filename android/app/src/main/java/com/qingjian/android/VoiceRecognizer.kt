package com.qingjian.android

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * ★ v1.9 纯离线语音识别引擎（sherpa-onnx + SenseVoice-Small + Silero VAD）。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * 线程模型（需求 3：模型准备必须在子线程；需求 5：采集在独立线程）
 * ═══════════════════════════════════════════════════════════════════════════
 *   · [prepare]   ：后台线程加载/校验模型 → 回调 [Callback.onReady]/[Callback.onModelError]，
 *                   回调经 [Handler] 投递到**主线程**（需求 3）。
 *   · [start]     ：主线程调用；模型已就绪则**立刻**打开 AudioRecord 并启动独立采集线程
 *                   （避免把「模型加载」的耗时算进「点击→开始录」）；未就绪则挂起等就绪。
 *   · 采集线程（[CaptureTask]）：持续 `AudioRecord.read` → 转 float → 喂 Silero VAD →
 *                   端点检测（静音结束）。★ v1.11：**连续会话**——每出一个语音段即提交
 *                   异步解码并回调 [Callback.onSegmentResult]，**不结束会话**，继续采集；
 *                   直到 [stop]（松开）冲刷尾段 → 回调 [Callback.onResult]（会话终结信号）。
 *   · [stop]      ：用户松开 → 置停止标志，采集线程冲刷 VAD 尾部，尾段同样异步解码后
 *                   以 [Callback.onResult] 收口。
 *   · [cancel]    ：丢弃结果、直接停线程、释放音频资源（返回键/切框等「放弃」路径）。
 *
 *  规则：**所有 [Callback] 回调都在主线程**（内部统一 `mainHandler.post`）。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * ★ v1.11 解码与采集解耦（防丢音频，关键）
 * ═══════════════════════════════════════════════════════════════════════════
 *  问题：SenseVoice 为**非流式**解码，单句 0.3~2s。若在采集线程同步解码，则解码期间
 *    `AudioRecord.read` 停摆，而 AudioRecord 环形缓冲有限（旧值仅 128ms 余量）→ 解码
 *    期间的说话内容**必定丢失**。
 *  解法：[CaptureTask] 内建**单线程**解码执行器 `decoderExecutor`（FIFO，保证分句顺序）。
 *    采集循环一旦出段就 `execute { decodeAndEmit(samples) }` 后**立即继续** read/feed VAD，
 *    采集永不因解码阻塞。同时把 AudioRecord bufSize 加大到 [AUDIO_BUFFER_SECONDS]（2s）
 *    作双保险，吸收「解码排队 + 主线程调度」的抖动。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * 录音参数（需求 5，硬约束）
 * ═══════════════════════════════════════════════════════════════════════════
 *   16_000 Hz / 单声道 (CHANNEL_IN_MONO) / 16bit PCM (ENCODING_PCM_16BIT)。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * VAD 时序（需求 5 + v1.11 连续会话）
 * ═══════════════════════════════════════════════════════════════════════════
 *   read(frame=512 sample) → normalize → vad.acceptWaveform(frame)
 *     if vad.isSpeechDetected()  → 首次命中记「VAD 首次检测到语音」
 *     if !vad.empty()            → front()/pop() 取整段 → **异步解码 → onSegmentResult**
 *                                   → **不 return，继续循环**
 *   用户松开 stop() → vad.flush() → 若仍有余段则同样异步解码 → 最后以 **onResult** 收口；
 *                     若无余段则直接 onResult("")。
 *
 * 日志锚点（需求 7）：`QJ-SV-MODEL` / `QJ-SV-REC` / `QJ-SV-VAD` / `QJ-SV-ASR` /
 *   `QJ-SV-RESULT` / `QJ-SV-SEG`。
 */
class VoiceRecognizer(private val context: Context) {

    /** 引擎回调（**全部在主线程**）。 */
    interface Callback {
        /**
         * ★ v1.10 模型拷贝进度（0..100）。
         * 仅在 `prepare` 真正触发 assets→filesDir 拷贝时才会回调；模型已就绪（adb push
         * 调试路径）时不回调。供面板显示「正在准备离线模型 45%」。**主线程回调**。
         */
        fun onProgress(percent: Int)

        /** 模型就绪（可开始录音）。 */
        fun onReady()

        /**
         * ★ v1.10 即将开始解码（VAD 已断句，进入 ASR 推理）。
         * 与 [onReady]/[onResult] 区分：「已检测到语音」≠「正在识别」——本回调标记
         * **真正开始识别**的时刻，供面板切到「识别中…」。**主线程回调**。
         *
         * ⚠️ v1.11 起：连续会话（按住说话）模式下，**只有最终收口那一段**（松开后的
         *   尾段解码，紧随其后即 [onResult]）才回调本方法；按住期间的中间分句只回调
         *   [onSegmentResult]，面板全程保持「倾听态」不闪「识别中…」。
         */
        fun onDecoding()

        /** 模型准备失败。@param message 面向用户的提示文案 */
        fun onModelError(message: String)

        /**
         * ★ v1.11 中间分句结果（连续会话：按住说话时每完成一句即回调一次）。
         *
         * 语义：会话**未结束**，后续还会有更多分句或最终 [onResult]。调用方应把
         * [text] 立即上屏并**保持会话**（不收面板、不置 session 结束）。
         * [text] 可能为空串（VAD 出段但识别为空，如纯噪声）——调用方应忽略空串。
         *
         * 与 [onResult] 的关系：本回调是「流式分句」，[onResult] 是「会话终结信号」，
         * 二者共同构成一次完整会话；[onResult] **必定**在最后一次 [onSegmentResult]
         * 之后到达（尾段无内容时也可能直接 [onResult] 而本回调为 0 次）。
         * **主线程回调**。
         */
        fun onSegmentResult(text: String)

        /** 识别成功（会话终结；文本可能为空串，表示全程静音）。 */
        fun onResult(text: String)

        /** 识别/录音出错（面向用户的提示文案）。 */
        fun onError(message: String)
    }

    /** 主线程 Handler：所有回调统一 post 到主线程。 */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 识别器（init 一次，复用；release 在 [release]）。 */
    @Volatile
    private var recognizer: OfflineRecognizer? = null

    /** VAD（init 一次，复用；每次会话 [Vad.reset]）。 */
    @Volatile
    private var vad: Vad? = null

    /** 模型是否已就绪。 */
    @Volatile
    private var modelReady = false

    /** 模型准备是否已发起（防重复 prepare）。 */
    private var prepareStarted = false

    /** 当前采集任务（null 表示未在采集）。 */
    @Volatile
    private var capture: CaptureTask? = null

    /** 当前会话回调（每次 start 注入）。 */
    @Volatile
    private var callback: Callback? = null

    // =========================================================================
    // 对外 API
    // =========================================================================

    /**
     * 准备模型（**子线程**执行重 IO；完成后主线程回调）。
     *
     * 幂等：已就绪或已在准备中则不重复发起。
     *
     * @param cb 结果回调（主线程）
     */
    fun prepare(cb: Callback) {
        callback = cb
        if (modelReady) {
            Log.i(TAG, "QJ-SV-MODEL already ready -> onReady (immediate)")
            mainHandler.post { cb.onReady() }
            return
        }
        if (prepareStarted) {
            Log.i(TAG, "QJ-SV-MODEL prepare already in progress -> wait (no re-trigger)")
            return
        }
        prepareStarted = true
        // ★ 需求 3：模型加载（228MB）必须在子线程，避免阻塞 IME 主线程
        Thread({ doPrepare(cb) }, "qj-sv-model").start()
    }

    /** 当前是否已可录音（模型就绪且未在采集）。 */
    fun isReady(): Boolean = modelReady

    /** 当前是否正在采集。 */
    fun isCapturing(): Boolean = capture != null

    /**
     * 开始采集（主线程调用）。
     *
     * @param cb 会话回调（识别结果/错误，主线程）
     */
    fun start(cb: Callback) {
        callback = cb
        if (!modelReady) {
            Log.w(TAG, "QJ-SV-REC start IGNORED: model not ready yet (prepare in progress?)")
            // prepare 完成后若用户仍在语音态，上层会在 onReady 里自动重试 start
            return
        }
        if (capture != null) {
            Log.w(TAG, "QJ-SV-REC start IGNORED: already capturing")
            return
        }
        val rec = recognizer
        val v = vad
        if (rec == null || v == null) {
            Log.e(TAG, "QJ-SV-REC start FAILED: recognizer/vad null despite modelReady")
            mainHandler.post { cb.onModelError(context.getString(R.string.voice_model_corrupt)) }
            return
        }
        v.reset()
        val task = CaptureTask(rec, v, cb)
        capture = task
        Log.i(TAG, "QJ-SV-REC START (16kHz/mono/16bit, frame=$FRAME_LEN, vadWindow=${VAD_WINDOW})")
        task.start()
    }

    /**
     * 用户主动结束（主线程调用）：通知采集线程冲刷尾部并提交尾段解码，
     * 最终以 [Callback.onResult]（**会话终结信号**）收口。不释放模型（供下次复用）。
     */
    fun stop() {
        val task = capture ?: return
        Log.i(TAG, "QJ-SV-REC STOP requested by user -> flush & final decode (session will end)")
        task.requestStop()
    }

    /**
     * 取消（主线程调用）：立即停止采集、丢弃结果、释放 AudioRecord。
     * 用于返回键 / 切输入框 / 收键盘 / 面板互斥等「放弃本次输入」路径。
     */
    fun cancel() {
        val task = capture ?: return
        Log.i(TAG, "QJ-SV-REC CANCEL -> abort capture thread, drop result")
        task.requestCancel()
        capture = null
    }

    /** 释放引擎（onDestroy；幂等）。 */
    fun release() {
        Log.i(TAG, "QJ-SV-MODEL release engine")
        capture?.requestCancel()
        capture = null
        runCatching { vad?.release() }.onFailure { Log.w(TAG, "vad release error", it) }
        vad = null
        runCatching { recognizer?.release() }.onFailure { Log.w(TAG, "recognizer release error", it) }
        recognizer = null
        modelReady = false
        prepareStarted = false
    }

    // =========================================================================
    // 模型准备（子线程）
    // =========================================================================

    /** 子线程：ModelLoader 校验/拷贝 → 构造 VAD + Recognizer → 主线程回调。 */
    private fun doPrepare(cb: Callback) {
        val t0 = System.currentTimeMillis()
        try {
            // ★ 需求 2：ensureReady 内部含「拷贝后 + 加载前」两次 length 校验；
            //   本轮起透传拷贝进度（主线程回抛），供面板显示百分比。
            //   与既有回调保持同样的「callback ?: cb」空安全风格，避免 prepare 后被 start 覆盖时丢回调。
            val dir = ModelLoader.ensureReady(context) { percent ->
                mainHandler.post { (callback ?: cb).onProgress(percent) }
            }
            val modelPath = File(dir, ModelLoader.FILE_MODEL).absolutePath
            val vadPath = File(dir, ModelLoader.FILE_VAD).absolutePath
            val tokensPath = File(dir, ModelLoader.FILE_TOKENS).absolutePath

            Log.i(TAG, "QJ-SV-MODEL loading Silero VAD: $vadPath")
            val v = Vad(
                config = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = vadPath,
                        threshold = VAD_THRESHOLD,
                        minSilenceDuration = VAD_MIN_SILENCE,
                        minSpeechDuration = VAD_MIN_SPEECH,
                        windowSize = VAD_WINDOW,
                        maxSpeechDuration = VAD_MAX_SPEECH,
                    ),
                    sampleRate = SAMPLE_RATE,
                    numThreads = 1,
                    provider = "cpu",
                    debug = false,
                ),
            )

            Log.i(TAG, "QJ-SV-MODEL loading SenseVoice: $modelPath (tokens=$tokensPath)")
            // ★ v1.13 语音自动加句号开关（设置页）：true=ITN 自动补句号等标点；false=原样上屏。
            val useAutoPeriod = context.getSharedPreferences(
                SettingsActivity.PREFS_NAME, android.content.Context.MODE_PRIVATE
            ).getBoolean(SettingsActivity.KEY_VOICE_AUTO_PERIOD, SettingsActivity.DEFAULT_AUTO_PERIOD)
            Log.i(TAG, "QJ-SV-MODEL useInverseTextNormalization(autoPeriod)=$useAutoPeriod (settings toggle)")
            val rec = OfflineRecognizer(
                config = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = FEATURE_DIM),
                    modelConfig = OfflineModelConfig(
                        senseVoice = OfflineSenseVoiceModelConfig(
                            model = modelPath,
                            language = "auto",
                            useInverseTextNormalization = useAutoPeriod,
                        ),
                        tokens = tokensPath,
                        numThreads = 2,
                        debug = false,
                        provider = "cpu",
                    ),
                ),
            )

            recognizer = rec
            vad = v
            modelReady = true
            Log.i(TAG, "QJ-SV-MODEL READY in ${System.currentTimeMillis() - t0}ms -> onReady")
            mainHandler.post { callback?.onReady() ?: cb.onReady() }
        } catch (e: ModelCorruptedException) {
            // 完整性校验失败：文件已删，提示重装/push
            Log.e(TAG, "QJ-SV-MODEL CORRUPT: ${e.message}", e)
            prepareStarted = false
            val cbRef = callback ?: cb
            mainHandler.post { cbRef.onModelError(context.getString(R.string.voice_model_corrupt)) }
        } catch (t: Throwable) {
            // ★ v1.9.1：JNI 字段契约缺陷会从 Vad/OfflineRecognizer 构造抛出
            //   RuntimeException("Failed to get field ID for <name>")；ModelLoader 也会抛
            //   ModelIoException（含 cause）。这里按 cause 链判定，并保留 native 原文进日志锚点。
            // 注意：不再要求外层类型恰为 ModelIoException（其虽为 Exception 子类，仍可被
            //   下方链路命中），改用 isModelIo() 递归判定，避免捕获顺序造成的误分类。
            if (t.isModelIo()) {
                Log.e(TAG, "QJ-SV-MODEL IO ERROR: ${t.message}", t)
                prepareStarted = false
                val cbRef = callback ?: cb
                mainHandler.post { cbRef.onModelError(context.getString(R.string.voice_model_io_error)) }
            } else {
                Log.e(TAG, "QJ-SV-MODEL UNEXPECTED init failure: ${t.javaClass.name}: ${t.message}", t)
                prepareStarted = false
                val cbRef = callback ?: cb
                mainHandler.post { cbRef.onModelError(context.getString(R.string.voice_error_default)) }
            }
        }
    }

    /** 沿 cause 链判定是否为 ModelIoException（宽松按简单类名，避免跨类耦合）。 */
    private fun Throwable.isModelIo(): Boolean {
        var t: Throwable? = this
        while (t != null) {
            if (t.javaClass.simpleName.contains("ModelIoException")) return true
            t = t.cause
        }
        return false
    }

    // =========================================================================
    // 采集线程（独立线程；需求 5）
    // =========================================================================

    /**
     * 独立采集任务：`AudioRecord` 读 PCM → float → VAD → 端点出段即**异步**解码。
     *
     * ★ v1.11：连续会话 + 采集/解码解耦（见类 KDoc）。每出一个语音段提交一帧解码任务，
     *   回调 [Callback.onSegmentResult]；仅 [requestStop] 后的收口（尾段或空结果）才回调
     *   [Callback.onResult]。
     *
     * @param rec 识别器（复用）
     * @param vad VAD（复用，开始前已 reset）
     * @param cb  会话回调（主线程）
     */
    private inner class CaptureTask(
        private val rec: OfflineRecognizer,
        private val vad: Vad,
        private val cb: Callback,
    ) {
        /** 用户主动停止（正常收尾，取尾部并收口）。 */
        @Volatile
        private var stopRequested = false

        /** 取消（丢弃结果）。 */
        @Volatile
        private var cancelRequested = false

        /**
         * 是否已发出**会话终结信号**（[Callback.onResult] / [Callback.onError]）。
         * ★ v1.11 语义收窄：连续模式下**只有**终结信号才置位；中间分句（[Callback.onSegmentResult]）
         *   **不**置位。保证「终结信号有且仅有一次」。
         */
        @Volatile
        private var finished = false

        /**
         * ★ v1.11 单线程解码执行器（FIFO → 分句结果顺序与出段顺序一致）。
         * 采集循环只 `execute` 不等待，从而**不阻塞** AudioRecord 读取。
         * 生命周期：会话结束（终结信号已发）→ [ExecutorService.shutdown]；
         * 取消 → [ExecutorService.shutdownNow]（丢弃排队任务）。
         */
        private val decoderExecutor: ExecutorService =
            Executors.newSingleThreadExecutor { r -> Thread(r, "qj-sv-decoder") }

        private var thread: Thread? = null

        fun start() {
            thread = Thread({ run() }, "qj-sv-capture").also { it.start() }
        }

        fun requestStop() {
            stopRequested = true
        }

        fun requestCancel() {
            cancelRequested = true
            // ★ v1.11：立刻丢弃排队中的解码任务（含正在等待的中间分句），避免取消后仍上屏。
            //   幂等：重复调用 shutdownNow 安全；采集循环内亦会再调一次做兜底。
            decoderExecutor.shutdownNow()
        }

        /** 采集主循环。 */
        private fun run() {
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            if (minBuf <= 0) {
                Log.e(TAG, "QJ-SV-REC getMinBufferSize invalid=$minBuf")
                finishWithError(context.getString(R.string.voice_error_audio))
                return
            }
            // ★ v1.11 缓冲加大（双保险，防丢音频）：
            //   期望容量 = 采样率 × 声道(1) × 每样本字节(PCM16=2) × AUDIO_BUFFER_SECONDS(2s)
            //            = 16000 * 2 * 2 = 64000 字节 ≈ 2 秒音频。
            //   取 max(系统最小, 保底帧容量, 2 秒容量)，避免个别机型 getMinBufferSize 偏小导致 underrun。
            //   算术说明：FRAME_LEN * 2 * 4 为旧值（仅 ≈128ms 余量，连续模式下不足以吸收解码耗时）。
            val bufSize = maxOf(minBuf, FRAME_LEN * 2 * 4, SAMPLE_RATE * 2 * AUDIO_BUFFER_SECONDS)
            Log.i(
                TAG,
                "QJ-SV-REC buffer sizing: minBuf=$minBuf, target=$bufSize " +
                    "(=max(minBuf, ${FRAME_LEN * 2 * 4}, ${SAMPLE_RATE * 2 * AUDIO_BUFFER_SECONDS})) " +
                    "approx ${bufSize / (SAMPLE_RATE * 2)}s of audio"
            )
            var ar: AudioRecord? = null
            try {
                ar = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufSize,
                )
                if (ar.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "QJ-SV-REC AudioRecord NOT initialized (state=${ar.state})")
                    finishWithError(context.getString(R.string.voice_error_audio))
                    return
                }
                ar.startRecording()
                Log.i(TAG, "QJ-SV-REC recording started (state=INITIALIZED, bufSize=$bufSize)")

                val shortBuf = ShortArray(FRAME_LEN)
                val floatBuf = FloatArray(FRAME_LEN)
                var speechSeen = false
                var frames = 0L
                var segments = 0
                // ★ v1.12：采集异常计数（用于区分「瞬时繁忙/瞬时错误」与「录音通道真中断」）
                //   zeroReads：连续 read==0 次数（部分 ROM 会瞬时返回 0）；≥ ZERO_READ_ABORT 才判中断
                //   errorReads：连续 read<0 次数（错误码，如麦克风被系统抢占）；≥ ERROR_READ_ABORT 才判中断
                //   一旦读到正常数据（n>0），两者立即清零。
                var zeroReads = 0
                var errorReads = 0
                // read 异常退出的具体错误码（供日志锚点定位）
                var lastReadN = 0
                // 循环退出原因：true=因 read 异常（既未 cancel 也未 stop）退出
                var readAborted = false

                while (!cancelRequested) {
                    val n = ar.read(shortBuf, 0, shortBuf.size)
                    if (n == 0) {
                        // ★ v1.12：瞬时繁忙不立即放弃（旧实现直接 break → 被冒充成「用户松手」收口）。
                        //   sleep(10) 后重试，连续 ZERO_READ_ABORT 次（约 1 秒）才判录音通道中断。
                        zeroReads++
                        if (zeroReads >= ZERO_READ_ABORT) {
                            lastReadN = n
                            Log.e(
                                TAG,
                                "QJ-SV-REC capture aborted (zeroReads=$zeroReads errorReads=$errorReads " +
                                    "lastN=$n stopRequested=$stopRequested)"
                            )
                            readAborted = true
                            break
                        }
                        Thread.sleep(READ_RETRY_SLEEP_MS)
                        continue
                    }
                    if (n < 0) {
                        // ★ v1.12：负值为错误码（如麦克风被系统抢占）→ 记为错误并短暂重试，
                        //   连续 ERROR_READ_ABORT 次才判录音通道中断（否则 sleep 后重试，成功即清零）。
                        errorReads++
                        Log.w(TAG, "QJ-SV-REC read error code n=$n (errorReads=$errorReads/$ERROR_READ_ABORT)")
                        if (errorReads >= ERROR_READ_ABORT) {
                            lastReadN = n
                            Log.e(
                                TAG,
                                "QJ-SV-REC capture aborted (zeroReads=$zeroReads errorReads=$errorReads " +
                                    "lastN=$n stopRequested=$stopRequested)"
                            )
                            readAborted = true
                            break
                        }
                        Thread.sleep(READ_RETRY_SLEEP_MS)
                        continue
                    }
                    // 读到正常数据 → 清零异常计数
                    zeroReads = 0
                    errorReads = 0
                    frames++
                    // PCM16 -> float [-1,1]
                    for (i in 0 until n) {
                        floatBuf[i] = shortBuf[i] / 32768.0f
                    }
                    vad.acceptWaveform(floatBuf)

                    if (!speechSeen && vad.isSpeechDetected()) {
                        speechSeen = true
                        Log.i(TAG, "QJ-SV-VAD speech DETECTED (first hit at frame=$frames)")
                    }

                    // ★ v1.11：端点出段 → **异步**解码为中间分句（不结束会话、不阻塞采集）
                    while (!vad.empty()) {
                        val seg = vad.front()
                        vad.pop()
                        segments++
                        Log.i(
                            TAG,
                            "QJ-SV-VAD segment #$segments samples=${seg.samples.size} " +
                                "(frame=$frames) -> submit async decode (session continues)"
                        )
                        submitDecode(seg.samples, isFinal = false)
                    }

                    if (stopRequested) {
                        Log.i(TAG, "QJ-SV-REC stop flag seen -> flush tail frame=$frames")
                        break
                    }
                }

                if (cancelRequested) {
                    Log.i(TAG, "QJ-SV-REC cancelled -> shutdown decoder, drop result")
                    // 丢弃排队中的解码任务；采集线程自身退出（onResult/onError 均不发）
                    decoderExecutor.shutdownNow()
                    return
                }

                if (readAborted) {
                    // ★ v1.12 关键重构：read 异常退出（既非 cancel 也非 stop）→ 绝不能静默走 flush
                    //   冒充「正常收口」（那正是「话没说完面板自动收回」的静默路径）。
                    //   改为明确的错误收口：置终结闸门 → shutdown 解码器 → onError（录音通道中断）。
                    Log.e(
                        TAG,
                        "QJ-SV-REC capture aborted -> finishWithError (zeroReads=$zeroReads " +
                            "errorReads=$errorReads lastN=$lastReadN stopRequested=$stopRequested)"
                    )
                    // 丢弃排队中的解码任务，避免错误收口后仍有中间分句上屏
                    decoderExecutor.shutdownNow()
                    finishWithError(context.getString(R.string.voice_error_capture))
                    return
                }

                // 用户松开：冲刷 VAD 尾部。
                // ★ v1.11：flush 后 VAD 可能仍有**多段**排队（例如刚出段但主循环尚未 drain 就
                //   收到 stop）。逐段各自**异步**解码为中间分句，仅**最后一段**标 isFinal 作收口，
                //   避免丢掉排队段、又保证「终结信号有且仅有一次」。
                vad.flush()
                if (!vad.empty()) {
                    // 先把所有排队段取出（保持顺序），最后一段作为收口
                    val tails = ArrayList<FloatArray>()
                    while (!vad.empty()) {
                        tails.add(vad.front().samples)
                        vad.pop()
                    }
                    Log.i(TAG, "QJ-SV-VAD flushed ${tails.size} tail segment(s) -> decode (last is final)")
                    for (i in tails.indices) {
                        submitDecode(tails[i], isFinal = (i == tails.size - 1))
                    }
                } else {
                    // 没有可取语音段（全程静音 / 尾段为空）→ 直接以空结果收口
                    Log.i(TAG, "QJ-SV-VAD no tail segment on stop -> finish with empty result")
                    finishWithResult("")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "QJ-SV-REC capture loop crashed", t)
                if (!finished) finishWithError(context.getString(R.string.voice_error_default))
            } finally {
                try {
                    ar?.let {
                        if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop()
                        it.release()
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "QJ-SV-REC AudioRecord release error", t)
                }
                Log.i(TAG, "QJ-SV-REC recording stopped & released")
                // 采集结束 → 清空 capture 引用（供再次 start）
                if (capture === this) capture = null
            }
        }

        /**
         * 提交一段音频到解码执行器（FIFO，保证分句顺序与出段顺序一致）。
         *
         * @param samples 该段 float 采样
         * @param isFinal true = 收口段（解码完成 → [Callback.onResult]）；false = 中间分句
         *   （解码完成 → [Callback.onSegmentResult]）
         */
        private fun submitDecode(samples: FloatArray, isFinal: Boolean) {
            try {
                decoderExecutor.execute { decodeAndEmit(samples, isFinal) }
            } catch (t: Throwable) {
                // 执行器已 shutdown（取消竞态）→ 丢弃本次，不视为错误
                Log.w(TAG, "QJ-SV-ASR submit decode rejected (executor shut down?), isFinal=$isFinal", t)
            }
        }

        /**
         * 解码一段语音并回结果（在解码线程执行；结果经主线程 post）。
         *
         * @param samples 该段 float 采样
         * @param isFinal true = 会话收口段（回 [Callback.onResult] 并终结会话）
         */
        private fun decodeAndEmit(samples: FloatArray, isFinal: Boolean) {
            if (cancelRequested) return
            // ★ 收口段才回调 onDecoding（面板切「识别中…」）；中间分句不切，保持倾听态不闪烁。
            if (isFinal) {
                mainHandler.post { cb.onDecoding() }
            }
            try {
                val t0 = System.currentTimeMillis()
                val stream = rec.createStream()
                // ★ v1.11 QA 修复：release 收进 finally **确定化释放**。
                // 原实现把 stream.release() 放在解码正常路径末尾，acceptWaveform /
                // decode / getResult 任一抛异常都会跳过释放，native stream 只能等
                // finalize()/GC 兜底（延迟释放）。现在无论成功失败都释放；
                // 释放自身失败仅记日志（runCatching 吞掉，不影响下方错误收口）。
                try {
                    stream.acceptWaveform(samples, SAMPLE_RATE)
                    rec.decode(stream)
                    val text = rec.getResult(stream).text
                    val text2 = text.trim()
                    Log.i(
                        TAG,
                        "QJ-SV-ASR decoded in ${System.currentTimeMillis() - t0}ms " +
                            "samples=${samples.size} rawLen=${text.length} isFinal=$isFinal -> '$text2'"
                    )
                    if (isFinal) {
                        // 收口段：置终结闸门 → shutdown 执行器（不再接受新任务）→ 回调 onResult
                        finishWithResult(text2)
                    } else {
                        // 中间分句：不置 finished，不结束会话
                        // ★ v1.12 双保险：错误收口后迟到的中间分句不再上屏。
                        //   readAborted 错误收口时 shutdownNow() 打不断已在运行任务，其 post 可能先于
                        //   onError 入队；此处 post 前复查终结闸门，迟到分句直接丢弃，防止错误收口后仍上屏。
                        if (finished) return
                        Log.i(TAG, "QJ-SV-SEG segment text='$text2' (len=${text2.length})")
                        mainHandler.post { cb.onSegmentResult(text2) }
                    }
                } finally {
                    runCatching { stream.release() }
                        .onFailure { Log.w(TAG, "QJ-SV-ASR stream.release failed (isFinal=$isFinal)", it) }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "QJ-SV-ASR decode FAILED (isFinal=$isFinal)", t)
                if (isFinal) {
                    // 收口段解码失败 → 走统一错误收口（置闸门 + shutdown 解码器 + onError）
                    finishWithError(context.getString(R.string.voice_error_default))
                }
                // 中间分句解码失败：吞掉（不打断整段会话），仅记日志
            }
        }

        /** 发出**会话终结**信号（正常路径）：置闸门 → shutdown 解码器 → 回调 [Callback.onResult]。 */
        private fun finishWithResult(text: String) {
            if (finished) return
            finished = true
            Log.i(TAG, "QJ-SV-RESULT final result text='$text' (len=${text.length}) -> session end")
            mainHandler.post { cb.onResult(text) }
            // 终结信号已发 → 不再接受新解码任务（已在执行的任务不受影响）
            decoderExecutor.shutdown()
        }

        /** 发出**会话终结**信号（错误路径）。 */
        private fun finishWithError(msg: String) {
            if (finished) return
            finished = true
            Log.e(TAG, "QJ-SV-REC error -> '$msg'")
            mainHandler.post { cb.onError(msg) }
            decoderExecutor.shutdown()
        }
    }

    companion object {
        private const val TAG = "QingjianIME"

        /** 采样率（硬约束：16kHz）。 */
        const val SAMPLE_RATE = 16000

        /** 单声道。 */
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO

        /** 16bit PCM。 */
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        /**
         * ★ v1.11 AudioRecord 目标缓冲时长（秒）。
         * 用于吸收「异步解码排队 + 主线程调度」抖动，避免采集 underrun 丢音频。
         * 实际字节数 = SAMPLE_RATE(16000) × 2(字节/样本) × 本值 = 64000 字节。
         */
        private const val AUDIO_BUFFER_SECONDS = 2

        /** 特征维度（SenseVoice / kaldi fbank 固定 80）。 */
        private const val FEATURE_DIM = 80

        /**
         * Silero v5 窗口（16kHz 固定 512）。
         * 说明：.so 内字符串明确 "For silero_vad v5, we require window_size to be 512 for 16kHz"。
         */
        private const val VAD_WINDOW = 512

        /** 每帧读取样本数（= VAD 窗口，逐帧喂入，端点最灵敏）。 */
        private const val FRAME_LEN = VAD_WINDOW

        /** 语音概率阈值。 */
        private const val VAD_THRESHOLD = 0.5f

        /** 判定句尾所需最短静音（秒）→ 触发「自动停」。 */
        private const val VAD_MIN_SILENCE = 0.6f

        /** 判定确为语音所需最短时长（秒）。 */
        private const val VAD_MIN_SPEECH = 0.25f

        /** 单段最长语音（秒），超时强制切分（防长句卡死）。 */
        private const val VAD_MAX_SPEECH = 30.0f

        /**
         * ★ v1.12 采集异常容忍阈值（防「瞬时繁忙/瞬时错误」被误判为录音通道中断）。
         *   · [ZERO_READ_ABORT]：连续 read==0 达此值（≈ 100×10ms = 1 秒）才判中断。
         *   · [ERROR_READ_ABORT]：连续 read<0（错误码）达此值才判中断。
         *   · [READ_RETRY_SLEEP_MS]：异常时每次重试前的等待毫秒数。
         */
        private const val ZERO_READ_ABORT = 100
        private const val ERROR_READ_ABORT = 3
        private const val READ_RETRY_SLEEP_MS = 10L
    }
}
