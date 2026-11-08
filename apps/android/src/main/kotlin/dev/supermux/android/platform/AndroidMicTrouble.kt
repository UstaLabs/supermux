// Why the phone's mic won't open (#62): what the assistant overlay says instead of listening.
package dev.supermux.android.platform

import android.media.AudioDeviceInfo
import dev.supermux.ui.platform.MicTrouble

/** Inputs that don't record a voice: a tuner, another app's sound, the call line. */
private val NOT_A_VOICE = setOf(
    AudioDeviceInfo.TYPE_FM_TUNER,
    AudioDeviceInfo.TYPE_TV_TUNER,
    AudioDeviceInfo.TYPE_REMOTE_SUBMIX,
    AudioDeviceInfo.TYPE_TELEPHONY,
)

/**
 * Why the mic won't open, from whether RECORD_AUDIO is [allowed], whether the device has a microphone at all
 * ([hasMicFeature]) and the types of its audio [inputs] (null when they couldn't be read). None at all comes first:
 * allowing a microphone that isn't there wouldn't help.
 */
internal fun androidMicTrouble(allowed: Boolean, hasMicFeature: Boolean, inputs: List<Int>?): MicTrouble = when {
    !hasMicFeature || inputs?.all { it in NOT_A_VOICE } == true -> MicTrouble.NONE_FOUND
    !allowed -> MicTrouble.NOT_ALLOWED
    else -> MicTrouble.DIDNT_OPEN
}
