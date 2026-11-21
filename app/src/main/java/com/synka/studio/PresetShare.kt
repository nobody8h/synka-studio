package com.synka.studio

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

object PresetShare {

    const val PREFIX: String = "SYNK1:"
    private const val MAX_ENCODED_LENGTH: Int = 32 * 1024
    private const val MAX_DECOMPRESSED_BYTES: Int = 64 * 1024
    private const val MAX_REMOTE_CATALOG_BYTES: Int = 256 * 1024
    private const val REMOTE_TIMEOUT_MS: Int = 4_000

    /**
     * Serializes [preset] to JSON, GZIP-compresses it, encodes with URL-safe Base64
     * (no line wraps), and prefixes with "SYNK1:".
     */
    fun encodePreset(preset: SynkaPreset): String {
        val normalizedPreset = preset.copy(
            arka = preset.arka.normalized(),
            svara = preset.svara.normalized()
        )
        val rawJsonBytes = normalizedPreset.toJson().toString().toByteArray(StandardCharsets.UTF_8)
        val compressedBytes = ByteArrayOutputStream().use { baos ->
            GZIPOutputStream(baos).use { gzip ->
                gzip.write(rawJsonBytes)
            }
            baos.toByteArray()
        }
        val encoded = Base64.encodeToString(
            compressedBytes,
            Base64.URL_SAFE or Base64.NO_WRAP
        )
        return "$PREFIX$encoded"
    }

    /**
     * Validates the "SYNK1:" prefix, safely decompresses with a strict 64 KB ceiling
     * (preventing GZIP decompression bombs), and parses [SynkaPreset] without crashing.
     */
    fun decodePreset(code: String): Result<SynkaPreset> = runCatching {
        val cleaned = code.trim().replace(Regex("\\s+"), "")
        require(cleaned.isNotEmpty()) { "Preset code is empty." }
        require(cleaned.startsWith(PREFIX)) { "Invalid preset code. Expected prefix '$PREFIX'." }

        val payload = cleaned.removePrefix(PREFIX)
        require(payload.isNotEmpty()) { "Preset code payload is empty." }
        require(payload.length <= MAX_ENCODED_LENGTH) {
            "Preset code exceeds maximum allowed length."
        }

        val compressedBytes = try {
            Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP)
        } catch (_: IllegalArgumentException) {
            Base64.decode(payload, Base64.DEFAULT)
        }
        require(compressedBytes.isNotEmpty()) { "Decoded Base64 payload is empty." }

        val decompressedBytes = ByteArrayOutputStream().use { out ->
            GZIPInputStream(ByteArrayInputStream(compressedBytes)).use { gzip ->
                val buffer = ByteArray(2048)
                var totalRead = 0
                while (true) {
                    val read = gzip.read(buffer)
                    if (read == -1) break
                    totalRead += read
                    if (totalRead > MAX_DECOMPRESSED_BYTES) {
                        throw IllegalArgumentException(
                            "Preset payload exceeds 64 KB safety limit."
                        )
                    }
                    out.write(buffer, 0, read)
                }
            }
            out.toByteArray()
        }

        val jsonText = String(decompressedBytes, StandardCharsets.UTF_8)
        val json = JSONObject(jsonText)
        require(json.has("name") || json.has("arka") || json.has("svara")) {
            "Decoded JSON does not contain valid Synka preset fields."
        }

        val parsed = SynkaPreset.fromJson(json)
        parsed.copy(
            arka = parsed.arka.normalized(),
            svara = parsed.svara.normalized()
        )
    }

    // -------------------------------------------------------------------------
    // Discover Catalog: Curated Built-Ins + Optional GitHub Raw JSON Sync
    // -------------------------------------------------------------------------

    data class CatalogEntry(
        val preset: SynkaPreset,
        val author: String,
        val description: String,
        val tags: List<String>,
        val isRemote: Boolean = false
    )

    data class CatalogFetchResult(
        val entries: List<CatalogEntry>,
        val fetchedFromRemote: Boolean,
        val statusMessage: String
    )

    object DiscoverCatalog {

        const val DEFAULT_REMOTE_CATALOG_URL: String =
            "https://raw.githubusercontent.com/synka-studio/synka-presets/main/catalog.json"

        @Volatile
        var remoteCatalogUrl: String = DEFAULT_REMOTE_CATALOG_URL

        @Volatile
        private var cachedRemoteEntries: List<CatalogEntry> = emptyList()

        fun clearCache() {
            cachedRemoteEntries = emptyList()
        }

        fun currentEntries(): List<CatalogEntry> = mergeCatalog(curatedEntries, cachedRemoteEntries)

        fun search(
            entries: List<CatalogEntry>,
            query: String,
            domainFilter: PresetDomain? = null
        ): List<CatalogEntry> {
            val trimmed = query.trim().lowercase()
            return entries.filter { entry ->
                val domainMatches = domainFilter == null || entry.preset.domain == domainFilter
                if (!domainMatches) return@filter false
                if (trimmed.isEmpty()) return@filter true

                val haystack = buildString {
                    append(entry.preset.name).append(' ')
                    append(entry.author).append(' ')
                    append(entry.description).append(' ')
                    append(entry.preset.domain.name).append(' ')
                    entry.preset.displayGenre?.let { append(it.name).append(' ') }
                    entry.preset.audioGenre?.let { append(it.name).append(' ') }
                    entry.tags.forEach { append(it).append(' ') }
                }.lowercase()

                trimmed.split(Regex("\\s+")).all { token -> haystack.contains(token) }
            }
        }

        /**
         * Attempts a 4-second background fetch from [url] using HttpURLConnection.
         * Gracefully falls back to [curatedEntries] if offline, blocked, or missing INTERNET permission.
         */
        suspend fun fetchCatalog(
            url: String = remoteCatalogUrl,
            forceRefresh: Boolean = false
        ): CatalogFetchResult = withContext(Dispatchers.IO) {
            if (!forceRefresh && cachedRemoteEntries.isNotEmpty()) {
                return@withContext CatalogFetchResult(
                    entries = mergeCatalog(curatedEntries, cachedRemoteEntries),
                    fetchedFromRemote = true,
                    statusMessage = "Community catalog synced (${cachedRemoteEntries.size} remote + ${curatedEntries.size} curated)"
                )
            }

            val cleanUrl = url.trim()
            if (cleanUrl.isEmpty()) {
                return@withContext CatalogFetchResult(
                    entries = curatedEntries,
                    fetchedFromRemote = false,
                    statusMessage = "Offline curated catalog (${curatedEntries.size} presets)"
                )
            }

            val remoteResult = runCatching {
                val connection = (URL(cleanUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = REMOTE_TIMEOUT_MS
                    readTimeout = REMOTE_TIMEOUT_MS
                    instanceFollowRedirects = true
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("User-Agent", "SynkaStudio/1.0")
                }

                try {
                    val code = connection.responseCode
                    if (code !in 200..299) {
                        throw IllegalStateException("HTTP $code")
                    }
                    val body = connection.inputStream.use { input ->
                        val out = ByteArrayOutputStream()
                        val buf = ByteArray(4096)
                        var total = 0
                        while (true) {
                            val n = input.read(buf)
                            if (n == -1) break
                            total += n
                            if (total > MAX_REMOTE_CATALOG_BYTES) {
                                throw IllegalStateException("Catalog payload exceeds 256 KB cap")
                            }
                            out.write(buf, 0, n)
                        }
                        String(out.toByteArray(), StandardCharsets.UTF_8)
                    }
                    parseRemoteCatalogJson(body)
                } finally {
                    connection.disconnect()
                }
            }

            val remoteEntries = remoteResult.getOrNull().orEmpty()
            if (remoteEntries.isNotEmpty()) {
                cachedRemoteEntries = remoteEntries
                CatalogFetchResult(
                    entries = mergeCatalog(curatedEntries, remoteEntries),
                    fetchedFromRemote = true,
                    statusMessage = "Synced ${remoteEntries.size} community presets + ${curatedEntries.size} curated"
                )
            } else {
                CatalogFetchResult(
                    entries = mergeCatalog(curatedEntries, cachedRemoteEntries),
                    fetchedFromRemote = cachedRemoteEntries.isNotEmpty(),
                    statusMessage = "Offline mode • Showing ${curatedEntries.size} curated community presets"
                )
            }
        }

        private fun parseRemoteCatalogJson(rawJson: String): List<CatalogEntry> {
            val trimmed = rawJson.trim()
            val array = when {
                trimmed.startsWith("[") -> JSONArray(trimmed)
                trimmed.startsWith("{") -> {
                    val root = JSONObject(trimmed)
                    root.optJSONArray("presets")
                        ?: root.optJSONArray("items")
                        ?: root.optJSONArray("catalog")
                        ?: JSONArray()
                }
                else -> return emptyList()
            }

            val parsed = mutableListOf<CatalogEntry>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val code = obj.optString("code", "").trim()
                val preset = if (code.isNotEmpty()) {
                    decodePreset(code).getOrNull()
                } else {
                    val nested = obj.optJSONObject("preset") ?: obj
                    runCatching { SynkaPreset.fromJson(nested) }.getOrNull()
                } ?: continue

                val author = obj.optString("author", "Community").ifBlank { "Community" }
                val description = obj.optString("description", "Community shared preset.")
                    .ifBlank { "Community shared preset." }
                val tagsArr = obj.optJSONArray("tags")
                val tags = if (tagsArr != null) {
                    List(tagsArr.length()) { idx -> tagsArr.optString(idx) }.filter { it.isNotBlank() }
                } else {
                    emptyList()
                }

                parsed += CatalogEntry(
                    preset = preset.copy(
                        id = if (preset.id.isBlank()) "remote_$i" else preset.id,
                        isUserCreated = false
                    ),
                    author = author,
                    description = description,
                    tags = tags,
                    isRemote = true
                )
            }
            return parsed
        }

        private fun mergeCatalog(
            curated: List<CatalogEntry>,
            remote: List<CatalogEntry>
        ): List<CatalogEntry> {
            if (remote.isEmpty()) return curated
            val seenIds = LinkedHashSet<String>()
            val merged = ArrayList<CatalogEntry>(remote.size + curated.size)
            (remote + curated).forEach { entry ->
                if (seenIds.add(entry.preset.id)) {
                    merged += entry
                }
            }
            return merged
        }

        private fun bandsWith(overrides: Map<ColorBand, BandGrade>): List<BandGrade> {
            return ColorBand.entries.map { band -> (overrides[band] ?: BandGrade()).clamped() }
        }

        private fun eqGains(gainsByIndex: Map<Int, Float>, preAmpDb: Float = 0f): SvaraParams {
            val bands = SvaraParams.DEFAULT_FREQUENCIES_HZ.mapIndexed { idx, freq ->
                EqBand(
                    freqHz = freq,
                    q = 1.4f,
                    gainDb = (gainsByIndex[idx] ?: 0f).coerceIn(-18f, 18f)
                )
            }
            return SvaraParams(preAmpDb = preAmpDb, bands = bands).normalized()
        }

        val curatedEntries: List<CatalogEntry> = listOf(
            CatalogEntry(
                preset = SynkaPreset(
                    id = "discover_kodak_2383",
                    name = "Kodak 2383 Print",
                    domain = PresetDomain.DISPLAY,
                    displayGenre = DisplayGenre.CINEMA_GRADE,
                    arka = ArkaParams(
                        bands = bandsWith(
                            mapOf(
                                ColorBand.RED to BandGrade(sat = 12f, hue = -3f, lum = -4f),
                                ColorBand.ORANGE to BandGrade(sat = 10f, hue = 2f, lum = 5f),
                                ColorBand.YELLOW to BandGrade(sat = -6f, hue = -4f, lum = 4f),
                                ColorBand.CYAN to BandGrade(sat = 16f, hue = -5f, lum = -6f),
                                ColorBand.BLUE to BandGrade(sat = 12f, hue = -4f, lum = -8f)
                            )
                        ),
                        blacks = -16f,
                        shadows = -8f,
                        mids = 8f,
                        highlights = 6f,
                        whites = 10f,
                        shadowTintX = -0.28f,
                        shadowTintY = 0.22f,
                        highlightTintX = 0.24f,
                        highlightTintY = -0.10f,
                        warmth = 8f,
                        tint = -2f
                    )
                ),
                author = "Synka Lab",
                description = "Print-film S-curve with cool cyan shadows and natural golden skin roll-off.",
                tags = listOf("cinema", "film", "2383", "teal", "contrast")
            ),
            CatalogEntry(
                preset = SynkaPreset(
                    id = "discover_amoled_paper",
                    name = "AMOLED Midnight Reader",
                    domain = PresetDomain.DISPLAY,
                    displayGenre = DisplayGenre.WARM,
                    arka = ArkaParams(
                        bands = bandsWith(
                            mapOf(
                                ColorBand.BLUE to BandGrade(sat = -24f, hue = 0f, lum = -14f),
                                ColorBand.CYAN to BandGrade(sat = -18f, hue = 0f, lum = -8f)
                            )
                        ),
                        blacks = -10f,
                        highlights = -12f,
                        warmth = 46f,
                        tint = 6f,
                        subzeroDimmer = 28f,
                        paper = 42f,
                        trimB = -16f
                    )
                ),
                author = "Synka Lab",
                description = "Subzero OLED dimming paired with warm paper grain for zero-glare late reading.",
                tags = listOf("night", "oled", "reading", "eye comfort", "warm")
            ),
            CatalogEntry(
                preset = SynkaPreset(
                    id = "discover_harman_iem",
                    name = "Harman 2019 IEM Target",
                    domain = PresetDomain.AUDIO,
                    audioGenre = AudioGenre.FLAT,
                    svara = eqGains(
                        gainsByIndex = mapOf(
                            0 to 4.0f,
                            1 to 3.5f,
                            2 to 1.0f,
                            3 to -1.5f,
                            4 to -0.5f,
                            5 to 0.5f,
                            6 to 2.5f,
                            7 to 3.0f,
                            8 to 1.0f,
                            9 to 1.5f
                        ),
                        preAmpDb = -3.5f
                    ).copy(
                        su = 3.0f,
                        vo = 2.0f,
                        tr = 1.5f,
                        antiCrackle = 86f
                    ).normalized()
                ),
                author = "Acoustic Ref",
                description = "Sub-bass shelf with clean 250 Hz tuck and crisp upper-mid pinna gain for wired IEMs.",
                tags = listOf("iem", "harman", "reference", "flac", "balanced")
            ),
            CatalogEntry(
                preset = SynkaPreset(
                    id = "discover_vshape_energy",
                    name = "Studio V-Shape Punch",
                    domain = PresetDomain.AUDIO,
                    audioGenre = AudioGenre.BASS,
                    svara = eqGains(
                        gainsByIndex = mapOf(
                            0 to 6.5f,
                            1 to 5.5f,
                            2 to 2.5f,
                            4 to -2.0f,
                            5 to -1.5f,
                            7 to 2.5f,
                            8 to 4.0f,
                            9 to 4.5f
                        ),
                        preAmpDb = -4.5f
                    ).copy(
                        su = 6.0f,
                        mi = 2.5f,
                        vo = -1.5f,
                        tr = 3.0f,
                        ai = 4.0f,
                        bassDepthX = 0.20f,
                        bassDepthY = 0.45f,
                        airWidthX = 0.25f,
                        airWidthY = 0.30f,
                        antiCrackle = 90f
                    ).normalized()
                ),
                author = "LowEnd Craft",
                description = "Tactile sub-bass weight and sparkly top-end extension with protective headroom.",
                tags = listOf("bass", "v-shape", "edm", "punch", "energetic")
            ),
            CatalogEntry(
                preset = SynkaPreset(
                    id = "discover_vocal_studio",
                    name = "Intimate Acoustic & Vocal",
                    domain = PresetDomain.AUDIO,
                    audioGenre = AudioGenre.VOCAL,
                    svara = eqGains(
                        gainsByIndex = mapOf(
                            0 to -2.0f,
                            1 to -1.0f,
                            3 to 1.0f,
                            4 to 2.0f,
                            5 to 3.5f,
                            6 to 4.0f,
                            7 to 2.0f,
                            9 to 2.5f
                        ),
                        preAmpDb = -2.5f
                    ).copy(
                        vo = 4.5f,
                        ai = 3.0f,
                        airWidthX = 0.35f,
                        airWidthY = 0.20f,
                        nightDialogue = 20f,
                        antiCrackle = 82f
                    ).normalized()
                ),
                author = "Svara Guild",
                description = "Forward midrange presence and airy upper harmonics for vocals, podcasts, and strings.",
                tags = listOf("vocal", "acoustic", "podcast", "clarity", "midrange")
            ),
            CatalogEntry(
                preset = SynkaPreset(
                    id = "discover_noir_monochrome",
                    name = "Tri-X 400 Street Noir",
                    domain = PresetDomain.DISPLAY,
                    displayGenre = DisplayGenre.BW,
                    arka = ArkaParams(
                        bands = bandsWith(
                            ColorBand.entries.associateWith { band ->
                                val lum = when (band) {
                                    ColorBand.RED, ColorBand.ORANGE -> 14f
                                    ColorBand.YELLOW -> 10f
                                    ColorBand.BLUE, ColorBand.CYAN -> -18f
                                    else -> 0f
                                }
                                BandGrade(sat = -100f, lum = lum)
                            }
                        ),
                        blacks = -28f,
                        shadows = -14f,
                        mids = 16f,
                        highlights = 14f,
                        whites = 18f,
                        paper = 12f
                    )
                ),
                author = "Monochrome Club",
                description = "Deep blue-sky darkening and punchy silver highlights modeled on classic street film.",
                tags = listOf("bw", "noir", "monochrome", "street", "film")
            ),
            CatalogEntry(
                preset = SynkaPreset(
                    id = "discover_cyber_neon",
                    name = "Neon Tokyo Isolation",
                    domain = PresetDomain.DISPLAY,
                    displayGenre = DisplayGenre.RED_ISO,
                    arka = ArkaParams(
                        bands = bandsWith(
                            mapOf(
                                ColorBand.RED to BandGrade(sat = 42f, hue = -4f, lum = 10f),
                                ColorBand.MAGENTA to BandGrade(sat = 38f, hue = 6f, lum = 8f),
                                ColorBand.CYAN to BandGrade(sat = 30f, hue = -4f, lum = 4f),
                                ColorBand.GREEN to BandGrade(sat = -75f, hue = 0f, lum = -8f),
                                ColorBand.YELLOW to BandGrade(sat = -60f, hue = 0f, lum = -4f)
                            )
                        ),
                        blacks = -18f,
                        shadows = -10f,
                        mids = 10f,
                        highlights = 12f,
                        shadowTintX = -0.30f,
                        shadowTintY = 0.25f
                    )
                ),
                author = "Arka Collective",
                description = "Selective crimson, magenta, and cyan pop against muted urban neutrals.",
                tags = listOf("neon", "cyberpunk", "red", "cyan", "vivid")
            ),
            CatalogEntry(
                preset = SynkaPreset(
                    id = "discover_paired_imax_night",
                    name = "Midnight IMAX Suite",
                    domain = PresetDomain.BOTH,
                    displayGenre = DisplayGenre.CINEMA_GRADE,
                    audioGenre = AudioGenre.LATE_NIGHT,
                    arka = ArkaParams(
                        bands = bandsWith(
                            mapOf(
                                ColorBand.CYAN to BandGrade(sat = 14f, hue = -4f, lum = -4f),
                                ColorBand.ORANGE to BandGrade(sat = 12f, hue = 2f, lum = 4f),
                                ColorBand.BLUE to BandGrade(sat = 8f, hue = -2f, lum = -8f)
                            )
                        ),
                        blacks = -14f,
                        shadows = -6f,
                        mids = 6f,
                        highlights = 4f,
                        warmth = 12f,
                        subzeroDimmer = 12f
                    ),
                    svara = eqGains(
                        gainsByIndex = mapOf(
                            0 to 2.5f,
                            1 to 1.5f,
                            5 to 3.0f,
                            6 to 3.5f,
                            8 to -2.0f,
                            9 to -3.0f
                        ),
                        preAmpDb = -2.0f
                    ).copy(
                        su = 1.5f,
                        vo = 4.0f,
                        ai = -2.0f,
                        nightDialogue = 70f,
                        antiCrackle = 92f
                    ).normalized()
                ),
                author = "Synka Lab",
                description = "Balanced cinema color paired with compressed late-night dialogue clarity.",
                tags = listOf("cinema", "movies", "night", "dialogue", "both")
            )
        )
    }
}
