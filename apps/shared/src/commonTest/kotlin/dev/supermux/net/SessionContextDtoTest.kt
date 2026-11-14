package dev.supermux.net

import dev.supermux.proto.ContextUsageDto
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** #144: the broker's GET /sessions/<id>/context, as Claude's breakdown and a totals-only agent send it. */
class SessionContextDtoTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun claude_s_report_decodes_with_its_lists() {
        val body = """{"context":{"used":154000,"size":300000},
          "breakdown":{"used":154000,"size":300000,"threshold":267000,
            "categories":[{"id":"messages","name":"Messages","tokens":69400,"kind":"used"},{"id":"mcp_tools_deferred","name":"MCP tools (deferred)","tokens":58200,"kind":"deferred"}],
            "mcpTools":[{"server":"mux-shim","name":"message_session","tokens":1150,"loaded":true}],
            "mcpInstructions":[{"server":"mux-shim","tokens":961}],
            "skills":[{"name":"anatomy","tokens":327,"source":"userSettings"}],
            "memoryFiles":[{"path":"/h/CLAUDE.md","type":"User","tokens":76}]},
          "used":[{"name":"mcp__mux-shim__message_session","count":3},{"name":"Skill:superpowers:brainstorming","count":1}]}"""
        val dto = json.decodeFromString<SessionContextDto>(body)
        assertEquals(ContextUsageDto(154000, 300000), dto.context)
        assertEquals(267000L, dto.breakdown?.threshold)
        assertEquals("deferred", dto.breakdown?.categories?.get(1)?.kind)
        assertEquals(McpToolCostDto("mux-shim", "message_session", 1150, true), dto.breakdown?.mcpTools?.single())
        assertEquals(3, dto.used.first().count)
    }

    @Test fun a_totals_only_agent_has_no_breakdown() {
        val dto = json.decodeFromString<SessionContextDto>("""{"context":{"used":21851,"size":258400},"used":[]}""")
        assertNull(dto.breakdown)
        assertEquals(emptyList(), dto.used)
    }
}
