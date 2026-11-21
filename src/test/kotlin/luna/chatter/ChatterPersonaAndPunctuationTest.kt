package luna.chatter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatterPersonaAndPunctuationTest {
    @Test
    fun `ChatterPromptBuilder SYSTEM_INSTRUCTION defines Chatter Persona traits and banter boundary without verbatim quotes`() {
        val sys = ChatterPromptBuilder.SYSTEM_INSTRUCTION

        // Core persona traits described purely as situational behavioral traits
        assertTrue(sys.contains("機靈") && sys.contains("好奇"), "Expected quick-witted and curious traits in SYSTEM_INSTRUCTION")
        assertTrue(sys.contains("吐槽") && sys.contains("親近"), "Expected sharp-tongued yet approachable traits in SYSTEM_INSTRUCTION")
        assertTrue(sys.contains("調皮") && sys.contains("迷糊"), "Expected mischievous yet occasionally scatterbrained traits in SYSTEM_INSTRUCTION")
        assertTrue(sys.contains("認錯") && sys.contains("嘴硬"), "Expected candid ownership without excuses when caught wrong")
        assertTrue(sys.contains("炸毛") || sys.contains("抗拒"), "Expected mildly thin-skinned/mock-reluctance trait when pressed")
        assertTrue(sys.contains("消氣") || sys.contains("安撫"), "Expected cooling down quickly when placated")
        assertTrue(sys.contains("幫忙") && sys.contains("客服"), "Expected naturally helpful friend contrast against customer-service assistant")

        // Explicit friendly-banter boundary & natural emergence
        assertTrue(sys.contains("人身攻擊"), "Expected explicit prohibition of personal attacks in friendly-banter boundary")
        assertTrue(sys.contains("認真求助") || sys.contains("真正求助"), "Expected boundary never genuinely refusing serious help")
        assertTrue(sys.contains("自然流露"), "Expected guidance that persona traits emerge naturally when warranted")

        // Strictly NO verbatim colloquial dialogue examples
        val forbiddenVerbatimExamples = listOf("「欸靠」", "「煩欸」", "「好啦」", "「蛤」", "「哭啊」", "「笑死」")
        for (example in forbiddenVerbatimExamples) {
            assertFalse(
                sys.contains(example),
                "SYSTEM_INSTRUCTION must not contain verbatim colloquial example $example",
            )
        }
    }

    @Test
    fun `ChatterPromptBuilder relaxes expressive punctuation while preserving newline breaks and avoiding commas and periods`() {
        val sys = ChatterPromptBuilder.SYSTEM_INSTRUCTION
        val userPrompt = ChatterPromptBuilder.buildUserPrompt("[Alice]: 你剛剛是不是又看錯了")

        // Avoid commas and periods, preserve single newline breaks and 2-3 short lines
        assertTrue(sys.contains("逗號") && sys.contains("句號"), "Expected SYSTEM_INSTRUCTION to avoid commas and periods")
        assertTrue(sys.contains("\\n") && sys.contains("換行"), "Expected SYSTEM_INSTRUCTION to require single newline breaks")
        assertTrue(sys.contains("兩到三句"), "Expected SYSTEM_INSTRUCTION to keep 2-3 ultra-short lines")

        // Relax expressive punctuation (？, ！) instead of forbidding exclamation marks
        assertTrue(sys.contains("？") && sys.contains("！"), "Expected SYSTEM_INSTRUCTION to allow expressive ？ and ！")
        assertFalse(sys.contains("不加逗號、句號、驚嘆號"), "Expected SYSTEM_INSTRUCTION to stop forbidding exclamation marks")

        assertTrue(userPrompt.contains("逗號") && userPrompt.contains("句號"), "Expected userPrompt to avoid commas and periods")
        assertTrue(userPrompt.contains("？") && userPrompt.contains("！"), "Expected userPrompt to allow expressive ？ and ！")
        assertFalse(userPrompt.contains("不加逗號、句號、驚嘆號"), "Expected userPrompt to stop forbidding exclamation marks")
        assertTrue(userPrompt.contains("\\n") && userPrompt.contains("換行"), "Expected userPrompt to require single newline breaks")
    }

    @Test
    fun `ChatterPromptBuilder buildReactionSystemInstruction aligns intro with Chatter Persona without verbatim quotes`() {
        val reactionSys = ChatterPromptBuilder.buildReactionSystemInstruction()

        assertTrue(reactionSys.contains("Luna"), "Expected reaction system instruction to identify Luna")
        assertTrue(
            reactionSys.contains("機靈") || reactionSys.contains("好奇") || reactionSys.contains("吐槽"),
            "Expected reaction system instruction to reflect Chatter Persona",
        )
        assertTrue(reactionSys.contains("NONE"), "Expected reaction system instruction to preserve NONE fallback")

        val forbiddenVerbatimExamples = listOf("「欸靠」", "「煩欸」", "「好啦」")
        for (example in forbiddenVerbatimExamples) {
            assertFalse(
                reactionSys.contains(example),
                "Reaction system instruction must not contain verbatim colloquial example $example",
            )
        }
    }

    @Test
    fun `CircadianPersonaDrift preserves exact circadian windows and threshold offsets while aligning all promptCues with Chatter Persona`() {
        // Exact CircadianPeriod offsets and hour boundaries
        assertEquals(0.08, CircadianPeriod.LATE_NIGHT.thresholdOffset)
        assertEquals(0.02, CircadianPeriod.MORNING.thresholdOffset)
        assertEquals(0.0, CircadianPeriod.DAYTIME.thresholdOffset)
        assertEquals(-0.02, CircadianPeriod.EVENING.thresholdOffset)

        for (hour in 0..23) {
            val expected =
                when (hour) {
                    in 1..5 -> CircadianPeriod.LATE_NIGHT
                    in 6..11 -> CircadianPeriod.MORNING
                    in 12..17 -> CircadianPeriod.DAYTIME
                    else -> CircadianPeriod.EVENING
                }
            assertEquals(expected, CircadianPeriod.fromHour(hour), "Circadian window mismatch at hour $hour")
        }

        // Exact PersonaMood offsets
        assertEquals(-0.08, PersonaMood.ENERGETIC.thresholdOffset)
        assertEquals(-0.04, PersonaMood.CHATTY.thresholdOffset)
        assertEquals(0.0, PersonaMood.STEADY.thresholdOffset)
        assertEquals(0.04, PersonaMood.MELLOW.thresholdOffset)
        assertEquals(0.08, PersonaMood.TIRED.thresholdOffset)
        assertEquals(0.12, PersonaMood.LOW_BATTERY.thresholdOffset)

        // All CircadianPeriod promptCues express Chatter Persona variations without verbatim quotes
        assertTrue(
            CircadianPeriod.LATE_NIGHT.promptCue.contains("迷糊") && CircadianPeriod.LATE_NIGHT.promptCue.contains("好奇"),
            "LATE_NIGHT cue should express sleepy/scatterbrained yet curious Chatter Persona",
        )
        assertTrue(
            CircadianPeriod.MORNING.promptCue.contains("迷糊") && CircadianPeriod.MORNING.promptCue.contains("親切"),
            "MORNING cue should express warming-up scatterbrained yet approachable Chatter Persona",
        )
        assertTrue(
            CircadianPeriod.DAYTIME.promptCue.contains("機靈") && CircadianPeriod.DAYTIME.promptCue.contains("好奇"),
            "DAYTIME cue should express quick-witted and curious Chatter Persona",
        )
        assertTrue(
            CircadianPeriod.EVENING.promptCue.contains("互嘴") && CircadianPeriod.EVENING.promptCue.contains("調皮"),
            "EVENING cue should express playful friendly-banter Chatter Persona",
        )

        // All PersonaMood promptCues express Chatter Persona variations without flattening her voice
        assertTrue(
            PersonaMood.ENERGETIC.promptCue.contains("機靈") && PersonaMood.ENERGETIC.promptCue.contains("吐槽"),
            "ENERGETIC cue should express quick-witted playful Chatter Persona",
        )
        assertTrue(
            PersonaMood.CHATTY.promptCue.contains("好奇") && PersonaMood.CHATTY.promptCue.contains("親近"),
            "CHATTY cue should express curious and approachable Chatter Persona",
        )
        assertTrue(
            PersonaMood.STEADY.promptCue.contains("機靈") && PersonaMood.STEADY.promptCue.contains("幫忙"),
            "STEADY cue should express balanced quick-witted helpful Chatter Persona",
        )
        assertTrue(
            PersonaMood.MELLOW.promptCue.contains("吐槽") && PersonaMood.MELLOW.promptCue.contains("親近"),
            "MELLOW cue should keep softer playful voice without becoming detached",
        )
        assertTrue(
            PersonaMood.TIRED.promptCue.contains("順毛") || PersonaMood.TIRED.promptCue.contains("認真求助"),
            "TIRED cue should express low-energy complaints while remaining responsive when placated or seriously asked",
        )
        assertTrue(
            PersonaMood.LOW_BATTERY.promptCue.contains("抗拒") && PersonaMood.LOW_BATTERY.promptCue.contains("幫忙"),
            "LOW_BATTERY cue should express mock reluctance while still chiming in for engaging topics or friends needing help",
        )

        val allCues = CircadianPeriod.entries.map { it.promptCue } + PersonaMood.entries.map { it.promptCue }
        for (cue in allCues) {
            assertFalse(cue.contains("「") || cue.contains("」"), "Circadian/mood promptCue must not contain verbatim quote examples: $cue")
        }
    }

    @Test
    fun `GeminiDecisionClient DEFAULT_INSTRUCTIONS aligns Luna description with Chatter Persona while preserving decision criteria`() {
        val instructions = GeminiDecisionClient.DEFAULT_INSTRUCTIONS

        assertTrue(instructions.contains("quick-witted"), "Expected quick-witted persona alignment in DEFAULT_INSTRUCTIONS")
        assertTrue(instructions.contains("curious"), "Expected curious persona alignment in DEFAULT_INSTRUCTIONS")
        assertTrue(instructions.contains("naturally helpful"), "Expected naturally helpful friend alignment in DEFAULT_INSTRUCTIONS")

        // Preserve all true/false participation boundaries intact
        assertTrue(instructions.contains("Answer true when:"), "Expected 'Answer true when:' section intact")
        assertTrue(instructions.contains("Answer false when:"), "Expected 'Answer false when:' section intact")
        assertTrue(instructions.contains("1-on-1 exchange"), "Expected 1-on-1 exclusion intact")
        assertTrue(instructions.contains("standalone emoji"), "Expected standalone emoji exclusion intact")
        assertTrue(instructions.contains("bot command or system notice"), "Expected bot command exclusion intact")
        assertTrue(instructions.contains("naturally references Luna"), "Expected Luna reference inclusion intact")
    }
}
