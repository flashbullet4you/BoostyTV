package ru.boostytv.app

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ru.boostytv.app.data.TokenStore
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Работа с внутренним API Boosty (не документирован и может измениться).
 * Все приватные данные приходят из ленты «Мои подписки» (/v1/feed/subscribed) —
 * каждый пользователь видит публикации тех каналов, на которые оформлен его собственный доступ.
 */
object BoostyRepository {
    private const val TAG = "BoostyRepository"
    private const val DEBUG_LOG_RAW_POST = false // поставьте true только для отладки структуры ответа

    private const val API = "https://api.boosty.to/v1"
    private const val BLOG = "miheevpavlov"
    private const val BLOG_URL = "https://boosty.to/$BLOG"

    /** Порядок предпочтения вариантов видео в playerUrls. */
    private val QUALITY_ORDER = listOf(
        "hls", "dash", "full_hd", "quad_hd", "ultra_hd", "high", "medium", "low", "tiny", "lowest"
    )

    private class HttpResult(val code: Int, val body: String)

    /** Профиль вошедшего пользователя (для подтверждения, что это его аккаунт). */
    suspend fun loadCurrentUser(tokens: TokenStore): UserProfile? = try {
        val result = apiRequest(tokens, "$API/user/")
        if (result.code !in 200..299) {
            Log.w(TAG, "user: HTTP ${result.code}")
            null
        } else {
            val data = JSONObject(result.body).optJSONObject("data") ?: JSONObject(result.body)
            val subData = data.optJSONArray("subscribesWithBlogData")
            UserProfile(
                name = data.optString("first_name").ifBlank { data.optString("email", "Пользователь") },
                email = data.optString("email").takeIf { it.isNotBlank() },
                hasSubscriptions = (subData?.length() ?: 0) > 0 ||
                        data.optJSONArray("activeSubscribes")?.length() != 0,
            )
        }
    } catch (e: Exception) {
        Log.w(TAG, "user: ${e.message}")
        null
    }

    /** Результат одной страницы ленты: элементы и курсор следующей страницы. */
    data class FeedPage(
        val items: List<VideoItem>,
        /** Курсор next_offset / «до какого поста» брать следующую страницу; null — конец ленты. */
        val nextCursor: String?,
        /** Есть ли ещё страницы (по данным API). */
        val hasMore: Boolean,
    )

    private const val PAGE_SIZE = 30

    /**
     * Первая страница ленты публикаций канала. Сначала пробуем приватную ленту
     * подписчика (там есть платные посты с has_access=true), при неудаче — публичную ленту блога.
     */
    suspend fun loadFeedPosts(tokens: TokenStore): FeedPage =
        loadFeedPage(tokens, cursor = null)

    /**
     * Одна страница ленты. [cursor] — значение из предыдущего ответа (offset/id последнего поста);
     * для первой страницы — null. Поддерживаются оба варианта пагинации Boosty:
     * параметр `offset` и `max_post_id` (в разных эндпоинтах по-разному).
     */
    suspend fun loadFeedPage(tokens: TokenStore, cursor: String?): FeedPage {
        val blogId = resolveBlogId(tokens)

        if (blogId != null) {
            val subscribed = apiRequestOpt(tokens, feedUrl("$API/feed/subscribed/", blogId, cursor))
            if (subscribed != null && subscribed.code in 200..299) {
                val page = parsePostsPage(subscribed.body)
                if (page.items.isNotEmpty() || cursor == null) return page
            }
            val blogPrivate = apiRequestOpt(tokens, feedUrl("$API/blog/$blogId/post/", null, cursor))
            if (blogPrivate != null && blogPrivate.code in 200..299) {
                val page = parsePostsPage(blogPrivate.body)
                if (page.items.isNotEmpty() || cursor == null) return page
            }
        }

        // Запасной путь — публичная лента без авторизации.
        val public = apiRequestOpt(tokens, feedUrl("$API/blog/$BLOG/post/", null, cursor))
            ?: error("Boosty не отвечает")
        if (public.code !in 200..299) error("Boosty вернул HTTP ${public.code}")
        return parsePostsPage(public.body)
    }

    /** URL страницы ленты: limit + параметры курсора (offset/max_post_id). */
    private fun feedUrl(base: String, blogIdInQuery: String?, cursor: String?): String {
        val sb = StringBuilder(base).append("?limit=").append(PAGE_SIZE)
        if (blogIdInQuery != null) sb.append("&blog_id=").append(blogIdInQuery)
        if (!cursor.isNullOrBlank()) {
            // Boosty использует числовой offset или строковый id последнего поста —
            // передаём оба варианта, API проигнорирует неприменимый.
            val numeric = cursor.toLongOrNull()
            if (numeric != null) {
                sb.append("&offset=").append(cursor)
            } else {
                sb.append("&max_post_id=").append(URLEncoder.encode(cursor, "UTF-8"))
            }
        }
        return sb.toString()
    }

    private fun parsePostsPage(body: String): FeedPage {
        val root = JSONObject(body)
        val payload = root.optJSONObject("data").takeUnless { it == null && root.has("data") && root.opt("data") is JSONArray }
            ?: root
        val posts = payload.optJSONArray("data")
            ?: root.optJSONArray("data")
            ?: root.optJSONArray("posts")
            ?: JSONArray()

        if (DEBUG_LOG_RAW_POST) {
            Log.d(TAG, "posts=${posts.length()}")
            posts.optJSONObject(0)?.let { Log.d(TAG, "first post: ${it.toString(2)}") }
        }

        val items = (0 until posts.length()).mapNotNull { index ->
            posts.optJSONObject(index)?.let { parsePost(it) }
        }

        // Курсор следующей страницы: разные версии API отдают next_offset,
        // pagination.next или have_more + id последнего элемента.
        val pagination = payload.optJSONObject("pagination") ?: root.optJSONObject("pagination")
        val nextCursor = payload.optString("next_offset").takeIf { it.isNotBlank() && it != "null" }
            ?: root.optString("next_offset").takeIf { it.isNotBlank() && it != "null" }
            ?: pagination?.optString("next")?.takeIf { it.isNotBlank() && it != "null" }
            ?: items.lastOrNull()?.id

        val hasMore = when {
            payload.has("has_next") -> payload.optBoolean("has_next")
            root.has("has_next") -> root.optBoolean("has_next")
            pagination?.has("has_next") == true -> pagination.optBoolean("has_next")
            payload.has("have_more") -> payload.optBoolean("have_more")
            root.has("have_more") -> root.optBoolean("have_more")
            else -> items.size >= PAGE_SIZE && nextCursor != null
        }

        return FeedPage(items = items, nextCursor = nextCursor.takeIf { hasMore }, hasMore = hasMore)
    }

    /** id канала miheevpavlov — нужен для приватных эндпоинтов ленты. */
    private suspend fun resolveBlogId(tokens: TokenStore): String? {
        cachedBlogId?.let { return it }
        val result = apiRequestOpt(tokens, "$API/blog/$BLOG/") ?: return null
        if (result.code !in 200..299) return null
        val data = JSONObject(result.body).optJSONObject("data") ?: JSONObject(result.body)
        cachedBlogId = data.optString("id").takeIf { it.isNotBlank() }
        Log.d(TAG, "blogId=$cachedBlogId")
        return cachedBlogId
    }

    @Volatile
    private var cachedBlogId: String? = null

    /** Комментарии к публикации. */
    suspend fun loadComments(tokens: TokenStore, postId: String): List<PostComment> {
        val result = apiRequest(tokens, "$API/post/$postId/comment/?limit=50")
        if (result.code !in 200..299) error("Комментарии: HTTP ${result.code}")
        val arr = JSONObject(result.body).let {
            it.optJSONArray("data") ?: it.optJSONObject("data")?.optJSONArray("data") ?: JSONArray()
        }
        val myId = currentUserId(tokens)
        return (0 until arr.length()).mapNotNull { i ->
            val c = arr.optJSONObject(i) ?: return@mapNotNull null
            val author = c.optJSONObject("authorFrom") ?: c.optJSONObject("owner") ?: JSONObject()
            PostComment(
                id = c.optString("id"),
                authorName = author.optString("name").ifBlank { author.optString("email", "Автор") },
                text = c.optString("text").ifBlank { c.optString("description") },
                createdAt = c.optString("createdAt").ifBlank { c.optString("created_at") },
                isMine = myId != null && author.optString("id") == myId,
            )
        }.filter { it.text.isNotBlank() }
    }

    /** Добавить комментарий к публикации. */
    suspend fun addComment(tokens: TokenStore, postId: String, text: String) {
        val form = "text=" + URLEncoder.encode(text, "UTF-8") + "&reply_to=&images="
        val result = apiRequest(tokens, "$API/post/$postId/comment/", "POST", form)
        if (result.code !in 200..299) error("Не удалось отправить комментарий (HTTP ${result.code})")
    }

    /** Поставить или снять реакцию (лайк). Возвращает актуальное состояние. */
    suspend fun toggleLike(tokens: TokenStore, postId: String, currentlyLiked: Boolean): LikeInfo {
        val method = if (currentlyLiked) "DELETE" else "PUT"
        val result = apiRequest(tokens, "$API/post/$postId/like/", method, "")
        if (result.code !in 200..299 && result.code != 304) {
            error("Реакция: HTTP ${result.code}")
        }
        val data = runCatching { JSONObject(result.body).optJSONObject("data") }.getOrNull()
        val count = data?.optInt("likes", -1)
            ?.takeIf { it >= 0 }
            ?: data?.optJSONObject("counter")?.optInt("likes", -1)
            ?: if (currentlyLiked) -1 else 1
        return LikeInfo(liked = !currentlyLiked, count = count)
    }

    private suspend fun currentUserId(tokens: TokenStore): String? {
        cachedUserId?.let { return it }
        val result = apiRequestOpt(tokens, "$API/user/") ?: return null
        if (result.code !in 200..299) return null
        val data = JSONObject(result.body).optJSONObject("data") ?: JSONObject(result.body)
        cachedUserId = data.optString("id").takeIf { it.isNotBlank() }
        return cachedUserId
    }

    @Volatile
    private var cachedUserId: String? = null

    /** GET/POST/PUT/DELETE с авторизацией; при 401 — один refresh токена и повтор. */
    private suspend fun apiRequest(
        tokens: TokenStore,
        url: String,
        method: String = "GET",
        formBody: String? = null,
    ): HttpResult {
        var token = try {
            tokens.validAccessToken()
        } catch (e: Exception) {
            Log.w(TAG, "нет валидного токена: ${e.message}")
            ""
        }
        var result = withContext(Dispatchers.IO) { http(url, method, token, formBody, tokens) }

        if (result.code == 401 && tokens.isLoggedIn) {
            token = try {
                tokens.refresh()
            } catch (e: Exception) {
                Log.w(TAG, "refresh не удался: ${e.message}")
                ""
            }
            result = withContext(Dispatchers.IO) { http(url, method, token, formBody, tokens) }
        }
        return result
    }

    private suspend fun apiRequestOpt(
        tokens: TokenStore,
        url: String,
        method: String = "GET",
        formBody: String? = null,
    ): HttpResult? = try {
        apiRequest(tokens, url, method, formBody)
    } catch (e: Exception) {
        Log.w(TAG, "$url -> ${e.message}")
        null
    }

    private fun http(
        url: String,
        method: String,
        token: String,
        formBody: String?,
        tokens: TokenStore,
    ): HttpResult {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 12_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", tokens.userAgent.ifBlank { "BoostyTV/0.3 AndroidTV" })
            if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
            if (formBody != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                outputStream.use { it.write(formBody.toByteArray(Charsets.UTF_8)) }
            }
        }
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            HttpResult(code, body)
        } finally {
            connection.disconnect()
        }
    }

    private fun parsePost(post: JSONObject): VideoItem? {
        val id = post.optString("id").takeIf { it.isNotBlank() }
            ?: post.optString("uuid").takeIf { it.isNotBlank() }
            ?: return null

        val title = post.optString("title").ifBlank { "Публикация Boosty" }
        val teaser = post.optString("teaser")
            .ifBlank { post.optString("description") }
            .ifBlank { "Открой публикацию, чтобы посмотреть подробности." }
        val text = post.optString("text")

        val hasAccess = post.optBoolean("has_access", true) &&
                post.optBoolean("hasAccess", true)
        val isLocked = !hasAccess ||
                post.optBoolean("is_locked", false) ||
                post.optString("access").contains("locked", ignoreCase = true)

        // 1) Основной путь: блоки data[] с type = ok_video -> playerUrls[]
        // 2) Запасной: поиск любых ссылок на видео по всему JSON
        val directVideo = extractVideoUrl(post) ?: findVideoUrlFallback(post)

        val counter = post.optJSONObject("counter")
        val likes = counter?.optInt("likes", -1)?.takeIf { it >= 0 }
            ?: post.optInt("likes", 0)
        val comments = counter?.optInt("comments", -1)?.takeIf { it >= 0 }
            ?: post.optInt("comments", 0)
        val liked = post.optBoolean("liked", false) ||
                post.optJSONObject("userLikeInfo")?.optBoolean("liked", false) == true

        return VideoItem(
            id = id,
            title = title,
            creator = post.optJSONObject("blog")?.optString("name")
                ?.ifBlank { null } ?: "Михеев и Павлов",
            description = if (text.isNotBlank() && teaser.length < 20) text else teaser,
            videoUrl = if (isLocked) null else directVideo,
            postUrl = "$BLOG_URL/posts/$id",
            accessLabel = when {
                isLocked -> "НУЖНА ПОДПИСКА"
                directVideo != null -> "ВИДЕО"
                else -> "ПУБЛИКАЦИЯ"
            },
            coverUrl = extractCoverUrl(post),
            isPrivate = isLocked || post.optBoolean("is_private", false),
            likesCount = if (liked) maxOf(likes, 1) else likes,
            commentsCount = comments,
        )
    }

    /** Обложка публикации (image-блоки или thumbnail видео). */
    private fun extractCoverUrl(post: JSONObject): String? {
        val blocks = post.optJSONArray("data")
        if (blocks != null) {
            for (i in 0 until blocks.length()) {
                val block = blocks.optJSONObject(i) ?: continue
                when (block.optString("type")) {
                    "image" -> {
                        val url = block.optString("urlTemplate")
                            .ifBlank { block.optString("fullSizeUrl") }
                            .ifBlank { block.optString("thumbnailUrl") }
                        if (url.isNotBlank()) return url
                    }
                    "ok_video" -> {
                        val thumb = block.optString("thumbnailUrl")
                            .ifBlank { block.optString("thumbnail") }
                        if (thumb.isNotBlank()) return thumb
                    }
                }
            }
        }
        val images = post.optJSONArray("images")
        if (images != null && images.length() > 0) {
            val first = images.optJSONObject(0)
            val url = first?.optString("large")
                ?.ifBlank { first.optString("middle") }
                ?.ifBlank { first.optString("small") }
            if (!url.isNullOrBlank()) return url
        }
        return post.optString("coverUrl").takeIf { it.isNotBlank() }
    }

    /** Достаёт ссылку на видео из блоков ok_video по приоритету качества. */
    private fun extractVideoUrl(post: JSONObject): String? {
        val blocks = post.optJSONArray("data") ?: return null
        for (i in 0 until blocks.length()) {
            val block = blocks.optJSONObject(i) ?: continue
            if (block.optString("type") != "ok_video") continue
            val urls = block.optJSONArray("playerUrls") ?: continue

            val byType = HashMap<String, String>()
            for (j in 0 until urls.length()) {
                val item = urls.optJSONObject(j) ?: continue
                val url = item.optString("url")
                if (url.isNotBlank()) byType[item.optString("type")] = url
            }
            QUALITY_ORDER.firstNotNullOfOrNull { byType[it] }?.let { return it }
            byType.values.firstOrNull()?.let { return it }
        }
        return null
    }

    /** Запасной вариант: ищет в JSON ссылки с видео-расширениями. */
    private fun findVideoUrlFallback(post: JSONObject): String? {
        val urls = mutableListOf<String>()
        collectUrls(post, urls)
        return urls.firstOrNull {
            val lower = it.lowercase()
            (lower.contains(".mp4") || lower.contains(".m3u8") || lower.contains(".mpd")) &&
                    !lower.contains("thumbnail") && !lower.contains("preview")
        }
    }

    private fun collectUrls(value: Any?, output: MutableList<String>, depth: Int = 0) {
        if (depth > 8 || output.size > 100) return
        when (value) {
            is JSONObject -> {
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val child = value.opt(key)
                    if (child is String &&
                        (key.contains("url", true) || key.contains("src", true) || key.contains("image", true))
                    ) {
                        if (child.startsWith("https://") || child.startsWith("http://")) output.add(child)
                    } else {
                        collectUrls(child, output, depth + 1)
                    }
                }
            }
            is JSONArray -> for (i in 0 until value.length()) collectUrls(value.opt(i), output, depth + 1)
        }
    }
}