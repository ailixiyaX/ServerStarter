import atm.bloodworkxgaming.serverstarter.util.ApiVerdict
import atm.bloodworkxgaming.serverstarter.util.ClientOnlyModFilter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * [ClientOnlyModFilter] 测试：TOML 与平台判定（CF 三态 / Modrinth 哈希反查）的合并决策。
 *
 * 关键用例来自真实整合包：overrides 里的 jar 在 mods.toml 写 `side = "BOTH"`，
 * 但平台判定是客户端专用 —— 只看 TOML 会漏删，服务端启动时崩在客户端类上。
 */
class ClientOnlyModFilterTest {

    private val tempDir: File = Files.createTempDirectory("client-mod-filter").toFile()

    @After
    fun cleanup() {
        tempDir.deleteRecursively()
    }

    /** 生成一个最小 jar：只含 META-INF/mods.toml（可选另加 fabric.mod.json）。 */
    private fun modJar(name: String, toml: String?, fabric: String? = null): File {
        val file = File(tempDir, name)
        ZipOutputStream(file.outputStream()).use { zip ->
            if (toml != null) {
                zip.putNextEntry(ZipEntry("META-INF/mods.toml"))
                zip.write(toml.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            if (fabric != null) {
                zip.putNextEntry(ZipEntry("fabric.mod.json"))
                zip.write(fabric.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return file
    }

    private fun toml(modId: String, side: String): String =
            "modLoader=\"javafml\"\n" +
                    "[[mods]]\n" +
                    "modId=\"$modId\"\n" +
                    "[[dependencies.$modId]]\n" +
                    "modId=\"minecraft\"\n" +
                    "side=\"$side\"\n"

    @Test
    fun `TOML 标 BOTH 但平台判定客户端专用时删除`() {
        val jar = modJar("mafglib.jar", toml("mafglib", "BOTH"))

        ClientOnlyModFilter.removeClientOnlyMods(tempDir, mapOf("mafglib.jar" to ApiVerdict.CLIENT_ONLY)) { null }

        assertFalse(jar.exists())
        assertEquals(0, tempDir.listFiles()!!.count { it.name.endsWith(".jar") })
    }

    @Test
    fun `TOML 标 BOTH 且无平台判定时保留`() {
        val jar = modJar("both.jar", toml("both", "BOTH"))

        ClientOnlyModFilter.removeClientOnlyMods(tempDir, emptyMap()) { null }

        assertTrue(jar.exists())
    }

    @Test
    fun `Fabric 未标 client 但平台判定客户端专用时删除`() {
        val jar = modJar("fabricmod.jar", null, "{\"id\":\"fabricmod\",\"environment\":\"*\"}")

        ClientOnlyModFilter.removeClientOnlyMods(tempDir, mapOf("fabricmod.jar" to ApiVerdict.CLIENT_ONLY)) { null }

        assertFalse(jar.exists())
    }

    @Test
    fun `TOML 标 CLIENT 且平台说双端时按用户仲裁`() {
        val jar = modJar("conflict.jar", toml("conflict", "CLIENT"))

        ClientOnlyModFilter.removeClientOnlyMods(tempDir, mapOf("conflict.jar" to ApiVerdict.DUAL)) { "N" }

        assertTrue(jar.exists())
    }

    @Test
    fun `读不到元数据时无论平台判定都保留`() {
        val jar = modJar("plain.jar", null)

        ClientOnlyModFilter.removeClientOnlyMods(tempDir, mapOf("plain.jar" to ApiVerdict.CLIENT_ONLY)) { null }

        assertTrue(jar.exists())
    }
}
