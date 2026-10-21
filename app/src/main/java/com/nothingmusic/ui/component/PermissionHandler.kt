package com.nothingmusic.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.nothingmusic.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nothingmusic.ui.theme.DotMatrixFont

@Composable
fun PermissionRationaleScreen(
    onRequestPermission: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.music),
            style = MaterialTheme.typography.displayMedium.copy(fontFamily = DotMatrixFont),
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.storage_access_is_required_to_read_your_music_fi),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp),
            lineHeight = MaterialTheme.typography.bodyLarge.lineHeight,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.choose_which_folders_to_scan_in_settings),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Spacer(modifier = Modifier.height(40.dp))
        TextButton(
            onClick = onRequestPermission,
            modifier = Modifier
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(24.dp))
                .padding(horizontal = 32.dp, vertical = 14.dp),
        ) {
            Text(
                text = stringResource(R.string.grant_access),
                style = MaterialTheme.typography.labelLarge.copy(fontFamily = DotMatrixFont),
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

@Composable
fun WelcomeOverlay(
    onContinue: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.brand_name),
                style = MaterialTheme.typography.displaySmall.copy(fontFamily = DotMatrixFont),
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.a_minimal_music_player),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(48.dp))
            TextButton(
                onClick = onContinue,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.onBackground, RoundedCornerShape(24.dp))
                    .padding(horizontal = 40.dp, vertical = 14.dp),
            ) {
                Text(
                    text = stringResource(R.string.start),
                    style = MaterialTheme.typography.labelLarge.copy(fontFamily = DotMatrixFont),
                    color = MaterialTheme.colorScheme.background,
                )
            }
        }
    }
}