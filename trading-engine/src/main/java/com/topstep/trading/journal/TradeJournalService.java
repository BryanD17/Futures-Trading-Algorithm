package com.topstep.trading.journal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.topstep.trading.domain.Trade;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Persists completed trades to an append-only JSON log file and
 * prints a session summary to stdout when the engine shuts down.
 *
 * Journal file location: journal/trade_journal.json (list of journal entry objects)
 */
public class TradeJournalService {

    private static final DateTimeFormatter DISPLAY_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(ZoneId.of("America/Chicago"));

    private final Path journalPath;
    private final ObjectMapper mapper;

    public TradeJournalService() {
        this(Path.of("journal", "trade_journal.json"));
    }

    public TradeJournalService(Path journalPath) {
        this.journalPath = journalPath;
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * Called at engine shutdown. Prints a formatted session summary
     * and appends all trades from this session to the journal file.
     */
    public void onSessionEnd(List<Trade> sessionTrades) {
        if (sessionTrades == null || sessionTrades.isEmpty()) {
            System.out.println("\n======================================");
            System.out.println("  SESSION ENDED - No trades taken.");
            System.out.println("======================================\n");
            return;
        }

        printSessionSummary(sessionTrades);
        persistTrades(sessionTrades);
    }

    /**
     * Print a structured session summary to stdout.
     */
    private void printSessionSummary(List<Trade> trades) {
        double totalPnl = trades.stream().mapToDouble(Trade::getRealizedPnL).sum();
        long wins = trades.stream().filter(Trade::isWinner).count();
        long losses = trades.stream().filter(Trade::isLoser).count();
        double winRate = trades.isEmpty() ? 0 : (wins * 100.0 / trades.size());

        System.out.println("\n+==============================================================+");
        System.out.println("|              SESSION TRADE JOURNAL SUMMARY                  |");
        System.out.println("+==============================================================+");
        System.out.printf("|  Total Trades : %-3d   Wins: %-3d   Losses: %-3d               |%n",
                trades.size(), wins, losses);
        System.out.printf("|  Win Rate     : %.1f%%                                        |%n", winRate);
        System.out.printf("|  Session P&L  : %s$%.2f                                      |%n",
                totalPnl >= 0 ? "+" : "", totalPnl);
        System.out.println("+==============================================================+");

        for (int i = 0; i < trades.size(); i++) {
            Trade t = trades.get(i);
            String pnlStr = String.format("%s$%.2f", t.getRealizedPnL() >= 0 ? "+" : "", t.getRealizedPnL());
            String result = t.isWinner() ? "WIN " : "LOSS";

            System.out.printf("|  #%-2d [%s] %-4s %-2s x%-3d  Entry: %-10.5f  Exit: %-10.5f |%n",
                    i + 1, result,
                    t.getSymbol(),
                    t.getSide(),
                    t.getQuantity(),
                    t.getEntryPrice(),
                    t.getExitPrice());

            System.out.printf("|       P&L: %-10s  R: %+.2f  Tier: %-20s|%n",
                    pnlStr,
                    t.getRMultiple(),
                    t.getTier() != null ? t.getTier().name() : "N/A");

            System.out.printf("|       Entry Time : %-40s|%n",
                    DISPLAY_FMT.format(t.getEntryTime()));

            System.out.printf("|       Exit Reason: %-40s|%n",
                    truncate(t.getNotes(), 40));

            if (t.getConfluenceFactors() != null && !t.getConfluenceFactors().isEmpty()) {
                System.out.printf("|       Confluence : %-40s|%n",
                        truncate(String.join(", ", t.getConfluenceFactors()), 40));
            }

            if (i < trades.size() - 1) {
                System.out.println("|  ---------------------------------------------------------- |");
            }
        }

        System.out.println("+==============================================================+\n");
    }

    /**
     * Append trades to the persistent JSON journal file.
     * Reads existing entries, merges, and rewrites.
     */
    @SuppressWarnings("unchecked")
    private void persistTrades(List<Trade> trades) {
        try {
            Files.createDirectories(journalPath.getParent());

            List<Map<String, Object>> existing = new ArrayList<>();
            if (Files.exists(journalPath)) {
                String content = Files.readString(journalPath).trim();
                if (!content.isBlank() && content.startsWith("[")) {
                    existing = mapper.readValue(content,
                            mapper.getTypeFactory().constructCollectionType(List.class, Map.class));
                }
            }

            for (Trade t : trades) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("tradeId", t.getTradeId());
                entry.put("sessionDate", LocalDate.now(ZoneId.of("America/Chicago")).toString());
                entry.put("symbol", t.getSymbol());
                entry.put("side", t.getSide().name());
                entry.put("quantity", t.getQuantity());
                entry.put("entryPrice", t.getEntryPrice());
                entry.put("exitPrice", t.getExitPrice());
                entry.put("entryTime", t.getEntryTime().toString());
                entry.put("exitTime", t.getExitTime().toString());
                entry.put("entryTimeCT", DISPLAY_FMT.format(t.getEntryTime()));
                entry.put("exitTimeCT", DISPLAY_FMT.format(t.getExitTime()));
                entry.put("realizedPnL", t.getRealizedPnL());
                entry.put("rMultiple", t.getRMultiple());
                entry.put("tier", t.getTier() != null ? t.getTier().name() : null);
                entry.put("confluenceFactors", t.getConfluenceFactors());
                entry.put("exitReason", t.getNotes());
                entry.put("result", t.isWinner() ? "WIN" : "LOSS");
                existing.add(entry);
            }

            Files.writeString(journalPath, mapper.writeValueAsString(existing));
            System.out.println("[Journal] " + trades.size() + " trade(s) saved -> " + journalPath.toAbsolutePath());

        } catch (IOException e) {
            System.err.println("[Journal] ERROR saving journal: " + e.getMessage());
        }
    }

    /**
     * Load all journal entries (used by the API layer).
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> loadAll() {
        try {
            if (!Files.exists(journalPath)) return List.of();
            String content = Files.readString(journalPath).trim();
            if (content.isBlank() || !content.startsWith("[")) return List.of();
            return mapper.readValue(content,
                    mapper.getTypeFactory().constructCollectionType(List.class, Map.class));
        } catch (IOException e) {
            System.err.println("[Journal] ERROR reading journal: " + e.getMessage());
            return List.of();
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }
}
