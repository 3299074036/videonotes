package com.zola.videonotes.data.catalog

import android.content.Context
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * 用户目录状态：对精选池的本地改动（增删改的唯一事实来源）。
 *
 * - [removedIds]：从列表移除的精选池 id（自定义条目移除时直接从 [custom] 删除，不进此集合）。
 * - [edited]：改过的精选池条目（全量覆盖，保存时来源仍为精选；推荐位以表单「是否推荐」字段为准，
 *   恢复即删掉这条覆盖）。
 * - [custom]：用户填写的新条目（按添加顺序排在精选池之后）。
 *
 * 持久化：filesDir/user_catalog/user_catalog.json（org.json 手写序列化，不引新依赖），
 * 写时先写临时文件再改名；内存缓存 + [version] 供界面层触发重算。
 * 未初始化时视为空状态（不崩），初始化由 [CatalogRepository]、[LocalLlmPreferences]、
 * ASR [ModelDownloadManager] 的 init 各调一次（幂等，先到先得）。
 */
data class UserCatalogState(
    val removedIds: Set<String> = emptySet(),
    val edited: Map<String, CatalogEntry> = emptyMap(),
    val custom: List<CatalogEntry> = emptyList(),
)

/** 生效列表合并（纯函数）：精选池保序 → 移除的掉 → 改过的就地替换 → 自定义按添加顺序追加。 */
fun mergeUserCatalog(pool: List<CatalogEntry>, state: UserCatalogState): List<CatalogEntry> {
    val out = ArrayList<CatalogEntry>(pool.size + state.custom.size)
    for (entry in pool) {
        if (entry.id in state.removedIds) continue
        out += state.edited[entry.id] ?: entry
    }
    out += state.custom
    return out
}

object UserCatalog {

    private const val DIR_NAME = "user_catalog"
    private const val FILE_NAME = "user_catalog.json"
    private const val TMP_NAME = "user_catalog.json.tmp"

    private val lock = Any()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var cached: UserCatalogState = UserCatalogState()

    private val _version = MutableStateFlow(0)

    /** 每次写操作自增；界面层把它拼进 browseList 的 combine 触发器即可。 */
    val version: StateFlow<Int> = _version.asStateFlow()

    /** 幂等初始化：读盘进缓存；解析失败视为空状态（不砖化）。 */
    fun init(context: Context) {
        if (appContext != null) return
        synchronized(lock) {
            if (appContext != null) return
            appContext = context.applicationContext
            cached = runCatching { readFile() }.getOrDefault(UserCatalogState())
        }
    }

    /** 当前快照（未初始化时为空状态）。 */
    fun snapshot(): UserCatalogState = cached

    /** 改过或自定义的条目（优先于精选池）；精选池原值不在此列。 */
    fun entryFor(id: String): CatalogEntry? {
        val state = cached
        return state.edited[id] ?: state.custom.firstOrNull { it.id == id }
    }

    fun isEdited(id: String): Boolean = cached.edited.containsKey(id)

    fun isCustom(id: String): Boolean = cached.custom.any { it.id == id }

    /** 新增自定义条目（调用方保证 id 在生效列表内唯一）。 */
    fun addCustom(entry: CatalogEntry) {
        update { it.copy(custom = it.custom + entry.copy(source = CatalogSource.CURATED)) }
    }

    /** 修改自定义条目（按 id 替换；推荐位以传入条目为准）。 */
    fun updateCustom(entry: CatalogEntry) {
        update { state ->
            state.copy(custom = state.custom.map { if (it.id == entry.id) entry else it })
        }
    }

    /** 修改精选池条目：全量覆盖，推荐位以表单「是否推荐」字段为准、来源仍为精选。 */
    fun saveEditedPoolEntry(entry: CatalogEntry) {
        update { state ->
            state.copy(
                edited = state.edited + (entry.id to entry.copy(
                    source = CatalogSource.CURATED,
                )),
            )
        }
    }

    /**
     * 从列表移除：自定义条目直接删；精选池条目记入移除集合并丢弃改动覆盖
     * （恢复时回到精选原值）。
     */
    fun remove(id: String) {
        update { state ->
            state.copy(
                removedIds = if (state.custom.any { it.id == id }) {
                    state.removedIds
                } else {
                    state.removedIds + id
                },
                edited = state.edited - id,
                custom = state.custom.filterNot { it.id == id },
            )
        }
    }

    /** 恢复精选池条目：清移除标记与改动覆盖（回到精选原值；自定义条目无此操作）。 */
    fun restore(id: String) {
        update { state ->
            state.copy(
                removedIds = state.removedIds - id,
                edited = state.edited - id,
            )
        }
    }

    private fun update(transform: (UserCatalogState) -> UserCatalogState) {
        synchronized(lock) {
            cached = transform(cached)
            runCatching { writeFile(cached) }
            _version.value += 1
        }
    }

    private fun catalogFile(): File? {
        val ctx = appContext ?: return null
        val dir = File(ctx.filesDir, DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, FILE_NAME)
    }

    private fun readFile(): UserCatalogState {
        val file = catalogFile() ?: return UserCatalogState()
        if (!file.exists()) return UserCatalogState()
        val root = JSONObject(file.readText())
        val removed = mutableSetOf<String>()
        root.optJSONArray("removedIds")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optString(i).takeIf { it.isNotBlank() }?.let { removed += it }
            }
        }
        val edited = mutableMapOf<String, CatalogEntry>()
        root.optJSONObject("edited")?.let { obj ->
            for (key in obj.keys()) {
                entryFromJson(obj.optJSONObject(key))?.let { edited[key] = it }
            }
        }
        val custom = mutableListOf<CatalogEntry>()
        root.optJSONArray("custom")?.let { arr ->
            for (i in 0 until arr.length()) {
                entryFromJson(arr.optJSONObject(i))?.let { custom += it }
            }
        }
        return UserCatalogState(removedIds = removed, edited = edited, custom = custom)
    }

    private fun writeFile(state: UserCatalogState) {
        val file = catalogFile() ?: return
        val root = JSONObject()
        root.put("removedIds", JSONArray(state.removedIds.toList()))
        val editedObj = JSONObject()
        for ((id, entry) in state.edited) {
            editedObj.put(id, entryToJson(entry))
        }
        root.put("edited", editedObj)
        val customArr = JSONArray()
        for (entry in state.custom) {
            customArr.put(entryToJson(entry))
        }
        root.put("custom", customArr)
        val tmp = File(file.parent, TMP_NAME)
        tmp.writeText(root.toString())
        if (!tmp.renameTo(file)) {
            file.writeText(root.toString())
        }
    }

    /** 序列化字段与 assets/model_catalog.json 的键名一致；空值省略，读时按缺省还原。 */
    internal fun entryToJson(e: CatalogEntry): JSONObject {
        val o = JSONObject()
        o.put("id", e.id)
        o.put("kind", e.kind.name)
        o.put("displayName", e.displayName)
        o.put("description", e.description)
        e.originalName?.let { o.put("originalName", it) }
        o.put("useCases", JSONArray(e.useCases))
        o.put("templateId", e.templateId)
        val filesArr = JSONArray()
        for (f in e.files) {
            val fo = JSONObject()
            fo.put("name", f.name)
            fo.put("url", f.url)
            fo.put("sizeBytes", f.sizeBytes)
            f.sha256?.let { fo.put("sha256", it) }
            filesArr.put(fo)
        }
        o.put("files", filesArr)
        o.put("recommended", e.recommended)
        o.put("smallModelPrompt", e.smallModelPrompt)
        e.downloadCount?.let { o.put("downloadCount", it) }
        e.minRamGB?.let { o.put("minRamGB", it) }
        if (e.source == CatalogSource.SHADOW) o.put("shadow", true)
        e.origin?.let { o.put("origin", it) }
        return o
    }

    /**
     * 读时宽容：id 为空或文件为空的条目跳过（无法下载）；kind 非法跳过；
     * templateId 不设白名单（表单保存时校验，旧文件不因模板下线而丢条目，
     * 映射层对未知模板有既定回退：LLM 走 QWEN3、ASR 走 WHISPER）。
     */
    internal fun entryFromJson(o: JSONObject?): CatalogEntry? {
        if (o == null) return null
        val id = o.optString("id").takeIf { it.isNotBlank() } ?: return null
        val kind = runCatching { CatalogKind.valueOf(o.optString("kind")) }.getOrNull() ?: return null
        val filesArr = o.optJSONArray("files") ?: return null
        val files = (0 until filesArr.length()).mapNotNull { j ->
            val f = filesArr.optJSONObject(j) ?: return@mapNotNull null
            val name = f.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val url = f.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            CatalogFile(
                name = name,
                url = url,
                sizeBytes = f.optLong("sizeBytes"),
                sha256 = f.optString("sha256").takeIf { it.isNotBlank() },
            )
        }
        if (files.isEmpty()) return null
        return CatalogEntry(
            id = id,
            kind = kind,
            displayName = o.optString("displayName"),
            description = o.optString("description"),
            originalName = o.optString("originalName").takeIf { it.isNotBlank() },
            useCases = o.optJSONArray("useCases")?.let { arr ->
                (0 until arr.length()).map { k -> arr.optString(k) }
            }.orEmpty().filter { it.isNotBlank() },
            templateId = o.optString("templateId"),
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
}
