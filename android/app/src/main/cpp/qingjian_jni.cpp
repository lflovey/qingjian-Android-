/**
 * qingjian_jni.cpp —— 青简引擎 JNI shim（jstring 包装层）
 *
 * 背景：阶段1 交付的 libqingjian.so 是纯 C ABI（符号 native_*，无 JNIEnv），
 * 而 Kotlin `external fun` 绑定的是 JNI 符号（Java_<包名>_<类>_<方法>），
 * 两者不匹配，因此需要本 shim 做桥接（不动 Rust 引擎 / 不改 .so）。
 *
 * 内存方案「jstring 包装层」：
 *   引擎返回的 char* 在 native 侧立即包成 jstring，并立刻调用 native_free 释放，
 *   Kotlin 侧全程不碰裸指针、不调 native_free。每个 char* 严格只 free 一次（在本层）。
 *
 * 线程纪律：Kotlin 侧所有调用串行（IME 服务主线程；init 在一次性后台线程完成，
 * 之后不再有并发进入），引擎句柄自身带 Mutex，双保险。
 */
#include <jni.h>
#include <string>
#include <android/log.h>

#define TAG "QingjianIME"

extern "C" {  // ---- libqingjian.so 的纯 C ABI（见 JNI_SIGNATURES.md / JNI_SIGNATURES_V2.md）----
    int   native_init(const char* data_dir);
    void  native_destroy(void);
    char* native_pinyin_input(const char* keys);
    int   native_select(int index);
    void  native_clear(void);
    char* native_translate(const char* word);
    char* native_predict(const char* context);
    void  native_free(char* ptr);
    // ★ v0.4 新增（第 9 个符号）：运行时切换输入方式。
    //   mode: 0=全拼 1=双拼 2=五笔 3=英文；scheme: 双拼方案名（非双拼忽略，可传空串）。
    int   native_set_mode(int mode, const char* shuangpin_scheme);
}

namespace {

/** 取 jstring 的 UTF-8 内容；空指针安全（返回 ""）。 */
std::string jstr(JNIEnv* env, jstring s) {
    if (s == nullptr) return "";
    const char* p = env->GetStringUTFChars(s, nullptr);
    std::string out = (p != nullptr) ? p : "";
    if (p != nullptr) env->ReleaseStringUTFChars(s, p);
    return out;
}

/**
 * 调用一个返回 char* 的引擎函数，把结果包成 jstring 返回，并立即 native_free。
 * 引擎返回 nullptr 时按契约退化为 "[]"。
 */
jstring str_call(JNIEnv* env, char* (*fn)(const char*), const std::string& arg) {
    char* r = fn(arg.c_str());
    jstring js = env->NewStringUTF(r != nullptr ? r : "[]");
    if (r != nullptr) native_free(r);  // ★ 立即释放，且只释放这一次；Kotlin 不管指针
    return js;
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_qingjian_android_QingjianNative_nativeInit(JNIEnv* env, jclass /*clazz*/, jstring dir) {
    const std::string d = jstr(env, dir);
    const int r = native_init(d.c_str());
    __android_log_print(ANDROID_LOG_INFO, TAG, "native_init(%s) -> %d", d.c_str(), r);
    return r;
}

extern "C" JNIEXPORT void JNICALL
Java_com_qingjian_android_QingjianNative_nativeDestroy(JNIEnv* /*env*/, jclass /*clazz*/) {
    native_destroy();
    __android_log_print(ANDROID_LOG_INFO, TAG, "native_destroy done");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_qingjian_android_QingjianNative_nativePinyinInput(JNIEnv* env, jclass /*clazz*/, jstring keys) {
    return str_call(env, native_pinyin_input, jstr(env, keys));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_qingjian_android_QingjianNative_nativeSelect(JNIEnv* /*env*/, jclass /*clazz*/, jint index) {
    return native_select(index);
}

extern "C" JNIEXPORT void JNICALL
Java_com_qingjian_android_QingjianNative_nativeClear(JNIEnv* /*env*/, jclass /*clazz*/) {
    native_clear();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_qingjian_android_QingjianNative_nativeTranslate(JNIEnv* env, jclass /*clazz*/, jstring word) {
    return str_call(env, native_translate, jstr(env, word));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_qingjian_android_QingjianNative_nativePredict(JNIEnv* env, jclass /*clazz*/, jstring context) {
    return str_call(env, native_predict, jstr(env, context));
}

/**
 * ★ v0.4 新增：切换输入方式。
 * Kotlin 侧 `external fun nativeSetMode(mode: Int, scheme: String): Int`，
 * 这里把 jstring 方案名转成 C 字符串后转发给引擎；返回 1 成功 / 0 失败。
 * scheme 为空串时传 ""（引擎侧按"非双拼"忽略或退回默认双拼方案）。
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_qingjian_android_QingjianNative_nativeSetMode(JNIEnv* env, jclass /*clazz*/, jint mode, jstring scheme) {
    const std::string s = jstr(env, scheme);
    const int r = native_set_mode(static_cast<int>(mode), s.c_str());
    __android_log_print(ANDROID_LOG_INFO, TAG, "native_set_mode(%d, '%s') -> %d", static_cast<int>(mode), s.c_str(), r);
    return static_cast<jint>(r);
}
