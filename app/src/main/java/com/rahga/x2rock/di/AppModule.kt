package com.rahga.x2rock.di

import android.util.Log
import com.rahga.x2rock.lan.LanHttp
import com.rahga.x2rock.lan.MulticastGate
import com.rahga.x2rock.lan.PlayerAddressBook
import com.rahga.x2rock.lan.SeedStore
import com.rahga.x2rock.lan.SonosHousehold
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import com.rahga.x2rock.net.PrefsSeedStore
import com.rahga.x2rock.net.WifiMulticastGate
import kotlinx.coroutines.CoroutineExceptionHandler
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
     *
     * The handler matters. Without one, an exception escaping any coroutine launched here
     * reaches the thread's default handler and takes the process down — a `SupervisorJob`
     * isolates children from each other, not from that.
     */
    @Provides
    @Singleton
    fun provideApplicationScope(): CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e ->
            Log.e("x2rock", "uncaught in application scope", e)
        }
    )

    @Provides
    @Singleton
    fun provideMulticastGate(impl: WifiMulticastGate): MulticastGate = impl

    @Provides
    @Singleton
    fun provideSeedStore(impl: PrefsSeedStore): SeedStore = impl

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
        multicast: MulticastGate,
        client: OkHttpClient,
        seeds: SeedStore,
    ): SonosHousehold = SonosHousehold(scope, addressBook, multicast, client, seeds)
}
