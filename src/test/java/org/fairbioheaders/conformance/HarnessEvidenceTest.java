/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.fairbioheaders.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

/**
 * The harness checks that an invalid vector fails for the rule the manifest names, and that a
 * valid vector reads the metadata the manifest records. These are the tables behind that.
 */
class HarnessEvidenceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void byteLevelRulesNeedTheirOwnLabel() {
        for (String rule : new String[] {"R2", "R3", "R5", "R6", "R7", "R8", "R10", "R12", "R13", "V4"}) {
            assertEquals(Set.of(rule), ConformanceHarness.evidenceFor(rule), rule);
        }
    }

    @Test
    void contractRulesAreEvidencedByASchemaError() {
        for (String rule : new String[] {"P1", "P2", "G2", "G3", "R15", "V1", "V2", "V3", "V5", "V6"}) {
            assertTrue(ConformanceHarness.evidenceFor(rule).contains("schema"), rule);
        }
        assertFalse(ConformanceHarness.evidenceFor("R8").contains("schema"));
        assertFalse(ConformanceHarness.evidenceFor("G1").contains("schema"));
    }

    @Test
    void hashingRulesShowAsAMismatchOrABadValue() {
        assertEquals(Set.of("R1", "R3"), ConformanceHarness.evidenceFor("R1"));
        assertEquals(Set.of("R4", "R3", "R5"), ConformanceHarness.evidenceFor("R4"));
        assertEquals(Set.of("G1", "R10"), ConformanceHarness.evidenceFor("G1"));
    }

    @Test
    void metadataComparesNumbersByValueAndEverythingElseExactly() {
        assertTrue(ConformanceHarness.sameValue(JSON.readTree("1"), JSON.readTree("1.0")));
        assertFalse(ConformanceHarness.sameValue(JSON.readTree("1"), JSON.readTree("\"1\"")));
        assertTrue(ConformanceHarness.sameValue(JSON.readTree("{\"a\":[1,\"x\"]}"),
            JSON.readTree("{\"a\":[1.0,\"x\"]}")));
        assertFalse(ConformanceHarness.sameValue(JSON.readTree("{\"a\":[1]}"),
            JSON.readTree("{\"a\":[1,2]}")));
        assertFalse(ConformanceHarness.sameValue(JSON.readTree("{\"a\":1}"),
            JSON.readTree("{\"a\":1,\"b\":2}")));
    }
}
