package com.topstep.trading.ictlib;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Per-symbol, BOUNDED store of ictlib detections with their lifecycle states.
 *
 * <p>Bounded is not a nicety here: nine detection families across three
 * instruments on a process that runs for weeks is exactly the shape that leaks
 * (V4 critical rule 9 / risk G-R10). Every family declares a retention cap; on
 * overflow the registry evicts TERMINAL detections first (a filled gap is
 * history; an active one is a live trade input) and only then the oldest live
 * one.
 *
 * <p>Thread-safety: every method synchronizes on the registry. Detectors run on
 * the market-data thread; the API and chart threads read through
 * {@link #snapshot()} and the query methods, which hand back immutable
 * {@link DetectionSnapshot} copies — a reader can never observe a detection
 * mid-transition.
 */
public final class DetectionRegistry {

    /** How many detections of a family to keep, and whether the cap is per side. */
    public record Retention(int cap, boolean perSide) {
        public Retention {
            if (cap < 1) throw new IllegalArgumentException("cap >= 1");
        }
    }

    /** Storage slot: one bounded list per (family, timeframe, side). */
    private record Slot(DetectionType type, String timeframe, String side) {}

    private final String symbol;
    private final Map<DetectionType, Retention> retentions;
    private final Map<Slot, List<MutableDetection>> bySlot = new LinkedHashMap<>();
    private final Map<Slot, Long> sequences = new LinkedHashMap<>();

    public DetectionRegistry(String symbol, Map<DetectionType, Retention> retentions) {
        this.symbol = symbol;
        this.retentions = Map.copyOf(retentions);
    }

    public String symbol() {
        return symbol;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // WRITE PATH (detectors only)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Create and store a detection. Returns the mutable handle so the owning
     * detector can keep advancing its lifecycle.
     *
     * <p>The id is derived from a per-slot sequence number, NOT from a UUID or
     * the wall clock: replaying the same feed must produce identical ids
     * (determinism, V4 critical rule 7).
     */
    synchronized MutableDetection create(DetectionType type, String timeframe,
                                         DetectionDirection direction,
                                         double priceBottom, double priceTop,
                                         long bar, Instant at, DetectionState initial) {
        Slot slot = slotFor(type, timeframe, direction);
        long seq = sequences.merge(slot, 1L, Long::sum);
        String id = type.jsonKey() + ":" + timeframe + ":"
                + (retentionFor(type).perSide() ? slot.side().toLowerCase() + ":" : "")
                + seq;
        MutableDetection d = new MutableDetection(id, symbol, type, timeframe, direction,
                priceBottom, priceTop, bar, at, initial);
        List<MutableDetection> list = bySlot.computeIfAbsent(slot, s -> new ArrayList<>());
        list.add(d);
        enforceCap(list, retentionFor(type).cap());
        return d;
    }

    /** Live (mutable) detections of a family+timeframe, oldest first — detector use only. */
    synchronized List<MutableDetection> mutableView(DetectionType type, String timeframe) {
        List<MutableDetection> out = new ArrayList<>();
        for (Map.Entry<Slot, List<MutableDetection>> e : bySlot.entrySet()) {
            if (e.getKey().type() == type && e.getKey().timeframe().equals(timeframe)) {
                out.addAll(e.getValue());
            }
        }
        out.sort((a, b) -> Long.compare(a.createdAtBar(), b.createdAtBar()));
        return out;
    }

    private Retention retentionFor(DetectionType type) {
        return retentions.getOrDefault(type, new Retention(20, false));
    }

    private Slot slotFor(DetectionType type, String timeframe, DetectionDirection dir) {
        return new Slot(type, timeframe,
                retentionFor(type).perSide() ? dir.name() : "ALL");
    }

    /** Evict terminal detections first (oldest of them), then the oldest live one. */
    private void enforceCap(List<MutableDetection> list, int cap) {
        while (list.size() > cap) {
            int victim = -1;
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).terminal()) { victim = i; break; }
            }
            list.remove(victim >= 0 ? victim : 0);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // READ PATH (chart, confluence, API)
    // ═══════════════════════════════════════════════════════════════════════

    /** Every detection currently retained, immutable, ordered oldest → newest. */
    public synchronized List<Detection> snapshot() {
        List<Detection> out = new ArrayList<>();
        for (List<MutableDetection> list : bySlot.values()) {
            for (MutableDetection d : list) out.add(DetectionSnapshot.of(d));
        }
        out.sort((a, b) -> {
            int c = a.createdAt().compareTo(b.createdAt());
            return c != 0 ? c : a.id().compareTo(b.id());
        });
        return Collections.unmodifiableList(out);
    }

    /** All retained detections of a family (any timeframe), including terminal ones. */
    public synchronized List<Detection> byType(DetectionType type) {
        List<Detection> out = new ArrayList<>();
        for (Detection d : snapshot()) {
            if (d.type() == type) out.add(d);
        }
        return out;
    }

    /** Non-terminal detections of a family — what the chart draws "live". */
    public synchronized List<Detection> activeByType(DetectionType type) {
        List<Detection> out = new ArrayList<>();
        for (Detection d : byType(type)) {
            if (!d.terminal()) out.add(d);
        }
        return out;
    }

    /** Non-terminal detections of a family in one direction. */
    public synchronized List<Detection> activeByType(DetectionType type,
                                                     DetectionDirection direction) {
        List<Detection> out = new ArrayList<>();
        for (Detection d : activeByType(type)) {
            if (d.direction() == direction) out.add(d);
        }
        return out;
    }

    /** Every non-terminal detection whose zone contains {@code price}. */
    public synchronized List<Detection> inZone(double price) {
        List<Detection> out = new ArrayList<>();
        for (Detection d : snapshot()) {
            if (!d.terminal() && d.contains(price)) out.add(d);
        }
        return out;
    }

    /** Nearest non-terminal zone whose bottom sits above {@code price}. */
    public synchronized Optional<Detection> nearestAbove(double price) {
        Detection best = null;
        for (Detection d : snapshot()) {
            if (d.terminal() || d.priceBottom() <= price) continue;
            if (best == null || d.priceBottom() < best.priceBottom()) best = d;
        }
        return Optional.ofNullable(best);
    }

    /** Nearest non-terminal zone whose top sits below {@code price}. */
    public synchronized Optional<Detection> nearestBelow(double price) {
        Detection best = null;
        for (Detection d : snapshot()) {
            if (d.terminal() || d.priceTop() >= price) continue;
            if (best == null || d.priceTop() > best.priceTop()) best = d;
        }
        return Optional.ofNullable(best);
    }

    /** The {@code n} most recently created detections of a family, newest first. */
    public synchronized List<Detection> recent(DetectionType type, int n) {
        List<Detection> all = byType(type);
        Collections.reverse(all);
        return all.subList(0, Math.min(Math.max(n, 0), all.size()));
    }

    /** Total retained count for a family — the cap-enforcement assertion hook. */
    public synchronized int count(DetectionType type) {
        return byType(type).size();
    }

    /** Total retained count across all families (memory-bound evidence). */
    public synchronized int size() {
        int n = 0;
        for (List<MutableDetection> list : bySlot.values()) n += list.size();
        return n;
    }
}
