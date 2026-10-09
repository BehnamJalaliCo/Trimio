package io.trimio.engine.llm

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Compiles the JSON-schema subset Trimio uses for structured output into a llama.cpp GBNF grammar,
 * so a small on-device model *cannot* emit anything but a schema-valid document: no prose, no
 * markdown fences, no missing fields, no runaway arrays.
 *
 * Supported: `type` object/array/string/integer/number/boolean, `properties` (all emitted, in
 * declaration order), `enum` of strings, `items`, `minItems`/`maxItems`, `maxLength`.
 * Bounds are enforced by the grammar itself, which also keeps generation time predictable.
 */
object JsonSchemaGrammar {

    fun compile(schema: JsonObject): String {
        val rules = LinkedHashMap<String, String>()
        val root = visit(schema, "root", rules)
        if (root != "root") rules["root"] = root
        val out = StringBuilder()
        out.append("root ::= ").append(rules.remove("root")).append('\n')
        rules.forEach { (name, body) -> out.append(name).append(" ::= ").append(body).append('\n') }
        out.append(PRIMITIVES)
        return out.toString()
    }

    /** Returns an expression for [schema]; complex types become named rules in [rules]. */
    private fun visit(schema: JsonObject, name: String, rules: MutableMap<String, String>): String {
        schema["enum"]?.jsonArray?.let { values ->
            return values.joinToString(" | ", "(", ")") { literal(quoteJson(it.jsonPrimitive.content)) }
        }
        return when (val type = schema.type()) {
            "object" -> {
                val props = schema["properties"]?.jsonObject ?: JsonObject(emptyMap())
                val body = if (props.isEmpty()) {
                    "\"{\" ws \"}\""
                } else {
                    props.entries.mapIndexed { i, (key, sub) ->
                        val value = visit(sub.jsonObject, ruleName("$name-$key"), rules)
                        (if (i == 0) "" else "\",\" ws ") + literal(quoteJson(key)) + " ws \":\" ws " + value
                    }.joinToString(" ", "\"{\" ws ", " ws \"}\"")
                }
                define(name, body, rules)
            }
            "array" -> {
                val item = visit(schema["items"]?.jsonObject ?: JsonObject(emptyMap()), ruleName("$name-item"), rules)
                val min = schema["minItems"]?.jsonPrimitive?.intOrNull ?: 0
                val max = schema["maxItems"]?.jsonPrimitive?.intOrNull
                val more = "(\",\" ws $item)"
                val body = when {
                    max == 0 -> "\"[\" ws \"]\""
                    min == 0 -> "\"[\" ws ($item ${more}${repeat(0, max?.minus(1))})? ws \"]\""
                    else -> "\"[\" ws $item ${more}${repeat(min - 1, max?.minus(1))} ws \"]\""
                }
                define(name, body, rules)
            }
            "string" -> schema["maxLength"]?.jsonPrimitive?.intOrNull?.let { "\"\\\"\" char{0,$it} \"\\\"\"" } ?: "string"
            "integer" -> "integer"
            "number" -> "number"
            "boolean" -> "boolean"
            else -> error("Unsupported schema type '$type' at $name")
        }
    }

    private fun define(name: String, body: String, rules: MutableMap<String, String>): String {
        rules[name] = body
        return name
    }

    private fun repeat(min: Int, max: Int?): String = when {
        max == null -> if (min == 0) "*" else "{$min,}"
        else -> "{$min,$max}"
    }

    private fun JsonObject.type(): String? = when (val t = this["type"]) {
        is JsonPrimitive -> t.contentOrNull
        is JsonArray -> t.firstOrNull()?.jsonPrimitive?.contentOrNull
        else -> null
    }

    private fun ruleName(raw: String) = raw.replace(Regex("[^A-Za-z0-9-]"), "-")

    private fun quoteJson(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** A GBNF string literal matching [text] exactly. */
    private fun literal(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    // Whitespace is limited so the model cannot stall on endless indentation.
    private val PRIMITIVES = """
        |ws ::= [ \t\n]{0,4}
        |char ::= [^"\\\x7F\x00-\x1F] | "\\" (["\\/bfnrt] | "u" [0-9a-fA-F]{4})
        |string ::= "\"" char{0,400} "\""
        |integer ::= "-"? ([0-9] | [1-9] [0-9]{1,15})
        |number ::= "-"? ([0-9] | [1-9] [0-9]{1,15}) ("." [0-9]{1,6})?
        |boolean ::= "true" | "false"
        |""".trimMargin()
}

/** Schema adjustments per backend. */
object JsonSchemas {
    private val CLOUD_UNSUPPORTED = setOf("maxItems", "maxLength", "minLength", "minimum", "maximum")

    /**
     * Cloud structured-output modes reject length and numeric bounds; they are dropped (the
     * plan sanitiser clamps instead) and every object gets `additionalProperties: false`.
     */
    fun forCloud(schema: JsonObject): JsonObject = buildJsonObject {
        for ((key, value) in schema) {
            if (key in CLOUD_UNSUPPORTED) continue
            if (key == "minItems" && (value.jsonPrimitive.intOrNull ?: 0) > 1) continue
            put(key, transform(value, key))
        }
        if (schema["type"]?.jsonPrimitive?.contentOrNull == "object") {
            put("additionalProperties", JsonPrimitive(false))
            schema["properties"]?.jsonObject?.keys?.let { keys -> put("required", JsonArray(keys.map(::JsonPrimitive))) }
        }
    }

    private fun transform(value: JsonElement, key: String): JsonElement = when {
        key == "properties" -> JsonObject(value.jsonObject.mapValues { forCloud(it.value.jsonObject) })
        key == "items" && value is JsonObject -> forCloud(value)
        else -> value
    }
}
