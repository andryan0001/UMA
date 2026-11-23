package tsuki.site.id

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser
import tsuki.exception.ParseException
import tsuki.model.ContentRating
import tsuki.model.ContentType
import tsuki.model.Manga
import tsuki.model.MangaChapter
import tsuki.model.MangaListFilter
import tsuki.model.MangaListFilterCapabilities
import tsuki.model.MangaListFilterOptions
import tsuki.model.MangaPage
import tsuki.model.MangaParserSource
import tsuki.model.MangaState
import tsuki.model.MangaTag
import tsuki.model.RATING_UNKNOWN
import tsuki.model.SortOrder
import tsuki.util.attrAsRelativeUrl
import tsuki.util.extractChapterNumber
import tsuki.util.generateUid
import tsuki.util.parseHtml
import tsuki.util.parseJson
import tsuki.util.src
import tsuki.util.toAbsoluteUrl
import tsuki.util.urlEncoded
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap

@MangaSourceParser("CGBUM", "CGBUM", "id")
class CGBUM(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.CGBUM, pageSize = 24) {

    override val configKeyDomain = ConfigKey.Domain("cgbum.com")

    override val availableSortOrders: Set<SortOrder> = setOf(
        SortOrder.UPDATED,      // latest
        SortOrder.ADDED,        // newest
        SortOrder.ADDED_ASC,    // oldest
        SortOrder.POPULARITY,   // views
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isMultipleTagsSupported = true,
        isTagsExclusionSupported = false,
        isSearchWithFiltersSupported = false,
    )

    private val tagsMutex = Mutex()
    @Volatile
    private var cachedTags: Set<MangaTag>? = null

    private val accessMutex = Mutex()
    @Volatile
    private var readerSid: String? = null
    private val grantCache = ConcurrentHashMap<String, CachedGrant>()

    private data class CachedGrant(
        val token: String,
        val expiresAtSec: Long,
    )

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        val tags = getAvailableTags()
        return MangaListFilterOptions(
            availableTags = tags,
            availableStates = EnumSet.of(
                MangaState.ONGOING,
                MangaState.FINISHED,
            ),
            availableContentTypes = EnumSet.of(
                ContentType.MANGA,
                ContentType.MANHWA,
                ContentType.MANHUA,
                ContentType.HENTAI,
            ),
        )
    }

    private suspend fun getAvailableTags(): Set<MangaTag> {
        cachedTags?.let { if (it.isNotEmpty()) return it }
        return tagsMutex.withLock {
            cachedTags?.let { if (it.isNotEmpty()) return@withLock it }
            val doc = webClient.httpGet("https://$domain/daftar-komik").parseHtml()
            val parsed = parseGenresFromDocument(doc)
            if (parsed.isNotEmpty()) {
                cachedTags = parsed
            }
            parsed
        }
    }

    private fun parseGenresFromDocument(doc: Document): Set<MangaTag> {
        val checkboxes = doc.select("input[type=checkbox][name=\"genres[]\"]")
        if (checkboxes.isEmpty()) return emptySet()
        return checkboxes.mapNotNullTo(LinkedHashSet()) { cb ->
            val value = cb.attr("value").trim()
            if (value.isEmpty()) return@mapNotNullTo null
            val label = cb.parent()?.text()?.trim()?.takeIf { it.isNotEmpty() } ?: value
            MangaTag(key = value, title = label, source = source)
        }
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val query = filter.query?.trim()
        val url = if (!query.isNullOrEmpty()) {
            buildSearchUrl(page, query)
        } else {
            buildBrowseUrl(page, order, filter)
        }
        val doc = webClient.httpGet(url).parseHtml()

        val cards = doc.select("article.comic-card")
        return if (cards.isEmpty()) emptyList() else cards.mapNotNull { parseMangaCard(it) }
    }

    private fun buildSearchUrl(page: Int, query: String): String {
        return buildString {
            append("https://$domain/cari?q=")
            append(query.urlEncoded().replace("%20", "+"))
            if (page > 1) {
                append("&page=")
                append(page)
            }
        }
    }

    private fun buildBrowseUrl(page: Int, order: SortOrder, filter: MangaListFilter): String {
        return buildString {
            append("https://$domain/daftar-komik?")
            append("type=")
            filter.types.firstOrNull()?.let { type ->
                append(
                    when (type) {
                        ContentType.MANGA -> "manga"
                        ContentType.MANHWA -> "manhwa"
                        ContentType.MANHUA -> "manhua"
                        ContentType.HENTAI -> "pornhwa"
                        else -> ""
                    }
                )
            }
            append("&status=")
            filter.states.firstOrNull()?.let { state ->
                append(
                    when (state) {
                        MangaState.ONGOING -> "ongoing"
                        MangaState.FINISHED -> "tamat"
                        else -> ""
                    }
                )
            }
            append("&sort=")
            append(
                when (order) {
                    SortOrder.UPDATED -> "latest"
                    SortOrder.ADDED -> "newest"
                    SortOrder.ADDED_ASC -> "oldest"
                    SortOrder.POPULARITY -> "views"
                    else -> "latest"
                }
            )
            filter.tags.take(3).forEach { tag ->
                append("&genres%5B%5D=")
                append(tag.key.urlEncoded())
            }
            if (page > 1) {
                append("&page=")
                append(page)
            }
        }
    }

    private fun parseMangaCard(card: Element): Manga? {
        val coverLink = card.selectFirst("a.comic-card-cover") ?: return null
        val href = coverLink.attrAsRelativeUrl("href")
        val titleEl = card.selectFirst(".comic-card-title a")
        val title = titleEl?.text()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val coverUrl = card.selectFirst("img")?.src()?.takeIf { it.isNotBlank() }

        val typeBadge = card.selectFirst(".badge-type-text")?.text()
            ?: card.selectFirst(".badge-type")?.text()
        val typeTag = when (typeBadge?.trim()?.lowercase()) {
            "manga" -> MangaTag("type:manga", "Manga", source)
            "manhwa" -> MangaTag("type:manhwa", "Manhwa", source)
            "manhua" -> MangaTag("type:manhua", "Manhua", source)
            else -> null
        }

        val isAdult = card.attr("data-adult") == "1" || card.selectFirst(".badge-pornhwa") != null

        val statusText = card.selectFirst(".badge-status")?.text()
        val state = when (statusText?.trim()?.lowercase()) {
            "ongoing" -> MangaState.ONGOING
            "tamat" -> MangaState.FINISHED
            else -> null
        }

        val tags = LinkedHashSet<MangaTag>()
        if (typeTag != null) tags.add(typeTag)
        if (isAdult) tags.add(MangaTag("adult", "Adult", source))

        return Manga(
            id = generateUid(href),
            url = href,
            publicUrl = href.toAbsoluteUrl(domain),
            title = title,
            altTitles = emptySet(),
            coverUrl = coverUrl,
            authors = emptySet(),
            tags = tags,
            rating = RATING_UNKNOWN,
            state = state,
            contentRating = if (isAdult) ContentRating.ADULT else ContentRating.SAFE,
            source = source,
        )
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val fullUrl = manga.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(fullUrl).parseHtml()

        val rawTitle = doc.selectFirst("h1")?.text()?.trim()
        val title = rawTitle?.removeSuffix(" Bahasa Indonesia")?.trim()?.takeIf { it.isNotEmpty() }
            ?: manga.title

        val altTitles = doc.selectFirst("p.comic-alt-title")?.text()
            ?.split("/")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: emptySet()

        val cover = doc.selectFirst(".comic-cover img")?.src()?.takeIf { it.isNotBlank() } ?: manga.coverUrl
        val desc = doc.selectFirst(".comic-synopsis .synopsis-content")?.text()?.trim() ?: ""

        val genreElements = doc.select(".comic-genres .genre-pill")
        val tags = genreElements.mapNotNull { a ->
            val genreName = a.text().trim()
            if (genreName.isNotEmpty()) MangaTag(key = genreName, title = genreName, source = source)
            else null
        }.toSet()

        val statusText = doc.selectFirst(".comic-cover .badge-status, .badge-status")?.text()
        val state = when (statusText?.trim()?.lowercase()) {
            "ongoing" -> MangaState.ONGOING
            "tamat" -> MangaState.FINISHED
            else -> null
        }

        val isAdult = doc.selectFirst(".comic-cover")?.attr("data-adult") == "1" ||
            doc.selectFirst(".comic-cover .badge-pornhwa, .badge-pornhwa") != null ||
            tags.any { it.title.equals("adult", ignoreCase = true) }
        val contentRating = if (isAdult) ContentRating.ADULT else ContentRating.SAFE

        val authorRow = doc.select(".meta-row").firstOrNull {
            it.selectFirst(".meta-label")?.text()?.trim().equals("Author", ignoreCase = true)
        }
        val author = authorRow?.selectFirst(".meta-value")?.text()?.trim()
            ?.takeIf { it.isNotBlank() && it != "-" && !it.equals("N/A", ignoreCase = true) }
        val authors = if (author != null) setOf(author) else emptySet()

        val chapterLinks = doc.select(".chapter-grid a.ch-grid-item")
        val chapters = chapterLinks.map { a ->
            val href = a.attrAsRelativeUrl("href")
            val label = a.text().trim().ifEmpty { a.attr("title").trim() }
            val number = a.attr("data-chapter-sort").toFloatOrNull()
                ?: a.attr("data-chapter").replace("-", ".").toFloatOrNull()
                ?: label.extractChapterNumber()
            MangaChapter(
                id = generateUid(href),
                url = href,
                title = label,
                number = number,
                volume = 0,
                uploadDate = 0,
                source = source,
                scanlator = null,
                branch = null,
            )
        }.sortedBy { it.number }

        return manga.copy(
            title = title,
            altTitles = altTitles,
            publicUrl = fullUrl,
            coverUrl = cover,
            description = desc,
            tags = tags,
            state = state,
            authors = authors,
            contentRating = contentRating,
            chapters = chapters,
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val fullUrl = chapter.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(fullUrl).parseHtml()

        val readerAccess = extractReaderAccessConfig(doc)
        if (readerAccess != null) {
            val imageUrls = fetchReaderAccessImages(readerAccess, fullUrl)
            if (imageUrls.isNotEmpty()) {
                return imageUrls.map { url ->
                    MangaPage(id = generateUid(url), url = url, preview = null, source = source)
                }
            }
        }

        val containers = doc.select(".page-container")
        if (containers.isEmpty()) {
            throw ParseException("No image containers found", fullUrl)
        }

        val pages = containers.mapNotNull { container ->
            val url = container.attr("data-url").takeIf { it.isNotBlank() }
                ?: container.selectFirst("img")?.src()?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            MangaPage(id = generateUid(url), url = url, preview = null, source = source)
        }
        if (pages.isEmpty()) {
            throw ParseException("No image URLs found", fullUrl)
        }
        return pages
    }

    private fun extractReaderAccessConfig(doc: Document): JSONObject? {
        for (script in doc.select("script")) {
            val data = script.data()
            val markerIdx = data.indexOf("window.__READER_ACCESS")
            if (markerIdx < 0) continue
            val startBrace = data.indexOf('{', markerIdx)
            if (startBrace < 0) continue
            val jsonStr = extractBalancedJsonObject(data, startBrace) ?: continue
            return runCatching { JSONObject(jsonStr) }.getOrNull()
        }
        return null
    }

    private fun extractBalancedJsonObject(text: String, startIdx: Int): String? {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in startIdx until text.length) {
            val ch = text[i]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (ch == '\\') {
                    escaped = true
                } else if (ch == '"') {
                    inString = false
                }
            } else {
                when (ch) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            return text.substring(startIdx, i + 1)
                        }
                    }
                }
            }
        }
        return null
    }

    private suspend fun fetchReaderAccessImages(cfg: JSONObject, refererUrl: String): List<String> {
        val endpoint = cfg.optString("endpoint").ifBlank { "/reader-access.php" }
        val pageToken = cfg.optString("page")
        if (pageToken.isBlank()) {
            throw ParseException("Missing reader access page token", refererUrl)
        }
        val ttl = cfg.optInt("ttl", 3600)
        val grantKey = if (ttl == 15) "short" else "normal"
        val endpointUrl = endpoint.toAbsoluteUrl(domain).toHttpUrl()

        return accessMutex.withLock {
            val sid = getOrCreateSid()
            val nowSec = System.currentTimeMillis() / 1000L
            val cachedGrant = grantCache[grantKey]?.takeIf { it.expiresAtSec > nowSec + 30L }?.token ?: ""

            val headers = getRequestHeaders().newBuilder()
                .set("X-Reader-Access", "1")
                .set("Origin", "https://$domain")
                .set("Referer", refererUrl)
                .build()

            val initialPayload = JSONObject().apply {
                put("sid", sid)
                put("page", pageToken)
                put("token", cachedGrant)
            }
            var json = webClient.httpPost(endpointUrl, initialPayload, headers).parseJson()

            val challenge = json.optJSONObject("challenge")
            if (challenge != null) {
                val seed = challenge.optString("seed")
                val difficulty = challenge.optInt("difficulty", 12)
                val ticket = challenge.optString("ticket")
                if (seed.isBlank() || ticket.isBlank()) {
                    throw ParseException("Invalid reader access challenge", refererUrl)
                }
                val nonce = solveChallenge(seed, difficulty, refererUrl)
                val challengePayload = JSONObject().apply {
                    put("sid", sid)
                    put("page", pageToken)
                    put("token", "")
                    put("ticket", ticket)
                    put("nonce", nonce)
                }
                json = webClient.httpPost(endpointUrl, challengePayload, headers).parseJson()
                if (json.has("challenge")) {
                    throw ParseException("Reader access challenge failed", refererUrl)
                }
            }

            val error = json.optString("error")
            if (error.isNotBlank()) {
                grantCache.remove(grantKey)
                throw ParseException("Reader access error: $error", refererUrl)
            }

            json.optJSONObject("grant")?.let { grant ->
                val token = grant.optString("token")
                val expiresAt = grant.optLong("expiresAt", nowSec + ttl)
                if (token.isNotBlank()) {
                    grantCache[grantKey] = CachedGrant(token, expiresAt)
                }
            }

            val images = json.optJSONArray("images") ?: JSONArray()
            val result = ArrayList<String>(images.length())
            for (i in 0 until images.length()) {
                val url = images.optString(i).trim()
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    result.add(url)
                }
            }
            result
        }
    }

    private fun getOrCreateSid(): String {
        readerSid?.let { return it }
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        val sid = bytes.joinToString("") { "%02x".format(it) }
        readerSid = sid
        return sid
    }

    private fun solveChallenge(seed: String, difficulty: Int, refererUrl: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val fullBytes = difficulty / 8
        val remBits = difficulty % 8
        val mask = if (remBits > 0) (0xFF shl (8 - remBits)) and 0xFF else 0
        for (n in 0 until 20_000_000) {
            digest.reset()
            val hash = digest.digest((seed + n).toByteArray(Charsets.UTF_8))
            var ok = true
            for (i in 0 until fullBytes) {
                if (hash[i].toInt() != 0) {
                    ok = false
                    break
                }
            }
            if (ok && remBits > 0) {
                if ((hash[fullBytes].toInt() and mask) != 0) {
                    ok = false
                }
            }
            if (ok) return n.toString()
        }
        throw ParseException("Reader access challenge timed out", refererUrl)
    }

    override suspend fun getRelatedManga(seed: Manga): List<Manga> {
        val fullUrl = seed.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(fullUrl).parseHtml()

        val section = doc.selectFirst("section:has(.section-header h2:contains(Baca Juga))")
            ?: return emptyList()

        return section.select("article.comic-card").mapNotNull { card ->
            parseMangaCard(card)
        }
    }
}
