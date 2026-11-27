package com.wangzan101.mika.voice.ondevice

import android.os.StatFs
import java.io.File
import java.io.IOException

/** 一个语音包在本机的状态,设置页的卡片按它显示。 */
enum class VoicePackStatus {
    NotInstalled,
    Installed,

    /** 当前版本的目录在,但文件缺了或大小对不上;要重新下载。 */
    Corrupt,

    /** 只装着旧版本:照常能用([VoicePackStore.installedAny]),卡片上提示有新版本,由用户决定更不更新。 */
    UpdateAvailable,
}

/**
 * 语音包在本机的存放:`<root>/<包id>/<版本>/<文件名>`,root 是 `filesDir/voice-models`(备份不包含这个目录)。
 * 下载和导入先写进 `<root>/.staging/<包id>-<版本>/`,全部通过后 [commit] 一次改名生效,再删旧版本;
 * 中途失败只丢 staging,已装好的版本不受影响。
 *
 * 加载时只核对文件大小,不重算哈希(几百 MB 太慢);哈希在下载、导入完成时校验。
 * [freeSpace] 只为测试可换,默认用 [StatFs] 取 root 所在分区的可用空间。
 */
class VoicePackStore(
    private val root: File,
    private val freeSpace: (File) -> Long = { StatFs(it.path).availableBytes },
) {
    private val staging get() = File(root, STAGING)

    /** 当前版本装全了(每个文件都在、大小和清单一致)时返回它,否则 null。 */
    fun installed(entry: VoicePackEntry): InstalledVoicePack? {
        val dir = versionDir(entry.id, entry.version)
        return if (complete(entry, dir)) InstalledVoicePack(entry, dir) else null
    }

    /**
     * 能用的那一份:当前版本装全了就是它;否则是已装的旧版本里最新的一个。
     * 旧版本的大小清单里没有,只看文件(按当前清单的文件名)都在。
     */
    fun installedAny(entry: VoicePackEntry): InstalledVoicePack? =
        installed(entry) ?: olderVersions(entry).firstOrNull { (_, dir) -> filesPresent(entry, dir) }
            ?.let { (_, dir) -> InstalledVoicePack(entry, dir) }

    fun status(entry: VoicePackEntry): VoicePackStatus = when {
        installed(entry) != null -> VoicePackStatus.Installed
        versionDir(entry.id, entry.version).exists() -> VoicePackStatus.Corrupt
        installedAny(entry) != null -> VoicePackStatus.UpdateAvailable
        else -> VoicePackStatus.NotInstalled
    }

    /** 这个包这个版本的 staging 目录,每次都是新建的空目录(上次没下完的残留清掉)。 */
    fun stagingDir(entry: VoicePackEntry): File {
        val dir = File(staging, "${safeId(entry.id)}-${entry.version}")
        dir.deleteRecursively()
        if (!dir.mkdirs()) throw IOException("cannot create $dir")
        return dir
    }

    /**
     * 把下好的 [stagingDir] 改名为 `<id>/<版本>/` 生效,再删掉这个包的其他版本。
     * staging 里文件不全或大小不对时抛 [IOException],什么都不动;改名失败也抛 [IOException],原来的版本留着。
     */
    fun commit(entry: VoicePackEntry, stagingDir: File): InstalledVoicePack {
        if (!complete(entry, stagingDir)) throw IOException("voice pack ${entry.id} staging is incomplete")
        val target = versionDir(entry.id, entry.version)
        target.parentFile?.mkdirs()
        // 同一版本重新下载:先把旧的挪开,新的改名成功后再删,失败就挪回来
        val previous = File(staging, "${safeId(entry.id)}-${entry.version}.previous")
        previous.deleteRecursively()
        if (target.exists() && !target.renameTo(previous)) throw IOException("cannot move aside $target")
        if (!stagingDir.renameTo(target)) {
            if (previous.exists()) previous.renameTo(target)
            throw IOException("cannot move $stagingDir to $target")
        }
        previous.deleteRecursively()
        target.parentFile?.listFiles()?.filter { it != target }?.forEach { it.deleteRecursively() }
        return InstalledVoicePack(entry, target)
    }

    /** 删掉这个包的所有版本。 */
    fun delete(id: String) {
        File(root, safeId(id)).deleteRecursively()
    }

    /**
     * App 启动时调一次(后台线程):清掉 staging 残留、清单里已经没有的包、每个包除「能用的那一份」以外的版本。
     * 一份能用的都没有时留着当前版本目录(状态显示「已损坏」,提示重新下载),其余删掉。
     */
    fun cleanup(catalog: VoicePackCatalog) {
        if (!root.isDirectory) return
        staging.deleteRecursively()
        root.listFiles()?.forEach { idDir ->
            val entry = catalog.entry(idDir.name)
            if (entry == null || !idDir.isDirectory) {
                idDir.deleteRecursively()
                return@forEach
            }
            val keep = installedAny(entry)?.dir ?: versionDir(entry.id, entry.version)
            idDir.listFiles()?.filter { it != keep }?.forEach { it.deleteRecursively() }
        }
    }

    /** root 所在分区的可用字节数;下载、导入前要求至少有包大小加 [SPACE_MARGIN]。 */
    fun freeBytes(): Long {
        root.mkdirs()
        return freeSpace(root)
    }

    private fun versionDir(id: String, version: Int) = File(File(root, safeId(id)), version.toString())

    /** 比当前版本旧的版本目录,新的在前。 */
    private fun olderVersions(entry: VoicePackEntry): List<Pair<Int, File>> =
        File(root, safeId(entry.id)).listFiles().orEmpty()
            .mapNotNull { dir -> dir.name.toIntOrNull()?.takeIf { it < entry.version && dir.isDirectory }?.let { it to dir } }
            .sortedByDescending { it.first }

    private fun complete(entry: VoicePackEntry, dir: File): Boolean =
        dir.isDirectory && entry.files.all { f -> File(dir, f.name).let { it.isFile && it.length() == f.size } }

    private fun filesPresent(entry: VoicePackEntry, dir: File): Boolean =
        dir.isDirectory && entry.files.all { File(dir, it.name).isFile }

    /** 包 id 当目录名用,不能带路径分隔符、不能是 `.`/`..`,也不能和 staging 目录重名。 */
    private fun safeId(id: String): String {
        require(id.isNotEmpty() && id != "." && id != ".." && id != STAGING && '/' !in id && '\\' !in id) {
            "invalid voice pack id '$id'"
        }
        return id
    }

    companion object {
        /** 剩余空间要比包大小多出的余量。 */
        const val SPACE_MARGIN = 50L * 1024 * 1024
        private const val STAGING = ".staging"
    }
}
