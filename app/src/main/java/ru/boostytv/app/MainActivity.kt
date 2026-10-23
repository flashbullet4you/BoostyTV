package ru.boostytv.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import ru.boostytv.app.data.TokenStore
import ru.boostytv.app.ui.BoostyLogin
import ru.boostytv.app.ui.BoostyLoginWebView
import ru.boostytv.app.ui.PostDetailScreen

private const val CHANNEL_URL = "https://boosty.to/miheevpavlov"

class MainActivity : ComponentActivity() {
    private val tokens by lazy { TokenStore(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                // Состояние входа реактивное: после логина экран переключается сам.
                var loggedIn by remember { mutableStateOf(tokens.isLoggedIn) }

                if (!loggedIn) {
                    BoostyLoginWebView(tokens = tokens, onLoggedIn = { loggedIn = tokens.isLoggedIn })
                } else {
                    HomeScreen(
                        tokens = tokens,
                        onLogout = {
                            tokens.clear()
                            BoostyLogin.clearAuthCookies()
                            loggedIn = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(tokens: TokenStore, onLogout: () -> Unit) {
    val context: Context = LocalContext.current
    var videos by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
    var profile by remember { mutableStateOf<UserProfile?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableStateOf(0) }
    var selected by remember { mutableStateOf<VideoItem?>(null) }

    // Пагинация ленты: курсор следующей страницы + состояние загрузки «ещё».
    var nextCursor by remember { mutableStateOf<String?>(null) }
    var hasMore by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var moreError by remember { mutableStateOf<String?>(null) }

    val listState = rememberLazyListState()

    LaunchedEffect(refreshKey) {
        loading = true
        error = null
        loadingMore = false
        moreError = null
        try {
            profile = BoostyRepository.loadCurrentUser(tokens)
            val page = BoostyRepository.loadFeedPosts(tokens)
            videos = page.items
            nextCursor = page.nextCursor
            hasMore = page.hasMore
            if (videos.isEmpty()) {
                error = "Пока не удалось получить список публикаций. Открой канал через кнопку ниже."
            }
        } catch (e: Exception) {
            error = "Не удалось загрузить каталог: ${e.message ?: "ошибка сети"}"
            videos = emptyList()
            hasMore = false
        } finally {
            loading = false
        }
    }

    // Доскроллили до предпоследней карточки — подгружаем следующую страницу.
    val nearEnd by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisible >= videos.size - 2 && videos.isNotEmpty()
        }
    }
    LaunchedEffect(nearEnd, videos.size, refreshKey) {
        if (nearEnd && hasMore && !loading && !loadingMore) {
            loadingMore = true
            moreError = null
            try {
                val page = BoostyRepository.loadFeedPage(tokens, cursor = nextCursor)
                val existing = videos.map { it.id }.toSet()
                videos = videos + page.items.filter { it.id !in existing }
                nextCursor = page.nextCursor
                hasMore = page.hasMore
            } catch (e: Exception) {
                moreError = "Не удалось догрузить ленту: ${e.message ?: "ошибка сети"}"
            } finally {
                loadingMore = false
            }
        }
    }

    // Экран выбранной публикации: комментарии, реакции, видео.
    selected?.let { post ->
        PostDetailScreen(
            tokens = tokens,
            post = post,
            onBack = { selected = null; refreshKey++ },
            onOpenVideo = { UrlOpener.openItem(context, it) },
        )
        return
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF101116)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(start = 52.dp, end = 42.dp, top = 34.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("boosty", color = Color(0xFFFF8A35), fontSize = 38.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.width(12.dp))
                Text("TV", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text(
                    profile?.name?.takeIf { it.isNotBlank() } ?: "MIHEEVPAVLOV",
                    color = Color(0xFF9DA3B4),
                    fontSize = 16.sp,
                    maxLines = 1,
                )
                Spacer(Modifier.width(14.dp))
                OutlinedButton(onClick = onLogout) { Text("Выйти") }
            }
            Text("Михеев и Павлов", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
            Text(
                when {
                    loading -> "Загружаем ваши подписки Boosty…"
                    error != null -> error!!
                    else -> "Публикации доступны вашей подписке · ${videos.size} элементов" +
                            if (profile?.hasSubscriptions == false) " (подписок не найдено — видны только публичные)" else ""
                },
                color = if (error != null) Color(0xFFFFB08A) else Color(0xFFB8BDCA),
                fontSize = 16.sp
            )

            if (videos.isNotEmpty()) {
                LazyRow(
                    state = listState,
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(videos, key = { it.id }) { video ->
                        // Открытие публикации: комментарии/лайки внутри приложения;
                        //Locked-посты сразу ведут на страницу подписки.
                        VideoCard(
                            video = video,
                            onClick = {
                                if (video.accessLabel == "НУЖНА ПОДПИСКА") {
                                    UrlOpener.openItem(context, video)
                                } else {
                                    selected = video
                                }
                            },
                        )
                    }
                    // Хвост ленты: индикатор подгрузки / кнопка ручного до borа / конец.
                    if (hasMore || loadingMore || moreError != null) {
                        item(key = "feed-tail") { FeedTailItem(hasMore, loadingMore, moreError) }
                    }
                }
            }

            Spacer(Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { refreshKey++ }, enabled = !loading) { Text("Обновить каталог") }
                Button(onClick = { UrlOpener.open(context, CHANNEL_URL) }) { Text("Открыть Boosty") }
                Text("v0.4.0 · вход через VK ID по QR", color = Color(0xFF858B9B), fontSize = 14.sp)
            }
            Text(
                "Выберите публикацию, чтобы смотреть видео, ставить реакции и оставлять комментарии. " +
                        "Приватные посты доступны, если ваш аккаунт подписан на канал. " +
                        "Лента подгружается автоматически при прокрутке вправо.",
                color = Color(0xFF858B9B),
                fontSize = 12.sp
            )
        }
    }
}

/** Хвост ленты в LazyRow: спиннер подгрузки, ошибка с повтором или отметка конца. */
@Composable
private fun FeedTailItem(hasMore: Boolean, loadingMore: Boolean, moreError: String?) {
    Box(
        modifier = Modifier.width(220.dp).height(188.dp).focusable(),
        contentAlignment = Alignment.Center,
    ) {
        when {
            loadingMore -> Column(
                verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxSize(),
            ) {
                CircularProgressIndicator(color = AccentOrange, modifier = Modifier.size(34.dp), strokeWidth = 3.dp)
                Text("Догружаем ленту…", color = Color(0xFF9DA3B4), fontSize = 14.sp)
            }
            moreError != null -> Card(
                onClick = { /* повтор произойдёт при следующем достижении хвоста */ },
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, Color(0xFF5A3A2A)),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2A1E18)),
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(moreError!!, color = Color(0xFFFFB08A), fontSize = 13.sp, maxLines = 4)
                    Text("Догрузится автоматически при прокрутке", color = Color(0xFF9DA3B4), fontSize = 11.sp)
                }
            }
            hasMore -> Text("→ ещё публикации", color = Color(0xFF9DA3B4), fontSize = 15.sp)
        }
    }
}

private val AccentOrange = Color(0xFFFFA66C)

@Composable
private fun VideoCard(video: VideoItem, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.width(300.dp).height(188.dp).focusable(),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Color(0xFF343846)),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF20232D))
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Обложка публикации поверх метки доступа; если картинки нет — остаётся текст.
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                if (video.coverUrl != null) {
                    AsyncImage(
                        model = video.coverUrl,
                        contentDescription = video.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(10.dp)),
                    )
                    Text(
                        video.accessLabel,
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xB3000000))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(video.accessLabel, color = AccentOrange, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text(video.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(video.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(video.creator, color = Color(0xFFB9BECA), fontSize = 13.sp, maxLines = 1)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    when {
                        video.videoUrl != null -> "▶  Смотреть"
                        video.isPrivate -> "🔒  Нужна подписка"
                        else -> "💬  Открыть публикацию"
                    },
                    color = AccentOrange,
                    fontSize = 13.sp,
                )
                Text("👍 ${video.likesCount}", color = Color(0xFF858B9B), fontSize = 13.sp)
                Text("💬 ${video.commentsCount}", color = Color(0xFF858B9B), fontSize = 13.sp)
            }
        }
    }
}
