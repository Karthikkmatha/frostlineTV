package tv.frostline.feature.player

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer

/** The app-wide single-player slot. Shared by every [PlayerViewModel], so two players can never coexist. */
@OptIn(UnstableApi::class)
fun createPlayerSlot(context: Context): ExclusiveSlot<ExoPlayer> {
    val app = context.applicationContext
    return ExclusiveSlot(
        create = {
            ExoPlayer.Builder(app)
                .setLoadControl(
                    DefaultLoadControl.Builder()
                        // Small buffers keep the live edge close; Twitch segments are ~2 s.
                        .setBufferDurationsMs(3_000, 12_000, 1_000, 2_000)
                        .build(),
                )
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    true,
                )
                .setHandleAudioBecomingNoisy(true)
                .build()
        },
        destroy = { it.release() },
    )
}
