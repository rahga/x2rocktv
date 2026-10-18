package com.rahga.x2rock.viewmodel

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rahga.x2rock.BuildConfig
import com.rahga.x2rock.lan.GroupState
import com.rahga.x2rock.lan.HouseholdState
import com.rahga.x2rock.lan.SonosHousehold
import com.rahga.x2rock.store.BetaFeatures
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Settings → Beta features → Send a report (T-21): what a tester's system is, as a GitHub issue
 * their phone opens from a QR code. A TV cannot easily mail or share, and the closed test reaches
 * hardware neither household has, so this is how what it saw gets back.
 *
 * Read once on opening, like About this system. Nothing is sent from here: the phone opens a draft
 * the tester can edit, and only submitting it publishes anything.
 */
@HiltViewModel
class BetaReportViewModel @Inject constructor(
    private val household: SonosHousehold,
    private val betaFeatures: BetaFeatures,
) : ViewModel() {

    sealed interface UiState {
        data object Loading : UiState
        data class Ready(val report: BetaReport) : UiState
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            // A speaker that does not answer is still listed, as About this system lists it.
            val speakers = runCatching { household.aboutSystem() }.getOrDefault(emptyList())
            // Read the stored service sign-ins if nothing has yet, so the report can say whether they work.
            if (household.accountsRead.value == null) runCatching { household.searchableServices() }
            _uiState.value = UiState.Ready(
                betaReport(
                    app = "x2rocktv ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    device = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}",
                    state = household.state.value,
                    groupStates = household.groupStates.value,
                    speakers = speakers,
                    refusal = betaFeatures.lastRefusal.value,
                    signIns = household.accountsRead.value,
                )
            )
        }
    }
}

/** The issue's text, as shown on the screen, and the link that opens it as a draft. */
data class BetaReport(val text: String, val url: String)

/** Where reports go: a public repository, which the screen says before showing the code. */
const val REPORT_ISSUES = "https://github.com/rahga/x2rocktv/issues/new"

/**
 * Past this a QR code is too dense to scan off a television from the sofa; byte mode at the lowest
 * correction holds 2953, and a phone needs margin below that.
 */
internal const val REPORT_URL_LIMIT = 2000

/**
 * The report, redacted as the fixtures are: identifiers only. No player or household id, no
 * address, no serial; rooms keep their names, which is what makes a report readable.
 *
 * What a tester's system *is* — each speaker's model, version and capabilities — then what a room
 * with a line-in reports right now, which is the capture P-14 is owed, and the last refusal a beta
 * feature met. Capabilities go first when it will not fit, then the text is cut.
 */
internal fun betaReport(
    app: String,
    device: String,
    state: HouseholdState,
    groupStates: Map<String, GroupState>,
    speakers: List<SonosHousehold.SpeakerAbout>,
    refusal: String?,
    /** The last read of the household's stored service sign-ins; `null` if none could be tried. */
    signIns: com.rahga.x2rock.lan.StoredAccountsRead? = null,
): BetaReport {
    val capabilities = state.players.associate { it.id to it.capabilities }
    fun speakerLines(withCapabilities: Boolean) = speakers.map { s ->
        val what = s.info?.let { i ->
            listOfNotNull(
                i.modelName.removePrefix("Sonos ").ifBlank { null },
                i.displayVersion.ifBlank { null },
                i.softwareVersion.ifBlank { null }?.let { "($it)" },
            ).joinToString(" ")
        } ?: "didn't answer"
        val caps = capabilities[s.playerId].orEmpty()
        // A line-in always shows: it is what the report is most often for.
        val shown = if (withCapabilities) caps else caps.filter { it == com.rahga.x2rock.lan.LINE_IN }
        "- ${s.room}: $what" + if (shown.isEmpty()) "" else " · ${shown.sorted().joinToString(",")}"
    }
    val lineInRooms = state.lineInPlayers.mapNotNull { player ->
        val group = state.groups.firstOrNull { player.id in it.playerIds } ?: return@mapNotNull null
        val now = groupStates[group.id]
        val container = now?.container
        "- ${group.name}: ${now?.playbackState ?: "unknown"} · container type ${container?.type ?: "none"}" +
            ", name ${container?.name ?: "none"}, service ${container?.service?.id ?: "none"}"
    }
    fun text(withCapabilities: Boolean) = buildString {
        appendLine("**What I tried, and what happened:**")
        appendLine()
        appendLine()
        appendLine("---")
        appendLine("$app on $device")
        // A household with Authentication on refuses every command from this app (T-29): it is how
        // many such systems the testers have that decides whether signing in is worth offering.
        if (state.authenticationRequired) {
            appendLine()
            appendLine("**Authentication is on:** the speakers refused this app (ERROR_NO_PERMISSION).")
        }
        // Whether the stored service sign-ins could be read (SoCo #1010's way), which a firmware could
        // change: said above the speakers, where cutting the end to fit cannot take it.
        signIns?.let {
            appendLine()
            appendLine(
                if (it.problem == null) "**Service sign-ins:** ${it.withSignIns} read"
                else "**Service sign-ins:** none usable, ${it.problem}"
            )
        }
        appendLine()
        appendLine("**Speakers**")
        // Said above the list, where cutting the end to fit cannot take it.
        if (!withCapabilities) appendLine("(capabilities left out to fit, but a line-in)")
        speakerLines(withCapabilities).forEach(::appendLine)
        if (lineInRooms.isNotEmpty()) {
            appendLine()
            appendLine("**Rooms with a line-in, now**")
            lineInRooms.forEach(::appendLine)
        }
        refusal?.let {
            appendLine()
            appendLine("**Last refusal:** $it")
        }
    }.trimEnd()
    fun url(body: String) = "$REPORT_ISSUES?title=${queryEncode("Beta report: $app")}&body=${queryEncode(body)}"

    var body = text(withCapabilities = true)
    if (url(body).length > REPORT_URL_LIMIT) body = text(withCapabilities = false)
    while (url(body).length > REPORT_URL_LIMIT) body = body.substring(0, body.length * 9 / 10).trimEnd() + "\n(cut to fit)"
    return BetaReport(body, url(body))
}

/**
 * Percent-encodes a query value, leaving what a query may carry as it is (RFC 3986): every byte
 * encoded tripled the length, and length is what the QR code pays for.
 */
internal fun queryEncode(value: String): String = buildString {
    for (b in value.toByteArray(Charsets.UTF_8)) {
        val c = b.toInt().toChar()
        if (b >= 0 && (c.isLetterOrDigit() || c in "-._~!$'()*,;:@/?")) append(c)
        else append('%').append("%02X".format(b.toInt() and 0xFF))
    }
}
