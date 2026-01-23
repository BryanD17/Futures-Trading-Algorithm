package com.topstep.trading.news.model;

/**
 * Actions for trade gating based on news events.
 */
public enum GatingAction {
    ALLOW,        // Normal trading allowed
    BLOCK,        // No new entries
    REDUCE_SIZE   // Reduce position size
}
