package com.rahga.x2rock.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.rahga.x2rock.ui.theme.X2RockTheme

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HomeScreen() {
    Surface(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "x2rock",
                style = MaterialTheme.typography.displayMedium
            )
        }
    }
}

@Preview
@Composable
private fun HomeScreenPreview() {
    X2RockTheme {
        HomeScreen()
    }
}
