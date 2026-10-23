package ru.boostytv.app

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ru.boostytv.app.data.TokenStore
import java.net.HttpURLConnection
import java.net.URL

/** Public-feed reader. Boosty's internal API is undocumented and may change. */
object BoostyRepository {
    private const val TAG = "BoostyRepository"
    private const val DEBUG_LOG_RAW_POST = true // поставьте false, когда разберётесь со структурой

    private const val BLOG = "miheevpavlov"
    private const val POSTS_URL = "https://api.boosty.to/v1/blog/$BLOG/post/?limit=30"
    private const val BLOG_URL = "https://boosty.to/$BLOG"

    /** Порядок предпочтения вариантов видео в playerUrls. */
    private val QUALITY_ORDER = listOf(
        "hls", "dash", "full_hd", "quad_hd", "ultra_hd", "high", "medium", "low", "tiny", "lowest"
    )

    private class HttpResult(val code: Int, val body: String)

    /**
     * Загружает посты. Токен берётся из [TokenStore] (при необходимости обновляется по refresh_token);
     * при ответе 401 делается одна попытка обновить токен и повторить запрос.
     */
    suspend fun loadPublicPosts(tokens: TokenStore): List<VideoItem> {
        var token = try {
            tokens.validAccessToken()
        } catch (e: Exception) {
            Log.w(TAG, "нет валидного токена: ${e.message}")
            ""
        }

        var result = withContext(Dispatchers.IO) { fetch(token, tokens) }

        if (result.code == 401 && tokens.isLoggedIn) {
            token = try {
                tokens.refresh()
            } catch (e: Exception) {
                Log.w(TAG, "refresh не удался: ${e.message}")
                ""
            }
            result = withContext(Dispatchers.IO) { fetch(token, tokens) }
        }

        if (result.code !in 200..299) error("Boosty вернул HTTP ${result.code}")

        val root = JSONObject(result.body)
        val posts = root.optJSONArray("data")
            ?: root.optJSONObject("data")?.optJSONArray("data")
            ?: root.optJSONArray("posts")
            ?: JSONArray()

        if (DEBUG_LOG_RAW_POST) {
            Log.d(TAG, "posts=${posts.length()}, token=${if (token.isBlank()) "нет" else "есть"}")
            posts.optJSONObject(0)?.let { Log.d(TAG, "first post: ${it.toString(2)}") }
        }

        return (0 until posts.length()).mapNotNull { index ->
            posts.optJSONObject(index)?.let { parsePost(it) }
        }
    }

    private fun fetch(token: String, tokens: TokenStore): HttpResult {
        val connection = (URL(POSTS_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", tokens.userAgent.ifBlank { "BoostyTV/0.2 AndroidTV" })
            if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
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

        val isLocked = !post.optBoolean("has_access", true) ||
                post.optBoolean("is_locked", false) ||
                post.optString("access").contains("locked", ignoreCase = true)

        // 1) Основной путь: блоки data[] с type = ok_video -> playerUrls[]
        // 2) Запасной: поиск любых ссылок на видео по всему JSON
        val directVideo = extractVideoUrl(post) ?: findVideoUrlFallback(post)

        return VideoItem(
            id = id,
            title = title,
            creator = "Михеев и Павлов",
            description = if (text.isNotBlank() && teaser.length < 20) text else teaser,
            videoUrl = if (isLocked) null else directVideo,
            postUrl = "$BLOG_URL/posts/$id",
            accessLabel = when {
                isLocked -> "НУЖНА ПОДПИСКА"
                directVideo != null -> "ВИДЕО"
                else -> "ПУБЛИКАЦИЯ"
            }
        )
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