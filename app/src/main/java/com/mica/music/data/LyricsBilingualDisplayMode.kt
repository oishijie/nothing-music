package com.mica.music.data

/** 双语歌词展示模式；远程歌词文档在选择行时使用。 */
enum class LyricsBilingualDisplayMode(
    val storageValue: String,
    val settingsLabel: String,
) {
    ALL("all", "全部歌词"),
    ORIGINAL("original", "仅原歌词"),
    TRANSLATION("translation", "仅翻译歌词"),
    ;

    companion object {
        fun fromStorage(value: String?): LyricsBilingualDisplayMode =
            entries.firstOrNull { it.storageValue == value } ?: ALL
    }
}
