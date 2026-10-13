package io.trimio.engine.perception.face

import io.trimio.engine.models.MediaAddOns
import io.trimio.engine.onnx.SessionConfig
import io.trimio.engine.perception.Frame
import io.trimio.engine.perception.Perception

/**
 * Builds [FaceZones] from frames sampled across a video: YuNet faces when the add-on is installed,
 * else skin-tone guesses (and the talking-head prior). Busy maps come from the frames themselves.
 */
class FaceZoneFinder(private val perception: Perception) {
    suspend fun zones(frames: List<Frame>): FaceZones {
        val busy = frames.map { BusyMap.of(it) }
        // NNAPI/QNN can compile YuNet once: its input is a fixed 640×640.
        val detected = perception.network("faces", MediaAddOns.faces, SessionConfig(staticShapes = true)) { net ->
            frames.map { YuNet.detect(net, it) }
        }
        val samples = frames.mapIndexed { i, frame ->
            val faces = detected?.get(i)
            if (faces != null) ZoneSample(frame.time, faces, busy[i]) else ZoneSample(frame.time, listOf(SkinFaces.guess(frame)), busy[i], guessed = true)
        }
        return FaceZones(samples)
    }
}
