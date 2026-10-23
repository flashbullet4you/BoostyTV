package ru.boostytv.app

data class VideoItem(
    val id: String,
    val title: String,
    val creator: String,
    val description: String,
    val videoUrl: String?,
    val postUrl: String,
    val accessLabel: String
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
