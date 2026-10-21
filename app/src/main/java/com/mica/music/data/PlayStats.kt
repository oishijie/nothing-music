package com.mica.music.data

/** 播放统计（远程曲库展示用）；宿主 App 若有自己的统计实现可在此对接。 */
data class PlayStats(
    val count: Int,
    val lastPlayedAtMs: Long,
    val totalListenSeconds: Long = 0L,
)

/** 一次曲库准备过程使用的只读播放统计快照。 */
internal class PlayStatsSnapshot private constructor(
    private val statsBySongId: Map<String, PlayStats>,
) : Map<String, PlayStats> by statsBySongId {
    companion object {
        val EMPTY = PlayStatsSnapshot(emptyMap())

        fun of(stats: Map<String, PlayStats>): PlayStatsSnapshot = PlayStatsSnapshot(stats)
    }
}
