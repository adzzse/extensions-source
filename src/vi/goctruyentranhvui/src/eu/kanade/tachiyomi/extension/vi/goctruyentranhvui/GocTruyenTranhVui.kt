package eu.kanade.tachiyomi.extension.vi.goctruyentranhvui

import android.annotation.SuppressLint
import android.app.Application
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferences
import keiyoushi.utils.parseAs
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class GocTruyenTranhVui :
    HttpSource(),
    ConfigurableSource {
    override val lang = "vi"
    override val name = "Goc Truyen Tranh Vui"
    override val supportsLatest: Boolean = true

    private val defaultBaseUrl = "https://goctruyentranhvui21.com"

    private val preferences: SharedPreferences = getPreferences()

    override val baseUrl by lazy { getPrefBaseUrl() }

    private val apiUrl get() = "$baseUrl/api/v2"

    init {
        preferences.getString(DEFAULT_BASE_URL_PREF, null).let { prefDefaultBaseUrl ->
            if (prefDefaultBaseUrl != defaultBaseUrl) {
                preferences.edit()
                    .putString(BASE_URL_PREF, defaultBaseUrl)
                    .putString(DEFAULT_BASE_URL_PREF, defaultBaseUrl)
                    .apply()
            }
        }
    }

    private var hasCheckedRedirect = false

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .addInterceptor { chain ->
            val originalRequest = chain.request()
            val response = chain.proceed(originalRequest)
            if (!hasCheckedRedirect && preferences.getBoolean(AUTO_CHANGE_DOMAIN_PREF, false)) {
                hasCheckedRedirect = true
                val originalHost = defaultBaseUrl.toHttpUrl().host
                val newHost = response.request.url.host
                if (newHost != originalHost) {
                    val newBaseUrl = "${response.request.url.scheme}://$newHost"
                    preferences.edit()
                        .putString(BASE_URL_PREF, newBaseUrl)
                        .apply()
                }
            }
            response
        }
        .rateLimit(3)
        .build()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    private val xhrHeaders by lazy {
        headersBuilder()
            .set("X-Requested-With", "XMLHttpRequest")
            .build()
    }

    override fun popularMangaRequest(page: Int): Request = GET(
        apiUrl.toHttpUrl().newBuilder().apply {
            addPathSegments("home/filter")
            addQueryParameter("p", (page - 1).toString())
            addQueryParameter("value", "recommend")
        }.build(),
        xhrHeaders,
    )

    override fun popularMangaParse(response: Response): MangasPage {
        val res = response.parseAs<ResultDto<ListingDto>>()
        val hasNextPage = res.result.next
        return MangasPage(res.result.data.map { it.toSManga(baseUrl) }, hasNextPage)
    }

    override fun latestUpdatesRequest(page: Int): Request = GET(
        "$apiUrl/search?p=${page - 1}&orders%5B%5D=recentDate",
        xhrHeaders,
    )

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    override fun getMangaUrl(manga: SManga) = "$baseUrl/truyen/${manga.url.substringAfter(':')}"

    override fun chapterListRequest(manga: SManga): Request {
        val mangaId = manga.url.substringBefore(':')
        val slug = manga.url.substringAfter(':')
        return GET("$baseUrl/api/comic/$mangaId/chapter?limit=-1#$slug", xhrHeaders)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val slug = response.request.url.fragment!!
        val chapterJson = runCatching { response.parseAs<ResultDto<ChapterListDto>>() }.getOrNull()
        if (chapterJson == null || chapterJson.result.chapters.isEmpty()) {
            throw Exception("Có thể: Phiên làm việc đã hết hạn, vui lòng tải lại.")
        }
        return chapterJson.result.chapters.map { it.toSChapter(slug) }
    }

    override fun mangaDetailsRequest(manga: SManga) = GET(getMangaUrl(manga), headers)

    override fun mangaDetailsParse(response: Response): SManga = SManga.create().apply {
        val document = response.asJsoup()
        title = document.select(".v-card-title").text()
        genre = document.select(".group-content > .v-chip-link").joinToString { it.text() }
        thumbnail_url = document.selectFirst("img.image")?.absUrl("src")
        status = parseStatus(document.selectFirst(".mb-1:contains(Trạng thái:) span")?.text())
        author = document.selectFirst(".mb-1:contains(Tác giả:) span")?.text()
        description = document.select(".v-card-text").joinToString { it.wholeText().trim() }
    }

    private fun parseStatus(status: String?) = when {
        status == null -> SManga.UNKNOWN
        status.contains("Đang thực hiện", ignoreCase = true) -> SManga.ONGOING
        status.contains("Hoàn thành", ignoreCase = true) -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }

    override fun pageListRequest(chapter: SChapter): Request {
        val url = chapter.url
        val slug = url.substringAfter("/truyen/").substringBefore("/chuong-")
        val numberChapter = url.substringAfter("/chuong-").substringBefore("#")
        val comicId = url.substringAfter("#")

        val body = FormBody.Builder()
            .add("comicId", comicId)
            .add("chapterNumber", numberChapter)
            .add("nameEn", slug)
            .build()

        return POST("$baseUrl/api/chapter/loadAll", pageHeaders, body)
    }

    override fun pageListParse(response: Response): List<Page> {
        val jsonResult = runCatching { response.parseAs<ResultDto<ImageListDto>>() }
        jsonResult.onFailure {
            throw Exception("Có thể: Phiên làm việc đã hết hạn, vui lòng tải lại")
        }

        val imageList = jsonResult.getOrThrow().result.data
        if (imageList.isNullOrEmpty()) {
            throw Exception("Chưa đăng nhập trong WebView. Hoặc không có ảnh!")
        }

        return imageList.mapIndexed { i, url ->
            val finalUrl = if (url.startsWith("/image/")) {
                baseUrl + url
            } else {
                url
            }
            Page(i, imageUrl = finalUrl)
        }
    }

    private val pageHeaders by lazy {
        token?.let {
            headersBuilder()
                .set("X-Requested-With", "XMLHttpRequest")
                .set("Origin", baseUrl)
                .set("Authorization", it)
                .build()
        } ?: xhrHeaders
    }

    private var _token: String? = null

    @get:SuppressLint("SetJavaScriptEnabled")
    val token: String?
        get() {
            _token?.also { return it }
            val handler = Handler(Looper.getMainLooper())
            val latch = CountDownLatch(1)
            if (!customToken().isNullOrBlank()) {
                return customToken()
            }
            if (_token != null) return _token

            handler.post {
                val webview = WebView(Injekt.get<Application>())
                with(webview.settings) {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    blockNetworkImage = true
                }
                webview.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        // Get token
                        view!!.evaluateJavascript("window.localStorage.getItem('Authorization')") { token ->
                            _token = token.takeUnless { it == "null" }?.removeSurrounding("\"")
                            latch.countDown()
                            webview.destroy()
                        }
                    }
                }
                webview.loadDataWithBaseURL(baseUrl, " ", "text/html", "UTF-8", null)
            }

            latch.await(10, TimeUnit.SECONDS)
            return _token
        }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        if (query.startsWith(PREFIX_ID_SEARCH)) {
            val slug = query.removePrefix(PREFIX_ID_SEARCH).trim()
            val newQuery = slug.replace("-", " ")
            return client.newCall(searchMangaRequest(page, newQuery, filters))
                .asObservableSuccess()
                .map { response ->
                    val pageResult = searchMangaParse(response)
                    val exactMatch = pageResult.mangas.filter { it.url.substringAfter(":") == slug }
                    if (exactMatch.isNotEmpty()) {
                        MangasPage(exactMatch, false)
                    } else {
                        pageResult
                    }
                }
        }
        return super.fetchSearchManga(page, query, filters)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = apiUrl.toHttpUrl().newBuilder().apply {
            addPathSegments("search")
            addQueryParameter("p", (page - 1).toString())
            addQueryParameter("searchValue", query)
            for (filter in filters) {
                when (filter) {
                    is FilterGroup ->
                        for (checkbox in filter.state) {
                            if (checkbox.state) addQueryParameter(filter.query, checkbox.id)
                        }

                    else -> {}
                }
            }
        }.build()
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    override fun getFilterList() = FilterList(
        StatusList(getStatusList()),
        SortByList(getSortByList()),
        GenreList(getGenreList()),
    )

    private fun customToken(): String? = preferences.getString(CUSTOM_TOKEN, null)

    private fun getPrefBaseUrl(): String = preferences.getString(BASE_URL_PREF, defaultBaseUrl)!!

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
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
        }.also(screen::addPreference)

        androidx.preference.SwitchPreferenceCompat(screen.context).apply {
            key = AUTO_CHANGE_DOMAIN_PREF
            title = AUTO_CHANGE_DOMAIN_TITLE
            summary = AUTO_CHANGE_DOMAIN_SUMMARY
            setDefaultValue(false)
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = CUSTOM_TOKEN
            title = "Authorization Token"
            summary = "Enter token manually"
            dialogTitle = "Authorization Token"
            customToken()?.let { dialogMessage = if (it.isNotEmpty()) "Token: ${customToken()}" else "Only show manually entered token, do not show token from WebView" }
            setOnPreferenceChangeListener { _, _ ->
                Toast.makeText(screen.context, RESTART_APP, Toast.LENGTH_LONG).show()
                true
            }
        }.also(screen::addPreference)
    }

    companion object {
        const val PREFIX_ID_SEARCH = "id:"
        private const val CUSTOM_TOKEN = "custom_token"
        private const val RESTART_APP = "Khởi chạy lại ứng dụng để áp dụng thay đổi."
        private const val DEFAULT_BASE_URL_PREF = "defaultBaseUrl"
        private const val BASE_URL_PREF_TITLE = "Ghi đè URL cơ sở"
        private const val BASE_URL_PREF = "overrideBaseUrl"
        private const val BASE_URL_PREF_SUMMARY =
            "Dành cho sử dụng tạm thời, cập nhật tiện ích sẽ xóa cài đặt."
        private const val AUTO_CHANGE_DOMAIN_PREF = "autoChangeDomain"
        private const val AUTO_CHANGE_DOMAIN_TITLE = "Tự động cập nhật domain"
        private const val AUTO_CHANGE_DOMAIN_SUMMARY =
            "Khi mở ứng dụng, ứng dụng sẽ tự động cập nhật domain mới nếu website chuyển hướng."
    }
}
