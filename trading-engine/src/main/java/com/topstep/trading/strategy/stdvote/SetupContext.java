package com.topstep.trading.strategy.stdvote;

import com.topstep.trading.strategy.FairValueGap;
import com.topstep.trading.strategy.LiquiditySweep;
import com.topstep.trading.strategy.MarketBias;
import com.topstep.trading.strategy.TradeTier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Per-setup mutable state carried through the {@link StdvOteStrategy} state
 * machine for a single instrument.
 *
 * <p>This class is thread-confined to the engine loop — there is one
 * {@code SetupContext} in flight per instrument at any time. The fields are
 * intentionally exposed for direct write by the strategy and read by the API
 * layer (snapshotted before serialisation, see SA6).
 *
 * <p>Fields are populated as the state machine advances:
 * <ul>
 *   <li>{@code BIAS_SET}: {@code htfBias} only</li>
 *   <li>{@code MANIP_DONE}: + {@code legLow}, {@code legHigh}, {@code legBullish},
 *       {@code projections}</li>
 *   <li>{@code SWEEP_DONE}: + {@code sweep}, {@code raidScore}</li>
 *   <li>{@code DISPLACED}: + {@code displacement}, {@code fvg}</li>
 *   <li>{@code MSS_CONFIRMED}: + {@code mss}</li>
 *   <li>{@code OTE_ARMED}: + {@code ote}, {@code pdArrayInOte}</li>
 *   <li>{@code IN_TRADE}: + {@code entry}, {@code stop}, {@code rr}, {@code tier},
 *       {@code sizeRequest}, {@code sizeFilled}</li>
 * </ul>
 *
 * <p>{@code lastGateFailed} carries the most recent mandatory gate name
 * (M1..M9) when the validator rejects; the UI and journal surface this so the
 * user can see *why* a non-trade did not fire.
 */
public final class SetupContext {

    /** Instrument the setup belongs to (MNQ / MES / MGC). */
    public String symbol;

    /** Current state-machine position. */
    public SetupState state = SetupState.IDLE;

    /** HTF bias from the 3-of-4 rule. */
    public MarketBias htfBias = MarketBias.NEUTRAL;

    /** Manipulation leg extremes; populated at {@code MANIP_DONE}. */
    public double legLow;
    public double legHigh;
    /** {@code true} when the bias-aligned expansion travels UP from the leg. */
    public boolean legBullish;

    /** STDV ladder for the dealing range; populated at {@code MANIP_DONE}. */
    public List<StdvProjection> projections = Collections.emptyList();

    /** The liquidity sweep that primed the setup; populated at {@code SWEEP_DONE}. */
    public LiquiditySweep sweep;
    /** Raid quality score from {@code RaidQualityScorer}; 0–10. */
    public int raidScore;

    /** True once a displacement candle is confirmed. */
    public boolean displacement;

    /** The FVG left by the displacement; populated at {@code DISPLACED}. */
    public FairValueGap fvg;

    /** True once a MSS/CHoCH in the bias direction is confirmed. */
    public boolean mss;

    /** The OTE zone built on the post-MSS impulse leg. */
    public OteZone ote;

    /**
     * Best PD-array edge price inside the OTE zone, or {@code Double.NaN}
     * when no PD array lies inside.
     */
    public double pdArrayInOte = Double.NaN;

    /** Optional descriptor of which PD array backed the entry (FVG / OB / IFVG / Breaker). */
    public String pdArrayKind;

    /** True when the engine is inside a killzone for this symbol. */
    public boolean killzoneOpen;

    /** SMT divergence vs the correlate: CONFIRM | DIVERGENT | NEUTRAL | NOT_AVAILABLE. */
    public String smtState = "NEUTRAL";

    /** Computed tier (size driver); null if the setup does not qualify. */
    public TradeTier tier;

    /** Human-readable list of confluence factors that contributed to the tier. */
    public final List<String> confluenceFactors = new ArrayList<>();

    /** Planned entry price, in instrument ticks (rounded). */
    public double entry;

    /** Planned stop price (just beyond OTE 1.0 + buffer). */
    public double stop;

    /** Risk-to-reward at the -2.0 STDV target; the {@code M7} gate enforces >= 2.0. */
    public double rr;

    /** Size requested by the strategy before risk-engine review. */
    public int sizeRequest;

    /** Size actually emitted (post risk-engine clamp / news multiplier). */
    public int sizeFilled;

    /** Name of the first failed mandatory gate (M1..M9), or null if all passed. */
    public String lastGateFailed;

    /** Bar index (LTF) at which this setup entered {@code BIAS_SET}. */
    public long createdAtBar;

    /**
     * Bar index after which an idle setup is considered expired and reset to
     * {@code IDLE}. Default {@code 0} means uninitialised — strategy sets it
     * when the setup begins.
     */
    public long expiresAtBar;

    /** Reset every transient field; preserves {@code symbol}. */
    public void resetForNextWindow() {
        state = SetupState.IDLE;
        htfBias = MarketBias.NEUTRAL;
        legLow = 0.0;
        legHigh = 0.0;
        legBullish = false;
        projections = Collections.emptyList();
        sweep = null;
        raidScore = 0;
        displacement = false;
        fvg = null;
        mss = false;
        ote = null;
        pdArrayInOte = Double.NaN;
        pdArrayKind = null;
        killzoneOpen = false;
        smtState = "NEUTRAL";
        tier = null;
        confluenceFactors.clear();
        entry = 0.0;
        stop = 0.0;
        rr = 0.0;
        sizeRequest = 0;
        sizeFilled = 0;
        lastGateFailed = null;
        createdAtBar = 0L;
        expiresAtBar = 0L;
    }
}
