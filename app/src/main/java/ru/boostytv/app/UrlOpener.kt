package ru.boostytv.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/**
 * Безопасное открытие ссылки: на Android TV часто нет браузера,
 * поэтому вместо падения/системной ошибки показываем понятное сообщение.
 */
object UrlOpener {
    fun open(context: Context, url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(
                context,
                "На устройстве нет браузера для открытия ссылки:\n$url",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /** Открывает плеер, если есть прямая ссылка на видео, иначе страницу публикации. */
    fun openItem(context: Context, item: VideoItem) {
        val videoUrl = item.videoUrl
        if (!videoUrl.isNullOrBlank()) {
            context.startActivity(
                Intent(context, PlayerActivity::class.java)
                    .putExtra(PlayerActivity.EXTRA_TITLE, item.title)
                    .putExtra(PlayerActivity.EXTRA_URL, videoUrl)
            )
        } else {
            open(context, item.postUrl)
        }
    }
}