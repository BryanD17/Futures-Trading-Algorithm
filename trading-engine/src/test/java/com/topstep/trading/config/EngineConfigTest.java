package com.topstep.trading.config;

import com.topstep.trading.confluence.ConfluenceField;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V5 Agent 01 — EngineConfig: precedence, aliases, sources, registry
 * coverage, product defaults, boot table.
 */
@DisplayName("EngineConfig (V5 Agent 01 single source of truth)")
class EngineConfigTest {

    @AfterEach
    void cleanup() {
        EngineConfig.reset();
    }

    private static EngineConfig load(Map<String, String> env, Map<String, String> sys) {
        return EngineConfigLoader.load(env, sys::get);
    }

    private static Path writeProps(Path dir, String body) throws IOException {
        Path f = dir.resolve("engine.properties");
        Files.writeString(f, body, StandardCharsets.UTF_8);
        return f;
    }

    @Test
    @DisplayName("precedence: -D > ENGINE_ env > engine.properties > classpath defaults > code")
    void precedence(@TempDir Path dir) throws IOException {
        Path f = writeProps(dir, "backfill.days=5\nhtf.backfill.days=40\nmock.virtualMinutes=10\n");
        Map<String, String> sys = new HashMap<>();
        sys.put("engine.props", f.toString());
        Map<String, String> env = new HashMap<>();
        env.put("ENGINE_HTF_BACKFILL_DAYS", "60");
        env.put("ENGINE_MOCK_VIRTUALMINUTES", "20");
        sys.put("mock.virtualMinutes", "30");
        EngineConfig c = load(env, sys);

        // classpath default (7) beaten by file (5)
        assertThat(c.lookup("backfill.days").value()).isEqualTo("5");
        assertThat(c.lookup("backfill.days").source()).isEqualTo(EngineConfig.Source.PROPERTIES_FILE);
        // file (40) beaten by env (60)
        assertThat(c.getInt("htf.backfill.days", 30)).isEqualTo(60);
        assertThat(c.lookup("htf.backfill.days").source()).isEqualTo(EngineConfig.Source.ENV);
        // env (20) beaten by -D (30)
        assertThat(c.getLong("mock.virtualMinutes", 2000L)).isEqualTo(30L);
        assertThat(c.lookup("mock.virtualMinutes").source()).isEqualTo(EngineConfig.Source.SYSTEM_PROPERTY);
        // nothing set anywhere -> caller's code default
        assertThat(c.lookup("sim.backfill.seed")).isNull();
        assertThat(c.getLong("sim.backfill.seed", 42L)).isEqualTo(42L);
        assertThat(c.fileLoaded()).isTrue();
    }

    @Test
    @DisplayName("classpath engine-defaults.properties carries the V5 product defaults (scalp stays OFF)")
    void productDefaults() {
        EngineConfig c = load(Map.of(), Map.of("engine.props", "NONE"));
        assertThat(c.getBoolean("session.allSessions", false)).isTrue();
        assertThat(c.getString("session.gateMode", "BLOCKING")).isEqualTo("SCORING");
        assertThat(c.getInt("backfill.days", 3)).isEqualTo(7);
        assertThat(c.getBoolean("bias.hysteresis", false)).isTrue();
        assertThat(c.getBoolean("strategy.legacyFallback", true)).isFalse();
        // scalp mode is NOT flipped by the defaults
        assertThat(c.lookup("scalp.enabled")).isNull();
        assertThat(c.getBoolean("scalpMode.enabled", false)).isFalse();
        assertThat(c.lookup("backfill.days").source()).isEqualTo(EngineConfig.Source.DEFAULT);
        assertThat(c.fileLoaded()).isFalse();
    }

    @Test
    @DisplayName("legacy names are aliases of the V5 keys (owner's -D flags keep working)")
    void aliases(@TempDir Path dir) throws IOException {
        Map<String, String> sys = new HashMap<>();
        sys.put("engine.props", "NONE");
        sys.put("stdvote.displacement.recentBars", "12");
        sys.put("stdvote.displacement.atrMult", "1.2");
        sys.put("stdvote.displacement.bodyPct", "0.55");
        sys.put("scalpMode.enabled", "true");
        EngineConfig c = load(Map.of(), sys);
        assertThat(c.getInt("displacement.recentBars", 5)).isEqualTo(12);
        assertThat(c.getInt("stdvote.displacement.recentBars", 5)).isEqualTo(12);
        assertThat(c.getDouble("displacement.atrMult", 1.5)).isEqualTo(1.2);
        assertThat(c.getDouble("displacement.bodyPct", 0.65)).isEqualTo(0.55);
        assertThat(c.getBoolean("scalp.enabled", false)).isTrue();
        // legacy alias in the file layer loses to canonical -D; canonical wins over alias in one layer
        Path f = writeProps(dir, "bias.hysteresis.enabled=false\n");
        EngineConfig c2 = load(Map.of(), Map.of("engine.props", f.toString()));
        assertThat(c2.getBoolean("bias.hysteresis", true)).isFalse();
        assertThat(c2.lookup("bias.hysteresis.enabled").source()).isEqualTo(EngineConfig.Source.PROPERTIES_FILE);
        EngineConfig c3 = load(Map.of(), Map.of("engine.props", f.toString(), "bias.hysteresis", "true"));
        assertThat(c3.getBoolean("bias.hysteresis.enabled", false)).isTrue();
    }

    @Test
    @DisplayName("default engine.properties path is ${user.home}/topstep-trading/engine.properties")
    void userHomeFile(@TempDir Path home) throws IOException {
        Path dir = Files.createDirectories(home.resolve("topstep-trading"));
        Files.writeString(dir.resolve("engine.properties"), "trade.profile=STANDARD\nmy.future.flag=1\n");
        EngineConfig c = load(Map.of(), Map.of("user.home", home.toString()));
        assertThat(c.fileLoaded()).isTrue();
        assertThat(c.getString("trade.profile", "STRICT")).isEqualTo("STANDARD");
        assertThat(c.filePath()).endsWith("engine.properties");
        assertThat(c.unknownFileKeys()).containsExactly("my.future.flag");
        // unknown keys are still readable (other agents' flags before registration)
        assertThat(c.getInt("my.future.flag", 0)).isEqualTo(1);
    }

    @Test
    @DisplayName("invalid values fall back to the default (legacy parser semantics)")
    void invalidValues() {
        EngineConfig c = load(Map.of(), Map.of("engine.props", "NONE",
                "backfill.days", "seven", "sim.warmBoot", "yes"));
        assertThat(c.getInt("backfill.days", 3)).isEqualTo(3);
        assertThat(c.getBoolean("sim.warmBoot", true)).isTrue();
    }

    @Test
    @DisplayName("boot table: every registered key with value + source, secrets masked, mode lines")
    void bootTable() {
        EngineConfig c = load(Map.of(), Map.of("engine.props", "NONE",
                "topstep.apiKey", "SECRET-KEY-123", "stdvote.displacement.recentBars", "12"));
        String t = c.formatBootTable();
        assertThat(t).contains("EFFECTIVE ENGINE CONFIG");
        assertThat(t).doesNotContain("SECRET-KEY-123");
        assertThat(t).containsPattern("\\| topstep\\.apiKey +\\| \\*\\*\\*\\* +\\| SYSTEM_PROPERTY +\\|");
        assertThat(t).containsPattern("\\| displacement\\.recentBars +\\| 12 +\\| SYSTEM_PROPERTY +\\|");
        assertThat(t).containsPattern("\\| backfill\\.days +\\| 7 +\\| DEFAULT +\\|");
        assertThat(t).contains("SESSION GATE: SCORING (entries allowed all sessions except 14:45-17:00 CT)");
        assertThat(t).contains("SCALP MODE: OFF");
        assertThat(t).contains("FAIL FAST (strategy.legacyFallback=false)");
        for (EngineConfig.Key k : EngineConfig.KEYS.values()) {
            assertThat(t).as("table lists " + k.name()).contains(k.name());
        }
        Map<String, Object> api = c.toApiMap();
        assertThat(api).containsKeys("configFile", "configFileLoaded", "keys", "modes");
    }

    @Test
    @DisplayName("registry covers all 94 keys of DIAGNOSIS_V5 §2 (+ V5 additions)")
    void registryCoversDiagnosisTable() {
        List<String> diagnosis = List.of(
                "backtest.commissionPerSide", "backtest.slippageTicks",
                "chart.minLegTicks.MNQ", "chart.swingStrength.MNQ", "chart.zoneExpiryBars.MNQ",
                "chart.anchorCompare", "chart.anchorMode", "chart.anchorMode.MGC", "chart.oteBand",
                "chart.oteBand.MNQ", "confluence.nearTicks", "confluence.raidScoreFloor",
                "confluence.recentMinutes", "mock.candleIntervalMs", "mock.virtualClock",
                "mock.virtualMinutes", "sim.warmBoot", "sim.tape", "sim.backfill.seed", "backfill.days",
                "htf.backfill.days", "topstep.allowNonSimulated", "topstep.apiUrl", "topstep.username",
                "topstep.apiKey", "topstep.accountId",
                "ictlib.enabled", "ictlib.displacement.meanLen", "ictlib.displacement.wickRatioMax",
                "ictlib.retain.displacement", "ictlib.fvg.mode", "ictlib.retain.fvg", "ictlib.retain.bpr",
                "ictlib.retain.volumeImbalance", "ictlib.vi.projectBars", "ictlib.retain.gapWeekly",
                "ictlib.retain.gapDaily", "ictlib.pool.swingLen", "ictlib.pool.toleranceDiv",
                "ictlib.pool.minCluster", "ictlib.pool.scanDepth", "ictlib.retain.pool",
                "ictlib.pool.atrPeriod", "ictlib.ob.swingLen", "ictlib.ob.useBody",
                "ictlib.retain.orderBlock", "ictlib.structure.pivotLeft", "ictlib.structure.pivotRight",
                "ictlib.structure.historyCap", "ictlib.structure.mssAgreeWindow",
                "stdvote.symbol", "stdvote.smt", "stdvote.multiInstrument", "notify.discord.enabled",
                "bias.vote.mode", "bias.v1.includeH4", "ote30m.confluence", "ote30m.acceptArmed",
                "ote.stats.file", "pd.gate.mode", "pd.eqBandTicks", "pd.minRangeTicks",
                "pd.minRangeTicks.MNQ", "pd.d1MinBars", "scalpMode.enabled", "scalp.breakevenAtHalfR",
                "scalp.minTargetClearanceTicks", "scalp.candidateWindowR", "scalp.minRaidScore",
                "scalp.rearmCooldownBars", "scalp.londonPrimeStartEt", "scalp.londonPrimeEndEt",
                "scalp.sizerSafetyCushion", "scalp.allSessions", "scalp.killzoneSizeBoost",
                "stdvOte.enabled", "stdvote.symbols.active", "stdvote.symbols.smt.MNQ",
                "stdvote.detectorTimeframe", "stdvOte.setupExpiryBars", "stdvOte.oteWindowBars",
                "stdvOte.mssFreshBars", "stdvOte.entryTimeoutBars", "stdvOte.stopBufferTicks",
                "stdvOte.reactionWickTicks", "stdvote.displacement.atrMult",
                "stdvote.displacement.bodyPct", "stdvote.displacement.recentBars",
                "stdvOte.rearmOnInvalidated", "bias.hysteresis.enabled", "bias.neutralGraceBars",
                "trade.profile", "profile.sim.file");
        for (String key : diagnosis) {
            assertThat(EngineConfig.registered(key)).as("registered: " + key).isNotNull();
        }
        for (String f : EngineConfig.CONFLUENCE_FIELDS) {
            assertThat(EngineConfig.registered("confluence.weight." + f)).isNotNull();
        }
        // CONFLUENCE_FIELDS mirrors the enum exactly
        List<String> enumKeys = new ArrayList<>();
        for (ConfluenceField cf : ConfluenceField.values()) enumKeys.add(cf.key());
        assertThat(EngineConfig.CONFLUENCE_FIELDS).containsExactlyElementsOf(enumKeys);
        // V5 additions
        assertThat(EngineConfig.registered("strategy.legacyFallback")).isNotNull();
        assertThat(EngineConfig.registered("session.gateMode")).isNotNull();
    }

    @Test
    @DisplayName("prints THIS test JVM's effective table (bootRun/test parity evidence)")
    void printsThisJvmTable() {
        EngineConfig.reset();
        String table = EngineConfig.current().formatBootTable();
        System.out.println(table);
        assertThat(table).contains("EFFECTIVE ENGINE CONFIG");
    }

    @Test
    @DisplayName("every readable key is forwarded by gradle/engine-config.gradle (bootRun == test)")
    void everyKeyForwarded() throws IOException {
        Path gradle = Path.of("..", "gradle", "engine-config.gradle");
        String text = Files.readString(gradle, StandardCharsets.UTF_8);
        String block = text.substring(text.indexOf("engineConfigPrefixes = ["), text.indexOf("]", text.indexOf("engineConfigPrefixes = [")));
        List<String> prefixes = new ArrayList<>();
        Matcher m = Pattern.compile("'([^']+)'").matcher(block);
        while (m.find()) prefixes.add(m.group(1));
        assertThat(prefixes).isNotEmpty();
        for (String name : EngineConfig.allReadableNames()) {
            assertThat(prefixes.stream().anyMatch(name::startsWith))
                    .as("forwarded prefix for " + name).isTrue();
        }
    }
}
