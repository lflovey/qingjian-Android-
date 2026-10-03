// Copyright (c) 2023 Xiaomi Corporation
// Adapted for 青简输入法 v1.9.
package com.k2fsa.sherpa.onnx

/**
 * 一条离线识别流。
 *
 * 典型用法（本工程 VoiceRecognizer）：
 * ```
 * val stream = recognizer.createStream()
 * stream.acceptWaveform(samples /* FloatArray, 归一化到 [-1,1] */, 16000)
 * recognizer.decode(stream)
 * val text = recognizer.getResult(stream).text
 * stream.release()
 * ```
 *
 * ⚠️ `ptr` 必须为 `var` 且开放给同包：OfflineRecognizer.decode/getResult 依赖它。
 */
class OfflineStream(var ptr: Long) {
    init {
        require(ptr != 0L) { "Failed to create native OfflineStream" }
    }

    /** 送入一段音频波形（单声道、已归一化 float；sampleRate 必须与实际采样率一致）。 */
    fun acceptWaveform(samples: FloatArray, sampleRate: Int) =
        acceptWaveform(ptr, samples, sampleRate)

    protected fun finalize() {
        if (ptr != 0L) {
            delete(ptr)
            ptr = 0
        }
    }

    /** 释放 native 流句柄（幂等）。 */
    fun release() = finalize()

    private external fun acceptWaveform(ptr: Long, samples: FloatArray, sampleRate: Int)

    private external fun delete(ptr: Long)

    companion object {
        init {
            System.loadLibrary("sherpa-onnx-jni")
        }
    }
}
