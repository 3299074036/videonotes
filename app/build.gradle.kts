plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.zola.videonotes"
    // compileSdk 36：Coil 3.5.0 的 AAR metadata 要求 >= 36；targetSdk 保持 35 以减少运行时行为变更
    compileSdk = 36
    // NDK：与 tools/android-sdk/ndk 下实际安装版本对齐（M7a llama.cpp JNI 用）
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "com.zola.videonotes"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // 项目约定 arm64 only：x86_64 仅模拟器调试用，不打进包
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    // M7a：llama.cpp JNI（app/src/main/cpp，产物 libvideonotes_llama.so）
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // 调试包使用项目固定 debug keystore（codes/keystores/videonotes-debug.keystore，
    // 密码 android/android，仅开发调试用，禁止用于发布）。keystore 不存在时回退默认调试签名。
    // 注意：rootProject 目录即 codes/videonotes，因此用 ../keystores 定位到 codes/keystores。
    val debugKeystore = rootProject.file("../keystores/videonotes-debug.keystore")
    signingConfigs {
        if (debugKeystore.exists()) {
            create("videonotesDebug") {
                storeFile = debugKeystore
                storePassword = "android"
                keyAlias = "videonotes-debug"
                keyPassword = "android"
            }
        }
        // 发布签名模板（默认禁用）：不要把 keystore 与密码提交到仓库。
        // 1. 按 docs/research/videonotes/发布检查清单.md 用 keytool 生成
        //    codes/keystores/videonotes-release.keystore（chmod 600，仅本机保管）；
        // 2. 取消下面注释；
        // 3. 构建时通过环境变量传入密码（不要写在 gradle 文件里）：
        //      RELEASE_STORE_PASSWORD=... RELEASE_KEY_PASSWORD=... ./gradlew assembleRelease
        // create("videonotesRelease") {
        //     storeFile = rootProject.file("../keystores/videonotes-release.keystore")
        //     storePassword = System.getenv("RELEASE_STORE_PASSWORD")
        //     keyAlias = "videonotes"
        //     keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
        // }
    }

    buildTypes {
        debug {
            signingConfigs.findByName("videonotesDebug")?.let { signingConfig = it }
        }
        release {
            // M6 发布准备：代码与资源压缩。proguard-rules.pro 中 keep 了
            // sherpa-onnx / NewPipeExtractor / WebView JS 接口等反射与 JNI 相关类。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // 发布签名：默认注释掉。按 docs/research/videonotes/发布检查清单.md 生成
            // codes/keystores/videonotes-release.keystore 后，取消 signingConfigs.release
            // 的注释，并通过环境变量传入密码再启用下行：
            // signingConfig = signingConfigs.getByName("videonotesRelease")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // NewPipeExtractor 在 minSdk < 33 的设备上需要核心库脱糖
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    // Compose BOM 统一管理版本（含 material3 1.4.0，即 M3 Expressive 稳定版）
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // Hilt 依赖注入
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // Room（M1 仅接入依赖，实体与 DAO 在 M2/M3 落地）
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)

    // JVM 单元测试
    testImplementation(libs.junit)

    // 网络：Retrofit + OkHttp
    implementation(libs.retrofit.core)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp.core)
    implementation(libs.okhttp.logging)
    implementation(libs.gson)

    // 图片加载
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // YouTube 视频解析
    implementation(libs.newpipe.extractor)

    // 端侧 ASR：sherpa-onnx 官方 AAR（已内置 onnxruntime，禁止再引入 onnxruntime-android）
    // AAR 由构建脚本从 GitHub Releases 拉取到 workspace 级 dependencies/sherpa-onnx/，不进 git
    implementation(files(rootProject.file("../../dependencies/sherpa-onnx/sherpa-onnx-1.13.8.aar")))

    // DataStore：ASR 模型选择 / 仅 Wi-Fi 下载等偏好设置
    implementation(libs.androidx.datastore.preferences)

    // EncryptedSharedPreferences：LLM API Key 加密存储（minSdk 26 可用，随应用卸载清除）
    implementation(libs.androidx.security.crypto)

    // M6：站内播放器（Media3 ExoPlayer + UI 控件 + HTTP 数据源）
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.datasource)

    // M6：长任务统一（WorkManager + Hilt Worker 注入）
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.hilt.compiler)

    // 核心库脱糖（minSdk 26 运行 NewPipeExtractor 所需）
    coreLibraryDesugaring(libs.desugar.jdk.libs.nio)
}
