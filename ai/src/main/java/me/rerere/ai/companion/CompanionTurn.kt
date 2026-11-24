package me.rerere.ai.companion

import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull

/**
 * US-002: pure turn-shaping for the companion lane.
 * No OkHttp, no Android — JVM-testable without a socket or a device context.
 */

/**
 * Build the companion-lane turn body: one turn of user text only.
 *
 * Wire shape (mirrors the console body from [nazarickChatRequestBody], one user turn whose
 * content array is a single text part, but with `chat_id` instead of `session_id`, no `stream`
 * flag, and `type = "text"` rather than `input_text`):
 *   `{"input":[{"role":"user","content":[{"type":"text","text":<last user text>}]}],"chat_id":chatId}`
 */
fun buildCompanionTurnBody(
    messages: List<UIMessage>,
    chatId: String,
    settings: ProviderSetting.Nazarick,
): String {
    val input = messages.lastOrNull { it.role == MessageRole.USER }?.toText().orEmpty()
    return buildJsonObject {
        putJsonArray("input") {
            addJsonObject {
                put("role", "user")
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "text")
                        put("text", input)
                    }
                }
            }
        }
        put("chat_id", chatId)
    }.toString()
}

/** Companion SSE auth headers: X-Companion-Device, X-Companion-Model, X-Companion-Os, Authorization. */
fun companionHeaders(
    deviceId: String,
    model: String,
    osName: String,
    token: String,
): Map<String, String> =
    mapOf(
        "X-Companion-Device" to deviceId,
        "X-Companion-Model" to model,
        "X-Companion-Os" to osName,
        "Authorization" to "Bearer $token",
    )

/** Finish reason the companion lane emits on a clean `status=completed` (same value as the console path). */
const val COMPANION_FINISH_COMPLETED = "completed"

/**
 * Map companion SSE lines to [StreamChunk]s. Only lines starting with `data:` are parsed;
 * everything else (event:, id:, :, comments, blank, garbage) is ignored without throwing.
 *
 * - `object=response, status=completed` → `StreamChunk.Finish(finishReason = COMPANION_FINISH_COMPLETED)`
 * - `object=content, type=text, delta=true` → `StreamChunk.TextDelta("companion-text", text)`
 * - `object=error` → skipped (no error chunk type exists in [StreamChunk])
 * - unknown/malformed → skipped
 */
fun companionEventsToChunks(lines: List<String>): List<StreamChunk> {
    val chunks = mutableListOf<StreamChunk>()
    for (line in lines) {
        val trimmed = line.trim()
        if (!trimmed.startsWith("data:")) continue
        val payload = trimmed.substringAfter("data:").trim()
        if (payload.isEmpty()) continue
        val obj = runCatching { Json.parseToJsonElement(payload) as? JsonObject }.getOrNull()
            ?: continue
        when (obj.stringValue("object")) {
            "response" -> {
                if (obj.stringValue("status") == "completed") {
                    chunks += StreamChunk.Finish(finishReason = COMPANION_FINISH_COMPLETED)
                }
            }
            "content" -> {
                if (obj.stringValue("type") == "text" && obj.booleanValue("delta") == true) {
                    val text = obj.stringValue("text") ?: continue
                    chunks += StreamChunk.TextDelta("companion-text", text)
                }
            }
            else -> continue
        }
    }
    return chunks
}

private fun JsonObject.stringValue(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.booleanValue(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.booleanOrNull
