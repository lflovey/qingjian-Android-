// Copyright (c) 2023 Xiaomi Corporation
// Adapted for 青简输入法 v1.9.1.
//
// ═══════════════════════════════════════════════════════════════════════════
// ⚠️ 本文件内的 **类名 / 包名 / 方法签名 / 字段名** 是 JNI 硬契约，禁止改动。
//
// v1.9.1 修正（P0）：v1.9 只验证了「已存在字段能否被找到」，**未验证字段完整性**。
// 而 sherpa-onnx 的 JNI 对下列字段是 **无条件** GetFieldID（缺一即抛
// `RuntimeException: Failed to get field ID for <name>`）：
//   OfflineModelConfig      : tokens / numThreads / debug / provider /
//                             modelType / tokenizer / modelingUnit / bpeVocab / teleSpeech
//   OfflineRecognizerConfig : decodingMethod / maxActivePaths / hotwordsFile /
//                             hotwordsScore / ruleFsts / ruleFars / blankPenalty
// 缺失后果：`OfflineRecognizer(...)` 构造必抛异常 → 真机语音 100% 不可用。
//
// 字段完整性依据（本次全量核对，非抽查）：
//   ① .so 实测：`strings -a libsherpa-onnx-jni.so | grep -oE "Failed to get field ID for [A-Za-z0-9_]+"`
//      → 77 个无条件读取字段名（见 CHANGELOG_V1.9.1.md 全量核对表）；
//   ② 官方源码逐字对照：`sherpa-onnx/kotlin-api/OfflineRecognizer.kt @ tag v1.12.21`
//      （与本工程内置 libsherpa-onnx-jni.so 同版本），声明顺序亦逐条对齐该 tag。
// ═══════════════════════════════════════════════════════════════════════════
package com.k2fsa.sherpa.onnx

import android.content.res.AssetManager

data class QnnConfig(
    var backendLib: String = "",
    var contextBinary: String = "",
    var systemLib: String = "",
)

/**
 * SenseVoice 离线识别模型配置。
 *
 * 字段完整性：`model` / `language` / `useInverseTextNormalization` / `qnnConfig` 四条全部命中
 * .so 的无条件字段清单。
 *
 * ⚠️ v1.13 修复（真机崩溃：模型加载到 100% 后进程自动退出）：
 *   native `libsherpa-onnx-jni.so` 在解析 `OfflineSenseVoiceModelConfig` 时会**无条件**
 *   读取 `qnnConfig` 字段并按其类描述符 `Lcom/k2fsa/sherpa/onnx/QnnConfig;` 查找类
 *   （.so 实测存在该字符串与 `backendLib/contextBinary/systemLib` 字段名）。
 *   此前本工程缺失该字段与 [QnnConfig] 类 → native 侧 GetFieldID 失败 →
 *   构造 `OfflineRecognizer` 时进程直接崩溃（logcat 表现为模型 LOADING 到 100% 后
 *   VoiceRecognizer 所在进程被杀死、重启）。
 *
 * @property model          模型文件名或绝对路径（本工程传入 filesDir 下的绝对路径）
 * @property language       语言："auto"/"zh"/"en"/"ja"/"ko"/"yue"（默认 "auto" 自动判别）
 * @property useInverseTextNormalization 是否启用逆文本正则化（数字/标点归一化）
 * @property qnnConfig      Qualcomm QNN 配置（本工程不使用 CPU 推理，保持默认空值；
 *                          仅为满足 native JNI 字段契约而声明）
 */
data class OfflineSenseVoiceModelConfig(
    var model: String = "",
    var language: String = "auto",
    var useInverseTextNormalization: Boolean = true,
    var qnnConfig: QnnConfig = QnnConfig(),
)

/**
 * 离线模型总配置。
 *
 * ⚠️ v1.9.1 修复：补齐 `modelType` / `tokenizer` / `modelingUnit` / `bpeVocab` / `teleSpeech`。
 *    JNI 对这 5 个字段 **无条件** GetFieldID，v1.9 缺失 → 构造必抛异常。
 *
 * 字段完整性：本工程声明的 10 个字段（`senseVoice` 为对象型，见下）全部命中 .so；
 * 官方 v1.12.21 另含 transducer/paraformer/whisper/... 等十余个模型配置对象，
 * 均为「对象字段」（JNI 以 `GetFieldID(name, "L…;")` 读取而非无条件标量读取），
 * 本工程未使用对应模型，省略不影响 SenseVoice 路径。
 *
 * 声明顺序按官方 v1.12.21：senseVoice 对象在前，标量字段（teleSpeech/numThreads/debug/
 * provider/modelType/tokens/modelingUnit/bpeVocab）在后。
 */
data class OfflineModelConfig(
    var senseVoice: OfflineSenseVoiceModelConfig = OfflineSenseVoiceModelConfig(),
    var teleSpeech: String = "",
    var numThreads: Int = 1,
    var debug: Boolean = false,
    var provider: String = "cpu",
    var modelType: String = "",
    var tokens: String = "",
    var modelingUnit: String = "",
    var bpeVocab: String = "",
    var tokenizer: String = "",
)

/**
 * 离线识别器总配置。
 *
 * ⚠️ v1.9.1 修复：补齐 `decodingMethod` / `maxActivePaths` / `hotwordsFile` /
 *    `hotwordsScore` / `ruleFsts` / `ruleFars` / `blankPenalty`（JNI 无条件 GetFieldID）。
 *
 * 字段完整性：本工程声明的 9 个字段全部命中 .so；官方 v1.12.21 另含 `hr`
 * （HomophoneReplacerConfig，默认空即禁用），未声明不影响 SenseVoice 路径。
 * 声明顺序按官方 v1.12.21（featConfig → modelConfig → decodingMethod → … → blankPenalty）。
 */
data class OfflineRecognizerConfig(
    var featConfig: FeatureConfig = FeatureConfig(),
    var modelConfig: OfflineModelConfig = OfflineModelConfig(),
    var decodingMethod: String = "greedy_search",
    var maxActivePaths: Int = 4,
    var hotwordsFile: String = "",
    var hotwordsScore: Float = 1.5f,
    var ruleFsts: String = "",
    var ruleFars: String = "",
    var blankPenalty: Float = 0.0f,
)

/**
 * 识别结果（由 JNI 侧构造并返回；字段名同样受 JNI 反射约束）。
 *
 * 官方 v1.12.21 字段：text / tokens / timestamps / lang / emotion / event / durations。
 * 本工程仅消费 [text]，但 **必须** 完整保留官方字段，否则 JNI 侧 `SetObjectField` 会失败。
 */
data class OfflineRecognizerResult(
    val text: String = "",
    val tokens: Array<String> = emptyArray(),
    val timestamps: FloatArray = FloatArray(0),
    val lang: String = "",
    val emotion: String = "",
    val event: String = "",
    val durations: FloatArray = FloatArray(0),
)

/**
 * 离线识别器（线程亲和：单个实例可被多线程调用，内部由 C++ 侧保证；本工程仍串行使用）。
 *
 * @param assetManager 非空时走 assets 加载（本工程不使用，统一走 file 路径以便校验后加载）
 * @param config       识别配置
 */
class OfflineRecognizer(
    assetManager: AssetManager? = null,
    val config: OfflineRecognizerConfig,
) {
    private var ptr: Long

    init {
        // ★ v1.9.1（任务 3）：异常透传。native 侧字段契约不符时抛的是
        //   RuntimeException("Failed to get field ID for <name>")，被 v1.9 的
        //   generic catch 吞成「加载失败」，真机无法定位。此处把原文打进日志锚点 QJ-SV-ASR。
        try {
            ptr = if (assetManager != null) {
                newFromAsset(assetManager, config)
            } else {
                newFromFile(config)
            }
        } catch (t: Throwable) {
            android.util.Log.e(
                "QingjianIME",
                "QJ-SV-ASR OfflineRecognizer JNI construct FAILED: ${t.javaClass.name}: ${t.message}",
                t,
            )
            throw t
        }
        require(ptr != 0L) {
            "Invalid OfflineRecognizerConfig: failed to create native OfflineRecognizer"
        }
    }

    protected fun finalize() {
        if (ptr != 0L) {
            delete(ptr)
            ptr = 0
        }
    }

    /** 释放 native 句柄（幂等）。 */
    fun release() = finalize()

    /** 创建一条识别流，用于接收音频波形并解码。 */
    fun createStream(): OfflineStream {
        val p = createStream(ptr)
        return OfflineStream(p)
    }

    /** 从识别流取回结果（须在 [decode] 之后调用）。 */
    fun getResult(stream: OfflineStream): OfflineRecognizerResult {
        return getResult(stream.ptr)
    }

    /** 解码（阻塞，直到当前流内音频全部识别完成）。 */
    fun decode(stream: OfflineStream) = decode(ptr, stream.ptr)

    /** 运行时更新配置（本工程暂不使用，保留以对齐官方 API）。 */
    fun setConfig(config: OfflineRecognizerConfig) = setConfig(ptr, config)

    private external fun delete(ptr: Long)

    private external fun createStream(ptr: Long): Long

    private external fun setConfig(ptr: Long, config: OfflineRecognizerConfig)

    private external fun newFromAsset(
        assetManager: AssetManager,
        config: OfflineRecognizerConfig,
    ): Long

    private external fun newFromFile(
        config: OfflineRecognizerConfig,
    ): Long

    private external fun decode(ptr: Long, streamPtr: Long)

    private external fun getResult(streamPtr: Long): OfflineRecognizerResult

    companion object {
        init {
            // 与本工程既有 JNI 范式一致：加载失败交由调用方通过 try/catch 感知。
            System.loadLibrary("sherpa-onnx-jni")
        }
    }
}
