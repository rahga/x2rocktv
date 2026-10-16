package com.rahga.x2rock.smapi

import okhttp3.OkHttpClient
import java.util.ServiceLoader

/**
 * A service's pages from somewhere other than its own SMAPI endpoint — a front page, a container, a
 * search — for what that endpoint refuses a client like this one. **None ships in this repository.**
 * A build can be given one from an untracked source set (`-Px2rock.cloudContent`; see CLAUDE.md,
 * "Untracked service pages"), found through [ServiceLoader] so that nothing here names it.
 *
 * Its ids are its own: [objectIdOf] turns one into the object id a player plays through the
 * household's own registration, which is how everything else plays.
 */
interface ServicePages {
    /** Service ids listed only through this source, and only while it is [ready]. */
    val onlyHere: Set<String>

    /** Service ids whose root this answers in place of SMAPI's; SMAPI's stands when it is refused. */
    val frontPages: Set<String>

    /** Whether a call here could be made now. */
    suspend fun ready(): Boolean

    /** The object id [id] stands for, where it is one of this source's own; `null` for any other. */
    fun objectIdOf(id: String): String?

    suspend fun home(household: String, linked: LinkedService): List<Item>

    suspend fun browse(household: String, linked: LinkedService, id: String): List<Item>

    suspend fun search(household: String, linked: LinkedService, term: String): ItemPage
}

/** Makes a build's [ServicePages], given the internet client and the account token it may send. */
fun interface ServicePagesFactory {
    fun create(client: OkHttpClient, bearer: suspend () -> String?): ServicePages
}

/** The factory this build was given, or `null` — every build from the repository as it stands. */
fun suppliedServicePages(): ServicePagesFactory? =
    ServiceLoader.load(ServicePagesFactory::class.java, ServicePagesFactory::class.java.classLoader).firstOrNull()
