package com.topstep.trading.news.model;

/**
 * Alignment status between macro news bias and technical bias.
 * Used for confluence scoring in the strategy.
 */
public enum MacroAlignment {
    ALIGNED,    // News supports technical bias (+1 to confluence score)
    NEUTRAL,    // No strong news signal (0 to confluence score)
    OPPOSING    // News opposes technical bias (-1 or BLOCK depending on strength)
}
