package com.nothingmusic.data.artwork

/** 从目录内的文件名中挑选专辑封面，忽略 back/scan 之类的无关图片。 */
internal object FolderArtwork {

    private val BASE_PRIORITY = listOf("cover", "folder", "albumart", "album", "front", "artwork")
    private val EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")

    fun pick(names: List<String>): String? {
        var best: String? = null
        var bestPriority = Int.MAX_VALUE
        for (name in names) {
            if (name.substringAfterLast('.', "").lowercase() !in EXTENSIONS) continue
            val priority = BASE_PRIORITY.indexOf(name.substringBeforeLast('.').lowercase())
            if (priority < 0) continue
            if (best == null || priority < bestPriority ||
                (priority == bestPriority && name.lowercase() < best!!.lowercase())
            ) {
                best = name
                bestPriority = priority
            }
        }
        return best
    }
}
