package com.topstep.trading.notify;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Fixed-decimal price rendering shared by every payload this package emits.
 *
 * <p>BigDecimal with HALF_UP rather than {@code String.format("%.2f")}, because
 * tick prices such as the index micros' 0.25 increments land on binary floating
 * point representations that {@code %.2f} rounds inconsistently across values.
 * Gold at 0.10 ticks has the same problem.
 */
final class Prices {

    private Prices() {}

    /** Render at a fixed number of decimals, or "n/a" for a non-finite value. */
    static String px(double value, int decimals) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return "n/a";
        return BigDecimal.valueOf(value)
                .setScale(decimals, RoundingMode.HALF_UP)
                .toPlainString();
    }
}
