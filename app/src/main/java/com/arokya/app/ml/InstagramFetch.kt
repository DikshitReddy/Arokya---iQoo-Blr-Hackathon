package com.arokya.app.ml

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * ============================================================
 *  INSTAGRAM REEL URL -> VIDEO BYTES
 * ============================================================
 *
 * ⚠️ THIS IS THE ONE CLOUD CALL IN AROKYA. Everything else runs on the phone;
 * this reaches out to Instagram's servers to turn a shared reel LINK into the
 * actual video (Instagram's share button only ever sends the link). The
 * "Where this ran" screen and a one-time notice both disclose it — if you ever
 * remove that disclosure, this feature makes the app's privacy claim a lie.
 *
 * HOW IT WORKS: Instagram doesn't hand a public video URL to an anonymous
 * client any more, so this walks the same path a browser does — fetch the
 * page shell for session tokens, then ask the same GraphQL endpoint the web
 * app uses. Several extraction strategies are tried in order because Instagram
 * rotates all of them; when they ALL fail (which WILL happen periodically),
 * the caller falls back to "download the reel and share the file", which
 * always works.
 *
 * This is scraping an undocumented endpoint, against Instagram's ToS, and
 * inherently brittle. It is here because the feature was explicitly requested
 * with that tradeoff understood — not because it's robust.
 */
object InstagramFetch {
    private const val TAG = "ArokyaIgFetch"
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Mobile Safari/537.36"
    private const val APP_ID = "936619743392459" // public web app id, sent by instagram.com itself

    /** True for the links Instagram's share sheet produces. */
    fun looksLikeReel(text: String): Boolean {
        val t = text.trim()
        return Regex("""instagram\.com/(reel|reels|p|tv)/""").containsMatchIn(t)
    }

    /** Cache key form: "ig:<shortcode>", or null if no shortcode is present. */
    fun shortcodeKey(text: String): String? = shortcodeOf(text)?.let { "ig:$it" }

    /** Pulls the /reel/<code>/ shortcode out of whatever text was shared. */
    private fun shortcodeOf(text: String): String? =
        Regex("""instagram\.com/(?:reel|reels|p|tv)/([A-Za-z0-9_-]+)""")
            .find(text)?.groupValues?.get(1)

    private fun firstUrl(text: String): String? =
        Regex("""https?://[^\s]+""").find(text)?.value

    /**
     * Resolves a shared reel link to a direct video URL. Throws with an
     * actionable message on any failure — the UI then offers the file-share
     * fallback rather than pretending it worked.
     */
    suspend fun resolveVideoUrl(sharedText: String): String = withContext(Dispatchers.IO) {
        val shortcode = shortcodeOf(sharedText)
            ?: throw IOException("That doesn't look like an Instagram reel link.")
        Log.i(TAG, "Resolving shortcode=$shortcode")

        // A share often contains a share-tracking URL (…/reel/x/?igsh=…) that
        // redirects; canonicalise to the clean permalink.
        val permalink = "https://www.instagram.com/reel/$shortcode/"

        // Strategy 1: the page shell frequently inlines "video_url" in a JSON
        // blob. Cheapest path — one GET, no token dance.
        val (html, cookies) = fetchPage(permalink)
        extractVideoUrlFromHtml(html)?.let {
            Log.i(TAG, "Found via page HTML")
            return@withContext it
        }

        // Strategy 2: og:video meta tag (present on some posts).
        extractOgVideo(html)?.let {
            Log.i(TAG, "Found via og:video")
            return@withContext it
        }

        // Strategy 3: the GraphQL endpoint the web client uses, with tokens
        // scraped from the shell above.
        graphqlVideoUrl(shortcode, html, cookies)?.let {
            Log.i(TAG, "Found via GraphQL")
            return@withContext it
        }

        throw IOException(
            "Instagram didn't return the video this time — this can happen when a reel " +
                    "is private or when Instagram changes their site. Download the reel " +
                    "in Instagram (⋯ → Download) and share the saved video instead."
        )
    }

    /** Downloads the resolved video into [into] and returns the byte count. */
    suspend fun download(videoUrl: String, into: java.io.File): Long = withContext(Dispatchers.IO) {
        val connection = (URL(videoUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", UA)
            connectTimeout = 15_000
            readTimeout = 30_000
        }
        try {
            if (connection.responseCode !in 200..299) {
                throw IOException("Couldn't download the video (HTTP ${connection.responseCode}).")
            }
            connection.inputStream.use { input ->
                into.outputStream().use { output -> input.copyTo(output) }
            }
        } finally {
            connection.disconnect()
        }
        into.length()
    }

    // ---- internals ----

    private fun fetchPage(url: String): Pair<String, String> {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "text/html,application/xhtml+xml")
            instanceFollowRedirects = true
            connectTimeout = 12_000
            readTimeout = 20_000
        }
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw IOException("Instagram returned HTTP $code for that link.")
            }
            val cookies = connection.headerFields["Set-Cookie"]?.joinToString("; ") { it.substringBefore(";") }.orEmpty()
            body to cookies
        } finally {
            connection.disconnect()
        }
    }

    /** Unescapes and returns the first "video_url":"…" found in a JSON-in-HTML blob. */
    private fun extractVideoUrlFromHtml(html: String): String? {
        val match = Regex(""""video_url":"([^"]+)"""").find(html) ?: return null
        return match.groupValues[1]
            .replace("\\u0026", "&")
            .replace("\\/", "/")
            .takeIf { it.startsWith("http") }
    }

    private fun extractOgVideo(html: String): String? =
        Regex("""<meta property="og:video" content="([^"]+)"""")
            .find(html)?.groupValues?.get(1)
            ?.replace("&amp;", "&")
            ?.takeIf { it.startsWith("http") }

    private fun graphqlVideoUrl(shortcode: String, shell: String, cookies: String): String? {
        val lsd = Regex(""""LSD",\[\],\{"token":"([^"]+)"""").find(shell)?.groupValues?.get(1)
        val csrf = Regex("""csrftoken=([^;]+)""").find(cookies)?.groupValues?.get(1)
        val docId = Regex(""""doc_id":"(\d+)"""").find(shell)?.groupValues?.get(1)
            ?: "8845758582119845" // last-known web PostPage query; rotates.

        val variables = JSONObject().apply {
            put("shortcode", shortcode)
            put("fetch_tagged_user_count", JSONObject.NULL)
            put("hoisted_comment_id", JSONObject.NULL)
            put("hoisted_reply_id", JSONObject.NULL)
        }.toString()

        val body = buildString {
            append("av=0&__d=www&server_timestamps=true&doc_id=").append(docId)
            append("&variables=").append(URLEncoder.encode(variables, "UTF-8"))
            if (lsd != null) append("&lsd=").append(URLEncoder.encode(lsd, "UTF-8"))
        }

        val connection = (URL("https://www.instagram.com/graphql/query").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("X-IG-App-ID", APP_ID)
            if (lsd != null) setRequestProperty("X-FB-LSD", lsd)
            if (csrf != null) setRequestProperty("X-CSRFToken", csrf)
            if (cookies.isNotBlank()) setRequestProperty("Cookie", cookies)
            setRequestProperty("Referer", "https://www.instagram.com/reel/$shortcode/")
            connectTimeout = 12_000
            readTimeout = 20_000
        }

        return try {
            connection.outputStream.use { it.write(body.toByteArray()) }
            val code = connection.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "GraphQL HTTP $code")
                return null
            }
            val json = connection.inputStream.bufferedReader().use { it.readText() }
            extractVideoUrlFromHtml(json) // same "video_url":"…" shape lives in the JSON
        } catch (e: Exception) {
            Log.w(TAG, "GraphQL failed: ${e.message}")
            null
        } finally {
            connection.disconnect()
        }
    }
}
