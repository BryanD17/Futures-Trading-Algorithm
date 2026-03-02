package com.topstep.trading.journal;

import com.topstep.trading.domain.OrderSide;
import com.topstep.trading.domain.Trade;
import com.topstep.trading.strategy.TradeTier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for TradeJournalService - verifies JSON persistence and session summary.
 */
class TradeJournalServiceTest {

    @TempDir
    Path tempDir;

    private TradeJournalService journalService;
    private Path journalPath;

    @BeforeEach
    void setUp() {
        journalPath = tempDir.resolve("test_journal.json");
        journalService = new TradeJournalService(journalPath);
    }

    @Test
    @DisplayName("onSessionEnd writes all trade fields to JSON file")
    void onSessionEnd_writesJsonWithAllFields() throws Exception {
        // Given
        Trade trade = Trade.builder()
                .symbol("NQ")
                .side(OrderSide.BUY)
                .quantity(2)
                .entryPrice(18500.25)
                .exitPrice(18550.50)
                .entryTime(Instant.parse("2026-02-20T14:30:00Z"))
                .exitTime(Instant.parse("2026-02-20T15:00:00Z"))
                .realizedPnL(500.00)
                .riskAmount(250.00)
                .notes("FVG entry with SMT confirmation")
                .tier(TradeTier.TIER_3)
                .confluenceFactors(List.of("IFVG", "SMT Divergence", "PDL Sweep"))
                .build();

        // When
        journalService.onSessionEnd(List.of(trade));

        // Then
        assertThat(Files.exists(journalPath)).isTrue();

        List<Map<String, Object>> entries = journalService.loadAll();
        assertThat(entries).hasSize(1);

        Map<String, Object> entry = entries.get(0);
        assertThat(entry.get("tradeId")).isNotNull();
        assertThat(entry.get("symbol")).isEqualTo("NQ");
        assertThat(entry.get("side")).isEqualTo("BUY");
        assertThat(entry.get("quantity")).isEqualTo(2);
        assertThat(entry.get("entryPrice")).isEqualTo(18500.25);
        assertThat(entry.get("exitPrice")).isEqualTo(18550.50);
        assertThat(entry.get("entryTime")).isNotNull();
        assertThat(entry.get("exitTime")).isNotNull();
        assertThat(entry.get("entryTimeCT")).isNotNull();
        assertThat(entry.get("exitTimeCT")).isNotNull();
        assertThat(entry.get("realizedPnL")).isEqualTo(500.0);
        assertThat(entry.get("rMultiple")).isEqualTo(2.0);
        assertThat(entry.get("tier")).isEqualTo("TIER_3");
        @SuppressWarnings("unchecked")
        List<String> factors = (List<String>) entry.get("confluenceFactors");
        assertThat(factors).containsExactly("IFVG", "SMT Divergence", "PDL Sweep");
        assertThat(entry.get("exitReason")).isEqualTo("FVG entry with SMT confirmation");
        assertThat(entry.get("result")).isEqualTo("WIN");
        assertThat(entry.get("sessionDate")).isNotNull();
    }

    @Test
    @DisplayName("onSessionEnd appends to existing journal file")
    void onSessionEnd_appendsToExistingJournal() {
        // Given - first session
        Trade trade1 = buildTestTrade("ES", OrderSide.BUY, 100.0, TradeTier.TIER_2);
        journalService.onSessionEnd(List.of(trade1));

        // When - second session
        Trade trade2 = buildTestTrade("NQ", OrderSide.SELL, -50.0, TradeTier.TIER_3);
        journalService.onSessionEnd(List.of(trade2));

        // Then
        List<Map<String, Object>> entries = journalService.loadAll();
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).get("symbol")).isEqualTo("ES");
        assertThat(entries.get(1).get("symbol")).isEqualTo("NQ");
    }

    @Test
    @DisplayName("onSessionEnd handles empty trade list gracefully")
    void onSessionEnd_emptyList() {
        // When
        journalService.onSessionEnd(List.of());

        // Then - no file should be created
        assertThat(Files.exists(journalPath)).isFalse();
    }

    @Test
    @DisplayName("onSessionEnd handles null trade list gracefully")
    void onSessionEnd_nullList() {
        // When
        journalService.onSessionEnd(null);

        // Then - no file should be created
        assertThat(Files.exists(journalPath)).isFalse();
    }

    @Test
    @DisplayName("loadAll returns empty list when no journal file exists")
    void loadAll_noFile_returnsEmptyList() {
        // When
        List<Map<String, Object>> entries = journalService.loadAll();

        // Then
        assertThat(entries).isEmpty();
    }

    @Test
    @DisplayName("Loss trades are recorded with correct result")
    void onSessionEnd_lossTrade_recordsLoss() {
        // Given
        Trade trade = buildTestTrade("GC", OrderSide.SELL, -375.00, TradeTier.TIER_3);

        // When
        journalService.onSessionEnd(List.of(trade));

        // Then
        List<Map<String, Object>> entries = journalService.loadAll();
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).get("result")).isEqualTo("LOSS");
        assertThat(entries.get(0).get("realizedPnL")).isEqualTo(-375.0);
    }

    private Trade buildTestTrade(String symbol, OrderSide side, double pnl, TradeTier tier) {
        return Trade.builder()
                .symbol(symbol)
                .side(side)
                .quantity(1)
                .entryPrice(100.0)
                .exitPrice(pnl > 0 ? 110.0 : 90.0)
                .entryTime(Instant.now().minusSeconds(3600))
                .exitTime(Instant.now())
                .realizedPnL(pnl)
                .riskAmount(250.0)
                .notes("Test trade")
                .tier(tier)
                .confluenceFactors(List.of("FVG", "SMT"))
                .build();
    }
}
