import atm.bloodworkxgaming.serverstarter.util.PrismInstance
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Prism / MultiMC 启动器实例导出的解析测试（不联网）。
 *
 * 实例导出不是"整合包格式"：instance.cfg + mmc-pack.json（版本在这里）+ 已展开的游戏目录
 * （minecraft/ 或老启动器的 .minecraft/）。这几项决定解压前缀与 loader 版本，漏了就装出一台空服务器。
 */
class PrismInstanceTest {

    private val tempDir: File = Files.createTempDirectory("prism-instance").toFile()

    @After
    fun cleanup() {
        tempDir.deleteRecursively()
    }

    private fun zip(name: String, entries: Map<String, String>): File {
        val file = File(tempDir, name)
        ZipOutputStream(file.outputStream()).use { zos ->
            entries.forEach { (path, content) ->
                zos.putNextEntry(ZipEntry(path))
                zos.write(content.toByteArray())
                zos.closeEntry()
            }
        }
        return file
    }

    private fun mmc(vararg components: Pair<String, String>) = components.joinToString(",", """{"formatVersion":1,"components":[""", "]}") {
        """{"uid":"${it.first}","version":"${it.second}"}"""
    }

    @Test
    fun `识别现代实例的游戏目录与版本`() {
        val pack = zip("modern.zip", mapOf(
                "instance.cfg" to "[General]",
                "mmc-pack.json" to mmc("net.minecraft" to "1.20.1", "net.minecraftforge" to "47.4.20"),
                "minecraft/mods/foo.jar" to "jar"))

        val meta = PrismInstance.read(pack)

        assertEquals("minecraft/", meta?.prefix)
        assertEquals("mmc-pack.json", meta?.mmcPack)
        assertEquals("1.20.1", meta?.mcVersion)
        assertEquals("forge", meta?.loaderName)
        assertEquals("47.4.20", meta?.loaderVersion)
    }

    @Test
    fun `识别老启动器的点 minecraft 目录`() {
        val pack = zip("legacy-dir.zip", mapOf(
                "instance.cfg" to "[General]",
                "mmc-pack.json" to mmc("net.minecraft" to "1.12.2", "net.minecraftforge" to "14.23.5.2859"),
                ".minecraft/mods/foo.jar" to "jar"))

        assertEquals(".minecraft/", PrismInstance.read(pack)?.prefix)
    }

    @Test
    fun `外面还套了一层目录时前缀包含该目录`() {
        val pack = zip("nested.zip", mapOf(
                "Instance/mmc-pack.json" to mmc("net.minecraft" to "1.20.1", "net.neoforged" to "21.1.249"),
                "Instance/minecraft/mods/foo.jar" to "jar"))

        val meta = PrismInstance.read(pack)
        assertEquals("Instance/minecraft/", meta?.prefix)
        assertEquals("Instance/mmc-pack.json", meta?.mmcPack)
        assertEquals("neoforge", meta?.loaderName)
        assertEquals("21.1.249", meta?.loaderVersion)
    }

    @Test
    fun `forge 版本带 MC 前缀时剥掉`() {
        val pack = zip("legacy-forge.zip", mapOf(
                "mmc-pack.json" to mmc("net.minecraft" to "1.12.2", "net.minecraftforge" to "1.12.2-14.23.5.2859"),
                "minecraft/mods/foo.jar" to "jar"))

        assertEquals("14.23.5.2859", PrismInstance.read(pack)?.loaderVersion)
    }

    @Test
    fun `fabric 的组件 uid 映射为 fabric`() {
        val pack = zip("fabric.zip", mapOf(
                "mmc-pack.json" to mmc("net.minecraft" to "1.21.1", "net.fabricmc.fabric-loader" to "0.16.9"),
                "minecraft/mods/foo.jar" to "jar"))

        val meta = PrismInstance.read(pack)
        assertEquals("fabric", meta?.loaderName)
        assertEquals("0.16.9", meta?.loaderVersion)
    }

    @Test
    fun `普通 zip 不是实例`() {
        val pack = zip("plain.zip", mapOf("config/x.toml" to "a=1"))

        assertNull(PrismInstance.read(pack))
        assertNull(PrismInstance.versions(pack))
    }

    @Test
    fun `只有 instance_cfg 没有游戏目录时前缀为空`() {
        val pack = zip("metadata-only.zip", mapOf(
                "instance.cfg" to "[General]",
                "mmc-pack.json" to mmc("net.minecraft" to "1.20.1")))

        val meta = PrismInstance.read(pack)
        assertEquals("", meta?.prefix)
        assertEquals("1.20.1", meta?.mcVersion)
    }

    @Test
    fun `versions 汇总为清单兜底版本`() {
        val pack = zip("versions.zip", mapOf(
                "mmc-pack.json" to mmc("net.minecraft" to "1.20.1", "net.minecraftforge" to "47.4.20"),
                "minecraft/mods/foo.jar" to "jar"))

        val versions = PrismInstance.versions(pack)
        assertEquals("1.20.1", versions?.mcVersion)
        assertEquals("47.4.20", versions?.loaderVersion)
        assertEquals("forge", versions?.loaderName)
    }
}
