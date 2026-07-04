package com.topstep.trading.montecarlo;

import com.topstep.trading.lifecycle.AccountLifecycle;
import com.topstep.trading.risk.RiskProfile;

/**
 * SA5 Monte Carlo comparison — the empirical argument for $150 risk/trade.
 *
 * <p>Runs the existing {@link MonteCarloSimulator} path model twice over the
 * SAME trade-outcome distribution and the SAME Topstep 50K rails
 * ($1,000 DLL, $2,000 MLL, $3,000 target, 60 trading days max):
 *
 * <ol>
 *   <li><b>SCALP profile</b> — $150 risk/trade, max 6 trades/day,
 *       stop-for-the-day after 3 consecutive losses (the
 *       {@code topstep50kScalp()} discipline);</li>
 *   <li><b>NAIVE profile</b> — $500 risk/trade, max 6 trades/day,
 *       NO loss-streak stop.</li>
 * </ol>
 *
 * <p><b>Stated assumptions</b> — primary run: win rate 52% (middle of the
 * plausible 50–55% band) at ~1:1 RR <b>net of costs</b>, i.e. win +1.00R /
 * loss −1.00R where the round-trip cost (~$24.60/trade at $150 risk on MNQ:
 * 6 micros × 2 sides × ($1.55 commission + 1 tick slippage) ≈ 0.16R) is
 * already absorbed in the net outcomes. A pessimistic sensitivity run
 * (win +0.84R / loss −1.16R — the edge does NOT cover costs) is printed as
 * well. Zone multipliers and self-imposed sub-limits are neutralized (1.0)
 * in both profiles so the ONLY variables are risk size and the loss-streak
 * stop. Seeded (42) — reproducible.
 *
 * <p>Run: {@code ./gradlew :trading-engine:run --args="MONTECARLO"}
 */
public final class MonteCarloScalpComparison {

    private static final int ITERATIONS = 10_000;
    private static final long SEED = 42L;

    // Assumptions (documented above and printed in the report).
    private static final double WIN_RATE = 0.52;
    private static final double NET_WIN_R = +1.00;   // ~1:1 RR, net of costs
    private static final double NET_LOSS_R = -1.00;
    private static final double PESSIMISTIC_WIN_R = +0.84;  // costs NOT covered
    private static final double PESSIMISTIC_LOSS_R = -1.16; // by the gross edge

    private MonteCarloScalpComparison() {}

    /** Aggregated per-profile results. */
    public static final class ProfileResult {
        public final String name;
        public int passed;
        public int blown;
        public int timeout;
        public int pathsWithDllBreach;
        public long totalDllBreachDays;
        public double sumMaxDrawdown;
        public double sumFinalEquity;

        ProfileResult(String name) { this.name = name; }

        public double pDll() { return (double) pathsWithDllBreach / ITERATIONS; }
        public double pMll() { return (double) blown / ITERATIONS; }
        public double pPass() { return (double) passed / ITERATIONS; }
        public double pTimeout() { return (double) timeout / ITERATIONS; }
        public double avgDllDays() { return (double) totalDllBreachDays / ITERATIONS; }
        public double avgMaxDd() { return sumMaxDrawdown / ITERATIONS; }
        public double avgFinalEquity() { return sumFinalEquity / ITERATIONS; }
    }

    /** Scalp discipline: $150 risk, 6/day, 3-consecutive-loss stop. */
    static RiskProfile scalpProfile() {
        return RiskProfile.builder()
                .baseRiskPct(150.0 / 50_000.0)      // $150/trade
                .maxTradesPerDay(6)
                .maxConsecutiveLosses(3)             // stop for the day
                .maxDailyLossPct(1.0)                // no self-imposed sub-limit
                .cruiseRiskMultiplier(1.0)           // fixed risk — isolate the variable
                .protectionRiskMultiplier(1.0)
                .cautionRiskMultiplier(1.0)
                .dangerRiskMultiplier(1.0)
                .maxRiskBudgetPct(1.0)
                .maxDailyLimitPct(1.0)
                .partialsEnabled(false)
                .build();
    }

    /** Naive sizing: $500 risk, 6/day, NO loss-streak stop. */
    static RiskProfile naiveProfile() {
        return RiskProfile.builder()
                .baseRiskPct(500.0 / 50_000.0)      // $500/trade
                .maxTradesPerDay(6)
                .maxConsecutiveLosses(Integer.MAX_VALUE) // never stops
                .maxDailyLossPct(1.0)
                .cruiseRiskMultiplier(1.0)
                .protectionRiskMultiplier(1.0)
                .cautionRiskMultiplier(1.0)
                .dangerRiskMultiplier(1.0)
                .maxRiskBudgetPct(1.0)
                .maxDailyLimitPct(1.0)
                .partialsEnabled(false)
                .build();
    }

    /** Run one profile through the EXISTING simulator's path model. */
    public static ProfileResult runProfile(String name, RiskProfile profile,
                                           double winR, double lossR) {
        MonteCarloSimulator simulator = new MonteCarloSimulator(SEED);
        StrategyMetrics metrics = StrategyMetrics.fromParameters(
                WIN_RATE, winR, lossR, 6.0);
        AccountLifecycle template = AccountLifecycle.topstep50kEvaluation();

        ProfileResult r = new ProfileResult(name);
        for (int i = 0; i < ITERATIONS; i++) {
            PathResult path = simulator.simulateSinglePath(metrics, profile, template);
            switch (path.getOutcome()) {
                case PASSED -> r.passed++;
                case BLOWN -> r.blown++;
                case TIMEOUT -> r.timeout++;
            }
            if (path.hadDllBreach()) r.pathsWithDllBreach++;
            r.totalDllBreachDays += path.getDllBreachDays();
            r.sumMaxDrawdown += path.getMaxDrawdown();
            r.sumFinalEquity += path.getFinalEquity();
        }
        return r;
    }

    public static void main(String[] args) {
        System.out.println("=".repeat(78));
        System.out.println("MONTE CARLO: SCALP ($150, 6/day, 3-loss stop) vs NAIVE ($500, 6/day, no stop)");
        System.out.println("=".repeat(78));
        System.out.println("Rails: Topstep 50K — DLL $1,000 | MLL $2,000 | target $3,000 | max 60 days");
        System.out.printf("Assumptions: win rate %.0f%% (plausible 50-55%% band), ~1:1 RR NET of costs%n",
                WIN_RATE * 100);
        System.out.println("  (win +1.00R / loss -1.00R with the ~0.16R round-trip cost — $24.60 at $150");
        System.out.println("  risk: MNQ 12-pt stop, 6 micros, $1.55 commission/side/contract + 1 tick");
        System.out.println("  slippage/side/contract — absorbed in the net outcomes).");
        System.out.println("Zone multipliers/self-imposed sub-limits neutralized; seed=42; "
                + ITERATIONS + " paths each.");
        System.out.println();

        System.out.println("PRIMARY (net ~1:1 — the strategy's gross edge covers costs):");
        printTable(
                runProfile("SCALP  $150 risk, 6/day, 3-loss stop", scalpProfile(),
                        NET_WIN_R, NET_LOSS_R),
                runProfile("NAIVE  $500 risk, 6/day, no stop    ", naiveProfile(),
                        NET_WIN_R, NET_LOSS_R));

        System.out.println();
        System.out.printf("SENSITIVITY (edge does NOT cover costs: win %+.2fR / loss %+.2fR):%n",
                PESSIMISTIC_WIN_R, PESSIMISTIC_LOSS_R);
        printTable(
                runProfile("SCALP  $150 risk, 6/day, 3-loss stop", scalpProfile(),
                        PESSIMISTIC_WIN_R, PESSIMISTIC_LOSS_R),
                runProfile("NAIVE  $500 risk, 6/day, no stop    ", naiveProfile(),
                        PESSIMISTIC_WIN_R, PESSIMISTIC_LOSS_R));

        System.out.println();
        System.out.println("Reading: at $150 with the 3-loss stop the worst mathematically possible day");
        System.out.println("is 3 straight losses = -$522 (at -1.16R) — the full $1,000 DLL is UNREACHABLE");
        System.out.println("(P(DLL)=0 in every scenario). At $500 with no stop, 2 losses already breach");
        System.out.println("the DLL, and the MLL sits only ~4 net losses away — the naive account rides");
        System.out.println("its rails on every ordinary losing streak. That is the case for $150 sizing.");
    }

    private static void printTable(ProfileResult scalp, ProfileResult naive) {
        String fmt = "| %-38s | %10s | %11s | %8s | %8s | %10s | %12s |%n";
        System.out.printf(fmt, "Profile", "P(DLL hit)", "P(MLL/bust)", "P(pass)", "P(t/o)",
                "avg DLL d", "avg maxDD $");
        System.out.println("|" + "-".repeat(40) + "|" + "-".repeat(12) + "|" + "-".repeat(13)
                + "|" + "-".repeat(10) + "|" + "-".repeat(10) + "|" + "-".repeat(12)
                + "|" + "-".repeat(14) + "|");
        for (ProfileResult r : new ProfileResult[]{scalp, naive}) {
            System.out.printf(fmt, r.name,
                    String.format("%.2f%%", r.pDll() * 100),
                    String.format("%.2f%%", r.pMll() * 100),
                    String.format("%.2f%%", r.pPass() * 100),
                    String.format("%.2f%%", r.pTimeout() * 100),
                    String.format("%.3f", r.avgDllDays()),
                    String.format("%.0f", r.avgMaxDd()));
        }
        System.out.printf("Avg final equity: scalp $%.0f | naive $%.0f (start $50,000)%n",
                scalp.avgFinalEquity(), naive.avgFinalEquity());
    }
}
