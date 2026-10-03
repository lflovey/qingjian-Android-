package com.qingjian.android

import android.os.Bundle
import android.util.Log

/**
 * ★ 已废弃占位（DEPRECATED PLACEHOLDER）——v1.9 起旧版在线语音识别
 * （`android.speech.SpeechRecognizer`）已 **退役**。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * ⚠️ 诚实声明（v1.9.1 更正）
 * ═══════════════════════════════════════════════════════════════════════════
 *   本类 **_未_** 保留旧在线引擎的真实回退能力。v1.9 曾把本文件注释为
 *   「保留完整 RecognitionListener 实现，回退时零改动」，但实测其中**只有**
 *   [EmptyListener]（纯空实现基类）与 [logRetired]（日志桩），**没有**任何
 *   SpeechRecognizer 的创建 / 监听接线 / 错误映射实现。v1.9.1 按「如实标注」
 *   修正注释：本类现为**废弃占位**，仅保留一个可供未来参考的空监听基类与
 *   一条退役证据日志。
 *
 *   若需真正恢复在线识别，须**重新实现**创建/监听/错误映射逻辑（本工程内
 *   已无任何该实现的残留），非「零改动」。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * 当前架构（v1.9.1）
 * ═══════════════════════════════════════════════════════════════════════════
 *   语音入口（工具栏「🎤」）**只**走纯离线引擎 [VoiceRecognizer]
 *   （sherpa-onnx + SenseVoice-Small + Silero VAD）。[QingjianImeService] 内
 *   不持有任何 `SpeechRecognizer` 字段，两套逻辑在代码位置/状态字段/调用入口
 *   三个层面彻底隔离。
 *
 *   ⚠️ 本类**未被实例化**（dead code）。[logRetired] 仅由服务在创建时调用一次，
 *      用于在日志中留下「旧路径不再参与识别」的证据。
 */
@Suppress("unused", "DEPRECATION")
internal class LegacyOnlineRecognizer {

    /**
     * 退役版在线识别监听契约（与 `android.speech.RecognitionListener` 同签名）。
     * 保留仅为未来重新实现时的参考，**当前无任何实现接线**。
     */
    interface Listener : android.speech.RecognitionListener

    /**
     * 适配 `android.speech.RecognitionListener` 的**空实现**基类（供未来参考）。
     *
     * ⚠️ 注意：这是一个空壳 —— 使用它**不会**产生任何可用识别；恢复在线识别
     *    需要另行实现 SpeechRecognizer 的创建与接线。
     *
     * 参考用法（仅供未来重写时起步）：
     * ```
     * val r = android.speech.SpeechRecognizer.createSpeechRecognizer(ctx)
     * r.setRecognitionListener(object : LegacyOnlineRecognizer.EmptyListener() {
     *     override fun onResults(results: Bundle?) { /* 需另实现：上屏 */ }
     *     override fun onError(error: Int) { /* 需另实现：错误映射 */ }
     * })
     * ```
     */
    open class EmptyListener : android.speech.RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onError(error: Int) {}
        override fun onResults(results: Bundle?) {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    companion object {
        private const val TAG = "QingjianIME"

        /**
         * 记录一次「旧在线引擎已退役」的证据（仅日志）。
         * v1.9.1 运行期恒打印一次，用于证明「旧路径不再被调用」。
         */
        fun logRetired() {
            Log.i(
                TAG,
                "QJ-SV-MODEL legacy online SpeechRecognizer RETIRED since v1.9; " +
                    "voice path now = offline sherpa-onnx + SenseVoice (see VoiceRecognizer)"
            )
        }
    }
}
