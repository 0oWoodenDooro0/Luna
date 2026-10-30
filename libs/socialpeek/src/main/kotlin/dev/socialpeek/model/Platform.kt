package dev.socialpeek.model

enum class Platform(
    val displayName: String,
    val brandColorHex: Int = 0x5865F2,
    val defaultIconUrl: String? = null
) {
    FACEBOOK("Facebook", 0x1877F2),
    BILIBILI("Bilibili", 0x00AEEC),
    X("X (Twitter)", 0x1DA1F2),
    INSTAGRAM("Instagram", 0xE1306C),
    THREADS("Threads", 0x000000),
    YOUTUBE("YouTube", 0xFF0000),
    REDDIT(
        "Reddit",
        0xFF4500,
        "https://www.redditstatic.com/shreddit/assets/favicon/192x192.png"
    ),
    GENERIC("Generic", 0x5865F2);
}
