package com.mtajchert.carom.app.core.hub

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/** Creates OkHttp-backed clients that share one connection pool. */
@Singleton
class OkHttpHubClientFactory @Inject constructor(
    private val http: OkHttpClient,
) : HubClientFactory {

    override fun client(baseUrl: String, tokenProvider: HubTokenProvider): HubClient {
        requireHttpUrl(baseUrl)
        return OkHttpHubClient(baseUrl, tokenProvider, http)
    }

    override fun socket(baseUrl: String, token: String): HubSocket =
        OkHttpHubSocket(http, requireHttpUrl(baseUrl), token)

    private fun requireHttpUrl(baseUrl: String): HttpUrl =
        requireNotNull(baseUrl.toHttpUrlOrNull()) { "hub url must be http(s)" }
}
