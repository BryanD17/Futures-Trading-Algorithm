package com.topstep.trading.strategy.session;

import java.lang.reflect.Field;
import java.util.Objects;

/**
 * "No double-invalidate on the same bias event" (V5 Agent 02, task 7).
 *
 * <p>A setup that died because of a bias event E is re-armed after the
 * cooldown. The re-armed setup must never be killed AGAIN by that same
 * event E — only a NEW bias event may invalidate it. This guard identifies
 * bias events by a key:
 * <ul>
 *   <li>{@code SetupContext.biasEpoch} (Agent 03 increments it on every REAL
 *       flip) when that field exists — read reflectively so this class
 *       compiles and works before and after Agent 03 lands;</li>
 *   <li>otherwise its own sequence, bumped every time the runner's effective
 *       bias value changes ({@link #observeBias}).</li>
 * </ul>
 * With the epoch key only a "HTF bias flip" death can be a duplicate (a
 * NEUTRAL-beyond-grace death legitimately happens without a new epoch);
 * with the own key every "HTF bias ..." death is checked, because any real
 * NEUTRAL/flip evaluation changes the observed bias value first.
 *
 * <p>Candle-thread confined, like the rest of the runner's re-arm state.
 */
public final class RearmBiasGuard {

    private static final long NONE = Long.MIN_VALUE;
    private static final Field BIAS_EPOCH = lookupBiasEpoch();

    private long ownSeq;
    private Object lastSeenBias;
    private long keyAtRearm = NONE;
    private int suppressed;

    private static Field lookupBiasEpoch() {
        try {
            Field f = com.topstep.trading.strategy.stdvote.SetupContext.class.getField("biasEpoch");
            Class<?> t = f.getType();
            if (t == long.class || t == int.class || t == Long.class || t == Integer.class) {
                return f;
            }
        } catch (NoSuchFieldException | SecurityException ignored) {
            // Agent 03 not merged yet: fall back to the runner-local sequence.
        }
        return null;
    }

    /** True when the key comes from {@code SetupContext.biasEpoch}. */
    public boolean usesEpoch() {
        return BIAS_EPOCH != null;
    }

    /** Record the runner's current effective bias; a change is a new bias event. */
    public void observeBias(Object effectiveBias) {
        if (!Objects.equals(effectiveBias, lastSeenBias)) {
            ownSeq++;
            lastSeenBias = effectiveBias;
        }
    }

    /** The current bias-event key. */
    public long currentKey(Object setupContext) {
        if (BIAS_EPOCH != null && setupContext != null) {
            try {
                Object v = BIAS_EPOCH.get(setupContext);
                if (v instanceof Number n) return n.longValue();
            } catch (IllegalAccessException ignored) {
                // fall through to the own sequence
            }
        }
        return ownSeq;
    }

    /** Called when the runner re-arms: the new setup belongs to the current event. */
    public void onRearm(Object setupContext) {
        keyAtRearm = currentKey(setupContext);
    }

    /**
     * True when {@code reason} is a bias-caused death of a RE-ARMED setup
     * and no new bias event happened since the re-arm — i.e. the same event
     * is invalidating the machine a second time.
     */
    public boolean isDuplicateInvalidation(Object setupContext, String reason) {
        if (keyAtRearm == NONE || reason == null) return false;
        boolean biasDeath = usesEpoch()
                ? reason.startsWith("HTF bias flip")
                : reason.startsWith("HTF bias");
        return biasDeath && currentKey(setupContext) == keyAtRearm;
    }

    /** Count a suppressed duplicate (telemetry / tests). */
    public void recordSuppressed() {
        suppressed++;
    }

    public int suppressedCount() {
        return suppressed;
    }

    /** Full reset (runner initialize()). */
    public void reset() {
        ownSeq = 0;
        lastSeenBias = null;
        keyAtRearm = NONE;
        suppressed = 0;
    }
}
