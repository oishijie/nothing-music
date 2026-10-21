package com.nothingmusic.util

import android.net.Uri
import android.provider.DocumentsContract
import java.security.MessageDigest

/** SAF 树 URI 的解析与稳定标识工具。 */
object SafUtils {

    /**
     * 由文档 URI 派生稳定 Long 主键：同一文件重扫后 id 不变，
     * 收藏/歌单引用不会因重新扫描而失效。
     */
    fun stableTrackId(documentUri: String): Long {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(documentUri.toByteArray(Charsets.UTF_8))
        var value = 0L
        for (i in 0 until 8) {
            value = (value shl 8) or (digest[i].toLong() and 0xFF)
        }
        return (value and Long.MAX_VALUE).takeIf { it != 0L } ?: 1L
    }

    fun treeDocumentId(treeUri: String): String? =
        runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(treeUri)) }.getOrNull()

    /** child 是否是 parent 目录树内的子目录。 */
    fun isNestedTree(childTreeUri: String, parentTreeUri: String): Boolean {
        val child = treeDocumentId(childTreeUri) ?: return false
        val parent = treeDocumentId(parentTreeUri) ?: return false
        return parent.isNotBlank() && child.startsWith("$parent/")
    }

    /** 从树 URI 推断展示名；provider 返回的 DocumentFile 名称优先于该回退。 */
    fun treeDisplayName(treeUri: String): String {
        val uri = Uri.parse(treeUri)
        val documentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            ?: return uri.lastPathSegment.orEmpty()
        val volume = documentId.substringBefore(':', "")
        val path = documentId.substringAfter(':', "")
        val leaf = path.substringAfterLast('/')
        if (leaf.isNotBlank()) return leaf
        return if (volume.isBlank() || volume == "primary") "Internal storage" else volume
    }
}
