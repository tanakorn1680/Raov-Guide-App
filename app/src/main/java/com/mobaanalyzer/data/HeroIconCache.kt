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
 * Hero icon cache
 *
 * แหล่งรูป (ตามลำดับความสำคัญ):
 * 1. Custom icon ที่ user เลือกเอง → ไม่ถูกทับ
 * 2. GitHub Releases: https://github.com/tanakorn1680/Raov-guide/releases/latest/download/<heroId>.jpg
 * 3. Cache บนเครื่อง (ถ้ายังไม่หมดอายุ)
 *
 * ใช้ HTTP ETag / If-None-Match → server ตอบ 304 ถ้าไม่มีการเปลี่ยนแปลง = ไม่โหลดซ้ำ
 */
object HeroIconCache {

    private const val TAG = "HeroIconCache"
    private const val CACHE_DIR = "hero_icons"
    private const val META_FILE = "meta.json"
    private const val ICON_BASE_URL =
        "https://github.com/tanakorn1680/Raov-guide/releases/latest/download"
    private const val CONNECT_TIMEOUT = 8_000
    private const val READ_TIMEOUT = 12_000

    private lateinit var iconDir: File
    private lateinit var metaFile: File
    private var initialized = false
    private var meta: MutableMap<String, IconMeta> = mutableMapOf()

    private data class IconMeta(
        val etag: String? = null,
        val lastModified: String? = null,
        val custom: Boolean = false
    )

    fun init(context: Context, knownHeroIds: Set<String>) {
        iconDir = File(context.filesDir, CACHE_DIR).also { it.mkdirs() }
        metaFile = File(iconDir, META_FILE)
        meta = loadMeta()
        pruneOrphans(knownHeroIds)
        initialized = true
    }

    fun initIfNeeded(context: Context, knownHeroIds: Set<String>) {
        if (!initialized) init(context, knownHeroIds)
    }

    /**
     * ดึง Bitmap ของ heroId
     * - custom → คืน cache ทันที
     * - มี cache + server 304 → คืน cache
     * - server 200 → โหลดทับ cache
     * - เน็ตล้มเหลว → คืน cache เดิม
     */
    suspend fun getIcon(heroId: String): Bitmap? = withContext(Dispatchers.IO) {
        val existing = meta[heroId]
        if (existing?.custom == true) return@withContext loadCached(heroId)

        val cacheFile = File(iconDir, "$heroId.jpg")
        val url = "$ICON_BASE_URL/$heroId.jpg"

        return@withContext try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                setRequestProperty("User-Agent", "RaovGuide-App")
                existing?.etag?.let { setRequestProperty("If-None-Match", it) }
                existing?.lastModified?.let { setRequestProperty("If-Modified-Since", it) }
            }

            when (conn.responseCode) {
                304 -> loadCached(heroId)
                200 -> {
                    val bitmap = conn.inputStream.use { BitmapFactory.decodeStream(it) }
                    if (bitmap != null) {
                        val scaled = Bitmap.createScaledBitmap(bitmap, 128, 128, true)
                        bitmap.recycle()
                        cacheFile.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                        meta[heroId] = IconMeta(
                            etag = conn.getHeaderField("ETag"),
                            lastModified = conn.getHeaderField("Last-Modified")
                        )
                        saveMeta()
                        Log.d(TAG, "Downloaded: $heroId")
                        scaled
                    } else loadCached(heroId)
                }
                else -> {
                    Log.w(TAG, "HTTP ${conn.responseCode} for $heroId")
                    loadCached(heroId)
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Network fail for $heroId, using cache: ${e.message}")
            loadCached(heroId)
        }
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

    fun isCached(heroId: String) =
        File(iconDir, "$heroId.jpg").let { it.exists() && it.length() > 0 }

    fun cachedCount() =
        iconDir.listFiles { f -> f.name.endsWith(".jpg") }?.size ?: 0

    // ── private ────────────────────────────────────────────────────────────────

    private fun loadCached(heroId: String): Bitmap? {
        val f = File(iconDir, "$heroId.jpg")
        return if (f.exists() && f.length() > 0) BitmapFactory.decodeFile(f.absolutePath) else null
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
        } catch (e: Exception) {
            Log.w(TAG, "saveMeta failed: ${e.message}")
        }
    }

    private fun pruneOrphans(knownIds: Set<String>) {
        iconDir.listFiles { f -> f.name.endsWith(".jpg") }?.forEach { f ->
            val id = f.nameWithoutExtension
            if (id !in knownIds) {
                f.delete()
                meta.remove(id)
                Log.d(TAG, "Pruned: $id")
            }
        }
    }
}
