package com.vakarux.instadownload

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * A link the user pasted or shared into the app.
 *
 * [url] is the cleaned Instagram URL (tracking query strings stripped) so entries stay short,
 * clean and free of the sharer's identifier. [firstSeen] is when the link was first recorded and
 * [lastSeen] is updated every time the same link comes in again, with [count] tracking how often.
 */
data class LinkRecord(
    val url: String,
    val firstSeen: Long,
    val lastSeen: Long,
    val count: Int,
)

/**
 * A persistent history of every Instagram link pasted or shared into the app.
 *
 * Unlike [DownloadHistory] (which only tracks files that were saved) this keeps the links
 * themselves, including ones that later failed. Entries are de-duplicated by cleaned URL and are
 * **never removed automatically** — not when a download succeeds and not when a retry succeeds.
 * The only way an entry leaves the list is an explicit user action (Remove or Clear history).
 */
object LinkHistory {
    private const val PREFS = "link_history"
    private const val KEY = "records"
    private const val MAX_ENTRIES = 500

    fun load(context: Context): List<LinkRecord> = runCatching {
        val json = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "[]"))
        (0 until json.length()).mapNotNull { i -> json.optJSONObject(i)?.toRecord() }
            .filter { it.url.isNotBlank() }
    }.getOrDefault(emptyList())

    /**
     * Records a link. The same cleaned link is not duplicated — its [LinkRecord.lastSeen] and
     * [LinkRecord.count] are refreshed and it moves to the top of the list.
     */
    @Synchronized
    fun add(context: Context, url: String) {
        val clean = InstagramDownloader.cleanInstagramUrl(url).trim()
        if (clean.isBlank()) return
        val now = System.currentTimeMillis()
        val existing = load(context).firstOrNull { it.url == clean }
        val record = LinkRecord(
            url = clean,
            firstSeen = existing?.firstSeen ?: now,
            lastSeen = now,
            count = (existing?.count ?: 0) + 1,
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

    private fun save(context: Context, records: List<LinkRecord>) {
        val json = JSONArray().apply { records.forEach { put(it.toJson()) } }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, json.toString()).apply()
    }

    private fun LinkRecord.toJson() = JSONObject().apply {
        put("url", url)
        put("firstSeen", firstSeen)
        put("lastSeen", lastSeen)
        put("count", count)
    }

    private fun JSONObject.toRecord() = LinkRecord(
        url = optString("url"),
        firstSeen = optLong("firstSeen"),
        lastSeen = optLong("lastSeen"),
        count = optInt("count", 1),
    )
}
