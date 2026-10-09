package io.trimio.core.pipeline

import io.trimio.core.model.audio.AudioFeatures
import io.trimio.core.model.audio.PcmAudio
import io.trimio.core.model.input.MediaInfo
import io.trimio.core.model.style.StyleSpec
import io.trimio.core.model.timeline.Timeline
import io.trimio.core.model.transcript.Transcript

/** Typed key for a value one stage produces and later stages consume. */
class ArtifactKey<T : Any>(val name: String) {
    override fun toString() = "ArtifactKey($name)"
}

/** Blackboard shared by the stages of one job. */
class Artifacts {
    private val values = mutableMapOf<String, Any>()

    operator fun <T : Any> set(key: ArtifactKey<T>, value: T) {
        values[key.name] = value
    }

    @Suppress("UNCHECKED_CAST")
    operator fun <T : Any> get(key: ArtifactKey<T>): T? = values[key.name] as T?

    fun <T : Any> require(key: ArtifactKey<T>): T =
        get(key) ?: error("Missing artifact ${key.name}; did an earlier stage fail to produce it?")

    operator fun contains(key: ArtifactKey<*>): Boolean = key.name in values
}

/** Well-known artifacts passed between the standard stages. */
object StandardArtifacts {
    val MediaInfo = ArtifactKey<MediaInfo>("media-info")

    /** Loudness-normalised mono speech audio at 16 kHz: the input of recognition and analysis. */
    val CleanAudio = ArtifactKey<PcmAudio>("clean-audio")
    val AudioFeatures = ArtifactKey<AudioFeatures>("audio-features")
    val Transcript = ArtifactKey<Transcript>("transcript")
    val Timeline = ArtifactKey<Timeline>("timeline")

    /** The resolved style for the job: the chosen pack's spec after the Director's QC adjustments. */
    val Style = ArtifactKey<StyleSpec>("style")

    /** Path of the final encoded file. */
    val Output = ArtifactKey<String>("output")
}
