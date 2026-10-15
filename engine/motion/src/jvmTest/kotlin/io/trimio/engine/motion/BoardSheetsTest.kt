package io.trimio.engine.motion

import androidx.compose.ui.graphics.toComposeImageBitmap
import io.trimio.engine.motion.recipe.BoardFamilies
import io.trimio.engine.motion.recipe.Box
import io.trimio.engine.motion.recipe.Cue
import io.trimio.engine.motion.recipe.Look
import org.jetbrains.skia.Image
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The storyboard's cards ([BoardFamilies]) over a talking head, each caught as it lands and after
 * its states. Every card stays inside the frame's safe area and above the head. Contact sheet for
 * review when TRIMIO_SHEETS names a folder (TRIMIO_BOARD_FOOTAGE: a 1080×1920 frame of real footage).
 */
class BoardSheetsTest {
    private fun cue(text: String = "", at: Float = 0.5f, out: Float = 9f, h: Float = 0.2f) =
        Cue(text = text, at = at, out = out, x = 540f, y = 160f + h * 1920f / 2f, width = 900f, height = h * 1920f, rtl = true, overMedia = true)

    private val cards: List<Pair<String, Cue>> = listOf(
        "board-tiles" to cue("۱۰ سیگنال در روز", h = 0.2f).copy(
            value = 10f, items = listOf("loss:9:۹ تاش استاپ", "gain:1:فقط ۱ سود"), itemTimes = listOf(2.5f, 4.5f),
        ),
        "board-pill" to cue("یه کانال سیگنال", h = 0.06f).copy(icon = "send", label = "info"),
        "board-quote" to cue("ما داریم سود می‌دیم!", h = 0.12f).copy(value = 5f),
        "board-price" to cue("۲٬۰۰۰٬۰۰۰ تومان", h = 0.1f).copy(label = "ورودی این کانال:", mark = "loss"),
        "board-versus" to cue(h = 0.22f).copy(
            items = listOf("gain:ما|۵۰ تتر|کف ورود", "loss:اونا|۲٬۷۰۰٬۰۰۰ ت|حق عضویت", "gain:شارژ حساب خودت", "gain:پرداخت به ما: ۰"),
            itemTimes = listOf(0.5f, 0.8f, 3f, 5f),
        ),
        "board-steps" to cue(h = 0.1f).copy(items = listOf("کامنت «کانال»", "چک دایرکت", "سایت، کانال، ربات"), itemTimes = listOf(1f, 3f, 5f)),
        "board-chips" to cue(h = 0.12f).copy(items = listOf("پیج ما", "سایت ما"), itemTimes = listOf(1f, 2.2f), label = "شفاف‌ترین در کامیونیتی فارسی", value = 4f),
    )

    @Test
    fun everyCardStaysAboveTheHeadAndInsideTheFrame() {
        for ((name, c) in cards) {
            val built = BoardFamilies.all.first { it.name == name }.build(c, Look.Noir, FamilyLab.fitter)
            assertTrue(built.nodes.isNotEmpty(), name)
            val box = built.box ?: Box.around(c.x, c.y, c.width, c.height)
            assertTrue(box.inside(1080f, 1920f, 40f), "$name $box")
            // The band over the head: nothing of a card reaches the face (below ~38 % of the frame).
            assertTrue(box.bottom < 1920f * 0.38f, "$name reaches ${box.bottom}")
        }
    }

    @Test
    fun sheet() {
        val out = System.getenv("TRIMIO_SHEETS")?.takeIf { it.isNotBlank() }?.let(::File) ?: return
        val real = System.getenv("TRIMIO_BOARD_FOOTAGE")?.let(::File)?.takeIf { it.isFile }?.let { Image.makeFromEncoded(it.readBytes()).toComposeImageBitmap() }
        val rows = cards.map { (name, c) ->
            listOf(c.at + 0.6f, 3.2f, 5.5f, 8.7f).map { t ->
                val built = BoardFamilies.all.first { it.name == name }.build(c, Look.Noir, FamilyLab.fitter)
                val root = Group(listOf(MediaNode("speaker", 1080f, 1920f, transform = Transform(x = 540f.anim, y = 960f.anim))) + built.nodes, name = "root")
                val img = MotionTestKit(Composition(1080, 1920, 30, 10f, Look.Noir.canvas, root), MediaSource { _, _ -> real ?: FamilyLab.footage }).draw(t)
                FamilyLab.Tile(img, 540f, 540f, 1080f, "$name @${t}s")
            }
        }
        FamilyLab.sheet(rows, out.resolve("board-cards.png"), px = 300)
    }
}
