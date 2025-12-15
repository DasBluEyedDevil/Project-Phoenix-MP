@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.devil.phoenixproject.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.domain.model.HapticEvent
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collectLatest
import platform.AVFAudio.AVAudioPlayer
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation
import platform.AVFAudio.setActive
import platform.Foundation.NSBundle
import platform.UIKit.UIImpactFeedbackGenerator
import platform.UIKit.UIImpactFeedbackStyle
import platform.UIKit.UINotificationFeedbackGenerator
import platform.UIKit.UINotificationFeedbackType

private val log = Logger.withTag("HapticFeedbackEffect.ios")

/**
 * iOS implementation of HapticFeedbackEffect using UIKit haptic generators
 * and AVAudioPlayer for sound playback.
 *
 * Uses UIImpactFeedbackGenerator for workout events and UINotificationFeedbackGenerator
 * for completion/error states. Different haptic patterns are applied based on event type.
 * Sound files should be bundled in the iOS app as .caf or .m4a files.
 */
@Composable
actual fun HapticFeedbackEffect(hapticEvents: SharedFlow<HapticEvent>) {
    // Initialize sound players for each event type
    val soundPlayers = remember { IosSoundManager() }

    // Cleanup sound players when leaving composition
    DisposableEffect(Unit) {
        onDispose {
            soundPlayers.release()
        }
    }

    LaunchedEffect(hapticEvents) {
        hapticEvents.collectLatest { event ->
            // Play haptic feedback
            playHapticFeedback(event)
            // Play sound (if available)
            soundPlayers.playSound(event)
        }
    }
}

/**
 * Manages sound playback for iOS using AVAudioPlayer.
 * Loads sounds from the app bundle and plays them for workout events.
 */
private class IosSoundManager {
    private val players = mutableMapOf<HapticEvent, AVAudioPlayer?>()

    init {
        setupAudioSession()
        loadSounds()
    }

    private fun setupAudioSession() {
        try {
            val session = AVAudioSession.sharedInstance()
            session.setCategory(AVAudioSessionCategoryPlayback, null)
            session.setActive(true, null)
        } catch (e: Exception) {
            log.w { "Failed to setup audio session: ${e.message}" }
        }
    }

    private fun loadSounds() {
        // Map events to sound file names (without extension)
        val soundFiles = mapOf(
            HapticEvent.REP_COMPLETED to "beep",
            HapticEvent.WARMUP_COMPLETE to "beepboop",
            HapticEvent.WORKOUT_COMPLETE to "boopbeepbeep",
            HapticEvent.WORKOUT_START to "chirpchirp",
            HapticEvent.WORKOUT_END to "chirpchirp",
            HapticEvent.REST_ENDING to "restover"
            // ERROR has no sound
        )

        soundFiles.forEach { (event, fileName) ->
            players[event] = loadSound(fileName)
        }
    }

    private fun loadSound(fileName: String): AVAudioPlayer? {
        // Try different audio formats in order of preference
        val extensions = listOf("caf", "m4a", "wav", "mp3")

        for (ext in extensions) {
            val url = NSBundle.mainBundle.URLForResource(fileName, ext)
            if (url != null) {
                try {
                    val player = AVAudioPlayer(url, null)
                    player.prepareToPlay()
                    player.volume = 0.8f
                    log.d { "Loaded sound: $fileName.$ext" }
                    return player
                } catch (e: Exception) {
                    log.w { "Failed to load $fileName.$ext: ${e.message}" }
                }
            }
        }

        log.d { "Sound file not found: $fileName (tried: ${extensions.joinToString()})" }
        return null
    }

    fun playSound(event: HapticEvent) {
        // ERROR event has no sound
        if (event == HapticEvent.ERROR) return

        val player = players[event]
        if (player != null) {
            try {
                // Reset to beginning if already playing
                player.currentTime = 0.0
                player.play()
            } catch (e: Exception) {
                log.w { "Sound playback failed for $event: ${e.message}" }
            }
        }
    }

    fun release() {
        players.values.forEach { player ->
            try {
                player?.stop()
            } catch (e: Exception) {
                // Ignore cleanup errors
            }
        }
        players.clear()

        try {
            AVAudioSession.sharedInstance().setActive(
                false,
                AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation,
                null
            )
        } catch (e: Exception) {
            // Ignore cleanup errors
        }
    }
}

/**
 * Play haptic feedback based on event type
 */
private fun playHapticFeedback(event: HapticEvent) {
    try {
        when (event) {
            HapticEvent.REP_COMPLETED -> {
                // Light impact for each rep
                val generator = UIImpactFeedbackGenerator(UIImpactFeedbackStyle.UIImpactFeedbackStyleLight)
                generator.prepare()
                generator.impactOccurred()
            }
            HapticEvent.WARMUP_COMPLETE, HapticEvent.WORKOUT_COMPLETE -> {
                // Success notification for completions
                val generator = UINotificationFeedbackGenerator()
                generator.prepare()
                generator.notificationOccurred(UINotificationFeedbackType.UINotificationFeedbackTypeSuccess)
            }
            HapticEvent.WORKOUT_START, HapticEvent.WORKOUT_END -> {
                // Medium impact for start/end
                val generator = UIImpactFeedbackGenerator(UIImpactFeedbackStyle.UIImpactFeedbackStyleMedium)
                generator.prepare()
                generator.impactOccurred()
            }
            HapticEvent.REST_ENDING -> {
                // Warning notification when rest is ending
                val generator = UINotificationFeedbackGenerator()
                generator.prepare()
                generator.notificationOccurred(UINotificationFeedbackType.UINotificationFeedbackTypeWarning)
            }
            HapticEvent.ERROR -> {
                // Error notification
                val generator = UINotificationFeedbackGenerator()
                generator.prepare()
                generator.notificationOccurred(UINotificationFeedbackType.UINotificationFeedbackTypeError)
            }
        }
    } catch (e: Exception) {
        log.w { "Haptic feedback failed for $event: ${e.message}" }
    }
}
