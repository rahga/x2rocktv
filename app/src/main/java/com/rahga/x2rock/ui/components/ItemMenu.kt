package com.rahga.x2rock.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.rahga.x2rock.ui.theme.AppButton
import com.rahga.x2rock.ui.theme.rememberAutoFocusRequester

/** One thing an [ItemMenu] offers. [detail] is a second, quieter line: the room it acts on. */
data class MenuAction(val label: String, val detail: String? = null, val onClick: () -> Unit)

/** A run of [actions] under an optional [title] — the service's name, over what the service offers. */
data class MenuSection(val title: String?, val actions: List<MenuAction>)

/**
 * Everything that can be done with one item, opened by a hold or the Menu key on its row — the
 * remote's version of the Sonos app's "⋯". Sonos's own actions first (play now, play next, add to
 * the queue), then the service's under its name (browse the artist, open the album), as the Sonos
 * app lays them out. Focus starts on the first action; Back or a tap outside closes it, and so
 * does any action, after it is sent.
 *
 * [note] stands in for a section still being asked for, so the menu opens at once rather than
 * waiting on a service.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ItemMenu(
    title: String,
    subtitle: String?,
    artUrl: String?,
    sections: List<MenuSection>,
    onDismiss: () -> Unit,
    note: String? = null,
) {
    BackHandler { onDismiss() }
    val firstFocus = rememberAutoFocusRequester()
    val rows = buildList<Any> {
        sections.filter { it.actions.isNotEmpty() }.forEach { section ->
            section.title?.let { add(it) }
            addAll(section.actions)
        }
        note?.let { add(Note(it)) }
    }
    val first = rows.indexOfFirst { it is MenuAction }

    Overlay(onDismiss) {
        Column(
            modifier = Modifier
                .width(520.dp)
                .background(MaterialTheme.colorScheme.surface)
                .padding(24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ArtSlot(artUrl)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (!subtitle.isNullOrEmpty()) {
                        Text(subtitle, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.padding(top = 16.dp))
            // A television is 540dp tall; the heading and padding take the rest, and a long menu
            // scrolls under focus rather than running off the top.
            // Room at the sides for a focused button: tv-material grows it by a tenth, which took a
            // full-width one out to the dialog's own edges, looking bigger than the box it was in.
            LazyColumn(
                modifier = Modifier.heightIn(max = 340.dp),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(rows) { index, row ->
                    when (row) {
                        is MenuAction -> AppButton(
                            onClick = { row.onClick(); onDismiss() },
                            modifier = Modifier.fillMaxWidth()
                                .then(if (index == first) Modifier.focusRequester(firstFocus) else Modifier),
                        ) {
                            Column {
                                Text(row.label)
                                row.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                        is Note -> Text(row.text, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
                        is String -> Text(row, style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                    }
                }
            }
        }
    }
}

private class Note(val text: String)
