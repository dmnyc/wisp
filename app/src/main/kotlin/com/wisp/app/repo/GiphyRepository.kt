package com.wisp.app.repo

import android.content.Context
import com.wisp.app.relay.HttpClientFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request

/**
 * Giphy REST client for the composer's GIF picker — the same Giphy-backed
 * picker the iOS app ships. The API key resolves the same way there: from a
 * gitignored `assets/giphy-api-key.txt`, falling back to the shared
 * development key so the picker works in fresh checkouts.
 *
 * Endpoints are the public REST API (`/v1/gifs|stickers/{search,trending}`).
 * A picked GIF is downloaded and re-hosted through the composer's standard
 * Blossom pipeline rather than hotlinking Giphy's anonymous-quota CDN.
 */
class GiphyRepository(context: Context) {

    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient
        get() = HttpClientFactory.createHttpClient(
            connectTimeoutSeconds = 10,
            readTimeoutSeconds = 20
        )

    @Serializable
    data class Rendition(val url: String? = null)

    @Serializable
    data class Images(
        val original: Rendition? = null,
        @SerialName("fixed_height") val fixedHeight: Rendition? = null,
        val downsized: Rendition? = null
    )

    @Serializable
    data class Gif(
        val id: String,
        val title: String? = null,
        val images: Images? = null
    ) {
        /** Grid preview — a moderate fixed-height rendition, always present. */
        val previewUrl: String?
            get() = images?.fixedHeight?.url ?: images?.original?.url

        /** What a selection hands the uploader: the best GIF rendition, in the
         *  same order the iOS picker resolves — original, fixed height, downsized. */
        val bestGifUrl: String?
            get() = images?.original?.url ?: images?.fixedHeight?.url ?: images?.downsized?.url
    }

    @Serializable
    private data class ApiResponse(val data: List<Gif> = emptyList())

    suspend fun trending(
        limit: Int = 30,
        offset: Int = 0,
        stickers: Boolean = false
    ): List<Gif> = fetch(if (stickers) "stickers/trending" else "gifs/trending", null, limit, offset)

    suspend fun search(
        query: String,
        limit: Int = 30,
        offset: Int = 0,
        stickers: Boolean = false
    ): List<Gif> = fetch(if (stickers) "stickers/search" else "gifs/search", query, limit, offset)

    /** Downloads the GIF bytes behind [url] (with the response's content type). */
    suspend fun download(url: String): Pair<ByteArray, String> = withContext(Dispatchers.IO) {
        val response = httpClient.newCall(Request.Builder().url(url).build()).execute()
        response.use {
            if (!it.isSuccessful) throw Exception("HTTP ${it.code}")
            val bytes = it.body?.bytes() ?: throw Exception("Empty body")
            val mime = it.header("Content-Type")?.substringBefore(';')?.trim()
                ?.takeIf { m -> m.startsWith("image/") } ?: "image/gif"
            bytes to mime
        }
    }

    private suspend fun fetch(
        path: String,
        query: String?,
        limit: Int,
        offset: Int
    ): List<Gif> = withContext(Dispatchers.IO) {
        val url = buildString {
            append("https://api.giphy.com/v1/")
            append(path)
            append("?api_key=").append(apiKey)
            query?.let { q -> append("&q=").append(java.net.URLEncoder.encode(q, "UTF-8")) }
            append("&limit=").append(limit)
            append("&offset=").append(offset)
        }
        val response = httpClient.newCall(Request.Builder().url(url).build()).execute()
        response.use {
            if (!it.isSuccessful) throw Exception("HTTP ${it.code}")
            val body = it.body?.string() ?: throw Exception("Empty body")
            json.decodeFromString(ApiResponse.serializer(), body).data
        }
    }

    private val apiKey: String by lazy {
        runCatching {
            appContext.assets.open("giphy-api-key.txt").bufferedReader().use { r -> r.readText() }
        }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: FALLBACK_KEY
    }

    companion object {
        /** Development fallback — the shared key the iOS app embeds; a
         *  project-owned key overrides it via `assets/giphy-api-key.txt`. */
        private const val FALLBACK_KEY = "skps5dg1WqyfynjZBkwa6ziTBJC2KLzx"
    }
}
