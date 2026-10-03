# debug 构建不启用混淆；本文件仅为 release 占位。
# JNI 入口类必须保留（external fun 由 native 侧按 Java_ 符号名绑定）：
-keep class com.qingjian.android.QingjianNative { *; }
