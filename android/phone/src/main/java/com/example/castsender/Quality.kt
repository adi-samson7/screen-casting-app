package com.example.castsender

/** A quality preset. [longSide] caps the longer edge of the captured screen, in pixels. */
data class Quality(val key: String, val label: String, val sub: String, val longSide: Int, val fps: Int, val bps: Int) {
    companion object {
        val ALL = listOf(
            Quality("smooth", "Smooth", "720p · 3 Mbps", 1280, 30, 3_000_000),
            Quality("balanced", "Balanced", "1080p · 6 Mbps", 1920, 30, 6_000_000),
            Quality("sharp", "Sharp", "1080p · 10 Mbps", 1920, 30, 10_000_000),
        )
        fun of(key: String?): Quality = ALL.firstOrNull { it.key == key } ?: ALL[1]
    }
}
