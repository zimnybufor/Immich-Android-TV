package nl.giejay.android.tv.immich.card

data class Card(
    override val title: String,
    override val description: String?,
    override val id: String,
    override val thumbnailUrl: String?,
    override val backgroundUrl: String?,
    override var selected: Boolean = false,
    override val isVideo: Boolean = false,
    override val durationLabel: String? = null,
    override val resolutionLabel: String? = null) : ICard
