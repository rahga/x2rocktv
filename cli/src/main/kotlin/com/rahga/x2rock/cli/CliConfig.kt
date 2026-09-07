package com.rahga.x2rock.cli

import com.google.gson.GsonBuilder
import java.nio.file.Files
import java.nio.file.Path

/**
 * Non-secret-ish settings in `$XDG_CONFIG_HOME/x2rocktv/config.json`. The client secret lives here
 * too (0600) unless SONOS_CLIENT_ID / SONOS_CLIENT_SECRET are set, which win.
 */
data class CliConfig(
    val clientId: String? = null,
    val clientSecret: String? = null,
    /** Room used when `--room` is absent. */
    val room: String? = null,
    /** Household id used when the account has more than one; unset means "first household". */
    val householdId: String? = null
) {
    companion object {
        private val gson = GsonBuilder().setPrettyPrinting().create()
        val file: Path get() = Xdg.configDir.resolve("config.json")

        fun load(): CliConfig {
            val fromFile = if (Files.exists(file)) {
                runCatching { gson.fromJson(Files.readString(file), CliConfig::class.java) }.getOrNull()
            } else null
            val base = fromFile ?: CliConfig()
            return base.copy(
                clientId = System.getenv("SONOS_CLIENT_ID")?.takeIf { it.isNotBlank() } ?: base.clientId,
                clientSecret = System.getenv("SONOS_CLIENT_SECRET")?.takeIf { it.isNotBlank() } ?: base.clientSecret,
                room = System.getenv("X2ROCK_ROOM")?.takeIf { it.isNotBlank() } ?: base.room,
                householdId = System.getenv("X2ROCK_HOUSEHOLD")?.takeIf { it.isNotBlank() } ?: base.householdId
            )
        }

        fun save(config: CliConfig) = Xdg.writePrivate(file, gson.toJson(config))
    }
}
