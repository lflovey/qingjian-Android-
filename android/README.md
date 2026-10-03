# 青简输入法 · Android 版（Qingjian IME for Android）

> 一个**完全离线**的 Android 中文输入法：自研拼音 / 五笔引擎 + sherpa-onnx 本地语音识别，**零联网、零上传**。

青简输入法 Android 版是从同名桌面输入法项目移植而来的 Android 输入法（IME）。它把桌面端「简洁、克制、快」的设计理念带到移动端，并额外集成了**纯本地的语音转文字**能力——不依赖任何云服务，说话内容不出设备。

> 📦 本目录是 [青简单仓库多平台](../README.md) 的 Android 部分，可**独立构建**，与桌面版（`desktop/`）互不依赖。

---

## 目录

- [功能特性](#功能特性)
- [技术架构](#技术架构)
- [目录结构](#目录结构)
- [构建指南](#构建指南)
- [大文件分发（重要）](#大文件分发重要)
- [安装与使用](#安装与使用)
- [权限说明](#权限说明)
- [已知问题](#已知问题)
- [版本记录](#版本记录)
- [许可证](#许可证)

---

## 功能特性

### 输入

- **拼音输入**：全拼输入，候选词智能排序（自研引擎，Rust 实现）
- **五笔输入**：内置五笔 86 词库（`wubi86.tsv`）
- **英文 / 符号**：独立英文键盘与符号面板
- **中英混输**：无需反复切换

### 语音（离线）

- **按住说话**（微信式交互）：**按住 🎤 开始聆听，松开即识别上屏**
- **分句依次上屏**：说话过程中每个自然停顿（VAD 断句）后**立即上屏该句**，无需等全部说完
- **完全本地**：基于 [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) + SenseVoice-Small 模型，**语音数据不离开设备**
- **模型加载进度**：首次使用显示准备进度百分比（模型约 228 MB）

### 效率工具

- **剪贴板历史面板**：保留最近复制内容，点击即粘贴
- **光标移动面板**：精细移动输入光标
- **工具栏**：常用符号、快捷操作一屏可达
- **个性化设置页**：键盘高度、振动反馈等

---

## 技术架构

```
┌─────────────────────────────────────────────────────────┐
│                    Android 系统                          │
│  ┌───────────────────────────────────────────────────┐  │
│  │           QingjianImeService (Kotlin)             │  │
│  │  · 输入视图 / 候选栏 / 工具栏 / 各功能面板         │  │
│  │  · 语音面板四态状态机（IDLE/LOADING/LISTENING/    │  │
│  │    DECODING/ERROR）                                │  │
│  └──────────┬────────────────────────┬───────────────┘  │
│             │ JNI                    │ JNI              │
│  ┌──────────▼──────────┐  ┌──────────▼───────────────┐  │
│  │  libqingjian.so     │  │  libsherpa-onnx-jni.so   │  │
│  │  (Rust 自研引擎)     │  │  (sherpa-onnx 运行时)    │  │
│  │  · 拼音 / 五笔 字典  │  │  · Silero VAD（端点检测）│  │
│  │  · 候选词排序        │  │  · SenseVoice-Small ASR  │  │
│  └─────────────────────┘  └──────────────────────────┘  │
└─────────────────────────────────────────────────────────┘
```

- **UI / 输入法框架**：Kotlin，`InputMethodService` 实现
- **核心输入引擎**：Rust 编写，编译为 `libqingjian.so`，经 JNI 调用
- **语音识别**：sherpa-onnx（ONNX Runtime）跑 Silero VAD + SenseVoice-Small（int8 量化）
- **采集与解码解耦**：语音采集在独立线程，解码在单线程执行器（`qj-sv-decoder`）按序执行，避免长句解码阻塞采集导致丢音频

---

## 目录结构

```
android/
├── app/
│   ├── build.gradle                    # 应用模块构建配置（含 prepareOfflineModels 模型准备 task）
│   ├── proguard-rules.pro              # 混淆规则
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/
│       │   ├── qingjian-data/          # 自研引擎数据
│       │   │   ├── dict.qj             # 拼音主词典
│       │   │   ├── glossary-en.qj      # 英文词表（约 15 MB，不随仓库分发）
│       │   │   ├── glossary-zh.qj      # 中文词表
│       │   │   └── wubi86.tsv          # 五笔 86 词库
│       │   └── models/                 # 语音模型（大文件，不随仓库分发）
│       │       ├── model.int8.onnx     # SenseVoice-Small（int8，约 228 MB）
│       │       ├── tokens.txt          # 词表
│       │       └── silero_vad.onnx     # 语音端点检测
│       ├── cpp/
│       │   ├── CMakeLists.txt
│       │   └── qingjian_jni.cpp        # JNI 桥接
│       ├── java/com/qingjian/android/  # Kotlin 源码
│       │   ├── QingjianImeService.kt   # 输入法主服务（UI 与交互）
│       │   ├── VoiceRecognizer.kt      # 语音采集 + VAD + 解码
│       │   ├── VoiceInputPanelView.kt  # 语音面板（四态状态机）
│       │   ├── ModelLoader.kt          # 模型准备与进度回调
│       │   ├── QingjianNative.kt       # JNI 声明
│       │   ├── CandidatesView.kt       # 候选栏
│       │   ├── ClipboardHistoryManager.kt
│       │   ├── CursorModePanelView.kt
│       │   ├── SettingsActivity.kt
│       │   └── ...
│       ├── jniLibs/arm64-v8a/
│       │   ├── libqingjian.so          # 自研引擎（随仓库分发）
│       │   └── libsherpa-onnx-jni.so   # 第三方运行时（不随仓库分发）
│       └── res/                        # 布局 / 文案 / 尺寸
├── gradle/wrapper/
├── build.gradle
├── settings.gradle
├── gradle.properties
├── gradlew / gradlew.bat
├── README.md
└── CHANGELOG.md
```

> 📌 **模型目录名已统一为 `models/`**（早期版本为 `sherpa/`）。代码中 `ModelLoader.MODEL_DIR_NAME = "models"`，assets 与 `filesDir` 下均使用 `models/` 子目录。

---

## 构建指南

### 环境要求

| 项目 | 版本 |
|---|---|
| JDK | 17 或更高 |
| Android SDK | API 34（compileSdk） |
| Android NDK | 用于编译 `libqingjian.so` / `qingjian_jni`（27.2.12479018） |
| Gradle | 见 `gradle/wrapper/gradle-wrapper.properties`（工程自带 wrapper） |

### 步骤

```bash
# 1. 配置 SDK 路径
#    在 android/ 目录创建 local.properties：
echo "sdk.dir=/path/to/your/android-sdk" > local.properties

# 2. 准备大体积模型与第三方库（见「大文件分发」章节）

# 3. 构建 Debug APK
./gradlew assembleDebug

# 4. 产物位置
#    app/build/outputs/apk/debug/app-debug.apk
```

> **离线构建**：本工程依赖已缓存于本地时，可加 `--offline` 加速：
> `./gradlew assembleDebug --offline`

### 关于 Rust 原生库

本工程的原生库分两类，来源与可复现性**完全不同**，请务必区分：

| 库文件 | 大小 | 来源 | 第三方可否自行获取 | 是否入库 |
|---|---|---|---|---|
| `libqingjian.so` | 4.7 MB | **本移植版交叉编译产物**（青简自研引擎） | ❌ **不能**——必须依赖仓库内文件 | ✅ **已入库** |
| `libsherpa-onnx-jni.so` | 18 MB | sherpa-onnx 官方预编译（版本 `1.17.1`） | ✅ 官方 Release 可下载 | ❌ 未入库，见下文 |

工程已包含 `libqingjian.so`（arm64-v8a），**无需 Rust 工具链即可构建**。

#### ⚠️ 引擎编译链路尚未入库（已知缺口）

`libqingjian.so` 由**上游 `crates/` 的 Rust 源码为 Android 交叉编译**而来，其导出符号为纯 C ABI：

```
native_init / native_pinyin_input / native_select / native_set_mode
native_predict / native_translate / native_clear / native_destroy / native_free
opencc_create / opencc_convert / opencc_destroy / opencc_free_string
```

真正的 JNI 桥接层位于 `app/src/main/cpp/qingjian_jni.cpp`（导出 `Java_com_qingjian_android_QingjianNative_*`），该文件**已入库**，随 Gradle 构建自动编译。

**但以下内容目前未记录在仓库中，属于已知缺口**：

- `libqingjian.so` 的 Android 交叉编译流程（NDK 版本、`cargo-ndk` / target 配置、编译参数）
- 上游 `desktop/` 与 `crates/` 中**没有 Android 平台实现**（`crates/qingjian-platform/` 仅含通用模块，`Cargo.toml` 无 `aarch64-linux-android` target，`tools/` 无 Android 打包工具）

**这对使用者的实际影响**：

- ✅ **能构建**：克隆仓库后可直接 `./gradlew assembleDebug`，产物 APK 功能完整
- ✅ **能改 Kotlin / JNI 桥接层**：`QingjianImeService.kt`、`qingjian_jni.cpp` 等均在仓库内
- ❌ **不能改 Rust 引擎**：若需修改拼音算法、候选排序、五笔逻辑等 `crates/` 内的实现，**必须自行搭建 NDK 交叉编译环境**重新编译 `libqingjian.so` 并替换 `app/src/main/jniLibs/arm64-v8a/` 下的文件

> 📌 **待补**：完整的 Android 交叉编译脚本（如 `build-native.sh`）与工具链版本说明**尚未整理入仓**，欢迎熟悉 Rust Android 交叉编译的贡献者协助补全，参见 [CONTRIBUTING.md](CONTRIBUTING.md)。

### 预构建模型校验（`prepareOfflineModels`）

`app/build.gradle` 内置了一个 Gradle task `prepareOfflineModels`，挂钩到 `preBuild`，在预构建阶段校验 `app/src/main/assets/models/` 下模型是否就绪：

- **模型已存在且体积合理** → 直接通过，**不阻塞构建**（本地有模型时 `./gradlew assembleDebug` 正常跑通）；
- **模型缺失**：尝试从外部链接下载并解压（`MODEL_DOWNLOAD_URL`）；
- **链接未配置 / 下载失败** → 抛出清晰的构建错误，提示放置路径与配置项：

  ```
  缺少模型文件：app/src/main/assets/models/model.int8.onnx
  请按 android/README.md 指引放入模型，或配置 build.gradle 中的 MODEL_DOWNLOAD_URL
  ```

> ⚠️ `MODEL_DOWNLOAD_URL` 目前为**占位符（待配置）**，尚无稳定的模型分发地址。请按下文「大文件分发」章节手动放置模型，或在获得分发地址后填入该常量。

---

## 大文件分发（重要）

为控制仓库体积，以下大体积二进制**不随 Git 仓库分发**，需按本节指引自行获取。

**重要区分**：下表「来源」列标明各文件是**第三方公开产物**还是**本项目自研产物**——前者可从公开渠道自由获取，后者则必须依赖本仓库或自行编译。

| 文件 | 大小 | 来源 | 是否随仓库分发 | 获取方式 |
|---|---|---|---|---|
| `app/src/main/assets/models/model.int8.onnx` | 约 228 MB | 第三方（sherpa-onnx 官方模型） | ❌ | [SenseVoice-Small 官方模型包](#1-sensevoice-small-语音模型) |
| `app/src/main/jniLibs/arm64-v8a/libsherpa-onnx-jni.so` | 约 18 MB | 第三方（sherpa-onnx 官方预编译 `1.17.1`） | ❌ | [sherpa-onnx 官方预编译产物](#2-libsherpa-onnx-jniso) |
| `app/src/main/assets/qingjian-data/glossary-en.qj` | 约 15 MB | 本项目（由上游 TSV 转换） | ❌ | [由 desktop/ 下 TSV 复现生成](#4-英文词表-glossary-enqj) |
| `app/src/main/jniLibs/arm64-v8a/libqingjian.so` | 约 4.7 MB | **本项目自研**（Rust 交叉编译产物） | ✅ **必须随仓库** | 已随仓库提供，[编译链路见上文](#-引擎编译链路尚未入库已知缺口) |
| `app/src/main/assets/models/silero_vad.onnx` | 约 1.7 MB | 第三方（Silero） | ✅ | 已随仓库提供，也可[重新获取](#3-silero-vad) |
| `app/src/main/assets/models/tokens.txt` | 约 0.3 MB | 第三方（随模型包） | ✅ | 随模型包提供 |

### 语音模型（SenseVoice-Small）

> 本项目运行需要 SenseVoice-Small 语音模型（约 228MB），该模型未随 Git 仓库分发。请从外部渠道（如 GitHub Release 附件、阿里云盘或百度网盘）下载模型压缩包，解压后放入 `android/app/src/main/assets/models/` 目录下。

> 📌 **分发链接状态：待配置**。模型分发地址尚未确定，确定后将同步填入根 `app/build.gradle` 的 `MODEL_DOWNLOAD_URL` 常量（当前为占位符 `// TODO(待配置): 替换为模型分发地址`）。在地址确定前，请使用下方的**官方直链**作为备选来源。

#### 1. SenseVoice-Small 语音模型

从 sherpa-onnx 官方发布页下载 **SenseVoice-Small 量化模型包**，解压后取出 `model.int8.onnx` 与 `tokens.txt` 放入 `app/src/main/assets/models/`。

**模型包（int8 量化，推荐）**

| 项目 | 值 |
|---|---|
| 文件名 | `sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2` |
| 大小 | 163,002,883 B（约 155 MB） |
| 直链 | `https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2` |

一键下载：

```bash
# 下载模型包
wget https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2

# 解压
tar xjf sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2

# 取出模型与词表，放置到工程目录（注意：目录名为 models）
cp sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17/model.int8.onnx \
   app/src/main/assets/models/model.int8.onnx
cp sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17/tokens.txt \
   app/src/main/assets/models/tokens.txt
```

> 📌 模型支持语言：中文（zh）/ 英文（en）/ 日语（ja）/ 韩语（ko）/ 粤语（yue）。
> 上述地址取自 [k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) 官方 Release（`asr-models` 标签），已验证可访问。
> 若需非量化版本（`model.onnx`，体积约 895 MB），请改用 `sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17.tar.bz2`（1,047,870,769 B）。

#### 2. `libsherpa-onnx-jni.so`

`app/src/main/jniLibs/arm64-v8a/libsherpa-onnx-jni.so` 是 **sherpa-onnx 官方预编译的第三方 JNI 运行时**（约 18 MB），出于仓库体积控制未纳入版本控制。

> ✅ **该文件非本项目编译产物**，可从公开渠道直接获取，不存在「拉不到」的问题。
> 经 `strings` 核对，本工程使用的版本为 **sherpa-onnx `1.17.1`**。

获取方式：

- 从 [k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) 官方 Release 或 `sherpa-onnx` 的 Android 预编译包（如 `sherpa-onnx-v1.17.1-android.tar.bz2` 一类命名）中提取 `arm64-v8a/libsherpa-onnx-jni.so`，放置到 `app/src/main/jniLibs/arm64-v8a/`；
- 或基于同一版本自行编译 Android arm64-v8a 产物。

自查当前所需版本号（下载对应版本，避免契约错配）：

```bash
strings -a libsherpa-onnx-jni.so | grep -E '^1\.[0-9]+\.[0-9]+$' | head -3
```

> ⚠️ 该 `.so` 与 `app/src/main/java/com/k2fsa/sherpa/onnx/` 下的 JNI 声明**版本必须对齐**（字段契约），否则运行时会抛 `Failed to get field ID for <name>`。版本核对方法见 `CHANGELOG.md` v1.9.1。

#### 3. Silero VAD

Silero VAD 模型（语音端点检测）体积很小（约 1.7 MB），**已随仓库提供**于 `app/src/main/assets/models/silero_vad.onnx`；如需重新获取：

```bash
wget https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx
# 放置到 app/src/main/assets/models/silero_vad.onnx
```

#### 4. 英文词表 `glossary-en.qj`

`app/src/main/assets/qingjian-data/glossary-en.qj`（约 15 MB）体积较大，未随仓库分发。

> ✅ **该文件非本项目编译产物**，可由上游 TSV 数据复现生成。

`.qj` 是青简自有的二进制词典格式（文件头魔数 `QINGJIAN`），由上游工具 **`desktop/tools/dict-convert`** 从 TSV 转换而来。上游原始数据位于：

| 目标文件（Android） | 上游源数据（desktop/） | 大小 |
|---|---|---|
| `glossary-en.qj` | `desktop/assets/glossary/glossary-en.tsv` | 6.5 MB |
| `glossary-zh.qj` | `desktop/assets/glossary/glossary-zh.tsv` | 0.98 MB |

因此第三方完全可以从本仓库 `desktop/` 中取得 TSV，再用 `tools/dict-convert` 自行生成 `.qj` 文件。`dict.qj` / `glossary-zh.qj` / `wubi86.tsv` 体积较小，已随仓库提供。

---

## 安装与使用

### 安装

1. 安装 `app-debug.apk`（首次安装需在系统设置中允许「安装未知来源应用」）
2. 打开系统 **设置 → 语言和输入法 → 虚拟键盘 → 管理键盘**
3. 启用 **青简输入法**
4. 在任意输入框中切换到青简输入法

### 语音输入用法

| 手势 | 行为 |
|---|---|
| **按住 🎤** | 开始聆听（面板显示「请说话，松开结束」） |
| 说话中停顿 | 该句**立即上屏**，继续按住可继续说下一句 |
| **松开 🎤** | 补上最后一段并收起面板 |
| 按住后滑出 / 按返回 | 取消本次输入，不上屏 |

**首次使用语音**：需要准备离线模型（约 228 MB），面板会显示准备进度百分比。准备完成后按住即可使用。此后无需再等待。

---

## 权限说明

应用**仅**申请一项权限：

| 权限 | 用途 |
|---|---|
| `RECORD_AUDIO` | 语音转文字。**音频仅在设备本地处理，不上传任何服务器。** |

青简输入法**没有网络权限**——它无法联网，这是设计上的隐私保证。

---

## 已知问题

- **语音引擎加载不可中断**：模型首次准备（拷贝 + 初始化）期间无法中途取消，需等待完成
- **候选词气泡去抖**：快速输入时候选气泡刷新存在轻微抖动，待优化
- **仅提供 arm64-v8a**：当前 `jniLibs` 与模型仅适配 64 位 ARM 设备

---

## 版本记录

| 版本 | 变更摘要 |
|---|---|
| **v1.12** | 修复「按住说话时话没说完面板自动收回」：`onFinishInput` 不再无差别取消活跃语音会话；音频读取异常不再静默冒充「松手收口」 |
| **v1.11** | 🎤 改为**按住说话**交互；支持 **VAD 分句依次上屏**；采集/解码解耦防丢音频 |
| **v1.10** | 语音面板升级为**四态状态机**（加载中/倾听中/识别中/错误），模型加载显示百分比进度 |
| **v1.9.1** | 修复 JNI 字段契约缺陷（补齐 sherpa-onnx JNI 无条件读取的 13 个缺失字段） |
| **v1.9** | 集成 sherpa-onnx + SenseVoice-Small 全本地语音识别 |

详见 [CHANGELOG.md](CHANGELOG.md)。

---

## 许可证

本项目采用 **[GPL-3.0-or-later](../LICENSE)** 许可（与上游桌面版一致）。Android 版使用了多个第三方组件，各自遵循其原有许可证：

| 组件 | 许可证 | 说明 |
|---|---|---|
| [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) | Apache-2.0 | 语音识别运行时 |
| [ONNX Runtime](https://github.com/microsoft/onnxruntime) | MIT | 推理引擎 |
| SenseVoice-Small 模型 | 见模型发布页 | 语音识别模型 |
| [Silero VAD](https://github.com/snakers4/silero-vad) | MIT | 语音端点检测 |
| AndroidX | Apache-2.0 | Android 支持库 |

---

## 贡献

欢迎提交 Issue 与 Pull Request，请先阅读 [CONTRIBUTING.md](CONTRIBUTING.md)。
