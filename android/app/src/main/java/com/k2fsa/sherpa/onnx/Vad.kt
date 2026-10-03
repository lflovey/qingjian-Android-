// Copyright (c) 2024 Xiaomi Corporation
// Adapted for 青简输入法 v1.9.1.
//
// ═══════════════════════════════════════════════════════════════════════════
// ⚠️ 类名 / 包名 / 方法签名 / 字段名 均为 JNI 硬契约（nm -D 实测）：
//   Java_com_k2fsa_sherpa_onnx_Vad_newFromFile / _newFromAsset
//   Java_com_k2fsa_sherpa_onnx_Vad_acceptWaveform / _compute / _empty
//   Java_com_k2fsa_sherpa_onnx_Vad_isSpeechDetected / _front / _clear
//   Java_com_k2fsa_sherpa_onnx_Vad_flush / _delete
//
// v1.9.1 修正（P0）：VadModelConfig 缺少 `tenVadModelConfig` 字段。JNI 对
//   `tenVadModelConfig` **无条件** GetFieldID（实测 .so 含
//   "Failed to get field ID for tenVadModelConfig"），缺失 → `Vad(...)` 构造必抛
//   RuntimeException → 真机语音 100% 不可用。本次补齐该字段并新增 TenVadModelConfig。
//   字段完整性依据：.so 无条件字段集 + 官方源码 Vad.kt @ tag v1.12.21 逐条对照。
// ═══════════════════════════════════════════════════════════════════════════
package com.k2fsa.sherpa.onnx

import android.content.res.AssetManager

/**
 * Silero VAD 模型配置。
 *
 * 字段完整性：`model` / `threshold` / `minSilenceDuration` / `minSpeechDuration` /
 * `windowSize` / `maxSpeechDuration` 六条全部命中 .so 无条件字段清单。
 *
 * @property model              模型路径（本工程传入 filesDir 下 silero_vad.onnx 绝对路径）
 * @property threshold          语音概率阈值（0..1），高于则判为语音
 * @property minSilenceDuration 判定「一句话结束」所需的最短静音时长（秒）
 * @property minSpeechDuration  判定「确为语音」所需的最短语音时长（秒）
 * @property windowSize         帧长（16kHz 下 silero v5 固定 512）
 * @property maxSpeechDuration  单段语音最长时长（秒），超过强制切分
 */
data class SileroVadModelConfig(
    var model: String = "",
    var threshold: Float = 0.5f,
    var minSilenceDuration: Float = 0.25f,
    var minSpeechDuration: Float = 0.25f,
    var windowSize: Int = 512,
    var maxSpeechDuration: Float = 5.0f,
)

/**
 * TEN VAD 模型配置（v1.9.1 新增；官方 Vad.kt 同名 data class）。
 *
 * ⚠️ 必要性：虽然本工程只加载 silero-vad，但 JNI 在构造 `VadModelConfig` 时
 *    会**无条件**读取 `tenVadModelConfig` 字段与 `TenVadModelConfig` 类描述符
 *    （.so 实测存在字符串 `Lcom/k2fsa/sherpa/onnx/TenVadModelConfig;`），
 *    故必须声明该字段与该类，否则 `Vad(...)` 构造抛异常。
 *
 * 字段与默认值逐字对齐官方 Vad.kt @ v1.12.21。
 *
 * @property model              ten-vad 模型路径（本工程不使用，保持空串）
 * @property threshold          语音概率阈值（0..1）
 * @property minSilenceDuration 最短静音时长（秒）
 * @property minSpeechDuration  最短语音时长（秒）
 * @property windowSize         ten-vad 帧长（16kHz 下官方默认 256）
 * @property maxSpeechDuration  单段语音最长时长（秒）
 */
data class TenVadModelConfig(
    var model: String = "",
    var threshold: Float = 0.5f,
    var minSilenceDuration: Float = 0.25f,
    var minSpeechDuration: Float = 0.25f,
    var windowSize: Int = 256,
    var maxSpeechDuration: Float = 5.0f,
)

/**
 * VAD 总配置。
 *
 * ⚠️ v1.9.1 修复：补齐 `tenVadModelConfig`（对象型字段，JNI 无条件读取）。
 * 字段完整性：`sileroVadModelConfig` / `tenVadModelConfig` / `sampleRate` /
 * `numThreads` / `provider` / `debug` 全部命中 .so 或以其类描述符被引用。
 * 声明顺序按官方 Vad.kt @ v1.12.21。
 */
data class VadModelConfig(
    var sileroVadModelConfig: SileroVadModelConfig = SileroVadModelConfig(),
    var tenVadModelConfig: TenVadModelConfig = TenVadModelConfig(),
    var sampleRate: Int = 16000,
    var numThreads: Int = 1,
    var provider: String = "cpu",
    var debug: Boolean = false,
)

/** VAD 切出的一段语音（start 为样本偏移，samples 为归一化波形）。 */
class SpeechSegment(val start: Int, val samples: FloatArray)

/**
 * Silero VAD 包装（file 路径加载，配合 ModelLoader 的完整性校验）。
 *
 * 本工程调用序列（端点检测）：
 * ```
 * vad.acceptWaveform(frame)      // 送入一帧
 * if (vad.isSpeechDetected()) …  // 是否处于语音中
 * if (!vad.empty()) {            // 端点到达，取一段完整语音
 *     val seg = vad.front(); vad.pop()
 * }
 * vad.reset() / vad.clear()      // 会话复位
 * ```
 */
class Vad(
    assetManager: AssetManager? = null,
    var config: VadModelConfig,
) {
    private var ptr: Long

    init {
        // ★ v1.9.1（任务 3）：异常透传。native 侧字段契约不符时抛的是
        //   RuntimeException("Failed to get field ID for tenVadModelConfig") 等，
        //   被 v1.9 的 generic catch 吞成「加载失败」，真机无法定位。
        //   此处把异常原文打进日志锚点 QJ-SV-ASR。
        try {
            ptr = if (assetManager != null) {
                newFromAsset(assetManager, config)
            } else {
                newFromFile(config)
            }
        } catch (t: Throwable) {
            android.util.Log.e(
                "QingjianIME",
                "QJ-SV-ASR Vad JNI construct FAILED: ${t.javaClass.name}: ${t.message}",
                t,
            )
            throw t
        }
        require(ptr != 0L) {
            "Invalid VadConfig: failed to create native Vad"
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

    /** 送入一帧音频（FloatArray）。 */
    fun acceptWaveform(samples: FloatArray) = acceptWaveform(ptr, samples)

    /** 当前帧的语音概率（0..1）。 */
    fun compute(samples: FloatArray): Float = compute(ptr, samples)

    /** 内部缓冲是否为空（为空表示尚无完整语音段可取）。 */
    fun empty(): Boolean = empty(ptr)

    fun pop() = pop(ptr)

    /** 取队首完整语音段（须先 [pop] 出队）。 */
    fun front(): SpeechSegment = front(ptr)

    /** 清空内部缓冲与状态机。 */
    fun clear() = clear(ptr)

    /** 本帧是否检测到语音。 */
    fun isSpeechDetected(): Boolean = isSpeechDetected(ptr)

    /** 复位 VAD 内部状态（保留配置）。 */
    fun reset() = reset(ptr)

    /** 冲刷尾部缓冲（录音结束时调用，取出最后一段）。 */
    fun flush() = flush(ptr)

    private external fun delete(ptr: Long)

    private external fun newFromAsset(
        assetManager: AssetManager,
        config: VadModelConfig,
    ): Long

    private external fun newFromFile(
        config: VadModelConfig,
    ): Long

    private external fun acceptWaveform(ptr: Long, samples: FloatArray)
    private external fun compute(ptr: Long, samples: FloatArray): Float

    private external fun empty(ptr: Long): Boolean
    private external fun pop(ptr: Long)
    private external fun clear(ptr: Long)
    private external fun front(ptr: Long): SpeechSegment
    private external fun isSpeechDetected(ptr: Long): Boolean
    private external fun reset(ptr: Long)
    private external fun flush(ptr: Long)

    companion object {
        init {
            System.loadLibrary("sherpa-onnx-jni")
        }
    }
}
