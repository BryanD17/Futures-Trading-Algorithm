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
        resetAgent04Fields();
    }

    // AGENT-02 fields (V5 Agent 02 — session domain). Written by the runner
    // on EVERY primary candle from the candle timestamp (never wall clock);
    // not cleared by resetForNextWindow because they describe the current
    // candle, not the setup.
    /** Current {@code SessionWindow} name (ASIA .. WEEKEND); null before the first candle. */
    public String sessionWindow;
    /** True inside a prime killzone (London 02-05, NY AM 09:45-11, NY PM 13:45-15:45 ET). */
    public boolean primeKillzone;

    // AGENT-03 fields (V5). Market CONTEXT, not per-setup state:
    // resetForNextWindow() deliberately leaves them untouched.

    /** Count of REAL HTF bias flips (non-NEUTRAL direction changes). */
    public long biasEpoch;

    /** The day's dealing range (impulse leg) the bias is read from —
     *  Agent 04's OTE anchor. NaN until the range exists. G1 2026-09-28
     *  after 10:48 ET: 30759.25 / 30356.75 / EQ 30558.0. */
    public double rangeHigh = Double.NaN;
    public double rangeLow = Double.NaN;
    public double rangeEq = Double.NaN;

    // ── AGENT-04 fields (V5: chart-parity OTE, arm/alarm, one RR band) ─────

    /** Anchor the OTE zone was drawn on (null = legacy recordOteImpulse path). */
    public String oteAnchorMode;
    /** Where the anchored leg came from (SESSION / TRADING_DAY / CONTEXT(Agent03) / IMPULSE). */
    public String oteAnchorSource;
    /** Candle time the OTE ARMED (price first traded into the band after MSS). */
    public java.time.Instant oteArmedAt;
    /** Candle time the OTE ALARM fired (PD array overlaps band + reaction). */
    public java.time.Instant oteAlarmAt;
    /** Far edge (stop side) of the PD array behind the entry; NaN when none. */
    public double pdArrayFarEdge = Double.NaN;
    /** Post-sweep extreme (short: highest high since the sweep); NaN when unknown. */
    public double sweepExtreme = Double.NaN;
    /** Target ladder: T1 = 0.5, T2 = 0.382, T3 = leg terminus (0 = not planned). */
    public double t1;
    public double t2;
    public double t3;
    /** The target the signal carries (furthest ladder rung with RR &le; ceiling). */
    public double finalTarget;
    /** RR against T1 (the M7 floor is checked here); 0 = use {@link #rr}. */
    public double rrT1;
    /** True when the setup is planned under the SCALP RR profile (floor 0.8). */
    public boolean scalpProfile;
    /** Displacement bar (detector TF) and MSS bar timestamps. */
    public java.time.Instant displacementAt;
    public java.time.Instant mssAt;
    /** How M5's FVG linkage was satisfied (FVG / IFVG / BREAKER / OB / IMPULSE_FVG). */
    public String m5LinkKind;

    // ── AGENT-05.2 fields (V5: impulse-leg entry model, ote.entryModel) ────
    // Written by OteSetupDriver when the setup is armed on an OTE-band sweep
    // of the dealing range: M5/M6 are then proven on the range's IMPULSE LEG
    // (the displacement + structure break that CREATED the range) and the
    // validator re-checks these numbers. Null / NaN on the POST_SWEEP path.

    /** Entry model that armed this setup: IMPULSE_LEG | POST_SWEEP (null = not armed yet). */
    public String oteEntryModel;
    /** Why the impulse-leg model did / did not apply on the last SWEEP_DONE bar. */
    public String impulseLegVerdict;
    /** Impulse leg bounds (detector-bar timestamps): short = HH bar -> LL bar. */
    public java.time.Instant impulseLegStart;
    public java.time.Instant impulseLegEnd;
    /** M5 on the leg: displacement bar numbers (same detector rule + thresholds). */
    public double impulseDispRangeAtr = Double.NaN;
    public double impulseDispBody = Double.NaN;
    public double impulseDispAtrMult = Double.NaN;
    public double impulseDispBodyMin = Double.NaN;
    /** M6 on the leg: swing broken and the close that broke it. */
    public double impulseMssSwing = Double.NaN;
    public double impulseMssClose = Double.NaN;
    /** The swept level (IMPULSE_LEG: inside the OTE band of the dealing range). */
    public double impulseSweptLevel = Double.NaN;

    /** Reset only the AGENT-04 fields (called from {@link #resetForNextWindow}). */
    void resetAgent04Fields() {
        oteAnchorMode = null;
        oteAnchorSource = null;
        oteArmedAt = null;
        oteAlarmAt = null;
        pdArrayFarEdge = Double.NaN;
        sweepExtreme = Double.NaN;
        t1 = 0.0;
        t2 = 0.0;
        t3 = 0.0;
        finalTarget = 0.0;
        rrT1 = 0.0;
        scalpProfile = false;
        displacementAt = null;
        mssAt = null;
        m5LinkKind = null;
        // AGENT-05.2
        oteEntryModel = null;
        impulseLegVerdict = null;
        impulseLegStart = null;
        impulseLegEnd = null;
        impulseDispRangeAtr = Double.NaN;
        impulseDispBody = Double.NaN;
        impulseDispAtrMult = Double.NaN;
        impulseDispBodyMin = Double.NaN;
        impulseMssSwing = Double.NaN;
        impulseMssClose = Double.NaN;
        impulseSweptLevel = Double.NaN;
    }
}
