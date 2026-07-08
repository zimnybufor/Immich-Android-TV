package nl.giejay.android.tv.immich.card

interface ICard {
    val title: String
    val description: String?
    val id: String
    val thumbnailUrl: String?
    val backgroundUrl: String?
    val selected: Boolean
    val isVideo: Boolean get() = false
    val durationLabel: String? get() = null
    val resolutionLabel: String? get() = null
}