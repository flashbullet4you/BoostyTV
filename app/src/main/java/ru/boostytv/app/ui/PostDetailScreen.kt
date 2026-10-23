package ru.boostytv.app.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ru.boostytv.app.BoostyRepository
import ru.boostytv.app.PostComment
import ru.boostytv.app.UrlOpener
import ru.boostytv.app.VideoItem
import ru.boostytv.app.data.TokenStore

private val Bg = Color(0xFF101116)
private val Surface = Color(0xFF20232D)
private val Border = Color(0xFF343846)
private val Accent = Color(0xFFFFA66C)
private val Muted = Color(0xFF9DA3B4)

/**
 * Экран публикации: текст, реакции (лайк), комментарии и поле для нового комментария.
 * Все действия выполняются от имени вошедшего пользователя — то есть с его подпиской.
 */
@Composable
fun PostDetailScreen(
    tokens: TokenStore,
    post: VideoItem,
    onBack: () -> Unit,
    onOpenVideo: (VideoItem) -> Unit,
) {
    BackHandler(onBack = onBack)

    val appContext = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var comments by remember { mutableStateOf<List<PostComment>>(emptyList()) }
    // liked по умолчанию берём из счётчика публикации: если API вернул userLikeInfo —
    // пользователь уже ставил реакцию.
    var liked by remember { mutableStateOf(post.accessLabel != "НУЖНА ПОДПИСКА" && post.likesCount > 0) }
    var likesCount by remember { mutableStateOf(post.likesCount) }
    var loadingComments by remember { mutableStateOf(true) }
    var commentsError by remember { mutableStateOf<String?>(null) }
    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var actionMessage by remember { mutableStateOf<String?>(null) }
    var commentsRefreshKey by remember { mutableStateOf(0) }

    LaunchedEffect(post.id, commentsRefreshKey) {
        loadingComments = true
        commentsError = null
        try {
            comments = BoostyRepository.loadComments(tokens, post.id)
        } catch (e: Exception) {
            commentsError = "Комментарии недоступны: ${e.message}"
            comments = emptyList()
        } finally {
            loadingComments = false
        }
    }

    fun sendComment() {
        val text = input.trim()
        if (text.isEmpty() || sending) return
        scope.launch {
            sending = true
            try {
                BoostyRepository.addComment(tokens, post.id, text)
                input = ""
                actionMessage = "Комментарий отправлен"
                commentsRefreshKey++
            } catch (e: Exception) {
                actionMessage = "Не удалось отправить: ${e.message}"
            } finally {
                sending = false
            }
        }
    }

    fun toggleLike() {
        scope.launch {
            try {
                val info = BoostyRepository.toggleLike(tokens, post.id, liked)
                liked = info.liked
                if (info.count >= 0) likesCount = info.count
                else likesCount = (likesCount + if (info.liked) 1 else -1).coerceAtLeast(0)
                actionMessage = if (info.liked) "Реакция поставлена 👍" else "Реакция снята"
            } catch (e: Exception) {
                actionMessage = "Реакция не прошла: ${e.message}"
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Bg)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 52.dp, end = 42.dp, top = 30.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onBack) { Text("← Назад") }
                Spacer(Modifier.width(16.dp))
                Text(
                    post.title,
                    color = Color.White,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    modifier = Modifier.weight(1f),
                )
            }

            Text(post.creator, color = Muted, fontSize = 15.sp)
            Text(
                post.description,
                color = Color(0xFFD7DAE2),
                fontSize = 16.sp,
                maxLines = 6,
                modifier = Modifier.heightIn(max = 150.dp).focusable(),
            )

            // Действия по публикации: видео, лайк, открытие на Boosty.
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (post.videoUrl != null) {
                    Button(onClick = { onOpenVideo(post) }) { Text("▶ Смотреть видео") }
                }
                LikeButton(liked = liked, count = likesCount, onClick = ::toggleLike)
                OutlinedButton(onClick = { UrlOpener.open(appContext, post.postUrl) }) {
                    Text("Открыть на Boosty")
                }
                actionMessage?.let {
                    Text(it, color = Accent, fontSize = 14.sp, maxLines = 1)
                }
            }

            Text(
                when {
                    loadingComments -> "Загружаем комментарии…"
                    commentsError != null -> commentsError!!
                    else -> "Комментарии · ${comments.size}"
                },
                color = if (commentsError != null) Color(0xFFFFB08A) else Muted,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )

            LazyColumn(
                modifier = Modifier.weight(1f).focusable(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 8.dp),
            ) {
                items(comments, key = { it.id.ifBlank { it.text } }) { comment ->
                    CommentCard(comment)
                }
            }

            // Поле ввода: по фокусу автоматически поднимаем системную клавиатуру
            // Android TV (Leanback IME) — пользователю не нужно ничего подключать.
            // D-pad перемещает каретку, текст вводится прямо пультом.
            CommentInput(
                value = input,
                enabled = !sending && post.accessLabel != "НУЖНА ПОДПИСКА",
                onChange = { input = it },
                onSend = ::sendComment,
            )
        }
    }
}

@Composable
private fun LikeButton(liked: Boolean, count: Int, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, if (liked) Accent else Border),
        colors = CardDefaults.cardColors(containerColor = if (liked) Color(0xFF3A2415) else Surface),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (liked) "👍" else "👍🏻", fontSize = 16.sp)
            Text(
                if (liked) "Вы оценили · $count" else "Оценить · $count",
                color = if (liked) Accent else Color(0xFFD7DAE2),
                fontSize = 15.sp,
            )
        }
    }
}

@Composable
private fun CommentCard(comment: PostComment) {
    Card(
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, if (comment.isMine) Accent.copy(alpha = 0.5f) else Border),
        colors = CardDefaults.cardColors(containerColor = Surface),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    comment.authorName + if (comment.isMine) " (вы)" else "",
                    color = if (comment.isMine) Accent else Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.weight(1f))
                Text(comment.createdAt.take(10), color = Muted, fontSize = 12.sp)
            }
            Text(comment.text, color = Color(0xFFD7DAE2), fontSize = 15.sp, maxLines = 4)
        }
    }
}

@Composable
private fun CommentInput(value: String, enabled: Boolean, onChange: (String) -> Unit, onSend: () -> Unit) {
    val focusRequester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    val view = LocalView.current

    // Поддержка системной клавиатуры Android TV: как только поле получает фокус
    // (D-pad + OK на пульте), поднимаем SoftKeyboard — на ТВ это Leanback-клавиатура,
    // управляемая стрелками пульта. На устройствах с физической клавиатурой IME
    // просто не покажется, и ввод пойдёт с неё.
    LaunchedEffect(focused, enabled) {
        if (focused && enabled) {
            view.showSystemKeyboard()
        } else if (!focused) {
            view.hideSystemKeyboard()
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(if (enabled) Color(0xFF181A22) else Surface)
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            if (value.isEmpty()) {
                Text(
                    when {
                        !enabled -> "Комментарии недоступны без подписки"
                        focused -> "Введите текст с клавиатуры… (Done/Enter — отправить)"
                        else -> "Написать комментарий…  (OK — вызвать клавиатуру ТВ)"
                    },
                    color = Muted,
                    fontSize = 15.sp,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged { focused = it.isFocused },
                textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 15.sp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSend(); view.hideSystemKeyboard() }),
                cursorBrush = SolidColor(Accent),
            )
        }
        Button(
            onClick = {
                if (!focused && enabled) focusRequester.requestFocus()
                view.hideSystemKeyboard()
                onSend()
            },
            enabled = enabled && value.isNotBlank(),
        ) {
            Text(if (enabled) "Отправить" else "—")
        }
    }
}

/**
 * Поддержка системной клавиатуры Android TV: поднимаем/скрываем IME через
 * WindowInsetsController + InputMethodManager. На ТВ без физической клавиатуры
 * появляется встроенная Leanback-клавиатура, управляемая D-pad пульта.
 */
private fun android.view.View.showSystemKeyboard() {
    val imm = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
            as android.view.inputmethod.InputMethodManager
    // Для Compose-хостинга фокусированное поле — внутренний EditText ViewRootImpl'а;
    // windowToken текущего view гарантирует показ над активным окном.
    requestFocus()
    if (android.os.Build.VERSION.SDK_INT >= 30) {
        // Дублируем современным API: show по insets type работает даже когда view
        // ещё не успел стать focused-view окна (Compose AndroidViewHost).
        windowInsetsController?.show(android.view.WindowInsets.Type.ime())
    } else {
        @Suppress("DEPRECATION")
        imm.showSoftInput(this, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }
}

private fun android.view.View.hideSystemKeyboard() {
    val imm = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
            as android.view.inputmethod.InputMethodManager
    imm.hideSoftInputFromWindow(windowToken, 0)
    windowInsetsController?.hide(android.view.WindowInsets.Type.ime())
}
