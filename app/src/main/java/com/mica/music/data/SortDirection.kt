package com.mica.music.data

/** 排序方向；远程曲库排序与发布日期比较使用。 */
enum class SortDirection(val storageValue: String, val label: String) {
    ASC("asc", "升序"),
    DESC("desc", "降序"),
    ;

    companion object {
        fun fromStorage(value: String?): SortDirection =
            entries.firstOrNull { it.storageValue == value } ?: ASC
    }
}
