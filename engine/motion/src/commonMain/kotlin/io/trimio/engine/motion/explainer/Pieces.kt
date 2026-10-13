package io.trimio.engine.motion.explainer

/** Every primitive's piece. */
internal object Pieces {
    private val table: Map<Primitive, Piece> by lazy {
        mapOf(
            Primitive.Heading to TextPieces.Heading,
            Primitive.Kicker to TextPieces.Kicker,
            Primitive.BigWord to TextPieces.BigWord,
            Primitive.Stat to TextPieces.Stat,
            Primitive.Quote to TextPieces.Quote,
            Primitive.Note to TextPieces.Note,
            Primitive.Badge to TextPieces.Badge,
            Primitive.Icon to TextPieces.Icon,
            Primitive.Mark to TextPieces.Mark,
            Primitive.Chips to ListPieces.Chips,
            Primitive.IconTiles to ListPieces.IconTiles,
            Primitive.TaskTiles to ListPieces.TaskTiles,
            Primitive.Checklist to ListPieces.Checklist,
            Primitive.Steps to SequencePieces.Steps,
            Primitive.Timeline to SequencePieces.Timeline,
            Primitive.Grid to SequencePieces.Grid,
            Primitive.Stack to SequencePieces.Stack,
            Primitive.Chat to SequencePieces.Chat,
            Primitive.Meter to DataPieces.Meter,
            Primitive.Bars to DataPieces.Bars,
            Primitive.Blocks to DataPieces.Blocks,
            Primitive.Ring to DataPieces.Ring,
            Primitive.Dots to DataPieces.Dots,
            Primitive.Clock to MeasurePieces.Clock,
            Primitive.Scale to MeasurePieces.Scale,
            Primitive.Ladder to MeasurePieces.Ladder,
            Primitive.Funnel to MeasurePieces.Funnel,
            Primitive.Versus to DiagramPieces.Versus,
            Primitive.BeforeAfter to DiagramPieces.BeforeAfter,
            Primitive.Cycle to DiagramPieces.Cycle,
            Primitive.Flow to DiagramPieces.Flow,
            Primitive.Map to DiagramPieces.Map,
            Primitive.Branches to FigurePieces.Branches,
            Primitive.Tangle to FigurePieces.Tangle,
            Primitive.Thinking to FigurePieces.Thinking,
            Primitive.Profile to FigurePieces.Profile,
        )
    }

    fun of(p: Primitive): Piece = table.getValue(p)

    /** Primitives whose value a "fill" moves; on the others a fill reads as a highlight. */
    val fillable: Set<Primitive> = setOf(
        Primitive.Stat, Primitive.Meter, Primitive.Bars, Primitive.Blocks, Primitive.Ring, Primitive.Dots, Primitive.Clock,
        Primitive.Scale, Primitive.Ladder, Primitive.Timeline, Primitive.Steps, Primitive.TaskTiles, Primitive.Checklist,
    )

    /** Primitives whose items can be a bare count (a check's picks name them even when they have no labels). */
    val counted: Set<Primitive> = setOf(Primitive.TaskTiles, Primitive.Blocks, Primitive.Dots, Primitive.Branches)

    /** Primitives that are words: a whole-element highlight underlines them; marks sit under them. */
    val wordy: Set<Primitive> = setOf(Primitive.Heading, Primitive.Kicker, Primitive.BigWord, Primitive.Note, Primitive.Stat, Primitive.Mark)

    /** A primitive for a beat that creates an element without naming one. */
    fun guess(c: BeatContent): Primitive = when {
        c.items.isNotEmpty() && c.items.any { it.value != null } -> Primitive.Bars
        c.items.isNotEmpty() && c.items.any { it.visual.isNotEmpty() } -> Primitive.IconTiles
        c.items.isNotEmpty() -> Primitive.Chips
        c.value != null -> if (c.value <= 1.0) Primitive.Meter else Primitive.Stat
        c.visual.isNotEmpty() -> Primitive.Icon
        else -> Primitive.Heading
    }
}
