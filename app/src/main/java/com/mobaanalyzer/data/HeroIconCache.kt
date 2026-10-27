package com.mobaanalyzer.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Hero icon cache — ลองโหลดจากหลาย source ตามลำดับ:
 * 1. Custom icon ที่ user เลือกเอง
 * 2. GitHub Releases (เมื่อ Action รันแล้ว)
 * 3. Garena CDN URL จาก hero_icons.json (fallback)
 */
object HeroIconCache {

    private const val TAG = "HeroIconCache"
    private const val CACHE_DIR = "hero_icons"
    private const val META_FILE = "meta.json"
    private const val GITHUB_BASE =
        "https://github.com/tanakorn1680/Hero-Data/releases/latest/download"
    private const val CONNECT_TIMEOUT = 8_000
    private const val READ_TIMEOUT = 12_000

    private lateinit var iconDir: File
    private lateinit var metaFile: File
    private var initialized = false
    private var meta: MutableMap<String, IconMeta> = mutableMapOf()
    private var cdnUrlMap: Map<String, String> = emptyMap()

    private data class IconMeta(
        val etag: String? = null,
        val lastModified: String? = null,
        val custom: Boolean = false
    )

    fun init(context: Context, knownHeroIds: Set<String>) {
        iconDir = File(context.filesDir, CACHE_DIR).also { it.mkdirs() }
        metaFile = File(iconDir, META_FILE)
        meta = loadMeta()
        cdnUrlMap = loadCdnUrls(context)

        // ล้าง cache ถ้า iconVersion ใน assets เปลี่ยน
        val cacheVerFile = File(context.filesDir, "icon_cache_version")
        val assetVer = try {
            val json = context.assets.open("heroes.json").bufferedReader().use { it.readText() }
            org.json.JSONObject(json).optString("iconVersion", "1")
        } catch (e: Exception) { "1" }
        val cachedVer = if (cacheVerFile.exists()) cacheVerFile.readText().trim() else ""
        if (cachedVer != assetVer) {
            clearAutoCache()
            cacheVerFile.writeText(assetVer)
            Log.d(TAG, "Cache cleared — iconVersion $assetVer")
        }

        pruneOrphans(knownHeroIds)
        initialized = true
    }

    fun initIfNeeded(context: Context, knownHeroIds: Set<String>) {
        if (!initialized) init(context, knownHeroIds)
    }

    suspend fun getIcon(heroId: String): Bitmap? = withContext(Dispatchers.IO) {
        val existing = meta[heroId]
        if (existing?.custom == true) return@withContext loadCached(heroId)

        // ลอง GitHub Releases ก่อน
        val githubBmp = tryDownload(
            heroId = heroId,
            url = "$GITHUB_BASE/$heroId.jpg",
            etag = existing?.etag,
            lastModified = existing?.lastModified
        )
        if (githubBmp != null) return@withContext githubBmp

        // ถ้ายังไม่มีใน cache เลย → ลอง CDN จาก hero_icons.json
        if (!isCached(heroId)) {
            val cdnUrl = cdnUrlMap[heroId]
            if (cdnUrl != null) {
                val cdnBmp = tryDownloadDirect(heroId, cdnUrl)
                if (cdnBmp != null) return@withContext cdnBmp
            }
        }

        loadCached(heroId)
    }

    private fun tryDownload(
        heroId: String,
        url: String,
        etag: String?,
        lastModified: String?
    ): Bitmap? {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                setRequestProperty("User-Agent", "RaovGuide-App")
                etag?.let { setRequestProperty("If-None-Match", it) }
                lastModified?.let { setRequestProperty("If-Modified-Since", it) }
            }
            when (conn.responseCode) {
                304 -> loadCached(heroId)
                200 -> saveAndReturn(heroId, conn)
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun tryDownloadDirect(heroId: String, url: String): Bitmap? {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36")
                setRequestProperty("Referer", "https://rovmeta.com/")
                setRequestProperty("Accept", "image/webp,image/apng,image/*,*/*;q=0.8")
            }
            if (conn.responseCode == 200) saveAndReturn(heroId, conn)
            else null
        } catch (e: Exception) {
            Log.d(TAG, "CDN fallback fail $heroId: ${e.message}")
            null
        }
    }

    private fun saveAndReturn(heroId: String, conn: HttpURLConnection): Bitmap? {
        val bitmap = conn.inputStream.use { BitmapFactory.decodeStream(it) } ?: return null
        val scaled = Bitmap.createScaledBitmap(bitmap, 128, 128, true)
        bitmap.recycle()
        File(iconDir, "$heroId.jpg").outputStream().use {
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, it)
        }
        meta[heroId] = IconMeta(
            etag = conn.getHeaderField("ETag"),
            lastModified = conn.getHeaderField("Last-Modified")
        )
        saveMeta()
        Log.d(TAG, "Cached: $heroId")
        return scaled
    }

    suspend fun prefetchAll(heroIds: List<String>) {
        heroIds.forEach { getIcon(it) }
        Log.d(TAG, "Prefetch done: ${cachedCount()}/${heroIds.size}")
    }

    fun saveCustomIcon(heroId: String, bitmap: Bitmap) {
        val scaled = Bitmap.createScaledBitmap(bitmap, 128, 128, true)
        File(iconDir, "$heroId.jpg").outputStream().use {
            scaled.compress(Bitmap.CompressFormat.JPEG, 90, it)
        }
        meta[heroId] = IconMeta(custom = true)
        saveMeta()
    }

    fun clearCustomIcon(heroId: String) {
        File(iconDir, "$heroId.jpg").delete()
        meta.remove(heroId)
        saveMeta()
    }

    /** ล้าง cache ทั้งหมดยกเว้น custom icon — เรียกตอน patch เปลี่ยน */
    fun clearAutoCache() {
        iconDir.listFiles { f -> f.name.endsWith(".jpg") }?.forEach { f ->
            val id = f.nameWithoutExtension
            if (meta[id]?.custom != true) {
                f.delete()
                meta.remove(id)
            }
        }
        saveMeta()
        Log.d(TAG, "Auto cache cleared")
    }

    fun isCached(heroId: String) =
        File(iconDir, "$heroId.jpg").let { it.exists() && it.length() > 0 }

    fun cachedCount() =
        iconDir.listFiles { f -> f.name.endsWith(".jpg") }?.size ?: 0

    // ── private ────────────────────────────────────────────────────────────────

    private fun loadCached(heroId: String): Bitmap? {
        val f = File(iconDir, "$heroId.jpg")
        return if (f.exists() && f.length() > 0) BitmapFactory.decodeFile(f.absolutePath) else null
    }

    private fun loadCdnUrls(context: Context): Map<String, String> {
        return try {
            val text = context.assets.open("hero_icons.json")
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
            val heroes = JSONObject(text).getJSONObject("heroes")
            buildMap {
                for (key in heroes.keys()) {
                    val url = heroes.getJSONObject(key).optString("icon_url")
                    if (url.isNotEmpty()) put(key, url)
                }
            }
        } catch (e: Exception) { emptyMap() }
    }

    private fun loadMeta(): MutableMap<String, IconMeta> {
        if (!metaFile.exists()) return mutableMapOf()
        return try {
            val json = JSONObject(metaFile.readText())
            buildMap {
                for (key in json.keys()) {
                    val o = json.getJSONObject(key)
                    put(key, IconMeta(
                        etag = o.optString("etag").ifEmpty { null },
                        lastModified = o.optString("lm").ifEmpty { null },
                        custom = o.optBoolean("custom", false)
                    ))
                }
            }.toMutableMap()
        } catch (e: Exception) { mutableMapOf() }
    }

    @Synchronized
    private fun saveMeta() {
        try {
            val json = JSONObject()
            for ((id, m) in meta) {
                json.put(id, JSONObject().apply {
                    m.etag?.let { put("etag", it) }
                    m.lastModified?.let { put("lm", it) }
                    if (m.custom) put("custom", true)
                })
            }
            metaFile.writeText(json.toString())
        } catch (e: Exception) { Log.w(TAG, "saveMeta: ${e.message}") }
    }

    private fun pruneOrphans(knownIds: Set<String>) {
        iconDir.listFiles { f -> f.name.endsWith(".jpg") }?.forEach { f ->
            val id = f.nameWithoutExtension
            if (id !in knownIds) { f.delete(); meta.remove(id) }
        }
    }
}
