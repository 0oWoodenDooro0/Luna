package luna.chatter.memory

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class UnderstandingPatchPromptTest {
    private val existingRecord =
        SocialUnderstandingRecord(
            id = 42L,
            guildId = "890892967991189534",
            scope = MemoryScope.USER,
            targetUserId = "111",
            subject = "humor",
            content = "Enjoys dry sarcasm",
            createdAt = 1000L,
            updatedAt = 1000L,
            lastAccessedAt = 1000L,
        )

    @Test
    fun `system instruction explicitly specifies action JSON key and schema`() {
        val instruction = UnderstandingPatchPrompt.SYSTEM_INSTRUCTION
        assertTrue(
            instruction.contains("\"action\""),
            "SYSTEM_INSTRUCTION must explicitly specify the \"action\" JSON field name, got:\n$instruction",
        )
        assertTrue(
            instruction.contains("ADD") &&
                instruction.contains("UPDATE") &&
                instruction.contains("REAFFIRM") &&
                instruction.contains("DELETE"),
            "SYSTEM_INSTRUCTION must document all patch actions",
        )
    }

    @Test
    fun `parseAndBuildMutations accepts synonymous action keys op type operation patch_action`() {
        val aliasMap = mapOf("u1" to existingRecord)
        val payload =
            """
            [
              {"op": "ADD", "scope": "user", "target_user_id": "111", "subject": "tone", "content": "Playful banter"},
              {"type": "UPDATE", "id": "u1", "subject": "humor", "content": "Very dry sarcasm"},
              {"operation": "REAFFIRM", "id": "u1"},
              {"patch_action": "DELETE", "id": "[u1]"}
            ]
            """.trimIndent()

        val result =
            UnderstandingPatchPrompt.parseAndBuildMutations(
                guildId = "890892967991189534",
                responseText = payload,
                aliasMap = aliasMap,
            )

        assertTrue(result.isSuccess, "Expected synonymous action keys to parse cleanly, got: ${result.exceptionOrNull()?.message}")
        val mutations = result.getOrThrow()
        assertEquals(4, mutations.size)
        assertIs<UnderstandingMutation.Add>(mutations[0])
        assertIs<UnderstandingMutation.Update>(mutations[1])
        assertIs<UnderstandingMutation.Reaffirm>(mutations[2])
        assertIs<UnderstandingMutation.Delete>(mutations[3])
    }

    @Test
    fun `parseAndBuildMutations skips element missing action with warning instead of aborting valid siblings`() {
        val stageLogger = LoggerFactory.getLogger(UnderstandingPatchPrompt::class.java) as Logger
        val listAppender = ListAppender<ILoggingEvent>().apply { start() }
        stageLogger.addAppender(listAppender)
        try {
            val payload =
                """
                [
                  {"scope": "user", "target_user_id": "111", "subject": "missing_action", "content": "No action key"},
                  {"action": "ADD", "scope": "guild", "target_user_id": null, "subject": "banter", "content": "Late-night banter"}
                ]
                """.trimIndent()

            val result =
                UnderstandingPatchPrompt.parseAndBuildMutations(
                    guildId = "890892967991189534",
                    responseText = payload,
                    aliasMap = emptyMap(),
                )

            assertTrue(result.isSuccess, "Expected missing action on one element to skip rather than fail array, got: ${result.exceptionOrNull()?.message}")
            val mutations = result.getOrThrow()
            assertEquals(1, mutations.size)
            val add = assertIs<UnderstandingMutation.Add>(mutations.single())
            assertEquals("banter", add.subject)

            val warnLogs = listAppender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
            assertTrue(
                warnLogs.any { it.contains("missing", ignoreCase = true) || it.contains("action", ignoreCase = true) },
                "Expected WARN log when skipping patch element without action, got: $warnLogs",
            )
        } finally {
            stageLogger.detachAppender(listAppender)
        }
    }

    @Test
    fun `parseAndBuildMutations still fails on malformed non-JSON syntax`() {
        val result =
            UnderstandingPatchPrompt.parseAndBuildMutations(
                guildId = "890892967991189534",
                responseText = "not valid json at all",
                aliasMap = emptyMap(),
            )
        assertTrue(result.isFailure, "Expected invalid JSON syntax to return Result.failure")
    }
}
