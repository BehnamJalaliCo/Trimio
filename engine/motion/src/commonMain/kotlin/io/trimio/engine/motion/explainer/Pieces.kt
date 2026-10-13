package io.trimio.engine.motion.explainer

/** Every primitive's piece. */
internal object Pieces {
    fun of(p: Primitive): Piece = when (p) {
        Primitive.Heading -> TextPieces.Heading
        Primitive.Kicker -> TextPieces.Kicker
        Primitive.BigWord -> TextPieces.BigWord
        Primitive.Stat -> TextPieces.Stat
        Primitive.Quote -> TextPieces.Quote
        Primitive.Note -> TextPieces.Note
        Primitive.Badge -> TextPieces.Badge
        Primitive.Icon -> TextPieces.Icon
        Primitive.Mark -> TextPieces.Mark
        Primitive.Chips -> ListPieces.Chips
        Primitive.IconTiles -> ListPieces.IconTiles
        Primitive.TaskTiles -> ListPieces.TaskTiles
        Primitive.Checklist -> ListPieces.Checklist
        Primitive.Steps -> SequencePieces.Steps
        Primitive.Timeline -> SequencePieces.Timeline
        Primitive.Grid -> SequencePieces.Grid
        Primitive.Stack -> SequencePieces.Stack
        Primitive.Chat -> SequencePieces.Chat
        Primitive.Meter -> DataPieces.Meter
        Primitive.Bars -> DataPieces.Bars
        Primitive.Blocks -> DataPieces.Blocks
        Primitive.Ring -> DataPieces.Ring
        Primitive.Dots -> DataPieces.Dots
        Primitive.Clock -> MeasurePieces.Clock
        Primitive.Scale -> MeasurePieces.Scale
        Primitive.Ladder -> MeasurePieces.Ladder
        Primitive.Funnel -> MeasurePieces.Funnel
        Primitive.Versus -> DiagramPieces.Versus
        Primitive.BeforeAfter -> DiagramPieces.BeforeAfter
        Primitive.Cycle -> DiagramPieces.Cycle
        Primitive.Flow -> DiagramPieces.Flow
        Primitive.Map -> DiagramPieces.Map
        Primitive.Branches -> FigurePieces.Branches
        Primitive.Tangle -> FigurePieces.Tangle
        Primitive.Thinking -> FigurePieces.Thinking
        Primitive.Profile -> FigurePieces.Profile
    }

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
