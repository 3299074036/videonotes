// 根构建脚本：只声明插件版本（见 gradle/libs.versions.toml），
// 插件的实际应用在各模块的 build.gradle.kts 中完成。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
}
