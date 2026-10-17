// 视记（VideoNotes）工程配置
// 插件与依赖仓库：google / mavenCentral / gradlePluginPortal / jitpack，
// 模块内禁止私自声明其他仓库（repositoriesMode = FAIL_ON_PROJECT_REPOS）。
// jitpack 的存在理由：NewPipeExtractor（com.github.TeamNewPipe）仅通过 JitPack 发布，
// 不在 Maven Central。

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

// 沙箱 MITM 代理：从环境变量 http_proxy / https_proxy 解析，
// 映射为 Gradle 依赖下载使用的代理系统属性。
// 注意：JDK 自身的 HTTPS 证书校验，还需要把当前代理 CA 导入所用 JDK 的 cacerts，见 README。
fun applyProxyFromEnv() {
    val proxies = mapOf(
        "http" to (System.getenv("http_proxy") ?: System.getenv("HTTP_PROXY")),
        "https" to (System.getenv("https_proxy") ?: System.getenv("HTTPS_PROXY")),
    )
    for ((scheme, raw) in proxies) {
        if (raw.isNullOrBlank()) continue
        // 兼容 http://host:port 与 host:port 两种写法
        val noScheme = raw.substringAfter("://")
        val host = noScheme.substringBefore(":")
        val port = noScheme.substringAfter(":", "").substringBefore("/").toIntOrNull() ?: continue
        if (host.isBlank()) continue
        System.setProperty("$scheme.proxyHost", host)
        System.setProperty("$scheme.proxyPort", port.toString())
        println("已从环境变量配置 $scheme 代理：$host:$port")
    }
}
applyProxyFromEnv()

rootProject.name = "videonotes"
include(":app")
