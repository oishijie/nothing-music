package com.mica.music.data.remote

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 进程级同步进度：目录扫描与元数据探测耗时较长（远程元数据要逐首下载文件头尾），
 * UI 需要展示「第 N / 共 M 首」而不是让用户干等。
 *
 * 按来源隔离：多个来源卡片各自订阅自己 sourceInstanceId 的进度，
 * 不能共享一份全局状态，否则 A 源同步时 B 源也会显示同样的进度。
 * 与 RemoteSourceStatus 分开：后者是持久化的曲库状态，这个是瞬时进度。
 */
object RemoteSyncProgress {

    private val _states = MutableStateFlow<Map<String, State>>(emptyMap())
    val states: StateFlow<Map<String, State>> = _states.asStateFlow()

    fun begin(sourceInstanceId: String, total: Int, stage: Stage) {
        _states.value = _states.value + (sourceInstanceId to State(stage = stage, done = 0, total = total))
    }

    fun advance(sourceInstanceId: String, done: Int, total: Int, stage: Stage) {
        _states.value = _states.value + (sourceInstanceId to State(stage = stage, done = done, total = total))
    }

    /** 同步结束即清除进度；卡片回到静止态，计数由持久化的 RemoteSourceStatus 承担。 */
    fun finish(sourceInstanceId: String, trackCount: Int) {
        _states.value = _states.value - sourceInstanceId
    }

    fun fail(sourceInstanceId: String) {
        _states.value = _states.value - sourceInstanceId
    }

    enum class Stage { SCANNING, METADATA }

    data class State(
        val stage: Stage,
        val done: Int = 0,
        val total: Int = 0,
    ) {
        /** 总数未知时（目录扫描阶段）只显示已完成数。 */
        val label: String
            get() = when {
                total > 0 -> "$done / $total"
                else -> "$done"
            }
    }
}
