package ru.boostytv.app.ui

import android.annotation.SuppressLint
import android.os.Message
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import org.json.JSONObject
import ru.boostytv.app.BuildConfig
import ru.boostytv.app.data.TokenStore
import java.net.URLDecoder

/**
 * Вход на Boosty через VK ID по QR-коду — внутри приложения, без внешнего браузера.
 *
 * Сценарий («каждый смотрит свои подписки со своего аккаунта»):
 * 1. WebView открывает boosty.to с десктопным User-Agent (в мобильной версии вход по QR недоступен).
 * 2. Приложение само нажимает «Войти», затем «Продолжить с VK ID»; страница VK ID показывает QR-код.
 * 3. Пользователь сканирует QR телефоном (приложение VK или браузер) и подтверждает вход.
 * 4. Сайт дописывает cookie "auth"/"_clientId"; приложение перехватывает их и сохраняет в [TokenStore].
 *
 * Поддерживаются popup-окна (onCreateWindow): вход через соцсеть может открываться во всплывающем
 * окне — оно показывается поверх основной страницы и закрывается, когда сайт его закроет.
 * Если на телевизоре установлено приложение VK, ссылки vk:// / vkapi:// открываются напрямую в нём.
 *
 * ВАЖНО: способ неофициальный. Имена cookie ("auth", "_clientId"), формат JSON и тексты кнопок
 * могут измениться; смотрите Logcat, тег BoostyLogin.
 */

private const val LOGIN_URL = "https://boosty.to/"
private const val COOKIE_HOST = "https://boosty.to"
private const val TAG = "BoostyLogin"
private const val POLL_INTERVAL_MS = 800L

/** Удаляет cookie авторизации Boosty — чтобы «Выйти» действительно завершал сессию на WebView. */
object BoostyLogin {
    fun clearAuthCookies() {
        CookieManager.getInstance().apply {
            setCookie(COOKIE_HOST, "auth=; Max-Age=0; path=/")
            setCookie(COOKIE_HOST, "_clientId=; Max-Age=0; path=/")
            removeSessionCookies { }
            flush()
        }
        Log.d(TAG, "cookie авторизации очищены")
    }
}

/**
 * Шаги входа: закрыть cookie-баннер → нажать «Войти» → выбрать вход через VK ID.
 * Каждый шаг выполняется только когда предыдущий завершён (ищем элементы по текстам),
 * поэтому повторные вызовы безопасны. Возвращает "done", когда страница VK ID открылась —
 * именно там сайт показывает QR-код для сканирования телефоном.
 */
private const val AUTO_OPEN_LOGIN_JS = """
(function(){
  function byText(re){
    var els=document.querySelectorAll('button,a,[role=button]');
    for(var i=0;i<els.length;i++){
      var t=(els[i].innerText||'').trim().toLowerCase();
      if(re.test(t)) return els[i];
    }
    return null;
  }
  var url=(location.href||'').toLowerCase();
  // Мы уже на странице VK ID (id.vk.com) — QR-код показывается там.
  if(url.indexOf('id.vk.com')>-1 || (url.indexOf('/auth')>-1 && url.indexOf('boosty')===-1)) return 'done';
  if((document.body.innerText||'').indexOf('QR-код')>-1 ||
     (document.body.innerText||'').indexOf('Отсканируйте')>-1) return 'done';
  var c=byText(/^я согласен$/); if(c){ c.click(); return 'step'; }
  // Шаг 3: в окне входа выбираем способ «через VK ID».
  var vk=byText(/vk\s?id|вконтакте|vk\.ru/);
  if(vk && byText(/^войти$/)){ vk.click(); return 'step'; }
  // Шаг 2: открываем само окно входа.
  if(!(document.body.innerText||'').includes('Вход и регистрация')){
    var l=byText(/^войти$/); if(l){ l.click(); return 'step'; }
  }
  return 'wait';
})()
"""

private class AuthCookies(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMs: Long,
    val deviceId: String?,
)

@SuppressLint("SetJavaScriptEnabled")
private fun configure(webView: WebView) {
    webView.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        // Тот же десктопный User-Agent, что использует TokenStore:
        // в мобильной версии браузера вход по QR недоступен.
        userAgentString = BuildConfig.DESKTOP_UA
        useWideViewPort = true
        loadWithOverviewMode = true
        javaScriptCanOpenWindowsAutomatically = true
        setSupportMultipleWindows(true)
        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
    }
    webView.isFocusable = true
    webView.isFocusableInTouchMode = true
    CookieManager.getInstance().apply {
        setAcceptCookie(true)
        setAcceptThirdPartyCookies(webView, true)
    }
}

/**
 * Раз в POLL_INTERVAL_MS пытается пройти шаги входа, пока не откроется страница VK ID с QR-кодом.
 * После «done» продолжает редкие проверки ещё несколько раз — вдруг окно перезагрузилось.
 */
private fun autoOpenLogin(view: WebView, attempt: Int = 0) {
    if (attempt > 40) return
    view.evaluateJavascript(AUTO_OPEN_LOGIN_JS) { result ->
        if (result?.contains("done") == true) {
            Log.d(TAG, "страница VK ID открыта, ждём сканирования QR ($attempt)")
            if (attempt < 8) view.postDelayed({ autoOpenLogin(view, attempt + 5) }, POLL_INTERVAL_MS * 3)
        } else {
            view.postDelayed({ autoOpenLogin(view, attempt + 1) }, POLL_INTERVAL_MS)
        }
    }
}

/** Открывает внешние схемы (vk://, vkapi://, http-ссылки на id.vk.com) вне WebView, если умеет. */
private fun openExternallyOrInPlace(view: WebView, url: android.net.Uri): Boolean {
    val scheme = url.scheme?.lowercase().orEmpty()
    val handled = when {
        scheme.startsWith("vk") -> {
            try {
                view.context.startActivity(
                    android.content.Intent(android.content.Intent.ACTION_VIEW, url)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                Log.d(TAG, "открыли externally: $scheme://…")
                true
            } catch (e: Exception) {
                Log.d(TAG, "нет приложения для $scheme://, оставляем в WebView")
                false // пусть WebView сам разбирается (например, покажет свой QR)
            }
        }
        else -> false
    }
    return handled
}

@Composable
fun BoostyLoginWebView(
    tokens: TokenStore,
    onLoggedIn: () -> Unit,
    modifier: Modifier = Modifier.fillMaxSize(),
) {
    val currentOnLoggedIn by rememberUpdatedState(onLoggedIn)
    var popup by remember { mutableStateOf<WebView?>(null) }
    var loggedIn by remember { mutableStateOf(false) }

    // Раз в POLL_INTERVAL_MS проверяем, появились ли токены после сканирования QR.
    LaunchedEffect(Unit) {
        var loggedOnce = false
        while (true) {
            val auth = readAuthFromCookies()
            if (auth != null) {
                CookieManager.getInstance().flush() // фиксируем cookie до перехода на главный экран
                tokens.saveTokens(
                    accessToken = auth.accessToken,
                    refreshToken = auth.refreshToken,
                    expiresAt = auth.expiresAtMs,
                    deviceId = auth.deviceId,
                )
                Log.d(TAG, "токены сохранены, deviceId=${if (auth.deviceId.isNullOrBlank()) "нет" else "есть"}")
                loggedIn = true
                currentOnLoggedIn()
                return@LaunchedEffect
            }
            if (!loggedOnce) {
                val names = CookieManager.getInstance().getCookie(COOKIE_HOST)
                    ?.split(";")?.map { it.substringBefore("=").trim() }
                Log.d(TAG, "cookies: $names")
                loggedOnce = true
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    // Кнопка «Назад» пульта закрывает зависшее popup-окно, не убивая весь экран входа.
    BackHandler(enabled = popup != null) {
        popup?.let { p ->
            popup = null
            p.destroy()
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    configure(this)
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val url = request.url
                            // vk:// и vkapi:// — попытка открыть приложение VK на телевизоре;
                            // если его нет, остаёмся в WebView (там показывается QR для телефона).
                            if (url.scheme?.lowercase()?.startsWith("vk") == true) {
                                return openExternallyOrInPlace(view, url)
                            }
                            return false
                        }
                        override fun onPageFinished(view: WebView, url: String?) {
                            Log.d(TAG, "загружено: $url")
                            if (url != null && url.startsWith(COOKIE_HOST)) autoOpenLogin(view)
                            else if (url != null && url.contains("id.vk.com")) Log.d(TAG, "страница VK ID: ждём QR")
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        // Всплывающие окна (например, вход через VK ID) показываем поверх страницы.
                        override fun onCreateWindow(
                            view: WebView,
                            isDialog: Boolean,
                            isUserGesture: Boolean,
                            resultMsg: Message,
                        ): Boolean {
                            val child = WebView(view.context)
                            configure(child)
                            child.layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            child.webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                    val url = request.url
                                    if (url.scheme?.lowercase()?.startsWith("vk") == true) {
                                        return openExternallyOrInPlace(view, url)
                                    }
                                    return false
                                }
                                override fun onPageFinished(view: WebView, url: String?) {
                                    Log.d(TAG, "popup загружено: $url")
                                }
                            }
                            child.webChromeClient = object : WebChromeClient() {
                                override fun onCloseWindow(window: WebView) {
                                    popup = null
                                    window.destroy()
                                }
                            }
                            (resultMsg.obj as WebView.WebViewTransport).webView = child
                            resultMsg.sendToTarget()
                            popup = child
                            return true
                        }
                    }
                    // На Android TV WebView принимает фокус с пульта (D-pad):
                    // навигация по элементам страницы работает стандартным способом.
                    loadUrl(LOGIN_URL)
                    post { requestFocus() }
                }
            },
            onRelease = { it.destroy() },
        )

        popup?.let { child ->
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { child.apply { requestFocus() } },
            )
        }

        // Статус входа + подсказка, чтобы пользователь у телевизора понимал, что делать с QR-кодом.
        LoginStatusOverlay(
            visible = !loggedIn,
            onPopup = popup != null,
        )
    }
}

/**
 * Простой статус: пока не открылось popup-окно — «готовим страницу VK ID»,
 * если открылось — «ждём подтверждения в приложении/браузере».
 */
@Composable
private fun LoginStatusOverlay(visible: Boolean, onPopup: Boolean) {
    if (!visible) return
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter,
    ) {
    Box(
        modifier = Modifier
            .padding(bottom = 24.dp, start = 48.dp, end = 48.dp)
            .background(
                Brush.horizontalGradient(
                    listOf(Color(0xCC1A0A0A), Color(0xE60E0E10)),
                ),
                shape = RoundedCornerShape(14.dp),
            )
            .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(if (onPopup) "🔑" else "📱", fontSize = 22.sp)
            Text(
                if (onPopup)
                    "Подтвердите вход в приложении VK или браузере на телефоне — " +
                        "Boosty TV сохранит ваш аккаунт автоматически."
                else
                    "Открываем страницу VK ID с QR-кодом… Если код не появился, " +
                        "выберите «Войти через VK ID» на пульте.",
                color = Color(0xFFE8E8EC),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
        }
    }
    }
}

/** Читает cookie Boosty: "auth" (JSON с токенами) и "_clientId" (идентификатор устройства). */
private fun readAuthFromCookies(): AuthCookies? {
    return try {
        val cookies = CookieManager.getInstance().getCookie(COOKIE_HOST) ?: return null
        val map = cookies.split(";")
            .map { it.trim() }
            .filter { it.contains("=") }
            .associate { it.substringBefore("=") to it.substringAfter("=") }

        val rawAuth = map["auth"] ?: return null
        val json = JSONObject(URLDecoder.decode(rawAuth, "UTF-8"))
        val access = json.optString("accessToken")
        val refresh = json.optString("refreshToken")
        if (access.isBlank() || refresh.isBlank()) return null

        // expiresAt может прийти в секундах или в миллисекундах.
        var expires = json.optLong("expiresAt", 0L)
        if (expires in 1..99_999_999_999L) expires *= 1000
        if (expires <= 0L) expires = System.currentTimeMillis() + 3_600_000L

        val deviceId = map["_clientId"]?.let { URLDecoder.decode(it, "UTF-8") }?.ifBlank { null }
        AuthCookies(access, refresh, expires, deviceId)
    } catch (e: Exception) {
        Log.w(TAG, "не удалось разобрать cookie auth: ${e.message}")
        null
    }
}