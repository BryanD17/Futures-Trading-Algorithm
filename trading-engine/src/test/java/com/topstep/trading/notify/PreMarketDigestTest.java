package com.topstep.trading.notify;

import com.topstep.trading.chartstate.LevelType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The digest is a daily <em>commitment</em>: members notice a missing routine
 * faster than a missing signal, and a skipped post looks identical to a dead
 * bot. These tests pin the three properties that promise depends on — it
 * survives restarts, it catches up rather than skipping, and it says something
 * honest on a cold day instead of going quiet.
 */
class PreMarketDigestTest {

    private static final ZoneId ET = DigestConfig.EXCHANGE_ZONE;
    private static final LocalTime AT_0830 = LocalTime.of(8, 30);

    private static DigestConfig cfg(int catchUpMinutes) {
        return new DigestConfig(true, "https://example.invalid/webhook",
                AT_0830, catchUpMinutes, Path.of("data", "digest_state.txt"));
    }

    private static ZonedDateTime et(String isoLocal) {
        return ZonedDateTime.of(java.time.LocalDateTime.parse(isoLocal), ET);
    }

    // ── schedule ──────────────────────────────────────────────────────────

    @Test
    void postsOnceAtTheScheduledTime() {
        // Tuesday 2026-08-25 08:30 ET
        assertTrue(PreMarketDigest.shouldPost(et("2026-08-25T08:30"), null, cfg(180)));
    }

    @Test
    void doesNotPostBeforeTheScheduledTime() {
        assertFalse(PreMarketDigest.shouldPost(et("2026-08-25T08:29"), null, cfg(180)));
    }

    @Test
    void neverPostsTwiceForTheSameTradingDay() {
        LocalDate today = LocalDate.parse("2026-08-25");
        assertFalse(PreMarketDigest.shouldPost(et("2026-08-25T09:15"), today, cfg(180)),
                "a restart mid-morning must not re-post the day's digest");
    }

    @Test
    void catchesUpAfterALateStartRatherThanSkippingTheDay() {
        // Engine was down at 08:30 and came up at 10:00 — 90 minutes late,
        // inside the window. Late beats absent.
        assertTrue(PreMarketDigest.shouldPost(et("2026-08-25T10:00"), null, cfg(180)));
    }

    @Test
    void doesNotFireAPreMarketPostInTheAfternoon() {
        // 4 hours late, outside a 180-minute window. A "pre-market" digest at
        // 12:31 is worse than none — it would be actively misleading.
        assertFalse(PreMarketDigest.shouldPost(et("2026-08-25T12:31"), null, cfg(180)));
    }

    @Test
    void skipsWeekends() {
        assertFalse(PreMarketDigest.shouldPost(et("2026-08-22T08:30"), null, cfg(180)),
                "Saturday");
        assertFalse(PreMarketDigest.shouldPost(et("2026-08-23T08:30"), null, cfg(180)),
                "Sunday");
    }

    @Test
    void yesterdaysStateDoesNotSuppressTodaysPost() {
        LocalDate yesterday = LocalDate.parse("2026-08-24");
        assertTrue(PreMarketDigest.shouldPost(et("2026-08-25T08:30"), yesterday, cfg(180)));
    }

    @Test
    void disabledConfigNeverPosts() {
        DigestConfig off = new DigestConfig(false, "https://example.invalid/w",
                AT_0830, 180, Path.of("x"));
        assertFalse(PreMarketDigest.shouldPost(et("2026-08-25T08:30"), null, off));
    }

    // ── restart durability ────────────────────────────────────────────────

    @Test
    void stateRoundTripsSoARestartDoesNotDoublePost(@TempDir Path dir) {
        Path state = dir.resolve("nested").resolve("digest_state.txt");
        LocalDate day = LocalDate.parse("2026-08-25");

        PreMarketDigest.writeState(state, day);
        assertTrue(Files.exists(state), "parent directories must be created");
        assertEquals(day, PreMarketDigest.readState(state));

        assertFalse(PreMarketDigest.shouldPost(et("2026-08-25T09:00"),
                        PreMarketDigest.readState(state), cfg(180)),
                "state read back after a restart must suppress a same-day repost");
    }

    @Test
    void unreadableStateFailsTowardPostingNotSilence(@TempDir Path dir) throws Exception {
        Path missing = dir.resolve("nope.txt");
        assertNull(PreMarketDigest.readState(missing), "missing file == never posted");

        Path garbage = dir.resolve("garbage.txt");
        Files.writeString(garbage, "not-a-date");
        assertNull(PreMarketDigest.readState(garbage),
                "corrupt state must degrade to 'never posted' — an extra post is "
                        + "recoverable, a silent skip is not");
    }

    // ── formatting, including the cold-day promise ────────────────────────

    private static final Instant NOW = Instant.parse("2026-08-25T12:30:00Z");
    private static final LocalDate DAY = LocalDate.parse("2026-08-25");

    private static Map<LevelType, Double> mnqLevels() {
        Map<LevelType, Double> m = new LinkedHashMap<>();
        m.put(LevelType.PDH, 20415.25);
        m.put(LevelType.PDL, 20280.50);
        m.put(LevelType.ASIA_HIGH, 20390.75);
        m.put(LevelType.LONDON_LOW, 20301.00);
        return m;
    }

    @Test
    void warmDigestRendersLevelsWithCorrectPrecisionAndDisclaimer() {
        String json = new PreMarketDigestFormatter().format(List.of(
                new PreMarketDigestFormatter.SymbolSection(
                        "MNQ", true, mnqLevels(), 4, null, 2)), DAY, NOW);

        assertTrue(json.startsWith("{\"embeds\":["));
        assertTrue(json.contains("MNQ"), "symbol in the title");
        assertTrue(json.contains("Pre-market levels"));
        assertTrue(json.contains("20415.25"), "PDH rendered");
        assertTrue(json.contains("20280.50"), "PDL keeps its trailing zero");
        assertTrue(json.contains("Prev day high"), "labels are readable, not enum names");
        assertTrue(json.contains("Asia high"));
        assertTrue(json.contains("Not financial advice"), "footer is mandatory");
        assertTrue(json.contains("Tuesday 25 August"), "trading day is stated");
    }

    @Test
    void goldRendersAtOneDecimalNotTwo() {
        Map<LevelType, Double> gold = Map.of(LevelType.PDH, 4394.85);
        String json = new PreMarketDigestFormatter().format(List.of(
                new PreMarketDigestFormatter.SymbolSection(
                        "MGC", true, gold, 1, null, 1)), DAY, NOW);
        assertTrue(json.contains("4394.9"), "MGC is 1dp, half-up: " + json);
        assertFalse(json.contains("4394.85"), "must not render at MNQ precision");
    }

    @Test
    void coldSymbolStillGetsAnHonestEmbedRatherThanSilence() {
        String json = new PreMarketDigestFormatter().format(List.of(
                new PreMarketDigestFormatter.SymbolSection(
                        "MNQ", false, Map.of(), 0, null, 2)), DAY, NOW);

        assertTrue(json.contains("\"embeds\""), "a cold day still publishes");
        assertTrue(json.contains("COLD"), "it must say the chart is cold");
        assertTrue(json.contains("data state, not a market call"),
                "and must not be mistakable for a market opinion");
        assertTrue(json.contains("Not financial advice"), "footer still mandatory");
        assertFalse(json.contains("Prev day high"), "no levels invented on a cold day");
    }

    @Test
    void warmButLevellessSymbolAlsoExplainsItself() {
        String json = new PreMarketDigestFormatter().format(List.of(
                new PreMarketDigestFormatter.SymbolSection(
                        "MNQ", true, Map.of(), 0, null, 2)), DAY, NOW);
        assertTrue(json.contains("no reference levels have formed yet"));
        assertTrue(json.contains("Not financial advice"));
    }

    @Test
    void watchingSectionSurfacesALiveZone() {
        String json = new PreMarketDigestFormatter().format(List.of(
                new PreMarketDigestFormatter.SymbolSection(
                        "MNQ", true, mnqLevels(), 4,
                        "Bullish OTE zone, state ARMED\n`20330.75` to `20305.50`", 2)), DAY, NOW);
        assertTrue(json.contains("Watching"));
        assertTrue(json.contains("ARMED"));
        assertTrue(json.contains("20330.75"));
    }

    @Test
    void multipleSymbolsProduceOneEmbedEach() {
        String json = new PreMarketDigestFormatter().format(List.of(
                new PreMarketDigestFormatter.SymbolSection("MNQ", true, mnqLevels(), 4, null, 2),
                new PreMarketDigestFormatter.SymbolSection("MGC", true,
                        Map.of(LevelType.PDL, 4381.0), 1, null, 1)), DAY, NOW);
        assertTrue(json.contains("MNQ"));
        assertTrue(json.contains("MGC"));
        assertEquals(2, json.split("\"title\"", -1).length - 1, "one embed per symbol");
    }

    @Test
    void footerIsMandatoryHereToo() {
        assertThrows(IllegalArgumentException.class,
                () -> new PreMarketDigestFormatter(" "));
    }

    // ── config ────────────────────────────────────────────────────────────

    @Test
    void malformedPostTimeFallsBackRatherThanPostingAtMidnight() {
        assertEquals(AT_0830, DigestConfig.parseTime("garbage"));
        assertEquals(AT_0830, DigestConfig.parseTime(null));
        assertEquals(LocalTime.of(9, 15), DigestConfig.parseTime("09:15"));
    }

    @Test
    void describeNeverLeaksTheDigestWebhook() {
        DigestConfig c = new DigestConfig(true,
                "https://discord.com/api/webhooks/1/DIGESTSECRET",
                AT_0830, 180, Path.of("data", "s.txt"));
        assertFalse(c.describe().contains("DIGESTSECRET"));
        assertTrue(c.describe().contains("<redacted>"));
        assertTrue(c.hasWebhook());
    }

    @Test
    void missingWebhookIsReportedNotFabricated() {
        DigestConfig c = new DigestConfig(true, null, AT_0830, 180, Path.of("s.txt"));
        assertFalse(c.hasWebhook());
        assertTrue(c.describe().contains("<none>"));
    }
}
