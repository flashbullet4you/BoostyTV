package ru.boostytv.app.ui

import android.content.Context
import android.os.Message
import android.view.KeyEvent
import android.util.Log
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * D-pad для SPA-страниц.
 *
 * Сайт Boosty — одностраничное приложение: «LOG IN» и «I AGREE» — обычные
 * ссылки и кнопки без `tabindex`, поэтому штатная клавиатурная навигация WebView
 * до них не доходит — D-pad просто скроллит страницу.
 *
 * Схема: [TvWebView] перехватывает стрелки и OK и вызывает в страницу
 * `window.__tvKey(...)`; страница ведёт свой фокус по кликабельным элементам
 * (включая одно-origin фреймы — баннер согласия на cookie может быть во фрейме)
 * и кликает выбранный элемент.
 *
 * Навигация двумерная: от текущего элемента ищем ближайший в нужную сторону,
 * иначе прокручиваем страницу. Так D-pad работает как в обычных ТВ-приложениях,
 * а не только «вверх-вниз».
 *
 * Фрейм с чужого origin (виджет входа VK ID) внутрь не пускает: его `contentDocument`
 * недоступен странице. Тогда клавиши отдаются браузеру нативно — дальше он сам
 * переходит между полями и кнопками виджета.
 *
 * Логика живёт в странице, а не в Android-коде: элементы появляются позже
 * (cookie-баннер, форма VK ID), обработчики переустанавливать не нужно.
 */
private const val TV_FOCUS_SCRIPT = """
(function () {
  // Установка идемпотентна: скрипт можно вливать при каждой загрузке страницы.
  if (window.__tvKey) { return; }

  var SEL = 'a[href],button,input,select,textarea,[role="button"],[tabindex],[onclick],iframe';
  var KEYS = ['agree', 'соглас', 'принять', 'log in', 'войти'];
  var cur = null;

  function query(d, sel) {
    try { return d.querySelectorAll(sel); } catch (e) { return []; }
  }

  function docs() {
    var out = [];
    try { out.push(document); } catch (e) { }
    var frames = query(document, 'iframe');
    for (var i = 0; i < frames.length; i++) {
      try { var d = frames[i].contentDocument; if (d) { out.push(d); } } catch (e) { }
    }
    return out;
  }

  function visible(el) {
    try {
      if (el.disabled) { return false; }
      var r = el.getBoundingClientRect();
      if (r.width < 4 || r.height < 4) { return false; }
      var s = getComputedStyle(el);
      if (s.visibility === 'hidden' || s.display === 'none') { return false; }
      return true;
    } catch (e) { return false; }
  }

  function style(d) {
    try {
      if (d.getElementById('__tv_style')) { return; }
      var s = d.createElement('style');
      s.id = '__tv_style';
      s.textContent = '.__tv_focus{outline:3px solid #ffd54f !important;outline-offset:2px !important;}';
      (d.head || d.documentElement).appendChild(s);
    } catch (e) { }
  }

  function items() {
    var list = [];
    var ds = docs();
    for (var k = 0; k < ds.length; k++) {
      style(ds[k]);
      var found = query(ds[k], SEL);
      for (var i = 0; i < found.length; i++) {
        if (visible(found[i])) { list.push(found[i]); }
      }
      list = list.concat(consentItems(ds[k], list));
    }
    return list;
  }

  // Баннер согласия на cookie — часто кастомный div без role и tabindex.
  // Ищем по тексту среди «листьев» (у них нет дочерних элементов): клик по
  // тексту всплывает к родителю, у которого и стоит обработчик.
  function consentItems(d, known) {
    var out = [];
    var found = query(d, 'div,span,label,p,button');
    for (var i = 0; i < found.length; i++) {
      var el = found[i];
      try {
        if (el.children.length > 0) { continue; }
        var t = (el.textContent || '').trim().toLowerCase();
        if (!t || t.length > 40) { continue; }
        var hit = false;
        for (var k = 0; k < KEYS.length; k++) {
          if (t.indexOf(KEYS[k]) >= 0) { hit = true; break; }
        }
        if (!hit) { continue; }
        if (!visible(el)) { continue; }
        var dup = false;
        for (var j = 0; j < known.length; j++) { if (known[j] === el) { dup = true; break; } }
        if (!dup) { out.push(el); }
      } catch (e) { }
    }
    return out;
  }

  function center(el) {
    var r = el.getBoundingClientRect();
    return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
  }

  function highlight() {
    var list = items();
    for (var i = 0; i < list.length; i++) {
      try {
        if (list[i] === cur) { list[i].classList.add('__tv_focus'); }
        else { list[i].classList.remove('__tv_focus'); }
      } catch (e) { }
    }
  }

  function focus(el) {
    cur = el;
    try { el.scrollIntoView({ block: 'center', inline: 'nearest' }); } catch (e) { }
    try { el.focus({ preventScroll: true }); } catch (e) { }
    highlight();
  }

  function scrollPage(dir) {
    try {
      if (dir === 'down') { window.scrollBy(0, 400); }
      else if (dir === 'up') { window.scrollBy(0, -400); }
      else if (dir === 'right') { window.scrollBy(400, 0); }
      else { window.scrollBy(-400, 0); }
    } catch (e) { }
  }

  // Каретка в текстовом поле — влево-вправо двигают её, а не страницу.
  function caret(el, dir) {
    try {
      var s = el.selectionStart;
      var t = el.selectionEnd;
      if (s == null) { return; }
      var len = (el.value || '').length;
      if (dir === 'left') { el.setSelectionRange(Math.max(0, s - 1), Math.max(0, t - 1)); }
      else { el.setSelectionRange(Math.min(len, s + 1), Math.min(len, t + 1)); }
    } catch (e) { }
  }

  // Ближайший элемент в сторону: по главной оси — расстояние, по второй — отклонение.
  function move(dir) {
    var list = items();
    if (!list.length) { scrollPage(dir); return; }

    var from = -1;
    for (var i = 0; i < list.length; i++) { if (list[i] === cur) { from = i; break; } }
    if (from < 0) { focus(list[0]); return; }

    var a = center(list[from]);
    var best = -1;
    var bestScore = Infinity;
    for (var j = 0; j < list.length; j++) {
      if (j === from) { continue; }
      var b = center(list[j]);
      var dx = b.x - a.x;
      var dy = b.y - a.y;
      var score = Infinity;
      if (dir === 'down' && dy > 8) { score = dy * 3 + Math.abs(dx); }
      else if (dir === 'up' && dy < -8) { score = -dy * 3 + Math.abs(dx); }
      else if (dir === 'right' && dx > 8) { score = dx * 3 + Math.abs(dy); }
      else if (dir === 'left' && dx < -8) { score = -dx * 3 + Math.abs(dy); }
      if (score < bestScore) { bestScore = score; best = j; }
    }
    if (best < 0) { scrollPage(dir); return; }
    focus(list[best]);
  }

  // Клик по кнопке с понятным текстом — запасной путь, если элемент не попал
  // в список (кастомный div без role и tabindex).
  function clickText() {
    var ds = docs();
    for (var k = 0; k < ds.length; k++) {
      var found = query(ds[k], 'a[href],button,div,span,label,p');
      for (var i = 0; i < found.length; i++) {
        var el = found[i];
        try {
          var t = (el.textContent || '').trim().toLowerCase();
          if (!t || t.length > 40) { continue; }
          var hit = false;
          for (var q = 0; q < KEYS.length; q++) {
            if (t.indexOf(KEYS[q]) >= 0) { hit = true; break; }
          }
          if (!hit) { continue; }
          if (!visible(el)) { continue; }
          el.click();
          return true;
        } catch (e) { }
      }
    }
    return false;
  }

  function press() {
    if (cur && cur.isConnected) {
      // Фрейм с чужим origin: клик по нему ничего не делает, нужен нативный OK.
      var tag = (cur.tagName || '').toLowerCase();
      if (tag === 'iframe') { return 'native'; }
      try { cur.click(); } catch (e) { }
      return 'handled';
    }
    clickText();
    return 'handled';
  }

  // Автоподсветка первого элемента при загрузке страницы.
  (function () { var l = items(); if (l.length) focus(l[0]); })();

  // Возвращает 'native', если клавиши должен обработать сам браузер.
  window.__tvKey = function (dir) {
    var el = document.activeElement;
    var tag = el && el.tagName ? el.tagName.toLowerCase() : '';

    // Фокус внутри фрейма — виджет входа VK ID. Кнопки за его пределами нам
    // недоступны, навигацией внутри фрейма занимается сам браузер.
    if (tag === 'iframe') { return 'native'; }

    var typing = (tag === 'input' || tag === 'textarea' || tag === 'select');
    if (typing) {
      if (dir === 'left' || dir === 'right') { caret(el, dir); return 'handled'; }
      if (dir === 'ok') {
        try { el.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true })); } catch (e) { }
        try { if (el.form) { el.form.requestSubmit(); } } catch (e) { }
        return 'handled';
      }
    }

    if (dir === 'ok') { return press(); }
    move(dir);
    return 'handled';
  };
})();
"""

/**
 * WebView, который переводит D-pad в вызовы `window.__tvKey` внутри страницы.
 *
 * Фокус у него Android-овский: клавиши доходят до страницы, а решение (прокрутить,
 * перейти по фокусу или отдать клавишу браузеру) принимает скрипт в странице.
 */
class TvWebView(context: Context) : WebView(context) {
    companion object { var current: TvWebView? = null }

    /** Повторная отправка клавиши браузеру — защита от рекурсии. */
    private var redispatching = false

    init {
        isFocusable = true
        isFocusableInTouchMode = true

        // Вход открывается во всплывающем окне — без этого его просто нет.
        settings.setSupportMultipleWindows(true)
        settings.javaScriptCanOpenWindowsAutomatically = true
        webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message,
            ): Boolean {
                // Всплывающее окно ловим временным WebView и переносим его адрес
                // в основной — иначе окно входа открывается мимо экрана.
                val temp = WebView(context)
                temp.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest,
                    ): Boolean {
                        val url = request.url.toString()
                        if (url.startsWith("vk://") || url.startsWith("vkontakte://")) {
                            try {
                                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, request.url)
                                context.startActivity(intent)
                            } catch (e: Exception) { }
                            return true
                        }
                        loadIntoSelf(url)
                        return true
                    }

                    @Deprecated("Для API 23")
                    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                        if (url.startsWith("vk://") || url.startsWith("vkontakte://")) {
                            try {
                                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                                context.startActivity(intent)
                            } catch (e: Exception) { }
                            return true
                        }
                        loadIntoSelf(url)
                        return true
                    }
                }
                val transport = resultMsg.obj as WebView.WebViewTransport
                transport.webView = temp
                resultMsg.sendToTarget()
                return true
            }
        }
    }

    private fun loadIntoSelf(url: String) {
        if (url.isBlank() || url == "about:blank") return
        loadUrl(url)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        current = this
        requestFocus()
    }

    override fun onDetachedFromWindow() {
        if (current === this) current = null
        super.onDetachedFromWindow()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        // Возврат фокуса окну — снова забираем его, иначе D-pad уходит в Compose.
        if (hasWindowFocus) requestFocus()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val dir = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> "down"
            KeyEvent.KEYCODE_DPAD_UP -> "up"
            KeyEvent.KEYCODE_DPAD_LEFT -> "left"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "right"
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_A -> "ok"
            else -> null
        }
        Log.d("TvFocus","onKeyDown keyCode=$keyCode dir=$dir")
        if (dir == null) return super.onKeyDown(keyCode, event)
        // Повтор клавиши в браузер — отдаём ему нативную обработку.
        if (redispatching) return super.onKeyDown(keyCode, event)

        // Скрипт решает, что делать с клавишей: вести фокус самому или отдать
        // клавишу браузеру (фокус внутри фрейма с чужим origin — виджет VK ID).
        evaluateJavascript("window.__tvKey ? window.__tvKey('$dir') : 'native'") { result ->
            if (result != "\"native\"") return@evaluateJavascript
            redispatching = true
            dispatchKeyEvent(
                KeyEvent(
                    event.downTime,
                    event.eventTime,
                    KeyEvent.ACTION_DOWN,
                    keyCode,
                    0,
                ),
            )
            redispatching = false
        }
        return true
    }

    /** Включает управление D-pad на загруженной странице. */
    fun installFocus() {
        evaluateJavascript(TV_FOCUS_SCRIPT, null)
    }
}
