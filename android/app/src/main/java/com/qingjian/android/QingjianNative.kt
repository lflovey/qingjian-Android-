package com.qingjian.android

import android.util.Log

/** 全局日志 tag（需求单：全用 QingjianIME） */
internal const val TAG = "QingjianIME"

/**
 * 青简引擎 JNI 绑定（camelCase external fun，全部返回 String/Int/Unit，无裸指针）。
 *
 * JNI 符号由 external 方法名决定（shim 内为
 * `Java_com_qingjian_android_QingjianNative_nativeInit` 等，见 qingjian_jni.cpp）。
 * 因此 **external 方法名不可改**，包装层在同名约束下无法再叠一层同名函数。
 * v0.2 的异常保护放在调用点（QingjianImeService.refreshCandidates / showTranslation
 * 均已 try/catch 并 `Log.e(..., t)` 带栈），本层保持与 v0.1 完全一致的签名契约。
 *
 * 内存所有权：引擎返回的 char* 已在 shim 内包成 jstring 并立即 native_free，
 * 本层与上层全程不碰裸指针、不调 native_free。
 *
 * 线程纪律：所有 external 调用必须串行 —— nativeInit 在一次性后台线程完成，
 * 其余调用只发生在 IME 服务主线程（引擎句柄自身带 Mutex，双保险）。
 */
object QingjianNative {

    /** native 库是否加载成功；false 时上层必须跳过一切 external 调用 */
    val libraryLoaded: Boolean = try {
        System.loadLibrary("qingjianjni")
        Log.i(TAG, "libqingjianjni loaded (libqingjian.so linked via DT_NEEDED)")
        true
    } catch (e: UnsatisfiedLinkError) {
        Log.e(TAG, "loadLibrary(qingjianjni) failed: ${e.message}")
        false
    }

    /** 初始化引擎；dataDir 为已解压 assets 的绝对路径；返回 1 成功 / 0 失败 */
    external fun nativeInit(dataDir: String): Int

    /** 释放引擎句柄；重复调用安全 */
    external fun nativeDestroy()

    /** 拼音输入；返回候选 JSON 数组字符串（word/score/gloss/pos/syllables/kind） */
    external fun nativePinyinInput(keys: String): String

    /** 选中候选（触发学习）；返回 1 成功 / 0 越界或未初始化 */
    external fun nativeSelect(index: Int): Int

    /** 清空拼音缓冲区与最近候选缓存 */
    external fun nativeClear()

    /** 查词翻译；返回 {"word":..., "en":[...], "zh":[...]} JSON 对象字符串 */
    external fun nativeTranslate(word: String): String

    /** 联想预测；当前阶段恒返回 {"predictions":[]}（UI 不依赖） */
    external fun nativePredict(context: String): String

    /**
     * ★ v0.4 新增：运行时切换输入方式。
     *
     * @param mode 0=全拼 1=双拼 2=五笔 3=英文（与 Kotlin `InputMode` 的 nativeCode 对应）
     * @param scheme 双拼方案名（"xiaohe"/"ziranma"/…）；非双拼可传空串
     * @return 1 成功 / 0 失败（引擎未初始化、五笔码表缺失等）
     *
     * 契约：切换只改引擎侧方案装配，**不**动缓冲；调用方（QingjianImeService）负责清空拼音缓冲。
     * 原有的 8 个 external 方法签名与语义一律不变（回归红线）。
     */
    external fun nativeSetMode(mode: Int, scheme: String): Int
}
