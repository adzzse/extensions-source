package eu.kanade.tachiyomi.extension.all.nhentai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// --- CDN ---

@Serializable
class CdnResponse(
    @SerialName("image_servers") val imageServers: List<String>,
    @SerialName("thumb_servers") val thumbServers: List<String>,
)

// --- Gallery List (from /galleries, /search, /favorites) ---

@Serializable
class GalleryListResponse(
    val result: List<GalleryListItem>,
    @SerialName("num_pages") val numPages: Int,
    @SerialName("per_page") val perPage: Int,
)

@Serializable
class GalleryListItem(
    val id: Int,
    @SerialName("media_id") val mediaId: String,
    @SerialName("english_title") val englishTitle: String? = null,
    @SerialName("japanese_title") val japaneseTitle: String? = null,
    val thumbnail: String,
    @SerialName("thumbnail_width") val thumbnailWidth: Int = 0,
    @SerialName("thumbnail_height") val thumbnailHeight: Int = 0,
    @SerialName("num_pages") val numPages: Int = 0,
    @SerialName("tag_ids") val tagIds: List<Int> = emptyList(),
    @SerialName("upload_date") val uploadDate: Long = 0,
)

// --- Gallery Detail (from /galleries/{id}) ---

@Serializable
class GalleryDetail(
    val id: Int,
    @SerialName("media_id") val mediaId: String,
    val title: Title,
    val cover: ImagePath,
    val thumbnail: ImagePath,
    val scanlator: String? = null,
    @SerialName("upload_date") val uploadDate: Long,
    val tags: List<Tag>,
    @SerialName("num_pages") val numPages: Int,
    @SerialName("num_favorites") val numFavorites: Long,
    val pages: List<PageImage>,
)

@Serializable
class Title(
    val english: String? = null,
    val japanese: String? = null,
    val pretty: String? = null,
)

@Serializable
class ImagePath(
    val path: String,
    val width: Int = 0,
    val height: Int = 0,
)

@Serializable
class PageImage(
    val number: Int,
    val path: String,
    val width: Int = 0,
    val height: Int = 0,
    val thumbnail: String? = null,
    @SerialName("thumbnail_width") val thumbnailWidth: Int = 0,
    @SerialName("thumbnail_height") val thumbnailHeight: Int = 0,
)

@Serializable
class Tag(
    val id: Int = 0,
    val type: String,
    val name: String,
    val slug: String = "",
    val url: String = "",
    val count: Int = 0,
)
