package dev.supermux.android.platform

import android.media.AudioDeviceInfo
import dev.supermux.ui.platform.MicTrouble
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Why the phone's mic won't open (#62), as Android tells it: no input that records a voice, or RECORD_AUDIO not
 * granted. None at all comes first: allowing a microphone that isn't there wouldn't help.
 */
class AndroidMicTroubleTest {
    private val builtIn = AudioDeviceInfo.TYPE_BUILTIN_MIC

    @Test fun aMicThatIsNotAllowedAsksToAllowIt() =
        assertEquals(MicTrouble.NOT_ALLOWED, androidMicTrouble(allowed = false, hasMicFeature = true, inputs = listOf(builtIn)))

    // A TV box: no microphone feature, or only inputs that aren't a voice (a tuner, another app's sound, the call line).
    @Test fun noInputThatRecordsAVoiceIsNoMicrophone() {
        assertEquals(MicTrouble.NONE_FOUND, androidMicTrouble(allowed = true, hasMicFeature = false, inputs = emptyList()))
        assertEquals(
            MicTrouble.NONE_FOUND,
            androidMicTrouble(
                allowed = false, hasMicFeature = true,
                inputs = listOf(AudioDeviceInfo.TYPE_TV_TUNER, AudioDeviceInfo.TYPE_REMOTE_SUBMIX, AudioDeviceInfo.TYPE_TELEPHONY),
            ),
        )
    }

    @Test fun aHeadsetsMicCounts() = assertEquals(
        MicTrouble.DIDNT_OPEN,
        androidMicTrouble(allowed = true, hasMicFeature = true, inputs = listOf(AudioDeviceInfo.TYPE_WIRED_HEADSET)),
    )

    // There and allowed, and it still didn't open: no "allow", no "connect one".
    @Test fun thereAndAllowedSaysOnlyThatItDidntOpen() =
        assertEquals(MicTrouble.DIDNT_OPEN, androidMicTrouble(allowed = true, hasMicFeature = true, inputs = listOf(builtIn)))

    // The input list couldn't be read: the feature alone decides whether there is one.
    @Test fun unreadableInputsLeaveItToTheFeature() =
        assertEquals(MicTrouble.NOT_ALLOWED, androidMicTrouble(allowed = false, hasMicFeature = true, inputs = null))
}
