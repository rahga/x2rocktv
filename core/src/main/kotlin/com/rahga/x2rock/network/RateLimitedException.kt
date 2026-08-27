package com.rahga.x2rock.network

/**
 * The Sonos Control API returned 429. [retryAfterMillis] carries the server's Retry-After when it
 * supplied one, so pollers can wait exactly as long as they were told to.
 */
class RateLimitedException(val retryAfterMillis: Long?) :
    RuntimeException("Sonos rate limit reached")
