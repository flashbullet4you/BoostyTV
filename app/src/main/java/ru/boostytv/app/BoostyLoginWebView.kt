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
 * Страницы авторизации (свои и сторонние), на которых нужно НЕ вмешиваться:
 * здесь идёт обмен кодом на сессию, показывается QR или подтверждение входа.
 */
private fun isAuthPage(url: String?): Boolean {
    val u = url?.lowercase().orEmpty()
    if (u.isBlank()) return false
    if (isLoginPage(u)) return true
    if (u.contains("id.vk.com") || u.contains("oauth.vk.com") || u.contains("vk.com")) return true
    // oauth2/auth/callback — стадии обмена кода Boosty после возврата из VK ID
    return u.contains("boosty.to") &&
        (u.contains("oauth2") || u.contains("/auth") || u.contains("callback"))
}

/**
 * Признак «чужой» страницы автора: путь вида /username (не вход, не лента, не сервисный раздел).
 * Именно на такие страницы уводит редирект, когда сессия уже есть или Boosty некорректно
 * обрабатывает /signin в WebView.
 */
private fun looksLikeAuthorPage(url: String?): Boolean {
    val u = url?.lowercase().orEmpty()
    if (!u.startsWith(COOKIE_HOST)) return false
    val path = u.removePrefix(COOKIE_HOST).substringBefore("?").trim('/')
    if (path.isEmpty()) return false // главная
    val reserved = setOf(
        "signin", "login", "logout", "feed", "subscriptions", "billing",
        "settings", "search", "help", "terms", "privacy", "static", "api", "blog",
    )
    val first = path.substringBefore("/")
    return !reserved.contains(first)
}

/** Признак стадии завершения входа (обмен кодом на сессию) — страницу нельзя трогать. */
private fun isAuthExchangeStage(u: String?): Boolean {
    val s = u?.lowercase().orEmpty()
    return s.contains("boosty.to") &&
        (s.contains("oauth2") || s.contains("callback") || s.contains("/auth/"))
}

/**
 * Состояние автонавигации ко входу. Хранится отдельно от WebView, чтобы:
 * - не запускать цикл JS-кликов повторно после каждой пересборки Compose;
 * - понимать, на какой странице мы находимся (для текста подсказки внизу экрана).
 */
private class LoginNavState {
    var phase by mutableStateOf("loading")   // loading | home | login | vkid | popup | waitauth
        private set
    var autoStarted = false                  // автоскролл/клики запущены хотя бы раз
    var retries = 0                          // попытки достучаться до boosty.to
    var loginUrlIndex = 0                    // текущий адрес страницы входа из LOGIN_URLS
    /** Последняя страница, на которую нас не пускают из-за уже существующей сессии. */
    var blockedBySessionPath: String? = null
    /** Ссылка «Выйти», найденная на чужой странице (для выхода из чужой сессии). */
    var logoutHref: String? = null
    /** Время последней принудительной навигации — защита от дёрганья WebView. */
    var lastForcedNavAt = 0L

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
 *
 * ВАЖНО (защита от «дёрганья» и вылета на чужие страницы):
 * - Ни во что не вмешиваемся на страницах авторизации: id.vk.com / oauth.vk.com,
 *   а также на boosty.to на стадиях обмена кода (oauth2/callback) — там идёт
 *   завершение входа, любые наши навигации убьют процесс.
 * - Если Boosty редиректит /signin на страницу автора (уже есть чужая сессия в WebView),
 *   НЕ гоняем страницу по кругу: извлекаем ссылку «Выйти», кликаем её один раз и
 *   возвращаемся ко входу только после выхода.
 * - Принудительные переходы выполняются не чаще одного раза в 4 секунды.
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
  // Кнопка/ссылка «Войти через VK ID». Ищем в первую очередь элементы со ссылкой на
  // id.vk.com / oauth.vk.com — это надёжнее текстов (текст может быть разбит span-ами).
  function findVk(){
    var links=document.querySelectorAll('a[href*="id.vk.com"],a[href*="oauth.vk.com"],[data-provider*="vk"]');
    if(links.length) return links[0];
    return byText(/vk\s?id|вконтакте|vk\.ru|войти через vk/);
  }
  // Флаг «нажато ОК» ставит обработчик клавиш ниже; автоскрипт кликает VK ID только по нему.
  if(!window.__tvKeyH){
    window.__tvKeyH=1;
    document.addEventListener('keydown',function(e){
      if(e.keyCode===13 || e.key==='Enter' || e.key==='DPadCenter'){
        window.__tvOk=1; // следующий проход автоскрипта кликнет VK ID как «жест пользователя»
      }
    },true);
    // Помечаем реальные клики (например, нативный OK пульта на подсвеченной кнопке):
    // в течение 2 секунд после них автоскрипту разрешено выполнить переход по VK ID.
    document.addEventListener('click',function(){ window.__tvUserClickAt=Date.now(); },true);
  }
  var url=(location.href||'').toLowerCase();
  // Стадия завершения входа на Boosty (обмен кодом / callback) — не мешаем.
  if(url.indexOf('boosty.to')>-1 &&
     (url.indexOf('oauth2')>-1 || url.indexOf('callback')>-1 || url.indexOf('/auth/')>-1)) return 'waitauth';
  // Страница VK ID — QR-код показывается там, ничего не трогаем.
  if(url.indexOf('id.vk.com')>-1 || url.indexOf('oauth.vk.com')>-1 || url.indexOf('vk.com')>-1) return 'done';
  if((document.body.innerText||'').indexOf('QR-код')>-1 ||
     (document.body.innerText||'').indexOf('Отсканируйте')>-1) return 'done';
  // Если сессия уже есть (cookie auth с токенами) — больше ничего не кликаем и
  // никуда не переходим: приложение само подхватит токены и закроет экран входа.
  if(document.cookie.indexOf('auth=')>-1) return 'haveauth';
  var c=byText(/^я согласен$/); if(c){ c.click(); return 'step'; }
  // На главной кликом «Войти» открываем окно входа, иначе — идём на /signin напрямую.
  if(location.pathname==='/' || location.pathname===''){
    var l=byText(/^войти$|^вход$|^log in$|^login$/); if(l){ l.click(); return 'step'; }
    return 'nav';
  }
  // Страница автора (/username вместо входа): в WebView уже есть чужая сессия.
  // Пробуем найти «Выйти»; если вышли — вернёмся ко входу. Без цикла перезагрузок.
  var path=(location.pathname||'').replace(/^\//,'').toLowerCase();
  var reserved=['signin','login','logout','feed','subscriptions','billing','settings',
                'search','help','terms','privacy','static','api','blog'];
  if(path && reserved.indexOf(path.split('/')[0])===-1){
    var out=byText(/^выйти$|^выход$|^log ?out$|^sign ?out$/);
    if(out){
      var href=out.getAttribute('href')||'';
      return 'author|logout:'+(href?location.origin+href.replace(/^([^/]|\/\/)/,'/$1'):out.href);
    }
    return 'author';
  }
  // Редирект на иную сервисную страницу (не вход и не VK ID) — навигацию выполнит Kotlin.
  if(url.indexOf('/signin')===-1 && url.indexOf('/login')===-1){
    return 'nav';
  }
  // Шаг: на странице входа выбираем способ «через VK ID».
  // ВАЖНО (почему QR-код не появлялся): клик по кнопке VK ID выполняется только с
  // ЖЕСТОМ пользователя (нативный OK пульта или click, помеченный обработчиками ниже).
  // Программный клик из автоскрипта WebView считает «непользовательским» и popup
  // (id.vk.com с QR-кодом) либо не открывается вовсе, либо убивается следующей
  // перезагрузкой основной страницы. Поэтому: подсвечиваем кнопку + просим нажать ОК;
  // если жест уже был — кликаем один раз и больше к этой кнопке не притрагиваемся.
  var vk=findVk();
  if(vk){
    var gesture=(window.__tvOk||(window.__tvUserClickAt&&Date.now()-window.__tvUserClickAt<2000))?1:0;
    window.__tvOk=0;
    if(gesture && !window.__tvVkJumped){
      window.__tvVkJumped=1;   // один клик на экран — popup должен открыться сам
      window.__tvArmed=1;
      try{ vk.click(); }catch(e){}
      return 'step';
    }
    if(window.__tvVkJumped) return 'wait'; // уже кликали — ждём popup/VK ID, не мешаем
    try{ vk.focus(); }catch(e){}
    if(!window.__tvHinted){
      window.__tvHinted=1;
      var m=document.getElementById('tv-login-hint');
      if(!m){
        m=document.createElement('div');
        m.id='tv-login-hint';
        m.style.cssText='position:fixed;left:50%;bottom:140px;transform:translateX(-50%);'+
          'background:#141418;color:#fff;border:1px solid #ff6b6b;border-radius:12px;'+
          'padding:12px 20px;font:16px sans-serif;z-index:99999;text-align:center;max-width:80%';
        document.body.appendChild(m);
      }
      m.textContent='Нажмите ОК на пульте, чтобы войти через VK ID';
    }
    return 'hint';
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

/** Слабая ссылка на основной WebView экрана входа — для страхового цикла навигации. */
private var mainViewRef: java.lang.ref.WeakReference<WebView>? = null

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
 *
 * Фокус отдаём WebView только когда он сам является целью (main=true). Для popup-окна
 * это важно: иначе запрос фокуса у дочернего WebView перехватывает основной WebView,
 * забирает фокус себе и сбрасывает незавершённый вход (QR-код на id.vk.com исчезал).
 */
private fun attachDpad(view: WebView, main: Boolean = true) {
    TvWebView.attachDpad(view, requestFocus = main)
}

/**
 * Перед открытием VK ID чистим cookie Boosty в WebView: если в WebView осталась
 * чужая сессия (например, после предыдущих тестов), Boosty после возврата из VK ID
 * редиректит не на ленту, а на страницу «того человека». Очистка перед авторизацией
 * гарантирует, что новый auth-cookie будет единственным.
 */
private fun prepareFreshLoginAttempt() {
    BoostyLogin.clearAuthCookies()
}

/** Минимальный интервал между принудительными навигациями — защита от «дёрганья» страницы. */
private const val FORCED_NAV_COOLDOWN_MS = 4_000L

/**
 * Принудительный переход не чаще одного раза в FORCED_NAV_COOLDOWN_MS (защита от цикла
 * перезагрузок). Если цель — страница входа, а мы уже на ней (или на стадии обмена кодом),
 * навигация пропускается: именно такие «повторные loadUrl(/signin)» сбрасывали начатый
 * вход через VK ID и из-за них QR-код никогда не показывался.
 */
private fun forceNavigate(view: WebView, nav: LoginNavState, url: String) {
    val current = view.url.orEmpty().lowercase()
    if (isLoginPage(url)) {
        if (isAuthExchangeStage(current)) return // идёт обмен кодом — не убиваем вход
        if (isLoginPage(current)) return         // уже на странице входа — перезагрузка ни к чему
    }
    val now = System.currentTimeMillis()
    if (now - nav.lastForcedNavAt < FORCED_NAV_COOLDOWN_MS) return
    nav.lastForcedNavAt = now
    Log.d(TAG, "force navigate → $url")
    view.loadUrl(url)
}

/**
 * Проверяет, не завис ли процесс обмена кодом: если долго находимся на стадии
 * oauth2/callback/auth без появления токенов — возвращаемся на страницу входа
 * (не чаще cooldown), иначе пользователь остаётся на пустой/чужой странице.
 */
private fun recoverStalledAuth(view: WebView, nav: LoginNavState) {
    val u = (view.url ?: "").lowercase()
    val stalled = u.contains("boosty.to") &&
        (u.contains("oauth2") || u.contains("callback") || u.contains("/auth/"))
    if (!stalled) return
    forceNavigate(view, nav, LOGIN_URLS[nav.loginUrlIndex])
}

/**
 * Раз в POLL_INTERVAL_MS пытается пройти шаги входа, пока не откроется страница VK ID с QR-кодом.
 * После «done» продолжает редкие проверки ещё несколько раз — вдруг окно перезагрузилось.
 * Цикл однократный на экран (флаг autoStarted) — пересборки Compose не плодят параллельные циклы.
 * Все принудительные переходы идут через forceNavigate (throttle + одна цель за раз),
 * поэтому страница больше не перезагружается каждые 800 мс.
 */
private fun autoOpenLogin(view: WebView, nav: LoginNavState, attempt: Int = 0) {
    if (attempt > 60) return
    view.evaluateJavascript(AUTO_OPEN_LOGIN_JS) { result ->
        val r = result?.trim('"')?.replace("\\\"", "\"").orEmpty()
        when {
            r.contains("done") -> {
                Log.d(TAG, "страница VK ID открыта, ждём сканирования QR ($attempt)")
                nav.set("vkid")
                // Редкие проверки живости окна; цикл сам завершится по лимиту попыток.
                if (attempt < 12) view.postDelayed({ autoOpenLogin(view, nav, 50) }, POLL_INTERVAL_MS * 5)
            }
            r.contains("step") -> {
                // Клик по «Войти через VK ID» выполнен — popup с id.vk.com открывается сам.
                // Никаких навигаций основной страницы, иначе popup будет убит вместе с QR-кодом.
                nav.set("login")
                view.postDelayed({ autoOpenLogin(view, nav, attempt + 1) }, POLL_INTERVAL_MS * 2)
            }
            r.contains("hint") -> {
                // Кнопка «Войти через VK ID» найдена и подсвечена; клик — только по ОК на пульте.
                // Страницу НЕ перезагружаем, popup не убиваем — QR появится после клика.
                nav.set("login")
                view.postDelayed({ autoOpenLogin(view, nav, attempt + 1) }, POLL_INTERVAL_MS * 2)
            }
            r.contains("haveauth") -> {
                // Сессия уже установлена — ничего не трогаем, цикл cookie вот-вот сохранит токены.
                nav.set("waitauth")
                view.postDelayed({ autoOpenLogin(view, nav, 55) }, POLL_INTERVAL_MS * 3)
            }
            r.contains("waitauth") -> {
                // Идёт обмен кода / подтверждение входа — не трогаем страницу вообще.
                nav.set("waitauth")
                view.postDelayed({ autoOpenLogin(view, nav, attempt + 3) }, POLL_INTERVAL_MS * 2)
            }
            r.contains("author|logout:") -> {
                // Чужая активная сессия в WebView: выходим из неё ОДИН раз, затем идём ко входу.
                val href = r.substringAfter("logout:").substringBefore('"')
                nav.set("home")
                if (nav.logoutHref == null) {
                    Log.w(TAG, "найдена чужая сессия, выходим: $href")
                    nav.logoutHref = href
                    forceNavigate(view, nav, href)
                } else if (System.currentTimeMillis() - nav.lastForcedNavAt > FORCED_NAV_COOLDOWN_MS) {
                    nav.logoutHref = null
                    BoostyLogin.clearAuthCookies()
                    forceNavigate(view, nav, LOGIN_URLS[nav.loginUrlIndex])
                }
                view.postDelayed({ autoOpenLogin(view, nav, attempt + 1) }, POLL_INTERVAL_MS)
            }
            r.contains("author") -> {
                // Страница автора без кнопки выхода: НЕ перезагружаем по кругу —
                // просто раз в cooldown возвращаемся ко входу напрямую.
                nav.blockedBySessionPath = view.url
                nav.set("home")
                forceNavigate(view, nav, LOGIN_URLS[nav.loginUrlIndex])
                view.postDelayed({ autoOpenLogin(view, nav, attempt + 1) }, POLL_INTERVAL_MS * 2)
            }
            else -> {
                if (r.contains("step")) nav.set("login")
                if (r.contains("nav")) forceNavigate(view, nav, LOGIN_URLS[nav.loginUrlIndex])
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

    // Страховка: если страница зависла (например, редирект в чужой профиль или пустая
    // страница после возврата из VK ID), возвращаемся ко входу; если на boosty.to уже
    // появилась СВОЯ сессия (токены в cookie) — завершаем вход без дальнейшей навигации.
    LaunchedEffect(Unit) {
        while (true) {
            delay(3_000L)
            if (readAuthFromCookies() != null) continue // цикл проверки токенов сам сохранит их
            val view = mainViewRef?.get() ?: continue
            val mainUrl = view.url.orEmpty().lowercase()
            if (looksLikeAuthorPage(mainUrl)) {
                Log.w(TAG, "страховка: зависли на $mainUrl, возвращаемся ко входу")
                forceNavigate(view, nav, LOGIN_URLS[nav.loginUrlIndex])
            } else if (isAuthExchangeStage(mainUrl)) {
                recoverStalledAuth(view, nav)
            }
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    configure(this)
                    mainViewRef = java.lang.ref.WeakReference(this)
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
                                    u.startsWith(COOKIE_HOST) && looksLikeAuthorPage(u) -> "home"
                                    isAuthPage(u) -> "waitauth"
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
                            val u = url.orEmpty().lowercase()
                            if (u.startsWith(COOKIE_HOST)) {
                                // Стадия завершения входа (обмен кодом) — не трогаем страницу,
                                // токены подхватит цикл проверки cookie.
                                if (isAuthExchangeStage(u)) {
                                    nav.set("waitauth")
                                    return
                                }
                                // Редирект /signin на чужую страницу (например, профиль автора):
                                // пробуем следующий известный адрес входа ОДИН раз (throttle),
                                // дальше разберётся автоскрипт (поиск кнопки «Выйти»).
                                if (looksLikeAuthorPage(url)) {
                                    if (nav.advanceLoginUrl()) {
                                        Log.w(TAG, "$url — чужая страница, пробуем ${LOGIN_URLS[nav.loginUrlIndex]}")
                                        forceNavigate(view, nav, LOGIN_URLS[nav.loginUrlIndex])
                                    }
                                }
                                startAutoLoginOnce(view, nav)
                            } else if (u.contains("id.vk.com") || u.contains("oauth.vk.com") || u.contains("vk.com")) {
                                // Открылась страница VK ID (QR или подтверждение в приложении VK).
                                nav.set("vkid")
                                Log.d(TAG, "страница VK ID: ждём подтверждения")
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
                                override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                                    val pu = url.orEmpty().lowercase()
                                    when {
                                        pu.contains("id.vk.com") || pu.contains("oauth.vk.com") || pu.contains("vk.com") ->
                                            nav.set("vkid")
                                        pu.contains("boosty.to") &&
                                            (pu.contains("oauth2") || pu.contains("callback") || pu.contains("/auth/")) ->
                                            nav.set("waitauth")
                                    }
                                }
                                override fun onPageFinished(view: WebView, url: String?) {
                            Log.d(TAG, "popup загружено: $url")
                            if (url != null && nav.phase != "vkid" && nav.phase != "waitauth") nav.set("popup")
                            // В popup-окне тоже нужен D-pad: стрелки и OK.
                            // requestFocus=false — иначе фокус перехватит основной WebView
                            // экрана входа и перезагрузкой убьёт незавершённый вход с QR-кодом.
                            attachDpad(view, main = false)
                            view.postDelayed({ view.requestFocus() }, 150)
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
                    // Перед первым открытием очищаем auth-cookie: иначе старая чужая
                    // сессия в WebView уводит /signin на профиль «того человека».
                    prepareFreshLoginAttempt()
                    loadUrl(LOGIN_URLS[0])
                    post { requestFocus() }
                }
            },
            onRelease = {
                if (mainViewRef?.get() === it) mainViewRef = null
                it.destroy()
            },
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
    phase == "waitauth" -> "⏳"
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
    phase == "waitauth" ->
        "Завершаем вход… Подтвердите вход на телефоне, приложение сохранит аккаунт автоматически."
    phase == "login" ->
        "Стрелками пульта подсветите кнопку «Войти через VK ID» и нажмите ОК. " +
            "Появится QR-код — отсканируйте его телефоном."
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