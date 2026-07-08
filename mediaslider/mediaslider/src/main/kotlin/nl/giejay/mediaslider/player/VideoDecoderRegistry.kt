package nl.giejay.mediaslider.player

import androidx.media3.common.Player
import java.util.WeakHashMap

/**
 * Remembers the most recently initialized video decoder per player, so the
 * stats overlay can show it even when toggled on mid-playback (the decoder
 * init event fires only once, before the overlay attaches its own listener).
 */
object VideoDecoderRegistry {
    private val names = WeakHashMap<Player, String>()

    @Synchronized
    fun record(player: Player, decoderName: String) {
        names[player] = decoderName
    }

    @Synchronized
    fun get(player: Player): String? = names[player]
}
