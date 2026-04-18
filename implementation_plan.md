# Migrate NHentai Extension from HTML Scraping to API v2

## Background

The nhentai site has been updated and the old HTML-scraping approach (parsing embedded `JSON.parse(...)` scripts for gallery data, regex for CDN URLs) is breaking. NHentai now exposes a proper REST API v2 at `https://nhentai.net/api/v2/`.

The extension currently extends `ParsedHttpSource` (HTML parsing). We need to switch to `HttpSource` (JSON-based) and use the API v2 endpoints directly.

## API v2 Summary

### Endpoints
| Endpoint | Description | Rate Limit |
|---|---|---|
| `GET /api/v2/cdn` | CDN server URLs (`image_servers`, `thumb_servers`) | N/A |
| `GET /api/v2/galleries?page=&per_page=` | Latest galleries (paginated) | 30/min/IP |
| `GET /api/v2/galleries/popular` | Popular galleries (no params) | 20/min/IP |
| `GET /api/v2/galleries/{gallery_id}?include=` | Single gallery detail | 45/min/IP |
| `GET /api/v2/search?query=&sort=&page=` | Search galleries | 20/min/IP |
| `GET /api/v2/favorites?q=&page=` | User favorites (auth required) | 15/min/user |

### Auth
- Header: `Authorization`
- Formats: `User <token>` or `Key <api_key>`
- Public endpoints work without auth; favorites require auth

### Key Response Schemas

**Gallery List Item** (from `/galleries` and `/search`):
```json
{
  "id": 644626,
  "media_id": "3895105",
  "english_title": "...",
  "japanese_title": "...",
  "thumbnail": "galleries/3895105/thumb.webp",
  "thumbnail_width": 250,
  "thumbnail_height": 353,
  "num_pages": 8,
  "tag_ids": [33172, 30253, ...],
  "upload_date": 1744968421
}
```

**Gallery List Response envelope**:
```json
{
  "result": [...],
  "num_pages": 1234,
  "per_page": 25
}
```

**Gallery Detail** (from `/galleries/{id}`):
```json
{
  "id": 1,
  "media_id": "9",
  "title": { "english": "...", "japanese": "...", "pretty": "..." },
  "cover": { "path": "galleries/9/cover.jpg", "width": 350, "height": 505 },
  "thumbnail": { "path": "galleries/9/thumb.jpg", "width": 250, "height": 361 },
  "scanlator": "",
  "upload_date": 1374783863,
  "tags": [{ "id": 0, "type": "tag", "name": "big breasts", "slug": "...", "url": "...", "count": 0 }],
  "num_pages": 15,
  "num_favorites": 2784,
  "pages": [{ "number": 1, "path": "galleries/9/1.jpg", "width": 1200, "height": 1734, "thumbnail": "galleries/9/1t.jpg", "thumbnail_width": 173, "thumbnail_height": 250 }],
  "comments": null,
  "related": null,
  "is_favorited": null
}
```

### CDN Response
```json
{
  "image_servers": ["https://i1.nhentai.net", ...],
  "thumb_servers": ["https://t1.nhentai.net", ...]
}
```

Image URL construction: `{image_server}/{page.path}` (e.g., `https://i1.nhentai.net/galleries/9/1.jpg`)
Thumbnail URL construction: `{thumb_server}/{gallery.thumbnail}` (for list items) or `{thumb_server}/{gallery.thumbnail.path}` (for detail)

### Search Sort Options
- `date` (default) / `popular` / `popular-today` / `popular-week` / `popular-month`

### Search Query Syntax
Same as before: `tag:"name"`, `artist:name`, `language:english`, `pages:>10`, `uploaded:<7d`, negation with `-`

---

## Proposed Changes

### [MODIFY] [NHentai.kt](file:///d:/Code/Other/extensions-source/src/all/nhentai/src/eu/kanade/tachiyomi/extension/all/nhentai/NHentai.kt)

Major rewrite - switch from `ParsedHttpSource` to `HttpSource`:

1. **Change base class** from `ParsedHttpSource` to `HttpSource`
2. **Add API base URL** constant: `$baseUrl/api/v2`
3. **CDN fetching**: Fetch `/api/v2/cdn` once and cache `image_servers` and `thumb_servers`
4. **Latest updates**: Use `GET /api/v2/galleries?page=X&per_page=25` instead of HTML scraping
   - For language-filtered: add `language:{nhLang}` as search query via `/api/v2/search`
5. **Popular manga**: Use `GET /api/v2/search?query=language:{nhLang}&sort=popular&page=X` (or `/galleries/popular` for "all")
6. **Search**: Use `GET /api/v2/search?query=X&sort=Y&page=Z`
7. **Gallery detail**: Use `GET /api/v2/galleries/{id}` instead of HTML parsing
8. **Page list**: Use gallery detail response's `pages` array with CDN URLs
9. **Favorites**: Use `GET /api/v2/favorites?q=X&page=Y` with auth header
10. **Remove**: All HTML parsing code (`ParsedHttpSource` overrides, JSoup, regex patterns)
11. **Add**: API key preference for optional auth

---

### [MODIFY] [NHDto.kt](file:///d:/Code/Other/extensions-source/src/all/nhentai/src/eu/kanade/tachiyomi/extension/all/nhentai/NHDto.kt)

Update DTOs to match API v2 response schemas:

1. **Add `GalleryListResponse`**: `{ result: List<GalleryListItem>, num_pages: Int, per_page: Int }`
2. **Add `GalleryListItem`**: `{ id, media_id, english_title, japanese_title, thumbnail, thumbnail_width, thumbnail_height, num_pages, tag_ids, upload_date }`
3. **Update `Hentai`** (rename to `GalleryDetail`): Add `cover`, `thumbnail` (as object with `path`), `pages` (with `number`, `path`, `width`, `height`), `num_pages`, remove `images`
4. **Add `CdnResponse`**: `{ image_servers: List<String>, thumb_servers: List<String> }`
5. **Add `CoverImage`/`PageImage`**: `{ path, width, height }`
6. **Update `Tag`**: Add `id`, `slug`, `url`, `count` fields

---

### [MODIFY] [NHUtils.kt](file:///d:/Code/Other/extensions-source/src/all/nhentai/src/eu/kanade/tachiyomi/extension/all/nhentai/NHUtils.kt)

- Remove the `Element.cleanTag()` extension function (no longer parsing HTML)
- Keep tag helper functions but update them to work with the new DTO class name

---

### No changes needed
- [NHFactory.kt](file:///d:/Code/Other/extensions-source/src/all/nhentai/src/eu/kanade/tachiyomi/extension/all/nhentai/NHFactory.kt) - No changes
- [NHUrlActivity.kt](file:///d:/Code/Other/extensions-source/src/all/nhentai/src/eu/kanade/tachiyomi/extension/all/nhentai/NHUrlActivity.kt) - No changes
- [AndroidManifest.xml](file:///d:/Code/Other/extensions-source/src/all/nhentai/AndroidManifest.xml) - No changes

---

### [MODIFY] [build.gradle](file:///d:/Code/Other/extensions-source/src/all/nhentai/build.gradle)

- Bump `extVersionCode` from 54 to 55

## User Review Required

> [!IMPORTANT]
> **API Key preference**: The API supports optional `Authorization: Key <api_key>` header for personalization and favorites. I will add a preference field where users can paste their API key. This is optional for browsing but required for favorites.

> [!WARNING]
> **Language filtering**: The old approach used `/language/{nhLang}/` URL paths. The new API does not have language-specific list endpoints. For language-filtered sources (en, ja, zh), I'll prepend `language:{nhLang}` to the search query to filter by language. For the latest updates, this means using the search endpoint instead of the galleries endpoint for non-"all" sources.

## Verification Plan

### Manual Verification
- Build the extension APK and test:
  - Latest manga list loads
  - Popular manga list loads
  - Search works with filters
  - Gallery details load correctly
  - Page images load correctly
  - Favorites work with API key
