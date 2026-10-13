package com.rahga.x2rock.ui.screens

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.rahga.x2rock.ui.components.DotScanner
import com.rahga.x2rock.ui.components.ScreenHeader
import com.rahga.x2rock.ui.theme.AppCard
import com.rahga.x2rock.ui.theme.requestFocusRetrying
import com.rahga.x2rock.viewmodel.BetaReportViewModel
import kotlinx.coroutines.flow.first

/**
 * Settings → Beta features → Send a report (T-21): the report's text on the left, exactly as the
 * issue will hold it, and a QR code on the right that opens it on a phone as a draft. The text is
 * shown first so a tester knows what they would be publishing; its blocks are focus stops only so a
 * long household scrolls on a remote, and nothing here is pressed.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun BetaReportScreen(onBack: () -> Unit, viewModel: BetaReportViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    BackHandler { onBack() }
    val firstFocus = remember { FocusRequester() }
    val backFocus = remember { FocusRequester() }
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(start = 48.dp, top = 32.dp, end = 48.dp)) {
            ScreenHeader(title = "Send a report", room = null, onBack = onBack, backModifier = Modifier.focusRequester(backFocus))
            Spacer(Modifier.height(12.dp))
            when (val s = state) {
                BetaReportViewModel.UiState.Loading -> DotScanner()
                is BetaReportViewModel.UiState.Ready -> {
                    // Once resumed, past the navigation's fade: see AlarmsScreen.
                    val lifecycle = LocalLifecycleOwner.current.lifecycle
                    LaunchedEffect(Unit) {
                        lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
                        firstFocus.requestFocusRetrying()
                    }
                    val blocks = remember(s.report.text) { s.report.text.split("\n\n").filter { it.isNotBlank() } }
                    Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(bottom = 48.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            itemsIndexed(blocks) { index, block ->
                                AppCard(
                                    onClick = {},
                                    modifier = Modifier.fillMaxWidth().then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier),
                                ) {
                                    Text(
                                        block.replace("**", ""),
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                    )
                                }
                            }
                        }
                        Column(Modifier.width(360.dp)) {
                            QrCode(s.report.url, Modifier.size(360.dp))
                            Spacer(Modifier.height(16.dp))
                            Text(
                                "Scan with your phone to open this as a GitHub issue. Issues there are public. " +
                                    "Nothing is sent until you submit it, and you can edit it first. " +
                                    "It holds no addresses, serial numbers or Sonos ids.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** [text] as a QR code, black on white with a quiet zone, whatever the theme. */
@Composable
internal fun QrCode(text: String, modifier: Modifier = Modifier, description: String = "QR code for the report") {
    val image = remember(text) {
        // One pixel a module, scaled up without smoothing: sharp at any size.
        val matrix = QRCodeWriter().encode(
            text, BarcodeFormat.QR_CODE, 0, 0,
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L, EncodeHintType.MARGIN to 0),
        )
        val pixels = IntArray(matrix.width * matrix.height) { i ->
            if (matrix[i % matrix.width, i / matrix.width]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }
        Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    Image(
        bitmap = image,
        contentDescription = description,
        filterQuality = FilterQuality.None,
        modifier = modifier.background(Color.White).padding(20.dp),
    )
}
