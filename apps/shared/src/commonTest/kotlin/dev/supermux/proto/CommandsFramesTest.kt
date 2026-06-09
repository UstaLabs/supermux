package dev.supermux.proto

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "type" }

class CommandsFramesTest {

    @Test fun round_trips_commands_changed_with_agent_command() {
        val raw = """{"type":"commands_changed","session":"s1","resolved":true,"commands":[{"id":"cmd-agent-1","family":"agent","name":"compact","sigil":"/","description":"Compact history","insertText":"/compact "}]}"""
        val frame = json.decodeFromString<ServerFrame>(raw)
        assertTrue(frame is ServerFrame.CommandsChanged)
        val f = frame as ServerFrame.CommandsChanged
        assertEquals("s1", f.session)
        assertTrue(f.resolved)
        assertEquals(1, f.commands.size)
        val cmd = f.commands[0]
        assertEquals("cmd-agent-1", cmd.id)
        assertEquals("agent", cmd.family)
        assertEquals("compact", cmd.name)
        assertEquals("/", cmd.sigil)
        assertEquals("Compact history", cmd.description)
        assertEquals("/compact ", cmd.insertText)
        assertNull(cmd.action)
        // re-encode and decode again
        val reEncoded = json.encodeToString(ServerFrame.serializer(), f)
        val decoded2 = json.decodeFromString<ServerFrame>(reEncoded)
        assertTrue(decoded2 is ServerFrame.CommandsChanged)
        assertEquals(f, decoded2)
    }

    @Test fun round_trips_commands_changed_with_control_command() {
        val raw = """{"type":"commands_changed","session":"s2","resolved":false,"commands":[{"id":"cmd-ctrl-1","family":"control","name":"mute","sigil":"/","description":"Mute this session","action":{"kind":"mute","muted":false}}]}"""
        val frame = json.decodeFromString<ServerFrame>(raw)
        assertTrue(frame is ServerFrame.CommandsChanged)
        val f = frame as ServerFrame.CommandsChanged
        assertEquals("s2", f.session)
        assertFalse(f.resolved)
        assertEquals(1, f.commands.size)
        val cmd = f.commands[0]
        assertEquals("cmd-ctrl-1", cmd.id)
        assertEquals("control", cmd.family)
        assertEquals("mute", cmd.name)
        assertNull(cmd.insertText)
        assertNotNull(cmd.action)
        assertEquals("mute", cmd.action!!.kind)
        assertEquals(false, cmd.action.muted)
        // re-encode and decode again
        val reEncoded = json.encodeToString(ServerFrame.serializer(), f)
        val decoded2 = json.decodeFromString<ServerFrame>(reEncoded)
        assertTrue(decoded2 is ServerFrame.CommandsChanged)
        assertEquals(f, decoded2)
    }

    @Test fun round_trips_commands_changed_with_mixed_commands() {
        val raw = """{"type":"commands_changed","session":"s3","resolved":true,"commands":[{"id":"cmd-agent-1","family":"agent","name":"clear","sigil":"/","insertText":"/clear "},{"id":"cmd-ctrl-1","family":"control","name":"kill","sigil":"/","description":"Kill session","action":{"kind":"kill"}}]}"""
        val frame = json.decodeFromString<ServerFrame>(raw)
        assertTrue(frame is ServerFrame.CommandsChanged)
        val f = frame as ServerFrame.CommandsChanged
        assertEquals(2, f.commands.size)
        assertEquals("agent", f.commands[0].family)
        assertEquals("control", f.commands[1].family)
        assertEquals("kill", f.commands[1].action?.kind)
        assertNull(f.commands[1].action?.muted)
    }

    @Test fun round_trips_snapshot_with_commands_and_commandsResolved() {
        val raw = """{
            "type":"snapshot",
            "sessions":[],
            "commands":{"s1":[{"id":"cmd-1","family":"agent","name":"compact","sigil":"/","insertText":"/compact "},{"id":"cmd-2","family":"control","name":"mute","sigil":"/","action":{"kind":"mute","muted":false}}]},
            "commandsResolved":{"s1":true}
        }"""
        val frame = json.decodeFromString<ServerFrame>(raw)
        assertTrue(frame is ServerFrame.Snapshot)
        val snap = frame as ServerFrame.Snapshot
        assertEquals(1, snap.commands.size)
        val cmds = snap.commands["s1"]!!
        assertEquals(2, cmds.size)
        assertEquals("agent", cmds[0].family)
        assertEquals("/compact ", cmds[0].insertText)
        assertEquals("control", cmds[1].family)
        assertEquals("mute", cmds[1].action?.kind)
        assertEquals(true, snap.commandsResolved["s1"])
    }

    @Test fun snapshot_defaults_commands_to_empty_when_absent() {
        val raw = """{"type":"snapshot","sessions":[]}"""
        val frame = json.decodeFromString<ServerFrame>(raw)
        assertTrue(frame is ServerFrame.Snapshot)
        val snap = frame as ServerFrame.Snapshot
        assertTrue(snap.commands.isEmpty())
        assertTrue(snap.commandsResolved.isEmpty())
    }

    @Test fun commands_changed_defaults_resolved_to_false_when_absent() {
        val raw = """{"type":"commands_changed","session":"s1","commands":[]}"""
        val frame = json.decodeFromString<ServerFrame>(raw)
        assertTrue(frame is ServerFrame.CommandsChanged)
        assertFalse((frame as ServerFrame.CommandsChanged).resolved)
    }
}
