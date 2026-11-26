package com.vakarux.instadownload

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * A download that could not be completed.
 *
 * [url] is the Instagram post/reel URL (tracking query strings already stripped) so the link stays
 * short and clean and can be retried later without carrying the sharer's identifier.
 */
data class FailedDownload(
    val url: String,
    val error: String,
    val failedAt: Long,
    val attempts: Int,
)

/**
 * Persists download URLs that failed, so the user can review them and retry later instead of
 * losing the link. Keyed by the cleaned URL: re-failing the same link bumps its attempt count and
 * updates the error, while a later success removes it.
 */
object FailedDownloads {
    private const val PREFS = "failed_downloads"
    private const val KEY = "records"
    private const val MAX_ENTRIES = 200

    fun load(context: Context): List<FailedDownload> = runCatching {
        val json = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "[]"))
        (0 until json.length()).mapNotNull { i -> json.optJSONObject(i)?.toFailed() }
            .filter { it.url.isNotBlank() }
    }.getOrDefault(emptyList())

    /** Records a failure. The same link is not duplicated — its error and counter are refreshed. */
    @Synchronized
    fun add(context: Context, url: String, error: String?) {
        val clean = InstagramDownloader.cleanInstagramUrl(url).trim()
        if (clean.isBlank()) return
        val now = System.currentTimeMillis()
        val message = error?.takeIf { it.isNotBlank() } ?: "Download failed"
        val existing = load(context).firstOrNull { it.url == clean }
        val record = FailedDownload(
            url = clean,
            error = message,
            failedAt = now,
            attempts = (existing?.attempts ?: 0) + 1
        )
        val updated = (listOf(record) + load(context).filterNot { it.url == clean })
            .take(MAX_ENTRIES)
        save(context, updated)
    }

    @Synchronized
    fun remove(context: Context, url: String) {
        save(context, load(context).filterNot { it.url == url })
    }

    @Synchronized
    fun clear(context: Context) {
        save(context, emptyList())
    }

    private fun save(context: Context, records: List<FailedDownload>) {
        val json = JSONArray().apply { records.forEach { put(it.toJson()) } }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, json.toString()).apply()
    }

    private fun FailedDownload.toJson() = JSONObject().apply {
        put("url", url)
        put("error", error)
        put("failedAt", failedAt)
        put("attempts", attempts)
    }

    private fun JSONObject.toFailed() = FailedDownload(
        url = optString("url"),
        error = optString("error"),
        failedAt = optLong("failedAt"),
        attempts = optInt("attempts", 1),
    )
}
