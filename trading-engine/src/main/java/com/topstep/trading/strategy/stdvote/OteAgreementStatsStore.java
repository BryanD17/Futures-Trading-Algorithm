package com.topstep.trading.strategy.stdvote;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.topstep.trading.strategy.TradingSessionCalendar;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Durable storage for the 30m-OTE agreement counters (V3 Agent 06).
 *
 * <p>WHY: {@link OteAgreementStats} is session-scoped by design; the
 * promote-or-delete decision needs LIFETIME evidence that survives
 * restarts. Storage follows the repo's existing engine-side persistence
 * convention (append-only files, like the trade journal — no DB/migration
 * tool exists in the trading-engine module; the api-backend JPA store was
 * considered and rejected because these counters are produced on the
 * engine's candle path, which must not depend on the API module).
 *
 * <p>FORMAT: JSONL at {@code data/ote_agreement_stats.jsonl}
 * ({@code -Dote.stats.file} overrides). One line per checkpoint:
 * {@code {"date","symbol","mode","machineEmitted_chartAgreed",
 * "machineEmitted_chartDisagreed","chartReacted_machineSilent",
 * "sessionsCounted"}} where the counters are the SESSION's running totals
 * at checkpoint time. Checkpoints are appended every 30 minutes (crash
 * safety) and at session end; the LOADER keeps the LAST line per
 * (symbol, date), so re-checkpointing the same session can never double
 * count — that is the overwrite-safety contract, tested.
 *
 * <p>Dates key on the CME SESSION date via {@link TradingSessionCalendar}
 * (candle time, not wall clock — B6).
 */
public final class OteAgreementStatsStore {

    /** System property overriding the JSONL path (tests use temp files). */
    public static final String FILE_PROPERTY = "ote.stats.file";
    /** Boot reminder threshold — the promote/delete evaluation floor. */
    public static final int EVALUATION_SESSIONS = 15;

    /** Lifetime totals for one symbol, EXCLUDING the given session date. */
    public record Lifetime(long agreed, long disagreed, long chartOnly, int sessions) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Object LOCK = new Object();

    /** symbol -> (sessionDate -> [agreed, disagreed, chartOnly]), last line wins. */
    private static Map<String, TreeMap<LocalDate, long[]>> loaded;
    private static Path activeFile;

    private OteAgreementStatsStore() {}

    private static Path file() {
        return Path.of(System.getProperty(FILE_PROPERTY, "data/ote_agreement_stats.jsonl"));
    }

    /** Test hook: point at a fresh file and drop the cache. */
    static void resetForTest() {
        synchronized (LOCK) {
            loaded = null;
            activeFile = null;
        }
    }

    /**
     * Append one checkpoint line for the symbol's CURRENT session counters.
     * Crash-safe by construction (append-only); duplicate same-session
     * lines are collapsed last-wins at load time. Failures are logged and
     * swallowed — persistence must never take down the candle path.
     */
    public static void checkpoint(String symbol, Instant candleTime) {
        if (symbol == null || candleTime == null) return;
        OteAgreementStats s = OteAgreementStats.forSymbol(symbol);
        LocalDate session = TradingSessionCalendar.sessionDate(candleTime);
        synchronized (LOCK) {
            ensureLoaded();
            try {
                Path f = file();
                if (f.getParent() != null) Files.createDirectories(f.getParent());
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("date", session.toString());
                line.put("symbol", symbol);
                line.put("mode", System.getProperty(
                        Ote30mConfluenceGate.MODE_PROPERTY, "LOG"));
                line.put("machineEmitted_chartAgreed", s.agreed());
                line.put("machineEmitted_chartDisagreed", s.disagreed());
                line.put("chartReacted_machineSilent", s.chartOnly());
                line.put("sessionsCounted", sessionsFor(symbol, session));
                Files.writeString(f, MAPPER.writeValueAsString(line) + System.lineSeparator(),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                loaded.computeIfAbsent(symbol, k -> new TreeMap<>())
                        .put(session, new long[] {s.agreed(), s.disagreed(), s.chartOnly()});
            } catch (IOException e) {
                System.out.println("[OTE-VERDICT] WARN: checkpoint write failed: "
                        + e.getMessage());
            }
        }
    }

    /**
     * Lifetime totals for a symbol EXCLUDING {@code excludeSession} (the
     * caller adds the LIVE session counters for that date on top — this
     * split is what makes checkpointed-today + still-counting-today safe).
     */
    public static Lifetime lifetimeExcluding(String symbol, LocalDate excludeSession) {
        synchronized (LOCK) {
            ensureLoaded();
            TreeMap<LocalDate, long[]> byDate = loaded.get(symbol);
            if (byDate == null) return new Lifetime(0, 0, 0, 0);
            long a = 0;
            long d = 0;
            long c = 0;
            int sessions = 0;
            for (Map.Entry<LocalDate, long[]> e : byDate.entrySet()) {
                if (e.getKey().equals(excludeSession)) continue;
                a += e.getValue()[0];
                d += e.getValue()[1];
                c += e.getValue()[2];
                sessions++;
            }
            return new Lifetime(a, d, c, sessions);
        }
    }

    /** Sessions on record for a symbol, counting {@code current} once. */
    static int sessionsFor(String symbol, LocalDate current) {
        TreeMap<LocalDate, long[]> byDate = loaded.get(symbol);
        if (byDate == null) return 1;
        int n = byDate.size();
        return byDate.containsKey(current) ? n : n + 1;
    }

    /** Load once per JVM (or per resetForTest). Emits the boot reminder. */
    private static void ensureLoaded() {
        Path f = file();
        if (loaded != null && f.equals(activeFile)) return;
        loaded = new HashMap<>();
        activeFile = f;
        if (!Files.exists(f)) return;
        try {
            List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
            for (String raw : lines) {
                if (raw.isBlank()) continue;
                try {
                    JsonNode n = MAPPER.readTree(raw);
                    String symbol = n.path("symbol").asText(null);
                    String date = n.path("date").asText(null);
                    if (symbol == null || date == null) continue;
                    loaded.computeIfAbsent(symbol, k -> new TreeMap<>())
                            .put(LocalDate.parse(date), new long[] {
                                    n.path("machineEmitted_chartAgreed").asLong(),
                                    n.path("machineEmitted_chartDisagreed").asLong(),
                                    n.path("chartReacted_machineSilent").asLong()});
                } catch (Exception perLine) {
                    // A corrupt line never poisons the rest of the file.
                }
            }
            for (Map.Entry<String, TreeMap<LocalDate, long[]>> e : loaded.entrySet()) {
                int sessions = e.getValue().size();
                System.out.println("[OTE-VERDICT] " + e.getKey() + ": restored "
                        + sessions + " session(s) of lifetime agreement data");
                if (sessions >= EVALUATION_SESSIONS) {
                    System.out.println("[OTE-VERDICT] " + sessions
                            + " sessions collected — evaluation due ("
                            + e.getKey() + "; see QUICK_START promote/delete criteria)");
                }
            }
        } catch (IOException e) {
            System.out.println("[OTE-VERDICT] WARN: could not load " + f + ": "
                    + e.getMessage());
        }
    }
}
