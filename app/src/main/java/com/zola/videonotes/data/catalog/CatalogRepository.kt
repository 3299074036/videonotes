package com.zola.videonotes.data.catalog

import android.content.Context
import com.zola.videonotes.data.llm.local.LocalLlmModel
import com.zola.videonotes.domain.asr.AsrModel
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 精选层目录仓库（WP1）：解析 assets/model_catalog.json，提供正式集 / 影子集分离查询。
 *
 * 解析失败或未初始化时回退到代码内种子（[SeedCatalog]，即目录化前的写死 6 条，
 * 行为等同旧版，保证 assets 损坏不砖化）。未知 templateId / kind 的条目跳过。
 */
object CatalogStore {

    @Volatile
    private var entries: List<CatalogEntry>? = null

    /** 幂等初始化：任意 Context 首次调用时解析 assets。 */
    fun init(context: Context) {
        if (entries != null) return
        synchronized(this) {
            if (entries != null) return
            entries = runCatching { parse(context) }.getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?: SeedCatalog.entries
        }
    }

    /** 全部条目（正式 + 影子）；未初始化时为种子。 */
    fun all(): List<CatalogEntry> = entries ?: SeedCatalog.entries

    fun llmCurated(): List<CatalogEntry> =
        all().filter { it.kind == CatalogKind.LLM && it.source == CatalogSource.CURATED }

    fun llmShadow(): List<CatalogEntry> =
        all().filter { it.kind == CatalogKind.LLM && it.source == CatalogSource.SHADOW }

    fun asrCurated(): List<CatalogEntry> =
        all().filter { it.kind == CatalogKind.ASR && it.source == CatalogSource.CURATED }

    /** LLM 解析域：正式 → 影子（动态注册表由 LocalLlmPreferences 在其后接续）。 */
    fun llmById(id: String): CatalogEntry? =
        all().firstOrNull { it.kind == CatalogKind.LLM && it.id == id }

    fun asrById(id: String): CatalogEntry? =
        all().firstOrNull { it.kind == CatalogKind.ASR && it.id == id }

    private val KNOWN_TEMPLATES = setOf(
        "gguf-qwen3-chat", "gguf-llama3-chat", "gguf-embedded",
        "gguf-named-hunyuan-dense", "gguf-named-glmedge",
        "sherpa-whisper", "sherpa-sensevoice", "sherpa-paraformer",
        "sherpa-zipformer-ctc", "sherpa-transducer", "sherpa-dolphin",
    )

    private fun parse(context: Context): List<CatalogEntry> {
        val text = context.assets.open("model_catalog.json").bufferedReader().use { it.readText() }
        val root = JSONObject(text)
        val arr = root.getJSONArray("models")
        val out = mutableListOf<CatalogEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val templateId = o.optString("templateId")
            if (templateId !in KNOWN_TEMPLATES) continue
            val kind = runCatching { CatalogKind.valueOf(o.optString("kind")) }.getOrNull() ?: continue
            val filesArr = o.getJSONArray("files")
            val files = (0 until filesArr.length()).map { j ->
                val f = filesArr.getJSONObject(j)
                CatalogFile(
                    name = f.getString("name"),
                    url = f.getString("url"),
                    sizeBytes = f.getLong("sizeBytes"),
                    sha256 = f.optString("sha256").takeIf { it.isNotBlank() },
                )
            }
            out += CatalogEntry(
                id = o.getString("id"),
                kind = kind,
                displayName = o.optString("displayName"),
                description = o.optString("description"),
                originalName = o.optString("originalName").takeIf { it.isNotBlank() },
                useCases = o.optJSONArray("useCases")?.let { arr ->
                    (0 until arr.length()).map { k -> arr.optString(k) }
                }.orEmpty().filter { it.isNotBlank() },
                templateId = templateId,
                files = files,
                recommended = o.optBoolean("recommended", false),
                smallModelPrompt = o.optBoolean("smallModelPrompt", false),
                downloadCount = if (o.isNull("downloadCount")) null else o.optLong("downloadCount"),
                minRamGB = if (o.isNull("minRamGB")) null else o.optDouble("minRamGB"),
                source = if (o.optBoolean("shadow", false)) {
                    CatalogSource.SHADOW
                } else {
                    CatalogSource.CURATED
                },
                origin = o.optString("origin").takeIf { it.isNotBlank() },
            )
        }
        return out
    }
}

/** Hilt 入口：构造时确保 CatalogStore 已初始化，查询全部委托给它。 */
@Singleton
class CatalogRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    init {
        CatalogStore.init(context)
    }

    fun llmCurated(): List<CatalogEntry> = CatalogStore.llmCurated()
    fun llmShadow(): List<CatalogEntry> = CatalogStore.llmShadow()
    fun asrCurated(): List<CatalogEntry> = CatalogStore.asrCurated()
    fun llmById(id: String): CatalogEntry? = CatalogStore.llmById(id)
    fun asrById(id: String): CatalogEntry? = CatalogStore.asrById(id)
}

/** 条目 → 旧 LLM 模型形态（引擎/下载链路的既有数据类）。 */
fun CatalogEntry.toLocalLlmModel(): LocalLlmModel {
    val f = files.first()
    return LocalLlmModel(
        id = id,
        displayName = displayName,
        fileName = f.name,
        url = f.url,
        sizeBytes = f.sizeBytes,
        sha256 = f.sha256,
        description = description,
        promptTemplate = promptTemplate,
        source = source,
        hfRepo = hfRepo,
        chatTemplateName = namedTemplate,
        requiresProbe = requiresProbe,
    )
}

/** 条目 → 旧 ASR 模型形态（识别/下载链路的既有数据类）。 */
fun CatalogEntry.toAsrModel(): AsrModel = AsrModel(
    id = id,
    displayName = displayName,
    description = description,
    totalBytes = totalBytes,
    files = files.map {
        com.zola.videonotes.domain.asr.AsrModelFile(
            name = it.name,
            url = it.url,
            sizeBytes = it.sizeBytes,
            sha256 = it.sha256,
        )
    },
    engineKind = when (templateId) {
        "sherpa-sensevoice" -> AsrModel.EngineKind.SENSE_VOICE
        "sherpa-paraformer" -> AsrModel.EngineKind.PARAFORMER
        "sherpa-zipformer-ctc" -> AsrModel.EngineKind.ZIPFORMER_CTC
        "sherpa-transducer" -> AsrModel.EngineKind.TRANSDUCER
        "sherpa-dolphin" -> AsrModel.EngineKind.DOLPHIN
        else -> AsrModel.EngineKind.WHISPER
    },
)

/**
 * 种子兜底：与 assets/model_catalog.json 逐字一致的写死条目（含已核 sha256），
 * 仅在 assets 清单解析失败时启用。2026-10-24 国内名单定版后 LLM 精选 13 + 影子 1 + ASR 5。
 */
internal object SeedCatalog {

    private const val HF = "https://huggingface.co"
    private const val MS = "https://modelscope.cn/models"

    val entries: List<CatalogEntry> = listOf(
        CatalogEntry(
            id = "qwen3-4b", kind = CatalogKind.LLM, displayName = "通义千问 4B",
            description = "适合：中文网课，质量最好。一篇笔记约 3–6 分钟。",
            templateId = "gguf-qwen3-chat", recommended = true, minRamGB = 6.0,
            files = listOf(
                CatalogFile(
                    "Qwen3-4B-Instruct-2507-Q4_K_M.gguf",
                    "$MS/unsloth/Qwen3-4B-Instruct-2507-GGUF/resolve/master/Qwen3-4B-Instruct-2507-Q4_K_M.gguf",
                    2_497_281_120L,
                    "3605803b982cb64aead44f6c1b2ae36e3acdb41d8e46c8a94c6533bc4c67e597",
                ),
            ),
        ),
        CatalogEntry(
            id = "qwen3-1_7b", kind = CatalogKind.LLM, displayName = "通义千问 1.7B",
            description = "适合：旧手机、求快。速度快约一倍，复杂长文稿质量略低。",
            templateId = "gguf-qwen3-chat", smallModelPrompt = true, minRamGB = 4.0,
            files = listOf(
                CatalogFile(
                    "Qwen3-1.7B-Q4_K_M.gguf",
                    "$HF/unsloth/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf",
                    1_107_409_472L,
                    "b139949c5bd74937ad8ed8c8cf3d9ffb1e99c866c823204dc42c0d91fa181897",
                ),
            ),
        ),
        CatalogEntry(
            id = "llama3_2-1b", kind = CatalogKind.LLM, displayName = "Llama 3.2 1B",
            description = "诊断对照用：排查 prefill hang 是否为 Qwen 特定问题。体积小、速度快，不适合正式笔记生成",
            templateId = "gguf-llama3-chat", smallModelPrompt = true,
            source = CatalogSource.SHADOW, minRamGB = 3.0,
            files = listOf(
                CatalogFile(
                    "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
                    "$HF/unsloth/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf",
                    807_694_368L,
                    "3f5a22426976ab26cfe84dba63c1d08391717abb1af893e10f1b2968d862dcc1",
                ),
            ),
        ),
        CatalogEntry(
            id = "qwen3-8b", kind = CatalogKind.LLM, displayName = "通义千问 8B",
            description = "适合：大内存手机、长文整理。本目录综合能力最强，中文与长文整理俱佳，生成较慢；体积接近 5 GiB 上限。",
            templateId = "gguf-qwen3-chat", minRamGB = 9.0, origin = "official",
            files = listOf(
                CatalogFile(
                    "Qwen3-8B-Q4_K_M.gguf",
                    "$MS/Qwen/Qwen3-8B-GGUF/resolve/master/Qwen3-8B-Q4_K_M.gguf",
                    5_027_783_488L,
                    "d98cdcbd03e17ce47681435b5150e34c1417f50b5c0019dd560e4882c5745785",
                ),
            ),
        ),
        CatalogEntry(
            id = "internlm3-8b-instruct", kind = CatalogKind.LLM, displayName = "书生 8B",
            description = "适合：中文与理工内容。上海 AI Lab 书生模型，体积接近上限。",
            templateId = "gguf-embedded", minRamGB = 9.5, origin = "official",
            files = listOf(
                CatalogFile(
                    "internlm3-8b-instruct-q4_k_m.gguf",
                    "$MS/Shanghai_AI_Laboratory/internlm3-8b-instruct-gguf/resolve/master/internlm3-8b-instruct-q4_k_m.gguf",
                    5_358_623_936L,
                    "e7b10f95f20a5c5a8e6213925c88bc6b02012e41c0c7d7da0b0788c528c0e010",
                ),
            ),
        ),
        CatalogEntry(
            id = "minicpm4-8b", kind = CatalogKind.LLM, displayName = "面壁 8B",
            description = "适合：端侧部署优化，中文能力强、运行效率高。",
            templateId = "gguf-embedded", minRamGB = 9.0, origin = "official",
            files = listOf(
                CatalogFile(
                    "MiniCPM4-8B-Q4_K_M.gguf",
                    "$MS/OpenBMB/MiniCPM4-8B-GGUF/resolve/master/MiniCPM4-8B-Q4_K_M.gguf",
                    4_965_526_048L,
                    "80b99ec4ceac302e7d474c8659e96b9f6c3bbeb73c41b2ef841c7fcb6d8250f3",
                ),
            ),
        ),
        CatalogEntry(
            id = "deepseek-r1-distill-llama-8b", kind = CatalogKind.LLM,
            displayName = "DeepSeek R1 8B",
            description = "适合：推理题、需要长思考过程。推理模型，生成带长思维链、端侧较慢。",
            templateId = "gguf-embedded", minRamGB = 9.0, origin = "third-party",
            files = listOf(
                CatalogFile(
                    "DeepSeek-R1-Distill-Llama-8B-Q4_K_M.gguf",
                    "$MS/lmstudio-community/DeepSeek-R1-Distill-Llama-8B-GGUF/resolve/master/DeepSeek-R1-Distill-Llama-8B-Q4_K_M.gguf",
                    4_920_736_320L,
                    "e42c90cb131c7f569364b2cc039ec03b40bc1e648090fc03470f07e3bcde6428",
                ),
            ),
        ),
        CatalogEntry(
            id = "deepseek-r1-distill-qwen-7b", kind = CatalogKind.LLM,
            displayName = "DeepSeek R1 7B",
            description = "适合：推理题、需要长思考过程。推理模型，生成带长思维链、端侧较慢。",
            templateId = "gguf-embedded", minRamGB = 8.5, origin = "third-party",
            files = listOf(
                CatalogFile(
                    "DeepSeek-R1-Distill-Qwen-7B-Q4_K_M.gguf",
                    "$MS/lmstudio-community/DeepSeek-R1-Distill-Qwen-7B-GGUF/resolve/master/DeepSeek-R1-Distill-Qwen-7B-Q4_K_M.gguf",
                    4_683_073_216L,
                    "16bba29e220550acfe6e8981cfd76b607ae00d8568bef711ff6c0455ddb322b8",
                ),
            ),
        ),
        CatalogEntry(
            id = "hunyuan-7b-instruct", kind = CatalogKind.LLM, displayName = "腾讯混元 7B",
            description = "适合：中文理解与生成均衡，指令遵循稳定。",
            templateId = "gguf-named-hunyuan-dense", minRamGB = 8.5,
            origin = "third-party",
            files = listOf(
                CatalogFile(
                    "tencent_Hunyuan-7B-Instruct-Q4_K_M.gguf",
                    "$MS/bartowski/tencent_Hunyuan-7B-Instruct-GGUF/resolve/master/tencent_Hunyuan-7B-Instruct-Q4_K_M.gguf",
                    4_621_992_960L,
                    "37361ed44b126de00a551260fd7c3de3c3a7a58d6060bce99b6b8fe0c0665d63",
                ),
            ),
        ),
        CatalogEntry(
            id = "yi-1_5-6b-chat", kind = CatalogKind.LLM, displayName = "零一万物 6B",
            description = "适合：中英双语均衡。上下文仅 4,096，长稿可能截断、分段更多。",
            templateId = "gguf-embedded", minRamGB = 7.5, origin = "third-party",
            files = listOf(
                CatalogFile(
                    "Yi-1.5-6B-Chat-Q4_K_M.gguf",
                    "$MS/bartowski/Yi-1.5-6B-Chat-GGUF/resolve/master/Yi-1.5-6B-Chat-Q4_K_M.gguf",
                    3_673_968_736L,
                    "9187794e1be34335df42a2f82e80c9b4ca7131c0f634aa976e7d70c834afd69f",
                ),
            ),
        ),
        CatalogEntry(
            id = "minicpm3-4b", kind = CatalogKind.LLM, displayName = "面壁 4B",
            description = "适合：端侧取向，长上下文见长，体积适中。",
            templateId = "gguf-embedded", minRamGB = 5.5, origin = "official",
            files = listOf(
                CatalogFile(
                    "minicpm3-4b-q4_k_m.gguf",
                    "$MS/OpenBMB/MiniCPM3-4B-GGUF/resolve/master/minicpm3-4b-q4_k_m.gguf",
                    2_469_791_584L,
                    "64913247e927414ecf47fd3e9ea8e3f0c9acae293f583dfa7e24b8872e20fa4c",
                ),
            ),
        ),
        CatalogEntry(
            id = "hunyuan-4b-instruct", kind = CatalogKind.LLM, displayName = "腾讯混元 4B",
            description = "适合：混元端侧尺寸，速度与质量折中。",
            templateId = "gguf-named-hunyuan-dense", minRamGB = 6.0,
            origin = "third-party",
            files = listOf(
                CatalogFile(
                    "tencent_Hunyuan-4B-Instruct-Q4_K_M.gguf",
                    "$MS/bartowski/tencent_Hunyuan-4B-Instruct-GGUF/resolve/master/tencent_Hunyuan-4B-Instruct-Q4_K_M.gguf",
                    2_607_709_696L,
                    "6504332fb1c42b176d0bbac725f8072ca52bf7d600d8f5b8b4b4686f680e3f76",
                ),
            ),
        ),
        CatalogEntry(
            id = "glm-edge-4b-chat", kind = CatalogKind.LLM, displayName = "智谱 4B",
            description = "适合：端侧聊天，响应快。上下文仅 3,072，长文稿分段会更多。",
            templateId = "gguf-named-glmedge", minRamGB = 6.0, origin = "official",
            files = listOf(
                CatalogFile(
                    "ggml-model-Q4_K_M.gguf",
                    "$MS/ZhipuAI/glm-edge-4b-chat-gguf/resolve/master/ggml-model-Q4_K_M.gguf",
                    2_627_488_704L,
                    "8868cf22ae605bb294e40bfcb8924d94368e8c9f8741c4aedf21ecece7b1bdb1",
                ),
            ),
        ),
        CatalogEntry(
            id = "glm-edge-1_5b-chat", kind = CatalogKind.LLM, displayName = "智谱 1.5B",
            description = "适合：体积最小、速度最快。上下文仅 2,048，适合较短的内容。",
            templateId = "gguf-named-glmedge", minRamGB = 4.0, origin = "official",
            files = listOf(
                CatalogFile(
                    "ggml-model-Q4_K_M.gguf",
                    "$MS/ZhipuAI/glm-edge-1.5b-chat-gguf/resolve/master/ggml-model-Q4_K_M.gguf",
                    980_470_144L,
                    "c78e1b978b44cd4d88d4bdc81ada1ca26b0089c502704c4e527ff6dc11e08f75",
                ),
            ),
        ),
        CatalogEntry(
            id = "whisper-tiny-int8", kind = CatalogKind.ASR, displayName = "轻量快速",
            description = "适合：旧手机、求快，中文日常内容可用。体积最小、速度最快；专业术语较多时建议换中文增强。",
            templateId = "sherpa-whisper",
            files = listOf(
                CatalogFile(
                    "tiny-encoder.int8.onnx",
                    "$HF/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main/tiny-encoder.int8.onnx",
                    12_937_772L,
                    "d24fb083ae3b1041fc24e97971d60e280c9342201fbb67b0ab428a8b4a51a434",
                ),
                CatalogFile(
                    "tiny-decoder.int8.onnx",
                    "$HF/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main/tiny-decoder.int8.onnx",
                    89_855_401L,
                    "d2fece8dd42771f1df975c6c0445770d0c292bf7547c2cae04a6c0cc57540925",
                ),
                CatalogFile(
                    "tiny-tokens.txt",
                    "$HF/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main/tiny-tokens.txt",
                    816_730L,
                    "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126",
                ),
            ),
        ),
        CatalogEntry(
            id = "sensevoice-int8", kind = CatalogKind.ASR, displayName = "中文增强",
            description = "适合：中文网课、技术术语多。中文增强，识别明显更准；速度介于轻量与精度最高之间。",
            templateId = "sherpa-sensevoice", recommended = true,
            files = listOf(
                CatalogFile(
                    "model.int8.onnx",
                    "$HF/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/model.int8.onnx",
                    239_233_841L,
                    "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51",
                ),
                CatalogFile(
                    "tokens.txt",
                    "$HF/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/tokens.txt",
                    315_894L,
                    "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc",
                ),
            ),
        ),
        CatalogEntry(
            id = "whisper-small-int8", kind = CatalogKind.ASR, displayName = "精度最高",
            description = "适合：英文课、长视频求准。精度最高，体积最大、速度最慢。",
            templateId = "sherpa-whisper",
            files = listOf(
                CatalogFile(
                    "small-encoder.int8.onnx",
                    "$HF/csukuangfj/sherpa-onnx-whisper-small/resolve/main/small-encoder.int8.onnx",
                    112_442_483L,
                    "4cbe7b22fa9026b843b60a68640c747de05bafb1a11b57edc0e66c232d9f33a9",
                ),
                CatalogFile(
                    "small-decoder.int8.onnx",
                    "$HF/csukuangfj/sherpa-onnx-whisper-small/resolve/main/small-decoder.int8.onnx",
                    262_226_114L,
                    "acad50b5c782696e91b55914cc5ab4f756f1532f76e22aa6fc615f39fb69a8ee",
                ),
                CatalogFile(
                    "small-tokens.txt",
                    "$HF/csukuangfj/sherpa-onnx-whisper-small/resolve/main/small-tokens.txt",
                    816_730L,
                    "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126",
                ),
            ),
        ),
        CatalogEntry(
            id = "paraformer-zh-int8", kind = CatalogKind.ASR, displayName = "中英方言",
            description = "适合：普通话与常见方言。中文与方言优化的大模型，识别较强。",
            templateId = "sherpa-paraformer",
            files = listOf(
                CatalogFile(
                    "model.int8.onnx",
                    "$HF/csukuangfj/sherpa-onnx-paraformer-zh-2024-03-09/resolve/main/model.int8.onnx",
                    227_330_205L,
                    "90bc03034ae1bef9575f8cc798cd1519c8be8aa9e8b458a033e32017ff4d584c",
                ),
                CatalogFile(
                    "tokens.txt",
                    "$HF/csukuangfj/sherpa-onnx-paraformer-zh-2024-03-09/resolve/main/tokens.txt",
                    75_354L,
                    "6c0e3b35cece259829e6cb5b8d90d13db88f61ea3a2953d11898e4b2bfd7a2e2",
                ),
            ),
        ),
        CatalogEntry(
            id = "paraformer-zh-small-int8", kind = CatalogKind.ASR,
            displayName = "轻量识别",
            description = "适合：旧手机、求快，中文日常内容可用。Paraformer 轻量版，体积小、速度快。",
            templateId = "sherpa-paraformer",
            files = listOf(
                CatalogFile(
                    "model.int8.onnx",
                    "$HF/csukuangfj/sherpa-onnx-paraformer-zh-small-2024-03-09/resolve/main/model.int8.onnx",
                    81_828_675L,
                    "3ef6c19369b912f7caf3cef8e545c5ccd1a33d9d7ec792a46668dc41c4b229ec",
                ),
                CatalogFile(
                    "tokens.txt",
                    "$HF/csukuangfj/sherpa-onnx-paraformer-zh-small-2024-03-09/resolve/main/tokens.txt",
                    75_352L,
                    "4b2d964e18b9cf139b473003b6698fb2ed9a2a5ec55b93daa677b28f578897aa",
                ),
            ),
        ),
        CatalogEntry(
            id = "paraformer-trilingual-int8", kind = CatalogKind.ASR,
            displayName = "三语",
            description = "适合：普通话/粤语/英语混合内容。三语版，词表更大，普通话与常见方言较强。",
            templateId = "sherpa-paraformer",
            files = listOf(
                CatalogFile(
                    "model.int8.onnx",
                    "$HF/csukuangfj/sherpa-onnx-paraformer-trilingual-zh-cantonese-en/resolve/main/model.int8.onnx",
                    244_684_152L,
                    "eb3cdd288f535cf73258f491cdd7d68ad5a00aee135c0bba4c0884ea8d926144",
                ),
                CatalogFile(
                    "tokens.txt",
                    "$HF/csukuangfj/sherpa-onnx-paraformer-trilingual-zh-cantonese-en/resolve/main/tokens.txt",
                    118_931L,
                    "8e4593d7a2eb2404ff82976b5494265e9a06283ca4d5e8605bf7b4fed557a492",
                ),
            ),
        ),
        CatalogEntry(
            id = "paraformer-sichuan-int8", kind = CatalogKind.ASR,
            displayName = "四川话特化",
            description = "适合：四川、重庆口音课程。四川话微调版，特化四川/重庆口音。",
            templateId = "sherpa-paraformer",
            files = listOf(
                CatalogFile(
                    "model.int8.onnx",
                    "$HF/csukuangfj/sherpa-onnx-paraformer-zh-int8-2025-10-07/resolve/main/model.int8.onnx",
                    238_429_929L,
                    "53813ee1d41722cc6370a571c887e6d0b391d25b8312cf714a31af85ea603812",
                ),
                CatalogFile(
                    "tokens.txt",
                    "$HF/csukuangfj/sherpa-onnx-paraformer-zh-int8-2025-10-07/resolve/main/tokens.txt",
                    75_756L,
                    "59aba8873a2ed1e122c25fee421e25f283b63290efbde85c1f01a853d83cb6e6",
                ),
            ),
        ),
        CatalogEntry(
            id = "sensevoice-yue-int8", kind = CatalogKind.ASR,
            displayName = "粤语特化",
            description = "适合：粤语课程。粤语微调版，特化粤语课程；时间戳为分段兜底。",
            templateId = "sherpa-sensevoice",
            files = listOf(
                CatalogFile(
                    "model.int8.onnx",
                    "$HF/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09/resolve/main/model.int8.onnx",
                    237_115_547L,
                    "12ca1a2ae7ecf3e0019ef2822307ee0b5cadc9196569e379b4c4026f8205276d",
                ),
                CatalogFile(
                    "tokens.txt",
                    "$HF/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09/resolve/main/tokens.txt",
                    315_894L,
                    "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc",
                ),
            ),
        ),
        CatalogEntry(
            id = "whisper-medium-int8", kind = CatalogKind.ASR,
            displayName = "英文课程",
            description = "适合：多语种/英文课程。中文非强项、端侧解码慢（真机测速待验）。",
            templateId = "sherpa-whisper",
            files = listOf(
                CatalogFile(
                    "medium-encoder.int8.onnx",
                    "$HF/csukuangfj/sherpa-onnx-whisper-medium/resolve/main/medium-encoder.int8.onnx",
                    374_196_283L,
                    "1c54582b4d829de0089f6cb63bbbdb3bf7555398bacaf855fbecf1a84dfd193e",
                ),
                CatalogFile(
                    "medium-decoder.int8.onnx",
                    "$HF/csukuangfj/sherpa-onnx-whisper-medium/resolve/main/medium-decoder.int8.onnx",
                    571_059_257L,
                    "595d00a338a365a7bfa0ca7f296cabc639583bef770ab6130df90f49a6412747",
                ),
                CatalogFile(
                    "medium-tokens.txt",
                    "$HF/csukuangfj/sherpa-onnx-whisper-medium/resolve/main/medium-tokens.txt",
                    816_730L,
                    "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126",
                ),
            ),
        ),
        CatalogEntry(
            id = "whisper-base-int8", kind = CatalogKind.ASR,
            displayName = "均衡档",
            description = "适合：轻量与精度最高之间的均衡档。",
            templateId = "sherpa-whisper",
            files = listOf(
                CatalogFile(
                    "base-encoder.int8.onnx",
                    "$HF/csukuangfj/sherpa-onnx-whisper-base/resolve/main/base-encoder.int8.onnx",
                    29_120_534L,
                    "0b8fb1304b6109976038efff5ace81720e00386f3ff6b54ee8c75291ca0a1e11",
                ),
                CatalogFile(
                    "base-decoder.int8.onnx",
                    "$HF/csukuangfj/sherpa-onnx-whisper-base/resolve/main/base-decoder.int8.onnx",
                    130_672_026L,
                    "9759d217388a01b3a4c7c15533201067b48ae819c4daafc8624e64b9409dc02d",
                ),
                CatalogFile(
                    "base-tokens.txt",
                    "$HF/csukuangfj/sherpa-onnx-whisper-base/resolve/main/base-tokens.txt",
                    816_730L,
                    "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126",
                ),
            ),
        ),
        CatalogEntry(
            id = "zipformer-ctc-zh-int8", kind = CatalogKind.ASR,
            displayName = "中文主力",
            description = "适合：中文网课，识别最准。官方中文测试字错率最低一档；时间戳：句级。",
            templateId = "sherpa-zipformer-ctc",
            files = listOf(
                CatalogFile(
                    "model.int8.onnx",
                    "$MS/csukuangfj/sherpa-onnx-zipformer-ctc-zh-int8-2025-07-03/resolve/master/model.int8.onnx",
                    367_074_356L,
                    "e291b9c468b651e2697caa09bc684326c3addc6a019e78eb537cfd1a8248ca07",
                ),
                CatalogFile(
                    "tokens.txt",
                    "$MS/csukuangfj/sherpa-onnx-zipformer-ctc-zh-int8-2025-07-03/resolve/master/tokens.txt",
                    13_366L,
                    "6fed8c6c248516f38e7faa19404b57413e8ce259f1cbc1fa4aebc86eac32fdfd",
                ),
                CatalogFile(
                    "bbpe.model",
                    "$MS/csukuangfj/sherpa-onnx-zipformer-ctc-zh-int8-2025-07-03/resolve/master/bbpe.model",
                    255_180L,
                    "503204e0690eff065e30d0e01898c9ab06d0e6dc376a741eb6846198f95b2f82",
                ),
            ),
        ),
        CatalogEntry(
            id = "zipformer-transducer-zh-hans-int8", kind = CatalogKind.ASR,
            displayName = "轻量 Transducer",
            description = "适合：旧手机、求快。轻量档，AISHELL-1 3.04（icefall 官方）；encoder/joiner 8 位 + decoder 32 位混搭；原生 token 级时间戳。",
            templateId = "sherpa-transducer",
            files = listOf(
                CatalogFile(
                    "encoder-epoch-20-avg-1.int8.onnx",
                    "$HF/zrjin/sherpa-onnx-zipformer-multi-zh-hans-2023-9-2/resolve/main/encoder-epoch-20-avg-1.int8.onnx",
                    69_037_476L,
                    "6dd1748110f55cb7e9ce00a6abd4b96959fa3f2a25a20d0c52184dd1eb8c9ece",
                ),
                CatalogFile(
                    "decoder-epoch-20-avg-1.onnx",
                    "$HF/zrjin/sherpa-onnx-zipformer-multi-zh-hans-2023-9-2/resolve/main/decoder-epoch-20-avg-1.onnx",
                    5_165_142L,
                    "f6bbd5fc9cfa2c69988fafb63e5ccd50756134993eb6c23d89887d81e7f7896c",
                ),
                CatalogFile(
                    "joiner-epoch-20-avg-1.int8.onnx",
                    "$HF/zrjin/sherpa-onnx-zipformer-multi-zh-hans-2023-9-2/resolve/main/joiner-epoch-20-avg-1.int8.onnx",
                    1_033_479L,
                    "fc04f95a7c184390f48a64eab541f7bcab7c856b5df03bf67f227d1c74b49f99",
                ),
                CatalogFile(
                    "tokens.txt",
                    "$HF/zrjin/sherpa-onnx-zipformer-multi-zh-hans-2023-9-2/resolve/main/tokens.txt",
                    18_610L,
                    "67352babdf908352d3ce19c4496cb05ca173805616c23b520927117b2c2e2ffa",
                ),
                CatalogFile(
                    "bpe.model",
                    "$HF/zrjin/sherpa-onnx-zipformer-multi-zh-hans-2023-9-2/resolve/main/bpe.model",
                    263_956L,
                    "867a7355801cb43939962ad757ba1cb7941b6171b5a6902772483b4e3a623377",
                ),
            ),
        ),
        CatalogEntry(
            id = "dolphin-small-ctc-int8", kind = CatalogKind.ASR,
            displayName = "方言强项",
            description = "适合：22 种中文方言，普通话标准课备选。CTC 时间戳通路。",
            templateId = "sherpa-dolphin",
            files = listOf(
                CatalogFile(
                    "model.int8.onnx",
                    "$MS/csukuangfj/sherpa-onnx-dolphin-small-ctc-multi-lang-int8-2025-04-02/resolve/master/model.int8.onnx",
                    249_658_954L,
                    "c1afcb9265de0ebd853eb8f570b371f399a6f9b2b9af9a3cb17c2e509171e697",
                ),
                CatalogFile(
                    "tokens.txt",
                    "$MS/csukuangfj/sherpa-onnx-dolphin-small-ctc-multi-lang-int8-2025-04-02/resolve/master/tokens.txt",
                    504_662L,
                    "c3788261a51df1899ea4b210b552cd42139204de72c0ad60f6cebb199078872e",
                ),
            ),
        ),
    )
}
