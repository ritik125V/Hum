package com.bravemusic.player.data.feed

data class MediaItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String,
    val videoId: String?,
    val playlistId: String? = null
)

data class FeedSection(
    val id: String,
    val title: String,
    val items: List<MediaItem>
)
