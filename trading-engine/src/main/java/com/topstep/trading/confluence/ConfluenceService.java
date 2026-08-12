package com.topstep.trading.confluence;

import com.topstep.trading.chart.ChartEngine;
import com.topstep.trading.chart.OteState;
import com.topstep.trading.chart.OteZoneSnapshot;
import com.topstep.trading.ictlib.Detection;
import com.topstep.trading.ictlib.DetectionDirection;
import com.topstep.trading.ictlib.DetectionRegistry;
import com.topstep.trading.ictlib.DetectionState;
import com.topstep.trading.ictlib.DetectionType;
import com.topstep.trading.ictlib.IctLibEngine;
import com.topstep.trading.strategy.MarketBias;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "Stack up my confluences" as a concrete object (V4 Agent 07).
 *
 * <p>ONE snapshot per symbol per direction, aggregating every source that
 * already exists. It is emphatically an AGGREGATOR: it computes no bias, no
 * sweep, no zone and no lifecycle. Engine facts arrive pre-answered from the
 * strategy via {@link #publish}; ictlib facts are read from the detection
 * registry's stored state; the chart fact is read from ChartEngine. Anything
 * this class derived itself would become a second truth nobody asked for
 * (B13 / anti-pattern C3).
 *
 * <p>It gates NOTHING. Agent 08's profiles read snapshots; nothing here can
 * emit, block or size a trade.
 */
public final class ConfluenceService {

    /** How near a zone must be to count as "at" price, in ticks. */
    private static final String P_NEAR_TICKS = "confluence.nearTicks";
    /** Raid score at or above which RAID_SCORE reads TRUE. */
    private static final String P_RAID_FLOOR = "confluence.raidScoreFloor";
    /** How recently a pool sweep / structure event still counts, in minutes. */
    private static final String P_RECENT_MINUTES = "confluence.recentMinutes";

    private final Map<String, EngineFacts> facts = new ConcurrentHashMap<>();
    private volatile ChartEngine chartEngine;
    private volatile IctLibEngine ictLib;
    private final Map<String, Double> tickSizes = new ConcurrentHashMap<>();

    public void setChartEngine(ChartEngine engine) {
        this.chartEngine = engine;
    }

    public void setIctLibEngine(IctLibEngine engine) {
        this.ictLib = engine;
    }

    public void registerInstrument(String symbol, double tickSize) {
        if (symbol != null && tickSize > 0) tickSizes.put(symbol, tickSize);
    }

    /** The strategy hands over the engine facts it already computed this bar. */
    public void publish(String symbol, EngineFacts f) {
        if (symbol == null || f == null) return;
        facts.put(symbol, f);
    }

    /** The most recently published engine facts, or an all-UNKNOWN read. */
    public EngineFacts factsFor(String symbol) {
        return facts.getOrDefault(symbol, EngineFacts.cold(null));
    }

    public java.util.Set<String> symbols() {
        return facts.keySet();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // THE SNAPSHOT
    // ═══════════════════════════════════════════════════════════════════════

    public ConfluenceSnapshot snapshot(String symbol, boolean bullish) {
        EngineFacts f = factsFor(symbol);
        return snapshot(symbol, bullish, f, f.at());
    }

    /** Test seam: aggregate from explicitly supplied facts. */
    public ConfluenceSnapshot snapshot(String symbol, boolean bullish,
                                       EngineFacts f, Instant at) {
        Map<ConfluenceField, Tri> values = new EnumMap<>(ConfluenceField.class);
        Map<ConfluenceField, String> details = new EnumMap<>(ConfluenceField.class);

        addEngineFacts(values, details, bullish, f);
        addIctLibFacts(values, details, symbol, bullish, f);
        addChartFacts(values, details, symbol, bullish);

        double score = 0.0;
        double maxScore = 0.0;
        for (ConfluenceField field : ConfluenceField.values()) {
            Tri v = values.getOrDefault(field, Tri.UNKNOWN);
            if (!v.isKnown()) continue;          // UNKNOWN counts for NEITHER side
            maxScore += field.weight();
            if (v.isTrue()) score += field.weight();
        }
        return new ConfluenceSnapshot(symbol, bullish, at, values, details, score, maxScore);
    }

    // ── ENGINE ─────────────────────────────────────────────────────────────

    private void addEngineFacts(Map<ConfluenceField, Tri> v, Map<ConfluenceField, String> d,
                                boolean bullish, EngineFacts f) {
        put(v, d, ConfluenceField.IN_TRADING_KILLZONE, Tri.of(f.inTradingKillzone()),
                f.inTradingKillzone() == null ? "no killzone reading yet" : "KillzoneClock");

        put(v, d, ConfluenceField.HTF_BIAS_ALIGNED, biasAligned(f.legacyBias(), bullish),
                f.legacyBias() == null ? "bias not published" : String.valueOf(f.legacyBias()));

        put(v, d, ConfluenceField.VOTE_BIAS_ALIGNED, biasAligned(f.voteBias(), bullish),
                f.voteDetail() != null ? f.voteDetail()
                        : (f.voteBias() == null ? "vote not running" : String.valueOf(f.voteBias())));

        put(v, d, ConfluenceField.PD_VERDICT, Tri.of(f.pdAligned()),
                f.pdDetail() != null ? f.pdDetail() : "no premium/discount verdict");

        put(v, d, ConfluenceField.RECENT_SWEEP, Tri.of(f.recentSweep()),
                f.recentSweep() == null ? "raid pipeline cold" : "raid pipeline");

        Integer raid = f.raidScore();
        int floor = intProp(P_RAID_FLOOR, 5);
        put(v, d, ConfluenceField.RAID_SCORE,
                raid == null ? Tri.UNKNOWN : Tri.of(raid >= floor),
                raid == null ? "no raid scored" : raid + "/10 (floor " + floor + ")");

        String ote = f.machineOteState();
        put(v, d, ConfluenceField.MACHINE_OTE_STATE,
                ote == null ? Tri.UNKNOWN : Tri.of(isArmedOrReacted(ote)),
                ote == null ? "state machine silent" : ote);
    }

    private static Tri biasAligned(MarketBias bias, boolean bullish) {
        if (bias == null) return Tri.UNKNOWN;
        if (bias == MarketBias.NEUTRAL) return Tri.FALSE;
        return Tri.of((bias == MarketBias.BULLISH) == bullish);
    }

    private static boolean isArmedOrReacted(String state) {
        return "ARMED".equalsIgnoreCase(state) || "REACTED".equalsIgnoreCase(state)
                || "OTE_ARMED".equalsIgnoreCase(state);
    }

    // ── ICTLIB ─────────────────────────────────────────────────────────────

    private void addIctLibFacts(Map<ConfluenceField, Tri> v, Map<ConfluenceField, String> d,
                                String symbol, boolean bullish, EngineFacts f) {
        IctLibEngine lib = ictLib;
        Optional<DetectionRegistry> maybe = (lib == null)
                ? Optional.empty() : lib.registryIfPresent(symbol);

        // A registry that has never seen a candle has not said "no" to
        // anything. Every ictlib field is UNKNOWN, not FALSE.
        if (maybe.isEmpty() || maybe.get().size() == 0) {
            for (ConfluenceField field : ictLibFields()) {
                put(v, d, field, Tri.UNKNOWN, "ictlib cold for " + symbol);
            }
            return;
        }

        DetectionRegistry r = maybe.get();
        DetectionDirection dir = DetectionDirection.of(bullish);
        Double price = f.price();
        double tick = tickSizes.getOrDefault(symbol, 0.25);
        double near = intProp(P_NEAR_TICKS, 40) * tick;

        List<Detection> fvgs = r.activeByType(DetectionType.FVG, dir);
        put(v, d, ConfluenceField.ACTIVE_FVG_IN_DIRECTION, Tri.of(!fvgs.isEmpty()),
                fvgs.size() + " active " + (bullish ? "bullish" : "bearish") + " gap(s)");

        if (price == null) {
            put(v, d, ConfluenceField.PRICE_INSIDE_FVG, Tri.UNKNOWN, "no price published");
        } else {
            Detection inside = null;
            for (Detection g : fvgs) {
                if (g.contains(price)) { inside = g; break; }
            }
            put(v, d, ConfluenceField.PRICE_INSIDE_FVG, Tri.of(inside != null),
                    inside == null ? "price outside every active gap"
                            : inside.id() + " " + fmt(inside.priceBottom()) + "-" + fmt(inside.priceTop()));
        }

        Detection ob = nearest(r.activeByType(DetectionType.ORDER_BLOCK, dir), price);
        if (price == null) {
            put(v, d, ConfluenceField.NEAREST_OB_ZONE, Tri.UNKNOWN, "no price published");
        } else if (ob == null) {
            put(v, d, ConfluenceField.NEAREST_OB_ZONE, Tri.FALSE, "no live order block this side");
        } else {
            double dist = distance(ob, price);
            put(v, d, ConfluenceField.NEAREST_OB_ZONE, Tri.of(dist <= near),
                    String.format("%s %s %.0f ticks away", ob.state(),
                            bullish ? "demand" : "supply", dist / tick));
        }

        List<Detection> bprs = r.activeByType(DetectionType.BPR, dir);
        put(v, d, ConfluenceField.BPR_PRESENT, Tri.of(!bprs.isEmpty()),
                bprs.isEmpty() ? "none active" : bprs.size() + " active");

        Detection vi = nearest(r.activeByType(DetectionType.VOLUME_IMBALANCE, dir), price);
        if (price == null) {
            put(v, d, ConfluenceField.VI_NEARBY, Tri.UNKNOWN, "no price published");
        } else if (vi == null) {
            put(v, d, ConfluenceField.VI_NEARBY, Tri.FALSE, "none active this side");
        } else {
            double dist = distance(vi, price);
            put(v, d, ConfluenceField.VI_NEARBY, Tri.of(dist <= near),
                    String.format("%.0f ticks away", dist / tick));
        }

        Detection gap = nearestGapMidline(r, price);
        if (price == null) {
            put(v, d, ConfluenceField.OPENING_GAP_MAGNET, Tri.UNKNOWN, "no price published");
        } else if (gap == null) {
            put(v, d, ConfluenceField.OPENING_GAP_MAGNET, Tri.FALSE, "no opening gap retained");
        } else {
            double mid = midline(gap);
            double dist = Math.abs(mid - price);
            put(v, d, ConfluenceField.OPENING_GAP_MAGNET, Tri.of(dist <= near),
                    String.format("%s midline %s, %.0f ticks away",
                            gap.type().jsonKey(), fmt(mid), dist / tick));
        }

        Instant now = f.at();
        Duration window = Duration.ofMinutes(intProp(P_RECENT_MINUTES, 120));
        Detection pool = mostRecentWithState(r, DetectionType.LIQUIDITY_POOL,
                DetectionState.PARTIAL, DetectionState.SWEPT);
        if (now == null) {
            put(v, d, ConfluenceField.POOL_SWEPT_RECENTLY, Tri.UNKNOWN, "no clock reading");
        } else if (pool == null) {
            put(v, d, ConfluenceField.POOL_SWEPT_RECENTLY, Tri.FALSE, "no pool raided");
        } else {
            boolean recent = !pool.stateChangedAt().isBefore(now.minus(window));
            put(v, d, ConfluenceField.POOL_SWEPT_RECENTLY, Tri.of(recent),
                    pool.state() + " at " + pool.stateChangedAt());
        }

        Detection mss = newest(r.byType(DetectionType.MSS));
        Detection bos = newest(r.byType(DetectionType.BOS));
        Detection structure = newer(mss, bos);
        if (structure == null) {
            put(v, d, ConfluenceField.STRUCTURE_STATE, Tri.FALSE, "no structure event yet");
        } else if (now == null) {
            put(v, d, ConfluenceField.STRUCTURE_STATE, Tri.UNKNOWN, "no clock reading");
        } else {
            boolean aligned = structure.direction() == dir
                    && !structure.createdAt().isBefore(now.minus(window));
            put(v, d, ConfluenceField.STRUCTURE_STATE, Tri.of(aligned),
                    structure.type().jsonKey().toUpperCase() + " " + structure.direction()
                            + " at " + structure.createdAt());
        }
    }

    private static ConfluenceField[] ictLibFields() {
        return new ConfluenceField[]{
                ConfluenceField.ACTIVE_FVG_IN_DIRECTION,
                ConfluenceField.PRICE_INSIDE_FVG,
                ConfluenceField.NEAREST_OB_ZONE,
                ConfluenceField.BPR_PRESENT,
                ConfluenceField.VI_NEARBY,
                ConfluenceField.OPENING_GAP_MAGNET,
                ConfluenceField.POOL_SWEPT_RECENTLY,
                ConfluenceField.STRUCTURE_STATE,
        };
    }

    // ── CHART ──────────────────────────────────────────────────────────────

    private void addChartFacts(Map<ConfluenceField, Tri> v, Map<ConfluenceField, String> d,
                               String symbol, boolean bullish) {
        ChartEngine ce = chartEngine;
        if (ce == null) {
            put(v, d, ConfluenceField.CHART_OTE_STATE, Tri.UNKNOWN, "no chart engine wired");
            return;
        }
        Optional<OteZoneSnapshot> zone = ce.getActiveOteZone(symbol);
        if (zone.isEmpty()) {
            put(v, d, ConfluenceField.CHART_OTE_STATE, Tri.UNKNOWN,
                    "no live 30m zone (" + ce.anchorModeFor(symbol) + ")");
            return;
        }
        OteZoneSnapshot z = zone.get();
        boolean armed = z.bullish() == bullish
                && (z.state() == OteState.ARMED || z.state() == OteState.REACTED);
        put(v, d, ConfluenceField.CHART_OTE_STATE, Tri.of(armed),
                z.state() + "/" + (z.bullish() ? "BULL" : "BEAR") + " " + z.anchorMode());
    }

    // ── TELEMETRY ──────────────────────────────────────────────────────────

    /**
     * The 15m line, e.g.
     * {@code [CONFLUENCE MNQ] long 7/11w=0.64 short 3/11w=0.27 top: kz,bias,fvg,ob}
     */
    public String logLine(String symbol) {
        ConfluenceSnapshot longs = snapshot(symbol, true);
        ConfluenceSnapshot shorts = snapshot(symbol, false);
        ConfluenceSnapshot stronger = longs.ratio() >= shorts.ratio() ? longs : shorts;
        return "[CONFLUENCE " + symbol + "] " + longs.token() + " " + shorts.token()
                + " top: " + String.join(",", stronger.topContributors(4));
    }

    // ── HELPERS ────────────────────────────────────────────────────────────

    private static void put(Map<ConfluenceField, Tri> v, Map<ConfluenceField, String> d,
                            ConfluenceField field, Tri value, String detail) {
        v.put(field, value);
        if (detail != null) d.put(field, detail);
    }

    private static Detection nearest(List<Detection> items, Double price) {
        if (price == null) return items.isEmpty() ? null : items.get(items.size() - 1);
        Detection best = null;
        double bestDist = Double.MAX_VALUE;
        for (Detection d : items) {
            double dist = distance(d, price);
            if (dist < bestDist) {
                bestDist = dist;
                best = d;
            }
        }
        return best;
    }

    private static double distance(Detection d, double price) {
        if (d.contains(price)) return 0.0;
        return price < d.priceBottom() ? d.priceBottom() - price : price - d.priceTop();
    }

    private static Detection nearestGapMidline(DetectionRegistry r, Double price) {
        List<Detection> gaps = new java.util.ArrayList<>();
        gaps.addAll(r.activeByType(DetectionType.OPENING_GAP_DAILY));
        gaps.addAll(r.activeByType(DetectionType.OPENING_GAP_WEEKLY));
        if (gaps.isEmpty()) return null;
        if (price == null) return gaps.get(gaps.size() - 1);
        Detection best = null;
        double bestDist = Double.MAX_VALUE;
        for (Detection g : gaps) {
            double dist = Math.abs(midline(g) - price);
            if (dist < bestDist) {
                bestDist = dist;
                best = g;
            }
        }
        return best;
    }

    private static double midline(Detection gap) {
        Object m = gap.meta().get("midline");
        return (m instanceof Number n) ? n.doubleValue() : gap.midpoint();
    }

    private static Detection mostRecentWithState(DetectionRegistry r, DetectionType type,
                                                 DetectionState... states) {
        Detection best = null;
        for (Detection d : r.byType(type)) {
            for (DetectionState s : states) {
                if (d.state() == s) {
                    if (best == null || d.stateChangedAt().isAfter(best.stateChangedAt())) {
                        best = d;
                    }
                    break;
                }
            }
        }
        return best;
    }

    private static Detection newest(List<Detection> items) {
        return items.isEmpty() ? null : items.get(items.size() - 1);
    }

    private static Detection newer(Detection a, Detection b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.createdAt().isAfter(b.createdAt()) ? a : b;
    }

    private static int intProp(String key, int def) {
        try {
            String v = System.getProperty(key);
            return v == null ? def : Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String fmt(double d) {
        return String.format("%.2f", d);
    }
}
