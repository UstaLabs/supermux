package dev.supermux.ui.chat

import dev.supermux.proto.ControlAction
import dev.supermux.proto.SlashCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `/` menu's model (card #56): what an empty "/" lists and in which sections, and how a query
 * ranks. Pure functions, shared by the chat composer and the New Session launcher.
 */
class SlashMenuModelTest {
    private fun agent(
        name: String,
        source: String? = null,
        plugin: String? = null,
        aliases: List<String> = emptyList(),
        description: String? = null,
        hint: String? = null,
    ) = SlashCommand(
        id = "agent:$name", family = "agent", name = name, description = description,
        argumentHint = hint, aliases = aliases, source = source, plugin = plugin,
    )

    private fun control(name: String) =
        SlashCommand(id = "control:$name", family = "control", name = name, action = ControlAction(kind = name))

    // Real names and shapes from Claude's list. The two mux skills carry no source: that is what the
    // live broker sends before it passes sources on, and the prefix alone must still group them.
    private val catalog = listOf(
        control("model"),
        control("stop"),
        agent("code-review", source = "builtin", aliases = listOf("review"), hint = "[pr]", description = "Review the current diff"),
        agent("usage", source = "builtin", aliases = listOf("cost", "stats"), description = "Show session cost"),
        agent("model", source = "builtin", description = "Set the AI model for Claude Code"),
        agent("superpowers:brainstorming", source = "plugin", plugin = "superpowers", aliases = listOf("brainstorming"), description = "Explore intent before building"),
        agent("superpowers:subagent-driven-development", source = "plugin", plugin = "superpowers", description = "Execute a plan with subagents"),
        agent("superpowers:test-driven-development", source = "plugin", plugin = "superpowers", description = "Write the test first"),
        agent("mux:ios-dev-on-linux", description = "Build an iOS app from Linux"),
        agent("mux:preview-broker", description = "Try a broker branch against the real app"),
        agent("anatomy", description = "Isometric figures, a reviewing aid"),
        agent("proj-cmd", source = "project"),
        agent("my-skill", source = "user"),
    )

    private fun names(query: String, commands: List<SlashCommand> = catalog) =
        slashMenu(query, commands, recents = emptyList(), agentLabel = "Claude")?.rows?.map { it.command.name }.orEmpty()

    // ── an empty "/" ─────────────────────────────────────────────────────────────

    @Test fun an_empty_slash_lists_everything_in_sections() {
        val menu = assertNotNull(slashMenu("/", catalog, recents = listOf("superpowers:brainstorming", "gone"), agentLabel = "Claude"))
        assertEquals(
            listOf("Recent", "supermux", "This project", "Your skills", "mux", "superpowers", "Claude built-in", "Other"),
            menu.sections.map { it.title },
        )
        // A recent command the agent no longer reports is left out.
        assertEquals(listOf("superpowers:brainstorming"), menu.sections[0].rows.map { it.command.name })
        assertEquals(listOf("model", "stop"), menu.sections[1].rows.map { it.command.name })
        assertEquals(listOf("code-review", "model", "usage"), menu.sections.first { it.key == "builtin" }.rows.map { it.command.name })
        assertEquals(listOf("anatomy"), menu.sections.last().rows.map { it.command.name })
        // Recent repeats a command; every other one is listed exactly once.
        assertEquals(catalog.size + 1, menu.rows.size)
    }

    @Test fun a_plugin_prefix_groups_its_commands_even_with_no_source() {
        val menu = assertNotNull(slashMenu("/", catalog, recents = emptyList(), agentLabel = "Claude"))
        val mux = menu.sections.first { it.title == "mux" }
        assertEquals("plugin:mux", mux.key)
        assertEquals(listOf("mux:ios-dev-on-linux", "mux:preview-broker"), mux.rows.map { it.command.name })
        assertEquals(3, menu.sections.first { it.key == "plugin:superpowers" }.rows.size)
        assertTrue(menu.sections.none { s -> s.key == "other" && s.rows.any { it.command.name.startsWith("mux:") } })
    }

    @Test fun a_skill_read_from_a_plugin_folder_joins_its_plugin_without_a_prefix() {
        val menu = assertNotNull(slashMenu("/", catalog + agent("browser", source = "plugin", plugin = "mux"), emptyList(), "Antigravity"))
        assertEquals(
            listOf("browser", "mux:ios-dev-on-linux", "mux:preview-broker"),
            menu.sections.first { it.key == "plugin:mux" }.rows.map { it.command.name },
        )
    }

    @Test fun control_commands_the_host_cannot_run_are_not_offered() {
        val menu = assertNotNull(slashMenu("/", catalog, emptyList(), "Claude") { it == "model" })
        assertEquals(listOf("model"), menu.sections.first { it.key == "supermux" }.rows.map { it.command.name })
    }

    // ── a query ──────────────────────────────────────────────────────────────────

    @Test fun word_starts_find_a_command_by_its_initials() {
        val found = names("/sdd")
        assertEquals("superpowers:subagent-driven-development", found.first())
        assertEquals(listOf(0, 9, 16), matchField("subagent-driven-development", "sdd")?.hits)
    }

    @Test fun letters_scattered_mid_word_do_not_match() {
        // "mod" used to match every mux:ios-dev-… (m…o…d across the name).
        assertNull(matchField("mux:ios-dev-on-linux", "mod"))
        assertEquals(listOf("model", "model"), names("/mod"))
    }

    @Test fun supermux_wins_a_tie_with_the_agents_own_command() {
        val first = assertNotNull(slashMenu("/model", catalog, emptyList(), "Claude")).rows.first().command
        assertNotNull(first.action)
    }

    @Test fun an_alias_finds_its_command_and_says_so() {
        val row = assertNotNull(slashMenu("/cost", catalog, emptyList(), "Claude")).rows.first()
        assertEquals("usage", row.command.name)
        assertEquals("cost", row.alias)
        assertEquals("review", assertNotNull(slashMenu("/rev", catalog, emptyList(), "Claude")).rows.first().alias)
    }

    @Test fun description_hits_come_last_under_their_own_label_and_only_at_a_word_start() {
        val commands = catalog + agent("x-cmd", description = "the previous run")
        val menu = assertNotNull(slashMenu("/revi", commands, emptyList(), "Claude"))
        assertEquals(listOf(null, "In descriptions"), menu.sections.map { it.title })
        assertEquals(listOf("anatomy"), menu.sections.last().rows.map { it.command.name })
        // "revi" sits inside "previous", not at a word's start: no match.
        assertTrue("x-cmd" !in menu.rows.map { it.command.name })
    }

    @Test fun a_query_nothing_matches_closes_the_menu() {
        assertNull(slashMenu("/zzzz", catalog, emptyList(), "Claude"))
        assertNull(slashMenu("no token here", catalog, emptyList(), "Claude"))
    }

    @Test fun a_recent_command_ranks_above_an_equal_one() {
        val commands = listOf(agent("plan-a"), agent("plan-b"))
        assertEquals("plan-a", assertNotNull(slashMenu("/plan", commands, emptyList(), "Claude")).rows.first().command.name)
        assertEquals("plan-b", assertNotNull(slashMenu("/plan", commands, listOf("plan-b"), "Claude")).rows.first().command.name)
    }

    // ── Recent and the token ─────────────────────────────────────────────────────

    @Test fun a_sent_message_counts_for_recent_by_name_or_alias() {
        assertEquals("usage", sentSlashCommand("/cost please", catalog)?.name)
        assertEquals("superpowers:brainstorming", sentSlashCommand("/brainstorming the menu", catalog)?.name)
        // supermux's own /model is not the agent's, and a slash mid-message is no command.
        assertEquals("agent:model", sentSlashCommand("/model", catalog)?.id)
        assertNull(sentSlashCommand("see /cost", catalog))
    }

    @Test fun recent_moves_a_pick_to_the_front_and_keeps_five() {
        assertEquals(listOf("c", "a", "b", "d", "e"), withRecent(listOf("a", "b", "c", "d", "e"), "c"))
        assertEquals(listOf("f", "a", "b", "c", "d"), withRecent(listOf("a", "b", "c", "d", "e"), "f"))
    }

    @Test fun the_token_start_is_where_its_slash_is() {
        assertEquals(6, activeSlashTokenStart("do it /re"))
        assertEquals(0, activeSlashTokenStart("/"))
        assertNull(activeSlashTokenStart("see https://example.com/path"))
    }

    @Test fun a_rows_description_is_its_first_clause() {
        assertEquals(
            "Use when you need to interact with a web browser",
            firstClause("Use when you need to interact with a web browser — opening URLs, clicking elements"),
        )
        assertEquals("Toggle fast mode (Opus 5.5)", firstClause("Toggle fast mode (Opus 5.5)"))
    }
}
