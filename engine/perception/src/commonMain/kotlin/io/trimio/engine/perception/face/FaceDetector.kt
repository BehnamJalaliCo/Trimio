package io.trimio.engine.perception.face

import io.trimio.engine.onnx.Network
import io.trimio.engine.onnx.Tensor
import io.trimio.engine.perception.Box
import io.trimio.engine.perception.Frame
import io.trimio.engine.perception.suppress
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sqrt

/** A face: its box in frame fractions, five landmarks (eyes, nose tip, mouth corners) and the detector's confidence. */
data class Face(val box: Box, val landmarks: List<Pair<Float, Float>>, val score: Float) {
    /** The eye line in frame fractions (mean of both eyes), where a viewer looks first. */
    val eyeY: Float get() = if (landmarks.size >= 2) (landmarks[0].second + landmarks[1].second) / 2f else box.y + box.h * EYES

    private companion object {
        const val EYES = 0.4f
    }
}

/**
 * YuNet (OpenCV Zoo, 2023mar, MIT): a 0.1–0.2 MB face detector. The frame is letterboxed into the
 * network's 640×640 BGR input (raw 0..255, no normalisation, as OpenCV's FaceDetectorYN feeds it);
 * the three strides' class and objectness scores are already sigmoids, the score is their geometric
 * mean, and boxes decode from cell offsets with exponential sizes. Post-processing follows OpenCV's
 * `face_detect.cpp` exactly, so results match its demo.
 */
object YuNet {
    const val SIZE = 640
    private val STRIDES = intArrayOf(8, 16, 32)
    private const val LANDMARKS = 5

    /** The network input for [frame] and the scale that maps network pixels back to frame fractions. */
    fun input(frame: Frame): Pair<Tensor, Float> {
        val scale = min(SIZE.toFloat() / frame.width, SIZE.toFloat() / frame.height)
        val w = (frame.width * scale).toInt().coerceIn(1, SIZE)
        val h = (frame.height * scale).toInt().coerceIn(1, SIZE)
        val small = frame.resized(w, h)
        val plane = SIZE * SIZE
        val data = FloatArray(Frame.CHANNELS * plane)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * SIZE + x
                data[i] = small.b(x, y).toFloat()
                data[plane + i] = small.g(x, y).toFloat()
                data[2 * plane + i] = small.r(x, y).toFloat()
            }
        }
        return Tensor.of(longArrayOf(1, Frame.CHANNELS.toLong(), SIZE.toLong(), SIZE.toLong()), data) to scale
    }

    /** Faces from the network's twelve outputs, in fractions of a [frameW]×[frameH] frame letterboxed at [scale]. */
    fun decode(out: Map<String, Tensor>, frameW: Int, frameH: Int, scale: Float, threshold: Float = 0.6f, nms: Float = 0.3f): List<Face> {
        val faces = ArrayList<Face>()
        for (stride in STRIDES) {
            val cls = out.getValue("cls_$stride").requireFloats()
            val obj = out.getValue("obj_$stride").requireFloats()
            val bbox = out.getValue("bbox_$stride").requireFloats()
            val kps = out.getValue("kps_$stride").requireFloats()
            val cols = SIZE / stride
            for (i in cls.indices) {
                val score = sqrt(cls[i].coerceIn(0f, 1f) * obj[i].coerceIn(0f, 1f))
                if (score < threshold) continue
                val r = i / cols
                val c = i % cols
                val cx = (c + bbox[i * 4]) * stride
                val cy = (r + bbox[i * 4 + 1]) * stride
                val w = exp(bbox[i * 4 + 2]) * stride
                val h = exp(bbox[i * 4 + 3]) * stride
                val fw = frameW * scale
                val fh = frameH * scale
                val box = Box.of((cx - w / 2) / fw, (cy - h / 2) / fh, (cx + w / 2) / fw, (cy + h / 2) / fh)
                val marks = (0 until LANDMARKS).map { n -> (kps[i * 10 + 2 * n] + c) * stride / fw to (kps[i * 10 + 2 * n + 1] + r) * stride / fh }
                faces += Face(box, marks, score)
            }
        }
        return suppress(faces, nms, Face::box, Face::score)
    }

    /** Runs the detector on one frame. */
    fun detect(net: Network, frame: Frame, threshold: Float = 0.6f): List<Face> {
        val (tensor, scale) = input(frame)
        val out = net.run(mapOf(net.inputNames.first() to tensor))
        return decode(out, frame.width, frame.height, scale, threshold)
    }
}
