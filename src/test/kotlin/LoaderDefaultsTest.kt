import atm.bloodworkxgaming.serverstarter.util.LoaderDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * loader 默认值推导测试（不联网）：归一化 loader 名、组合 loader 身份、推导安装器地址与安装器参数。
 */
class LoaderDefaultsTest {

    @Test
    fun `归一化 loader 名`() {
        assertEquals("fabric", LoaderDefaults.normalizeName("fabric-loader"))
        assertEquals("fabric", LoaderDefaults.normalizeName("Fabric"))
        assertEquals("neoforge", LoaderDefaults.normalizeName("neoforge"))
        assertEquals("forge", LoaderDefaults.normalizeName("Forge"))
        assertNull(LoaderDefaults.normalizeName("quilt-loader"))
        assertNull(LoaderDefaults.normalizeName(""))
        assertNull(LoaderDefaults.normalizeName(null))
    }

    @Test
    fun `组合 loader 身份：名字来自 manifest`() {
        assertEquals(LoaderDefaults.LoaderId("neoforge", "21.1.249"),
                LoaderDefaults.resolveLoader("neoforge", "21.1.249"))
        assertEquals(LoaderDefaults.LoaderId("fabric", "0.16.5"),
                LoaderDefaults.resolveLoader("fabric-loader", "0.16.5"))
    }

    @Test
    fun `组合 loader 身份：名字缺失时从版本串里拆`() {
        assertEquals(LoaderDefaults.LoaderId("neoforge", "21.1.249"),
                LoaderDefaults.resolveLoader(null, "neoforge-21.1.249"))
        assertEquals(LoaderDefaults.LoaderId("forge", "14.23.5.2859"),
                LoaderDefaults.resolveLoader(null, "forge-1.12.2-14.23.5.2859"))
    }

    @Test
    fun `组合 loader 身份：信息不足时返回 null`() {
        assertNull(LoaderDefaults.resolveLoader(null, "0.16.5"))
        assertNull(LoaderDefaults.resolveLoader("quilt-loader", "0.24.0"))
        assertNull(LoaderDefaults.resolveLoader("fabric", ""))
        assertNull(LoaderDefaults.resolveLoader("fabric", "NONE"))
    }

    @Test
    fun `NeoForge 安装器地址不带 MC 版本`() {
        val id = LoaderDefaults.LoaderId("neoforge", "21.1.249")

        assertEquals(
                "https://maven.neoforged.net/releases/net/neoforged/neoforge/21.1.249/neoforge-21.1.249-installer.jar",
                LoaderDefaults.installerUrl(id, "1.21.1"))
    }

    @Test
    fun `Forge 安装器地址带 MC 版本并按版本切换 maven 主机`() {
        assertEquals(
                "https://maven.minecraftforge.net/net/minecraftforge/forge/1.20.1-47.2.0/forge-1.20.1-47.2.0-installer.jar",
                LoaderDefaults.installerUrl(LoaderDefaults.LoaderId("forge", "47.2.0"), "1.20.1"))
        assertEquals(
                "https://files.minecraftforge.net/maven/net/minecraftforge/forge/1.12.2-14.23.5.2859/forge-1.12.2-14.23.5.2859-installer.jar",
                LoaderDefaults.installerUrl(LoaderDefaults.LoaderId("forge", "14.23.5.2859"), "1.12.2"))
    }

    @Test
    fun `Fabric 安装器地址是 meta 接口前缀`() {
        assertEquals("https://meta.fabricmc.net/v2/versions/loader/1.21.1/0.16.5",
                LoaderDefaults.installerUrl(LoaderDefaults.LoaderId("fabric", "0.16.5"), "1.21.1"))
    }

    @Test
    fun `安装器参数按 loader 区分`() {
        assertEquals(listOf("--installServer"), LoaderDefaults.installerArguments(LoaderDefaults.LoaderId("forge", "47.2.0")))
        assertEquals(listOf("--installServer"), LoaderDefaults.installerArguments(LoaderDefaults.LoaderId("neoforge", "21.1.249")))
        assertTrue(LoaderDefaults.installerArguments(LoaderDefaults.LoaderId("fabric", "0.16.5")).isEmpty())
    }
}
