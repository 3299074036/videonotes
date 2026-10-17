package com.zola.videonotes.data.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 模型目录完整性判断与模型清单测试（不含 native / 网络）。 */
class ModelDownloadManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun writeFile(dir: File, name: String, bytes: Int) {
        File(dir, name).writeBytes(ByteArray(bytes) { 1 })
    }

    /** 全部文件存在且 >0 字节 → 完整。 */
    @Test
    fun `complete directory is detected`() {
        val dir = tmp.newFolder("whisper-tiny-int8")
        val model = AsrModelCatalog.get("whisper-tiny-int8")
        for (f in model.files) {
            writeFile(dir, f.name, 8)
        }
        assertTrue(ModelDownloadManager.isModelComplete(dir, model))
    }

    /** 缺任意一个文件 → 不完整。 */
    @Test
    fun `missing file is detected`() {
        val dir = tmp.newFolder("whisper-tiny-int8")
        val model = AsrModelCatalog.get("whisper-tiny-int8")
        for (f in model.files.dropLast(1)) {
            writeFile(dir, f.name, 8)
        }
        assertFalse(ModelDownloadManager.isModelComplete(dir, model))
    }

    /** 存在 0 字节文件 → 不完整（断点续传的中间态）。 */
    @Test
    fun `zero byte file is not complete`() {
        val dir = tmp.newFolder("whisper-tiny-int8")
        val model = AsrModelCatalog.get("whisper-tiny-int8")
        for (f in model.files) {
            writeFile(dir, f.name, if (f.name.endsWith("tokens.txt")) 0 else 8)
        }
        assertFalse(ModelDownloadManager.isModelComplete(dir, model))
    }

    /** 不存在的目录 → 不完整。 */
    @Test
    fun `missing directory is not complete`() {
        val model = AsrModelCatalog.get("whisper-tiny-int8")
        assertFalse(ModelDownloadManager.isModelComplete(File(tmp.root, "nope"), model))
    }

    /** 模型清单：3 个模型，默认 id 为 whisper-tiny-int8，总大小与文件列表自洽。 */
    @Test
    fun `catalog is consistent`() {
        assertEquals(3, AsrModelCatalog.models.size)
        assertEquals("whisper-tiny-int8", AsrModelCatalog.DEFAULT_MODEL_ID)
        for (m in AsrModelCatalog.models) {
            assertTrue(m.files.isNotEmpty())
            assertTrue(m.totalBytes > 0)
            for (f in m.files) {
                assertTrue(f.url.startsWith("https://huggingface.co/"))
            }
        }
    }
}
