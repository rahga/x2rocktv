package com.rahga.x2rock.di

import com.rahga.x2rock.auth.EncryptedTokenStore
import com.rahga.x2rock.auth.TokenStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class StoreModule {
    @Binds
    abstract fun bindTokenStore(impl: EncryptedTokenStore): TokenStore
}
