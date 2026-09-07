package com.rahga.x2rock.di

import com.rahga.x2rock.channel.ChannelSync
import com.rahga.x2rock.channel.RoomsChannelSync
import com.rahga.x2rock.media.MediaSessionPublisher
import com.rahga.x2rock.media.NowPlayingPublisher
import com.rahga.x2rock.store.Preferences
import com.rahga.x2rock.store.SharedPreferencesStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * The seams that keep the view models constructible without the Android framework.
 *
 * Each of these was a concrete class the view models depended on directly, and each dragged
 * a `Context` — or, in the publisher's case, a `MediaSession` built in a field initializer —
 * into anything that tried to instantiate them. That is why they had no tests.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class StoreModule {

    @Binds abstract fun bindPreferences(impl: SharedPreferencesStore): Preferences

    @Binds abstract fun bindChannelSync(impl: RoomsChannelSync): ChannelSync

    @Binds abstract fun bindNowPlayingPublisher(impl: MediaSessionPublisher): NowPlayingPublisher
}
