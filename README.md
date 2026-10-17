# 视记（VideoNotes）

把 B站 / YouTube / 抖音 / 小红书视频一键变成带时间索引的结构化 Markdown 课堂笔记的 Android App。

## 功能

- **视频解析**：B站（BV/AV/番剧）、YouTube、抖音/小红书（元信息 + deep link）
- **字幕获取**：官方字幕优先；无字幕时端侧 ASR 兜底（whisper tiny / SenseVoice / whisper small，sherpa-onnx）
- **笔记生成**：云端 LLM（OpenAI 兼容 BYOK，SSE 流式）或本地大模型（llama.cpp + Qwen3，完全离线）
- **长文稿**：map-reduce 切分，时间戳质量门禁（±5s 校验 → 修正重试 → 剔除标注）
- **阅读导出**：自研 Compose Markdown 渲染（含 mermaid 知识地图）、SAF 导出 .md、分享、复制
- **后台任务**：转写/生成跑在 WorkManager，前台服务通知，可离开页面，杀进程可重连
- **站内播放器**：Media3，B站 DASH / YouTube progressive，时间 pill 点击站内 seek

## 环境要求

| 组件 | 要求 |
|---|---|
| JDK | 17（`tools/jdk-17`） |
| Android SDK | API 35/36，`ANDROID_HOME` 必须设置 |
| NDK | 30.x（本地 LLM 的 llama.cpp JNI 需要，CMake 3.22.1） |
| Gradle | 工程内 Wrapper 8.14.6 |

## 构建

```bash
# 一键构建 debug 包（自动导出环境变量，产物归档到 apps/videonotes/）
bash scripts/videonotes/build-videonotes.sh assembleDebug

# 一键构建 release 包（需 codes/keystores/videonotes-release.keystore，密码从同目录 .pass 文件读取）
bash scripts/videonotes/build-videonotes.sh assembleRelease
```

构建脚本按顺序定位 JDK（`tools/jdk-21` → `tools/jdk-17` → `$JAVA_HOME`），
设置 `ANDROID_HOME` / `GRADLE_USER_HOME` / 代理（`GRADLE_OPTS`），
执行 `gradle <task> --no-daemon`，产物复制到 `apps/videonotes/`：
- debug 包命名 `<项目名>-debug-<versionName>.apk`（仅保留最新 3 个）
- release 包命名 `<项目名>-release-<versionName>-arm64.apk`（仅保留最新 3 个，带 R8 混淆）

release 签名：`codes/keystores/videonotes-release.keystore`（chmod 600，仅本机保管，不进仓库），
密码存在同目录 `videonotes-release.pass`（chmod 600），构建时自动读取；也可通过环境变量
`RELEASE_STORE_PASSWORD` / `RELEASE_KEY_PASSWORD` 传入。

单元测试：

```bash
export JAVA_HOME=tools/jdk-17
export ANDROID_HOME=tools/android-sdk ANDROID_SDK_ROOT=tools/android-sdk
export GRADLE_USER_HOME=dependencies/gradle-home
export _JAVA_OPTIONS="-Djava.net.preferIPv4Stack=true"
# GRADLE_OPTS 需带代理 host/port（见脚本解析逻辑）
./gradlew :app:testDebugUnitTest --no-daemon
```

## 项目结构

```
codes/videonotes/
├── settings.gradle.kts / build.gradle.kts / gradle.properties
├── gradle/libs.versions.toml      # 全工程版本目录
└── app/
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        ├── cpp/llama_jni.cpp      # llama.cpp v0.7.0 JNI（本地 LLM）
        ├── java/com/zola/videonotes/
        │   ├── data/
        │   │   ├── llm/           # LlmEngine 抽象（云端/本地）、NoteGenerator、Chunker、门禁
        │   │   ├── llm/local/     # LlamaCpp JNI 桥、模型下载管理
        │   │   ├── asr/           # sherpa-onnx ASR、模型管理
        │   │   ├── work/          # TranscribeWorker/GenerateNoteWorker（WorkManager）
        │   │   └── local/         # Room（NoteEntity/VideoEntity/TranscriptSegment）
        │   ├── domain/            # VideoInfo、Parser 接口
        │   └── ui/
        │       ├── knowledge/     # 知识库主页
        │       ├── metadata/      # 视频详情（双 Tab）+ 实时日志面板
        │       ├── models/        # 统一模型管理（LLM/语音识别）
        │       ├── settings/      # 设置页
        │       ├── reader/        # Markdown 富渲染 + mermaid WebView
        │       └── player/        # Media3 播放器
        └── res/
```

## 技术栈

- Kotlin 2.3.21 / AGP 8.13.2 / KSP 2.3.12 / Hilt 2.57.2
- Compose M3 Expressive / Room / WorkManager / Media3 1.11.1
- llama.cpp v0.7.0（JNI，Qwen3 GGUF，arm64-v8a only）
- sherpa-onnx v1.13.8（ASR）、NewPipeExtractor v0.26.5（YouTube）
- minSdk 26 / targetSdk 35 / compileSdk 36

## 注意事项

- 本地 LLM 模型（Qwen3 2.5GB/1.1GB）走 HuggingFace 下载，**不**在仓库内
- ASR 模型同样按需下载，不在仓库内
- debug keystore 不在仓库内（`codes/keystores/`，chmod 600）
- API Key 存 EncryptedSharedPreferences，绝不硬编码
