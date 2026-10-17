# 视记 ProGuard / R8 规则（release 启用混淆时使用）

# NewPipeExtractor 内嵌 Rhino JS 引擎，混淆时必须保留
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.tools.**
# Rhino 引用的桌面 JDK 类（javax.script / java.beans / jdk.dynalink）在 Android 上不存在，
# NewPipe 在 Android 上不走这些代码路径，仅屏蔽警告
-dontwarn javax.script.**
-dontwarn java.beans.**
-dontwarn jdk.dynalink.**

# ---------- M6：release 开启 minifyEnabled + shrinkResources 后的 keep 规则 ----------

# sherpa-onnx：JNI native 方法 + Kotlin API，禁止混淆
-keep class com.k2fsa.sherpa.onnx.** { *; }
-dontwarn com.k2fsa.sherpa.onnx.**

# M7a llama.cpp JNI：native 方法签名与类名必须与 C++ 侧一致，禁止混淆
-keep class com.zola.videonotes.data.llm.local.LlamaCpp { *; }
-keep class com.zola.videonotes.data.llm.local.LlamaCpp$TokenCallback { *; }

# NewPipeExtractor：反射与内部工具类
-keep class org.schabi.newpipe.extractor.** { *; }
-dontwarn org.schabi.newpipe.extractor.**

# WebView JS 接口：mermaid 高度回传桥接类（方法名被 JS 字符串调用）
-keep class com.zola.videonotes.ui.reader.MermaidBridge { *; }

# Gson 反序列化：B站 / 通用网络 DTO（字段名参与 JSON 映射）
-keep class com.zola.videonotes.data.network.** { *; }
-keepclassmembers class com.zola.videonotes.data.network.** {
    <fields>;
}

# Room / Hilt / WorkManager：各自 consumer-rules 已覆盖，此处仅兜底
-keep class androidx.room.RoomDatabase { *; }

# data class 的 copy/componentN/toString：R8 默认保留，无需额外规则
