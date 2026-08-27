package com.rahga.x2rock.cli

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/** XDG base directories, with the spec's fallbacks when the variables are unset. */
object Xdg {
    private val home: Path get() = Path.of(System.getProperty("user.home"))

    val configDir: Path
        get() = (System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }?.let(Path::of)
            ?: home.resolve(".config")).resolve("x2rock")

    /** Per-login, tmpfs-backed, user-only. Where the OAuth callback is handed between processes. */
    val runtimeDir: Path
        get() = (System.getenv("XDG_RUNTIME_DIR")?.takeIf { it.isNotBlank() }?.let(Path::of)
            ?: Path.of("/tmp", "x2rock-${System.getProperty("user.name")}")).resolve("x2rock")

    /** Creates [dir] readable only by the current user. */
    fun privateDir(dir: Path): Path {
        if (!Files.exists(dir)) {
            Files.createDirectories(dir)
        }
        runCatching { Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------")) }
        return dir
    }

    /** Writes [content] atomically with mode 0600 — the file holds credentials. */
    fun writePrivate(file: Path, content: String) {
        privateDir(file.parent)
        val tmp = file.resolveSibling("${file.fileName}.tmp")
        Files.writeString(tmp, content)
        runCatching { Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------")) }
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }
}
