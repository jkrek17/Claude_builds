package com.survivor.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Minimal HTTP abstraction so the clients can be tested with canned responses. */
interface HttpFetcher {
    suspend fun get(url: String): String

    /** [get] with extra/overriding request headers (e.g. a browser-like User-Agent for a page that varies its
     *  response by client). Default implementation ignores [headers] so existing fetchers keep working. */
    suspend fun get(url: String, headers: Map<String, String>): String = get(url)
}

class OkHttpFetcher(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build(),
) : HttpFetcher {
    override suspend fun get(url: String): String = get(url, emptyMap())

    override suspend fun get(url: String, headers: Map<String, String>): String = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(url).header("User-Agent", "SurvivorOptimizer/1.0 (Android)")
        headers.forEach { (k, v) -> builder.header(k, v) }
        client.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for $url")
            resp.body?.string() ?: throw IOException("Empty body for $url")
        }
    }
}
