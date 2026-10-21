package com.nothingmusic.ui.screen.remote

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mica.music.RemoteRuntime
import com.mica.music.data.remote.RemoteSourceStatus
import com.mica.music.data.remote.RemoteSourceType
import com.mica.music.data.remote.RemoteSyncProgress
import com.nothingmusic.ui.component.DotMatrixHeaderTitle
import com.nothingmusic.ui.theme.DotMatrixFont
import com.nothingmusic.ui.theme.NothingSurfaceElevated
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

/**
 * 远程来源管理：添加 / 同步 / 测试连接 / 更新登录信息 / 删除。
 * 视觉对齐「音乐」页：点阵标题、直角卡片、细分割线、无彩色按钮块。
 */
@Composable
fun RemoteSourcesScreen() {
    val manager = RemoteRuntime.sourceManager
    val scope = rememberCoroutineScope()
    var statuses by remember { mutableStateOf<List<RemoteSourceStatus>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    // 按来源隔离忙状态：卡片只在自己同步/测试/轮换时亮，其它卡片不受影响。
    var busySourceId by remember { mutableStateOf<String?>(null) }
    var addType by remember { mutableStateOf<RemoteSourceType?>(null) }
    var rotateTarget by remember { mutableStateOf<RemoteSourceStatus?>(null) }
    var showPicker by remember { mutableStateOf(false) }
    var revision by remember { mutableStateOf(0) }

    LaunchedEffect(revision) {
        statuses = runCatching { manager.statuses() }.getOrDefault(emptyList())
    }

    fun refresh() {
        revision++
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DotMatrixHeaderTitle(text = "云盘")
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = { refresh() }) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = "刷新",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { showPicker = true }) {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = "添加来源",
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
        }

        // 状态行：固定占位，避免出现/消失时整页跳动。
        Text(
            text = message ?: if (busySourceId != null) "处理中…" else "",
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = DotMatrixFont),
            color = if (busySourceId != null) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.primary
            },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .padding(horizontal = 20.dp),
        )

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 20.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            if (statuses.isEmpty()) {
                item {
                    Text(
                        text = "还没有云端来源\n从下方选择一种接入方式",
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = DotMatrixFont),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                    )
                }
            }

            items(statuses, key = { it.instance.id }) { status ->
                SourceRow(
                    status = status,
                    busy = busySourceId == status.instance.id,
                    onSync = {
                        busySourceId = status.instance.id
                        message = "同步中…"
                        scope.launch {
                            runCatching { manager.syncSource(status.instance.id) }
                                .onSuccess { message = "同步完成" }
                                .onFailure { message = "同步失败：${it.message ?: it.javaClass.simpleName}" }
                            busySourceId = null
                            refresh()
                        }
                    },
                    onTest = {
                        busySourceId = status.instance.id
                        message = "正在测试连接…"
                        scope.launch {
                            runCatching { manager.testConnection(status.instance.id) }
                                .onSuccess { message = "连接正常" }
                                .onFailure { message = "连接失败：${it.message ?: it.javaClass.simpleName}" }
                            busySourceId = null
                        }
                    },
                    onRotate = { rotateTarget = status },
                    onDelete = {
                        scope.launch {
                            runCatching { manager.deleteSource(status.instance.id) }
                                .onFailure { message = "删除失败：${it.message}" }
                            refresh()
                        }
                    },
                )
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 20.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }

            item { AddSourceSection(onPick = { showPicker = true }) }
            item { Spacer(modifier = Modifier.height(120.dp)) }
        }
    }

    if (showPicker) {
        SourcePickerSheet(
            onDismiss = { showPicker = false },
            onPick = {
                showPicker = false
                addType = it
            },
        )
    }

    addType?.let { type ->
        AddSourceDialog(
            type = type,
            onDismiss = { addType = null },
            onConfirm = { name, endpoint, username, password ->
                busySourceId = "adding"
                message = "正在添加…"
                scope.launch {
                    runCatching {
                        when (type) {
                            RemoteSourceType.NAVIDROME -> manager.createNavidrome(name, endpoint, username, password)
                            RemoteSourceType.WEBDAV -> manager.createWebDav(name, endpoint, username, password)
                            RemoteSourceType.SMB -> manager.createSmb(name, endpoint, username, password)
                            RemoteSourceType.ALIYUNDRIVE -> manager.createAliyunDrive(name, endpoint, password)
                            RemoteSourceType.ALIST -> manager.createAList(name, endpoint, username, password)
                        }
                    }.onSuccess { message = "已添加 ${it.displayName}，正在同步…" }
                        .onFailure { message = "添加失败：${it.message ?: it.javaClass.simpleName}" }
                    addType = null
                    busySourceId = null
                    refresh()
                }
            },
        )
    }

    rotateTarget?.let { status ->
        RotateDialog(
            status = status,
            onDismiss = { rotateTarget = null },
            onConfirm = { username, secret ->
                busySourceId = status.instance.id
                scope.launch {
                    runCatching {
                        when (status.instance.type) {
                            RemoteSourceType.NAVIDROME ->
                                manager.rotateNavidromeCredentials(status.instance.id, username, secret)
                            RemoteSourceType.WEBDAV ->
                                manager.rotateWebDavCredentials(status.instance.id, username, secret)
                            RemoteSourceType.SMB ->
                                manager.rotateSmbCredentials(status.instance.id, username, secret)
                            RemoteSourceType.ALIYUNDRIVE ->
                                manager.rotateAliyunDriveCredentials(status.instance.id, secret)
                            RemoteSourceType.ALIST ->
                                manager.rotateAListCredentials(status.instance.id, username, secret)
                        }
                    }.onSuccess { message = "已更新登录信息" }
                        .onFailure { message = "更新失败：${it.message ?: it.javaClass.simpleName}" }
                    rotateTarget = null
                    busySourceId = null
                    refresh()
                }
            },
        )
    }
}

/** 单个来源：图标 + 名称/统计/地址 + 右侧圆环同步按钮，操作行用点阵小字。 */
@Composable
private fun SourceRow(
    status: RemoteSourceStatus,
    busy: Boolean,
    onSync: () -> Unit,
    onTest: () -> Unit,
    onRotate: () -> Unit,
    onDelete: () -> Unit,
) {
    val syncLabel = if (status.lastSyncAtMs > 0L) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(status.lastSyncAtMs))
    } else {
        "尚未同步"
    }
    val progressStates by RemoteSyncProgress.states.collectAsState()
    val own = progressStates[status.instance.id]
    val syncAction = when {
        !busy -> "同步"
        own == null -> "同步中…"
        own.stage == RemoteSyncProgress.Stage.METADATA -> "读取标签 ${own.label}"
        else -> "扫描 ${own.label}"
    }

    Column(modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Cloud,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 14.dp),
            ) {
                Text(
                    text = status.instance.displayName,
                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = DotMatrixFont),
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${typeLabel(status.instance.type)} · ${status.trackCount} 首",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Text(
                    text = syncLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                )
            }
            IconButton(onClick = onSync, enabled = !busy) {
                Icon(
                    imageVector = Icons.Outlined.Sync,
                    contentDescription = syncAction,
                    tint = if (busy) MaterialTheme.colorScheme.outline
                    else MaterialTheme.colorScheme.onBackground,
                )
            }
        }

        // 操作行：无背景、无边框的纯文字，靠间距分组，与音乐页列表一致。
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(modifier = Modifier.width(58.dp))
            RowAction(syncAction, !busy, onSync)
            RowAction("测试", !busy, onTest)
            RowAction("登录信息", !busy, onRotate)
            IconButton(onClick = onDelete, enabled = !busy, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun RowAction(label: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium.copy(fontFamily = DotMatrixFont),
        color = if (enabled) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.outline,
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
    )
}

@Composable
private fun AddSourceSection(onPick: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 20.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onPick)
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = "添加来源",
                style = MaterialTheme.typography.titleSmall.copy(fontFamily = DotMatrixFont),
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(start = 10.dp),
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "选择接入方式",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/** 来源分类：每类一组白名单，决定弹窗里的小节顺序与归属。 */
private enum class SourceGroup(val title: String, val kinds: List<SourceKind>) {
    MEDIA(
        "媒体服务器",
        listOf(SourceKind.NAVIDROME),
    ),
    CLOUD(
        "云盘",
        listOf(SourceKind.ALIYUN, SourceKind.ALIST_CLOUD),
    ),
    SHARE(
        "文件共享",
        listOf(SourceKind.SMB, SourceKind.WEBDAV, SourceKind.ALIST),
    ),
    OTHER(
        "其他",
        listOf(SourceKind.OTHER_DRIVE),
    ),
}

/**
 * 弹窗底色：深色主题用 surfaceVariant，浅色主题用纯白。
 * 浅色模式下动态取色的 surfaceVariant 偏灰蓝，对比度与 Nothing 风格不符。
 */
@Composable
private fun sheetContainerColor(): Color {
    val scheme = MaterialTheme.colorScheme
    return if (scheme.background.luminance() > 0.5f) Color.White else scheme.surfaceVariant
}

@Composable
private fun pressHighlight(): Color {
    val scheme = MaterialTheme.colorScheme
    return if (scheme.background.luminance() > 0.5f) {
        Color.Black.copy(alpha = 0.06f)
    } else {
        NothingSurfaceElevated
    }
}

private enum class SourceKind(val type: RemoteSourceType?, val label: String) {
    NAVIDROME(RemoteSourceType.NAVIDROME, "Navidrome"),
    ALIYUN(RemoteSourceType.ALIYUNDRIVE, "阿里云盘"),
    ALIST(RemoteSourceType.ALIST, "OpenList / AList"),
    ALIST_CLOUD(RemoteSourceType.ALIST, "OpenList / AList"),
    SMB(RemoteSourceType.SMB, "SMB"),
    WEBDAV(RemoteSourceType.WEBDAV, "WebDAV"),
    OTHER_DRIVE(null, "其他网盘支持"),
}

/**
 * 添加来源选择面板：分区小标题 + 直角方块入口，不用彩色胶囊。
 * 未原生支持的网盘统一走 OpenList / AList 挂载，所以入口指向 AList。
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun SourcePickerSheet(
    onDismiss: () -> Unit,
    onPick: (RemoteSourceType) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = sheetContainerColor(),
        dragHandle = {
            HorizontalDivider(
                modifier = Modifier.width(48.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "添加来源",
                    style = MaterialTheme.typography.titleSmall.copy(fontFamily = DotMatrixFont),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = "关闭",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            SourceGroup.entries.forEach { group ->
                Text(
                    text = group.title,
                    style = MaterialTheme.typography.titleSmall.copy(fontFamily = DotMatrixFont),
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(top = 20.dp, bottom = 10.dp),
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    group.kinds.forEach { kind ->
                        SourceTile(label = kind.label) {
                            val target = kind.type
                            if (target == null) onPick(RemoteSourceType.ALIST) else onPick(target)
                        }
                    }
                }
            }
        }
    }
}

/** 直角方块入口：描边 + 点阵字，按下时用浅底色反馈。 */
@Composable
private fun SourceTile(label: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = Modifier
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(4.dp),
            )
            .background(
                color = if (pressed) pressHighlight() else Color.Transparent,
                shape = RoundedCornerShape(4.dp),
            )
            .clip(RoundedCornerShape(4.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall.copy(fontFamily = DotMatrixFont),
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

private fun typeLabel(type: RemoteSourceType): String = when (type) {
    RemoteSourceType.NAVIDROME -> "Navidrome"
    RemoteSourceType.WEBDAV -> "WebDAV"
    RemoteSourceType.SMB -> "SMB"
    RemoteSourceType.ALIYUNDRIVE -> "阿里云盘"
    RemoteSourceType.ALIST -> "OpenList/AList"
}

private fun defaultName(type: RemoteSourceType): String = when (type) {
    RemoteSourceType.NAVIDROME -> "Navidrome"
    RemoteSourceType.WEBDAV -> "WebDAV"
    RemoteSourceType.SMB -> "SMB"
    RemoteSourceType.ALIYUNDRIVE -> "阿里云盘"
    RemoteSourceType.ALIST -> "OpenList/AList"
}

private fun defaultEndpoint(type: RemoteSourceType): String = when (type) {
    RemoteSourceType.SMB -> "smb://"
    RemoteSourceType.ALIYUNDRIVE -> "https://openapi.alipan.com"
    // AList 常见局域网自建部署，默认 http 而不是 https。
    RemoteSourceType.ALIST -> "http://"
    else -> "https://"
}

@Composable
private fun AddSourceDialog(
    type: RemoteSourceType,
    onDismiss: () -> Unit,
    onConfirm: (name: String, endpoint: String, username: String, password: String) -> Unit,
) {
    var name by remember { mutableStateOf(defaultName(type)) }
    var endpoint by remember { mutableStateOf(defaultEndpoint(type)) }
    var username by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    val isAliyun = type == RemoteSourceType.ALIYUNDRIVE
    val isAList = type == RemoteSourceType.ALIST
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = defaultName(type).uppercase(),
                style = MaterialTheme.typography.titleSmall.copy(fontFamily = DotMatrixFont),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NothingField(name, { name = it }, "名称")
                NothingField(
                    value = endpoint,
                    onValueChange = { endpoint = it },
                    label = "服务器地址",
                    keyboardType = KeyboardType.Uri,
                )
                if (!isAliyun) {
                    NothingField(username, { username = it }, "用户名")
                }
                NothingField(
                    value = secret,
                    onValueChange = { secret = it },
                    label = if (isAliyun) "refresh_token" else "密码",
                    secret = !isAliyun,
                )
                if (isAList) {
                    Text(
                        text = "游客访问可同时留空用户名与密码",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim(), endpoint.trim(), username.trim(), secret.trim()) }) {
                Text("添加", fontFamily = DotMatrixFont)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", fontFamily = DotMatrixFont) }
        },
    )
}

@Composable
private fun RotateDialog(
    status: RemoteSourceStatus,
    onDismiss: () -> Unit,
    onConfirm: (username: String, secret: String) -> Unit,
) {
    var username by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    val isAliyun = status.instance.type == RemoteSourceType.ALIYUNDRIVE
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = status.instance.displayName.uppercase(),
                style = MaterialTheme.typography.titleSmall.copy(fontFamily = DotMatrixFont),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!isAliyun) {
                    NothingField(username, { username = it }, "用户名")
                }
                NothingField(
                    value = secret,
                    onValueChange = { secret = it },
                    label = if (isAliyun) "refresh_token" else "密码",
                    secret = !isAliyun,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(username.trim(), secret.trim()) }) {
                Text("保存", fontFamily = DotMatrixFont)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", fontFamily = DotMatrixFont) }
        },
    )
}

@Composable
private fun NothingField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, style = MaterialTheme.typography.bodySmall) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = Modifier.fillMaxWidth(),
    )
}
