package com.rahga.x2rock.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.rahga.x2rock.lan.StoredAccountsRead

/**
 * Why the services that need an account are missing, when the household's stored sign-ins could not
 * be had: the speaker sent none, or they could not be read. Those sign-ins are read the way SoCo #1010
 * found, a format a firmware update could change; without a word here the services would simply be
 * gone from Music Services and Search, with only the ones needing no account left. Nothing while the
 * sign-ins are fine or not yet read.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SignInsNote(read: StoredAccountsRead?, modifier: Modifier = Modifier) {
    // Plain words here; the reason, technical as it is, goes in the beta report.
    read?.problem ?: return
    Text(
        "This system's music-service sign-ins couldn't be used, so only services that need no account " +
            "are listed. Settings → Beta features → Send a report says why.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(bottom = 12.dp),
    )
}
