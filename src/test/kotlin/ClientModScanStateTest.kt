import atm.bloodworkxgaming.serverstarter.util.ClientModScanState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [ClientModScanState] 测试：mods/ 指纹 + 「已扫描」标记文件。
 *
 * 作用：已装好的服务器每次启动都能补跑客户端模组清理，但不能每次都重新哈希上百个 jar 并联网，
 * 也不能在扫描失败（离线）时把标记写死。
 */
class ClientModScanStateTest {

    private val tempDir: File = Files.createTempDirectory("clientmod-state").toFile()

    @After
    fun cleanup() {
        tempDir.deleteRecursively()
    }

    private fun jar(name: String, content: String, mtime: Long? = null): File {
        val file = File(tempDir, name)
        file.writeText(content)
        if (mtime != null) file.setLastModified(mtime)
        return file
    }

    @Test
    fun `目录不存在时指纹是 empty`() {
        assertEquals("empty", ClientModScanState.fingerprint(File(tempDir, "no-such-dir")))
    }

    @Test
    fun `同样的文件得到同样的指纹，与创建顺序无关`() {
        jar("b.jar", "b")
        jar("a.jar", "a")
        val first = ClientModScanState.fingerprint(tempDir)
        jar("c.jar", "c")
        val second = ClientModScanState.fingerprint(tempDir)
        File(tempDir, "c.jar").delete()
        val third = ClientModScanState.fingerprint(tempDir)

        assertEquals(first, third)
        assertNotEquals(first, second)
        assertEquals(40, third.length)
    }

    @Test
    fun `文件大小或修改时间变化都会改变指纹`() {
        val file = jar("a.jar", "a", mtime = 1_600_000_000_000L)
        val before = ClientModScanState.fingerprint(tempDir)

        file.writeText("longer content")
        val afterSize = ClientModScanState.fingerprint(tempDir)
        assertNotEquals(before, afterSize)

        file.setLastModified(1_700_000_000_000L)
        assertNotEquals(afterSize, ClientModScanState.fingerprint(tempDir))
    }

    @Test
    fun `非 jar 文件不参与指纹`() {
        jar("a.jar", "a")
        val before = ClientModScanState.fingerprint(tempDir)
        File(tempDir, "notes.txt").writeText("hello")
        File(tempDir, "mods.toml").writeText("x")

        assertEquals(before, ClientModScanState.fingerprint(tempDir))
    }

    @Test
    fun `标记可以读出写入的值，空文件与不存在的文件都是 null`() {
        val marker = File(tempDir.parentFile, "clientmod-state.marker")
        marker.delete()
        assertNull(ClientModScanState.read(marker))

        ClientModScanState.write(marker, "abc123")
        assertEquals("abc123", ClientModScanState.read(marker))

        marker.writeText("   ")
        assertNull(ClientModScanState.read(marker))
        marker.delete()
    }
}
