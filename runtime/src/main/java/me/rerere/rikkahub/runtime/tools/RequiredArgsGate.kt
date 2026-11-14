package me.rerere.rikkahub.runtime.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Pre-execution gate for schema-required tool parameters (AURA-021 US-012, z7 2026-11-14).
 *
 * The Jensen battery on z7 showed Spark ending step after step with
 * `stream finish sent kind=ToolCalls` while the tool chip rendered `Search web: `
 * with empty arguments: calls like `search_web` with `{}` (or `{"query": ""}`) parsed
 * as valid JSON and were dispatched anyway, returning junk the model replayed until
 * the turn died. The call was never *invalid* - it was *unable* - so nothing in the
 * loop rejected it, and the beta's only trace was a silent step counter.
 *
 * This gate answers, purely and testably, the two questions GenerationLoop needs
 * before executing: what the schema requires, and which of those keys are missing
 * from the parsed args (absent, JSON null, or blank string all count as missing;
 * a non-object args element is missing everything).
 */
object RequiredArgsGate {

    /**
     * The JSON-Schema `required` list of a tool definition's parameter schema.
     * Non-string array entries are ignored; a missing/foreign schema yields no
     * requirements (the gate stays out of the way of tools that declare nothing).
     */
    fun requiredNames(parameters: JsonObject?): List<String> =
        (parameters?.get("required") as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            .orEmpty()

    /**
     * Required names that are missing, null, or blank in [args]. A non-object
     * [args] (or null) is missing every required name - there is nowhere to look.
     */
    fun missing(required: List<String>, args: JsonElement?): List<String> =
        required.filter { name ->
            val v = (args as? JsonObject)?.get(name)
            v == null || v is JsonNull || (v is JsonPrimitive && v.isString && v.content.isBlank())
        }
}
