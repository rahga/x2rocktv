package com.rahga.x2rock.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.rahga.x2rock.ui.components.DotScanner
import com.rahga.x2rock.ui.components.ScreenHeader
import com.rahga.x2rock.ui.theme.AppButton
import com.rahga.x2rock.ui.theme.requestFocusRetrying
import com.rahga.x2rock.ui.theme.requestFocusWhenResumed
import com.rahga.x2rock.viewmodel.AccountViewModel
import com.rahga.x2rock.viewmodel.AccountViewModel.Step

/**
 * Settings → Sonos account (T-27): sign this TV in from a phone, or out. Signed out, it starts at once
 * with a QR code; a remote cannot get through Sonos's consent page, a phone can.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AccountScreen(onBack: () -> Unit, viewModel: AccountViewModel = hiltViewModel()) {
    val signedIn by viewModel.signedIn.collectAsState()
    val step by viewModel.step.collectAsState()
    BackHandler { onBack() }
    val backFocus = remember { FocusRequester() }
    val actionFocus = remember { FocusRequester() }
    LaunchedEffect(signedIn) { if (!signedIn) viewModel.start() }
    // Once resumed, past the navigation's fade: see requestFocusWhenResumed.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(signedIn, step is Step.Failed) {
        (if (signedIn || step is Step.Failed) actionFocus else backFocus).requestFocusWhenResumed(lifecycle)
    }
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(start = 48.dp, top = 32.dp, end = 48.dp)) {
            ScreenHeader(title = "Sonos account", room = null, onBack = onBack, backModifier = Modifier.focusRequester(backFocus))
            Spacer(Modifier.height(4.dp))
            Row { BetaMark() }
            Spacer(Modifier.height(24.dp))
            when {
                signedIn -> {
                    Text("This TV is signed in to a Sonos account.", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Note("Speakers that ask who is controlling them get this account. Revoking it is done in the Sonos account itself.")
                    Spacer(Modifier.height(24.dp))
                    AppButton(onClick = viewModel::signOut, modifier = Modifier.focusRequester(actionFocus)) { Text("Sign out") }
                }
                step is Step.Waiting -> Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                    QrCode((step as Step.Waiting).url, Modifier.size(360.dp), description = "QR code for signing in to Sonos")
                    Column(Modifier.width(520.dp)) {
                        Text("Scan with a phone on this network", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Note(
                            "Sign in to Sonos on the phone. It is sent back to this TV, which finishes by itself. " +
                                "The code works for five minutes."
                        )
                    }
                }
                step is Step.Failed -> {
                    Text("Not signed in", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Note((step as Step.Failed).message)
                    Spacer(Modifier.height(24.dp))
                    AppButton(onClick = viewModel::start, modifier = Modifier.focusRequester(actionFocus)) { Text("Try again") }
                }
                else -> DotScanner()
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
