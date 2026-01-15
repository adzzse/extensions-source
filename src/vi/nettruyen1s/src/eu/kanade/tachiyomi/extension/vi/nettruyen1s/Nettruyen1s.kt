package eu.kanade.tachiyomi.extension.vi.nettruyen1s

import android.content.SharedPreferences
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import keiyoushi.utils.getPreferences
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

class Nettruyen1s : ParsedHttpSource(), ConfigurableSource {

    override val name = "Nettruyen1s"

    private val defaultBaseUrl = "https://nettruyen1s.com"

    override val baseUrl by lazy { getPrefBaseUrl() }

    private val preferences: SharedPreferences = getPreferences()

    override val lang = "vi"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // Popular
    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/danh-sach-truyen/$page/?sort=views", headers)
    }

    override fun popularMangaSelector() = ".items .item"

    override fun popularMangaFromElement(element: Element): SManga {
        return SManga.create().apply {
            element.select("h3 a").let {
                title = it.text()
                setUrlWithoutDomain(it.attr("href"))
            }
            thumbnail_url = element.select(".image img").attr("data-original").ifEmpty {
                element.select(".image img").attr("src")
            }
        }
    }

    override fun popularMangaNextPageSelector() = "ul.pagination > li.active + li"

    // Latest
    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl?page=$page", headers)
    }

    override fun latestUpdatesSelector() = popularMangaSelector()

    override fun latestUpdatesFromElement(element: Element): SManga = popularMangaFromElement(element)

    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    // Search
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val tagFilter = filters.firstOrNull { it is TagFilter } as? TagFilter

        return if (query.isNotEmpty()) {
            GET("$baseUrl/tim-truyen?keyword=$query&page=$page", headers)
        } else if (tagFilter != null && tagFilter.state != 0) {
            val tag = tagFilter.toUriPart()
            GET("$baseUrl/the-loai/$tag?page=$page", headers)
        } else {
            popularMangaRequest(page)
        }
    }

    override fun searchMangaSelector() = popularMangaSelector()

    override fun searchMangaFromElement(element: Element): SManga = popularMangaFromElement(element)

    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    // Details
    override fun mangaDetailsParse(document: Document): SManga {
        return SManga.create().apply {
            val root = document.select("#item-detail")
            title = root.select("h1.title-detail").text()
            author = root.select("li.author p.col-xs-8").text()
            description = root.select(".detail-content p").text()
            genre = root.select("li.kind p.col-xs-8 a").joinToString { it.text() }
            status = parseStatus(root.select("li.status p.col-xs-8").text())
            thumbnail_url = root.select(".col-image img").attr("src")
        }
    }

    private fun parseStatus(status: String) = when {
        status.contains("Đang tiến hành") -> SManga.ONGOING
        status.contains("Hoàn thành") -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }

    // Chapters
    override fun chapterListSelector() = "#nt_listchapter .row:not(.heading)"

    override fun chapterFromElement(element: Element): SChapter {
        return SChapter.create().apply {
            element.select(".chapter a").let {
                name = it.text()
                setUrlWithoutDomain(it.attr("href"))
            }
            date_upload = parseDate(element.select(".col-xs-4").text())
        }
    }

    private val dateFormat = SimpleDateFormat("dd/MM/yy", Locale.getDefault())

    private fun parseDate(date: String): Long {
        return try {
            if (date.contains(":")) {
                // Return current time for relative dates like "15:30"
                System.currentTimeMillis()
            } else {
                dateFormat.parse(date)?.time ?: 0L
            }
        } catch (e: Exception) {
            0L
        }
    }

    // Pages
    override fun pageListParse(document: Document): List<Page> {
        val pages = mutableListOf<Page>()
        document.select(".reading-detail .page-chapter img").forEachIndexed { i, img ->
            val url = img.attr("data-original").ifEmpty { img.attr("src") }
            if (url.startsWith("//")) {
                pages.add(Page(i, "", "https:$url"))
            } else {
                pages.add(Page(i, "", url))
            }
        }
        return pages
    }

    override fun imageUrlParse(document: Document): String = throw UnsupportedOperationException("Not used")

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val baseUrlPref = EditTextPreference(screen.context).apply {
            key = BASE_URL_PREF
            title = BASE_URL_PREF_TITLE
            summary = BASE_URL_PREF_SUMMARY
            setDefaultValue(defaultBaseUrl)
            dialogTitle = BASE_URL_PREF_TITLE
            dialogMessage = "Default: $defaultBaseUrl"

            setOnPreferenceChangeListener { _, _ ->
                Toast.makeText(screen.context, RESTART_APP, Toast.LENGTH_LONG).show()
                true
            }
        }
        screen.addPreference(baseUrlPref)
    }

    private fun getPrefBaseUrl(): String = preferences.getString(BASE_URL_PREF, defaultBaseUrl)!!

    override fun getFilterList() = FilterList(
        TagFilter(tagList),
    )

    private class TagFilter(tags: List<Tag>) : Filter.Select<String>("Thể loại", tags.map { it.name }.toTypedArray()) {
        private val validTags = tags
        fun toUriPart() = validTags[state].id
    }

    private class Tag(val name: String, val id: String)

    private val tagList = listOf(
        Tag("Tất cả", ""),
        Tag("Action", "action"),
        Tag("Adventure", "adventure"),
        Tag("AI", "ai"),
        Tag("Anime", "anime"),
        Tag("Chuyển Sinh", "chuyen-sinh"),
        Tag("Cổ Đại", "co-dai"),
        Tag("Cổ Trang", "co-trang"),
        Tag("Comedy", "comedy"),
        Tag("Comic", "comic"),
        Tag("Crossdress", "crossdress"),
        Tag("Demons", "demons"),
        Tag("Detective", "detective"),
        Tag("Doujinshi", "doujinshi"),
        Tag("Drama", "drama"),
        Tag("Đam Mỹ", "dam-my"),
        Tag("Ecchi", "ecchi"),
        Tag("Fantasy", "fantasy"),
        Tag("Gender Bender", "gender-bender"),
        Tag("Harem", "harem"),
        Tag("Historical", "historical"),
        Tag("Horror", "horror"),
        Tag("Huyền Huyễn", "huyen-huyen"),
        Tag("Isekai", "isekai"),
        Tag("Josei", "josei"),
        Tag("Magic", "magic"),
        Tag("Manga", "manga"),
        Tag("Manhua", "manhua"),
        Tag("Manhwa", "manhwa"),
        Tag("Martial Arts", "martial-arts"),
        Tag("Mature", "mature"),
        Tag("Mystery", "mystery"),
        Tag("Ngôn Tình", "ngon-tinh"),
        Tag("One Shot", "one-shot"),
        Tag("Psychological", "psychological"),
        Tag("Romance", "romance"),
        Tag("School Life", "school-life"),
        Tag("Sci-Fi", "sci-fi"),
        Tag("Seinen", "seinen"),
        Tag("Shoujo", "shoujo"),
        Tag("Shoujo Ai", "shoujo-ai"),
        Tag("Shounen", "shounen"),
        Tag("Shounen Ai", "shounen-ai"),
        Tag("Slice Of Life", "slice-of-life"),
        Tag("Sports", "sports"),
        Tag("Supernatural", "supernatural"),
        Tag("Tragedy", "tragedy"),
        Tag("Trọng Sinh", "trong-sinh"),
        Tag("Truyện Màu", "truyen-mau"),
        Tag("Webtoon", "webtoon"),
        Tag("Xuyên Không", "xuyen-khong"),
        Tag("Yaoi", "yaoi"),
        Tag("Yuri", "yuri"),
    )

    companion object {
        private const val RESTART_APP = "Khởi chạy lại ứng dụng để áp dụng thay đổi."
        private const val BASE_URL_PREF_TITLE = "Ghi đè URL cơ sở"
        private const val BASE_URL_PREF = "overrideBaseUrl"
        private const val BASE_URL_PREF_SUMMARY = "Dành cho sử dụng tạm thời, cập nhật tiện ích sẽ xóa cài đặt."
    }
}
