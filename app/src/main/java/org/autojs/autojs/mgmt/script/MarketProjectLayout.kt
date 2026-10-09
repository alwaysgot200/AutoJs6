package org.autojs.autojs.mgmt.script

import java.io.File
import java.util.zip.ZipInputStream

/**
 * 市场工程包 (PROJECT) 的 zip 结构预检与解压 (方案 §10.2-7)。
 *
 * 官方 [org.autojs.autojs.io.Zip]/`com.stardust.io.Zip` 直接 `File(dir, entry.name)`
 * 拼接, **自身不含 zip-slip 防护**, 故 mgmt 层必须:
 *  1. 先完整预检一遍 (不落盘): 路径规范化、目录穿越/绝对路径/盘符、空名、数量与总量
 *     上限、压缩比炸弹、`.so` 位置、macOS 垃圾条目;
 *  2. 预检通过后再解压到同卷临时目录;
 *  3. 调用方随后用官方 `ProjectConfig.fromProjectDir` 二次校验并原子替换目标目录。
 *
 * 纯文件工具, 不依赖 Android/官方类, 便于独立推理与测试。
 */
internal object MarketProjectLayout {

    /** 与服务端清洁包同口径的条目/总量上限 (防御兜底, 正常工程远小于此)。 */
    private const val MAX_ENTRIES = 2_000
    private const val MAX_TOTAL_UNCOMPRESSED_BYTES = 200L * 1024 * 1024
    private const val MAX_COMPRESSION_RATIO = 100

    /** `.so` 只允许出现在工程 lib 目录的 ABI 子层 (与官方 APK/工程惯例一致)。 */
    private val SO_PATTERN = Regex("^lib/[^/]+/[^/]+\\.so$", RegexOption.IGNORE_CASE)

    class InvalidPackageException(message: String) : Exception(message)

    /**
     * 第一遍: 纯预检, 不解压任何内容。
     *
     * @throws InvalidPackageException 任一硬性规则不通过
     */
    fun preCheck(zip: ZipInputStream) {
        var entryCount = 0
        var totalBytes = 0L
        while (true) {
            val entry = zip.nextEntry ?: break
            entryCount++
            if (entryCount > MAX_ENTRIES) {
                throw InvalidPackageException("工程文件数量超过上限 ($MAX_ENTRIES)")
            }
            val rawName = entry.name
            // 目录条目 (以 / 结尾) 也必须是安全路径, 但无需读取内容。
            val normalized = normalizeOrThrow(rawName)
            if (skipForNoise(normalized, rawName.endsWith('/'))) {
                zip.closeEntry()
                continue
            }
            if (!rawName.endsWith('/')) {
                val entryBytes = countEntryBytes(zip, entry.compressedSize, normalized)
                totalBytes += entryBytes
                if (totalBytes > MAX_TOTAL_UNCOMPRESSED_BYTES) {
                    throw InvalidPackageException("工程解压后总大小超过上限 (200MB)")
                }
            }
            zip.closeEntry()
        }
    }

    /**
     * 第二遍: 解压到 [targetDir] (须为新建/已清空的同卷临时目录)。
     *
     * 调用前必须已通过 [preCheck]; 解压时仍对每条路径做 canonical 复核 (纵深防御)。
     */
    fun extract(zip: ZipInputStream, targetDir: File) {
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw InvalidPackageException("临时目录创建失败")
        }
        val canonicalRoot = targetDir.canonicalPath
        while (true) {
            val entry = zip.nextEntry ?: break
            val rawName = entry.name
            val normalized = normalizeOrThrow(rawName)
            val isDirectory = rawName.endsWith('/')
            try {
                if (skipForNoise(normalized, isDirectory)) continue

                val out = File(targetDir, normalized)
                // canonical 复核: 规范化后的最终路径必须仍在目标根内 (zip-slip 兜底)。
                if (!out.canonicalPath.startsWith(canonicalRoot + File.separator) &&
                    out.canonicalPath != canonicalRoot
                ) {
                    throw InvalidPackageException("非法解压路径: $rawName")
                }
                if (isDirectory) {
                    if (!out.exists() && !out.mkdirs()) {
                        throw InvalidPackageException("目录创建失败: $normalized")
                    }
                    continue
                }
                out.parentFile?.let { if (!it.exists() && !it.mkdirs()) throw InvalidPackageException("目录创建失败: $normalized") }
                // 必须在 closeEntry 之前读空当前条目内容, 否则读到的是下一条目边界 (空)。
                zip.copyTo(out.outputStream(), 64 * 1024)
            } finally {
                zip.closeEntry()
            }
        }
    }

    /**
     * 规范化 zip entry 名; 返回相对路径 (以 `/` 分隔); 硬性非法路径直接拒绝。
     */
    private fun normalizeOrThrow(rawName: String): String {
        if (rawName.isEmpty()) throw InvalidPackageException("压缩包内存在空文件名条目")
        var path = rawName.replace('\\', '/').trim()
        if (path.endsWith('/')) path = path.dropLast(1)
        if (path.isEmpty()) return ""

        if (path.startsWith('/') || path.startsWith("//")) {
            throw InvalidPackageException("压缩包含绝对路径条目: $rawName")
        }
        // Windows 盘符 (C:/) 与 UNC (//server)。
        if (Regex("^[a-zA-Z]:").containsMatchIn(path)) {
            throw InvalidPackageException("压缩包含盘符路径条目: $rawName")
        }
        val segments = path.split('/')
        for (segment in segments) {
            when (segment) {
                "", ".", ".." -> throw InvalidPackageException("压缩包含非法路径片段: $rawName")
            }
            if (segment.any { it.code in 0..31 }) {
                throw InvalidPackageException("文件名含控制字符: $rawName")
            }
        }
        if (path.endsWith(".so", ignoreCase = true) && !SO_PATTERN.matches(path)) {
            throw InvalidPackageException("原生库 (.so) 位置非法, 只允许 lib/<abi>/ 下: $rawName")
        }
        return path
    }

    /** 非阻断性噪音条目: macOS 资源叉与目录杂项, 清洁包不应包含, 客户端直接跳过。 */
    private fun skipForNoise(normalized: String, isDirectory: Boolean): Boolean {
        if (normalized.isEmpty()) return true
        if (normalized.startsWith("__MACOSX/")) return true
        if (normalized == ".DS_Store" || normalized.endsWith("/.DS_Store")) return true
        return false
    }

    /**
     * 统计单条目字节并做压缩比炸弹防护。
     *
     * ZipInputStream 在 closeEntry 前必须读空条目数据, 预检阶段不允许落盘,
     * 故读入丢弃型 OutputStream (仅计数, 不占内存)。
     */
    private fun countEntryBytes(zip: ZipInputStream, compressedSize: Long, name: String): Long {
        val discarding = object : java.io.OutputStream() {
            var count = 0L
            override fun write(b: Int) {
                count++
            }
            override fun write(b: ByteArray, off: Int, len: Int) {
                count += len
            }
        }
        val bytes = zip.copyTo(discarding, 64 * 1024)
        // entry.compressedSize 为压缩后大小; 解压后远超压缩体积即疑似 zip 炸弹。
        if (compressedSize > 0 && bytes > compressedSize * MAX_COMPRESSION_RATIO) {
            throw InvalidPackageException("压缩比异常, 疑似压缩炸弹: $name")
        }
        return bytes
    }
}
