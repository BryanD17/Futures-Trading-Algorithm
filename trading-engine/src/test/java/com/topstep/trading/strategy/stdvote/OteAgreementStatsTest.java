package com.topstep.trading.strategy.stdvote;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V2 Agent 06: the agreement counters must count correctly per bucket,
 * keep their event rings bounded (memory is a budget), and serialize the
 * exact "oteStats" shape /api/chart exposes.
 */
class OteAgreementStatsTest {

    private static final Instant T0 = Instant.parse("2026-07-09T14:00:00Z");

    @Test
    void bucketsIncrementIndependently() {
        OteAgreementStats stats = OteAgreementStats.forSymbol("TEST-BUCKETS");
        stats.recordMachineEmittedChartAgreed(T0);
        stats.recordMachineEmittedChartAgreed(T0.plusSeconds(60));
        stats.recordMachineEmittedChartDisagreed(T0.plusSeconds(120));
        stats.recordChartReactedMachineSilent(T0.plusSeconds(180));
        stats.recordChartReactedMachineSilent(T0.plusSeconds(240));
        stats.recordChartReactedMachineSilent(T0.plusSeconds(300));

        assertEquals(2, stats.agreed());
        assertEquals(1, stats.disagreed());
        assertEquals(3, stats.chartOnly());
        assertEquals("oteAgree=2 oteDisagree=1 chartOnly=3", stats.rollup());
    }

    @Test
    void ringsAreBoundedAtCap() {
        OteAgreementStats stats = OteAgreementStats.forSymbol("TEST-RING");
        for (int i = 0; i < OteAgreementStats.RING_CAP * 3; i++) {
            stats.recordChartReactedMachineSilent(T0.plusSeconds(i));
        }
        Map<String, Object> api = stats.toApiMap();
        @SuppressWarnings("unchecked")
        Map<String, Object> events = (Map<String, Object>) api.get("lastEvents");
        @SuppressWarnings("unchecked")
        List<String> chartOnly = (List<String>) events.get("chartOnly");
        assertEquals(OteAgreementStats.RING_CAP, chartOnly.size(),
                "ring must never exceed its cap");
        // Ring keeps the MOST RECENT events.
        assertEquals(T0.plusSeconds(OteAgreementStats.RING_CAP * 3 - 1).toString(),
                chartOnly.get(chartOnly.size() - 1));
        // The count is NOT capped — only the event ring is.
        assertEquals(OteAgreementStats.RING_CAP * 3L, stats.chartOnly());
    }

    @Test
    void apiMapCarriesTheExactContractShape() {
        OteAgreementStats stats = OteAgreementStats.forSymbol("TEST-SHAPE");
        stats.recordMachineEmittedChartAgreed(T0);
        Map<String, Object> api = stats.toApiMap();

        assertTrue(api.containsKey("machineEmitted_chartAgreed"));
        assertTrue(api.containsKey("machineEmitted_chartDisagreed"));
        assertTrue(api.containsKey("chartReacted_machineSilent"));
        assertTrue(api.containsKey("lastEvents"));
        assertEquals(1L, api.get("machineEmitted_chartAgreed"));
        assertEquals(0L, api.get("machineEmitted_chartDisagreed"));
    }

    @Test
    void registryIsPerSymbolAndStable() {
        OteAgreementStats a1 = OteAgreementStats.forSymbol("TEST-REG-A");
        OteAgreementStats a2 = OteAgreementStats.forSymbol("TEST-REG-A");
        OteAgreementStats b = OteAgreementStats.forSymbol("TEST-REG-B");
        assertSame(a1, a2, "same symbol -> same instance");
        assertNotSame(a1, b, "different symbols -> independent counters");
        a1.recordMachineEmittedChartAgreed(T0);
        assertEquals(0, b.agreed(), "counters must not bleed across symbols");
    }
}
