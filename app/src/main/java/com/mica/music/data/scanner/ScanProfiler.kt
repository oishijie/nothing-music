package com.mica.music.data.scanner

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.mica.music.data.DsdSupport
import com.mica.music.data.Song
import com.mica.music.data.SongIdentity
import com.mica.music.util.DiagnosticLog
import com.mica.music.util.DiagnosticDetailDomain
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private const val ScanPerfTag = "ScanPerf"
/** Bumped when lyric ingestion semantics change; stale embedded lyric caches are re-probed. */
internal const val CURRENT_EMBEDDED_LYRICS_PROBE_VERSION = 2

private fun embeddedLyricsProbeRevision(
    songId: String,
    sizeBytes: Long,
    dateModifiedMs: Long,
): String = buildString {
    append(CURRENT_EMBEDDED_LYRICS_PROBE_VERSION)
    append('\u0001')
    append(songId)
    append('\u0001')
    append(sizeBytes.coerceAtLeast(0L))
    append('\u0001')
    append(dateModifiedMs.coerceAtLeast(0L))
}

internal class ScanProfiler(
    private val source: String,
    private val enabled: Boolean = DiagnosticLog.isDetailedEnabled(DiagnosticDetailDomain.LIBRARY_SCAN),
) {
    private val startedAtNs = if (enabled) SystemClock.elapsedRealtimeNanos() else 0L
    private val stages = ConcurrentHashMap<String, Stage>()
    private val byteStages = ConcurrentHashMap<String, ByteStage>()
    private val reuseMisses = ConcurrentHashMap<String, AtomicInteger>()

    fun <T> measure(stage: String, block: () -> T): T {
        if (!enabled) return block()
        val start = SystemClock.elapsedRealtimeNanos()
        return try {
            block()
        } finally {
            record(stage, SystemClock.elapsedRealtimeNanos() - start)
        }
    }

    suspend fun <T> measureSuspend(stage: String, block: suspend () -> T): T {
        if (!enabled) return block()
        val start = SystemClock.elapsedRealtimeNanos()
        return try {
            block()
        } finally {
            record(stage, SystemClock.elapsedRealtimeNanos() - start)
        }
    }

    fun record(stage: String, elapsedNs: Long) {
        if (!enabled) return
        val item = stages.computeIfAbsent(stage) { Stage() }
        item.count.incrementAndGet()
        item.totalNs.addAndGet(elapsedNs)
    }

    fun recordBytes(stage: String, byteCount: Long) {
        if (!enabled) return
        val item = byteStages.computeIfAbsent(stage) { ByteStage() }
        item.count.incrementAndGet()
        item.totalBytes.addAndGet(byteCount.coerceAtLeast(0L))
    }

    fun recordReuseMiss(reason: String) {
        if (!enabled) return
        reuseMisses.computeIfAbsent(reason) { AtomicInteger(0) }.incrementAndGet()
    }

    fun finish(total: Int, reused: Int, probed: Int): String {
        if (!enabled) return ""
        val totalMs = (SystemClock.elapsedRealtimeNanos() - startedAtNs).nanosToMs()
        val stageSummary = stages.entries
            .sortedByDescending { it.value.totalNs.get() }
            .joinToString(" | ") { (name, stage) ->
                val count = stage.count.get()
                val totalStageMs = stage.totalNs.get().nanosToMs()
                val avgMs = if (count > 0) totalStageMs / count else 0
                "$name=${totalStageMs}ms/${count}x(avg ${avgMs}ms)"
            }
        val byteSummary = byteStages.entries
            .sortedByDescending { it.value.totalBytes.get() }
            .joinToString(" | ") { (name, stage) ->
                val count = stage.count.get()
                val totalBytes = stage.totalBytes.get()
                val totalMiB = totalBytes / (1024L * 1024L)
                val avgKiB = if (count > 0) totalBytes / count / 1024L else 0L
                "$name=${totalMiB}MiB/${count}x(avg ${avgKiB}KiB)"
            }
        val reuseMissSummary = reuseMisses.entries
            .sortedByDescending { it.value.get() }
            .joinToString(",") { (reason, count) -> "$reason=${count.get()}" }
        return buildString {
            append("source=$source wall=${totalMs}ms tracks=$total reused=$reused probed=$probed")
            append(" stages(cumulative): $stageSummary")
            if (byteSummary.isNotEmpty()) append(" bytes(cumulative): $byteSummary")
            if (reuseMissSummary.isNotEmpty()) append(" reuseMisses=$reuseMissSummary")
        }
            .also {
                Log.i(ScanPerfTag, it)
                DiagnosticLog.event(ScanPerfTag, it)
            }
    }

    private class Stage {
        val count = AtomicInteger(0)
        val totalNs = AtomicLong(0)
    }


    private class ByteStage {
        val count = AtomicInteger(0)
        val totalBytes = AtomicLong(0)
    }
}

/** 纳秒转毫秒，供扫描耗时统计使用。 */
internal fun Long.nanosToMs(): Long = this / 1_000_000L
