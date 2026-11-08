package dev.supermux.desktop.platform

import dev.supermux.ui.platform.MicTrouble
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Whether a desktop has a microphone, and whether the OS keeps it from supermux (#62), read from what each OS says:
 * PipeWire's (or PulseAudio's) sources on Linux, Windows' privacy switches and audio endpoints in its registry.
 */
class MicPresenceTest {
    private fun node(mediaClass: String, name: String) =
        """{"id":1,"type":"PipeWire:Interface:Node","info":{"props":{"media.class":"$mediaClass","node.name":"$name"}}}"""

    // The box this was found on: Chrome Remote Desktop's virtual speaker, and nothing to record from.
    @Test fun aPipeWireWithOnlyASpeakerHasNoMicrophone() = assertEquals(
        false,
        pipeWireHasMic("""[{"id":0,"type":"PipeWire:Interface:Core","info":{}},${node("Audio/Sink", "chrome_remote_desktop_session")}]"""),
    )

    @Test fun aPipeWireSourceIsAMicrophone() {
        assertEquals(true, pipeWireHasMic("[${node("Audio/Sink", "speakers")},${node("Audio/Source", "alsa_input.pci-0000_00_1f.3.analog-stereo")}]"))
        // A noise-cancelling filter's source records too.
        assertEquals(true, pipeWireHasMic("[${node("Audio/Source/Virtual", "rnnoise_source")}]"))
    }

    // An app's own recording stream is not a microphone.
    @Test fun aRecordingStreamIsNoMicrophone() = assertEquals(false, pipeWireHasMic("[${node("Stream/Input/Audio", "arecord")}]"))

    @Test fun pipeWireOutputThatIsNoDumpTellsNothing() = assertNull(pipeWireHasMic("Failed to connect: Host is down"))

    // `pactl list short sources`: a sink's monitor records what plays, not a voice.
    @Test fun pulseAudioSourcesBesidesMonitorsAreMicrophones() {
        assertFalse(pulseHasMic("0\talsa_output.pci-0000_00_1f.3.analog-stereo.monitor\tmodule-alsa-card.c\ts16le 2ch 44100Hz\tSUSPENDED\n"))
        assertTrue(
            pulseHasMic(
                "0\talsa_output.pci-0000_00_1f.3.analog-stereo.monitor\tmodule-alsa-card.c\ts16le 2ch 44100Hz\tSUSPENDED\n" +
                    "1\talsa_input.usb-Blue_Yeti-00.analog-stereo\tmodule-alsa-card.c\ts16le 2ch 48000Hz\tIDLE\n",
            ),
        )
        assertFalse(pulseHasMic(""))
    }

    // Settings › Privacy › Microphone, as `reg query` prints its switches.
    @Test fun windowsSaysWhenItKeepsTheMicrophoneFromDesktopApps() {
        val key = "HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\CapabilityAccessManager\\ConsentStore\\microphone"
        assertFalse(windowsMicDenied("$key\r\n    Value    REG_SZ    Allow\r\n\r\n$key\\NonPackaged\r\n    Value    REG_SZ    Allow\r\n"))
        assertTrue(windowsMicDenied("$key\r\n    Value    REG_SZ    Allow\r\n\r\n$key\\NonPackaged\r\n    Value    REG_SZ    Deny\r\n"))
        // Never switched: no value at all, which is allowed.
        assertFalse(windowsMicDenied("ERROR: The system was unable to find the specified registry key or value.\r\n"))
    }

    // Its recording endpoints (`reg query …\MMDevices\Audio\Capture /s /v DeviceState`): 1 is a working one; 4 is
    // gone, 8 is a jack with nothing in it, 2 switched off.
    @Test fun windowsHasAMicrophoneOnlyWhenAnEndpointWorks() {
        val capture = "HKEY_LOCAL_MACHINE\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\MMDevices\\Audio\\Capture"
        fun endpoint(state: String) = "$capture\\{0.0.1.00000000}.{6f9b4a0e-1c2d-4e5f-8a9b-0c1d2e3f4a5b}\r\n    DeviceState    REG_DWORD    $state\r\n\r\n"
        assertEquals(true, windowsHasMic(endpoint("0x4") + endpoint("0x1") + "End of search: 2 match(es) found.\r\n"))
        assertEquals(false, windowsHasMic(endpoint("0x8") + endpoint("0x4") + endpoint("0x2") + "End of search: 3 match(es) found.\r\n"))
        // Never had one: the key isn't there at all.
        assertEquals(false, windowsHasMic("ERROR: The system was unable to find the specified registry key or value.\r\n"))
        assertNull(windowsHasMic("ERROR: Access is denied.\r\n"))
    }

    // None at all comes first: allowing a microphone that isn't there wouldn't help.
    @Test fun whatTheOverlaySaysFollowsWhatTheOsSaid() {
        assertEquals(MicTrouble.NONE_FOUND, micTroubleOf(hasMic = false, denied = false))
        assertEquals(MicTrouble.NONE_FOUND, micTroubleOf(hasMic = false, denied = true))
        assertEquals(MicTrouble.NOT_ALLOWED, micTroubleOf(hasMic = true, denied = true))
        assertEquals(MicTrouble.NOT_ALLOWED, micTroubleOf(hasMic = null, denied = true))
        assertEquals(MicTrouble.DIDNT_OPEN, micTroubleOf(hasMic = true, denied = false))
        // Couldn't tell: never a guess.
        assertEquals(MicTrouble.DIDNT_OPEN, micTroubleOf(hasMic = null, denied = null))
    }
}
