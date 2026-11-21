package com.synka.studio

import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject

enum class ColorBand(
    val label: String,
    val shortLabel: String,
    val centerHueDeg: Float
) {
    RED("Red", "RED", 0f),
    ORANGE("Orange", "ORN", 30f),
    YELLOW("Yellow", "YEL", 60f),
    GREEN("Green", "GRN", 120f),
    CYAN("Cyan", "CYN", 180f),
    BLUE("Blue", "BLU", 240f),
    PURPLE("Purple", "PUR", 275f),
    MAGENTA("Magenta", "MAG", 320f);

    companion object {
        fun fromNameOrNull(name: String?): ColorBand? {
            if (name.isNullOrBlank()) return null
            return entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
        }
    }
}

@Immutable
data class BandGrade(
    val sat: Float = 0f,
    val hue: Float = 0f,
    val lum: Float = 0f,
    val solo: Boolean = false,
    val mute: Boolean = false
) {
    fun clamped(): BandGrade = copy(
        sat = sat.coerceIn(-100f, 100f),
        hue = hue.coerceIn(-30f, 30f),
        lum = lum.coerceIn(-100f, 100f)
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("sat", sat.toDouble())
        put("hue", hue.toDouble())
        put("lum", lum.toDouble())
        put("solo", solo)
        put("mute", mute)
    }

    companion object {
        fun fromJson(json: JSONObject?): BandGrade {
            if (json == null) return BandGrade()
            return BandGrade(
                sat = json.optDouble("sat", 0.0).toFloat().coerceIn(-100f, 100f),
                hue = json.optDouble("hue", 0.0).toFloat().coerceIn(-30f, 30f),
                lum = json.optDouble("lum", 0.0).toFloat().coerceIn(-100f, 100f),
                solo = json.optBoolean("solo", false),
                mute = json.optBoolean("mute", false)
            )
        }
    }
}

@Immutable
data class ArkaParams(
    val bands: List<BandGrade> = defaultBands(),
    // 5-zone Tone EQ (-100f..100f)
    val blacks: Float = 0f,
    val shadows: Float = 0f,
    val mids: Float = 0f,
    val highlights: Float = 0f,
    val whites: Float = 0f,
    // Cinematic Mood (-1f..1f xy globes; -100f..100f warmth/tint)
    val shadowTintX: Float = 0f,
    val shadowTintY: Float = 0f,
    val highlightTintX: Float = 0f,
    val highlightTintY: Float = 0f,
    val warmth: Float = 0f,
    val tint: Float = 0f,
    // Comfort & Optics (0..100 dimmer/paper; -50..50 RGB trim)
    val subzeroDimmer: Float = 0f,
    val paper: Float = 0f,
    val trimR: Float = 0f,
    val trimG: Float = 0f,
    val trimB: Float = 0f
) {
    fun normalized(): ArkaParams {
        val safeBands = List(ColorBand.entries.size) { index ->
            bands.getOrElse(index) { BandGrade() }.clamped()
        }
        return copy(
            bands = safeBands,
            blacks = blacks.coerceIn(-100f, 100f),
            shadows = shadows.coerceIn(-100f, 100f),
            mids = mids.coerceIn(-100f, 100f),
            highlights = highlights.coerceIn(-100f, 100f),
            whites = whites.coerceIn(-100f, 100f),
            shadowTintX = shadowTintX.coerceIn(-1f, 1f),
            shadowTintY = shadowTintY.coerceIn(-1f, 1f),
            highlightTintX = highlightTintX.coerceIn(-1f, 1f),
            highlightTintY = highlightTintY.coerceIn(-1f, 1f),
            warmth = warmth.coerceIn(-100f, 100f),
            tint = tint.coerceIn(-100f, 100f),
            subzeroDimmer = subzeroDimmer.coerceIn(0f, 100f),
            paper = paper.coerceIn(0f, 100f),
            trimR = trimR.coerceIn(-50f, 50f),
            trimG = trimG.coerceIn(-50f, 50f),
            trimB = trimB.coerceIn(-50f, 50f)
        )
    }

    fun toJson(): JSONObject = JSONObject().apply {
        val bandArr = JSONArray()
        normalized().bands.forEach { bandArr.put(it.toJson()) }
        put("bands", bandArr)

        put("blacks", blacks.toDouble())
        put("shadows", shadows.toDouble())
        put("mids", mids.toDouble())
        put("highlights", highlights.toDouble())
        put("whites", whites.toDouble())

        put("shadowTintX", shadowTintX.toDouble())
        put("shadowTintY", shadowTintY.toDouble())
        put("highlightTintX", highlightTintX.toDouble())
        put("highlightTintY", highlightTintY.toDouble())
        put("warmth", warmth.toDouble())
        put("tint", tint.toDouble())

        put("subzeroDimmer", subzeroDimmer.toDouble())
        put("paper", paper.toDouble())
        put("trimR", trimR.toDouble())
        put("trimG", trimG.toDouble())
        put("trimB", trimB.toDouble())
    }

    companion object {
        fun defaultBands(): List<BandGrade> = List(ColorBand.entries.size) { BandGrade() }

        fun fromJson(json: JSONObject?): ArkaParams {
            if (json == null) return ArkaParams()
            val arr = json.optJSONArray("bands")
            val parsedBands = List(ColorBand.entries.size) { index ->
                BandGrade.fromJson(arr?.optJSONObject(index))
            }
            return ArkaParams(
                bands = parsedBands,
                blacks = json.optDouble("blacks", 0.0).toFloat(),
                shadows = json.optDouble("shadows", 0.0).toFloat(),
                mids = json.optDouble("mids", 0.0).toFloat(),
                highlights = json.optDouble("highlights", 0.0).toFloat(),
                whites = json.optDouble("whites", 0.0).toFloat(),
                shadowTintX = json.optDouble("shadowTintX", 0.0).toFloat(),
                shadowTintY = json.optDouble("shadowTintY", 0.0).toFloat(),
                highlightTintX = json.optDouble("highlightTintX", 0.0).toFloat(),
                highlightTintY = json.optDouble("highlightTintY", 0.0).toFloat(),
                warmth = json.optDouble("warmth", 0.0).toFloat(),
                tint = json.optDouble("tint", 0.0).toFloat(),
                subzeroDimmer = json.optDouble("subzeroDimmer", 0.0).toFloat(),
                paper = json.optDouble("paper", 0.0).toFloat(),
                trimR = json.optDouble("trimR", 0.0).toFloat(),
                trimG = json.optDouble("trimG", 0.0).toFloat(),
                trimB = json.optDouble("trimB", 0.0).toFloat()
            ).normalized()
        }
    }
}

@Immutable
data class EqBand(
    val freqHz: Float,
    val q: Float = 1.4f,
    val gainDb: Float = 0f
) {
    fun clamped(): EqBand = copy(
        freqHz = freqHz.coerceAtLeast(20f),
        q = q.coerceIn(0.1f, 20f),
        gainDb = gainDb.coerceIn(-18f, 18f)
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("freqHz", freqHz.toDouble())
        put("q", q.toDouble())
        put("gainDb", gainDb.toDouble())
    }

    companion object {
        fun fromJson(json: JSONObject?, fallbackFreqHz: Float): EqBand {
            if (json == null) return EqBand(freqHz = fallbackFreqHz)
            return EqBand(
                freqHz = json.optDouble("freqHz", fallbackFreqHz.toDouble()).toFloat().coerceAtLeast(20f),
                q = json.optDouble("q", 1.4).toFloat().coerceIn(0.1f, 20f),
                gainDb = json.optDouble("gainDb", 0.0).toFloat().coerceIn(-18f, 18f)
            )
        }
    }
}

@Immutable
data class SvaraParams(
    val preAmpDb: Float = 0f,
    val bands: List<EqBand> = defaultEqBands(),
    // 5-zone Sound Contour (-12f..12f)
    val su: Float = 0f,
    val mi: Float = 0f,
    val vo: Float = 0f,
    val tr: Float = 0f,
    val ai: Float = 0f,
    // Dual Globes (-1f..1f)
    val bassDepthX: Float = 0f,
    val bassDepthY: Float = 0f,
    val airWidthX: Float = 0f,
    val airWidthY: Float = 0f,
    // Safety
    val nightDialogue: Float = 0f,
    val antiCrackle: Float = 75f,
    val lrBalance: Float = 0f
) {
    fun normalized(): SvaraParams {
        val defaults = defaultEqBands()
        val safeBands = List(defaults.size) { index ->
            val fallbackFreq = defaults[index].freqHz
            val candidate = bands.getOrElse(index) { EqBand(freqHz = fallbackFreq) }.clamped()
            if (candidate.freqHz <= 0f) candidate.copy(freqHz = fallbackFreq) else candidate
        }
        return copy(
            preAmpDb = preAmpDb.coerceIn(-12f, 12f),
            bands = safeBands,
            su = su.coerceIn(-12f, 12f),
            mi = mi.coerceIn(-12f, 12f),
            vo = vo.coerceIn(-12f, 12f),
            tr = tr.coerceIn(-12f, 12f),
            ai = ai.coerceIn(-12f, 12f),
            bassDepthX = bassDepthX.coerceIn(-1f, 1f),
            bassDepthY = bassDepthY.coerceIn(-1f, 1f),
            airWidthX = airWidthX.coerceIn(-1f, 1f),
            airWidthY = airWidthY.coerceIn(-1f, 1f),
            nightDialogue = nightDialogue.coerceIn(0f, 100f),
            antiCrackle = antiCrackle.coerceIn(0f, 100f),
            lrBalance = lrBalance.coerceIn(-100f, 100f)
        )
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("preAmpDb", preAmpDb.toDouble())
        val eqArr = JSONArray()
        normalized().bands.forEach { eqArr.put(it.toJson()) }
        put("bands", eqArr)

        put("su", su.toDouble())
        put("mi", mi.toDouble())
        put("vo", vo.toDouble())
        put("tr", tr.toDouble())
        put("ai", ai.toDouble())

        put("bassDepthX", bassDepthX.toDouble())
        put("bassDepthY", bassDepthY.toDouble())
        put("airWidthX", airWidthX.toDouble())
        put("airWidthY", airWidthY.toDouble())

        put("nightDialogue", nightDialogue.toDouble())
        put("antiCrackle", antiCrackle.toDouble())
        put("lrBalance", lrBalance.toDouble())
    }

    companion object {
        val DEFAULT_FREQUENCIES_HZ: List<Float> = listOf(
            31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f
        )

        fun defaultEqBands(): List<EqBand> = DEFAULT_FREQUENCIES_HZ.map { freq ->
            EqBand(freqHz = freq, q = 1.4f, gainDb = 0f)
        }

        fun fromJson(json: JSONObject?): SvaraParams {
            if (json == null) return SvaraParams()
            val arr = json.optJSONArray("bands")
            val parsedBands = List(DEFAULT_FREQUENCIES_HZ.size) { index ->
                EqBand.fromJson(arr?.optJSONObject(index), DEFAULT_FREQUENCIES_HZ[index])
            }
            return SvaraParams(
                preAmpDb = json.optDouble("preAmpDb", 0.0).toFloat(),
                bands = parsedBands,
                su = json.optDouble("su", 0.0).toFloat(),
                mi = json.optDouble("mi", 0.0).toFloat(),
                vo = json.optDouble("vo", 0.0).toFloat(),
                tr = json.optDouble("tr", 0.0).toFloat(),
                ai = json.optDouble("ai", 0.0).toFloat(),
                bassDepthX = json.optDouble("bassDepthX", 0.0).toFloat(),
                bassDepthY = json.optDouble("bassDepthY", 0.0).toFloat(),
                airWidthX = json.optDouble("airWidthX", 0.0).toFloat(),
                airWidthY = json.optDouble("airWidthY", 0.0).toFloat(),
                nightDialogue = json.optDouble("nightDialogue", 0.0).toFloat(),
                antiCrackle = json.optDouble("antiCrackle", 75.0).toFloat(),
                lrBalance = json.optDouble("lrBalance", 0.0).toFloat()
            ).normalized()
        }
    }
}

enum class PresetDomain {
    DISPLAY,
    AUDIO,
    BOTH
}

enum class DisplayGenre {
    CINEMA_GRADE,
    RED_ISO,
    BW,
    WARM
}

enum class AudioGenre {
    BASS,
    FLAT,
    VOCAL,
    LATE_NIGHT
}

@Immutable
data class SynkaPreset(
    val id: String,
    val name: String,
    val domain: PresetDomain,
    val displayGenre: DisplayGenre? = null,
    val audioGenre: AudioGenre? = null,
    val arka: ArkaParams = ArkaParams(),
    val svara: SvaraParams = SvaraParams(),
    val isFavorite: Boolean = false,
    val isUserCreated: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("domain", domain.name)
        put("displayGenre", displayGenre?.name ?: JSONObject.NULL)
        put("audioGenre", audioGenre?.name ?: JSONObject.NULL)
        put("arka", arka.normalized().toJson())
        put("svara", svara.normalized().toJson())
        put("isFavorite", isFavorite)
        put("isUserCreated", isUserCreated)
    }

    fun toJsonString(): String = toJson().toString()

    companion object {
        fun fromJson(json: JSONObject): SynkaPreset {
            val id = json.optString("id", "custom_preset").ifBlank { "custom_preset" }
            val name = json.optString("name", "Untitled Preset").ifBlank { "Untitled Preset" }
            val domain = PresetDomain.entries.firstOrNull {
                it.name.equals(json.optString("domain"), ignoreCase = true)
            } ?: PresetDomain.BOTH

            val dispRaw = if (json.isNull("displayGenre")) null else json.optString("displayGenre")
            val audRaw = if (json.isNull("audioGenre")) null else json.optString("audioGenre")

            val displayGenre = DisplayGenre.entries.firstOrNull {
                it.name.equals(dispRaw, ignoreCase = true)
            }
            val audioGenre = AudioGenre.entries.firstOrNull {
                it.name.equals(audRaw, ignoreCase = true)
            }

            return SynkaPreset(
                id = id,
                name = name,
                domain = domain,
                displayGenre = displayGenre,
                audioGenre = audioGenre,
                arka = ArkaParams.fromJson(json.optJSONObject("arka")),
                svara = SvaraParams.fromJson(json.optJSONObject("svara")),
                isFavorite = json.optBoolean("isFavorite", false),
                isUserCreated = json.optBoolean("isUserCreated", false)
            )
        }

        fun fromJsonString(raw: String): SynkaPreset = fromJson(JSONObject(raw))

        fun listToJsonArray(presets: List<SynkaPreset>): JSONArray = JSONArray().apply {
            presets.forEach { put(it.toJson()) }
        }

        fun listFromJsonArray(array: JSONArray?): List<SynkaPreset> {
            if (array == null) return emptyList()
            return List(array.length()) { index ->
                fromJson(array.getJSONObject(index))
            }
        }
    }
}

object SynkaBuiltInPresets {

    private fun eqWithGains(gainsByIndex: Map<Int, Float>, preAmpDb: Float = 0f): SvaraParams {
        val updatedBands = SvaraParams.DEFAULT_FREQUENCIES_HZ.mapIndexed { idx, freq ->
            EqBand(
                freqHz = freq,
                q = 1.4f,
                gainDb = (gainsByIndex[idx] ?: 0f).coerceIn(-18f, 18f)
            )
        }
        return SvaraParams(
            preAmpDb = preAmpDb,
            bands = updatedBands
        ).normalized()
    }

    private fun bandsWithOverrides(overrides: Map<ColorBand, BandGrade>): List<BandGrade> {
        return ColorBand.entries.map { band ->
            (overrides[band] ?: BandGrade()).clamped()
        }
    }

    private fun redIsoBands(): List<BandGrade> {
        return ColorBand.entries.map { band ->
            if (band == ColorBand.RED) {
                BandGrade(sat = 35f, hue = 0f, lum = 8f, solo = true, mute = false)
            } else {
                BandGrade(sat = -100f, hue = 0f, lum = -6f, solo = false, mute = false)
            }
        }
    }

    // 1. KORA: Pure untouched neutral preset
    val KORA = SynkaPreset(
        id = "builtin_kora",
        name = "Kora",
        domain = PresetDomain.BOTH,
        displayGenre = DisplayGenre.CINEMA_GRADE,
        audioGenre = AudioGenre.FLAT,
        arka = ArkaParams(),
        svara = SvaraParams(),
        isFavorite = true,
        isUserCreated = false
    )

    // Display Genre Presets (2+ per DisplayGenre: CINEMA_GRADE, RED_ISO, BW, WARM)
    val CINEMA_TEAL_GOLD = SynkaPreset(
        id = "builtin_cinema_teal_gold",
        name = "Cinema Teal & Gold",
        domain = PresetDomain.DISPLAY,
        displayGenre = DisplayGenre.CINEMA_GRADE,
        arka = ArkaParams(
            bands = bandsWithOverrides(
                mapOf(
                    ColorBand.CYAN to BandGrade(sat = 18f, hue = -4f, lum = -4f),
                    ColorBand.BLUE to BandGrade(sat = 14f, hue = -5f, lum = -6f),
                    ColorBand.ORANGE to BandGrade(sat = 16f, hue = 3f, lum = 6f),
                    ColorBand.YELLOW to BandGrade(sat = 12f, hue = 2f, lum = 5f)
                )
            ),
            blacks = -12f,
            shadows = -8f,
            mids = 6f,
            highlights = 10f,
            whites = 8f,
            shadowTintX = -0.35f,
            shadowTintY = 0.20f,
            highlightTintX = 0.30f,
            highlightTintY = -0.15f,
            warmth = 10f,
            tint = -4f
        )
    )

    val CINEMA_NOCTURNE_FILM = SynkaPreset(
        id = "builtin_cinema_nocturne",
        name = "Cinema Nocturne 2383",
        domain = PresetDomain.DISPLAY,
        displayGenre = DisplayGenre.CINEMA_GRADE,
        arka = ArkaParams(
            bands = bandsWithOverrides(
                mapOf(
                    ColorBand.RED to BandGrade(sat = 10f, hue = -2f, lum = -4f),
                    ColorBand.BLUE to BandGrade(sat = -10f, hue = 4f, lum = -10f),
                    ColorBand.GREEN to BandGrade(sat = -14f, hue = -3f, lum = -5f)
                )
            ),
            blacks = -18f,
            shadows = -10f,
            mids = 12f,
            highlights = -6f,
            whites = 4f,
            shadowTintX = -0.25f,
            shadowTintY = 0.35f,
            highlightTintX = 0.18f,
            highlightTintY = 0.10f,
            warmth = 6f,
            tint = 5f
        )
    )

    val RED_ISO_CRIMSON_LOCK = SynkaPreset(
        id = "builtin_red_iso_crimson",
        name = "Red ISO Crimson Lock",
        domain = PresetDomain.DISPLAY,
        displayGenre = DisplayGenre.RED_ISO,
        arka = ArkaParams(
            bands = redIsoBands(),
            blacks = -10f,
            shadows = -5f,
            mids = 8f,
            highlights = 6f,
            whites = 5f
        )
    )

    val RED_ISO_HIGH_CONTRAST = SynkaPreset(
        id = "builtin_red_iso_contrast",
        name = "Red ISO High Contrast",
        domain = PresetDomain.DISPLAY,
        displayGenre = DisplayGenre.RED_ISO,
        arka = ArkaParams(
            bands = redIsoBands().mapIndexed { idx, bg ->
                if (idx == ColorBand.RED.ordinal) bg.copy(sat = 50f, lum = 14f) else bg
            },
            blacks = -22f,
            shadows = -12f,
            mids = 16f,
            highlights = 12f,
            whites = 10f
        )
    )

    val BW_SILVER_GELATIN = SynkaPreset(
        id = "builtin_bw_silver_gelatin",
        name = "Silver Gelatin B&W",
        domain = PresetDomain.DISPLAY,
        displayGenre = DisplayGenre.BW,
        arka = ArkaParams(
            bands = bandsWithOverrides(
                ColorBand.entries.associateWith { BandGrade(sat = -100f) }
            ),
            blacks = -16f,
            shadows = -8f,
            mids = 10f,
            highlights = 12f,
            whites = 14f
        )
    )

    val BW_LEICA_MONOCHROM = SynkaPreset(
        id = "builtin_bw_leica_mono",
        name = "Monochrom Deep Contrast",
        domain = PresetDomain.DISPLAY,
        displayGenre = DisplayGenre.BW,
        arka = ArkaParams(
            bands = bandsWithOverrides(
                ColorBand.entries.associateWith { band ->
                    val lumBoost = when (band) {
                        ColorBand.RED, ColorBand.ORANGE, ColorBand.YELLOW -> 8f
                        ColorBand.BLUE, ColorBand.CYAN -> -10f
                        else -> 0f
                    }
                    BandGrade(sat = -100f, lum = lumBoost)
                }
            ),
            blacks = -24f,
            shadows = -14f,
            mids = 15f,
            highlights = 8f,
            whites = 12f
        )
    )

    val WARM_GOLDEN_HOUR = SynkaPreset(
        id = "builtin_warm_golden_hour",
        name = "Golden Hour Glow",
        domain = PresetDomain.DISPLAY,
        displayGenre = DisplayGenre.WARM,
        arka = ArkaParams(
            bands = bandsWithOverrides(
                mapOf(
                    ColorBand.ORANGE to BandGrade(sat = 14f, hue = 4f, lum = 6f),
                    ColorBand.YELLOW to BandGrade(sat = 12f, hue = 2f, lum = 8f),
                    ColorBand.BLUE to BandGrade(sat = -15f, hue = 3f, lum = -4f)
                )
            ),
            shadows = 6f,
            highlights = 8f,
            warmth = 38f,
            tint = 6f,
            paper = 15f
        )
    )

    val WARM_CANDLELIGHT_PAPER = SynkaPreset(
        id = "builtin_warm_candlelight",
        name = "Candlelight Reading",
        domain = PresetDomain.DISPLAY,
        displayGenre = DisplayGenre.WARM,
        arka = ArkaParams(
            warmth = 55f,
            tint = 10f,
            paper = 35f,
            subzeroDimmer = 20f,
            trimB = -12f
        )
    )

    // Audio Genre Presets (2+ per AudioGenre: BASS, FLAT, VOCAL, LATE_NIGHT)
    val AUDIO_SUB_BASS_PUNCH = SynkaPreset(
        id = "builtin_audio_sub_bass",
        name = "Sub Bass Punch",
        domain = PresetDomain.AUDIO,
        audioGenre = AudioGenre.BASS,
        svara = eqWithGains(
            gainsByIndex = mapOf(0 to 8f, 1 to 7f, 2 to 4.5f, 3 to 1f, 8 to 2f, 9 to 3f),
            preAmpDb = -4f
        ).copy(
            su = 7.5f,
            mi = 4.0f,
            vo = -1.0f,
            tr = 2.0f,
            ai = 2.5f,
            bassDepthX = 0.25f,
            bassDepthY = 0.55f,
            antiCrackle = 88f
        ).normalized()
    )

    val AUDIO_DEEP_WARMTH = SynkaPreset(
        id = "builtin_audio_deep_warmth",
        name = "Deep Low-End Foundation",
        domain = PresetDomain.AUDIO,
        audioGenre = AudioGenre.BASS,
        svara = eqWithGains(
            gainsByIndex = mapOf(0 to 6f, 1 to 6f, 2 to 3.5f, 4 to -1f),
            preAmpDb = -3f
        ).copy(
            su = 6.0f,
            mi = 3.0f,
            bassDepthX = -0.15f,
            bassDepthY = 0.45f,
            antiCrackle = 90f
        ).normalized()
    )

    val AUDIO_STUDIO_FLAT = SynkaPreset(
        id = "builtin_audio_studio_flat",
        name = "Reference Flat",
        domain = PresetDomain.AUDIO,
        audioGenre = AudioGenre.FLAT,
        svara = SvaraParams(
            preAmpDb = 0f,
            antiCrackle = 75f
        )
    )

    val AUDIO_TRANSPARENT_MONITOR = SynkaPreset(
        id = "builtin_audio_transparent",
        name = "Transparent Monitor",
        domain = PresetDomain.AUDIO,
        audioGenre = AudioGenre.FLAT,
        svara = SvaraParams(
            preAmpDb = 0f,
            antiCrackle = 85f
        )
    )

    val AUDIO_VOCAL_CLARITY = SynkaPreset(
        id = "builtin_audio_vocal_clarity",
        name = "Vocal Presence & Air",
        domain = PresetDomain.AUDIO,
        audioGenre = AudioGenre.VOCAL,
        svara = eqWithGains(
            gainsByIndex = mapOf(1 to -2f, 2 to -1.5f, 4 to 2.5f, 5 to 4.5f, 6 to 5.0f, 7 to 3.0f, 8 to 2.5f),
            preAmpDb = -2f
        ).copy(
            su = -2.0f,
            vo = 5.5f,
            tr = 3.0f,
            ai = 3.5f,
            airWidthX = 0.30f,
            airWidthY = 0.35f,
            nightDialogue = 25f
        ).normalized()
    )

    val AUDIO_PODCAST_INTELLIGIBILITY = SynkaPreset(
        id = "builtin_audio_podcast_speech",
        name = "Dialogue Focus",
        domain = PresetDomain.AUDIO,
        audioGenre = AudioGenre.VOCAL,
        svara = eqWithGains(
            gainsByIndex = mapOf(0 to -4f, 1 to -3f, 2 to -1.5f, 5 to 4.0f, 6 to 4.5f, 7 to 2.5f),
            preAmpDb = -1.5f
        ).copy(
            su = -3.5f,
            vo = 5.0f,
            tr = 2.0f,
            nightDialogue = 40f
        ).normalized()
    )

    val AUDIO_LATE_NIGHT_SOFT = SynkaPreset(
        id = "builtin_audio_late_night_soft",
        name = "Late Night Soft",
        domain = PresetDomain.AUDIO,
        audioGenre = AudioGenre.LATE_NIGHT,
        svara = eqWithGains(
            gainsByIndex = mapOf(0 to -4f, 1 to -2.5f, 5 to 2.5f, 6 to 2.0f, 8 to -3.5f, 9 to -5f),
            preAmpDb = -2f
        ).copy(
            su = -3.0f,
            vo = 3.0f,
            tr = -2.5f,
            ai = -4.0f,
            nightDialogue = 75f,
            antiCrackle = 92f
        ).normalized()
    )

    val AUDIO_LATE_NIGHT_WHISPER = SynkaPreset(
        id = "builtin_audio_late_night_whisper",
        name = "Midnight Whisper",
        domain = PresetDomain.AUDIO,
        audioGenre = AudioGenre.LATE_NIGHT,
        svara = eqWithGains(
            gainsByIndex = mapOf(0 to -6f, 1 to -4f, 2 to -2f, 5 to 3.0f, 8 to -4f, 9 to -6f),
            preAmpDb = -3f
        ).copy(
            su = -4.5f,
            vo = 3.5f,
            tr = -3.5f,
            ai = -5.0f,
            nightDialogue = 90f,
            antiCrackle = 95f
        ).normalized()
    )

    // Paired BOTH-domain presets linking DisplayGenre and AudioGenre
    val PAIRED_CINEMA_NIGHT = SynkaPreset(
        id = "builtin_paired_cinema_night",
        name = "Cinema Late Night",
        domain = PresetDomain.BOTH,
        displayGenre = DisplayGenre.CINEMA_GRADE,
        audioGenre = AudioGenre.LATE_NIGHT,
        arka = CINEMA_NOCTURNE_FILM.arka,
        svara = AUDIO_LATE_NIGHT_SOFT.svara
    )

    val PAIRED_WARM_LOUNGE = SynkaPreset(
        id = "builtin_paired_warm_lounge",
        name = "Warm Lounge",
        domain = PresetDomain.BOTH,
        displayGenre = DisplayGenre.WARM,
        audioGenre = AudioGenre.BASS,
        arka = WARM_GOLDEN_HOUR.arka,
        svara = AUDIO_DEEP_WARMTH.svara
    )

    val PAIRED_RED_ISO_VOCAL = SynkaPreset(
        id = "builtin_paired_red_iso_vocal",
        name = "Red ISO Studio",
        domain = PresetDomain.BOTH,
        displayGenre = DisplayGenre.RED_ISO,
        audioGenre = AudioGenre.VOCAL,
        arka = RED_ISO_CRIMSON_LOCK.arka,
        svara = AUDIO_VOCAL_CLARITY.svara
    )

    val PAIRED_BW_REFERENCE = SynkaPreset(
        id = "builtin_paired_bw_reference",
        name = "Monochrome Reference",
        domain = PresetDomain.BOTH,
        displayGenre = DisplayGenre.BW,
        audioGenre = AudioGenre.FLAT,
        arka = BW_SILVER_GELATIN.arka,
        svara = AUDIO_STUDIO_FLAT.svara
    )

    val ALL_PRESETS: List<SynkaPreset> = listOf(
        KORA,
        CINEMA_TEAL_GOLD,
        CINEMA_NOCTURNE_FILM,
        RED_ISO_CRIMSON_LOCK,
        RED_ISO_HIGH_CONTRAST,
        BW_SILVER_GELATIN,
        BW_LEICA_MONOCHROM,
        WARM_GOLDEN_HOUR,
        WARM_CANDLELIGHT_PAPER,
        AUDIO_SUB_BASS_PUNCH,
        AUDIO_DEEP_WARMTH,
        AUDIO_STUDIO_FLAT,
        AUDIO_TRANSPARENT_MONITOR,
        AUDIO_VOCAL_CLARITY,
        AUDIO_PODCAST_INTELLIGIBILITY,
        AUDIO_LATE_NIGHT_SOFT,
        AUDIO_LATE_NIGHT_WHISPER,
        PAIRED_CINEMA_NIGHT,
        PAIRED_WARM_LOUNGE,
        PAIRED_RED_ISO_VOCAL,
        PAIRED_BW_REFERENCE
    )

    private val linkedDisplayToAudioMap: Map<DisplayGenre, AudioGenre> = mapOf(
        DisplayGenre.CINEMA_GRADE to AudioGenre.LATE_NIGHT,
        DisplayGenre.RED_ISO to AudioGenre.VOCAL,
        DisplayGenre.BW to AudioGenre.FLAT,
        DisplayGenre.WARM to AudioGenre.BASS
    )

    private val linkedAudioToDisplayMap: Map<AudioGenre, DisplayGenre> = mapOf(
        AudioGenre.BASS to DisplayGenre.WARM,
        AudioGenre.FLAT to DisplayGenre.BW,
        AudioGenre.VOCAL to DisplayGenre.RED_ISO,
        AudioGenre.LATE_NIGHT to DisplayGenre.CINEMA_GRADE
    )

    fun linkedAudioGenreFor(displayGenre: DisplayGenre): AudioGenre =
        linkedDisplayToAudioMap[displayGenre] ?: AudioGenre.FLAT

    fun linkedDisplayGenreFor(audioGenre: AudioGenre): DisplayGenre =
        linkedAudioToDisplayMap[audioGenre] ?: DisplayGenre.WARM

    fun byDisplayGenre(genre: DisplayGenre): List<SynkaPreset> =
        ALL_PRESETS.filter { it.displayGenre == genre }

    fun byAudioGenre(genre: AudioGenre): List<SynkaPreset> =
        ALL_PRESETS.filter { it.audioGenre == genre }

    fun pairedPresetFor(displayGenre: DisplayGenre, audioGenre: AudioGenre): SynkaPreset {
        return ALL_PRESETS.firstOrNull {
            it.domain == PresetDomain.BOTH &&
                it.displayGenre == displayGenre &&
                it.audioGenre == audioGenre
        } ?: SynkaPreset(
            id = "paired_${displayGenre.name.lowercase()}_${audioGenre.name.lowercase()}",
            name = "${displayGenre.name.replace('_', ' ')} + ${audioGenre.name.replace('_', ' ')}",
            domain = PresetDomain.BOTH,
            displayGenre = displayGenre,
            audioGenre = audioGenre,
            arka = byDisplayGenre(displayGenre).firstOrNull()?.arka ?: ArkaParams(),
            svara = byAudioGenre(audioGenre).firstOrNull()?.svara ?: SvaraParams()
        )
    }
}
