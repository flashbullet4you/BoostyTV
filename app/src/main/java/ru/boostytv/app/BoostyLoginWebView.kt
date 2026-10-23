package ru.boostytv.app.ui

import android.annotation.SuppressLint
import android.os.Message
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import org.json.JSONObject
import ru.boostytv.app.BuildConfig
import ru.boostytv.app.data.TokenStore
import java.net.URLDecoder

/**
 * Вход на Boosty внутри приложения.
 *
 * Страница входа у Boosty — это окно поверх главной страницы boosty.to (отдельного адреса нет),
 * поэтому после загрузки мы сами нажимаем «Войти». Дальше пользователь выбирает VK, видит QR-код,
 * сканирует его телефоном, а приложение забирает токены из cookie и сохраняет в [TokenStore].
 *
 * Вход через соцсеть часто открывается во всплывающем окне, поэтому поддерживаются popup-окна
 * (onCreateWindow): они показываются поверх основной страницы и закрываются, когда сайт их закроет.
 *
 * ВАЖНО: способ неофициальный. Имена cookie ("auth", "_clientId"), формат JSON и тексты кнопок
 * могут измениться; смотрите Logcat, тег BoostyLogin.
 */

private const val LOGIN_URL = "https://boosty.to/"
private const val COOKIE_HOST = "https://boosty.to"
private const val TAG = "BoostyLogin"

/** Закрывает баннер cookie и нажимает «Войти». Возвращает "done", когда окно входа открыто. */
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
  var c=byText(/^я согласен$/); if(c) c.click();
  if((document.body.innerText||'').indexOf('Вход и регистрация')>-1) return 'done';
  var l=byText(/^войти$/); if(l){ l.click(); return 'done'; }
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

/** Раз в секунду пытается нажать «Войти», пока окно входа не откроется (страница рисуется на JS). */
private fun autoOpenLogin(view: WebView, attempt: Int = 0) {
    if (attempt > 15) return
    view.evaluateJavascript(AUTO_OPEN_LOGIN_JS) { result ->
        if (result?.contains("done") != true) {
            view.postDelayed({ autoOpenLogin(view, attempt + 1) }, 1000)
        } else {
            Log.d(TAG, "окно входа открыто")
        }
    }
}

@Composable
fun BoostyLoginWebView(
    tokens: TokenStore,
    onLoggedIn: () -> Unit,
    modifier: Modifier = Modifier.fillMaxSize(),
) {
    val currentOnLoggedIn by rememberUpdatedState(onLoggedIn)
    var popup by remember { mutableStateOf<WebView?>(null) }

    // Раз в секунду проверяем, появились ли токены после сканирования QR.
    LaunchedEffect(Unit) {
        var logged = false
        while (true) {
            val auth = readAuthFromCookies()
            if (auth != null) {
                tokens.saveTokens(
                    accessToken = auth.accessToken,
                    refreshToken = auth.refreshToken,
                    expiresAt = auth.expiresAtMs,
                    deviceId = auth.deviceId,
                )
                Log.d(TAG, "токены сохранены, deviceId=${if (auth.deviceId.isNullOrBlank()) "нет" else "есть"}")
                currentOnLoggedIn()
                return@LaunchedEffect
            }
            if (!logged) {
                val names = CookieManager.getInstance().getCookie(COOKIE_HOST)
                    ?.split(";")?.map { it.substringBefore("=").trim() }
                Log.d(TAG, "cookies: $names")
                logged = true
            }
            delay(1000)
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    configure(this)
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String?) {
                            Log.d(TAG, "загружено: $url")
                            if (url != null && url.startsWith(COOKIE_HOST)) autoOpenLogin(view)
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
                            child.webViewClient = WebViewClient()
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
                    loadUrl(LOGIN_URL)
                    requestFocus()
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