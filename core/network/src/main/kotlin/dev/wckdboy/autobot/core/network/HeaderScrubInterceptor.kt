package dev.wckdboy.autobot.core.network

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Normalizes outgoing headers so requests do not fingerprint the device:
 * - replaces `User-Agent` (OkHttp's default includes the library version) with [USER_AGENT];
 * - removes `Referer`, `Cookie`, `Origin` and common tracking / device headers.
 *
 * `Authorization` and content headers set by the caller are left untouched.
 */
class HeaderScrubInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val builder = chain.request().newBuilder()
        STRIPPED_HEADERS.forEach(builder::removeHeader)
        builder.header("User-Agent", USER_AGENT)
        return chain.proceed(builder.build())
    }

    companion object {
        const val USER_AGENT = "Autobot"
        val STRIPPED_HEADERS = listOf(
            "Referer",
            "Cookie",
            "Origin",
            "X-Requested-With",
            "X-Device-Id",
            "X-Client-Data",
            "X-Forwarded-For",
            "HTTP-Referer",
            "X-Title",
        )
    }
}
