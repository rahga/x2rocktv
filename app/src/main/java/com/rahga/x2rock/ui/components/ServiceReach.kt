package com.rahga.x2rock.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.rahga.x2rock.ui.theme.AppCard
import com.rahga.x2rock.viewmodel.ServiceReach

/**
 * [ServiceReach] at the foot of a list of services. Each block is one focus stop, so a remote walking
 * down the list reaches it and brings all of it into view; Select on it does nothing, since it only
 * says where those services are.
 */
fun LazyListScope.serviceReachItems(reach: ServiceReach) {
    if (reach.unavailable.isNotEmpty()) item(key = "reach:unavailable") {
        ReachBlock("Unavailable:", reach.unavailable, "See the Sonos app for iPhone and Android to access those services.")
    }
    if (reach.appleInSettings) item(key = "reach:settings") {
        ReachBlock("Available via Settings:", listOf("Apple Music"), note = null)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ReachBlock(title: String, names: List<String>, note: String?) {
    AppCard(onClick = {}, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            names.forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
            note?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}
