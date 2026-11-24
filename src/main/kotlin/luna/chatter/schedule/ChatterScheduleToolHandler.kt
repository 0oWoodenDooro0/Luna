package luna.chatter.schedule

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import luna.core.ai.GeminiInteractionTool
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/**
 * Handles `update_today_schedule` and `manage_schedule_commitment` function calls from
 * `ChatterGenerator` (`/v1beta/interactions`).
 *
 * Parses and validates time/date/availability arguments, delegates mutations to
 * [DailyScheduleCoordinator], and returns clear model-readable JSON payloads without throwing
 * on malformed arguments, guardrail rejections, or unexpected failures.
 */
open class ChatterScheduleToolHandler(
    private val coordinator: DailyScheduleCoordinator,
) {
    private val logger = LoggerFactory.getLogger(ChatterScheduleToolHandler::class.java)

    /**
     * Applies 1..N structured time-range patches to today's `Daily Schedule`.
     */
    open suspend fun handleTodayScheduleCall(arguments: JsonObject): String {
        val patchesArray =
            arguments["patches"] as? JsonArray
                ?: return invalidArgumentResponse("patches must be a non-empty array of schedule patch objects.")
        if (patchesArray.isEmpty()) {
            return invalidArgumentResponse("patches must contain at least one schedule patch.")
        }

        val patches = ArrayList<SchedulePatch>(patchesArray.size)
        for ((index, element) in patchesArray.withIndex()) {
            val obj =
                element as? JsonObject
                    ?: return invalidArgumentResponse("patches[$index] must be a JSON object.")
            val parsed =
                parsePatch(obj, prefix = "patches[$index].")
                    ?: return invalidArgumentResponse("patches[$index] is invalid.")
            when (parsed) {
                is ParseResult.Error -> return invalidArgumentResponse(parsed.message)
                is ParseResult.Value -> patches.add(parsed.value)
            }
        }

        return try {
            when (val result = coordinator.applyTodayPatches(patches)) {
                is DailyScheduleSplicer.SpliceResult.Success -> formatTodaySuccess(result)
                is DailyScheduleSplicer.SpliceResult.Rejected -> rejectedResponse(result.reason)
            }
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            logger.warn("update_today_schedule failed unexpectedly: {}", ex.message, ex)
            unavailableResponse("今日行程調整暫時無法完成，請依目前對話自然回覆。")
        }
    }

    /**
     * Creates, updates, or cancels a cross-day `Schedule Commitment` (`tomorrow..+7 days`).
     */
    open suspend fun handleCommitmentCall(arguments: JsonObject): String {
        val action =
            extractString(arguments, "action")?.lowercase()
                ?: return invalidArgumentResponse("action is required and must be 'upsert' or 'cancel'.")

        return when (action) {
            "upsert" -> handleUpsertCommitment(arguments)
            "cancel" -> handleCancelCommitment(arguments)
            else -> invalidArgumentResponse("action must be 'upsert' or 'cancel', got '$action'.")
        }
    }

    private suspend fun handleUpsertCommitment(arguments: JsonObject): String {
        val id = extractString(arguments, "id")
        val rawDate =
            extractString(arguments, "date")
                ?: return invalidArgumentResponse("date (YYYY-MM-DD) is required for action='upsert'.")
        val targetDate =
            parseDate(rawDate)
                ?: return invalidArgumentResponse("Invalid date '$rawDate'; expected YYYY-MM-DD.")

        val parsedPatch =
            parsePatch(arguments, prefix = "")
                ?: return invalidArgumentResponse("Invalid commitment fields.")
        val patch =
            when (parsedPatch) {
                is ParseResult.Error -> return invalidArgumentResponse(parsedPatch.message)
                is ParseResult.Value -> parsedPatch.value
            }

        return try {
            when (
                val result =
                    coordinator.upsertCommitment(
                        id = id,
                        targetDate = targetDate,
                        startMinute = patch.startMinute,
                        endMinute = patch.endMinute,
                        activityTitle = patch.activityTitle,
                        activityDetail = patch.activityDetail,
                        statusText = patch.statusText,
                        availability = patch.availability,
                    )
            ) {
                is DailyScheduleCoordinator.CommitmentResult.Upserted -> formatUpsertedResponse(result.commitment)
                is DailyScheduleCoordinator.CommitmentResult.Rejected -> rejectedResponse(result.reason)
                is DailyScheduleCoordinator.CommitmentResult.Cancelled -> rejectedResponse("Unexpected cancel result.")
            }
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            logger.warn("manage_schedule_commitment (upsert) failed unexpectedly: {}", ex.message, ex)
            unavailableResponse("跨日約定更新暫時無法完成，請依目前對話自然回覆。")
        }
    }

    private suspend fun handleCancelCommitment(arguments: JsonObject): String {
        val explicitId = extractString(arguments, "id")
        if (explicitId != null) {
            return executeCancelById(explicitId)
        }

        val rawDate = extractString(arguments, "date")
        val parsedDate =
            if (rawDate != null) {
                parseDate(rawDate) ?: return invalidArgumentResponse("Invalid date '$rawDate'; expected YYYY-MM-DD.")
            } else {
                null
            }
        val rawStartTime = extractString(arguments, "start_time")
        val parsedStartMinute =
            if (rawStartTime != null) {
                parseTimeMinutes(rawStartTime, allowEndOfDay = false)
                    ?: return invalidArgumentResponse("Invalid start_time '$rawStartTime'; expected HH:mm (00:00..23:59).")
            } else {
                null
            }
        val title = extractString(arguments, "title")

        if (parsedDate == null && parsedStartMinute == null && title == null) {
            return invalidArgumentResponse("id or matching date/start_time/title is required for action='cancel'.")
        }

        return try {
            val matches =
                coordinator.upcomingCommitments().filter { commitment ->
                    (parsedDate == null || commitment.targetDate == parsedDate) &&
                        (parsedStartMinute == null || commitment.startMinute == parsedStartMinute) &&
                        (title == null || commitment.activityTitle.equals(title, ignoreCase = true))
                }
            when (matches.size) {
                0 -> {
                    rejectedResponse("No matching upcoming schedule commitment found to cancel.")
                }

                1 -> {
                    executeCancelById(matches.first().id)
                }

                else -> {
                    val candidates = matches.joinToString(", ") { "${it.id} (${it.targetDate} ${it.formattedTimeRange()} ${it.activityTitle})" }
                    invalidArgumentResponse("Multiple commitments matched; specify id explicitly: $candidates")
                }
            }
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            logger.warn("manage_schedule_commitment (cancel) failed unexpectedly: {}", ex.message, ex)
            unavailableResponse("跨日約定取消暫時無法完成，請依目前對話自然回覆。")
        }
    }

    private suspend fun executeCancelById(id: String): String =
        try {
            when (val result = coordinator.cancelCommitment(id)) {
                is DailyScheduleCoordinator.CommitmentResult.Cancelled -> formatCancelledResponse(result.id)
                is DailyScheduleCoordinator.CommitmentResult.Rejected -> rejectedResponse(result.reason)
                is DailyScheduleCoordinator.CommitmentResult.Upserted -> rejectedResponse("Unexpected upsert result.")
            }
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            logger.warn("manage_schedule_commitment (cancel id={}) failed unexpectedly: {}", id, ex.message, ex)
            unavailableResponse("跨日約定取消暫時無法完成，請依目前對話自然回覆。")
        }

    private sealed interface ParseResult<out T> {
        data class Value<T>(
            val value: T,
        ) : ParseResult<T>

        data class Error(
            val message: String,
        ) : ParseResult<Nothing>
    }

    private fun parsePatch(
        obj: JsonObject,
        prefix: String,
    ): ParseResult<SchedulePatch>? {
        val rawStart =
            extractString(obj, "start_time")
                ?: return ParseResult.Error("${prefix}start_time (HH:mm) is required.")
        val startMinute =
            parseTimeMinutes(rawStart, allowEndOfDay = false)
                ?: return ParseResult.Error("Invalid ${prefix}start_time '$rawStart'; expected HH:mm (00:00..23:59).")

        val rawEnd =
            extractString(obj, "end_time")
                ?: return ParseResult.Error("${prefix}end_time (HH:mm) is required.")
        val endMinute =
            parseTimeMinutes(rawEnd, allowEndOfDay = true)
                ?: return ParseResult.Error("Invalid ${prefix}end_time '$rawEnd'; expected HH:mm (00:01..24:00).")

        val title =
            extractString(obj, "title")
                ?: return ParseResult.Error("${prefix}title must not be blank.")
        val detail =
            extractString(obj, "detail")
                ?: return ParseResult.Error("${prefix}detail must not be blank.")
        val statusText =
            extractString(obj, "status_text")
                ?: return ParseResult.Error("${prefix}status_text must not be blank.")

        val rawAvailability =
            extractString(obj, "availability")
                ?: return ParseResult.Error("${prefix}availability is required (AVAILABLE, BREAK, BUSY, or SLEEPING).")
        val availability =
            AvailabilityState.entries.firstOrNull { it.name.equals(rawAvailability, ignoreCase = true) }
                ?: return ParseResult.Error(
                    "Invalid ${prefix}availability '$rawAvailability'; must be AVAILABLE, BREAK, BUSY, or SLEEPING.",
                )

        return ParseResult.Value(
            SchedulePatch(
                startMinute = startMinute,
                endMinute = endMinute,
                activityTitle = title,
                activityDetail = detail,
                statusText = statusText,
                availability = availability,
            ),
        )
    }

    private fun formatTodaySuccess(result: DailyScheduleSplicer.SpliceResult.Success): String =
        buildJsonObject {
            put("status", "ok")
            put("applied_count", result.appliedBlocks.size)
            put(
                "applied_blocks",
                buildJsonArray {
                    for (block in result.appliedBlocks) {
                        add(
                            buildJsonObject {
                                put("time_range", block.formattedTimeRange())
                                put("title", block.activityTitle)
                                put("availability", block.availability.name)
                            },
                        )
                    }
                },
            )
            put("message", "已更新今日後續行程。")
        }.toString()

    private fun formatUpsertedResponse(commitment: ScheduleCommitment): String =
        buildJsonObject {
            put("status", "ok")
            put("action", "upsert")
            putJsonObject("commitment") {
                put("id", commitment.id)
                put("date", commitment.targetDate.toString())
                put("time_range", commitment.formattedTimeRange())
                put("title", commitment.activityTitle)
                put("availability", commitment.availability.name)
            }
            put("message", "已記錄跨日約定。")
        }.toString()

    private fun formatCancelledResponse(id: String): String =
        buildJsonObject {
            put("status", "ok")
            put("action", "cancel")
            put("id", id)
            put("message", "已取消該筆跨日約定。")
        }.toString()

    private fun extractString(
        arguments: JsonObject,
        key: String,
    ): String? =
        (arguments[key] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun parseDate(raw: String): LocalDate? =
        runCatching {
            LocalDate.parse(raw, STRICT_DATE_FORMATTER)
        }.getOrNull()

    private fun parseTimeMinutes(
        raw: String,
        allowEndOfDay: Boolean,
    ): Int? {
        val match = TIME_REGEX.matchEntire(raw) ?: return null
        val hours = match.groupValues[1].toIntOrNull() ?: return null
        val minutes = match.groupValues[2].toIntOrNull() ?: return null
        if (hours == 24 && minutes == 0) {
            return if (allowEndOfDay) ScheduleBlock.MINUTES_PER_DAY else null
        }
        if (hours !in 0..23 || minutes !in 0..59) {
            return null
        }
        val total = hours * 60 + minutes
        if (allowEndOfDay && total == 0) {
            return null
        }
        return total
    }

    companion object {
        const val TODAY_TOOL_NAME = "update_today_schedule"
        const val COMMITMENT_TOOL_NAME = "manage_schedule_commitment"

        private val TIME_REGEX = Regex("""^(\d{2}):(\d{2})$""")
        private val STRICT_DATE_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ISO_LOCAL_DATE.withResolverStyle(ResolverStyle.STRICT)

        val TODAY_TOOL_DECLARATION: GeminiInteractionTool =
            GeminiInteractionTool.function(
                name = TODAY_TOOL_NAME,
                description =
                    "Adjust Luna's remaining schedule for TODAY (strictly >= current time) by splicing one or more " +
                        "time-range patches into today's contiguous 00:00–24:00 schedule. Use when Luna agrees to join " +
                        "an activity, shift a plan, take a break, lock in (BUSY <= 4h), or go to sleep (SLEEPING <= 8h).",
                parameters =
                    buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("patches") {
                                put("type", "array")
                                put("description", "1 or more non-overlapping future time-range patches for today.")
                                putJsonObject("items") {
                                    put("type", "object")
                                    putJsonObject("properties") {
                                        putJsonObject("start_time") {
                                            put("type", "string")
                                            put("description", "Start time in 24-hour HH:mm format (00:00..23:59).")
                                        }
                                        putJsonObject("end_time") {
                                            put("type", "string")
                                            put("description", "End time in 24-hour HH:mm format (00:01..24:00).")
                                        }
                                        putJsonObject("title") {
                                            put("type", "string")
                                            put("description", "Short Traditional Chinese activity title.")
                                        }
                                        putJsonObject("detail") {
                                            put("type", "string")
                                            put("description", "Brief description of what Luna is doing.")
                                        }
                                        putJsonObject("status_text") {
                                            put("type", "string")
                                            put("description", "Short Discord status text.")
                                        }
                                        putJsonObject("availability") {
                                            put("type", "string")
                                            putJsonArray("enum") {
                                                add(JsonPrimitive("AVAILABLE"))
                                                add(JsonPrimitive("BREAK"))
                                                add(JsonPrimitive("BUSY"))
                                                add(JsonPrimitive("SLEEPING"))
                                            }
                                            put("description", "Availability state for this block.")
                                        }
                                    }
                                    put(
                                        "required",
                                        JsonArray(
                                            listOf(
                                                JsonPrimitive("start_time"),
                                                JsonPrimitive("end_time"),
                                                JsonPrimitive("title"),
                                                JsonPrimitive("detail"),
                                                JsonPrimitive("status_text"),
                                                JsonPrimitive("availability"),
                                            ),
                                        ),
                                    )
                                }
                            }
                        }
                        put("required", JsonArray(listOf(JsonPrimitive("patches"))))
                    },
            )

        val COMMITMENT_TOOL_DECLARATION: GeminiInteractionTool =
            GeminiInteractionTool.function(
                name = COMMITMENT_TOOL_NAME,
                description =
                    "Create, update, or cancel a cross-day Schedule Commitment for a future local calendar date " +
                        "(tomorrow through +7 days). Use when Luna agrees to a future plan or cancels/reschedules an existing one.",
                parameters =
                    buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("action") {
                                put("type", "string")
                                putJsonArray("enum") {
                                    add(JsonPrimitive("upsert"))
                                    add(JsonPrimitive("cancel"))
                                }
                                put("description", "'upsert' to create/update a commitment, or 'cancel' to remove one.")
                            }
                            putJsonObject("id") {
                                put("type", "string")
                                put("description", "Commitment ID (optional on upsert to update; preferred on cancel).")
                            }
                            putJsonObject("date") {
                                put("type", "string")
                                put("description", "Target local date in YYYY-MM-DD format (tomorrow..+7 days).")
                            }
                            putJsonObject("start_time") {
                                put("type", "string")
                                put("description", "Start time in 24-hour HH:mm format (00:00..23:59).")
                            }
                            putJsonObject("end_time") {
                                put("type", "string")
                                put("description", "End time in 24-hour HH:mm format (00:01..24:00).")
                            }
                            putJsonObject("title") {
                                put("type", "string")
                                put("description", "Short Traditional Chinese activity title.")
                            }
                            putJsonObject("detail") {
                                put("type", "string")
                                put("description", "Brief description of the planned activity.")
                            }
                            putJsonObject("status_text") {
                                put("type", "string")
                                put("description", "Short Discord status text for that block.")
                            }
                            putJsonObject("availability") {
                                put("type", "string")
                                putJsonArray("enum") {
                                    add(JsonPrimitive("AVAILABLE"))
                                    add(JsonPrimitive("BREAK"))
                                    add(JsonPrimitive("BUSY"))
                                    add(JsonPrimitive("SLEEPING"))
                                }
                                put("description", "Availability state for the commitment.")
                            }
                        }
                        put("required", JsonArray(listOf(JsonPrimitive("action"))))
                    },
            )

        fun invalidArgumentResponse(message: String): String =
            buildJsonObject {
                put("status", "invalid_argument")
                put("message", message)
            }.toString()

        fun rejectedResponse(reason: String): String =
            buildJsonObject {
                put("status", "rejected")
                put("reason", reason)
            }.toString()

        fun unavailableResponse(message: String = "行程調整工具暫時無法使用，請直接根據現有上下文回覆。"): String =
            buildJsonObject {
                put("status", "unavailable")
                put("message", message)
            }.toString()
    }
}
