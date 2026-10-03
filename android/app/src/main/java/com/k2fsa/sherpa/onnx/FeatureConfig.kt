// Copyright (c) 2023 Xiaomi Corporation
// Adapted for 青简输入法 v1.9 — 逐字段对齐 libsherpa-onnx-jni.so 的反射契约。
package com.k2fsa.sherpa.onnx

/**
 * 特征提取配置（JNI 侧通过反射读取字段 `sampleRate` / `featureDim` / `dither`）。
 *
 * ⚠️ 字段名 **必须** 与官方 `sherpa-onnx/kotlin-api/FeatureConfig.kt` 完全一致——
 *    JNI（`OfflineRecognizer_newFromFile`）用 `GetFieldID` 按名取值，名字错一个
 *    即 `NoSuchFieldException`，或静默取到默认值导致识别结果异常。
 *
 * 字段名确认方式：
 *   1. `nm -D libsherpa-onnx-jni.so` 确认导出符号；
 *   2. `strings -a libsherpa-onnx-jni.so | grep -E "^(sampleRate|featureDim)$"` 命中字段名；
 *   3. 与官方源码 `sherpa-onnx/kotlin-api/FeatureConfig.kt` 逐字段对照。
 */
data class FeatureConfig(
    var sampleRate: Int = 16000,
    var featureDim: Int = 80,
    var dither: Float = 0.0f,
)
