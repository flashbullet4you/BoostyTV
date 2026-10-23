package ru.boostytv.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.boostytv.app.data.TokenStore
import ru.boostytv.app.ui.BoostyLoginWebView

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
                    HomeScreen(tokens)
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(tokens: TokenStore) {
    val context: Context = LocalContext.current
    var videos by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableStateOf(0) }

    LaunchedEffect(refreshKey) {
        loading = true
        error = null
        try {
            videos = BoostyRepository.loadPublicPosts(tokens)
            if (videos.isEmpty()) {
                error = "Пока не удалось получить список публикаций. Открой канал через кнопку ниже."
            }
        } catch (e: Exception) {
            error = "Не удалось загрузить каталог: ${e.message ?: "ошибка сети"}"
            videos = emptyList()
        } finally {
            loading = false
        }
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
                Text("MIHEEVPAVLOV", color = Color(0xFF9DA3B4), fontSize = 14.sp)
            }
            Text("Михеев и Павлов", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
            Text(
                when {
                    loading -> "Загружаем публичные публикации Boosty…"
                    error != null -> error!!
                    else -> "Публикации канала · ${videos.size} элементов"
                },
                color = if (error != null) Color(0xFFFFB08A) else Color(0xFFB8BDCA),
                fontSize = 16.sp
            )

            if (videos.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(videos, key = { it.id }) { video ->
                        // Есть прямая ссылка на видео -> плеер, иначе безопасно открываем страницу
                        // (если браузера нет, UrlOpener покажет понятное сообщение).
                        VideoCard(video = video, onClick = { UrlOpener.openItem(context, video) })
                    }
                }
            }

            Spacer(Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { refreshKey++ }, enabled = !loading) { Text("Обновить каталог") }
                Button(onClick = { UrlOpener.open(context, CHANNEL_URL) }) { Text("Открыть Boosty") }
                Text("v0.2.0 · тестовая интеграция", color = Color(0xFF858B9B), fontSize = 14.sp)
            }
            Text(
                "Видео открывается в плеере только если API вернул прямой доступный поток. " +
                        "В остальных случаях публикация откроется на Boosty.",
                color = Color(0xFF858B9B),
                fontSize = 12.sp
            )
        }
    }
}

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
            modifier = Modifier.fillMaxSize().padding(18.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(video.accessLabel, color = Color(0xFFFFA66C), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(video.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 3)
                Text(video.creator, color = Color(0xFFB9BECA), fontSize = 14.sp)
            }
            Text(
                if (video.videoUrl != null) "▶  Смотреть" else "↗  Открыть публикацию",
                color = Color(0xFFFFA66C),
                fontSize = 14.sp
            )
        }
    }
}