package com.topstep.trading.connector;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The root parsed out of a ProjectX contract id is what binds a symbol to a
 * contract, so it has to be exact.
 *
 * <p>Field incident (2026-08-17, LIVE observation on PRAC): the bulk contract
 * discovery searches with an empty searchText and the gateway answers with a
 * truncated page of 20 contracts ordered by root. MGC, MNQ and MES all sort
 * after that cut-off, so all three missed the cache and fell through to the
 * calendar-based guesser. It put MGC on {@code CON.F.US.MGC.Q26} — August gold,
 * already rolled off — while the active contract was {@code CON.F.US.MGC.Z26}.
 * The symptom was a 5-bar backfill against a dead contract.
 */
class ContractRootMatchTest {

    @Test
    void parsesTheRootFromAWellFormedContractId() {
        assertEquals("MGC", TopstepConnector.rootOf("CON.F.US.MGC.Z26"));
        assertEquals("MNQ", TopstepConnector.rootOf("CON.F.US.MNQ.U26"));
        assertEquals("ENQ", TopstepConnector.rootOf("CON.F.US.ENQ.U26"));
    }

    @Test
    void rootMatchIsExactSoNeighbouringRootsCannotBind() {
        // The bug this guards: a prefix/contains match would let MGC bind to
        // full-size gold (GCE) or MES to M6E, both of which the truncated
        // discovery page actually contained.
        assertNotEquals("MGC", TopstepConnector.rootOf("CON.F.US.GCE.Z26"));
        assertNotEquals("MES", TopstepConnector.rootOf("CON.F.US.M6E.U26"));
        assertNotEquals("MNQ", TopstepConnector.rootOf("CON.F.US.M2K.U26"));
    }

    @Test
    void malformedIdsYieldNoRootRatherThanThrowing() {
        // A resolver that throws here would abort symbol subscription on a
        // single odd row in the search response.
        assertEquals("", TopstepConnector.rootOf(null));
        assertEquals("", TopstepConnector.rootOf(""));
        assertEquals("", TopstepConnector.rootOf("MGC"));
        assertEquals("", TopstepConnector.rootOf("CON.F.US.MGC"));
    }

    @Test
    void rootComparisonIsCaseInsensitiveOnTheContractSide() {
        assertEquals("MGC", TopstepConnector.rootOf("con.f.us.mgc.z26"));
    }
}
