package io.trimio.core.model.timeline

import kotlinx.serialization.json.Json

/**
 * JSON codec for [Timeline]. The same schema is used for persistence, for LLM structured
 * output and for style-pack rule fixtures, so it must stay stable and forward compatible.
 */
object TimelineJson {

    val format: Json = Json {
        classDiscriminator = "type"
        encodeDefaults = true
        // Newer app versions may add fields; older readers must not crash on them.
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private val pretty: Json = Json(format) { prettyPrint = true }

    fun encode(timeline: Timeline, prettyPrint: Boolean = false): String =
        (if (prettyPrint) pretty else format).encodeToString(Timeline.serializer(), timeline)

    fun decode(json: String): Timeline {
        val timeline = format.decodeFromString(Timeline.serializer(), json)
        require(timeline.version <= Timeline.CURRENT_VERSION) {
            "Timeline schema v${timeline.version} is newer than supported v${Timeline.CURRENT_VERSION}"
        }
        return timeline
    }
}
