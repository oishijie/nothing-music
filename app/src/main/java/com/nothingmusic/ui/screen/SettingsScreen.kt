package com.nothingmusic.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PlaylistRemove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.nothingmusic.R
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import com.nothingmusic.util.AppLanguage
import com.nothingmusic.util.AppThemeMode
import com.nothingmusic.util.AppThemeModeState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.nothingmusic.ui.component.DotMatrixHeaderTitle
import com.nothingmusic.ui.component.DotMatrixText
import com.nothingmusic.ui.theme.DotMatrixFont

@Composable
fun SettingsScreen(
    onNavigateToEqualizer: () -> Unit,
    onNavigateToFolderPicker: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
    onLanguageChanged: () -> Unit = {},
) {
    val context = LocalContext.current
    val playlists by viewModel.playlists.collectAsState()
    var showAbout by remember { mutableStateOf(false) }
    var playlistToDelete by remember { mutableStateOf<Long?>(null) }

Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DotMatrixHeaderTitle(text = stringResource(R.string.settings))
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item { SectionLabel(stringResource(R.string.section_sound)) }
            item {
                SettingsItem(
                    icon = Icons.Outlined.GraphicEq,
                    title = stringResource(R.string.equalizer),
                    subtitle = stringResource(R.string.bass_treble_presets_virtualizer),
                    onClick = onNavigateToEqualizer,
                )
            }
            item { SectionLabel(stringResource(R.string.section_library)) }
            item {
                SettingsItem(
                    icon = Icons.Outlined.FolderOpen,
                    title = stringResource(R.string.manage_folders),
                    subtitle = stringResource(R.string.choose_which_folders_to_scan),
                    onClick = onNavigateToFolderPicker,
                )
            }

            item { SectionLabel(stringResource(R.string.section_language)) }
            item {
                var language by remember { mutableStateOf(AppLanguage.current(context)) }
                var showLanguageDialog by remember { mutableStateOf(false) }
                SettingsItem(
                    icon = Icons.Outlined.Language,
                    title = stringResource(R.string.language),
                    subtitle = AppLanguage.label(context),
                    onClick = { showLanguageDialog = true },
                )
                if (showLanguageDialog) {
                    AlertDialog(
                        onDismissRequest = { showLanguageDialog = false },
                        title = { Text(stringResource(R.string.language)) },
                        text = {
                            Column {
                                AppLanguage.options.forEach { (value, label) ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                AppLanguage.apply(context, value)
                                                language = value
                                                showLanguageDialog = false
                                                onLanguageChanged()
                                            }
                                            .padding(vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        RadioButton(
                                            selected = language == value,
                                            onClick = null,
                                        )
                                        Text(
                                            text = label,
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.padding(start = 8.dp),
                                        )
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showLanguageDialog = false }) {
                                Text(stringResource(R.string.close))
                            }
                        },
                    )
                }
            }

            item { SectionLabel(stringResource(R.string.section_theme)) }
            item {
                var themeMode by AppThemeModeState.mode
                var showThemeDialog by remember { mutableStateOf(false) }
                SettingsItem(
                    icon = Icons.Outlined.Palette,
                    title = stringResource(R.string.theme_mode),
                    subtitle = stringResource(AppThemeMode.labelRes(context)),
                    onClick = { showThemeDialog = true },
                )
                if (showThemeDialog) {
                    AlertDialog(
                        onDismissRequest = { showThemeDialog = false },
                        title = { Text(stringResource(R.string.theme_mode)) },
                        text = {
                            Column {
                                AppThemeMode.options.forEach { (value, labelRes) ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                AppThemeMode.apply(context, value)
                                                themeMode = value
                                                showThemeDialog = false
                                            }
                                            .padding(vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        RadioButton(
                                            selected = themeMode == value,
                                            onClick = null,
                                        )
                                        Text(
                                            text = stringResource(labelRes),
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.padding(start = 8.dp),
                                        )
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showThemeDialog = false }) {
                                Text(stringResource(R.string.close))
                            }
                        },
                    )
                }
            }

            item { SectionLabel(stringResource(R.string.section_playlists) + " (${playlists.size})") }
            if (playlists.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.no_playlists_yet),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
            } else {
                items(playlists, key = { it.id }) { playlist ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { }
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DotMatrixText(
                            text = playlist.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "${playlist.trackCount}",
                            style = MaterialTheme.typography.labelMedium.copy(fontFamily = DotMatrixFont),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        IconButton(onClick = { playlistToDelete = playlist.id }) {
                            Icon(
                                imageVector = Icons.Outlined.PlaylistRemove,
                                contentDescription = "Delete playlist",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            item { Spacer(modifier = Modifier.height(24.dp)) }

            item { SectionLabel("ABOUT") }
            item {
                SettingsItem(
                    icon = Icons.Outlined.Info,
                    title = stringResource(R.string.about_nothing_music),
                    subtitle = stringResource(R.string.version_1_0_0),
                    onClick = { showAbout = true },
                )
            }
            item { Spacer(modifier = Modifier.height(32.dp)) }
        }
    }

    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = {
                Text(
                    text = stringResource(R.string.brand_name),
                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = DotMatrixFont),
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.a_nothingos_inspired_music_player) +
                        "Supports MP3, AAC, FLAC, OGG, WAV, WMA, ALAC, AIFF & DSD.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                TextButton(onClick = { showAbout = false }) {
                    Text(stringResource(R.string.close), style = MaterialTheme.typography.labelMedium.copy(fontFamily = DotMatrixFont))
                }
            },
        )
    }

    playlistToDelete?.let { id ->
        AlertDialog(
            onDismissRequest = { playlistToDelete = null },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = { Text(stringResource(R.string.delete_playlist)) },
            text = { Text(stringResource(R.string.this_cannot_be_undone), color = MaterialTheme.colorScheme.onSurfaceVariant) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deletePlaylist(id)
                        playlistToDelete = null
                    }
                ) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium.copy(fontFamily = DotMatrixFont))
                }
            },
            dismissButton = {
                TextButton(onClick = { playlistToDelete = null }) {
                    Text(stringResource(R.string.cancel), style = MaterialTheme.typography.labelMedium.copy(fontFamily = DotMatrixFont))
                }
            },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(
            fontFamily = DotMatrixFont,
            letterSpacing = 2.sp,
        ),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
    )
}

@Composable
private fun SettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .padding(4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(2.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NothingSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
            checkedTrackColor = MaterialTheme.colorScheme.primary,
            uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
            uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
            uncheckedBorderColor = MaterialTheme.colorScheme.outlineVariant,
        ),
    )
}

