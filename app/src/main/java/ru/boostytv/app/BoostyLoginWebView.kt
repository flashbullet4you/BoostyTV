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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
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
 * 1. WebView открывает сразу страницу входа boosty.to/signin (не главную).
 * 2. Если страница не загрузилась — автоматические повторные попытки с индикатором прогресса.
 * 3. На странице входа приложение само выбирает «Войти через VK ID»; id.vk.com показывает QR-код.
 * 4. Пользователь сканирует QR телефоном (приложение VK или браузер) и подтверждает вход.
 * 5. Сайт дописывает cookie "auth"/"_clientId"; приложение перехватывает их и сохраняет в [TokenStore].
 *
 * Поддерживаются popup-окна (onCreateWindow): вход через соцсеть может открываться во всплывающем
 * окне — оно показывается поверх основной страницы и закрывается, когда сайт его закроет.
 * Если на телевизоре установлено приложение VK, ссылки vk:// / vkapi:// открываются напрямую в нём.
 *
 * ВАЖНО: способ неофициальный. Имена cookie ("auth", "_clientId"), формат JSON и тексты кнопок
 * могут измениться; смотрите Logcat, тег BoostyLogin.
 */

private const val HOME_URL = "https://boosty.to/"
/**
 * Страница входа. Пробуем оба известных адреса: Boosty менял их со временем,
 * а на продвинутых аккаунтах /signin может редиректить не туда (в т.ч. на чужие
 * страницы) — тогда переключаемся на англоязычный вариант.
 */
private val LOGIN_URLS = listOf("https://boosty.to/signin", "https://boosty.to/login")
private const val COOKIE_HOST = "https://boosty.to"
private const val TAG = "BoostyLogin"
private const val POLL_INTERVAL_MS = 800L

/** Признак страницы входа по URL (оба варианта). */
private fun isLoginPage(url: String?): Boolean {
    val u = url?.lowercase().orEmpty()
    return u.contains("/signin") || u.contains("/login")
}

/**
 * Состояние автонавигации ко входу. Хранится отдельно от WebView, чтобы:
 * - не запускать цикл JS-кликов повторно после каждой пересборки Compose;
 * - понимать, на какой странице мы находимся (для текста подсказки внизу экрана).
 */
private class LoginNavState {
    var phase by mutableStateOf("loading")   // loading | home | login | vkid | popup
        private set
    var autoStarted = false                  // автоскролл/клики запущены хотя бы раз
    var retries = 0                          // попытки достучаться до boosty.to
    var loginUrlIndex = 0                    // текущий адрес страницы входа из LOGIN_URLS

    fun set(p: String) { if (phase != p) phase = p }

    /** Следующий кандидат адреса входа (/signin → /login); после конца списка — стоп. */
    fun advanceLoginUrl(): Boolean {
        if (loginUrlIndex + 1 >= LOGIN_URLS.size) return false
        loginUrlIndex++
        return true
    }
}

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
 * Шаги входа на странице /signin: закрыть cookie-баннер → выбрать «Войти через VK ID».
 * Возвращает "done", когда страница VK ID открылась — именно там сайт показывает QR-код.
 * Если по какой-то причине попали не на страницу входа (редирект на главную или на
 * чужую страницу авторизатора), возвращаемся ко входу напрямую по адресу /signin.
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
  if(url.indexOf('id.vk.com')>-1 || url.indexOf('oauth.vk.com')>-1) return 'done';
  if((document.body.innerText||'').indexOf('QR-код')>-1 ||
     (document.body.innerText||'').indexOf('Отсканируйте')>-1) return 'done';
  var c=byText(/^я согласен$/); if(c){ c.click(); return 'step'; }
  // На главной кликом «Войти» открываем окно входа, иначе — идём на /signin напрямую.
  if(location.pathname==='/' || location.pathname===''){
    var l=byText(/^войти$|^вход$|^log in$|^login$/); if(l){ l.click(); return 'step'; }
    location.href='https://boosty.to/signin'; return 'nav';
  }
  // Редирект на чужую страницу (не вход и не VK ID) — возвращаемся ко входу напрямую.
  if(url.indexOf('/signin')===-1 && url.indexOf('/login')===-1){
    location.href='https://boosty.to/signin'; return 'nav';
  }
  // Шаг: на странице входа выбираем способ «через VK ID».
  var vk=byText(/vk\s?id|вконтакте|vk\.ru/);
  if(vk){ vk.click(); return 'step'; }
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
 * Инжектирует D-pad-скрипт (TvFocus) в страницу и забирает Android-фокус на WebView,
 * чтобы стрелки ВЛЕВО/ВПРАВО и OK обрабатывались двухмерной навигацией, а не только
 * вертикальным скроллом. Вызывается при каждой загрузке страницы — после навигации
 * скрипт в странице теряется и нужен повторный впрыск.
 */
private fun attachDpad(view: WebView) {
    TvWebView.attachDpad(view)
}

/**
 * Раз в POLL_INTERVAL_MS пытается пройти шаги входа, пока не откроется страница VK ID с QR-кодом.
 * После «done» продолжает редкие проверки ещё несколько раз — вдруг окно перезагрузилось.
 * Цикл однократный на экран (флаг autoStarted) — пересборки Compose не плодят параллельные циклы.
 */
private fun autoOpenLogin(view: WebView, nav: LoginNavState, attempt: Int = 0) {
    if (attempt > 40) return
    view.evaluateJavascript(AUTO_OPEN_LOGIN_JS) { result ->
        when {
            result?.contains("done") == true -> {
                Log.d(TAG, "страница VK ID открыта, ждём сканирования QR ($attempt)")
                nav.set("vkid")
                if (attempt < 8) view.postDelayed({ autoOpenLogin(view, nav, attempt + 5) }, POLL_INTERVAL_MS * 3)
            }
            else -> {
                if (result?.contains("step") == true) nav.set("login")
                view.postDelayed({ autoOpenLogin(view, nav, attempt + 1) }, POLL_INTERVAL_MS)
            }
        }
    }
}

private fun startAutoLoginOnce(view: WebView, nav: LoginNavState) {
    if (nav.autoStarted) return
    nav.autoStarted = true
    autoOpenLogin(view, nav)
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
    val nav = remember { LoginNavState() }
    // Прогресс повторных попыток загрузки (-1 — страница открылась нормально).
    var loadProgress by remember { mutableStateOf(-1) }

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

                        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                            loadProgress = -1
                            val u = (url ?: "").lowercase()
                            nav.set(
                                when {
                                    u.contains("id.vk.com") || u.contains("oauth.vk.com") -> "vkid"
                                    isLoginPage(u) -> "login"
                                    u.startsWith(COOKIE_HOST) -> "home"
                                    else -> nav.phase
                                },
                            )
                        }

                        override fun onReceivedError(
                            view: WebView,
                            request: WebResourceRequest?,
                            error: android.webkit.WebResourceError?,
                        ) {
                            // Ошиблась главная рамка (а не картинка/шрифт) — чиним перезагрузкой.
                            if (request?.isForMainFrame == true) {
                                Log.w(TAG, "ошибка загрузки: ${error?.description} (${error?.errorCode}), перезапуск…")
                                retryLoad(view)
                            }
                        }

                        override fun onReceivedHttpError(
                            view: WebView,
                            request: WebResourceRequest?,
                            errorResponse: android.webkit.WebResourceResponse?,
                        ) {
                            if (request?.isForMainFrame == true) {
                                Log.w(TAG, "HTTP ${errorResponse?.statusCode}, перезапуск…")
                                retryLoad(view)
                            }
                        }

                        override fun onPageFinished(view: WebView, url: String?) {
                            Log.d(TAG, "загружено: $url")
                            loadProgress = -1
                            // D-pad (стрелки влево/вправо и OK) — после каждой загрузки страницы.
                            attachDpad(view)
                            if (url != null && url.startsWith(COOKIE_HOST)) {
                                // Редирект /signin на чужую страницу (например, профиль автора):
                                // пробуем следующий известный адрес входа; когда варианты кончатся,
                                // автоскрипт в JS сам вернёт нас на /signin.
                                if (!isLoginPage(url) && !url.equals(HOME_URL, ignoreCase = true) &&
                                    nav.advanceLoginUrl()) {
                                    Log.w(TAG, "$url — не страница входа, пробуем ${LOGIN_URLS[nav.loginUrlIndex]}")
                                    view.loadUrl(LOGIN_URLS[nav.loginUrlIndex])
                                    return
                                }
                                startAutoLoginOnce(view, nav)
                            } else if (url != null && (url.contains("id.vk.com") || url.contains("oauth.vk.com"))) {
                                nav.set("vkid")
                                Log.d(TAG, "страница VK ID: ждём QR")
                            }
                        }

                        private fun retryLoad(view: WebView) {
                            if (nav.retries >= 5) {
                                Log.e(TAG, "не удалось загрузить boosty.to после 5 попыток")
                                return
                            }
                            nav.retries++
                            view.postDelayed({
                                loadProgress = nav.retries
                                view.loadUrl(LOGIN_URLS[nav.loginUrlIndex])
                            }, 2_000L * nav.retries)
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
                                    if (url != null) nav.set("popup")
                                    // В popup-окне тоже нужен D-pad: стрелки и OK.
                                    attachDpad(view)
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
                    // двухмерную навигацию по элементам обеспечивает скрипт TvFocus,
                    // который впрыскивается при каждой загрузке страницы.
                    // Сразу страница входа — без главной ленты Boosty.
                    loadUrl(LOGIN_URLS[0])
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
            nav = nav,
            loadProgress = loadProgress,
        )
    }
}

/**
 * Подсказка внизу экрана: текст зависит от реального шага (загрузка → страница входа →
 * VK ID → ожидание подтверждения), а при проблемах с сетью показывает прогресс retries.
 */
@Composable
private fun LoginStatusOverlay(visible: Boolean, nav: LoginNavState, loadProgress: Int) {
    if (!visible) return
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (loadProgress > 0) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .padding(bottom = 12.dp)
                        .width(220.dp)
                        .height(6.dp),
                    color = Color(0xFFFF6B6B),
                    trackColor = Color(0x33FFFFFF),
                )
            }
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
                    Text(loginHintEmoji(nav.phase, loadProgress), fontSize = 22.sp)
                    Text(
                        loginHintText(nav.phase, loadProgress),
                        color = Color(0xFFE8E8EC),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

private fun loginHintEmoji(phase: String, loadProgress: Int): String = when {
    loadProgress > 0 -> "📡"
    phase == "vkid" || phase == "popup" -> "🔑"
    else -> "📱"
}

private fun loginHintText(phase: String, loadProgress: Int): String = when {
    loadProgress > 0 ->
        "Нет связи с boosty.to — повторная попытка $loadProgress из 5…"
    phase == "vkid" ->
        "Отсканируйте QR-код камерой телефона (приложение VK или камера) и подтвердите вход — " +
            "Boosty TV сохранит ваш аккаунт автоматически."
    phase == "popup" ->
        "Подтвердите вход в приложении VK или браузере на телефоне — " +
            "Boosty TV сохранит ваш аккаунт автоматически."
    phase == "login" ->
        "Выбираем «Войти через VK ID»… Если кнопка не нажалась, стрелками пульта " +
            "(вверх/вниз/влево/вправо) подсветите её и нажмите ОК."
    phase == "home" ->
        "Открываем страницу входа…"
    else ->
        "Загружаем boosty.to…"
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