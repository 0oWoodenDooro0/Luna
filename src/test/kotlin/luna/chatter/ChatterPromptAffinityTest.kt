package luna.chatter

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatterPromptAffinityTest {
    @Test
    fun `ChatterPromptBuilder system instruction includes per-user tone adaptation and amended ADR-0019 refusal boundary`() {
        val sys = ChatterPromptBuilder.SYSTEM_INSTRUCTION
        assertContains(sys, "FAVORITE")
        assertContains(sys, "COOL")
        assertContains(sys, "DISLIKED")
        assertContains(sys, "絕不進行人身攻擊")
        assertTrue(
            sys.contains("冷淡") || sys.contains("拒絕幫忙") || sys.contains("不想回答"),
            "Expected amended refusal clause allowing aloofness/refusal for COOL/DISLIKED users",
        )
    }

    @Test
    fun `ChatterPromptBuilder buildUserPrompt includes disengagement sentinels only when allowMentionDisengagement is true`() {
        val normalPrompt = ChatterPromptBuilder.buildUserPrompt(ChatterInput(transcript = "Alice: hi", isDirectMention = true))
        assertFalse(normalPrompt.contains(ChatterPromptBuilder.NO_REPLY_SENTINEL), "Normal prompt must not contain NO_REPLY sentinel")
        assertFalse(normalPrompt.contains(ChatterPromptBuilder.COLD_REACT_SENTINEL), "Normal prompt must not contain COLD_REACT sentinel")

        val disengagedPrompt =
            ChatterPromptBuilder.buildUserPrompt(
                ChatterInput(transcript = "Troll: hey", isDirectMention = true, allowMentionDisengagement = true),
            )
        assertContains(disengagedPrompt, ChatterPromptBuilder.NO_REPLY_SENTINEL)
        assertContains(disengagedPrompt, ChatterPromptBuilder.COLD_REACT_SENTINEL)
    }

    @Test
    fun `ChatterPromptBuilder buildReactionSystemInstruction includes cold reaction cue when coldReaction is true`() {
        val normalReactionSys = ChatterPromptBuilder.buildReactionSystemInstruction()
        val coldReactionSys = ChatterPromptBuilder.buildReactionSystemInstruction(coldReaction = true)
        assertFalse(normalReactionSys.contains("冷淡"), "Normal reaction prompt should not include cold reaction cue")
        assertContains(coldReactionSys, "冷淡")
    }

    @Test
    fun `ChatterPromptBuilder parseDisengagementOutcome handles sentinels and prevents sentinel leaks`() {
        assertEquals(
            ChatterPromptBuilder.DisengagementOutcome.NoReply,
            ChatterPromptBuilder.parseDisengagementOutcome("[NO_REPLY]", allowDisengagement = true),
        )
        assertEquals(
            ChatterPromptBuilder.DisengagementOutcome.ColdReact,
            ChatterPromptBuilder.parseDisengagementOutcome("  [COLD_REACT]  ", allowDisengagement = true),
        )
        assertEquals(
            ChatterPromptBuilder.DisengagementOutcome.Text("不要煩我"),
            ChatterPromptBuilder.parseDisengagementOutcome("不要煩我 [NO_REPLY]", allowDisengagement = true),
        )
        assertEquals(
            ChatterPromptBuilder.DisengagementOutcome.Text(""),
            ChatterPromptBuilder.parseDisengagementOutcome("[NO_REPLY]", allowDisengagement = false),
        )
        assertEquals(
            ChatterPromptBuilder.DisengagementOutcome.Text("早安"),
            ChatterPromptBuilder.parseDisengagementOutcome("早安 [COLD_REACT]", allowDisengagement = false),
        )
    }
}
