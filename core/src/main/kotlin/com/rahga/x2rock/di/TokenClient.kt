package com.rahga.x2rock.di

import javax.inject.Qualifier

/**
 * The OkHttp client used for OAuth token exchange and refresh. It deliberately carries no auth
 * interceptor (it sets its own Basic header) and never logs response bodies, which contain tokens.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class TokenClient
