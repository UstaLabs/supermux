package dev.supermux.state

import dev.supermux.proto.LogEntry

/** The raw payload [dev.supermux.desktop.state.DesktopAppState.agentReplies] emits — an agent-
 *  reply `message_append` (`direction=="outbound" && op=="reply"`) for [session]. Defined here
 *  (not in the `state` package) because it's part of this module's public decision vocabulary. */
data class AgentReplyEvent(val session: String, val entry: LogEntry)
