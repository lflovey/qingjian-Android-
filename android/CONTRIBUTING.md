# 贡献指南（Contributing）

感谢你愿意为青简输入法 Android 版做贡献。本文档说明参与开发的标准流程。

---

## 开始之前

- 请先阅读 [README.md](README.md) 了解项目架构与构建方式
- 提交前**确保能在本地构建通过**（见 [构建与验证](#构建与验证)）
- 有疑问先开 **Issue** 讨论，避免做了大量工作后发现方向不符

---

## 报告问题（Issue）

提 Issue 时请包含：

1. **设备信息**：机型、Android 版本、ROM（如 One UI / MIUI / 澎湃 OS）
2. **复现步骤**：尽可能明确的 1-2-3 步
3. **期望行为 vs 实际行为**
4. **日志**（如涉及语音/异常）：
   ```bash
   adb logcat -s QingjianIME
   ```
5. **截图/录屏**（如为 UI 问题）

---

## 开发流程

### 1. 分支

从主分支切出功能分支，命名建议：

| 类型 | 格式 | 示例 |
|---|---|---|
| 新功能 | `feat/<简述>` | `feat/night-mode` |
| 缺陷修复 | `fix/<简述>` | `fix/voice-panel-hide` |
| 文档 | `docs/<简述>` | `docs/build-guide` |
| 重构 | `refactor/<简述>` | `refactor/voice-engine` |

### 2. 提交信息

遵循 [Conventional Commits](https://www.conventionalcommits.org/) 风格：

```
<type>(<scope>): <subject>

[optional body]

[optional footer]
```

示例：

```
feat(voice): 支持按住说话时 VAD 分句依次上屏
fix(ime): onFinishInput 不再取消活跃的语音会话
docs(readme): 补充模型准备说明
```

类型（type）：`feat` / `fix` / `docs` / `style` / `refactor` / `perf` / `test` / `chore`

### 3. 代码规范

- **Kotlin**：遵循 [Kotlin 官方编码约定](https://kotlinlang.org/docs/coding-conventions.html)，4 空格缩进
- **命名**：类 `UpperCamelCase`，函数/变量 `lowerCamelCase`，常量 `UPPER_SNAKE_CASE`
- **注释**：中文注释，关键设计决策请说明「为什么」而非「做了什么」
- **最小变更**：一次提交只做一件事，避免夹带无关格式化改动

### 4. 构建与验证

提交前必须确认构建通过：

```bash
./gradlew clean assembleDebug
```

涉及语音改动的，请在真机上验证以下场景：

- [ ] 按住 🎤 持续说话 30 秒不松手 → 面板不提前收回
- [ ] 说话中停顿 → 分句依次上屏
- [ ] 松开 → 最后一段上屏并收面板
- [ ] 不说话快速按放 → 提示「未听到声音」
- [ ] 按住中按返回键 → 取消且不复活

### 5. 提交 PR

- PR 标题与提交信息风格一致
- 描述中说明：**改了什么 / 为什么 / 如何验证**
- 关联相关 Issue（如 `Closes #12`）
- 保持 PR 聚焦，大改动请拆分

---

## 关于语音模块

语音相关代码（`VoiceRecognizer.kt` / `VoiceInputPanelView.kt`）**对时序与竞态敏感**，修改时请特别留意：

1. **会话终结信号有且仅有一次**——终结信号只能由收口段触发
2. **采集线程不得被解码阻塞**——解码必须走独立执行器
3. **面板状态流转必须经统一入口**，便于日志追踪
4. 建议在 PR 描述中附上**竞态场景推演**说明（关键字段在各步骤的取值）

---

## 行为准则

- 尊重所有参与者，就事论事
- 欢迎建设性批评，避免人身攻击
- 提交的代码需为本人原创或已明确标注来源
