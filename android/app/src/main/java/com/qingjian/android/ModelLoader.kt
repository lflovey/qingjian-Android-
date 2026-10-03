package com.qingjian.android

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * ★ v1.9 离线语音模型加载器（sherpa-onnx + SenseVoice-Small）。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * 需求 1 —— 一律使用 filesDir（内部私有目录），**禁止** getExternalFilesDir()
 * ═══════════════════════════════════════════════════════════════════════════
 *  模型目录 = `context.filesDir/models/`（即 `/data/data/com.qingjian.android/files/models/`）。
 *  理由：① 免存储权限；② 防用户/清理软件误删；③ 路径稳定，C++ 侧加载不会因外置
 *  卡卸载/挂载点漂移而失败。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * 需求 4 —— 双路径兼容（调试期 adb push / 发布期 assets 首启拷贝）
 * ═══════════════════════════════════════════════════════════════════════════
 *  判定顺序（[ensureReady]）：
 *    ① filesDir/models 下三个文件齐全且长度校验通过  → 直接使用（调试期 adb push 场景）；
 *    ② 否则从 assets/models 逐个拷贝到 filesDir/models（发布期首启场景）；
 *    ③ 拷贝后再校验；失败则删损坏文件 + 抛 [ModelCorruptedException]。
 *  调试期 push 命令：
 *    adb push model.int8.onnx /data/data/com.qingjian.android/files/models/
 *    adb push silero_vad.onnx /data/data/com.qingjian.android/files/models/
 *    adb push tokens.txt     /data/data/com.qingjian.android/files/models/
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * 需求 2 —— 完整性校验（用户称「救命级」，红线）
 * ═══════════════════════════════════════════════════════════════════════════
 *  · 拷贝后 **及每次加载前** 都校验；
 *  · 校验优先比较 [File.length]（与 assets 内基准长度逐字节数对齐，见 [ASSET_SIZES]）；
 *  · 任一文件缺失/长度不符 → ① 删除损坏文件 ② 抛异常由上层 Toast
 *    「模型文件损坏，请重新安装或推送模型」③ **绝不**把不完整文件交给 C++ 加载
 *    （否则 native 崩溃，无法 catch）。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * 需求 3 —— 异常兜底
 * ═══════════════════════════════════════════════════════════════════════════
 *  · 捕获 IOException：空间不足（磁盘满）→ 向上抛 [ModelIoException]，上层提示清理空间；
 *  · 本类全部方法为**阻塞式**（IO 重），**必须在子线程调用**（由 VoiceRecognizer 保证）。
 *
 * 日志锚点：`QJ-SV-MODEL`。
 */
object ModelLoader {

    /** 模型子目录名（相对 filesDir / assets）。 */
    const val MODEL_DIR_NAME = "models"

    /** senseVoice 主模型文件名。 */
    const val FILE_MODEL = "model.int8.onnx"

    /** Silero VAD 模型文件名。 */
    const val FILE_VAD = "silero_vad.onnx"

    /** 词表文件名。 */
    const val FILE_TOKENS = "tokens.txt"

    /**
     * assets 内各文件的**权威字节数**（来自交付资源清单实测）。
     * 用作完整性校验基准；长度必须逐字节匹配。
     */
    private val ASSET_SIZES: Map<String, Long> = mapOf(
        FILE_MODEL to 239_233_841L,
        FILE_VAD to 1_807_522L,
        FILE_TOKENS to 315_894L,
    )

    /** 需要准备的三个文件（顺序：词表 → VAD → 主模型，先小后大，便于快速失败）。 */
    private val REQUIRED_FILES: List<String> = listOf(FILE_TOKENS, FILE_VAD, FILE_MODEL)

    /** 模型目录 File（`filesDir/models`）。 */
    fun modelDir(context: Context): File = File(context.filesDir, MODEL_DIR_NAME)

    /**
     * 确保模型就绪：filesDir 已有且校验通过 → 直接用；否则从 assets 拷贝并校验。
     *
     * **阻塞方法，必须在子线程调用。**
     *
     * @return 模型目录（绝对路径可用 `dir.absolutePath`）
     * @throws ModelIoException           IO 失败（含空间不足）
     * @throws ModelCorruptedException    文件缺失/长度不符（已删除损坏文件）
     */
    @Throws(ModelIoException::class, ModelCorruptedException::class)
    fun ensureReady(context: Context): File = ensureReady(context, null)

    /**
     * ★ v1.10 带**拷贝进度回调**的重载（需求 2）。
     *
     * 设计动机：首启需拷贝约 228MB，在红米 K70 Pro 上耗时数秒；旧版面板在这期间
     * 显示的是「倾听中」——用户误以为已能说话，实际还在落盘。此重载把「已反馈的落盘
     * 字节数」透出，供面板显示「正在准备离线模型 45%」，让等待可见。
     *
     * 进度语义（跨三个文件的**总体**进度）：
     *   · 分子 = 已写入目标文件的累计字节（含本次运行前已存在的合法文件，按 [ASSET_SIZES]
     *     全量计入——因为对用户而言「已落盘多少」就是进度）；
     *   · 分母 = [ASSET_SIZES] 三者之和（= 总字节数，恒定）；
     *   · 取整到 0..100。
     * 回调频率：至少每 1% 或每 [PROGRESS_STEP_BYTES]（512KiB）触发一次，取**先到者**，
     *   避免 64KiB 缓冲下每读一块都回调（过密 → 主线程 post 洪泛）。
     * **线程**：回调在**调用者线程**（本方法是阻塞的，通常为 VoiceRecognizer 的子线程），
     *   本方法**不做**任何线程切换；调用方需自行把回调 post 到主线程。
     *
     * 边界：若三文件都已存在且校验通过（adb push 调试路径），**不拷贝、不回调进度**，
     *   直接返回——因为进程内无「拷贝」动作，语义上无进度可言（面板会把 0% 之后的
     *   加载态直接切到倾听态，不会卡在某个百分比）。*不回调 100*：避免与「拷贝完成」
     *   混淆，且此时上层本就该直接进倾听态。
     *
     * @param onProgress 进度回调（0..100）；null 时行为等价于 [ensureReady]
     * @return 模型目录
     * @throws ModelIoException           IO 失败（含空间不足）
     * @throws ModelCorruptedException    文件缺失/长度不符（已删除损坏文件）
     */
    @Throws(ModelIoException::class, ModelCorruptedException::class)
    fun ensureReady(context: Context, onProgress: ((percent: Int) -> Unit)?): File {
        val dir = modelDir(context)
        Log.i(TAG, "QJ-SV-MODEL dir=${dir.absolutePath} (withProgress=${onProgress != null})")

        // ① 调试期路径：filesDir 已有且校验通过 → 直接用（不重拷 228MB）
        if (validate(dir, deleteOnFail = false)) {
            Log.i(TAG, "QJ-SV-MODEL filesDir models present & VALID -> use directly (adb-push path)")
            return dir
        }

        // ② 发布期路径：从 assets 拷贝（首次启动）
        Log.i(TAG, "QJ-SV-MODEL filesDir models missing/invalid -> copying from assets …")
        if (!dir.exists() && !dir.mkdirs()) {
            throw ModelIoException("无法创建模型目录: ${dir.absolutePath}")
        }
        val totalBytes = ASSET_SIZES.values.sum()
        var copiedBytes = 0L
        var lastPercent = -1
        var lastReportedBytes = 0L
        for (name in REQUIRED_FILES) {
            // 断点友好：进入拷贝前，若该文件已合法（本次为**部分重拷**场景），
            // 按资产全量计入分子，使进度贴近「已落盘」直观语义。
            val expect = ASSET_SIZES[name] ?: 0L
            val existing = File(dir, name)
            if (existing.exists() && existing.length() == expect) {
                copiedBytes += expect
            }
            copyAssetIfNeeded(context, dir, name) { delta ->
                copiedBytes += delta
                val p = if (totalBytes <= 0L) 100 else
                    ((copiedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
                // 节流：每 1% 或每 512KiB（先到者）才回调一次
                if (p != lastPercent || copiedBytes - lastReportedBytes >= PROGRESS_STEP_BYTES) {
                    lastPercent = p
                    lastReportedBytes = copiedBytes
                    onProgress?.invoke(p)
                }
            }
            // 单文件拷完强制上报一次（保证整数百分比的最终值不被节流吞掉）
            val pAfter = if (totalBytes <= 0L) 100 else
                ((copiedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
            if (pAfter != lastPercent) {
                lastPercent = pAfter
                lastReportedBytes = copiedBytes
                onProgress?.invoke(pAfter)
            }
        }

        // ③ 拷贝后再校验；失败 → 删损坏文件并抛异常（绝不交给 C++）
        if (!validate(dir, deleteOnFail = true)) {
            throw ModelCorruptedException("assets 拷贝后完整性校验失败")
        }
        Log.i(TAG, "QJ-SV-MODEL assets copy done & VALID -> models ready at ${dir.absolutePath}")
        return dir
    }

    /**
     * 校验模型目录下三个文件的长度是否与资产基准一致。
     *
     * @param deleteOnFail true 时，任一不合法即删除该文件（脏数据自清理）
     * @return 全部合法返回 true
     */
    fun validate(dir: File, deleteOnFail: Boolean): Boolean {
        var allOk = true
        for (name in REQUIRED_FILES) {
            val f = File(dir, name)
            val expect = ASSET_SIZES[name] ?: 0L
            val actual = if (f.exists()) f.length() else -1L
            if (actual != expect) {
                allOk = false
                Log.e(
                    TAG,
                    "QJ-SV-MODEL VALIDATE FAIL ${f.absolutePath} " +
                        "exists=${f.exists()} actual=$actual expect=$expect"
                )
                if (deleteOnFail && f.exists()) {
                    val deleted = f.delete()
                    Log.w(TAG, "QJ-SV-MODEL corrupt file deleted=${deleted} (${f.absolutePath})")
                }
            } else {
                Log.i(TAG, "QJ-SV-MODEL VALIDATE OK ${f.name} size=$actual")
            }
        }
        return allOk
    }

    /**
     * 若目标文件缺失或长度不符，则从 assets 拷贝覆盖。
     *
     * @param onChunk 每写入一块（[COPY_BUF] 字节）后回调「本次新增字节数」；
     *   **在调用者线程**同步回调，本方法不做线程切换（供 [ensureReady] 汇总为总体百分比）。
     * @throws ModelIoException 空间不足等 IO 失败
     */
    private fun copyAssetIfNeeded(
        context: Context,
        dir: File,
        name: String,
        onChunk: ((Long) -> Unit)? = null,
    ) {
        val target = File(dir, name)
        val expect = ASSET_SIZES[name] ?: 0L
        // 已存在且长度正确 → 跳过（断点友好，避免重复拷贝大文件）
        if (target.exists() && target.length() == expect) {
            Log.i(TAG, "QJ-SV-MODEL copy skip (already valid) ${target.name} size=${target.length()}")
            return
        }
        // 若长度不符，先删除再拷（覆盖损坏残留）
        if (target.exists() && !target.delete()) {
            Log.w(TAG, "QJ-SV-MODEL cannot delete stale ${target.absolutePath}; will overwrite")
        }
        val t0 = System.currentTimeMillis()
        var input: InputStream? = null
        try {
            input = context.assets.open("$MODEL_DIR_NAME/$name")
            target.outputStream().use { out ->
                val buf = ByteArray(COPY_BUF)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    total += n
                    onChunk?.invoke(n.toLong())
                }
                out.flush()
                Log.i(
                    TAG,
                    "QJ-SV-MODEL copied $name bytes=$total in ${System.currentTimeMillis() - t0}ms " +
                        "-> ${target.absolutePath}"
                )
            }
        } catch (e: IOException) {
            // 空间不足（ENOSPC）等：删除半成品，抛 IO 异常供上层提示「清理空间」
            Log.e(TAG, "QJ-SV-MODEL copy FAILED $name: ${e.message}", e)
            runCatching { if (target.exists()) target.delete() }
            throw ModelIoException("拷贝模型失败（可能是存储空间不足）: $name", e)
        } finally {
            runCatching { input?.close() }
        }
    }

    private const val TAG = "QingjianIME"
    private const val COPY_BUF = 1 shl 16 // 64 KiB

    /**
     * 进度节流步长（字节）：512KiB。
     * 与「每 1%」取或：在 228MB 量级下 1% ≈ 2.28MB > 512KiB，故实际以 1% 为主；
     * 当资产总量远小于 512KiB×100 的极端场景时，512KiB 保证不低于此粒度。
     */
    private const val PROGRESS_STEP_BYTES = 512L * 1024L
}

/**
 * 模型 IO 异常（拷贝/落盘失败，通常为存储空间不足）。
 * 上层据此提示「请清理存储空间」。
 */
class ModelIoException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 模型完整性异常（文件缺失/长度不符，脏文件已删除）。
 * 上层据此 Toast「模型文件损坏，请重新安装或推送模型」。
 */
class ModelCorruptedException(message: String) : Exception(message)
