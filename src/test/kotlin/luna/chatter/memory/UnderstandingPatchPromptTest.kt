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

    @Test
    fun `buildPrompt includes UserRelationship tier score and toneStyle for active human speakers`() {
        val aliceRel =
            UserRelationship(
                guildId = "890892967991189534",
                userId = "111",
                affinityScore = 25,
                toneStyle = "playful sarcasm",
            )
        val bobRel =
            UserRelationship(
                guildId = "890892967991189534",
                userId = "222",
                affinityScore = -45,
                toneStyle = "",
            )

        val (prompt, _) =
            UnderstandingPatchPrompt.buildPrompt(
                humanSpeakers = linkedMapOf("111" to "Alice", "222" to "Bob"),
                userUnderstandings = emptyMap(),
                guildUnderstandings = emptyList(),
                transcript = "Alice: hey\nBob: whatever",
                userRelationships = mapOf("111" to aliceRel, "222" to bobRel),
            )

        assertTrue(prompt.contains("WARM"), "Expected WARM tier for Alice in prompt, got:\n$prompt")
        assertTrue(prompt.contains("25"), "Expected affinityScore 25 for Alice in prompt, got:\n$prompt")
        assertTrue(prompt.contains("playful sarcasm"), "Expected toneStyle for Alice in prompt, got:\n$prompt")
        assertTrue(prompt.contains("DISLIKED"), "Expected DISLIKED tier for Bob in prompt, got:\n$prompt")
        assertTrue(prompt.contains("-45"), "Expected affinityScore -45 for Bob in prompt, got:\n$prompt")
    }

    @Test
    fun `system instruction documents relationshipUpdates schema`() {
        val instruction = UnderstandingPatchPrompt.SYSTEM_INSTRUCTION
        assertTrue(
            instruction.contains("relationshipUpdates"),
            "SYSTEM_INSTRUCTION must document relationshipUpdates, got:\n$instruction",
        )
        assertTrue(
            instruction.contains("targetUserId") && instruction.contains("affinityDelta") && instruction.contains("toneStyle"),
            "SYSTEM_INSTRUCTION must document targetUserId, affinityDelta, and toneStyle",
        )
    }

    @Test
    fun `parsePatchResponse parses relationshipUpdates clamps affinityDelta and ignores blank or unknown targetUserId`() {
        val aliasMap = mapOf("u1" to existingRecord)
        val longTone = "x".repeat(150)
        val payload =
            """
            {
              "understandingPatches": [
                {"action": "REAFFIRM", "id": "u1"}
              ],
              "relationshipUpdates": [
                {"targetUserId": "111", "affinityDelta": 42, "toneStyle": "  warm and playful  "},
                {"targetUserId": "222", "affinityDelta": -99, "toneStyle": "$longTone"},
                {"targetUserId": "   ", "affinityDelta": 5, "toneStyle": "ignored blank"},
                {"targetUserId": "999", "affinityDelta": 10, "toneStyle": "ignored unknown"}
              ]
            }
            """.trimIndent()

        val result =
            UnderstandingPatchPrompt.parsePatchResponse(
                guildId = "890892967991189534",
                responseText = payload,
                aliasMap = aliasMap,
                validSpeakerIds = setOf("111", "222"),
            )

        assertTrue(result.isSuccess, "Expected envelope JSON to parse cleanly, got: ${result.exceptionOrNull()?.message}")
        val stage3 = result.getOrThrow()
        assertEquals(1, stage3.understandingMutations.size)
        assertIs<UnderstandingMutation.Reaffirm>(stage3.understandingMutations.single())

        assertEquals(2, stage3.relationshipUpdates.size)
        assertEquals(
            ValidatedRelationshipUpdate(targetUserId = "111", affinityDelta = 15, toneStyle = "warm and playful"),
            stage3.relationshipUpdates[0],
        )
        assertEquals(
            ValidatedRelationshipUpdate(targetUserId = "222", affinityDelta = -15, toneStyle = "x".repeat(120)),
            stage3.relationshipUpdates[1],
        )
    }

    @Test
    fun `parsePatchResponse remains backward compatible with legacy array response omitting relationshipUpdates`() {
        val payload = """[{"action": "ADD", "scope": "guild", "target_user_id": null, "subject": "vibe", "content": "Chill"}]"""
        val result =
            UnderstandingPatchPrompt.parsePatchResponse(
                guildId = "890892967991189534",
                responseText = payload,
                aliasMap = emptyMap(),
                validSpeakerIds = setOf("111"),
            )

        assertTrue(result.isSuccess, "Expected legacy array response to parse cleanly, got: ${result.exceptionOrNull()?.message}")
        val stage3 = result.getOrThrow()
        assertEquals(1, stage3.understandingMutations.size)
        assertTrue(stage3.relationshipUpdates.isEmpty(), "Expected empty relationshipUpdates for legacy array response")
    }
}
