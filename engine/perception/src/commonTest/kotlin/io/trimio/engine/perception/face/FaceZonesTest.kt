package io.trimio.engine.perception.face

import io.trimio.engine.onnx.Tensor
import io.trimio.engine.perception.Box
import io.trimio.engine.perception.Frame
import io.trimio.engine.perception.Perception
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FaceZonesTest {
    private val face = Face(Box(0.35f, 0.4f, 0.3f, 0.22f), emptyList(), 0.9f)

    @Test
    fun yunetDecodesOneConfidentCellIntoAFrameBox() {
        // One cell of stride 32 at row 5, column 3 with a 128×128 box centred on the cell's corner + (0.5, 0.5).
        val out = HashMap<String, Tensor>()
        for (stride in intArrayOf(8, 16, 32)) {
            val n = (YuNet.SIZE / stride) * (YuNet.SIZE / stride)
            val cls = FloatArray(n)
            val obj = FloatArray(n)
            val box = FloatArray(n * 4)
            if (stride == 32) {
                val i = 5 * (YuNet.SIZE / 32) + 3
                cls[i] = 0.9f
                obj[i] = 0.9f
                box[i * 4] = 0.5f
                box[i * 4 + 1] = 0.5f
                box[i * 4 + 2] = ln(4f)
                box[i * 4 + 3] = ln(4f)
            }
            out["cls_$stride"] = Tensor.of(longArrayOf(1, n.toLong(), 1), cls)
            out["obj_$stride"] = Tensor.of(longArrayOf(1, n.toLong(), 1), obj)
            out["bbox_$stride"] = Tensor.of(longArrayOf(1, n.toLong(), 4), box)
            out["kps_$stride"] = Tensor.of(longArrayOf(1, n.toLong(), 10), FloatArray(n * 10))
        }
        // A 640×640 frame: scale 1, so fractions are pixels / 640.
        val faces = YuNet.decode(out, 640, 640, 1f)
        assertEquals(1, faces.size)
        val b = faces.single().box
        assertEquals((3.5f * 32 - 64) / 640, b.x, 1e-4f)
        assertEquals((5.5f * 32 - 64) / 640, b.y, 1e-4f)
        assertEquals(128f / 640, b.w, 1e-4f)
        assertEquals(0.9f, faces.single().score, 1e-4f)
    }

    @Test
    fun letterboxedInputKeepsTheAspectAndPadsWithBlack() {
        val (t, scale) = YuNet.input(Frame.solid(360, 640, 10, 20, 30))
        assertEquals(1f, scale)
        val data = t.requireFloats()
        val plane = 640 * 640
        assertEquals(30f, data[0]) // blue first: OpenCV feeds BGR
        assertEquals(10f, data[2 * plane])
        assertEquals(0f, data[639], "right of the 360-px image is padding")
    }

    @Test
    fun placementNeverCoversAFace() {
        val zones = FaceZones(listOf(ZoneSample(0f, listOf(face), null)))
        val p = assertNotNull(zones.place(0.8f, 0.1f))
        assertFalse(p.box.intersects(zones.faces().single()), "placed $p over the face")
        assertTrue(p.box.y > face.box.bottom || p.box.bottom < face.box.y)
    }

    @Test
    fun aBlockTooBigForTheFreeSpaceIsRefused() {
        val big = Face(Box(0.1f, 0.05f, 0.8f, 0.85f), emptyList(), 0.9f)
        assertNull(FaceZones(listOf(ZoneSample(0f, listOf(big), null))).place(0.8f, 0.3f))
    }

    @Test
    fun placementPrefersTheBottomAndAvoidsTextAlreadyThere() {
        val zones = FaceZones(listOf(ZoneSample(0f, listOf(face), null)))
        val bottom = assertNotNull(zones.place(0.6f, 0.08f)).box
        assertTrue(bottom.centerY > 0.75f, "bottom first: $bottom")
        val sign = Box(0.06f, 0.72f, 0.88f, 0.14f)
        val moved = assertNotNull(zones.place(0.6f, 0.08f, avoid = listOf(sign))).box
        assertFalse(moved.intersects(sign), "kept clear of on-screen text: $moved")
    }

    @Test
    fun aSpanTakesEveryFaceSeenInIt() {
        val left = Face(Box(0.1f, 0.4f, 0.2f, 0.15f), emptyList(), 0.9f)
        val zones = FaceZones(listOf(ZoneSample(0f, listOf(face), null), ZoneSample(2f, listOf(left), null)))
        assertEquals(1, zones.faces(0f, 1f).size)
        assertEquals(2, zones.faces(0f, 3f).size)
        // A span between samples is covered by the nearest one.
        assertEquals(1, zones.faces(1.7f, 1.8f).size)
        assertTrue(abs(zones.eyeLine(0f, 1f)!! - (face.box.y + face.box.h * 0.4f)) < 1e-4f)
    }

    @Test
    fun busyMapFindsTheTexturedHalf() {
        val w = 90
        val h = 160
        val px = ByteArray(w * h * 3)
        for (y in 0 until h) for (x in 0 until w) {
            val v = if (y < h / 2) ((x + y) % 2) * 255 else 128
            for (c in 0 until 3) px[(y * w + x) * 3 + c] = v.toByte()
        }
        val map = BusyMap.of(Frame(w, h, px))
        assertTrue(map.mean(Box(0f, 0f, 1f, 0.45f)) > 0.8f)
        assertTrue(map.mean(Box(0f, 0.55f, 1f, 0.45f)) < 0.1f)
    }

    @Test
    fun withoutTheModelASkinBlobStandsInForTheFace() = runTest {
        val w = 120
        val h = 200
        val px = ByteArray(w * h * 3)
        for (i in 0 until w * h) {
            px[i * 3] = 40
            px[i * 3 + 1] = 90
            px[i * 3 + 2] = 160.toByte()
        }
        for (y in 50 until 90) for (x in 45 until 75) {
            val i = (y * w + x) * 3
            px[i] = 224.toByte()
            px[i + 1] = 172.toByte()
            px[i + 2] = 140.toByte()
        }
        val zones = FaceZoneFinder(Perception()).zones(listOf(Frame(w, h, px)))
        assertFalse(zones.measured)
        val guess = zones.samples.single().faces.single().box
        assertTrue(abs(guess.centerX - 0.5f) < 0.06f && abs(guess.centerY - 0.35f) < 0.06f, "skin guess $guess")
    }
}
