package com.topstep.trading.config;

import com.topstep.trading.lifecycle.LucidAccountProfile;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * V6 Agent 05.2: loads the Lucid account profiles from config/lucid_profiles.yaml
 * (Agent 01; every number cites a Lucid URL there and in docs/LUCID_RULES.md).
 * Java never hand-types a plan number (anti-pattern AP-5).
 *
 * <p>Lookup order: system property {@code lucid.profiles} (a file path), the
 * classpath resource {@code /lucid_profiles.yaml} (trading-engine's
 * processResources copies the repo file there), then {@code config/lucid_profiles.yaml}
 * / {@code ../config/lucid_profiles.yaml} relative to the working directory.
 * Missing file or missing required field = fail loud.
 */
public final class LucidProfiles {

    public static final String DEFAULT_EVAL = "LUCID_FLEX_50K_EVAL";
    public static final String DEFAULT_FUNDED = "LUCID_FLEX_50K_FUNDED";
    public static final String PATH_PROPERTY = "lucid.profiles";
    static final String RESOURCE = "/lucid_profiles.yaml";

    private static volatile Map<String, LucidAccountProfile> cached;

    private LucidProfiles() {}

    /** The profile with this id (e.g. {@code LUCID_FLEX_50K_EVAL}); fails loud when absent. */
    public static LucidAccountProfile get(String id) {
        LucidAccountProfile p = all().get(id);
        if (p == null) {
            throw new IllegalArgumentException("Unknown Lucid profile '" + id + "' (config/lucid_profiles.yaml has "
                    + all().keySet() + ")");
        }
        return p;
    }

    public static LucidAccountProfile flex50kEval() { return get(DEFAULT_EVAL); }

    public static LucidAccountProfile flex50kFunded() { return get(DEFAULT_FUNDED); }

    public static Map<String, LucidAccountProfile> all() {
        Map<String, LucidAccountProfile> m = cached;
        if (m == null) {
            synchronized (LucidProfiles.class) {
                if (cached == null) cached = load();
                m = cached;
            }
        }
        return m;
    }

    private static Map<String, LucidAccountProfile> load() {
        String override = System.getProperty(PATH_PROPERTY);
        try {
            if (override != null && !override.isBlank()) {
                return parse(Files.readString(Path.of(override), StandardCharsets.UTF_8));
            }
            try (InputStream in = LucidProfiles.class.getResourceAsStream(RESOURCE)) {
                if (in != null) return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            for (String rel : List.of("config/lucid_profiles.yaml", "../config/lucid_profiles.yaml")) {
                Path p = Path.of(rel);
                if (Files.isRegularFile(p)) return parse(Files.readString(p, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read lucid_profiles.yaml: " + e.getMessage(), e);
        }
        throw new IllegalStateException("config/lucid_profiles.yaml not found (classpath " + RESOURCE
                + ", -D" + PATH_PROPERTY + ", ./config, ../config)");
    }

    /** Parse the YAML text (package-visible for tests). */
    @SuppressWarnings("unchecked")
    static Map<String, LucidAccountProfile> parse(String yamlText) {
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        Object rootObj;
        try (Reader r = new StringReader(yamlText)) {
            rootObj = yaml.load(r);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        if (!(rootObj instanceof Map)) throw new IllegalStateException("lucid_profiles.yaml: root is not a map");
        Map<String, Object> root = (Map<String, Object>) rootObj;
        Map<String, Object> common = (Map<String, Object>) root.getOrDefault("common", Map.of());
        Map<String, Object> profiles = (Map<String, Object>) root.get("profiles");
        if (profiles == null || profiles.isEmpty()) throw new IllegalStateException("lucid_profiles.yaml: no profiles");
        Map<String, LucidAccountProfile> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : profiles.entrySet()) {
            String id = e.getKey();
            Map<String, Object> p = (Map<String, Object>) e.getValue();
            List<LucidAccountProfile.ScalingBand> bands = new ArrayList<>();
            Object sp = p.get("scaling_plan");
            if (sp instanceof List<?> list) {
                for (Object o : list) {
                    Map<String, Object> b = (Map<String, Object>) o;
                    Object to = b.get("profit_to_usd");
                    bands.add(new LucidAccountProfile.ScalingBand(num(id, b, "profit_from_usd"),
                            to == null ? Double.POSITIVE_INFINITY : ((Number) to).doubleValue(),
                            (int) num(id, b, "micros")));
                }
            }
            int maxMicros = (int) num(id, p, "max_contracts_micros");
            Object cap = p.get("scaling_cap_micros");
            String drawdown = String.valueOf(p.getOrDefault("drawdown_type", common.getOrDefault("drawdown_type", "")));
            out.put(id, new LucidAccountProfile(
                    id,
                    String.valueOf(p.getOrDefault("plan", "")),
                    String.valueOf(p.getOrDefault("stage", "")),
                    num(id, p, "starting_balance_usd"),
                    optNum(p, "profit_target_usd", 0.0),
                    num(id, p, "max_loss_limit_usd"),
                    num(id, p, "initial_trail_balance_usd"),
                    num(id, p, "locked_mll_balance_usd"),
                    num(id, p, "v6_soft_daily_loss_usd"),
                    maxMicros,
                    cap == null ? maxMicros : ((Number) cap).intValue(),
                    Collections.unmodifiableList(bands),
                    optNum(p, "consistency_max_pct", 0.0),
                    (int) optNum(p, "min_trading_days", 0.0),
                    drawdown,
                    optNum(p, "profit_split_trader_pct", Double.NaN) / 100.0));
        }
        // profit_split_trader_pct is stated once per plan (LucidFlex 50K funded,
        // source "payouts": 90/10 for every LucidFlex size) -> inherit it.
        Map<String, LucidAccountProfile> fixed = new LinkedHashMap<>();
        for (LucidAccountProfile p : out.values()) {
            if (Double.isNaN(p.profitSplitTrader())) {
                final String plan = p.plan();
                double split = out.values().stream().filter(q -> q.plan().equals(plan))
                        .mapToDouble(LucidAccountProfile::profitSplitTrader).filter(d -> !Double.isNaN(d))
                        .findFirst().orElse(Double.NaN);
                p = new LucidAccountProfile(p.id(), p.plan(), p.stage(), p.startingBalance(), p.profitTarget(),
                        p.maxLossLimit(), p.initialTrailBalance(), p.lockedMllBalance(), p.softDailyLoss(),
                        p.maxContractsMicros(), p.scalingCapMicros(), p.scalingPlan(), p.consistencyMaxPct(),
                        p.minTradingDays(), p.drawdownType(), split);
            }
            fixed.put(p.id(), p);
        }
        return Collections.unmodifiableMap(fixed);
    }

    private static double num(String id, Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (!(v instanceof Number n)) {
            throw new IllegalStateException("lucid_profiles.yaml: " + id + "." + key + " is required (got " + v + ")");
        }
        return n.doubleValue();
    }

    private static double optNum(Map<String, Object> m, String key, double dflt) {
        Object v = m.get(key);
        return v instanceof Number n ? n.doubleValue() : dflt;
    }
}
