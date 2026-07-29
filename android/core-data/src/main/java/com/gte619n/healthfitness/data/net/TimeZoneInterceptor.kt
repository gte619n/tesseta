package com.gte619n.healthfitness.data.net

import okhttp3.Interceptor
import okhttp3.Response
import java.time.ZoneId

// Attaches `X-Timezone: <device IANA zone>` to every outgoing request so the
// backend can compute the user's *local* calendar day server-side (e.g. which
// day a workout "run today" is logged against). The server clock is UTC, so
// without this an evening workout for a user behind UTC lands on tomorrow.
//
// Read on each request (not cached) so a device that changes zones — travel,
// DST — reports the current one. ZoneId.systemDefault() is a cheap in-memory
// lookup.
class TimeZoneInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .header("X-Timezone", ZoneId.systemDefault().id)
            .build()
        return chain.proceed(request)
    }
}
