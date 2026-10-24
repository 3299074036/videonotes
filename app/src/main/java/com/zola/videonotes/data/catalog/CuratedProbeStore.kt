package com.zola.videonotes.data.catalog

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 精选条目（国内名单定版新增的内嵌/命名模板条目）的探针结论与首次使用确认存储。
 *
 * 动态条目的同类状态在注册表 entry.json 里（按 id 目录自描述）；精选条目没有
 * entry.json，故集中存 `filesDir/curated_model_state.json` 一份：
 * 条目 id → {探针结论, 解析模式（embedded/named）, 文件指纹（大小+修改时间）}
 * 与已确认「设为使用」的 id 集合。指纹不一致时结论作废，须重新探针。
 */
@Singleton
class CuratedProbeStore @Inject constructor(
    @ApplicationContext context: Context,
) {

    data class Record(
        val state: TemplateProbeState,
        /** 探针定下的拼装模式：MODE_EMBEDDED（内嵌模板）/ MODE_NAMED（预定义命名模板）。 */
        val mode: String,
        val sizeBytes: Long,
        val mtime: Long,
    )

    private val storeFile = File(context.filesDir, FILE_NAME)

    @Volatile
    private var records: MutableMap<String, Record>? = null

    @Volatile
    private var confirmed: MutableSet<String>? = null

    @Synchronized
    private fun load() {
        if (records != null) return
        val recs = mutableMapOf<String, Record>()
        val conf = mutableSetOf<String>()
        runCatching {
            if (storeFile.exists()) {
                val root = JSONObject(storeFile.readText())
                val recObj = root.optJSONObject("records")
                if (recObj != null) {
                    for (id in recObj.keys()) {
                        val o = recObj.getJSONObject(id)
                        val state = runCatching {
                            TemplateProbeState.valueOf(o.getString("state"))
                        }.getOrNull() ?: continue
                        recs[id] = Record(
                            state = state,
                            mode = o.optString("mode", MODE_EMBEDDED),
                            sizeBytes = o.getLong("size"),
                            mtime = o.getLong("mtime"),
                        )
                    }
                }
                val confArr = root.optJSONArray("confirmed")
                if (confArr != null) {
                    for (i in 0 until confArr.length()) conf += confArr.getString(i)
                }
            }
        }
        records = recs
        confirmed = conf
    }

    @Synchronized
    private fun persist() {
        val recs = records ?: return
        val conf = confirmed ?: return
        runCatching {
            val root = JSONObject()
            val recObj = JSONObject()
            for ((id, r) in recs) {
                recObj.put(
                    id,
                    JSONObject()
                        .put("state", r.state.name)
                        .put("mode", r.mode)
                        .put("size", r.sizeBytes)
                        .put("mtime", r.mtime),
                )
            }
            root.put("records", recObj)
            root.put("confirmed", org.json.JSONArray(conf.toList()))
            storeFile.writeText(root.toString())
        }
    }

    /** 当前记录（不校验指纹）。 */
    fun record(id: String): Record? {
        load()
        return records?.get(id)
    }

    /** 指纹与当前文件一致时才有效的探针结论；无记录/指纹不符/文件缺失返回 null。 */
    fun validState(id: String, fingerprint: Pair<Long, Long>?): TemplateProbeState? {
        val r = record(id) ?: return null
        val fp = fingerprint ?: return null
        return if (r.sizeBytes == fp.first && r.mtime == fp.second) r.state else null
    }

    /** 指纹有效时探针定下的拼装模式（供生成时选内嵌/命名通路）。 */
    fun validMode(id: String, fingerprint: Pair<Long, Long>?): String? {
        val r = record(id) ?: return null
        val fp = fingerprint ?: return null
        return if (r.sizeBytes == fp.first && r.mtime == fp.second) r.mode else null
    }

    fun save(id: String, state: TemplateProbeState, mode: String, fingerprint: Pair<Long, Long>) {
        load()
        records?.put(id, Record(state, mode, fingerprint.first, fingerprint.second))
        persist()
    }

    fun isUseConfirmed(id: String): Boolean {
        load()
        return confirmed?.contains(id) == true
    }

    fun setUseConfirmed(id: String) {
        load()
        if (confirmed?.add(id) == true) persist()
    }

    companion object {
        const val FILE_NAME = "curated_model_state.json"
        const val MODE_EMBEDDED = "embedded"
        const val MODE_NAMED = "named"
    }
}
