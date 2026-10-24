package com.zola.videonotes.data.catalog

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 动态层本地注册表（WP3）：已下载的 HF 动态模型以「目录自描述」方式落盘——
 * 每个条目在 `filesDir/llm_models/<syntheticId>/` 下有模型文件与 entry.json
 * （合成 id、仓库、文件名、URL、大小、sha256、探针结论、首次使用确认）。
 *
 * 已下载动态条目的枚举/解析一律读本地注册表，永不依赖 HF 可达性；
 * 删除 = 删整个目录（与 ASR 同语义）。
 */
@Singleton
class DynamicModelRegistry @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** entry.json 中除条目本体外的运行时状态。 */
    data class DynamicMeta(
        val probe: TemplateProbeState? = null,
        /** 探针结论对应的文件指纹（长度 to 修改时间），文件变了结论作废。 */
        val probeFingerprint: Pair<Long, Long>? = null,
        /** 首次「设为使用」的一次性确认是否已完成（C7）。 */
        val useConfirmed: Boolean = false,
        val downloadedAt: Long = 0L,
    )

    fun modelsRoot(): File = File(context.filesDir, "llm_models")

    fun dir(id: String): File = File(modelsRoot(), id)

    fun entryFile(id: String): File = File(dir(id), ENTRY_FILE_NAME)

    fun modelFile(entry: CatalogEntry): File =
        File(dir(entry.id), entry.files.first().name)

    /**
     * 合成 id 规则（方案 §3.3.5）：`hf--<owner>--<repo>--<fileStem>`，
     * 全小写、仅保留 [a-z0-9-]、其余转 '-'、连续 '-' 折叠；
     * 超 80 字符截断并拼 8 位原文 sha1 后缀保证唯一。
     */
    fun syntheticId(owner: String, repo: String, fileName: String): String {
        val stem = fileName.removeSuffix(".gguf")
        val raw = "hf--$owner--$repo--$stem"
        val cleaned = raw.lowercase()
            .map { c -> if (c in 'a'..'z' || c in '0'..'9' || c == '-') c else '-' }
            .joinToString("")
            .replace(Regex("-+"), "-")
            .trim('-')
        if (cleaned.length <= 80) return cleaned
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(8)
        return cleaned.take(71).trimEnd('-') + "-" + digest
    }

    /** 写入/更新 entry.json（保留已有的探针结论与确认标记，除非显式覆盖）。 */
    fun writeEntry(entry: CatalogEntry, meta: DynamicMeta? = null) {
        val existing = readMeta(entry.id)
        val merged = DynamicMeta(
            probe = meta?.probe ?: existing?.probe,
            probeFingerprint = meta?.probeFingerprint ?: existing?.probeFingerprint,
            useConfirmed = meta?.useConfirmed ?: existing?.useConfirmed ?: false,
            downloadedAt = meta?.downloadedAt?.takeIf { it > 0 }
                ?: existing?.downloadedAt
                ?: System.currentTimeMillis(),
        )
        dir(entry.id).mkdirs()
        entryFile(entry.id).writeText(toJson(entry, merged).toString())
    }

    fun readEntry(id: String): CatalogEntry? {
        val f = entryFile(id)
        if (!f.exists()) return null
        return runCatching { fromJson(JSONObject(f.readText())) }.getOrNull()
    }

    fun readMeta(id: String): DynamicMeta? {
        val f = entryFile(id)
        if (!f.exists()) return null
        return runCatching {
            val o = JSONObject(f.readText())
            DynamicMeta(
                probe = o.optString("probe").takeIf { it.isNotBlank() }
                    ?.let { runCatching { TemplateProbeState.valueOf(it) }.getOrNull() },
                probeFingerprint = if (o.has("probeSize") && o.has("probeMtime")) {
                    o.getLong("probeSize") to o.getLong("probeMtime")
                } else {
                    null
                },
                useConfirmed = o.optBoolean("useConfirmed", false),
                downloadedAt = o.optLong("downloadedAt", 0L),
            )
        }.getOrNull()
    }

    fun setProbe(id: String, state: TemplateProbeState, fingerprint: Pair<Long, Long>) {
        val entry = readEntry(id) ?: return
        val meta = readMeta(id) ?: DynamicMeta()
        writeEntry(entry, meta.copy(probe = state, probeFingerprint = fingerprint))
    }

    fun setUseConfirmed(id: String) {
        val entry = readEntry(id) ?: return
        val meta = readMeta(id) ?: DynamicMeta()
        if (!meta.useConfirmed) writeEntry(entry, meta.copy(useConfirmed = true))
    }

    /** 回填无官方哈希文件的本地实算 sha256（仅本地记录，首下即算，见方案 §3.3.1）。 */
    fun setSha256(id: String, sha256: String) {
        val entry = readEntry(id) ?: return
        val updated = entry.copy(
            files = listOf(entry.files.first().copy(sha256 = sha256)),
        )
        writeEntry(updated)
    }

    /** 枚举全部已下载动态条目（目录含 entry.json 且不在精选/影子集内）。 */
    fun entries(): List<CatalogEntry> {
        val root = modelsRoot()
        if (!root.isDirectory) return emptyList()
        val known = CatalogStore.all().map { it.id }.toSet()
        return root.listFiles().orEmpty()
            .filter { it.isDirectory && it.name !in known }
            .mapNotNull { readEntry(it.name) }
            .sortedBy { it.displayName.lowercase() }
    }

    fun entryFor(id: String): CatalogEntry? =
        if (id.startsWith("hf--")) readEntry(id) else null

    /** 当前文件指纹（长度 to 修改时间）；文件不存在返回 null。 */
    fun fingerprintOf(entry: CatalogEntry): Pair<Long, Long>? {
        val f = modelFile(entry)
        return if (f.exists()) f.length() to f.lastModified() else null
    }

    /** 探针结论是否仍对应当前文件（指纹一致才有效）。 */
    fun validProbe(entry: CatalogEntry): TemplateProbeState? {
        val meta = readMeta(entry.id) ?: return null
        val fp = fingerprintOf(entry) ?: return null
        return if (meta.probeFingerprint == fp) meta.probe else null
    }

    private fun toJson(entry: CatalogEntry, meta: DynamicMeta): JSONObject {
        val f = entry.files.first()
        return JSONObject().apply {
            put("id", entry.id)
            put("displayName", entry.displayName)
            put("description", entry.description)
            put("hfRepo", entry.hfRepo ?: JSONObject.NULL)
            put("fileName", f.name)
            put("url", f.url)
            put("sizeBytes", f.sizeBytes)
            put("sha256", f.sha256 ?: JSONObject.NULL)
            put("minRamGB", entry.minRamGB ?: JSONObject.NULL)
            put("probe", meta.probe?.name ?: JSONObject.NULL)
            meta.probeFingerprint?.let {
                put("probeSize", it.first)
                put("probeMtime", it.second)
            }
            put("useConfirmed", meta.useConfirmed)
            put("downloadedAt", meta.downloadedAt)
        }
    }

    private fun fromJson(o: JSONObject): CatalogEntry {
        val fileName = o.getString("fileName")
        return CatalogEntry(
            id = o.getString("id"),
            kind = CatalogKind.LLM,
            displayName = o.optString("displayName"),
            description = o.optString("description"),
            templateId = "gguf-embedded",
            files = listOf(
                CatalogFile(
                    name = fileName,
                    url = o.getString("url"),
                    sizeBytes = o.getLong("sizeBytes"),
                    sha256 = o.optString("sha256").takeIf { it.isNotBlank() },
                ),
            ),
            minRamGB = if (o.isNull("minRamGB")) null else o.optDouble("minRamGB"),
            source = CatalogSource.DYNAMIC,
            hfRepo = o.optString("hfRepo").takeIf { it.isNotBlank() },
        )
    }

    companion object {
        const val ENTRY_FILE_NAME = "entry.json"
    }
}
