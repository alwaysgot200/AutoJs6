package org.autojs.autojs.mgmt.script

import org.autojs.autojs.mgmt.client.MarketApiClient
import org.autojs.autojs.project.ProjectConfig
import org.autojs.autojs.util.WorkingDirectoryUtils
import java.io.File
import java.util.zip.ZipInputStream

/**
 * 市场脚本安装编排 (方案 §10.2-7, P3)。
 *
 * - 安装根固定为 `<脚本工作目录>/market/` (官方脚本列表直接可见);
 * - **目录短码只信服务端响应头** (`x-install-slug`, 形如 `script-<id>`),
 *   绝不使用 zip 内包裹目录名或 project.json.name 拼路径 (防路径注入/同名覆盖);
 * - SINGLE: 清洁 zip 内仅 1 个脚本文件, 按响应头声明的文件名落盘
 *   `<root>/<slug>/<entryFile>` (仅 .js/.auto), 临时文件同目录 rename 原子替换;
 * - PROJECT: [MarketProjectLayout] 预检 → 解压同卷临时目录 → 官方
 *   [ProjectConfig.fromProjectDir] 二次校验且 main 真实存在 → 目录 rename 原子替换
 *   (旧目录先备份, 失败回滚, 永不留半成品)。
 *
 * 阻塞方法, 仅在后台线程调用。
 */
internal object MarketScriptInstaller {

    data class InstallResult(
        /** 用于成功提示的展示名: PROJECT=project.json.name; SINGLE=脚本文件名 */
        val displayName: String,
        /** 相对脚本工作目录的安装位置, 如 market/script-6/main.js */
        val relativePath: String,
    )

    private val SLUG_PATTERN = Regex("^[A-Za-z0-9._-]{1,64}$")
    private val SINGLE_FILE_PATTERN = Regex("^[^\\\\/]+\\.(js|auto)$", RegexOption.IGNORE_CASE)

    @Synchronized
    fun installBlocking(pkg: MarketApiClient.PackageFile): InstallResult {
        val slug = pkg.installSlug
        if (!SLUG_PATTERN.matches(slug) || slug.startsWith(".")) {
            throw MarketProjectLayout.InvalidPackageException("安装短码非法: $slug")
        }
        val root = File(WorkingDirectoryUtils.path, "market")
        if (!root.exists() && !root.mkdirs()) {
            throw MarketProjectLayout.InvalidPackageException("安装目录创建失败: ${root.path}")
        }
        try {
            val result = when (pkg.packageType) {
                "SINGLE" -> installSingle(root, slug, pkg)
                "PROJECT" -> installProject(root, slug, pkg)
                else -> throw MarketProjectLayout.InvalidPackageException("未知包型: ${pkg.packageType}")
            }
            // 安装成功后删除下载临时包。
            runCatching { pkg.file.delete() }
            return result
        } catch (e: Exception) {
            runCatching { pkg.file.delete() }
            throw e
        }
    }

    // region SINGLE

    private fun installSingle(
        root: File,
        slug: String,
        pkg: MarketApiClient.PackageFile,
    ): InstallResult {
        val fileName = pkg.entryFile.substringAfterLast('/').substringAfterLast('\\')
        if (!SINGLE_FILE_PATTERN.matches(fileName) || fileName.length > 200) {
            throw MarketProjectLayout.InvalidPackageException("单脚本文件名非法: ${pkg.entryFile}")
        }

        val scriptBytes = ZipInputStream(pkg.file.inputStream().buffered()).use { zip ->
            var single: ByteArray? = null
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.name.endsWith('/')) {
                    if (single != null) {
                        throw MarketProjectLayout.InvalidPackageException("单脚本包内存在多个文件")
                    }
                    single = zip.readBytes()
                }
                zip.closeEntry()
            }
            single ?: throw MarketProjectLayout.InvalidPackageException("单脚本包内容为空")
        }

        val targetDir = File(root, slug).apply { if (!exists()) mkdirs() }
        val target = File(targetDir, fileName)
        val temp = File(targetDir, ".${fileName}.install-${System.nanoTime()}.tmp")
        temp.outputStream().use { it.write(scriptBytes) }
        if (!temp.renameTo(target)) {
            // 个别 ROM 上 rename 覆盖已存在文件失败: 先删目标再改名的回退路径 (目标为单文件, 风险可控)。
            if (target.exists() && !target.delete()) {
                temp.delete()
                throw MarketProjectLayout.InvalidPackageException("旧版本文件无法替换: $fileName")
            }
            if (!temp.renameTo(target)) {
                temp.delete()
                throw MarketProjectLayout.InvalidPackageException("文件落盘失败: $fileName")
            }
        }
        return InstallResult(
            displayName = fileName,
            relativePath = "market/$slug/$fileName",
        )
    }

    // endregion

    // region PROJECT

    private fun installProject(
        root: File,
        slug: String,
        pkg: MarketApiClient.PackageFile,
    ): InstallResult {
        // 第一遍: 纯预检不落盘。
        ZipInputStream(pkg.file.inputStream().buffered()).use { zip ->
            MarketProjectLayout.preCheck(zip)
        }

        val tmpRoot = File(root, ".tmp")
        if (!tmpRoot.exists()) tmpRoot.mkdirs()
        val tempDir = File(tmpRoot, "$slug-${pkg.versionCode.ifZero()}")
        if (tempDir.exists()) tempDir.deleteRecursively()
        if (!tempDir.mkdirs()) {
            throw MarketProjectLayout.InvalidPackageException("临时目录创建失败")
        }

        try {
            // 第二遍: 解压到同卷临时目录。
            ZipInputStream(pkg.file.inputStream().buffered()).use { zip ->
                MarketProjectLayout.extract(zip, tempDir)
            }

            // 官方识别口径二次校验: project.json 必备字段齐全 (非 null), 且 main 入口真实存在。
            val config = ProjectConfig.fromProjectDir(tempDir.absolutePath)
                ?: throw MarketProjectLayout.InvalidPackageException("project.json 缺少必需字段 (name/packageName/versionName/main/versionCode)")
            val mainFile = File(tempDir, config.mainScriptFileName)
            if (!mainFile.isFile || !mainFile.canonicalPath.startsWith(tempDir.canonicalPath + File.separator)) {
                throw MarketProjectLayout.InvalidPackageException("入口文件不存在: ${config.mainScriptFileName}")
            }

            // 原子替换: 旧目录改名备份 → 新目录就位 → 成功删备份; 任一步失败回滚备份。
            val target = File(root, slug)
            val backup = File(root, ".$slug.bak")
            if (backup.exists()) backup.deleteRecursively()
            val hadOld = target.exists()
            if (hadOld && !target.renameTo(backup)) {
                throw MarketProjectLayout.InvalidPackageException("旧版本无法移动, 安装中止")
            }
            if (!tempDir.renameTo(target)) {
                if (hadOld) backup.renameTo(target)
                throw MarketProjectLayout.InvalidPackageException("工程目录就位失败")
            }
            if (hadOld) backup.deleteRecursively()

            return InstallResult(
                displayName = config.name,
                relativePath = "market/$slug",
            )
        } finally {
            if (tempDir.exists()) tempDir.deleteRecursively()
            // 清理可能残留的空 .tmp 根目录 (存在其他临时目录时忽略失败)。
            runCatching { tmpRoot.delete() }
        }
    }

    private fun Int.ifZero(): Int = if (this == 0) 1 else this

    // endregion
}
