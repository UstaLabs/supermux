package dev.supermux.state

import dev.supermux.proto.LogEntry

/** The raw payload [HostStore.agentReplies] emits — an agent-
 *  reply `message_append` (`direction=="outbound" && op=="reply"`) for [session]. Defined here
 *  because it's part of this module's public decision vocabulary. */
data class AgentReplyEvent(val session: String, val entry: LogEntry)
