package io.trimio.engine.motion

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import io.trimio.engine.motion.art.MotionArt
import io.trimio.engine.motion.art.MotionArts
import io.trimio.engine.motion.visual.VectorIcon
import org.jetbrains.skia.Image
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A designer's After Effects template (Lottie) with its text layers as slots: the engine sets the
 * edit's own words in them, in the brand's type, moved word by word by the template's selectors.
 * Contact sheet of real templates when TRIMIO_TEMPLATES names a folder of Lottie files and
 * TRIMIO_SHEETS a folder to write to.
 */
class TemplateSlotsTest {
    /** A bar that slides in and a title whose words appear one after another (opacity 0 on what the selector still covers). */
    private val lowerThird = """
        {"v":"5.7.4","fr":30,"ip":0,"op":60,"w":600,"h":160,"ddd":0,"assets":[],
         "fonts":{"list":[{"fName":"Inter-Bold","fFamily":"Inter","fStyle":"Bold"}]},
         "layers":[
          {"ddd":0,"ind":1,"ty":5,"nm":"Title","ks":{"o":{"a":0,"k":100},"p":{"a":0,"k":[40,100,0]},"a":{"a":0,"k":[0,0,0]},"s":{"a":0,"k":[100,100,100]},"r":{"a":0,"k":0}},
           "t":{"d":{"k":[{"s":{"s":56,"f":"Inter-Bold","t":"YOUR TITLE HERE","j":0,"tr":0,"lh":67,"fc":[1,1,1]},"t":0}]},
                "a":[{"nm":"reveal","s":{"t":0,"r":1,"b":1,"sh":1,"s":{"a":1,"k":[{"t":0,"s":[0],"o":{"x":[0.3],"y":[0]},"i":{"x":[0.2],"y":[1]}},{"t":24,"s":[100]}]},"e":{"a":0,"k":100},"o":{"a":0,"k":0}},
                      "a":{"o":{"a":0,"k":0}}}]},
           "ip":0,"op":60,"st":0},
          {"ddd":0,"ind":2,"ty":4,"nm":"Bar","ks":{"o":{"a":0,"k":100},"p":{"a":1,"k":[{"t":0,"s":[-300,80,0],"o":{"x":0.3,"y":0},"i":{"x":0.2,"y":1}},{"t":12,"s":[300,80,0]}]},"a":{"a":0,"k":[0,0,0]},"s":{"a":0,"k":[100,100,100]},"r":{"a":0,"k":0}},
           "shapes":[{"ty":"gr","it":[{"ty":"rc","p":{"a":0,"k":[0,0]},"s":{"a":0,"k":[560,120]},"r":{"a":0,"k":16}},{"ty":"fl","c":{"a":0,"k":[0.1,0.5,0.9,1]},"o":{"a":0,"k":100}},
             {"ty":"tr","p":{"a":0,"k":[0,0]},"a":{"a":0,"k":[0,0]},"s":{"a":0,"k":[100,100]},"r":{"a":0,"k":0},"o":{"a":0,"k":100}}]}],
           "ip":0,"op":60,"st":0}
         ]}
    """.trimIndent()

    private fun node(art: MotionArt, texts: List<String>, x: Float = 300f, y: Float = 80f) =
        VectorNode(VectorIcon("template", "test", art.width, art.height, emptyList(), emptyList(), art), size = art.width, transform = Transform(x = x.anim, y = y.anim)).also {
            it.play = Anim.Keys(listOf(Anim.Key(0f, 0f, Easing.Linear), Anim.Key(10f, 10f, Easing.Linear)))
            it.texts = texts
        }

    private fun white(img: Image, x0: Int, y0: Int, x1: Int, y1: Int): Int {
        val bmp = org.jetbrains.skia.Bitmap.makeFromImage(img)
        var n = 0
        for (y in y0 until y1) for (x in x0 until x1) {
            val c = bmp.getColor(x, y)
            if ((c shr 16 and 0xFF) > 230 && (c shr 8 and 0xFF) > 230 && (c and 0xFF) > 230) n++
        }
        return n
    }

    @Test
    fun aTextLayerIsASlotAndAPictureStillRefusesIt() {
        assertNull(MotionArts.read(MotionArt.FORMAT_LOTTIE, lowerThird), "a picture with text is refused as before")
        val (art, why) = MotionArts.template(lowerThird)
        assertNotNull(art, why)
        val slot = art.slots.single()
        assertEquals("YOUR TITLE HERE", slot.text)
        assertEquals(56f, slot.size)
        assertTrue(slot.bold)
        assertEquals(0, slot.justify)
    }

    @Test
    fun theEditsWordsAppearWordByWordAndThePlaceholderNever() {
        val art = MotionArts.template(lowerThird).first!!
        fun frame(t: Float, texts: List<String>): Image {
            val root = Group(listOf(node(art, texts)), name = "root")
            return MotionTestKit(Composition(600, 160, 30, 2f, Color.Black, root), MediaSource { _, _ -> null }).draw(t)
        }
        val said = listOf("سود واقعی نه وعده")
        val start = white(frame(0.05f, said), 0, 0, 600, 160)
        val half = white(frame(0.45f, said), 0, 0, 600, 160)
        val done = white(frame(1.5f, said), 0, 0, 600, 160)
        assertTrue(start < done / 10, "nothing written before the selector opens: $start vs $done")
        assertTrue(half in (start + 1) until done, "words arrive one after another: $start < $half < $done")
        // No words for the slot: the designer's placeholder is never drawn.
        assertEquals(0, white(frame(1.5f, emptyList()), 0, 0, 600, 160))
    }

    /** What a folder of real templates gives the engine: each one read or refused (and why), its slots and size — a TSV for the template survey. */
    @Test
    fun survey() {
        val out = System.getenv("TRIMIO_SHEETS")?.takeIf { it.isNotBlank() }?.let(::File) ?: return
        val dir = System.getenv("TRIMIO_TEMPLATES")?.let(::File)?.takeIf { it.isDirectory } ?: return
        val rows = dir.listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }.map { f ->
            val (art, why) = MotionArts.template(runCatching { f.readText() }.getOrDefault(""))
            listOf(f.nameWithoutExtension, if (art != null) "ok" else "no", why.orEmpty().take(60), art?.slots?.size ?: 0, art?.width ?: 0, art?.height ?: 0, art?.duration ?: 0).joinToString("\t")
        }
        File(out, "survey.tsv").writeText(rows.joinToString("\n"))
    }

    @Test
    fun sheet() {
        val out = System.getenv("TRIMIO_SHEETS")?.takeIf { it.isNotBlank() }?.let(::File) ?: return
        val dir = System.getenv("TRIMIO_TEMPLATES")?.let(::File)?.takeIf { it.isDirectory } ?: return
        val real = System.getenv("TRIMIO_BOARD_FOOTAGE")?.let(::File)?.takeIf { it.isFile }?.let { Image.makeFromEncoded(it.readBytes()).toComposeImageBitmap() }
        val words = listOf("سود واقعی، نه وعده", "۹ از ۱۰ سیگنال استاپ")
        val rows = dir.listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }.mapNotNull { f ->
            val art = MotionArts.template(f.readText()).first ?: return@mapNotNull null
            val width = 960f
            listOf(0.25f, 0.5f, 0.95f).map { k ->
                val t = art.duration * k
                val n = node(art, words, x = 540f, y = 520f)
                val scaled = VectorNode(n.icon, size = width, transform = Transform(x = 540f.anim, y = 520f.anim)).also { it.play = n.play; it.texts = words }
                val root = Group(listOf(MediaNode("speaker", 1080f, 1920f, transform = Transform(x = 540f.anim, y = 960f.anim)), scaled), name = "root")
                val img = MotionTestKit(Composition(1080, 1920, 30, art.duration + 0.5f, Color.Black, root), MediaSource { _, _ -> real ?: FamilyLab.footage }).draw(t)
                FamilyLab.Tile(img, 540f, 560f, 1080f, "${f.nameWithoutExtension} ${"%.1f".format(t)}s")
            }
        }
        if (rows.isNotEmpty()) FamilyLab.sheet(rows, File(out, "templates.png"))
    }

    /** One look per template (as it rests, over the footage), six to a row, captioned with its id: for choosing the shelf. */
    @Test
    fun catalogue() {
        val out = System.getenv("TRIMIO_SHEETS")?.takeIf { it.isNotBlank() }?.let(::File) ?: return
        val dir = System.getenv("TRIMIO_CATALOGUE")?.let(::File)?.takeIf { it.isDirectory } ?: return
        val real = System.getenv("TRIMIO_BOARD_FOOTAGE")?.let(::File)?.takeIf { it.isFile }?.let { Image.makeFromEncoded(it.readBytes()).toComposeImageBitmap() }
        val tiles = dir.listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }.mapNotNull { f ->
            val art = MotionArts.template(runCatching { f.readText() }.getOrDefault("")).first ?: return@mapNotNull null
            val t = art.hold?.let { (it.start + it.endInclusive) / 2f } ?: (art.duration * 0.8f)
            val icon = VectorIcon(f.nameWithoutExtension, "template", art.width, art.height, emptyList(), emptyList(), art)
            val aspect = art.width / art.height
            val n = VectorNode(icon, size = if (aspect >= 1f) 700f else 700f / aspect, transform = Transform(x = 540f.anim, y = 540f.anim)).also {
                it.play = Anim.Keys(listOf(Anim.Key(0f, 0f, Easing.Linear), Anim.Key(30f, 30f, Easing.Linear)))
            }
            val root = Group(listOf(MediaNode("speaker", 1080f, 1920f, transform = Transform(x = 540f.anim, y = 960f.anim)), n), name = "root")
            val img = MotionTestKit(Composition(1080, 1920, 30, 31f, Color.Black, root), MediaSource { _, _ -> real ?: FamilyLab.footage }).draw(t)
            FamilyLab.Tile(img, 540f, 540f, 1080f, f.nameWithoutExtension)
        }
        tiles.chunked(36).forEachIndexed { i, page -> FamilyLab.sheet(page.chunked(6), File(out, "catalogue-$i.png"), px = 200) }
    }
}
