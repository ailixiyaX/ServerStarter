import atm.bloodworkxgaming.serverstarter.util.ApiVerdict
import atm.bloodworkxgaming.serverstarter.util.HashingUtil
import atm.bloodworkxgaming.serverstarter.util.ModrinthClientModScan
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [ModrinthClientModScan] 测试：安装末尾对没有平台判定的 jar 做 Modrinth 哈希反查。
 *
 * 真实用例：整合包 overrides 里的 `MaFgLib-0.1.14-mc1.20.1.jar` 与
 * `jecharacters-1.20.1-forge-4.6.12.jar`，mods.toml 写 `side = "BOTH"`，但 Modrinth
 * project 的 server_side 是 unsupported（客户端专用），放上服务器会崩在 RuntimeDistCleaner。
 *
 * complete 语义：只有全部请求都成功才算扫描完整（调用方据此决定是否记「已扫描」标记）。
 */
class ModrinthClientModScanTest {

    private val tempDir: File = Files.createTempDirectory("modrinth-client-scan").toFile()

    @After
    fun cleanup() {
        tempDir.deleteRecursively()
    }

    private fun jar(name: String, content: String): File {
        val file = File(tempDir, name)
        file.writeText(content)
        return file
    }

    @Test
    fun `hash 反查请求体声明 sha1 算法并带全部哈希`() {
        val body = ModrinthClientModScan.versionFilesBody(listOf("aa", "bb"))

        assertTrue(body.contains("\"algorithm\":\"sha1\""))
        assertTrue(body.contains("\"aa\""))
        assertTrue(body.contains("\"bb\""))
    }

    @Test
    fun `projects 查询 URL 是 URL 编码的 JSON 数组`() {
        val url = ModrinthClientModScan.projectsUrl(listOf("P1", "P2"))

        assertTrue(url.startsWith(ModrinthClientModScan.VERSION_FILES_URL.substringBefore("/version_files") + "/projects?ids="))
        assertTrue(url.contains("%5B%22P1%22%2C%22P2%22%5D"))
    }

    @Test
    fun `解析 hash 反查响应只取 project_id`() {
        val json = "{\"abc\":{\"project_id\":\"P1\",\"name\":\"x\"},\"def\":{\"name\":\"no project\"},\"ghi\":\"oops\"}"

        assertEquals(mapOf("abc" to "P1"), ModrinthClientModScan.parseVersionFiles(json))
    }

    @Test
    fun `哈希以大写返回时统一成小写`() {
        assertEquals(mapOf("abc" to "P1"), ModrinthClientModScan.parseVersionFiles("{\"ABC\":{\"project_id\":\"P1\"}}"))
    }

    @Test
    fun `解析 project 响应缺 server_side 记为 null`() {
        val json = "[{\"id\":\"P1\",\"server_side\":\"unsupported\"},{\"id\":\"P2\"},{\"id\":\"P3\",\"server_side\":\"required\"}]"

        val sides = ModrinthClientModScan.parseProjectServerSides(json)

        assertEquals("unsupported", sides["P1"])
        assertTrue(sides.containsKey("P2"))
        assertNull(sides["P2"])
        assertEquals("required", sides["P3"])
    }

    @Test
    fun `只有 server_side=unsupported 算客户端专用`() {
        assertTrue(ModrinthClientModScan.isClientOnly("unsupported"))
        assertTrue(ModrinthClientModScan.isClientOnly("UNSUPPORTED"))
        assertEquals(false, ModrinthClientModScan.isClientOnly("required"))
        assertEquals(false, ModrinthClientModScan.isClientOnly("optional"))
        assertEquals(false, ModrinthClientModScan.isClientOnly(null))
    }

    @Test
    fun `按 sha1 命中客户端专用项目并只返回该文件`() {
        val clientJar = jar("clientmod.jar", "client")
        val serverJar = jar("servermod.jar", "server")
        val clientSha1 = HashingUtil.sha1Hex(clientJar)
        val serverSha1 = HashingUtil.sha1Hex(serverJar)

        val result = ModrinthClientModScan.scan(
                tempDir,
                emptyList(),
                { _, body ->
                    assertTrue(body.contains(clientSha1))
                    assertTrue(body.contains(serverSha1))
                    "{\"$clientSha1\":{\"project_id\":\"P1\"},\"$serverSha1\":{\"project_id\":\"P2\"}}"
                },
                { url ->
                    assertTrue(url.contains("P1"))
                    assertTrue(url.contains("P2"))
                    "[{\"id\":\"P1\",\"server_side\":\"unsupported\"},{\"id\":\"P2\",\"server_side\":\"required\"}]"
                }
        )

        assertEquals(mapOf("clientmod.jar" to ApiVerdict.CLIENT_ONLY), result.verdicts)
        assertTrue(result.complete)
    }

    @Test
    fun `已有平台判定的文件不再查询`() {
        jar("known.jar", "known")
        val requestedBodies = mutableListOf<String>()

        val result = ModrinthClientModScan.scan(
                tempDir,
                listOf("known.jar"),
                { _, body -> requestedBodies.add(body); "{}" },
                { "[]" }
        )

        assertTrue(result.verdicts.isEmpty())
        assertTrue(result.complete)
        assertTrue(requestedBodies.isEmpty())
    }

    @Test
    fun `hash 反查失败时返回空表且标记为不完整`() {
        jar("a.jar", "a")

        val result = ModrinthClientModScan.scan(
                tempDir,
                emptyList(),
                { _, _ -> throw IllegalStateException("network down") },
                { "[]" }
        )

        assertTrue(result.verdicts.isEmpty())
        assertFalse(result.complete)
    }

    @Test
    fun `project 查询失败时保留该批模组并标记为不完整`() {
        val jarFile = jar("a.jar", "a")
        val sha1 = HashingUtil.sha1Hex(jarFile)

        val result = ModrinthClientModScan.scan(
                tempDir,
                emptyList(),
                { _, _ -> "{\"$sha1\":{\"project_id\":\"P1\"}}" },
                { _ -> throw IllegalStateException("network down") }
        )

        assertTrue(result.verdicts.isEmpty())
        assertFalse(result.complete)
    }
}
