# ROV Guide App — Handoff Notes

## Repos
- **Raov-Guide-App** — แอพ Android (repo นี้)
- **Hero-main** (tanakorn1680/Hero-Data หรือชื่อที่ใช้จริง) — scraper + icon downloader

## สถานะปัจจุบัน

### ✅ ทำเสร็จแล้ว
- หน้า HeroDb (ลิสต์ฮีโร่ + search + filter by role + tier badge + icon)
- หน้า HeroDetail (แสดงข้อมูลครบ: patch notes, จุดแข็ง/อ่อน, counters, weakTo, synergies, skills)
- HeroIconCache — โหลด icon จาก GitHub Releases, cache ไว้ในเครื่อง, custom icon, prune orphans
- HeroDatabaseUpdater — ดึง heroes.json จาก Releases อัตโนมัติตอน patch เปลี่ยน
- GitHub Action (Hero-main) — scrape rovmeta → download icons → parse → push Releases ทุกวันจันทร์
- heroes.json มีข้อมูลครบ: strengths, weaknesses, patchChanges, skills, synergies, tier, proStats

### ⚠️ ปัญหาที่กำลังแก้
**Icon ในแอพไม่ตรงกับ hero** แม้ใน GitHub Releases จะถูกต้องแล้ว
- สาเหตุ: แอพใช้ cache เก่าในเครื่อง (filesDir/hero_icons/)
- แก้ล่าสุด: เพิ่ม `iconVersion: "2"` ใน heroes.json (assets) — แอพจะล้าง cache อัตโนมัติเมื่อ build ใหม่

## วิธีทำงาน

### Icon cache flow
1. แอพเริ่ม → `HeroDatabaseUpdater.loadWithUpdate()`
2. อ่าน `iconVersion` จาก `assets/heroes.json`
3. เปรียบเทียบกับ `filesDir/icon_cache_version`
4. ถ้าต่างกัน → `HeroIconCache.clearAutoCache()` ล้างทุกตัวที่ไม่ใช่ custom
5. เขียน version ใหม่ลง `icon_cache_version`
6. `HeroIconCache.getIcon(heroId)` โหลดจาก GitHub Releases: `https://github.com/tanakorn1680/Raov-guide/releases/latest/download/<heroId>.jpg`

### Hero-main Action flow
1. `fetch_rovmeta.py` — ดึง HTML จาก rovmeta.com ทุก hero → `out/rovmeta_raw/`
2. `download_icons.py` — ดึง URL จาก `_heroes_list.html` (แม่นที่สุด) → download → `out/icons/<heroId>.jpg`
3. `parse_heroes.py` — parse HTML → `out/heroes.json` + `out/hero_icons.json`
4. ลบ Release เก่า → สร้าง Release ใหม่พร้อม heroes.json + hero_icons.json + icons/*.jpg

## ไฟล์สำคัญ

```
app/src/main/assets/
  heroes.json          ← ข้อมูลฮีโร่ (iconVersion field ควบคุม cache)
  hero_icons.json      ← URL icon fallback

app/src/main/java/com/mobaanalyzer/
  data/HeroDatabase.kt         ← data class + parser
  data/HeroIconCache.kt        ← cache icon จาก Releases
  data/HeroDatabaseUpdater.kt  ← auto-update + cache version check
  ui/HeroDbActivity.kt         ← หน้าลิสต์
  ui/HeroDetailActivity.kt     ← หน้า detail
```

## ขั้นต่อไป
1. Build + install แอพใหม่ → cache จะล้างอัตโนมัติ → icon โหลดใหม่จาก Releases
2. ถ้า icon ยังผิด → ตรวจสอบว่า `filesDir/icon_cache_version` ถูกเขียนหรือไม่ (log: "Icon cache cleared")
3. งานต่อไปที่ยังไม่ได้ทำ:
   - ระบบ draft/pick analysis ที่ใช้ข้อมูล counters/synergies จริงๆ
   - Overlay แสดงคำแนะนำ pick บน minimap
   - อัปเดต iconVersion ใน Hero-main Action ทุกครั้งที่รูปเปลี่ยน

## หมายเหตุ
- GitHub Releases URL: `https://github.com/tanakorn1680/Raov-guide/releases/latest/download/`
- Hero icon ในแอพ: 128×128 JPEG
- Custom icon (user เลือกเอง) จะไม่ถูกล้างโดย clearAutoCache()
