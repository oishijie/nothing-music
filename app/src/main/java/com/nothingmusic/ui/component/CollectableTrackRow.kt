package com.nothingmusic.ui.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.nothingmusic.R
import com.nothingmusic.domain.model.Track

/** 带收藏与「加入歌单」操作的曲目行，本地与远程列表共用。 */
@Composable
fun CollectableTrackRow(
    track: Track,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onAddToPlaylist: () -> Unit,
) {
    TrackListItem(
        track = track,
        isFavorite = isFavorite,
        onClick = onClick,
        onFavoriteClick = onToggleFavorite,
        trailingContent = {
            IconButton(onClick = onAddToPlaylist) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.PlaylistAdd,
                    contentDescription = stringResource(R.string.add_to_playlist),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}
