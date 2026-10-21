package com.nothingmusic.data.artwork

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 内嵌封面落盘缓存：按内容哈希去重，同一专辑的每首歌指向同一个文件。
 * 放在 filesDir 而非 cacheDir，避免系统清缓存后封面在下次扫描前消失。
 */
@Singleton
class LocalArtworkStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun store(bytes: ByteArray): Uri? {
        if (bytes.isEmpty()) return null
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val name = digest.take(16).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        val dir = directory().apply { mkdirs() }
        val file = File(dir, "$name.${extensionOf(bytes)}")
        if (!file.isFile) {
            val tmp = runCatching { File.createTempFile(name, ".tmp", dir) }.getOrNull() ?: return null
            try {
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(file)) {
                    tmp.copyTo(file, overwrite = true)
                    tmp.delete()
                }
            } catch (_: Exception) {
                tmp.delete()
            }
        }
        return file.takeIf { it.isFile }?.let(Uri::fromFile)
    }

    /** 删除不再被任何曲目引用的封面文件（侧挂封面是 content URI，不在此目录内）。 */
    fun prune(referencedUris: Set<String>) {
        val dir = directory().takeIf { it.isDirectory } ?: return
        val referenced = referencedUris.mapNotNullTo(HashSet()) { value ->
            runCatching { Uri.parse(value) }.getOrNull()
                ?.takeIf { it.scheme == "file" }
                ?.toString()
        }
        dir.listFiles()?.forEach { file ->
            if (file.isFile && Uri.fromFile(file).toString() !in referenced) file.delete()
        }
    }

    private fun directory() = File(context.filesDir, DIRECTORY_NAME)

    private fun extensionOf(bytes: ByteArray): String = when {
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "jpg"
        bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() -> "png"
        bytes.size >= 12 && bytes.copyOfRange(0, 4).contentEquals(RIFF) &&
            bytes.copyOfRange(8, 12).contentEquals(WEBP) -> "webp"
        else -> "jpg"
    }

    private companion object {
        const val DIRECTORY_NAME = "local_artwork"
        val RIFF = "RIFF".toByteArray(Charsets.US_ASCII)
        val WEBP = "WEBP".toByteArray(Charsets.US_ASCII)
    }
}
