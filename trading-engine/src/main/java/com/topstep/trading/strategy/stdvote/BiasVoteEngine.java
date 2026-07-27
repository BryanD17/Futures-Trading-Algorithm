package com.topstep.trading.strategy.stdvote;

import com.topstep.trading.chartstate.KnownLevel;
import com.topstep.trading.strategy.DailyAmdCycleTracker.DailyPhase;
import com.topstep.trading.strategy.HtfTrendAnalyzer.HtfTrendState;
import com.topstep.trading.strategy.MarketBias;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The 3-of-4 HTF bias vote per {@code STDV_OTE_MODEL.md} §H1 (V3 Agent 03).
 *
 * <p>§H1 verbatim (the spec — pasted in Appendix A-01 of the V3 master doc):
 * four sources each cast one vote — HtfTrendAnalyzer trend state,
 * DailyAmdCycleTracker distribution-leg direction, price vs true day open
 * (discount → bullish, premium → bearish), and daily draw on liquidity
 * (toward PDH = bullish, toward PDL = bearish). ≥3 same-direction votes ⇒
 * BULLISH/BEARISH, otherwise NEUTRAL and the engine stands down.
 *
 * <p>Where §H1 is silent, this document's defaults apply (recorded in
 * Appendix A-03): ABSTAIN semantics (a vote declining to opine never counts
 * for either side — with 2+ abstentions 3-of-4 is impossible and the vote
 * is NEUTRAL by construction), the equilibrium band around the day open,
 * and V4's tie rules.
 *
 * <p>DETERMINISTIC AND PURE: {@link #evaluate} is a function of its inputs
 * only — no wall-clock reads, evaluated on the same 15m cadence the legacy
 * bias uses. Counters are session-scoped (JVM lifetime) and thread-safe.
 *
 * <h2>Modes ({@code bias.vote.mode}, DEFAULT LOG)</h2>
 * <ul>
 *   <li>{@code LEGACY} — the engine is never evaluated; byte-identical.</li>
 *   <li>{@code LOG} — evaluated every bias cadence, [VOTE] line + agreement
 *       counters recorded; the LEGACY bias still decides.</li>
 *   <li>{@code VOTE} — {@code finalBias} replaces the legacy value at the
 *       ONE seam the runner feeds {@code core.recordHtfBias} (B12: single
 *       seam so modes cannot diverge across call sites).</li>
 * </ul>
 */
public final class BiasVoteEngine {

    /** System property: bias source switch. */
    public static final String MODE_PROPERTY = "bias.vote.mode";

    /**
     * System property (V3 Agent 05, DEFAULT false — measure first): when
     * true, V1 additionally consults the seeded H4 series' fractal
     * structure ({@link FractalSwings} — the SAME unmodified swing formula
     * R0 uses) and ABSTAINS when H4 structure contradicts the 15m/30m
     * read. It never adds a direction of its own — consult, not overrule.
     */
    public static final String INCLUDE_H4_PROPERTY = "bias.v1.includeH4";

    /** Three-position rollout switch (Rollout Doctrine). */
    public enum VoteMode { LEGACY, LOG, VOTE }

    /** One vote's direction. ABSTAIN never counts for either side. */
    public enum VoteDirection { BULL, BEAR, ABSTAIN }

    /** One of the four §H1 votes with its human-readable rationale. */
    public record BiasVote(String source, VoteDirection direction, String detail) {
        String token() {
            return source + "=" + direction
                    + (detail.isEmpty() ? "" : "(" + detail + ")");
        }
    }

    /** Full result of one 3-of-4 evaluation. */
    public record BiasVoteResult(MarketBias finalBias, List<BiasVote> votes,
                                 int alignedBull, int alignedBear, int abstains) {}

    /** Everything one evaluation needs — assembled by the runner per 15m bar.
     *  The V3-Agent-05 extras (weekly levels for V4 detail, H4 series for
     *  the optional V1 consult) default to empty via the 6-arg convenience
     *  constructor, so pre-Agent-05 callers and tests compile unchanged. */
    public record VoteInputs(HtfTrendState trendState,
                             DailyPhase amdPhase,
                             Optional<Double> trueDayOpen,
                             double price,
                             Optional<KnownLevel> pdh,
                             Optional<KnownLevel> pdl,
                             Optional<KnownLevel> pwh,
                             Optional<KnownLevel> pwl,
                             List<com.topstep.trading.domain.Candle> h4Series) {
        public VoteInputs(HtfTrendState trendState, DailyPhase amdPhase,
                          Optional<Double> trueDayOpen, double price,
                          Optional<KnownLevel> pdh, Optional<KnownLevel> pdl) {
            this(trendState, amdPhase, trueDayOpen, price, pdh, pdl,
                    Optional.empty(), Optional.empty(), List.of());
        }
    }

    // Per-symbol registry for the API layer (OteAgreementStats pattern).
    private static final Map<String, BiasVoteEngine> REGISTRY = new ConcurrentHashMap<>();

    /** Build from system properties, register for API access, log config. */
    public static BiasVoteEngine install(String symbol, double tickSize) {
        VoteMode mode = parseMode(System.getProperty(MODE_PROPERTY, "LOG"));
        int eqBand = Integer.getInteger(
                PremiumDiscountEvaluator.EQ_BAND_TICKS_PROPERTY,
                PremiumDiscountEvaluator.DEFAULT_EQ_BAND_TICKS);
        BiasVoteEngine e = new BiasVoteEngine(symbol, tickSize, mode, eqBand);
        e.includeH4 = Boolean.getBoolean(INCLUDE_H4_PROPERTY);
        REGISTRY.put(symbol, e);
        System.out.println("[VOTE " + symbol + "] config: mode=" + mode
                + " eqBandTicks=" + eqBand + " v1.includeH4=" + e.includeH4);
        return e;
    }

    /** Registered engine for a symbol (empty before the runner wires it). */
    public static Optional<BiasVoteEngine> get(String symbol) {
        return Optional.ofNullable(REGISTRY.get(symbol));
    }

    static VoteMode parseMode(String raw) {
        if (raw == null) return VoteMode.LOG;
        try {
            return VoteMode.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            System.out.println("[VOTE] WARN: invalid " + MODE_PROPERTY + "='" + raw
                    + "', using default LOG");
            return VoteMode.LOG;
        }
    }

    private final String symbol;
    private final double tickSize;
    private final VoteMode mode;
    private final int eqBandTicks;
    /** {@code bias.v1.includeH4} — set by install(); false in direct ctor. */
    private volatile boolean includeH4;

    /** True when V1 should consult the H4 series (runner input hint). */
    public boolean includeH4() {
        return includeH4;
    }

    /** Test hook mirroring configureBiasHysteresis's pattern. */
    void configureIncludeH4(boolean enabled) {
        this.includeH4 = enabled;
    }

    // ── Session-scoped agreement counters (labeled; NOT persisted) ───────
    private final AtomicLong evaluations = new AtomicLong();
    private final AtomicLong agree = new AtomicLong();
    private final AtomicLong disagree = new AtomicLong();
    private final AtomicLong voteNeutralLegacyDirectional = new AtomicLong();
    private final AtomicLong voteDirectionalLegacyNeutral = new AtomicLong();

    /** Latest result, for the [GATES] rollup + /api/setup (may be null). */
    private volatile BiasVoteResult lastResult;
    private volatile boolean lastAgree = true;

    public BiasVoteEngine(String symbol, double tickSize, VoteMode mode, int eqBandTicks) {
        this.symbol = symbol;
        this.tickSize = tickSize;
        this.mode = mode;
        this.eqBandTicks = Math.max(0, eqBandTicks);
    }

    public VoteMode mode() {
        return mode;
    }

    /** Evaluation count — the LEGACY-mode no-invocation assertion hook. */
    public long evaluationCount() {
        return evaluations.get();
    }

    // ══════════════════════════════════════════════════════════════════════
    // The four votes (§H1 order) — pure statics, unit-tested in isolation.
    // ══════════════════════════════════════════════════════════════════════

    /** V1 — structure: HtfTrendAnalyzer state. RANGING/absent → ABSTAIN. */
    static BiasVote voteV1(HtfTrendState state) {
        if (state == null || state == HtfTrendState.RANGING) {
            return new BiasVote("V1", VoteDirection.ABSTAIN, "ranging");
        }
        boolean bull = state == HtfTrendState.STRONG_BULLISH
                || state == HtfTrendState.WEAK_BULLISH;
        return new BiasVote("V1", bull ? VoteDirection.BULL : VoteDirection.BEAR,
                state.name().toLowerCase().replace('_', '-'));
    }

    /**
     * V2 — AMD cycle: the distribution-leg direction. Only the distribution
     * phases carry one; every other phase (incl. manipulation, which §H1
     * deliberately does not name for this vote) → ABSTAIN.
     */
    static BiasVote voteV2(DailyPhase phase) {
        if (phase == DailyPhase.DISTRIBUTION_UP) {
            return new BiasVote("V2", VoteDirection.BULL, "distribution-up");
        }
        if (phase == DailyPhase.DISTRIBUTION_DOWN) {
            return new BiasVote("V2", VoteDirection.BEAR, "distribution-down");
        }
        return new BiasVote("V2", VoteDirection.ABSTAIN, "no-dist-leg");
    }

    /**
     * V3 — price vs TRUE day open (midnight ET, {@code MIDNIGHT_OPEN} —
     * LevelEngine's pre-existing DAILY_OPEN is the previous day's 18:00 ET
     * session open, which is NOT what §H1's "true day open" means; the
     * midnight level was added for this vote and the choice is recorded in
     * Appendix A-03). Below open = discount = BULL; above = BEAR; inside
     * the ±eqBandTicks band, or no open yet → ABSTAIN.
     */
    static BiasVote voteV3(double price, Optional<Double> trueDayOpen,
                           double tickSize, int eqBandTicks) {
        if (trueDayOpen.isEmpty()) {
            return new BiasVote("V3", VoteDirection.ABSTAIN, "no-day-open");
        }
        double open = trueDayOpen.get();
        if (Math.abs(price - open) <= eqBandTicks * tickSize) {
            return new BiasVote("V3", VoteDirection.ABSTAIN, "at-open");
        }
        return price < open
                ? new BiasVote("V3", VoteDirection.BULL, "below-open")
                : new BiasVote("V3", VoteDirection.BEAR, "above-open");
    }

    /**
     * V4 — daily draw on liquidity, from the UNTAPPED previous-day
     * extremes (LevelEngine's raided state is authoritative — no
     * re-detection):
     * exactly one untapped → draw toward it (PDH→BULL, PDL→BEAR);
     * both untapped → the NEARER one in ticks, ABSTAIN when the distances
     * are within eqBandTicks of each other (no meaningful magnet);
     * both tapped → ABSTAIN (range day); levels absent → ABSTAIN.
     */
    static BiasVote voteV4(double price, Optional<KnownLevel> pdh,
                           Optional<KnownLevel> pdl,
                           double tickSize, int eqBandTicks) {
        if (pdh.isEmpty() || pdl.isEmpty()) {
            return new BiasVote("V4", VoteDirection.ABSTAIN, "no-pd-levels");
        }
        boolean pdhUntapped = !pdh.get().isRaided();
        boolean pdlUntapped = !pdl.get().isRaided();
        if (!pdhUntapped && !pdlUntapped) {
            return new BiasVote("V4", VoteDirection.ABSTAIN, "both-tapped");
        }
        if (pdhUntapped && !pdlUntapped) {
            return new BiasVote("V4", VoteDirection.BULL, "PDH-untapped");
        }
        if (!pdhUntapped) {
            return new BiasVote("V4", VoteDirection.BEAR, "PDL-untapped");
        }
        double distHigh = Math.abs(pdh.get().getPrice() - price);
        double distLow = Math.abs(price - pdl.get().getPrice());
        if (Math.abs(distHigh - distLow) <= eqBandTicks * tickSize) {
            return new BiasVote("V4", VoteDirection.ABSTAIN, "equidistant");
        }
        return distHigh < distLow
                ? new BiasVote("V4", VoteDirection.BULL, "PDH-nearer")
                : new BiasVote("V4", VoteDirection.BEAR, "PDL-nearer");
    }

    /** §H1 aggregation: ≥3 aligned ⇒ directional, otherwise NEUTRAL. */
    static BiasVoteResult aggregate(List<BiasVote> votes) {
        int bull = 0;
        int bear = 0;
        int abstains = 0;
        for (BiasVote v : votes) {
            switch (v.direction()) {
                case BULL -> bull++;
                case BEAR -> bear++;
                case ABSTAIN -> abstains++;
            }
        }
        MarketBias bias = (bull >= 3) ? MarketBias.BULLISH
                : (bear >= 3) ? MarketBias.BEARISH
                : MarketBias.NEUTRAL;
        return new BiasVoteResult(bias, List.copyOf(votes), bull, bear, abstains);
    }

    /**
     * The seam decision: which bias actually feeds
     * {@code core.recordHtfBias}. LEGACY/LOG → the legacy value; VOTE →
     * the vote's finalBias.
     */
    public static MarketBias effectiveBias(VoteMode mode, MarketBias legacy,
                                           BiasVoteResult vote) {
        if (mode == VoteMode.VOTE && vote != null) {
            return vote.finalBias();
        }
        return legacy;
    }

    // ══════════════════════════════════════════════════════════════════════
    // Evaluation + telemetry
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Run the four votes, aggregate, record agreement vs the legacy bias,
     * and print the [VOTE] line. Called by the runner once per completed
     * HTF bar when mode != LEGACY.
     */
    public BiasVoteResult evaluate(VoteInputs in, MarketBias legacyBias) {
        evaluations.incrementAndGet();
        BiasVote v1 = voteV1(in.trendState());
        // Optional H4 consult (V3 Agent 05, DEFAULT OFF): H4 fractal
        // structure contradicting the 15m/30m direction demotes V1 to
        // ABSTAIN. It never invents a direction (consult, not overrule).
        if (includeH4 && v1.direction() != VoteDirection.ABSTAIN
                && in.h4Series() != null && !in.h4Series().isEmpty()) {
            int h4Dir = FractalSwings.direction(in.h4Series(), 2);
            boolean conflict = (h4Dir > 0 && v1.direction() == VoteDirection.BEAR)
                    || (h4Dir < 0 && v1.direction() == VoteDirection.BULL);
            if (conflict) {
                v1 = new BiasVote("V1", VoteDirection.ABSTAIN,
                        "h4-conflict(" + v1.detail() + ")");
            }
        }
        BiasVote v4 = voteV4(in.price(), in.pdh(), in.pdl(), tickSize, eqBandTicks);
        // Weekly draw CONTEXT in the detail string only — no vote change
        // (V3 Agent 05; §H1 names the DAILY draw, so PWH/PWL stay telemetry).
        String weekly = weeklyDetail(in.pwh(), in.pwl());
        if (!weekly.isEmpty()) {
            v4 = new BiasVote(v4.source(), v4.direction(), v4.detail() + weekly);
        }
        List<BiasVote> votes = List.of(
                v1,
                voteV2(in.amdPhase()),
                voteV3(in.price(), in.trueDayOpen(), tickSize, eqBandTicks),
                v4);
        BiasVoteResult result = aggregate(votes);
        boolean agreed = result.finalBias() == legacyBias;
        lastResult = result;
        lastAgree = agreed;
        if (agreed) {
            agree.incrementAndGet();
        } else {
            disagree.incrementAndGet();
            if (result.finalBias() == MarketBias.NEUTRAL) {
                voteNeutralLegacyDirectional.incrementAndGet();
            } else if (legacyBias == MarketBias.NEUTRAL) {
                voteDirectionalLegacyNeutral.incrementAndGet();
            }
        }
        StringBuilder sb = new StringBuilder("[VOTE ").append(symbol).append("] ");
        for (BiasVote v : result.votes()) {
            sb.append(v.token()).append(' ');
        }
        sb.append("-> vote=").append(result.finalBias())
          .append(" legacy=").append(legacyBias)
          .append(" AGREE=").append(agreed);
        System.out.println(sb);
        return result;
    }

    /** Weekly tapped-state context for V4's detail string (never a vote). */
    private static String weeklyDetail(Optional<KnownLevel> pwh, Optional<KnownLevel> pwl) {
        if (pwh.isEmpty() && pwl.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(",wk:");
        pwh.ifPresent(l -> sb.append("PWH-").append(l.isRaided() ? "tapped" : "untapped"));
        if (pwh.isPresent() && pwl.isPresent()) sb.append('/');
        pwl.ifPresent(l -> sb.append("PWL-").append(l.isRaided() ? "tapped" : "untapped"));
        return sb.toString();
    }

    /** Compact rollup for the [GATES] line: {@code vote=NEUTRAL(1/1/2) agree=false}. */
    public String gatesToken() {
        BiasVoteResult r = lastResult;
        if (r == null) {
            return "vote=?";
        }
        return "vote=" + r.finalBias() + "(" + r.alignedBull() + "/"
                + r.alignedBear() + "/" + r.abstains() + ") agree=" + lastAgree;
    }

    /** JSON-friendly snapshot for /api/setup (session-scoped counters). */
    public Map<String, Object> toApiMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", mode.name());
        BiasVoteResult r = lastResult;
        if (r != null) {
            m.put("finalBias", r.finalBias().name());
            m.put("alignedBull", r.alignedBull());
            m.put("alignedBear", r.alignedBear());
            m.put("abstains", r.abstains());
            java.util.List<Map<String, Object>> votes = new java.util.ArrayList<>();
            for (BiasVote v : r.votes()) {
                Map<String, Object> vm = new LinkedHashMap<>();
                vm.put("source", v.source());
                vm.put("direction", v.direction().name());
                vm.put("detail", v.detail());
                votes.add(vm);
            }
            m.put("votes", votes);
            m.put("agree", lastAgree);
        }
        Map<String, Long> counters = new LinkedHashMap<>();
        counters.put("agree", agree.get());
        counters.put("disagree", disagree.get());
        counters.put("voteNeutral_legacyDirectional", voteNeutralLegacyDirectional.get());
        counters.put("voteDirectional_legacyNeutral", voteDirectionalLegacyNeutral.get());
        m.put("counters", counters);
        return m;
    }

    // Test hooks.
    long agreeCount()    { return agree.get(); }
    long disagreeCount() { return disagree.get(); }
    long voteDirectionalLegacyNeutralCount() { return voteDirectionalLegacyNeutral.get(); }
    long voteNeutralLegacyDirectionalCount() { return voteNeutralLegacyDirectional.get(); }
}
