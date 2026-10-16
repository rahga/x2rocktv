package com.rahga.x2rock.ui.screens

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import com.rahga.x2rock.ui.theme.IconAppButton
import com.rahga.x2rock.viewmodel.AfterPlayingViewModel

/**
 * The right end of every Browse header — Browse, Radio, Music Services and a service's pages, Apple
 * Music: whether starting something there holds the viewer in the browser or releases them to the
 * player (see `AfterPlaying`). A pin, filled when holding, outlined when not, named while focused by
 * what a press will do — a verb, not the state: "Hold" while it releases, "Release" while it holds.
 */
@Composable
internal fun RowScope.StayPin(viewModel: AfterPlayingViewModel) {
    val stay by viewModel.stay.collectAsState()
    Spacer(Modifier.weight(1f))
    Named(if (stay) "Release" else "Hold") { focus ->
        IconAppButton(onClick = viewModel::toggle, modifier = focus) {
            Icon(
                if (stay) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                contentDescription = if (stay) "Hold in the browser after playing" else "Release to the player after playing",
                modifier = Modifier.size(26.dp),
            )
        }
    }
}
