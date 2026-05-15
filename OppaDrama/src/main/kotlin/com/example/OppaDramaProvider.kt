package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class OppaDramaProvider : MainAPI() {

    // ════════════════════════════════════════════════════════════════
    // KONFIGURASI DASAR
    // ════════════════════════════════════════════════════════════════

    override var mainUrl = "http://45.11.57.199"
    override var name = "OppaDrama"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama,
        TvType.Anime
    )

    // Halaman utama — kategori yang tampil di home CloudStream
    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "Update Terbaru",
        "$mainUrl/drama/ongoing/page/" to "Drama Ongoing",
        "$mainUrl/drama/completed/page/" to "Drama Completed",
        "$mainUrl/movies/page/" to "Film",
        "$mainUrl/animasi/page/" to "Animasi",
    )

    // ════════════════════════════════════════════════════════════════
    // HELPER: Parse card element menjadi SearchResponse
    // ════════════════════════════════════════════════════════════════

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = this.selectFirst("a") ?: return null
        val title = anchor.attr("title").ifBlank {
            this.selectFirst(".tt")?.text()?.trim() ?: anchor.attr("oldtitle") ?: return null
        }
        val href = fixUrl(anchor.attr("href"))
        val posterUrl = fixUrlNull(
            this.selectFirst("img")?.attr("data-src")
                ?: this.selectFirst("img")?.attr("src")
        )
        val epText = this.selectFirst(".epx, .ep")?.text()?.trim()
        val typeText = this.selectFirst(".typez, .type")?.text()?.trim()?.lowercase()

        val tvType = when {
            typeText?.contains("movie") == true -> TvType.Movie
            typeText?.contains("tv") == true -> TvType.TvSeries
            typeText?.contains("drama") == true -> TvType.AsianDrama
            epText != null -> TvType.TvSeries
            else -> TvType.AsianDrama
        }

        return newAnimeSearchResponse(title, href, tvType) {
            this.posterUrl = posterUrl
            addSub(epText?.filter { it.isDigit() }?.toIntOrNull())
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 1. HALAMAN UTAMA (HOME PAGE)
    // ════════════════════════════════════════════════════════════════

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = request.data + page
        val document = app.get(url).document

        val items = document.select("article.bs .bsx, .listupd .bs .bsx").mapNotNull { element ->
            element.toSearchResult()
        }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = items,
                isHorizontalImages = false
            ),
            hasNext = items.isNotEmpty()
        )
    }

    // ════════════════════════════════════════════════════════════════
    // 2. PENCARIAN (SEARCH)
    // ════════════════════════════════════════════════════════════════

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document

        return document.select("article.bs .bsx, .listupd .bs .bsx").mapNotNull { element ->
            element.toSearchResult()
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 3. DETAIL KONTEN (LOAD)
    // ════════════════════════════════════════════════════════════════

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst(".entry-title")?.text()?.trim() ?: ""
        val poster = fixUrlNull(
            document.selectFirst(".thumb img")?.attr("data-src")
                ?: document.selectFirst(".thumb img")?.attr("src")
        )
        val synopsis = document.selectFirst(".entry-content")?.text()?.trim()
        val genres = document.select(".genxed a").map { it.text().trim() }

        // Parse info dari .spe span
        val infoSpans = document.select(".spe span")
        var year: Int? = null
        var status: ShowStatus? = null
        var type: String? = null
        val actors = mutableListOf<String>()
        var network: String? = null
        var duration: String? = null

        infoSpans.forEach { span ->
            val text = span.text().trim()
            when {
                text.startsWith("Status:") -> {
                    val statusText = text.substringAfter("Status:").trim().lowercase()
                    status = when {
                        statusText.contains("ongoing") -> ShowStatus.Ongoing
                        statusText.contains("completed") -> ShowStatus.Completed
                        else -> null
                    }
                }
                text.startsWith("Tipe:") || text.startsWith("Type:") -> {
                    type = text.substringAfter(":").trim()
                }
                text.startsWith("Dirilis:") || text.startsWith("Released:") -> {
                    val dateStr = text.substringAfter(":").trim()
                    year = Regex("(\\d{4})").find(dateStr)?.value?.toIntOrNull()
                }
                text.startsWith("Artis:") || text.startsWith("Actors:") -> {
                    actors.addAll(
                        text.substringAfter(":").trim().split(",").map { it.trim() }
                    )
                }
                text.startsWith("Network:") -> {
                    network = text.substringAfter(":").trim()
                }
                text.startsWith("Durasi:") || text.startsWith("Duration:") -> {
                    duration = text.substringAfter(":").trim()
                }
            }
        }

        // Trailer dari YouTube embed
        val trailerUrl = document.selectFirst("iframe[src*=youtube], iframe[src*=youtu.be]")
            ?.attr("src")

        // Cek apakah ada episode list
        val episodes = document.select(".eplister ul li").mapNotNull { li ->
            val a = li.selectFirst("a") ?: return@mapNotNull null
            val epHref = fixUrl(a.attr("href"))
            val epNum = li.selectFirst(".epl-num")?.text()?.trim()
            val epTitle = li.selectFirst(".epl-title")?.text()?.trim()
            val epDate = li.selectFirst(".epl-date")?.text()?.trim()

            newEpisode(epHref) {
                this.name = epTitle ?: "Episode $epNum"
                this.episode = epNum?.filter { it.isDigit() }?.toIntOrNull()
            }
        }

        val tvType = when {
            type?.lowercase()?.contains("movie") == true -> TvType.Movie
            episodes.isNotEmpty() -> TvType.TvSeries
            else -> TvType.Movie
        }

        return if (tvType == TvType.Movie || episodes.isEmpty()) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = synopsis
                this.year = year
                this.tags = genres
                addActors(actors)
                if (trailerUrl != null) addTrailer(trailerUrl)
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.AsianDrama, episodes) {
                this.posterUrl = poster
                this.plot = synopsis
                this.year = year
                this.tags = genres
                this.showStatus = status
                addActors(actors)
                if (trailerUrl != null) addTrailer(trailerUrl)
            }
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 4. AMBIL LINK STREAMING & DOWNLOAD (LOAD LINKS)
    // ════════════════════════════════════════════════════════════════

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        // ── Method 1: Iframe embed player ──
        val iframe = document.selectFirst("iframe")?.attr("src")
        if (!iframe.isNullOrBlank()) {
            val iframeUrl = fixUrl(iframe)
            loadExtractor(iframeUrl, mainUrl, subtitleCallback, callback)
        }

        // ── Method 2: Mirror select options (AJAX-based server switching) ──
        val mirrorSelect = document.selectFirst("select.mirror, select#mirror")
        mirrorSelect?.select("option")?.forEach { option ->
            val dataContent = option.attr("value")
            if (dataContent.isNotBlank() && dataContent != "0") {
                try {
                    // Some sites use base64 encoded iframe in value
                    val decoded = base64Decode(dataContent)
                    val iframeSrc = Regex("src=\"([^\"]+)\"").find(decoded)?.groupValues?.get(1)
                    if (!iframeSrc.isNullOrBlank()) {
                        loadExtractor(fixUrl(iframeSrc), mainUrl, subtitleCallback, callback)
                    }
                } catch (_: Exception) {
                    // Value might be a direct URL
                    if (dataContent.startsWith("http")) {
                        loadExtractor(dataContent, mainUrl, subtitleCallback, callback)
                    }
                }
            }
        }

        // ── Method 3: AJAX-based mirror/server loading ──
        val nonce = document.selectFirst("script:containsData(ajax_url)")?.data()
            ?.let { Regex("nonce[\"']?\\s*[:=]\\s*[\"']([^\"']+)[\"']").find(it)?.groupValues?.get(1) }
        val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"

        // Try to load each server via AJAX
        mirrorSelect?.select("option")?.forEach { option ->
            val action = option.attr("data-action").ifBlank { "action" }
            val postId = option.attr("data-post").ifBlank { null }
            val nume = option.attr("data-nume").ifBlank { null }
            val dataType = option.attr("data-type").ifBlank { null }

            if (postId != null && nume != null) {
                try {
                    val response = app.post(
                        url = ajaxUrl,
                        data = mapOf(
                            "action" to "player_ajax",
                            "post" to postId,
                            "nume" to nume,
                            "type" to (dataType ?: ""),
                        ).let { map ->
                            if (nonce != null) map + ("nonce" to nonce) else map
                        },
                        referer = data,
                        headers = mapOf(
                            "X-Requested-With" to "XMLHttpRequest"
                        )
                    ).text

                    // Parse iframe src from AJAX response
                    val iframeSrc = Regex("src=\"([^\"]+)\"").find(response)?.groupValues?.get(1)
                        ?: Regex("\"embed_url\"\\s*:\\s*\"([^\"]+)\"").find(response)?.groupValues?.get(1)

                    if (!iframeSrc.isNullOrBlank()) {
                        val cleanUrl = iframeSrc.replace("\\", "")
                        loadExtractor(fixUrl(cleanUrl), mainUrl, subtitleCallback, callback)
                    }
                } catch (_: Exception) {
                    // Skip failed AJAX requests
                }
            }
        }

        // ── Method 4: Download links table ──
        document.select("table.soratable tr, .soraddlx .soraurlx a, .mctnx .soraurlx a").forEach { element ->
            val downloadLink = element.selectFirst("a[href]")?.attr("href") ?: element.attr("href")
            if (downloadLink.isNotBlank() && downloadLink.startsWith("http")) {
                val serverName = element.selectFirst("td:first-child, .sorattlx")?.text()?.trim() ?: ""
                val quality = element.selectFirst("td:nth-child(2), .soradlx")?.text()?.trim() ?: ""

                val qualityInt = when {
                    quality.contains("1080") -> Qualities.P1080.value
                    quality.contains("720") -> Qualities.P720.value
                    quality.contains("480") -> Qualities.P480.value
                    quality.contains("360") -> Qualities.P360.value
                    else -> Qualities.Unknown.value
                }

                try {
                    loadExtractor(downloadLink, mainUrl, subtitleCallback, callback)
                } catch (_: Exception) {
                    // Some download links might not be extractable
                    callback.invoke(
                        newExtractorLink(
                            source = "$name - $serverName",
                            name = "$serverName $quality",
                            url = downloadLink,
                        ) {
                            this.referer = mainUrl
                            this.quality = qualityInt
                        }
                    )
                }
            }
        }

        // ── Method 5: Direct download links (Buzzheavier, DataNodes, etc.) ──
        document.select("a[href*=buzzheavier], a[href*=datanodes], a[href*=earnvids], a[href*=filelions]").forEach { a ->
            val href = a.attr("href")
            if (href.isNotBlank()) {
                try {
                    loadExtractor(href, mainUrl, subtitleCallback, callback)
                } catch (_: Exception) {
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = a.text().trim().ifBlank { "Download" },
                            url = href,
                        ) {
                            this.referer = mainUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
            }
        }

        return true
    }
}
