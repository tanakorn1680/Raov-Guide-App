package com.mobaanalyzer.ui

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.mobaanalyzer.R
import com.mobaanalyzer.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class HeroDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_HERO_ID = "hero_id"
        private const val PICK_IMAGE = 1001
    }

    private lateinit var hero: Hero

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hero_detail)

        val heroId = intent.getStringExtra(EXTRA_HERO_ID) ?: run { finish(); return }
        val db = AppState.heroDb ?: HeroDatabase.load(this)
        hero = db.heroes.find { it.id == heroId } ?: run { finish(); return }
        HeroIconCache.initIfNeeded(applicationContext, db.heroes.map { it.id }.toSet())

        populateHeader()
        populatePatch()
        populateStats()
        populateStrengths()
        populateWeaknesses()
        populateMatchups(db)
        populateSkills()
        setupChangeIcon()
        loadIcon()
    }

    // ── Header ────────────────────────────────────────────────────────────────

    private fun populateHeader() {
        val tierColor = mapOf(
            "S+" to 0xFFFF4444.toInt(), "S" to 0xFFFF8844.toInt(),
            "A"  to 0xFFFFDD44.toInt(), "B" to 0xFF88DD44.toInt(),
            "C"  to 0xFF44AAFF.toInt(), "D" to 0xFF888888.toInt()
        )
        val laneLabel = mapOf(
            "JUNGLE" to "ป่า", "MID" to "กลาง",
            "ABYSSAL" to "ล่าง", "DARK_SLAYER" to "บน"
        )
        val roleLabel = when (hero.role) {
            Role.TANK -> "แทงค์"; Role.FIGHTER -> "วอริเออร์"
            Role.ASSASSIN -> "อัสซาซิน"; Role.MAGE -> "เมจ"
            Role.CARRY -> "มาร์คแมน"; Role.SUPPORT -> "ซัพพอร์ต"
            else -> hero.role.name
        }
        val lanes = hero.lanes.mapNotNull { laneLabel[it] }.joinToString(" / ")

        findViewById<TextView>(R.id.tvDetailName).text = hero.name
        findViewById<TextView>(R.id.tvDetailRole).text =
            if (lanes.isNotEmpty()) "$roleLabel · $lanes" else roleLabel
        findViewById<TextView>(R.id.tvDetailTags).text = hero.tags.joinToString(" · ")

        val tvTier = findViewById<TextView>(R.id.tvDetailTier)
        if (hero.tier != null) {
            tvTier.text = hero.tier
            tvTier.setTextColor(tierColor[hero.tier] ?: 0xFF888888.toInt())
            tvTier.visibility = View.VISIBLE
        } else tvTier.visibility = View.GONE
    }

    // ── Patch ─────────────────────────────────────────────────────────────────

    private fun populatePatch() {
        if (hero.patchStatus == null && hero.patchChanges.isEmpty()) return

        val rowPatch = findViewById<View>(R.id.rowPatch)
        rowPatch.visibility = View.VISIBLE

        val statusColor = mapOf(
            "Buffed" to 0xFF4ADE80.toInt(),
            "Nerfed" to 0xFFF87171.toInt(),
            "Adjusted" to 0xFFFACC15.toInt(),
            "New" to 0xFF60A5FA.toInt(),
            "Reworked" to 0xFFE879F9.toInt()
        )
        val tvStatus = findViewById<TextView>(R.id.tvPatchStatus)
        if (hero.patchStatus != null) {
            tvStatus.text = hero.patchStatus
            tvStatus.setTextColor(statusColor[hero.patchStatus] ?: 0xFFFFFFFF.toInt())
            tvStatus.visibility = View.VISIBLE
        }
        if (hero.patchVersion != null) {
            val tvVer = findViewById<TextView>(R.id.tvPatchVersion)
            tvVer.text = "Patch ${hero.patchVersion}"
            tvVer.visibility = View.VISIBLE
        }

        if (hero.patchChanges.isNotEmpty()) {
            val sectionPatch = findViewById<View>(R.id.sectionPatch)
            sectionPatch.visibility = View.VISIBLE
            val tvChanges = findViewById<TextView>(R.id.tvPatchChanges)
            tvChanges.text = hero.patchChanges.joinToString("\n") { "• $it" }
        }
    }

    // ── Pro stats ─────────────────────────────────────────────────────────────

    private fun populateStats() {
        if (hero.proWinRate == null && hero.proPickRate == null && hero.proBanRate == null) return
        val rowStats = findViewById<View>(R.id.rowStats)
        rowStats.visibility = View.VISIBLE
        hero.proWinRate?.let { findViewById<TextView>(R.id.tvWin).text = "%.1f%%".format(it) }
        hero.proPickRate?.let { findViewById<TextView>(R.id.tvPick).text = "%.1f%%".format(it) }
        hero.proBanRate?.let { findViewById<TextView>(R.id.tvBan).text = "%.1f%%".format(it) }
    }

    // ── Strengths / Weaknesses ────────────────────────────────────────────────

    private fun populateStrengths() {
        if (hero.strengths.isEmpty()) return
        val sec = findViewById<View>(R.id.sectionStrengths)
        sec.visibility = View.VISIBLE
        findViewById<TextView>(R.id.tvStrengths).text =
            hero.strengths.joinToString("\n") { "✓  $it" }
    }

    private fun populateWeaknesses() {
        if (hero.weaknesses.isEmpty()) return
        val sec = findViewById<View>(R.id.sectionWeaknesses)
        sec.visibility = View.VISIBLE
        findViewById<TextView>(R.id.tvWeaknesses).text =
            hero.weaknesses.joinToString("\n") { "✕  $it" }
    }

    // ── Matchups ──────────────────────────────────────────────────────────────

    private fun populateMatchups(db: HeroDatabase) {
        fun resolveNames(ids: Set<String>) =
            ids.mapNotNull { id -> db.heroes.find { it.id == id }?.name ?: id.ifBlank { null } }
                .joinToString("  ·  ")

        if (hero.counters.isNotEmpty()) {
            findViewById<View>(R.id.sectionCounters).visibility = View.VISIBLE
            findViewById<TextView>(R.id.tvCounters).text = resolveNames(hero.counters)
        }
        if (hero.weakTo.isNotEmpty()) {
            findViewById<View>(R.id.sectionWeakTo).visibility = View.VISIBLE
            findViewById<TextView>(R.id.tvWeakTo).text = resolveNames(hero.weakTo)
        }
        if (hero.synergies.isNotEmpty()) {
            findViewById<View>(R.id.sectionSynergies).visibility = View.VISIBLE
            findViewById<TextView>(R.id.tvSynergies).text = resolveNames(hero.synergies)
        }
    }

    // ── Skills ────────────────────────────────────────────────────────────────

    private fun populateSkills() {
        if (hero.skills.isEmpty()) return
        val secSkills = findViewById<View>(R.id.sectionSkills)
        secSkills.visibility = View.VISIBLE
        val container = findViewById<LinearLayout>(R.id.skillContainer)
        container.removeAllViews()

        val slotLabel = mapOf(
            "passive" to "P", "skill1" to "1",
            "skill2" to "2", "skill3" to "3", "ultimate" to "ULT"
        )

        for (skill in hero.skills) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 0, 0, dp(12))
            }
            val nameRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val badge = TextView(this).apply {
                text = slotLabel[skill.slot] ?: skill.slot.uppercase()
                textSize = 10f
                setTextColor(0xFF60A5FA.toInt())
                setPadding(dp(6), dp(2), dp(6), dp(2))
                background = resources.getDrawable(R.drawable.bg_chip, null)
            }
            val skillName = TextView(this).apply {
                text = skill.name
                textSize = 14f
                setTextColor(0xFFFFFFFF.toInt())
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setPadding(dp(8), 0, 0, 0)
            }
            nameRow.addView(badge)
            nameRow.addView(skillName)
            row.addView(nameRow)

            if (skill.desc.isNotEmpty()) {
                val desc = TextView(this).apply {
                    text = skill.desc
                    textSize = 12f
                    setTextColor(0xFF999999.toInt())
                    setPadding(0, dp(4), 0, 0)
                    setLineSpacing(0f, 1.4f)
                }
                row.addView(desc)
            }
            container.addView(row)
        }
    }

    // ── Change icon ───────────────────────────────────────────────────────────

    private fun setupChangeIcon() {
        val panelChangeIcon = findViewById<View>(R.id.panelChangeIcon)
        findViewById<TextView>(R.id.btnChangeIcon).setOnClickListener {
            panelChangeIcon.visibility =
                if (panelChangeIcon.visibility == View.GONE) View.VISIBLE else View.GONE
        }
        findViewById<Button>(R.id.btnPickFromGallery).setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_PICK).apply { type = "image/*" }, PICK_IMAGE)
        }
        findViewById<Button>(R.id.btnSaveIcon).setOnClickListener { saveIconFromUrl() }
        findViewById<Button>(R.id.btnResetIcon).setOnClickListener {
            HeroIconCache.clearCustomIcon(hero.id)
            loadIcon()
            panelChangeIcon.visibility = View.GONE
            Toast.makeText(this, "รีเซ็ต icon แล้ว", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadIcon() {
        val ivIcon = findViewById<ImageView>(R.id.ivDetailIcon)
        ivIcon.setImageDrawable(null)
        lifecycleScope.launch {
            HeroIconCache.getIcon(hero.id)?.let { ivIcon.setImageBitmap(it) }
        }
    }

    private fun saveIconFromUrl() {
        val url = findViewById<EditText>(R.id.etIconUrl).text.toString().trim()
        if (url.isEmpty()) { Toast.makeText(this, "กรอก URL ก่อน", Toast.LENGTH_SHORT).show(); return }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 8_000; conn.readTimeout = 10_000
                val bmp = conn.inputStream.use { BitmapFactory.decodeStream(it) }
                if (bmp != null) {
                    HeroIconCache.saveCustomIcon(hero.id, bmp)
                    withContext(Dispatchers.Main) {
                        findViewById<ImageView>(R.id.ivDetailIcon).setImageBitmap(bmp)
                        findViewById<View>(R.id.panelChangeIcon).visibility = View.GONE
                        Toast.makeText(this@HeroDetailActivity, "บันทึก icon แล้ว", Toast.LENGTH_SHORT).show()
                    }
                } else withContext(Dispatchers.Main) {
                    Toast.makeText(this@HeroDetailActivity, "โหลดรูปไม่ได้", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@HeroDetailActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_IMAGE && resultCode == Activity.RESULT_OK) {
            val uri: Uri = data?.data ?: return
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                    if (bmp != null) {
                        HeroIconCache.saveCustomIcon(hero.id, bmp)
                        withContext(Dispatchers.Main) {
                            findViewById<ImageView>(R.id.ivDetailIcon).setImageBitmap(bmp)
                            findViewById<View>(R.id.panelChangeIcon).visibility = View.GONE
                            Toast.makeText(this@HeroDetailActivity, "บันทึก icon แล้ว", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@HeroDetailActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
