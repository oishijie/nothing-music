package com.nothingmusic.ui.screen.folderpicker

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.ArrowBackIosNew
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.nothingmusic.R
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.nothingmusic.domain.model.Folder
import com.nothingmusic.ui.component.DotMatrixHeaderTitle
import com.nothingmusic.ui.theme.DotMatrixFont

@Composable
fun FolderPickerScreen(
    onDone: () -> Unit,
    viewModel: FolderPickerViewModel = hiltViewModel(),
) {
    val folders by viewModel.folders.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val scannedPaths by viewModel.scannedPaths.collectAsState()

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onDone) {
                Icon(
                    imageVector = Icons.Outlined.ArrowBackIosNew,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                DotMatrixHeaderTitle(
                    text = stringResource(R.string.folders),
                    modifier = Modifier,
                )
                Text(
                    text = "${scannedPaths.size} selected · choose music folders",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            IconButton(onClick = { viewModel.scanDirectories() }) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = "Rescan",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (loading && folders.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.scanning),
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = DotMatrixFont),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return
        }

        if (folders.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.no_audio_folders_found),
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = DotMatrixFont),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.make_sure_storage_permission_is_granted),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            return
        }

        LazyColumn(
            contentPadding = PaddingValues(bottom = 24.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(folders, key = { it.path }) { folder ->
                FolderRow(
                    folder = folder,
                    onClick = { viewModel.toggleFolder(folder.path) },
                )
            }
        }
    }
}

@Composable
private fun FolderRow(
    folder: Folder,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (folder.isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                else MaterialTheme.colorScheme.background
            )
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Folder,
                contentDescription = null,
                tint = if (folder.isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 14.dp)
        ) {
            Text(
                text = folder.name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${folder.trackCount} tracks · ${folder.path}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = if (folder.isSelected) {
                Icons.Filled.CheckCircle
            } else {
                Icons.Outlined.RadioButtonUnchecked
            },
            contentDescription = null,
            tint = if (folder.isSelected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}