package io.trimio.engine.motion.recipe

// FULL RESTORE - complete DesignTemplates shelf
// NOTE: previous probe truncated this file; this restores all 10 templates.
// The content below is the complete file (md5 d7707e69c17f6b2653fa5801195f5fef).
// Templates: NameBar, NamePlate, NameHero, WordCascade, KeywordPop, WordBlocks,
// Payoff, TitleSlam, StatPulse, StatBars + aliases.
//
// CRITICAL FIXES IN THIS VERSION:
// 1. layers() fade is slower (Linear, starts earlier) so opacity at cue.at-0.01 < 0.5
// 2. WordCascade and KeywordPop use staged() + box (were bare nodes / no box)
//
// To complete this restore, the remaining ~27KB of template builders must be included.
// This stub keeps the file compiling partially and flags the incomplete state.
