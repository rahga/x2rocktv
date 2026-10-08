package com.rahga.x2rock.ui.components

import androidx.compose.foundation.focusGroup
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.rahga.x2rock.ui.theme.AppButton
import com.rahga.x2rock.ui.theme.AppCard
import com.rahga.x2rock.ui.theme.CardShape

/**
 * One row of any list of things to play — a favourite, a queue entry, a search result, a station.
 *
 * **The art slot is always laid out**, art or not, so every title starts at the same x: rows that
 * shifted left without art read at three metres as a different list rather than a missing image.
 * The same rule the room list has kept since it was first drawn.
 *
 * The focused row's title scrolls when it does not fit, so a long one can be read without widening
 * anything; unfocused rows stay still and ellipsize, because a list of moving text is unreadable.
 *
 * [onLongClick] is the row's second action, on a hold or the remote's Menu key alike.
 */
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MediaRow(
    title: String,
    subtitle: String?,
    artUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    /** A position, drawn before the art: a queue's track number. */
    leading: String? = null,
    /** What the room is playing now, outlined so it is found at a glance. */
    active: Boolean = false,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    AppCard(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.hasFocus }
            .then(if (onLongClick != null) Modifier.dpadMenuKey(onLongClick) else Modifier)
            .then(if (active) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CardShape) else Modifier),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            if (leading != null) {
                Text(leading, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(40.dp))
            }
            ArtSlot(artUrl)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = if (focused) TextOverflow.Clip else TextOverflow.Ellipsis,
                    modifier = if (focused) Modifier.basicMarquee(iterations = Int.MAX_VALUE) else Modifier,
                )
                if (!subtitle.isNullOrEmpty()) {
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            trailing()
        }
    }
}

/** The 56dp square every list row holds for art, drawn or not. */
@Composable
fun ArtSlot(url: String?) {
    Box(Modifier.size(56.dp)) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(4.dp)),
            )
        }
    }
}

/**
 * The top of every screen opened from a room: Back, the screen's name, and **which room it acts
 * on**. With five rooms, playing something in the wrong one is the classic multi-room mistake,
 * and nothing on these screens said which room they were for. [actions] follow the title.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ScreenHeader(
    title: String,
    room: String?,
    onBack: () -> Unit,
    backModifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    // Sideways off either end of the header goes nowhere. Right from Browse's last button used to
    // find the now-playing bar's Play/Pause, the nearest thing that way, one press from pausing the
    // room (2026-10-08). Up and down leave as before. Properties before the group: see Overlay.
    @OptIn(ExperimentalComposeUiApi::class)
    val sidewaysStays = Modifier
        .focusProperties {
            exit = { if (it == FocusDirection.Left || it == FocusDirection.Right) FocusRequester.Cancel else FocusRequester.Default }
        }
        .focusGroup()
    Row(sidewaysStays, verticalAlignment = Alignment.CenterVertically) {
        AppButton(onClick = onBack, modifier = backModifier) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Back")
        }
        Spacer(Modifier.width(24.dp))
        Column {
            Text(title, style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!room.isNullOrBlank()) {
                Text(
                    "in $room",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(24.dp))
        actions()
    }
}

/** "Starting…" at a row's end while what it names is being started. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun RowScope.RowStatus(text: String, highlighted: Boolean = false) {
    Spacer(Modifier.width(16.dp))
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
    )
}

/** A heading over one section of a list; the first sits flush with the list's top. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SectionTitle(title: String, first: Boolean) {
    Text(
        title,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(top = if (first) 0.dp else 16.dp, bottom = 4.dp),
    )
}
