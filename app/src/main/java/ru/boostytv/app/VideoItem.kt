package ru.boostytv.app

data class VideoItem(
    val id: String,
    val title: String,
    val creator: String,
    val description: String,
    val videoUrl: String?,
    val postUrl: String,
    val accessLabel: String,
    val coverUrl: String? = null,
    val isPrivate: Boolean = false,
    val likesCount: Int = 0,
    val commentsCount: Int = 0,
)

/** Профиль пользователя Boosty, полученный из /v1/user. */
data class UserProfile(
    val name: String,
    val email: String?,
    val hasSubscriptions: Boolean,
)

/** Комментарий к публикации. */
data class PostComment(
    val id: String,
    val authorName: String,
    val text: String,
    val createdAt: String,
    val isMine: Boolean,
)

/** Реакция (лайк) на публикацию. */
data class LikeInfo(
    val liked: Boolean,
    val count: Int,
)

object DemoCatalog {
    private const val TEST_VIDEO =
        "https://storage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"

    val videos = listOf(
        VideoItem(
            id = "demo-1",
            title = "Демонстрационное видео",
            creator = "Boosty TV",
            description = "Проверка навигации и воспроизведения на Android TV.",
            videoUrl = TEST_VIDEO,
            postUrl = "https://boosty.to/miheevpavlov",
            accessLabel = "ТЕСТОВОЕ"
        )
    )
}
