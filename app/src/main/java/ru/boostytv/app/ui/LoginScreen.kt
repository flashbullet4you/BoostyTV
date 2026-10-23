package ru.boostytv.app.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import ru.boostytv.app.BuildConfig
import ru.boostytv.app.data.TokenStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URLDecoder
import kotlin.coroutines.resume

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(
    tokens: TokenStore,
    onLoggedIn: () -> Unit,
) {
    var browserOpen by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Нажмите «Войти» и авторизуйтесь через VK ID") }
    val webViewHolder = remember { WebViewHolder() }

    LaunchedEffect(browserOpen) {
        if (!browserOpen) return@LaunchedEffect
        while (true) {
            if (extractTokens(tokens) ||
                webViewHolder.webView?.let { extractFromLocalStorage(it, tokens) } == true
            ) {
                onLoggedIn()
                return@LaunchedEffect
            }
            delay(1000)
        }
    }

    BackHandler(enabled = browserOpen) { browserOpen = false }

    if (browserOpen) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0E0E10)),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 40.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Вход в Boosty",
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = status,
                    color = Color(0xFF9A9AA0),
                    fontSize = 16.sp,
                )
            }

            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.userAgentString = BuildConfig.DESKTOP_UA
                        CookieManager.getInstance().setAcceptCookie(true)
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val url = request.url.toString()
                                if (url.startsWith("vk://") || url.startsWith("vkontakte://")) {
                                    try { (view.context).startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, request.url)) } catch (e: Exception) { }
                                    return true
                                }
                                return false
                            }
                            override fun onPageFinished(view: WebView, url: String) {
                                
                                status = "Войдите через VK ID"
                            }
                        }
                        webViewHolder.webView = this
                        loadUrl(BuildConfig.BOOSTY_SITE_URL)
                    }
                },
            )
        }
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        listOf(Color(0xFF1A0A0A), Color(0xFF0E0E10)),
                    ),
                ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 72.dp, vertical = 56.dp),
                horizontalArrangement = Arrangement.spacedBy(80.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = "BOOSTY",
                        color = Color.White,
                        fontSize = 72.sp,
                        fontWeight = FontWeight.Black,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "Посты подписок и видео — на телевизоре",
                        color = Color(0xFF9A9AA0),
                        fontSize = 22.sp,
                    )
                    Spacer(Modifier.height(40.dp))
                    Text(
                        text = status,
                        color = Color(0xFF9A9AA0),
                        fontSize = 17.sp,
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(top = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Button(
                        onClick = {
                            status = "Открываю Boosty…"
                            browserOpen = true
                        },
                        modifier = Modifier.width(280.dp),
                    ) {
                        Text("Войти", fontSize = 20.sp)
                    }
                    Text(
                        text = "OK — выбрать\n← → ↑ ↓ — навигация",
                        color = Color(0xFF6A6A72),
                        fontSize = 15.sp,
                    )
                }
            }
        }
    }
}

private class WebViewHolder {
    var webView: WebView? = null
}

private fun extractTokens(tokens: TokenStore): Boolean {
    val raw = CookieManager.getInstance().getCookie(BuildConfig.BOOSTY_SITE_URL) ?: return false
    val cookies = raw.split(";").mapNotNull { part ->
        val index = part.indexOf('=')
        if (index <= 0) return@mapNotNull null
        part.substring(0, index).trim() to part.substring(index + 1).trim()
    }.toMap()
    val authCookie = cookies["auth"] ?: return false
    return saveFromAuthJson(tokens, authCookie, cookies["_clientId"].orEmpty())
}

private suspend fun extractFromLocalStorage(webView: WebView, tokens: TokenStore): Boolean {
    val auth = evaluateJs(webView, "localStorage.getItem('auth')") ?: return false
    val clientId = evaluateJs(webView, "localStorage.getItem('_clientId')").orEmpty()
    return saveFromAuthJson(tokens, unquote(auth), unquote(clientId))
}

private suspend fun evaluateJs(webView: WebView, script: String): String? =
    suspendCancellableCoroutine { continuation ->
        webView.evaluateJavascript(script) { value ->
            if (continuation.isActive) continuation.resume(value) { _, _, _ -> }
        }
    }

private fun unquote(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.length < 2 || !trimmed.startsWith("\"") || !trimmed.endsWith("\"")) return trimmed
    return runCatching { JSONTokener(trimmed).nextValue().toString() }.getOrDefault(trimmed.drop(1).dropLast(1))
}

private fun saveFromAuthJson(tokens: TokenStore, rawAuth: String, clientId: String): Boolean {
    return runCatching {
        val decoded = URLDecoder.decode(rawAuth.replace("+", "%2B"), "UTF-8")
        val obj = JSONObject(decoded)
        val access = obj.optString("accessToken")
        val refresh = obj.optString("refreshToken")
        if (access.isBlank() || refresh.isBlank()) return false
        val expiresAt = obj.optLong("expiresAt")
        tokens.saveTokens(
            accessToken = access,
            refreshToken = refresh,
            expiresAt = if (expiresAt > 10_000_000_000L) expiresAt else expiresAt * 1000L,
            deviceId = clientId.ifBlank { null },
        )
        true
    }.getOrDefault(false)
}

