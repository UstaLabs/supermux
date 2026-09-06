package dev.supermux.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `GET /usage` must decode LENIENTLY (cluster E6).
 *
 * Android used to render this endpoint through a hand-written parser whose every accessor
 * collapsed a missing / wrong-typed / null field to a default, so one bad field cost one row.
 * The typed decode replaced that parser, and without `coerceInputValues` an explicit `null` on any
 * non-nullable field (`codex.plan`, `windows[].label`, `credits.balance`, every Double/Boolean)
 * would throw — `runApi` would swallow it and the WHOLE screen would read "Unable to load usage
 * data." These pin the leniency in place.
 */
class BrokerApiUsageTest {

    private fun api(body: String): BrokerApi {
        val engine = MockEngine {
            respond(
                content = ByteReadChannel(body),
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        return BrokerApi("http://h", "tok", HttpClient(engine))
    }

    @Test fun explicit_nulls_on_non_nullable_fields_fall_back_to_their_defaults() = runTest {
        val usage = api(
            """
            {
              "claude": {
                "fiveHour": {"used": null, "resetsAt": null},
                "sevenDay": {"used": 40.0},
                "extraUsage": {"enabled": null, "monthlyLimit": null, "usedCredits": 10.0, "currency": null}
              },
              "codex": {
                "plan": null,
                "windows": [{"id": null, "used": null, "label": null, "windowSeconds": null}],
                "credits": {"hasCredits": null, "balance": null},
                "limitReached": null,
                "resetCredits": null
              },
              "cursor": {"totalPercentUsed": null, "spendAvailable": null},
              "opencode": {"sessions": null, "inputTokens": null, "totalCostUsd": null},
              "grok": {"plan": null, "percentUsed": null}
            }
            """.trimIndent(),
        ).usage()

        // Nothing threw: every provider still decoded, with its defaults where the broker sent null.
        assertEquals(0.0, usage.claude?.fiveHour?.used)
        assertEquals(40.0, usage.claude?.sevenDay?.used)
        assertEquals(false, usage.claude?.extraUsage?.enabled)
        assertEquals("", usage.codex?.plan)
        assertEquals("", usage.codex?.windows?.single()?.label)
        assertEquals("", usage.codex?.credits?.balance)
        assertEquals(false, usage.codex?.limitReached)
        assertEquals(0, usage.codex?.resetCredits)
        assertEquals(0.0, usage.cursor?.totalPercentUsed)
        assertEquals(0L, usage.opencode?.inputTokens)
        assertEquals("", usage.grok?.plan)
    }

    @Test fun a_null_error_string_does_not_abort_the_decode() = runTest {
        val usage = api("""{"errors": {"claude": null, "codex": "no api key"}}""").usage()
        assertNull(usage.errors["claude"])
        assertEquals("no api key", usage.errors["codex"])
    }

    @Test fun a_partial_payload_keeps_the_providers_it_does_have() = runTest {
        val usage = api("""{"claude": {"fiveHour": {"used": 12.0}}, "codex": null}""").usage()
        assertEquals(12.0, usage.claude?.fiveHour?.used)
        assertNull(usage.codex)
    }

    @Test fun unknown_fields_are_still_ignored() = runTest {
        val usage = api("""{"claude": {"fiveHour": {"used": 5.0}}, "brandNewProvider": {"x": 1}}""").usage()
        assertNotNull(usage.claude)
        assertTrue(usage.errors.isEmpty())
    }

    @Test fun the_codex_reset_result_is_lenient_too() = runTest {
        val r = api("""{"code": null, "windowsReset": null, "codex": {"plan": null}}""").redeemCodexReset()
        assertEquals("", r.code)
        assertEquals(0, r.windowsReset)
        assertEquals("", r.codex?.plan)
    }
}
