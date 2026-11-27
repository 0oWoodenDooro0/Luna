package luna.mcp.server

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import luna.ledger.LedgerDatabase
import luna.ledger.LedgerRepository
import luna.ledger.LedgerService
import luna.mcp.data.McpDatabase
import luna.mcp.data.McpWebhookRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LedgerPluginTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun createPlugin(
        tempDir: File,
        allowedUserIds: Set<String> = emptySet(),
        timezone: ZoneId = ZoneId.of("Asia/Taipei"),
        defaultCurrency: String = "TWD",
        fixedNow: Instant = Instant.parse("2026-11-28T02:30:00Z"),
    ): LedgerPlugin {
        val db = LedgerDatabase.connect(File(tempDir, "ledger.db").absolutePath)
        val service = LedgerService(LedgerRepository(db), Clock.fixed(fixedNow, timezone))
        return LedgerPlugin(
            ledgerService = service,
            defaultCurrency = defaultCurrency,
            timezone = timezone,
            allowedUserIds = allowedUserIds,
        )
    }

    @Test
    fun `allowlist hides ledger tools on tools list and rejects unlisted callers on tools call`(
        @TempDir tempDir: File,
    ) = runTest {
        val plugin = createPlugin(tempDir, allowedUserIds = setOf("user-allowed"))
        val handler = McpProtocolHandler(plugins = listOf(plugin))

        val allowedListRaw =
            handler.handle(
                """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""",
                McpAuthContext(clientId = "cli-1", ownerId = "user-allowed"),
            )!!
        val allowedTools =
            json
                .parseToJsonElement(allowedListRaw)
                .jsonObject["result"]!!
                .jsonObject["tools"]!!
                .jsonArray
        assertEquals(6, allowedTools.size)

        val deniedListRaw =
            handler.handle(
                """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""",
                McpAuthContext(clientId = "cli-2", ownerId = "user-other"),
            )!!
        val deniedTools =
            json
                .parseToJsonElement(deniedListRaw)
                .jsonObject["result"]!!
                .jsonObject["tools"]!!
                .jsonArray
        assertTrue(deniedTools.isEmpty(), "Unlisted user must not see ledger_* tools")

        val nullOwnerListRaw =
            handler.handle(
                """{"jsonrpc":"2.0","id":3,"method":"tools/list"}""",
                McpAuthContext(clientId = "cli-3", ownerId = null),
            )!!
        val nullOwnerTools =
            json
                .parseToJsonElement(nullOwnerListRaw)
                .jsonObject["result"]!!
                .jsonObject["tools"]!!
                .jsonArray
        assertTrue(nullOwnerTools.isEmpty(), "Caller with null ownerId must not see ledger_* tools")

        val deniedCallRaw =
            handler.handle(
                """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"ledger_list_categories","arguments":{}}}""",
                McpAuthContext(clientId = "cli-2", ownerId = "user-other"),
            )!!
        val deniedCall = json.parseToJsonElement(deniedCallRaw).jsonObject
        assertNotNull(deniedCall["error"], "Unlisted user calling ledger tool must receive JSON-RPC error")
    }

    @Test
    fun `add_entry, list_entries, and list_categories respect timezone calendar ranges and default currency`(
        @TempDir tempDir: File,
    ) = runTest {
        val plugin = createPlugin(tempDir, timezone = ZoneId.of("Asia/Taipei"), defaultCurrency = "TWD")
        val handler = McpProtocolHandler(plugins = listOf(plugin))
        val auth = McpAuthContext(clientId = "cli-1", ownerId = "user-1")

        // 2026-11-28T00:30:00+08:00 is 2026-11-27T16:30:00Z in UTC, but 2026-11-28 in Asia/Taipei
        val addRaw =
            handler.handle(
                """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"ledger_add_entry","arguments":{"type":"EXPENSE","amount":"150","category":"Food","payment_method":"Card","note":"Midnight ramen","occurred_at":"2026-11-28T00:30:00+08:00"}}}""",
                auth,
            )!!
        val addResult = json.parseToJsonElement(addRaw).jsonObject["result"]!!.jsonObject
        assertFalse(addResult["isError"]!!.jsonPrimitive.boolean, "Valid add_entry must succeed")
        val addPayload =
            json
                .parseToJsonElement(
                    addResult["content"]!!
                        .jsonArray[0]
                        .jsonObject["text"]!!
                        .jsonPrimitive.content,
                ).jsonObject
        assertEquals("TWD", addPayload["currency"]!!.jsonPrimitive.content)
        assertEquals("150", addPayload["amount"]!!.jsonPrimitive.content)
        assertEquals("2026-11-28T00:30:00+08:00", addPayload["occurredAt"]!!.jsonPrimitive.content)

        // Querying 2026-11-28 in Asia/Taipei must include 2026-11-28T00:30:00+08:00
        val listRaw =
            handler.handle(
                """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"ledger_list_entries","arguments":{"from":"2026-11-28","to":"2026-11-28"}}}""",
                auth,
            )!!
        val listResult = json.parseToJsonElement(listRaw).jsonObject["result"]!!.jsonObject
        assertFalse(listResult["isError"]!!.jsonPrimitive.boolean, "Valid list_entries must succeed")
        val listPayload =
            json
                .parseToJsonElement(
                    listResult["content"]!!
                        .jsonArray[0]
                        .jsonObject["text"]!!
                        .jsonPrimitive.content,
                ).jsonObject
        assertEquals(1, listPayload["count"]!!.jsonPrimitive.int)

        val categoriesRaw =
            handler.handle(
                """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"ledger_list_categories","arguments":{}}}""",
                auth,
            )!!
        val catResult = json.parseToJsonElement(categoriesRaw).jsonObject["result"]!!.jsonObject
        assertFalse(catResult["isError"]!!.jsonPrimitive.boolean, "Valid list_categories must succeed")
        val catPayload =
            json
                .parseToJsonElement(
                    catResult["content"]!!
                        .jsonArray[0]
                        .jsonObject["text"]!!
                        .jsonPrimitive.content,
                ).jsonObject
        val categories = catPayload["categories"]!!.jsonArray
        assertEquals(1, categories.size)
        assertEquals("food", categories[0].jsonObject["category"]!!.jsonPrimitive.content)
        assertEquals(1, categories[0].jsonObject["count"]!!.jsonPrimitive.int)
    }

    @Test
    fun `get_summary, update_entry, and delete_entry enforce owner isolation and compute multi-currency totals`(
        @TempDir tempDir: File,
    ) = runTest {
        val plugin = createPlugin(tempDir)
        val handler = McpProtocolHandler(plugins = listOf(plugin))
        val ownerA = McpAuthContext(clientId = "cli-a", ownerId = "owner-a")
        val ownerB = McpAuthContext(clientId = "cli-b", ownerId = "owner-b")

        val incomeRaw =
            handler.handle(
                """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"ledger_add_entry","arguments":{"type":"INCOME","amount":"1000","category":"Salary","occurred_at":"2026-11-28"}}}""",
                ownerA,
            )!!
        val incomeId =
            json
                .parseToJsonElement(
                    json
                        .parseToJsonElement(incomeRaw)
                        .jsonObject["result"]!!
                        .jsonObject["content"]!!
                        .jsonArray[0]
                        .jsonObject["text"]!!
                        .jsonPrimitive.content,
                ).jsonObject["id"]!!
                .jsonPrimitive.content

        val expenseRaw =
            handler.handle(
                """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"ledger_add_entry","arguments":{"type":"EXPENSE","amount":"250","category":"Food","payment_method":"cash","occurred_at":"2026-11-28"}}}""",
                ownerA,
            )!!
        val expenseId =
            json
                .parseToJsonElement(
                    json
                        .parseToJsonElement(expenseRaw)
                        .jsonObject["result"]!!
                        .jsonObject["content"]!!
                        .jsonArray[0]
                        .jsonObject["text"]!!
                        .jsonPrimitive.content,
                ).jsonObject["id"]!!
                .jsonPrimitive.content

        // Summary before update/delete
        val summaryRaw =
            handler.handle(
                """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"ledger_get_summary","arguments":{"from":"2026-11-28","to":"2026-11-28"}}}""",
                ownerA,
            )!!
        val summaryResult = json.parseToJsonElement(summaryRaw).jsonObject["result"]!!.jsonObject
        assertFalse(summaryResult["isError"]!!.jsonPrimitive.boolean, "Summary must succeed")
        val summaryPayload =
            json
                .parseToJsonElement(
                    summaryResult["content"]!!
                        .jsonArray[0]
                        .jsonObject["text"]!!
                        .jsonPrimitive.content,
                ).jsonObject
        val twdSummary = summaryPayload["currencies"]!!.jsonArray[0].jsonObject
        assertEquals("TWD", twdSummary["currency"]!!.jsonPrimitive.content)
        assertEquals("1000", twdSummary["totalIncome"]!!.jsonPrimitive.content)
        assertEquals("250", twdSummary["totalExpense"]!!.jsonPrimitive.content)
        assertEquals("750", twdSummary["net"]!!.jsonPrimitive.content)

        // Cross-owner update & delete must fail with isError = true
        val crossUpdateRaw =
            handler.handle(
                """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"ledger_update_entry","arguments":{"entry_id":"$expenseId","amount":"999"}}}""",
                ownerB,
            )!!
        assertTrue(
            json
                .parseToJsonElement(crossUpdateRaw)
                .jsonObject["result"]!!
                .jsonObject["isError"]!!
                .jsonPrimitive.boolean,
            "Cross-owner update must return isError = true",
        )

        val crossDeleteRaw =
            handler.handle(
                """{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"ledger_delete_entry","arguments":{"entry_id":"$incomeId"}}}""",
                ownerB,
            )!!
        assertTrue(
            json
                .parseToJsonElement(crossDeleteRaw)
                .jsonObject["result"]!!
                .jsonObject["isError"]!!
                .jsonPrimitive.boolean,
            "Cross-owner delete must return isError = true",
        )

        // Owner update & delete succeed
        val updateRaw =
            handler.handle(
                """{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"ledger_update_entry","arguments":{"entry_id":"$expenseId","amount":"300","clear_payment_method":true}}}""",
                ownerA,
            )!!
        val updateResult = json.parseToJsonElement(updateRaw).jsonObject["result"]!!.jsonObject
        assertFalse(updateResult["isError"]!!.jsonPrimitive.boolean, "Owner update must succeed")
        val updatedPayload =
            json
                .parseToJsonElement(
                    updateResult["content"]!!
                        .jsonArray[0]
                        .jsonObject["text"]!!
                        .jsonPrimitive.content,
                ).jsonObject
        assertEquals("300", updatedPayload["amount"]!!.jsonPrimitive.content)

        val deleteRaw =
            handler.handle(
                """{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"ledger_delete_entry","arguments":{"entry_id":"$expenseId"}}}""",
                ownerA,
            )!!
        val deleteResult = json.parseToJsonElement(deleteRaw).jsonObject["result"]!!.jsonObject
        assertFalse(deleteResult["isError"]!!.jsonPrimitive.boolean, "Owner delete must succeed")

        val listAfterDeleteRaw =
            handler.handle(
                """{"jsonrpc":"2.0","id":8,"method":"tools/call","params":{"name":"ledger_list_entries","arguments":{}}}""",
                ownerA,
            )!!
        val listAfterDelete =
            json
                .parseToJsonElement(
                    json
                        .parseToJsonElement(listAfterDeleteRaw)
                        .jsonObject["result"]!!
                        .jsonObject["content"]!!
                        .jsonArray[0]
                        .jsonObject["text"]!!
                        .jsonPrimitive.content,
                ).jsonObject
        assertEquals(1, listAfterDelete["count"]!!.jsonPrimitive.int)
    }

    @Test
    fun `invalid arguments return isError true`(
        @TempDir tempDir: File,
    ) = runTest {
        val plugin = createPlugin(tempDir)
        val handler = McpProtocolHandler(plugins = listOf(plugin))
        val auth = McpAuthContext(clientId = "cli-1", ownerId = "user-1")

        val invalidPayloads =
            listOf(
                """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"ledger_add_entry","arguments":{"type":"EXPENSE","amount":"-10","category":"food"}}}""",
                """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"ledger_add_entry","arguments":{"type":"EXPENSE","amount":"100","currency":"XYZ","category":"food"}}}""",
                """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"ledger_add_entry","arguments":{"type":"TRANSFER","amount":"100","category":"food"}}}""",
                """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"ledger_add_entry","arguments":{"type":"EXPENSE","amount":"100","category":"food","occurred_at":"not-a-date"}}}""",
                """{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"ledger_list_entries","arguments":{"from":"2026-11-30","to":"2026-11-01"}}}""",
            )

        for (payload in invalidPayloads) {
            val raw = handler.handle(payload, auth)!!
            val result = json.parseToJsonElement(raw).jsonObject["result"]!!.jsonObject
            assertTrue(result["isError"]!!.jsonPrimitive.boolean, "Expected isError=true for payload: $payload")
        }
    }

    @Test
    fun `McpServerMain buildServerPlugins wires LedgerPlugin only when ledgerEnabled is true`(
        @TempDir tempDir: File,
    ) {
        val mcpDb = McpDatabase.connect(File(tempDir, "mcp.db").absolutePath)
        val webhookRepo = McpWebhookRepository(mcpDb)
        val broadcaster = DiscordWebhookBroadcaster(webhookRepo)
        val ledgerDbFile = File(tempDir, "ledger.db")

        val disabledConfig =
            McpServerConfig(
                ledgerEnabled = false,
                ledgerDbPath = ledgerDbFile.absolutePath,
            )
        val disabledPlugins = McpServerMain.buildServerPlugins(disabledConfig, broadcaster, webhookRepo)
        assertEquals(listOf("discord-webhook"), disabledPlugins.map { it.name })
        assertFalse(ledgerDbFile.exists(), "ledger.db must not be opened or created when MCP_LEDGER_ENABLED=false")

        val enabledConfig =
            McpServerConfig(
                ledgerEnabled = true,
                ledgerDbPath = ledgerDbFile.absolutePath,
                ledgerAllowedUserIds = setOf("user-1"),
            )
        val enabledPlugins = McpServerMain.buildServerPlugins(enabledConfig, broadcaster, webhookRepo)
        assertEquals(listOf("discord-webhook", "ledger"), enabledPlugins.map { it.name })
        assertTrue(ledgerDbFile.exists(), "ledger.db must be opened when MCP_LEDGER_ENABLED=true")
    }
}
