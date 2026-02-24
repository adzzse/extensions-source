package eu.kanade.tachiyomi.extension.vi.qadcuteo

import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.source.model.SChapter
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

class QADCuteo :
    Madara(
        "QAD Cuteo",
        "https://qadcuteo.cc",
        "vi",
        SimpleDateFormat("dd/MM/yyyy", Locale("vi")),
    ) {
    override val filterNonMangaItems = false
    override val useLoadMoreRequest = LoadMoreStrategy.Never
    override val useNewChapterEndpoint = true

    override fun chapterFromElement(element: Element): SChapter {
        val chapter = SChapter.create()

        with(element) {
            // Some chapters have a thumbnail with an <a> containing only an image (no text),
            // or a "NEW" badge <a> with empty text. Pick the first <a> with actual text.
            val urlElement = select("a").first { it.text().isNotBlank() }

            chapter.url = urlElement.attr("abs:href").let {
                it.substringBefore("?style=paged") + if (!it.endsWith(chapterUrlSuffix)) chapterUrlSuffix else ""
            }
            chapter.name = urlElement.text()

            chapter.date_upload = selectFirst("img:not(.thumb)")?.attr("alt")?.let { parseRelativeDate(it) }
                ?: selectFirst("span a")?.attr("title")?.let { parseRelativeDate(it) }
                ?: parseChapterDate(selectFirst(chapterDateSelector())?.text())
        }

        return chapter
    }
}
