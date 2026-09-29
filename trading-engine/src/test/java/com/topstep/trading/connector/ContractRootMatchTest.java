package com.topstep.trading.connector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The root parsed out of a ProjectX contract id is what binds a symbol to a
 * contract, so it has to be exact.
 *
 * <p>Field incident (LIVE 2026-09-29, Main 78699f1): the bulk contract
 * discovery searches with an empty searchText and the gateway answers with a
 * truncated page of ~20 contracts ordered by root (BP6 ... M6E). MGC, MNQ and
 * MES all sort after that cut-off, so all three fell through to the calendar
 * guesser, which put MGC on {@code CON.F.US.MGC.V26} (October, 1,257
 * contracts that day) while the broker's active contract was
 * {@code CON.F.US.MGC.Z26} (December, 135,803 contracts). Ported from #151
 * and extended for AGENT-05.12.
 */
class ContractRootMatchTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static JsonNode json(String s) throws Exception {
        return M.readTree(s);
    }

    @Test
    void parsesTheRootFromAWellFormedContractId() {
        assertEquals("MGC", TopstepConnector.rootOf("CON.F.US.MGC.Z26"));
        assertEquals("MNQ", TopstepConnector.rootOf("CON.F.US.MNQ.U26"));
        assertEquals("ENQ", TopstepConnector.rootOf("CON.F.US.ENQ.U26"));
    }

    @Test
    void rootMatchIsExactSoNeighbouringRootsCannotBind() {
        assertNotEquals("MGC", TopstepConnector.rootOf("CON.F.US.GCE.Z26"));
        assertNotEquals("MES", TopstepConnector.rootOf("CON.F.US.M6E.U26"));
        assertNotEquals("MNQ", TopstepConnector.rootOf("CON.F.US.M2K.U26"));
    }

    @Test
    void malformedIdsYieldNoRootRatherThanThrowing() {
        assertEquals("", TopstepConnector.rootOf(null));
        assertEquals("", TopstepConnector.rootOf(""));
        assertEquals("", TopstepConnector.rootOf("MGC"));
        assertEquals("", TopstepConnector.rootOf("CON.F.US.MGC"));
    }

    @Test
    void rootComparisonIsCaseInsensitiveOnTheContractSide() {
        assertEquals("MGC", TopstepConnector.rootOf("con.f.us.mgc.z26"));
    }

    @Test
    void projectXRootMapsTheSymbolsWhoseRootDiffers() {
        assertEquals("MGC", TopstepConnector.projectXRoot("MGC"));
        assertEquals("MNQ", TopstepConnector.projectXRoot("mnq"));
        assertEquals("MES", TopstepConnector.projectXRoot("MES"));
        assertEquals("GCE", TopstepConnector.projectXRoot("GC"));
        assertEquals("EP", TopstepConnector.projectXRoot("ES"));
        assertEquals("ENQ", TopstepConnector.projectXRoot("NQ"));
    }

    @Test
    void mgcBindsToTheActiveDecemberContractFromTheLiveResponse() throws Exception {
        // The exact response POST /api/Contract/search {"searchText":"MGC","live":false} returned.
        TopstepConnector.ContractBinding b = TopstepConnector.pickContract("MGC", json(
            "{\"contracts\":[{\"id\":\"CON.F.US.MGC.Z26\",\"name\":\"MGCZ6\",\"activeContract\":true,"
                + "\"description\":\"Micro Gold: December 2026\"}],\"success\":true,\"errorCode\":0}"));
        assertNotNull(b);
        assertEquals("CON.F.US.MGC.Z26", b.contractId);
        assertEquals(TopstepConnector.BindingSource.BROKER_SEARCH, b.source);
        assertTrue(b.activeContract);
        assertFalse(b.isGuess());
    }

    @Test
    void activeMgcWinsOverGceAndOverANonActiveMgcMonth() throws Exception {
        TopstepConnector.ContractBinding b = TopstepConnector.pickContract("MGC", json(
            "{\"contracts\":["
                + "{\"id\":\"CON.F.US.GCE.Z26\",\"name\":\"GCZ6\",\"activeContract\":true},"
                + "{\"id\":\"CON.F.US.MGC.V26\",\"name\":\"MGCV6\",\"activeContract\":false},"
                + "{\"id\":\"CON.F.US.MGC.Z26\",\"name\":\"MGCZ6\",\"activeContract\":true}"
                + "]}"));
        assertNotNull(b);
        assertEquals("CON.F.US.MGC.Z26", b.contractId);
        assertTrue(b.activeContract);
    }

    @Test
    void mesNeverBindsToM6E() throws Exception {
        assertNull(TopstepConnector.pickContract("MES", json(
            "{\"contracts\":[{\"id\":\"CON.F.US.M6E.Z26\",\"name\":\"M6EZ6\",\"activeContract\":true}]}")));
        TopstepConnector.ContractBinding b = TopstepConnector.pickContract("MES", json(
            "{\"contracts\":[{\"id\":\"CON.F.US.M6E.Z26\",\"activeContract\":true},"
                + "{\"id\":\"CON.F.US.MES.Z26\",\"activeContract\":true}]}"));
        assertEquals("CON.F.US.MES.Z26", b.contractId);
    }

    @Test
    void mgcNeverBindsToGceAlone() throws Exception {
        assertNull(TopstepConnector.pickContract("MGC", json(
            "{\"contracts\":[{\"id\":\"CON.F.US.GCE.Z26\",\"activeContract\":true}]}")));
    }

    @Test
    void withoutAnActiveFlagTheFirstExactRootMatchIsUsed() throws Exception {
        TopstepConnector.ContractBinding b = TopstepConnector.pickContract("MNQ", json(
            "[{\"id\":\"CON.F.US.MNQ.Z26\"},{\"id\":\"CON.F.US.MNQ.H27\"}]"));
        assertEquals("CON.F.US.MNQ.Z26", b.contractId);
        assertFalse(b.activeContract);
        assertEquals(TopstepConnector.BindingSource.BROKER_SEARCH, b.source);
    }

    @Test
    void bindingLogLineFormat() {
        assertEquals("CONTRACT BINDING MGC -> CON.F.US.MGC.Z26 (source=BROKER_SEARCH activeContract=true)",
            new TopstepConnector.ContractBinding("MGC", "CON.F.US.MGC.Z26",
                TopstepConnector.BindingSource.BROKER_SEARCH, true).logLine());
        assertEquals("CONTRACT BINDING MGC -> CON.F.US.MGC.V26 (source=CALENDAR_GUESS)",
            new TopstepConnector.ContractBinding("MGC", "CON.F.US.MGC.V26",
                TopstepConnector.BindingSource.CALENDAR_GUESS, false).logLine());
    }
}
