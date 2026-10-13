package com.rahga.x2rock.store

import com.google.gson.Gson
import com.rahga.x2rock.BuildConfig
import com.rahga.x2rock.account.AccountToken
import com.rahga.x2rock.account.Integration
import com.rahga.x2rock.account.SonosAccount
import com.rahga.x2rock.account.TokenStore
import com.rahga.x2rock.di.InternetHttp
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Sonos account this device is signed in to, where the build carries a developer integration
 * (T-27; `-Px2rock.sonosIntegration`). [account] is `null` in every other build — the default, and
 * every build anyone else makes from the repo — and then no account feature exists at all.
 */
@Singleton
class DeveloperAccount @Inject constructor(prefs: Preferences, @InternetHttp client: OkHttpClient) {

    val account: SonosAccount? = integration()?.let { SonosAccount(client, it, PreferencesTokenStore(prefs)) }

    companion object {
        /** The integration compiled into this build, if one was asked for. */
        fun integration(): Integration? =
            BuildConfig.SONOS_KEY.takeIf { it.isNotEmpty() && BuildConfig.SONOS_SECRET.isNotEmpty() }
                ?.let { Integration(it, BuildConfig.SONOS_SECRET, BuildConfig.SONOS_REDIRECT) }
    }
}

/** The token in this app's private preferences: not backed up (`allowBackup="false"`), not shared. */
class PreferencesTokenStore(private val prefs: Preferences) : TokenStore {
    private val gson = Gson()
    override fun load(): AccountToken? =
        prefs.getString(KEY)?.let { runCatching { gson.fromJson(it, AccountToken::class.java) }.getOrNull() }
            ?.takeIf { it.accessToken.isNotEmpty() }
    override fun save(token: AccountToken?) = prefs.putString(KEY, token?.let(gson::toJson))

    private companion object {
        const val KEY = "sonos_account_token"
    }
}
