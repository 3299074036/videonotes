package com.zola.videonotes.data.llm

/**
 * LLM 服务预设：OpenAI 兼容接口的快捷 base_url。
 * 用户也可以手动填写任意兼容接口地址。
 */
object LlmPresets {

    data class Preset(val name: String, val baseUrl: String)

    const val DEFAULT_BASE_URL = "https://api.openai.com/v1"

    val ALL = listOf(
        Preset("OpenAI", "https://api.openai.com/v1"),
        Preset("DeepSeek", "https://api.deepseek.com/v1"),
        Preset("硅基流动", "https://api.siliconflow.cn/v1"),
    )

    /** 模型输入框的占位提示（不做任何预设，由用户填写）。 */
    const val MODEL_HINT = "deepseek-chat"
}
