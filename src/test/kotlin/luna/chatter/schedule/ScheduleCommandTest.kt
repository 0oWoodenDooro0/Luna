package luna.chatter.schedule

import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.behavior.interaction.response.DeferredPublicMessageInteractionResponseBehavior
import dev.kord.core.entity.interaction.ChatInputCommandInteraction
import dev.kord.rest.json.request.ApplicationCommandCreateRequest
import dev.kord.rest.json.request.MultipartInteractionResponseModifyRequest
import dev.kord.rest.service.InteractionService
import dev.kord.rest.service.RestClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ScheduleCommandTest {
    private fun setupPublicInteraction(): Pair<ChatInputCommandInteraction, () -> MultipartInteractionResponseModifyRequest?> {
        val capturedModify = slot<MultipartInteractionResponseModifyRequest>()
        var capturedRequest: MultipartInteractionResponseModifyRequest? = null

        val mockInteractionService = mockk<InteractionService>(relaxed = true)
        coEvery {
            mockInteractionService.modifyInteractionResponse(any(), any(), capture(capturedModify))
        } answers {
            capturedRequest = capturedModify.captured
            mockk(relaxed = true)
        }

        val mockRestClient =
            mockk<RestClient>(relaxed = true) {
                every { interaction } returns mockInteractionService
            }
        val mockKord =
            mockk<Kord>(relaxed = true) {
                every { rest } returns mockRestClient
            }

        val mockResponse =
            mockk<DeferredPublicMessageInteractionResponseBehavior>(relaxed = true) {
                every { kord } returns mockKord
                every { applicationId } returns Snowflake(999L)
                every { token } returns "interaction-token"
            }

        val mockInteraction =
            mockk<ChatInputCommandInteraction>(relaxed = true) {
                coEvery { deferPublicResponse() } returns mockResponse
            }

        return mockInteraction to { capturedRequest }
    }

    @Test
    fun `handle responds publicly with graceful message when DailySchedule is disabled`() =
        runTest {
            val provider =
                object : DailyScheduleProvider {
                    override fun isEnabled(): Boolean = false

                    override fun currentSchedule(): DailySchedule? = null

                    override fun currentBlock(): ScheduleBlock? = null
                }
            val command = ScheduleCommand(scheduleProvider = provider, clock = Clock.fixed(Instant.parse("2026-11-24T04:30:00Z"), ZoneOffset.UTC))
            val (interaction, capturedRequest) = setupPublicInteraction()

            command.handle(interaction)

            coVerify(exactly = 1) { interaction.deferPublicResponse() }
            val request = assertNotNull(capturedRequest(), "Expected public response to be sent")
            val content = assertNotNull(request.request.content.value, "Expected text content when disabled")
            assertTrue(
                content.contains("未啟用") || content.contains("disabled", ignoreCase = true),
                "Expected disabled message in response content, got: $content",
            )
        }

    @Test
    fun `handle responds publicly with graceful message when currentSchedule is null`() =
        runTest {
            val provider =
                object : DailyScheduleProvider {
                    override fun isEnabled(): Boolean = true

                    override fun currentSchedule(): DailySchedule? = null

                    override fun currentBlock(): ScheduleBlock? = null
                }
            val command = ScheduleCommand(scheduleProvider = provider, clock = Clock.fixed(Instant.parse("2026-11-24T04:30:00Z"), ZoneOffset.UTC))
            val (interaction, capturedRequest) = setupPublicInteraction()

            command.handle(interaction)

            coVerify(exactly = 1) { interaction.deferPublicResponse() }
            val request = assertNotNull(capturedRequest(), "Expected public response to be sent")
            val content = assertNotNull(request.request.content.value, "Expected text content when schedule is still generating")
            assertTrue(
                content.contains("規劃中") || content.contains("generating", ignoreCase = true),
                "Expected generating message in response content, got: $content",
            )
        }

    @Test
    fun `handle responds publicly with Discord Embed listing full schedule chronologically and highlighting active block`() =
        runTest {
            val schedule =
                DailySchedule(
                    date = LocalDate.of(2026, 11, 24),
                    timezone = "Asia/Taipei",
                    blocks =
                        listOf(
                            ScheduleBlock(
                                startMinute = 0,
                                endMinute = 480,
                                activityTitle = "Deep Sleep",
                                activityDetail = "Sleeping",
                                statusText = "Zzz...",
                                availability = AvailabilityState.SLEEPING,
                            ),
                            ScheduleBlock(
                                startMinute = 480,
                                endMinute = 720,
                                activityTitle = "Morning Sketching",
                                activityDetail = "Drawing commission drafts",
                                statusText = "Sketching drafts",
                                availability = AvailabilityState.BUSY,
                            ),
                            ScheduleBlock(
                                startMinute = 720,
                                endMinute = 780,
                                activityTitle = "Lunch & Coffee Break",
                                activityDetail = "Taking a quick lunch break",
                                statusText = "Grabbing lunch",
                                availability = AvailabilityState.BREAK,
                            ),
                            ScheduleBlock(
                                startMinute = 780,
                                endMinute = 1440,
                                activityTitle = "Evening Hangout",
                                activityDetail = "Chatting on Discord",
                                statusText = "Hanging out",
                                availability = AvailabilityState.AVAILABLE,
                            ),
                        ),
                    summary = "Quiet creative morning, relaxing afternoon and evening.",
                    createdAtMillis = 1_700_000_000_000L,
                )
            val provider =
                object : DailyScheduleProvider {
                    override fun isEnabled(): Boolean = true

                    override fun currentSchedule(): DailySchedule = schedule

                    override fun currentBlock(): ScheduleBlock = schedule.blocks[2]
                }
            // 04:30 UTC == 12:30 Asia/Taipei (inside 12:00-13:00 BREAK block)
            val clock = Clock.fixed(Instant.parse("2026-11-24T04:30:00Z"), ZoneOffset.UTC)
            val command = ScheduleCommand(scheduleProvider = provider, clock = clock)
            val (interaction, capturedRequest) = setupPublicInteraction()

            command.handle(interaction)

            coVerify(exactly = 1) { interaction.deferPublicResponse() }
            val request = assertNotNull(capturedRequest(), "Expected public response to be sent")
            val embeds = assertNotNull(request.request.embeds.value, "Expected embeds in public response")
            assertEquals(1, embeds.size)

            val embed = embeds.single()
            val title = assertNotNull(embed.title.value, "Expected embed title")
            assertTrue(title.contains("2026-11-24"), "Title should include schedule date: $title")

            val description = assertNotNull(embed.description.value, "Expected embed description")
            assertTrue(description.contains("Grabbing lunch"), "Description should show active block status summary")
            assertTrue(description.contains("Quiet creative morning"), "Description should include schedule summary")

            val sleepIdx = description.indexOf("00:00–08:00")
            val sketchIdx = description.indexOf("08:00–12:00")
            val lunchIdx = description.indexOf("12:00–13:00")
            val hangoutIdx = description.indexOf("13:00–24:00")
            assertTrue(sleepIdx in 0 until sketchIdx, "Blocks must be in chronological order")
            assertTrue(sketchIdx in 0 until lunchIdx, "Blocks must be in chronological order")
            assertTrue(lunchIdx in 0 until hangoutIdx, "Blocks must be in chronological order")

            val lines = description.lines()
            val activeLine = assertNotNull(lines.firstOrNull { it.contains("12:00–13:00") })
            assertTrue(activeLine.contains("▶"), "Active block line should be highlighted: $activeLine")
            assertTrue(activeLine.contains("Lunch & Coffee Break"), "Active line should include block title: $activeLine")

            val inactiveLine = assertNotNull(lines.firstOrNull { it.contains("08:00–12:00") })
            assertFalse(inactiveLine.contains("▶"), "Inactive block line should not be highlighted: $inactiveLine")
            assertTrue(inactiveLine.contains("Morning Sketching"), "Inactive line should include block title: $inactiveLine")

            val footer = assertNotNull(embed.footer.value)
            assertTrue(footer.text.contains("Asia/Taipei"), "Footer should include schedule timezone: ${footer.text}")
        }

    @Test
    fun `register creates flat global schedule command with no subcommands`() =
        runTest {
            val capturedCommand = slot<ApplicationCommandCreateRequest>()
            val mockInteractionService = mockk<InteractionService>(relaxed = true)
            coEvery {
                mockInteractionService.createGlobalApplicationCommand(any(), capture(capturedCommand))
            } returns mockk(relaxed = true)

            val mockRestClient =
                mockk<RestClient>(relaxed = true) {
                    every { interaction } returns mockInteractionService
                }
            val mockKord =
                mockk<Kord>(relaxed = true) {
                    every { rest } returns mockRestClient
                    every { selfId } returns Snowflake(999L)
                }

            val provider =
                object : DailyScheduleProvider {
                    override fun isEnabled(): Boolean = false

                    override fun currentSchedule(): DailySchedule? = null

                    override fun currentBlock(): ScheduleBlock? = null
                }
            val command = ScheduleCommand(scheduleProvider = provider)

            command.register(mockKord)

            assertEquals("schedule", command.name)
            assertEquals("schedule", capturedCommand.captured.name)
            val options = capturedCommand.captured.options.value
            assertTrue(options.isNullOrEmpty(), "Expected flat /schedule command with no subcommands or options, got $options")
        }

    @Test
    fun `truncateDescription caps output at Discord embed description limit`() {
        val longText = "a".repeat(ScheduleCommand.DISCORD_EMBED_DESCRIPTION_MAX_LENGTH + 50)
        val truncated = ScheduleCommand.truncateDescription(longText)
        assertEquals(ScheduleCommand.DISCORD_EMBED_DESCRIPTION_MAX_LENGTH, truncated.length)
        assertTrue(truncated.endsWith("..."), "Truncated description should end with ellipsis")
    }

    @Test
    fun `handle renders pencil marker only on conversation-adjusted blocks`() =
        runTest {
            val schedule =
                DailySchedule(
                    date = LocalDate.of(2026, 11, 24),
                    timezone = "Asia/Taipei",
                    blocks =
                        listOf(
                            ScheduleBlock(
                                startMinute = 0,
                                endMinute = 480,
                                activityTitle = "Deep Sleep",
                                activityDetail = "Sleeping",
                                statusText = "Zzz...",
                                availability = AvailabilityState.SLEEPING,
                                adjustedByConversation = false,
                            ),
                            ScheduleBlock(
                                startMinute = 480,
                                endMinute = 720,
                                activityTitle = "Morning Sketching",
                                activityDetail = "Drawing commission drafts",
                                statusText = "Sketching drafts",
                                availability = AvailabilityState.BUSY,
                                adjustedByConversation = true,
                            ),
                            ScheduleBlock(
                                startMinute = 720,
                                endMinute = 780,
                                activityTitle = "Lunch & Coffee Break",
                                activityDetail = "Taking a quick lunch break",
                                statusText = "Grabbing lunch",
                                availability = AvailabilityState.BREAK,
                                adjustedByConversation = true,
                            ),
                            ScheduleBlock(
                                startMinute = 780,
                                endMinute = 1440,
                                activityTitle = "Evening Hangout",
                                activityDetail = "Chatting on Discord",
                                statusText = "Hanging out",
                                availability = AvailabilityState.AVAILABLE,
                                adjustedByConversation = false,
                            ),
                        ),
                    summary = "Quiet creative morning, relaxing afternoon and evening.",
                    createdAtMillis = 1_700_000_000_000L,
                )
            val provider =
                object : DailyScheduleProvider {
                    override fun isEnabled(): Boolean = true

                    override fun currentSchedule(): DailySchedule = schedule

                    override fun currentBlock(): ScheduleBlock = schedule.blocks[2]
                }
            val clock = Clock.fixed(Instant.parse("2026-11-24T04:30:00Z"), ZoneOffset.UTC)
            val command = ScheduleCommand(scheduleProvider = provider, clock = clock)
            val (interaction, capturedRequest) = setupPublicInteraction()

            command.handle(interaction)

            val request = assertNotNull(capturedRequest())
            val embed = assertNotNull(request.request.embeds.value).single()
            val description = assertNotNull(embed.description.value)
            val lines = description.lines()

            val sleepLine = assertNotNull(lines.firstOrNull { it.contains("00:00–08:00") })
            assertFalse(sleepLine.contains("✏️"), "Unadjusted block should not render pencil marker: $sleepLine")

            val sketchLine = assertNotNull(lines.firstOrNull { it.contains("08:00–12:00") })
            assertTrue(sketchLine.contains("✏️"), "Adjusted inactive block should render pencil marker: $sketchLine")

            val lunchLine = assertNotNull(lines.firstOrNull { it.contains("12:00–13:00") })
            assertTrue(lunchLine.contains("✏️"), "Adjusted active block should render pencil marker: $lunchLine")

            val hangoutLine = assertNotNull(lines.firstOrNull { it.contains("13:00–24:00") })
            assertFalse(hangoutLine.contains("✏️"), "Unadjusted block should not render pencil marker: $hangoutLine")
        }

    @Test
    fun `handle appends compact upcoming commitments section when commitments exist`() =
        runTest {
            val schedule =
                DailySchedule(
                    date = LocalDate.of(2026, 11, 24),
                    timezone = "Asia/Taipei",
                    blocks =
                        listOf(
                            ScheduleBlock(
                                startMinute = 0,
                                endMinute = 1440,
                                activityTitle = "Free Day",
                                activityDetail = "Relaxing",
                                statusText = "Chilling",
                                availability = AvailabilityState.AVAILABLE,
                            ),
                        ),
                    summary = "Relaxing day.",
                    createdAtMillis = 1_700_000_000_000L,
                )
            val commitments =
                listOf(
                    ScheduleCommitment(
                        id = "c2",
                        targetDate = LocalDate.of(2026, 11, 26),
                        startMinute = 600,
                        endMinute = 720,
                        activityTitle = "Team Sync",
                        activityDetail = "Weekly meeting",
                        statusText = "In a meeting",
                        availability = AvailabilityState.BUSY,
                        createdAtMillis = 1_700_000_000_100L,
                    ),
                    ScheduleCommitment(
                        id = "c1",
                        targetDate = LocalDate.of(2026, 11, 25),
                        startMinute = 1200,
                        endMinute = 1320,
                        activityTitle = "Watch Party",
                        activityDetail = "Movie night",
                        statusText = "Watching movies",
                        availability = AvailabilityState.BREAK,
                        createdAtMillis = 1_700_000_000_050L,
                    ),
                )
            val provider =
                object : DailyScheduleProvider {
                    override fun isEnabled(): Boolean = true

                    override fun currentSchedule(): DailySchedule = schedule

                    override fun currentBlock(): ScheduleBlock = schedule.blocks.single()

                    override fun upcomingCommitments(): List<ScheduleCommitment> = commitments
                }
            val clock = Clock.fixed(Instant.parse("2026-11-24T04:30:00Z"), ZoneOffset.UTC)
            val command = ScheduleCommand(scheduleProvider = provider, clock = clock)
            val (interaction, capturedRequest) = setupPublicInteraction()

            command.handle(interaction)

            val request = assertNotNull(capturedRequest())
            val embed = assertNotNull(request.request.embeds.value).single()
            val description = assertNotNull(embed.description.value)

            assertTrue(description.contains("近期約定"), "Expected 近期約定 section header, got:\n$description")
            val firstIdx = description.indexOf("2026-11-25 20:00–22:00 ☕ Watch Party")
            val secondIdx = description.indexOf("2026-11-26 10:00–12:00 🔴 Team Sync")
            assertTrue(firstIdx >= 0, "Expected formatted commitment for 2026-11-25, got:\n$description")
            assertTrue(secondIdx > firstIdx, "Expected commitments sorted chronologically, got:\n$description")
        }

    @Test
    fun `handle omits upcoming commitments section when no commitments exist`() =
        runTest {
            val schedule =
                DailySchedule(
                    date = LocalDate.of(2026, 11, 24),
                    timezone = "Asia/Taipei",
                    blocks =
                        listOf(
                            ScheduleBlock(
                                startMinute = 0,
                                endMinute = 1440,
                                activityTitle = "Free Day",
                                activityDetail = "Relaxing",
                                statusText = "Chilling",
                                availability = AvailabilityState.AVAILABLE,
                            ),
                        ),
                    summary = "Relaxing day.",
                    createdAtMillis = 1_700_000_000_000L,
                )
            val provider =
                object : DailyScheduleProvider {
                    override fun isEnabled(): Boolean = true

                    override fun currentSchedule(): DailySchedule = schedule

                    override fun currentBlock(): ScheduleBlock = schedule.blocks.single()

                    override fun upcomingCommitments(): List<ScheduleCommitment> = emptyList()
                }
            val clock = Clock.fixed(Instant.parse("2026-11-24T04:30:00Z"), ZoneOffset.UTC)
            val command = ScheduleCommand(scheduleProvider = provider, clock = clock)
            val (interaction, capturedRequest) = setupPublicInteraction()

            command.handle(interaction)

            val request = assertNotNull(capturedRequest())
            val embed = assertNotNull(request.request.embeds.value).single()
            val description = assertNotNull(embed.description.value)
            assertFalse(description.contains("近期約定"), "Expected 近期約定 section omitted when empty, got:\n$description")
            assertTrue(embed.fields.value.isNullOrEmpty(), "Expected no extra embed fields when commitments empty")
        }
}
