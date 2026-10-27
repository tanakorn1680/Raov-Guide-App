package com.mobaanalyzer.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object HeroDatabaseUpdater {

    private const val TAG = "HeroDatabaseUpdater"
    private const val HEROES_URL =
        "https://github.com/tanakorn1680/Hero-Data/releases/latest/download/heroes.json"
    private const val LOCAL_FILE = "heroes.json"
    private const val CACHE_VER_FILE = "icon_cache_version"
    private const val CONNECT_TIMEOUT = 8_000
    private const val READ_TIMEOUT = 12_000

    suspend fun loadWithUpdate(context: Context): HeroDatabase = withContext(Dispatchers.IO) {
        val localFile = File(context.filesDir, LOCAL_FILE)
        val cacheVerFile = File(context.filesDir, CACHE_VER_FILE)

        // เช็ค iconVersion จาก assets (bundle ใน APK) ก่อน
        // ถ้า cachedIconVersion ต่างจาก assets → ล้าง cache (เช่นหลัง build ใหม่)
        val assetIconVer = readIconVersionFromAssets(context) ?: "1"
        val cachedIconVer = if (cacheVerFile.exists()) cacheVerFile.readText().trim() else ""
        if (cachedIconVer != assetIconVer) {
            HeroIconCache.clearAutoCache()
            cacheVerFile.writeText(assetIconVer)
            Log.d(TAG, "Icon cache cleared — iconVersion $assetIconVer")
        }

        // ดึง patch ใหม่จาก Releases
        val currentPatch = readPatch(localFile) ?: readPatchFromAssets(context)
        val remotePatch = fetchRemotePatch()
        if (remotePatch != null && remotePatch != currentPatch) {
            val downloaded = downloadHeroesJson()
            if (downloaded != null) {
                localFile.writeText(downloaded)
                // ถ้า iconVersion ใน remote ต่างจากที่มี → ล้าง cache
                val remoteIconVer = readIconVersionFromJson(downloaded) ?: assetIconVer
                if (remoteIconVer != assetIconVer) {
                    HeroIconCache.clearAutoCache()
                    cacheVerFile.writeText(remoteIconVer)
                    Log.d(TAG, "Icon cache cleared — remote iconVersion $remoteIconVer")
                }
                Log.d(TAG, "heroes.json updated: patch $remotePatch")
            }
        }

        return@withContext if (localFile.exists() && localFile.length() > 0)
            HeroDatabase.fromJson(localFile.readText())
        else
            HeroDatabase.load(context)
    }

    private fun readPatch(file: File): String? = try {
        if (file.exists()) JSONObject(file.readText()).optString("patch").ifEmpty { null }
        else null
    } catch (e: Exception) { null }

    private fun readPatchFromAssets(context: Context): String? = try {
        JSONObject(context.assets.open(LOCAL_FILE).bufferedReader().use { it.readText() })
            .optString("patch").ifEmpty { null }
    } catch (e: Exception) { null }

    private fun readIconVersionFromAssets(context: Context): String? = try {
        JSONObject(context.assets.open(LOCAL_FILE).bufferedReader().use { it.readText() })
            .optString("iconVersion").ifEmpty { null }
    } catch (e: Exception) { null }

    private fun readIconVersionFromJson(json: String): String? = try {
        JSONObject(json).optString("iconVersion").ifEmpty { null }
    } catch (e: Exception) { null }

    private fun fetchRemotePatch(): String? = try {
        val conn = URL(HEROES_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT
        conn.readTimeout = READ_TIMEOUT
        conn.setRequestProperty("User-Agent", "RaovGuide-App")
        if (conn.responseCode == 200) {
            val buf = ByteArray(512)
            val read = conn.inputStream.use { it.read(buf) }
            Regex(""""patch"\s*:\s*"([\d.]+)"""").find(String(buf, 0, read))?.groupValues?.get(1)
        } else null
    } catch (e: Exception) { null }

    private fun downloadHeroesJson(): String? = try {
        val conn = URL(HEROES_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT
        conn.readTimeout = READ_TIMEOUT
        conn.setRequestProperty("User-Agent", "RaovGuide-App")
        if (conn.responseCode == 200) conn.inputStream.bufferedReader().use { it.readText() }
        else null
    } catch (e: Exception) { null }
}
