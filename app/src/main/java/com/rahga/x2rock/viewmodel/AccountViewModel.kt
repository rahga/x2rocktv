package com.rahga.x2rock.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rahga.x2rock.account.SignInCallback
import com.rahga.x2rock.account.lanAddressToward
import com.rahga.x2rock.account.signInState
import com.rahga.x2rock.store.BetaFeatures
import com.rahga.x2rock.store.DeveloperAccount
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import java.net.ServerSocket
import javax.inject.Inject

/**
 * Settings → Sonos account (T-27): signing this TV in with the build's own developer integration, from
 * a phone. Offered only where the build carries one and Beta features are on.
 *
 * The TV shows a QR code to Sonos's consent page; the phone signs in there, Sonos sends it to the
 * integration's redirect page, and that page forwards it to this TV's LAN address, where [start]
 * listens once. The code is redeemed here, with the secret, which never leaves the TV.
 */
@HiltViewModel
class AccountViewModel @Inject constructor(
    developer: DeveloperAccount,
    betaFeatures: BetaFeatures,
) : ViewModel() {

    private val account = developer.account

    /** Whether Settings offers the account at all. */
    val offered: StateFlow<Boolean> = betaFeatures.enabled.map { it && account != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, betaFeatures.enabled.value && account != null)

    val signedIn: StateFlow<Boolean> = account?.signedIn ?: MutableStateFlow(false)

    sealed interface Step {
        data object Idle : Step
        data object Starting : Step
        /** The phone's way in: [url] is Sonos's consent page, shown as a QR code. */
        data class Waiting(val url: String) : Step
        data class Failed(val message: String) : Step
    }

    private val _step = MutableStateFlow<Step>(Step.Idle)
    val step: StateFlow<Step> = _step.asStateFlow()

    private var job: Job? = null

    /** Show the code and wait for the phone, for as long as a person might take to sign in. */
    fun start() {
        val account = account ?: return
        job?.cancel()
        job = viewModelScope.launch {
            _step.value = Step.Starting
            // Held here, not only by awaitCode: a cancellation as the socket is made, or a failure
            // before the wait, would otherwise leave it bound.
            var listener: ServerSocket? = null
            try {
                val (server, state) = withContext(Dispatchers.IO) {
                    // The interface toward the internet is the one a phone on the same network reaches.
                    val address = lanAddressToward(InetAddress.getByName(ROUTE_PROBE))
                        ?: error("this TV has no private network address for a phone to reach")
                    val server = ServerSocket(0, 4, address).also { listener = it }
                    server to signInState(address, server.localPort)
                }
                _step.value = Step.Waiting(account.authorizeUrl(state))
                val code = withTimeout(WAIT_MILLIS) { SignInCallback.awaitCode(server, state) }
                _step.value = Step.Starting
                account.finish(code)
                _step.value = Step.Idle
            } catch (e: TimeoutCancellationException) {
                _step.value = Step.Failed("No sign-in arrived in ${WAIT_MILLIS / 60_000} minutes.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _step.value = Step.Failed(e.message ?: e.toString())
            } finally {
                runCatching { listener?.close() }
            }
        }
    }

    fun signOut() {
        val account = account ?: return
        job?.cancel()
        viewModelScope.launch { account.signOut() }
        _step.value = Step.Idle
    }

    private companion object {
        const val WAIT_MILLIS = 5 * 60_000L
        /** Never contacted: a connected UDP socket only asks which interface would carry a packet. */
        const val ROUTE_PROBE = "1.1.1.1"
    }
}
