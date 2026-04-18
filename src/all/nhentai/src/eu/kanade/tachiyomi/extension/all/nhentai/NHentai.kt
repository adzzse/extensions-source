package eu.kanade.tachiyomi.extension.all.nhentai

import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.extension.all.nhentai.NHUtils.getArtists
import eu.kanade.tachiyomi.extension.all.nhentai.NHUtils.getGroups
import eu.kanade.tachiyomi.extension.all.nhentai.NHUtils.getTagDescription
import eu.kanade.tachiyomi.extension.all.nhentai.NHUtils.getTags
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.lib.randomua.addRandomUAPreferenceToScreen
import keiyoushi.lib.randomua.getPrefCustomUA
import keiyoushi.lib.randomua.getPrefUAType
import keiyoushi.lib.randomua.setRandomUserAgent
import keiyoushi.utils.getPreferencesLazy
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import uy.kohesive.injekt.injectLazy

open class NHentai(
    override val lang: String,
    private val nhLang: String,
) : HttpSource(),
    ConfigurableSource {

    final override val baseUrl = "https://nhentai.net"

    private val apiUrl = "$baseUrl/api/v2"

    override val id by lazy { if (lang == "all") 7309872737163460316 else super.id }

    override val name = "NHentai"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by getPreferencesLazy()

    override val client: OkHttpClient by lazy {
        network.cloudflareClient.newBuilder()
            .setRandomUserAgent(
                userAgentType = preferences.getPrefUAType(),
                customUA = preferences.getPrefCustomUA(),
                filterInclude = listOf("chrome"),
            )
            .rateLimit(4)
            .build()
    }

    private var displayFullTitle: Boolean = when (preferences.getString(TITLE_PREF, "full")) {
        "full" -> true
        else -> false
    }

    private val shortenTitleRegex = Regex("""(\[[^]]*]|[({][^)}]*[)}])""")
    private fun String.shortenTitle() = this.replace(shortenTitleRegex, "").trim()

    // CDN configuration cached lazily
    private val cdnConfig: CdnResponse by lazy {
        val response = client.newCall(GET("$apiUrl/cdn", apiHeaders())).execute()
        response.parseAs<CdnResponse>()
    }

    private fun imageUrl(path: String): String = "${cdnConfig.imageServers.random()}/$path"

    private fun thumbUrl(path: String): String = "${cdnConfig.thumbServers.random()}/$path"

    // --- Preferences ---

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = TITLE_PREF
            title = TITLE_PREF
            entries = arrayOf("Full Title", "Short Title")
            entryValues = arrayOf("full", "short")
            summary = "%s"
            setDefaultValue("full")

            setOnPreferenceChangeListener { _, newValue ->
                displayFullTitle = when (newValue) {
                    "full" -> true
                    else -> false
                }
                true
            }
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = API_KEY_PREF
            title = "API Key (optional)"
            summary = "Required for favorites. Enter your nhentai API key."
            setDefaultValue("")
        }.also(screen::addPreference)

        addRandomUAPreferenceToScreen(screen)
    }

    private fun apiHeaders() = headers.newBuilder().apply {
        val apiKey = preferences.getString(API_KEY_PREF, "")
        if (!apiKey.isNullOrBlank()) {
            add("Authorization", "Key $apiKey")
        }
        add("Accept", "application/json")
    }.build()

    // --- Latest Updates ---

    override fun latestUpdatesRequest(page: Int): Request = if (nhLang.isBlank()) {
        GET("$apiUrl/galleries?page=$page&per_page=25", apiHeaders())
    } else {
        // Use search endpoint with language filter for specific languages
        val url = "$apiUrl/search".toHttpUrl().newBuilder()
            .addQueryParameter("query", "language:$nhLang")
            .addQueryParameter("sort", "date")
            .addQueryParameter("page", page.toString())
            .build()
        GET(url, apiHeaders())
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val data = response.parseAs<GalleryListResponse>()
        val page = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val mangas = data.result.map { it.toSManga() }
        val hasNext = page < data.numPages
        return MangasPage(mangas, hasNext)
    }

    // --- Popular Manga ---

    override fun popularMangaRequest(page: Int): Request {
        val url = "$apiUrl/search".toHttpUrl().newBuilder()
            .addQueryParameter(
                "query",
                if (nhLang.isBlank()) "\"\"" else "language:$nhLang",
            )
            .addQueryParameter("sort", "popular")
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, apiHeaders())
    }

    override fun popularMangaParse(response: Response): MangasPage = latestUpdatesParse(response)

    // --- Search ---

    override fun fetchSearchManga(
        page: Int,
        query: String,
        filters: FilterList,
    ): Observable<MangasPage> = when {
        query.startsWith(PREFIX_ID_SEARCH) -> {
            val id = query.removePrefix(PREFIX_ID_SEARCH)
            client.newCall(galleryDetailRequest(id))
                .asObservableSuccess()
                .map { response -> searchMangaByIdParse(response, id) }
        }

        query.toIntOrNull() != null -> {
            client.newCall(galleryDetailRequest(query))
                .asObservableSuccess()
                .map { response -> searchMangaByIdParse(response, query) }
        }

        else -> super.fetchSearchManga(page, query, filters)
    }

    override fun searchMangaRequest(
        page: Int,
        query: String,
        filters: FilterList,
    ): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val nhLangSearch = if (nhLang.isBlank()) "" else "language:$nhLang "
        val advQuery = combineQuery(filterList)
        val favoriteFilter = filterList.findInstance<FavoriteFilter>()
        val offsetPage =
            filterList.findInstance<OffsetPageFilter>()?.state?.toIntOrNull()?.plus(page) ?: page

        if (favoriteFilter?.state == true) {
            val url = "$apiUrl/favorites".toHttpUrl().newBuilder()
                .addQueryParameter("q", "$query $advQuery".trim())
                .addQueryParameter("page", offsetPage.toString())
                .build()
            return GET(url, apiHeaders())
        }

        val fullQuery = "$query $nhLangSearch$advQuery".trim().ifBlank { "\"\"" }
        val urlBuilder = "$apiUrl/search".toHttpUrl().newBuilder()
            .addQueryParameter("query", fullQuery)
            .addQueryParameter("page", offsetPage.toString())

        filterList.findInstance<SortFilter>()?.let { f ->
            urlBuilder.addQueryParameter("sort", f.toUriPart())
        }

        return GET(urlBuilder.build(), apiHeaders())
    }

    override fun searchMangaParse(response: Response): MangasPage = latestUpdatesParse(response)

    private fun galleryDetailRequest(id: String) = GET("$apiUrl/galleries/$id", apiHeaders())

    private fun searchMangaByIdParse(response: Response, id: String): MangasPage {
        val details = mangaDetailsParse(response)
        details.url = "/g/$id/"
        return MangasPage(listOf(details), false)
    }

    // --- Manga Details ---

    override fun mangaDetailsRequest(manga: SManga): Request {
        val id = manga.url.trimEnd('/').substringAfterLast('/')
        return galleryDetailRequest(id)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val data = response.parseAs<GalleryDetail>()
        return SManga.create().apply {
            title = if (displayFullTitle) {
                data.title.english ?: data.title.japanese ?: data.title.pretty!!
            } else {
                data.title.pretty
                    ?: (data.title.english ?: data.title.japanese)!!.shortenTitle()
            }
            thumbnail_url = thumbUrl(data.thumbnail.path)
            status = SManga.COMPLETED
            artist = getArtists(data)
            author = getGroups(data) ?: getArtists(data)
            // Some people want these additional details in description
            description = "Full English and Japanese titles:\n"
                .plus(
                    "${data.title.english ?: data.title.japanese ?: data.title.pretty ?: ""}\n",
                )
                .plus(data.title.japanese ?: "")
                .plus("\n\n")
                .plus("Pages: ${data.numPages}\n")
                .plus("Favorited by: ${data.numFavorites}\n")
                .plus(getTagDescription(data))
            genre = getTags(data)
            update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
        }
    }

    // --- Chapters ---

    override fun chapterListRequest(manga: SManga): Request = mangaDetailsRequest(manga)

    override fun chapterListParse(response: Response): List<SChapter> {
        val data = response.parseAs<GalleryDetail>()
        return listOf(
            SChapter.create().apply {
                name = "Chapter"
                scanlator = getGroups(data)
                date_upload = data.uploadDate * 1000
                url = "/g/${data.id}/"
            },
        )
    }

    // --- Pages ---

    override fun pageListRequest(chapter: SChapter): Request {
        val id = chapter.url.trimEnd('/').substringAfterLast('/')
        return galleryDetailRequest(id)
    }

    override fun pageListParse(response: Response): List<Page> {
        val data = response.parseAs<GalleryDetail>()
        return data.pages.mapIndexed { i, page ->
            Page(
                index = i,
                imageUrl = imageUrl(page.path),
            )
        }
    }

    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()

    // --- Helpers ---

    private fun GalleryListItem.toSManga(): SManga = SManga.create().apply {
        url = "/g/$id/"
        title = (englishTitle ?: japaneseTitle ?: "Gallery #$id").let {
            if (displayFullTitle) it.trim() else it.shortenTitle()
        }
        thumbnail_url = thumbUrl(thumbnail)
    }

    private fun combineQuery(filters: FilterList): String = buildString {
        filters.filterIsInstance<AdvSearchEntryFilter>().forEach { filter ->
            filter.state.split(",")
                .map(String::trim)
                .filterNot(String::isBlank)
                .forEach { tag ->
                    val quoted = filter.queryName != "pages" &&
                        filter.queryName != "uploaded"
                    if (tag.startsWith("-")) append("-")
                    append(filter.queryName, ':')
                    if (quoted) append('"')
                    append(tag.removePrefix("-"))
                    if (quoted) append('"')
                    append(" ")
                }
        }
    }

    private inline fun <reified T> Response.parseAs(): T = json.decodeFromString<T>(body.string())

    // --- Filters ---

    override fun getFilterList(): FilterList = FilterList(
        Filter.Header("Separate tags with commas (,)"),
        Filter.Header("Prepend with dash (-) to exclude"),
        TagFilter(),
        CategoryFilter(),
        GroupFilter(),
        ArtistFilter(),
        ParodyFilter(),
        CharactersFilter(),
        Filter.Header("Uploaded valid units are h, d, w, m, y."),
        Filter.Header("example: (>20d)"),
        UploadedFilter(),
        Filter.Header("Filter by pages, for example: (>20)"),
        PagesFilter(),

        Filter.Separator(),
        SortFilter(),
        OffsetPageFilter(),
        Filter.Header("Sort is ignored if favorites only"),
        FavoriteFilter(),
    )

    class TagFilter : AdvSearchEntryFilter("tag", "Tags")
    class CategoryFilter : AdvSearchEntryFilter("category", "Categories")
    class GroupFilter : AdvSearchEntryFilter("group", "Groups")
    class ArtistFilter : AdvSearchEntryFilter("artist", "Artists")
    class ParodyFilter : AdvSearchEntryFilter("parody", "Parodies")
    class CharactersFilter : AdvSearchEntryFilter("character", "Characters")
    class UploadedFilter : AdvSearchEntryFilter("uploaded", "Uploaded")
    class PagesFilter : AdvSearchEntryFilter("pages", "Pages")
    open class AdvSearchEntryFilter(
        val queryName: String,
        displayName: String,
    ) : Filter.Text(displayName)

    class OffsetPageFilter : Filter.Text("Offset results by # pages")

    private class FavoriteFilter : Filter.CheckBox("Show favorites only", false)

    private class SortFilter :
        UriPartFilter(
            "Sort By",
            arrayOf(
                Pair("Popular: All Time", "popular"),
                Pair("Popular: Month", "popular-month"),
                Pair("Popular: Week", "popular-week"),
                Pair("Popular: Today", "popular-today"),
                Pair("Recent", "date"),
            ),
        )

    private open class UriPartFilter(
        displayName: String,
        val vals: Array<Pair<String, String>>,
    ) : Filter.Select<String>(
        displayName,
        vals.map { it.first }.toTypedArray(),
    ) {
        fun toUriPart() = vals[state].second
    }

    private inline fun <reified T> Iterable<*>.findInstance() = find { it is T } as? T

    companion object {
        const val PREFIX_ID_SEARCH = "id:"
        private const val TITLE_PREF = "Display manga title as:"
        private const val API_KEY_PREF = "api_key"
    }
}
