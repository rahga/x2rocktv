package com.rahga.x2rock.di

import com.rahga.x2rock.lan.LanHttp
import com.rahga.x2rock.lan.PlayerAddressBook
import com.rahga.x2rock.lan.SonosHousehold
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * Outlives any ViewModel: the household's sockets and their subscriptions belong to the
     * application, not to whichever screen happens to be showing.
     */
    @Provides
    @Singleton
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun providePlayerAddressBook(): PlayerAddressBook = PlayerAddressBook()

    /**
     * The one client allowed to talk to players, and the only one that can.
     *
     * It carries the address book that resolves `sonos-<MAC>.local` names — Android's
     * resolver cannot — and the trust manager for the players' leaf-only certificate
     * chain. **The image loader has to use this too**: album art is served by the players
     * themselves at cleartext `.local` URLs, so a loader with its own client would fail on
     * both counts. See `Upnp.absolute`.
     */
    @Provides
    @Singleton
    fun provideLanClient(addressBook: PlayerAddressBook): OkHttpClient =
        LanHttp.client(addressBook)

    @Provides
    @Singleton
    fun provideSonosHousehold(
        scope: CoroutineScope,
        addressBook: PlayerAddressBook,
        client: OkHttpClient,
    ): SonosHousehold = SonosHousehold(scope, addressBook, client)
}
