package io.trimio.engine.illustration.library

import io.trimio.core.model.text.TextKey
import io.trimio.engine.illustration.vector.Affine
import io.trimio.engine.illustration.vector.Drawing
import kotlin.random.Random

/** One Open Peeps figure: a pose (bust, standing or sitting) and the head's pieces. Keys are the library's ("bust/Device"). */
data class Peep(val pose: String, val hair: String, val face: String, val facialHair: String? = null, val accessory: String? = null) {
    val pieces: List<String> get() = listOfNotNull(pose, "hair/$hair", "face/$face", facialHair?.let { "facialHair/$it" }, accessory?.let { "accessories/$it" })
}

/** Who a request shows: how many people, and what can be read about them. */
data class Cast(
    val count: Int,
    val woman: Boolean,
    val man: Boolean,
    val old: Boolean,
    val doctor: Boolean,
    val faces: List<String>,
    val poses: List<String>,
    /** The words that made it (shown to the jury). */
    val words: List<String>,
) {
    val isEmpty: Boolean get() = count == 0
}

/**
 * Builds people from Open Peeps pieces: reads the cast from the request's words (who, how many, how
 * they feel, what they do) and dresses each figure deterministically from the seed — a woman in a
 * Persian film wears a headscarf, an older person grey hair, a doctor a coat; faces follow the mood.
 */
object People {
    /** Reads people from [subject] (who), [action] (pose) and [mood] (face); [count] overrides the number. */
    fun cast(subject: String, action: String, mood: String, count: Int = 0): Cast {
        val who = Words.terms(subject)
        val all = Words.terms("$subject $action $mood")
        fun hit(set: Set<String>, terms: List<String> = who) = terms.filter { Words.best(it, set) > 0f }
        // Numbers and plurals match exactly: «مرد» is one man even though «مردم» (people) starts the same.
        fun exact(set: Set<String>) = who.filter { it in set }
        val person = hit(PeopleWords.PERSON)
        if (person.isEmpty() && count <= 0) return Cast(0, false, false, false, false, emptyList(), emptyList(), emptyList())
        val group = exact(PeopleWords.GROUP)
        val n = when {
            count > 0 -> count
            exact(PeopleWords.TWO).isNotEmpty() -> 2
            exact(PeopleWords.THREE).isNotEmpty() || group.isNotEmpty() -> GROUP_SIZE
            else -> 1
        }
        val woman = exact(PeopleWords.WOMAN).isNotEmpty()
        val man = exact(PeopleWords.MAN).isNotEmpty()
        val faces = PeopleWords.FACES.firstOrNull { (set, _) -> all.any { Words.best(it, set) > 0f } }?.second.orEmpty()
        val poses = PeopleWords.POSES.firstOrNull { (set, _) -> Words.terms(action).any { Words.best(it, set) > 0f } }?.second
            ?: PeopleWords.POSES.firstOrNull { (set, _) -> all.any { Words.best(it, set) > 0f } }?.second.orEmpty()
        return Cast(
            count = n.coerceIn(1, MAX_PEOPLE),
            woman = woman,
            man = man,
            old = hit(PeopleWords.OLD).isNotEmpty(),
            doctor = hit(PeopleWords.DOCTOR).isNotEmpty(),
            faces = faces,
            poses = poses,
            words = (person + group).distinct(),
        )
    }

    /** The figures of [cast]: the first one follows the words exactly, the rest of a group vary around it. */
    fun dress(cast: Cast, library: Library, seed: Long, language: String, fullBody: Boolean): List<Peep> {
        val rnd = Random(seed)
        return (0 until cast.count).map { i ->
            // A group mixes people: one in three is the other sex unless the words fixed it, one older.
            val woman = when {
                cast.woman && !cast.man -> i == 0 || rnd.nextFloat() < MIXED_SHARE
                cast.man && !cast.woman -> i > 0 && rnd.nextFloat() < MIXED_SHARE
                else -> if (cast.count == 1) rnd.nextBoolean() else i % 3 == 2
            }
            val old = if (i == 0) cast.old else cast.count > 2 && i == 1
            val pose = pose(cast, library, rnd, fullBody || cast.count > 1, i)
            val hair = hair(woman, old, language, cast.doctor && i == 0, rnd)
            val face = cast.faces.takeIf { it.isNotEmpty() }?.let { it[if (i == 0) 0 else rnd.nextInt(it.size)] }
                ?: if (old && i == 0) "OldAged" else PeopleWords.CALM_FACES[rnd.nextInt(PeopleWords.CALM_FACES.size)]
            val beard = if (!woman && rnd.nextFloat() < BEARD_SHARE) {
                if (old) "GrayFull" else PeopleWords.BEARDS[rnd.nextInt(PeopleWords.BEARDS.size)]
            } else null
            val glasses = if (rnd.nextFloat() < GLASSES_SHARE || (old && rnd.nextBoolean())) PeopleWords.GLASSES[rnd.nextInt(PeopleWords.GLASSES.size)] else null
            Peep(pose, hair, face.takeIf { library.has("face/$it") } ?: "Calm", beard, glasses)
        }
    }

    private fun pose(cast: Cast, library: Library, rnd: Random, fullBody: Boolean, i: Int): String {
        val wanted = when {
            cast.doctor && i == 0 -> PeopleWords.DOCTOR_POSES
            cast.poses.isNotEmpty() && (i == 0 || rnd.nextFloat() < SAME_POSE) -> cast.poses
            fullBody -> PeopleWords.STANDING_DEFAULT
            else -> PeopleWords.BUST_DEFAULT
        }
        // In a full-body group, a bust pose is swapped for a standing one (busts would float).
        val pool = if (fullBody && cast.count > 1) wanted.filter { !it.startsWith("bust/") }.ifEmpty { PeopleWords.STANDING_DEFAULT } else wanted
        return pool.filter(library::has).ifEmpty { listOf("bust/Shirt") }.let { it[rnd.nextInt(it.size)] }
    }

    private fun hair(woman: Boolean, old: Boolean, language: String, doctor: Boolean, rnd: Random): String {
        val persian = TextKey.of(language).startsWith("fa")
        val pool = when {
            doctor && !woman -> listOf("Short", "ShortWavy")
            woman && persian -> PeopleWords.WOMEN_HAIR_FA
            woman && old -> PeopleWords.OLD_WOMEN_HAIR
            woman -> PeopleWords.WOMEN_HAIR
            old -> PeopleWords.OLD_MEN_HAIR
            else -> PeopleWords.MEN_HAIR
        }
        return pool[rnd.nextInt(pool.size)]
    }

    /** The figure as one drawing in Open Peeps units (the pose, then the head at its anchor), parts named by piece. */
    fun draw(peep: Peep, library: Library): Drawing {
        val body = library.piece(peep.pose) ?: Drawing.Empty
        val head = Affine.translate(HEAD_X, 0f)
        fun at(key: String?, x: Float, y: Float): Drawing = key?.let { library.piece(it) }?.transformed(head * Affine.translate(x, y)) ?: Drawing.Empty
        return body +
            at("hair/${peep.hair}", 0f, 0f) +
            at("face/${peep.face}", FACE_X, FACE_Y) +
            at(peep.facialHair?.let { "facialHair/$it" }, BEARD_X, BEARD_Y) +
            at(peep.accessory?.let { "accessories/$it" }, GLASSES_X, GLASSES_Y)
    }

    private const val GROUP_SIZE = 3
    private const val MAX_PEOPLE = 4
    private const val MIXED_SHARE = 0.34f
    private const val BEARD_SHARE = 0.45f
    private const val GLASSES_SHARE = 0.22f
    private const val SAME_POSE = 0.5f
    private const val HEAD_X = 225f
    private const val FACE_X = 159f
    private const val FACE_Y = 186f
    private const val BEARD_X = 123f
    private const val BEARD_Y = 338f
    private const val GLASSES_X = 47f
    private const val GLASSES_Y = 241f
}
