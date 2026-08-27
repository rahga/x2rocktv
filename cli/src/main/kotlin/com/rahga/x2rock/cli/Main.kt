package com.rahga.x2rock.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.obj
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.mordant.rendering.TextColors.brightBlue
import com.github.ajalt.mordant.rendering.TextColors.green
import com.github.ajalt.mordant.rendering.TextColors.yellow
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.rendering.TextStyles.dim
import com.github.ajalt.mordant.table.ColumnWidth
import com.github.ajalt.mordant.table.table
import com.github.ajalt.mordant.terminal.Terminal
import com.google.gson.GsonBuilder
import com.rahga.x2rock.model.Group
import com.rahga.x2rock.model.GroupVolume
import com.rahga.x2rock.model.PlayModeState
import com.rahga.x2rock.model.PlaybackMetadata
import com.rahga.x2rock.model.PlaybackState
import com.rahga.x2rock.model.RepeatModes
import com.rahga.x2rock.model.isPlaying
import com.rahga.x2rock.model.toPlaybackLabel
import com.rahga.x2rock.network.RateLimitedException
import com.rahga.x2rock.repository.SonosAuthRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import retrofit2.HttpException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes

/** Everything a subcommand needs, built once by the root command. */
class Session(val roomOption: String?, val householdOption: String?) {
    val terminal = Terminal()
    val config: CliConfig = CliConfig.load()
    val sonos: Sonos by lazy { Sonos(config, householdOverride = householdOption) }
    val json = GsonBuilder().serializeNulls().create()
}

fun main(args: Array<String>) {
    System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn")
    X2Rock()
        .subcommands(
            Login(), Logout(), Config(), InstallHandler(), OAuthCallbackCmd(),
            Rooms(), Households(), Now(), Play(), Pause(), Toggle(), Next(), Prev(), Vol(), Mute(),
            Queue(), Favorites(), Favorite(), Daemon()
        )
        .main(args)
}

class X2Rock : CliktCommand(name = "x2rock") {
    override fun help(context: Context) = """
        Control Sonos from the terminal.

        Rooms default to the one in `x2rock config --room`, or X2ROCK_ROOM. Most commands
        accept --json for status bars and scripts.
    """.trimIndent()

    private val room by option("-r", "--room", help = "Room to control (group or speaker name)")
    private val household by option(
        "-H", "--household",
        help = "Use the household containing this room, for this command only (see `x2rock households`)"
    )

    override fun run() {
        currentContext.obj = Session(room, household)
    }

    override fun aliases(): Map<String, List<String>> = mapOf("stop" to listOf("pause"))
}

/** Base for commands that talk to Sonos: signed-in check, room resolution, error mapping. */
abstract class SonosCommand(name: String) : CliktCommand(name = name) {
    private val parentSession by requireObject<Session>()

    // `-r`/`-H` also work after the subcommand name (`x2rock daemon -H room`), not just before it
    // (`x2rock -H room daemon`) — Clikt doesn't share a parent's options with its children, so we
    // repeat them here and fall back to whatever the root command already parsed.
    private val roomOverride by option("-r", "--room", help = "Room to control (group or speaker name)")
    private val householdOverride by option(
        "-H", "--household",
        help = "Use the household containing this room, for this command only (see `x2rock households`)"
    )

    protected val session: Session by lazy {
        if (roomOverride == null && householdOverride == null) parentSession
        else Session(roomOverride ?: parentSession.roomOption, householdOverride ?: parentSession.householdOption)
    }
    protected val t get() = session.terminal
    protected val sonos get() = session.sonos

    abstract suspend fun execute()

    override fun run() {
        if (!sonos.tokens.isAuthenticated) fail("Not signed in. Run `x2rock login`.")
        try {
            runBlocking { execute() }
        } catch (e: AmbiguousRoomException) {
            fail(e.message ?: "Ambiguous room")
        } catch (e: NoSuchHouseholdException) {
            fail(e.message ?: "No such household")
        } catch (e: AmbiguousHouseholdException) {
            fail(e.message ?: "Ambiguous household")
        } catch (e: RateLimitedException) {
            fail("Sonos rate-limited this request${e.retryAfterMillis?.let { " — retry in ${it / 1000}s" } ?: ""}.")
        } catch (e: HttpException) {
            if (e.code() == 401 || sonos.auth.sessionExpired.value) {
                fail("Session expired. Run `x2rock login`.")
            }
            fail("Sonos returned HTTP ${e.code()}${e.response()?.errorBody()?.string()?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""}")
        } catch (e: IOException) {
            fail("Network error: ${e.message}")
        } finally {
            sonos.shutdown()
        }
    }

    protected fun fail(message: String): Nothing = throw PrintMessage(message, statusCode = 1, printError = true)

    /** The requested room, or a clear list of what exists when it cannot be resolved. */
    protected suspend fun room(prefetched: List<Group>? = null): Group {
        val groups = prefetched ?: sonos.groups()
        val query = session.roomOption ?: session.config.room
        if (query == null) {
            if (groups.size == 1) return groups.single()
            fail("Which room? Pass --room or set one with `x2rock config --room NAME`.\n" +
                "Rooms: ${groups.map { it.name }.sorted().joinToString()}")
        }
        return matchRoom(groups, query, sonos.repo::getPlayerName)
            ?: fail("No room matches \"$query\". Rooms: ${groups.map { it.name }.sorted().joinToString()}")
    }
}

// ---------------------------------------------------------------- auth & setup

class Login : CliktCommand(name = "login") {
    override fun help(context: Context) =
        "Sign in with your Sonos account. Opens the browser; the redirect lands back here."

    private val manual by option("--manual", help = "Don't wait for the browser handler — paste the redirected URL instead").flag()
    private val session by requireObject<Session>()

    override fun run() {
        val t = session.terminal
        val sonos = session.sonos
        if (!sonos.hasClientCredentials) {
            throw PrintMessage(
                "No Sonos client credentials. Set them with\n" +
                    "  x2rock config --client-id ID --client-secret SECRET\n" +
                    "or export SONOS_CLIENT_ID / SONOS_CLIENT_SECRET.",
                statusCode = 1, printError = true
            )
        }

        OAuthCallback.clearStale()
        val url = sonos.auth.buildAuthUrl()
        t.println(bold("Open this URL to sign in:"))
        t.println(brightBlue(url))
        t.println()
        val opened = runCatching {
            ProcessBuilder("xdg-open", url).redirectErrorStream(true).start().waitFor() == 0
        }.getOrDefault(false)
        if (opened) t.println(dim("(opened in your browser)"))

        val redirected = if (manual) {
            t.println("Paste the URL the browser ended up on (it starts with x2rock://callback):")
            readlnOrNull()
        } else {
            t.println(dim("Waiting for the browser to hand back the sign-in… (Ctrl-C to abort)"))
            OAuthCallback.await(5.minutes)
                ?: throw PrintMessage(
                    "Timed out waiting for the callback. If the browser showed the x2rock:// link but nothing " +
                        "happened, run `x2rock install-handler` once, or retry with `x2rock login --manual`.",
                    statusCode = 1, printError = true
                )
        }

        val code = redirected?.let(OAuthCallback::parse)
            ?: throw PrintMessage("That URL has no code/state in it.", statusCode = 1, printError = true)

        runBlocking { sonos.auth.exchangeCodeForTokens(code.code, code.state) }
            .onFailure { throw PrintMessage("Sign-in failed: ${it.message}", statusCode = 1, printError = true) }
        sonos.shutdown()
        t.println(green("Signed in."))
    }
}

class Logout : CliktCommand(name = "logout") {
    override fun help(context: Context) = "Forget the stored tokens."
    private val session by requireObject<Session>()
    override fun run() {
        session.sonos.auth.clearTokens()
        session.terminal.println("Signed out.")
    }
}

class Config : CliktCommand(name = "config") {
    override fun help(context: Context) = "Show or set client credentials, the default room, and the household."
    private val clientId by option("--client-id")
    private val clientSecret by option("--client-secret")
    private val room by option("--room", help = "Default room when --room is not passed")
    private val household by option(
        "--household",
        help = "Default household, by the name of any room inside it (see `x2rock households`)"
    )
    private val session by requireObject<Session>()

    override fun run() {
        val current = session.config
        if (clientId == null && clientSecret == null && room == null && household == null) {
            session.terminal.println("config file: ${CliConfig.file}")
            session.terminal.println("client id:   ${current.clientId ?: dim("(unset)")}")
            session.terminal.println("secret:      ${if (current.clientSecret.isNullOrBlank()) dim("(unset)") else "••••••••"}")
            session.terminal.println("room:        ${current.room ?: dim("(unset)")}")
            session.terminal.println("household:   ${current.householdId ?: dim("(unset — using the first one)")}")
            return
        }

        val householdId = household?.let { query ->
            try {
                runBlocking { resolveHouseholdId(session.sonos.repo, query) }
            } catch (e: NoSuchHouseholdException) {
                throw PrintMessage(e.message ?: "No such household", statusCode = 1, printError = true)
            } catch (e: AmbiguousHouseholdException) {
                throw PrintMessage(e.message ?: "Ambiguous household", statusCode = 1, printError = true)
            }
        }

        // Save only what came from the file/flags — never persist values that arrived via env.
        val onDisk = runCatching {
            if (Files.exists(CliConfig.file)) GsonBuilder().create().fromJson(Files.readString(CliConfig.file), CliConfig::class.java) else null
        }.getOrNull() ?: CliConfig()
        CliConfig.save(
            onDisk.copy(
                clientId = clientId ?: onDisk.clientId,
                clientSecret = clientSecret ?: onDisk.clientSecret,
                room = room ?: onDisk.room,
                householdId = householdId ?: onDisk.householdId
            )
        )
        session.terminal.println("Saved to ${CliConfig.file}")
    }
}

class Households : SonosCommand("households") {
    override fun help(context: Context) =
        "List every household on the account, with the rooms in each — Sonos gives households no name of their own."
    private val json by option("--json").flag()

    override suspend fun execute() {
        val households = sonos.repo.listHouseholds().getOrThrow()
        if (json) {
            t.println(session.json.toJson(households.map { (id, players) -> mapOf("id" to id, "rooms" to players) }))
            return
        }
        households.entries.forEachIndexed { index, (id, players) ->
            t.println("${bold("#${index + 1}")} ${dim(id)}")
            t.println("   ${players.sorted().joinToString(", ").ifBlank { dim("(no rooms)") }}")
        }
        t.println()
        t.println(dim("Pick one with: x2rock config --household \"<a room name from that household>\""))
    }
}

class InstallHandler : CliktCommand(name = "install-handler") {
    override fun help(context: Context) =
        "Register x2rock:// with the desktop so the browser can hand the OAuth redirect back to `x2rock login`."

    private val exec by option("--exec", help = "Launcher path to put in the .desktop file")
        .default(System.getProperty("x2rock.launcher") ?: "x2rock")
    private val session by requireObject<Session>()

    override fun run() {
        val t = session.terminal
        val appsDir = (System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }?.let(Path::of)
            ?: Path.of(System.getProperty("user.home"), ".local", "share")).resolve("applications")
        Files.createDirectories(appsDir)
        val desktop = appsDir.resolve("x2rock-url-handler.desktop")
        Files.writeString(
            desktop,
            """
            [Desktop Entry]
            Type=Application
            Name=x2rock OAuth handler
            Exec=$exec oauth-callback %u
            NoDisplay=true
            Terminal=false
            MimeType=x-scheme-handler/x2rock;
            """.trimIndent() + "\n"
        )
        t.println("Wrote $desktop")

        val mime = runCatching {
            ProcessBuilder("xdg-mime", "default", desktop.fileName.toString(), "x-scheme-handler/x2rock")
                .inheritIO().start().waitFor() == 0
        }.getOrDefault(false)
        if (mime) t.println("Registered x-scheme-handler/x2rock") else t.println(yellow("xdg-mime not available — register the handler manually."))
        runCatching { ProcessBuilder("update-desktop-database", appsDir.toString()).start().waitFor() }
    }
}

/** Invoked by the desktop, not by people: the browser hands us the redirect URL. */
class OAuthCallbackCmd : CliktCommand(name = "oauth-callback") {
    override val hiddenFromHelp = true
    private val url by argument()
    override fun run() {
        OAuthCallback.deliver(url)
    }
}

// ---------------------------------------------------------------- status

class Rooms : SonosCommand("rooms") {
    override fun help(context: Context) = "List rooms with their state and what's playing."
    private val json by option("--json").flag()

    override suspend fun execute() {
        val groups = sonos.groups().sortedBy { it.name }
        val nowPlaying = sonos.repo.getNowPlaying(groups)
        if (json) {
            t.println(session.json.toJson(groups.map { g ->
                mapOf(
                    "id" to g.id, "name" to g.name, "state" to g.playbackState,
                    "players" to g.playerIds.map(sonos.repo::getPlayerName),
                    "track" to nowPlaying[g.id]?.name, "artist" to nowPlaying[g.id]?.artist?.name
                )
            }))
            return
        }
        t.println(table {
            column(0) { width = ColumnWidth.Auto }
            column(1) { width = ColumnWidth.Auto }
            column(2) { width = ColumnWidth.Expand() }
            header { row("Room", "State", "Now playing") }
            body {
                for (g in groups) {
                    val state = g.playbackState.toPlaybackLabel()
                    row(
                        bold(g.name),
                        if (g.playbackState.isPlaying()) green(state) else dim(state),
                        nowPlaying[g.id].oneLine() ?: dim("—")
                    )
                }
            }
        })
    }
}

class Now : SonosCommand("now") {
    override fun help(context: Context) = "What's playing in a room."
    private val json by option("--json", help = "Machine-readable output").flag()
    private val oneline by option("--oneline", help = "Single line for status bars").flag()

    override suspend fun execute() {
        val group = room()
        val snapshot = coroutineScope {
            val p = async { sonos.repo.getPlaybackState(group.id).getOrThrow() }
            val m = async { sonos.repo.getPlaybackMetadata(group.id).getOrNull() }
            val v = async { sonos.repo.getGroupVolume(group.id).getOrThrow() }
            val pm = async { sonos.repo.getPlayMode(group.id).getOrNull()?.playMode }
            Snapshot(p.await(), m.await(), v.await(), pm.await())
        }
        val (playback, meta, volume, mode) = snapshot

        val track = meta?.currentItem?.track
        val label = playback.playbackState.toPlaybackLabel()

        if (json) {
            t.println(session.json.toJson(mapOf(
                "room" to group.name, "state" to playback.playbackState, "label" to label,
                "track" to track?.name, "artist" to track?.artist?.name, "album" to track?.album?.name,
                "container" to meta?.container?.name, "imageUrl" to track?.imageUrl,
                "positionMillis" to playback.positionMillis, "durationMillis" to track?.durationMillis,
                "volume" to volume.volume, "muted" to volume.muted,
                "shuffle" to mode?.shuffle, "repeat" to mode?.repeat, "crossfade" to mode?.crossfade
            )))
            return
        }
        if (oneline) {
            val icon = if (playback.playbackState.isPlaying()) "▶" else "⏸"
            t.println("$icon ${track.oneLine() ?: label}")
            return
        }

        t.println("${bold(group.name)}  ${if (playback.playbackState.isPlaying()) green(label) else dim(label)}")
        if (track?.name != null) {
            t.println("  ${bold(track.name!!)}")
            track.artist?.name?.let { t.println("  $it") }
            track.album?.name?.let { t.println("  ${dim(it)}") }
            val dur = track.durationMillis
            t.println("  ${playback.positionMillis.toClock()}${if (dur > 0) " / ${dur.toClock()}" else ""}")
        } else {
            meta?.container?.name?.let { t.println("  ${dim(it)}") }
        }
        val flags = buildList {
            if (mode?.shuffle == true) add("shuffle")
            when (mode?.repeat) { RepeatModes.ALL -> add("repeat"); RepeatModes.ONE -> add("repeat one") }
            if (mode?.crossfade == true) add("crossfade")
        }
        t.println("  vol ${volume.volume}${if (volume.muted) " (muted)" else ""}${if (flags.isNotEmpty()) "  ·  ${flags.joinToString("  ")}" else ""}")
    }

    private data class Snapshot(
        val playback: PlaybackState,
        val meta: PlaybackMetadata?,
        val volume: GroupVolume,
        val mode: PlayModeState?
    )
}

// ---------------------------------------------------------------- transport

class Play : SonosCommand("play") {
    override fun help(context: Context) = "Start playback if it isn't already."
    override suspend fun execute() {
        val g = room()
        val state = sonos.repo.getPlaybackState(g.id).getOrThrow()
        if (!state.playbackState.isPlaying()) sonos.repo.togglePlayPause(g.id).getOrThrow()
        t.println("▶ ${g.name}")
    }
}

class Pause : SonosCommand("pause") {
    override fun help(context: Context) = "Pause if playing."
    override suspend fun execute() {
        val g = room()
        val state = sonos.repo.getPlaybackState(g.id).getOrThrow()
        if (state.playbackState.isPlaying()) sonos.repo.togglePlayPause(g.id).getOrThrow()
        t.println("⏸ ${g.name}")
    }
}

class Toggle : SonosCommand("toggle") {
    override fun help(context: Context) = "Play/pause."
    override suspend fun execute() {
        val g = room()
        sonos.repo.togglePlayPause(g.id).getOrThrow()
        t.println("⏯ ${g.name}")
    }
}

class Next : SonosCommand("next") {
    override fun help(context: Context) = "Skip to the next track."
    override suspend fun execute() { sonos.repo.skipToNextTrack(room().id).getOrThrow(); t.println("⏭") }
}

class Prev : SonosCommand("prev") {
    override fun help(context: Context) = "Go back a track."
    override suspend fun execute() { sonos.repo.skipToPreviousTrack(room().id).getOrThrow(); t.println("⏮") }
}

class Vol : SonosCommand("vol") {
    override fun help(context: Context) = "Show or set group volume: `vol`, `vol 40`, `vol +5`, `vol -5`."
    private val level by argument().optional()

    override suspend fun execute() {
        val g = room()
        val current = sonos.repo.getGroupVolume(g.id).getOrThrow()
        val arg = level ?: run { t.println("${current.volume}${if (current.muted) " (muted)" else ""}"); return }
        val target = parseVolume(arg, current.volume) ?: fail("Volume must be 0–100, +N or -N; got \"$arg\".")
        val result = sonos.repo.setGroupVolume(g.id, target).getOrThrow()
        t.println("${result.volume}")
    }
}

class Mute : SonosCommand("mute") {
    override fun help(context: Context) = "Toggle mute, or `mute on` / `mute off`."
    private val state by argument().optional()

    override suspend fun execute() {
        val g = room()
        val muted = when (state?.lowercase()) {
            null -> !sonos.repo.getGroupVolume(g.id).getOrThrow().muted
            "on", "true", "1" -> true
            "off", "false", "0" -> false
            else -> fail("Expected on or off.")
        }
        sonos.repo.setGroupMute(g.id, muted).getOrThrow()
        t.println(if (muted) "muted" else "unmuted")
    }
}

// ---------------------------------------------------------------- content

class Queue : SonosCommand("queue") {
    override fun help(context: Context) = "Show the queue. `queue 7` jumps to track 7."
    private val track by argument().optional()

    override suspend fun execute() {
        val g = room()
        track?.let { arg ->
            val n = arg.toIntOrNull() ?: fail("Track number must be an integer.")
            sonos.repo.skipToQueueItem(g.id, n).getOrThrow()
            t.println("⏭ track $n")
            return
        }
        val (queue, meta) = coroutineScope {
            val q = async { sonos.repo.getQueue(g.id).getOrThrow() }
            val m = async { sonos.repo.getPlaybackMetadata(g.id).getOrNull() }
            q.await() to m.await()
        }
        val currentName = meta?.currentItem?.track?.name
        val entries = queue.items.mapIndexed { i, item -> (i + 1) to item }.filter { !it.second.deleted }
        if (entries.isEmpty()) { t.println(dim("Queue is empty.")); return }
        val width = entries.last().first.toString().length
        for ((n, item) in entries) {
            val line = item.track.oneLine() ?: dim("(untitled)")
            val num = n.toString().padStart(width)
            if (item.track?.name != null && item.track?.name == currentName) t.println(green("▶ $num  $line"))
            else t.println("  $num  $line")
        }
    }
}

class Favorites : SonosCommand("favorites") {
    override fun help(context: Context) = "List Sonos favorites."
    private val json by option("--json").flag()

    override suspend fun execute() {
        sonos.groups() // primes the household id the favorites call needs
        val favs = sonos.repo.getFavorites().getOrThrow().items
        if (json) { t.println(session.json.toJson(favs)); return }
        favs.forEachIndexed { i, f ->
            t.println("${(i + 1).toString().padStart(2)}  ${bold(f.name)}${f.description?.let { "  ${dim(it)}" } ?: ""}")
        }
    }
}

class Favorite : SonosCommand("favorite") {
    override fun help(context: Context) = "Play a favorite by number (from `favorites`) or name."
    private val which by argument()

    override suspend fun execute() {
        val groups = sonos.groups()
        val g = room(groups)
        val favs = sonos.repo.getFavorites().getOrThrow().items
        val pick = which.toIntOrNull()?.let { favs.getOrNull(it - 1) }
            ?: favs.firstOrNull { it.name.equals(which, ignoreCase = true) }
            ?: favs.singleOrNull { it.name.contains(which, ignoreCase = true) }
            ?: fail("No favorite matches \"$which\". Run `x2rock favorites`.")
        sonos.repo.loadFavorite(g.id, pick.id).getOrThrow()
        t.println("▶ ${pick.name} → ${g.name}")
    }
}
