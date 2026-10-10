package com.mobaanalyzer.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * ดึง heroes.json จาก GitHub Releases อัตโนมัติ
 * - ถ้ามี version ใหม่กว่า → โหลดและบันทึกไว้ใน internal storage
 * - ถ้าไม่มีเน็ต / version เหมือนเดิม → ใช้ไฟล์เดิม
 * - ลำดับ: internal storage → assets (APK)
 */
object HeroDatabaseUpdater {

    private const val TAG = "HeroDatabaseUpdater"
    private const val HEROES_URL =
        "https://github.com/tanakorn1680/Raov-guide/releases/latest/download/heroes.json"
    private const val LOCAL_FILE = "heroes.json"
    private const val CONNECT_TIMEOUT = 8_000
    private const val READ_TIMEOUT = 12_000

    /**
     * โหลด HeroDatabase — ดึงจาก release ถ้ามีเวอร์ชันใหม่
     * เรียกแบบ suspend ใน background
     */
    suspend fun loadWithUpdate(context: Context): HeroDatabase = withContext(Dispatchers.IO) {
        val localFile = File(context.filesDir, LOCAL_FILE)

        // patch ที่มีอยู่ตอนนี้
        val currentPatch = readPatch(localFile)
            ?: readPatchFromAssets(context)

        // ลองดึง header จาก release เพื่อเช็ค patch ก่อนโหลดทั้งไฟล์
        val remotePatch = fetchRemotePatch()

        val shouldUpdate = remotePatch != null && remotePatch != currentPatch

        if (shouldUpdate) {
            Log.d(TAG, "New patch: $currentPatch → $remotePatch, downloading...")
            val downloaded = downloadHeroesJson()
            if (downloaded != null) {
                localFile.writeText(downloaded)
                Log.d(TAG, "heroes.json updated (patch $remotePatch)")
            }
        }

        // โหลด — local file ก่อน, fallback assets
        return@withContext when {
            localFile.exists() && localFile.length() > 0 -> {
                HeroDatabase.fromJson(localFile.readText())
            }
            else -> HeroDatabase.load(context)
        }
    }

    private fun readPatch(file: File): String? = try {
        if (file.exists()) JSONObject(file.readText()).optString("patch").ifEmpty { null }
        else null
    } catch (e: Exception) { null }

    private fun readPatchFromAssets(context: Context): String? = try {
        val text = context.assets.open(LOCAL_FILE).bufferedReader().use { it.readText() }
        JSONObject(text).optString("patch").ifEmpty { null }
    } catch (e: Exception) { null }

    private fun fetchRemotePatch(): String? = try {
        // ดึงแค่ 512 bytes แรกเพื่อหา patch field
        val conn = URL(HEROES_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT
        conn.readTimeout = READ_TIMEOUT
        conn.setRequestProperty("User-Agent", "RaovGuide-App")
        if (conn.responseCode == 200) {
            val buf = ByteArray(512)
            val read = conn.inputStream.use { it.read(buf) }
            val head = String(buf, 0, read)
            Regex(""""patch"\s*:\s*"([\d.]+)"""").find(head)?.groupValues?.get(1)
        } else null
    } catch (e: Exception) {
        Log.d(TAG, "fetchRemotePatch failed: ${e.message}")
        null
    }

    private fun downloadHeroesJson(): String? = try {
        val conn = URL(HEROES_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT
        conn.readTimeout = READ_TIMEOUT
        conn.setRequestProperty("User-Agent", "RaovGuide-App")
        if (conn.responseCode == 200) conn.inputStream.bufferedReader().use { it.readText() }
        else null
    } catch (e: Exception) {
        Log.w(TAG, "download failed: ${e.message}")
        null
    }
}
