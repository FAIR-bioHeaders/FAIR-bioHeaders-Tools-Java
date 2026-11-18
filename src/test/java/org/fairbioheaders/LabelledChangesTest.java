/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.fairbioheaders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** The labelled-change mechanism of the default-behavior golden applies exactly what it says. */
class LabelledChangesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ObjectNode golden() {
        return (ObjectNode) JSON.readTree("{\"kinds\":[{\"name\":\"a.fa\",\"kind\":\"FASTA\"},"
            + "{\"name\":\"x.gff\",\"kind\":\"FASTA\"}],\"configuration\":\"c\"}");
    }

    private static JsonNode changes(String from, String reason) {
        return JSON.readTree("{\"changes\":[{\"id\":\"gff\",\"reason\":\"" + reason + "\","
            + "\"section\":\"kinds\",\"match\":{\"name\":\"x.gff\"},"
            + "\"from\":{\"name\":\"x.gff\",\"kind\":\"" + from + "\"},"
            + "\"to\":{\"name\":\"x.gff\",\"kind\":\"GFF3\"}}]}");
    }

    @Test
    void aMatchingChangeReplacesTheOneEntryItNames() {
        ObjectNode golden = golden();
        List<String> problems = new ArrayList<>();
        assertEquals(1, LabelledChanges.apply(golden, changes("FASTA", "match the toolkit"), problems));
        assertTrue(problems.isEmpty(), problems.toString());
        assertEquals("GFF3", golden.get("kinds").get(1).get("kind").asString());
        assertEquals("FASTA", golden.get("kinds").get(0).get("kind").asString());
    }

    @Test
    void aStaleChangeFailsInsteadOfDoingNothing() {
        ObjectNode golden = golden();
        List<String> problems = new ArrayList<>();
        assertEquals(0, LabelledChanges.apply(golden, changes("GFA", "match the toolkit"), problems));
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("stale"), problems.toString());
        assertEquals("FASTA", golden.get("kinds").get(1).get("kind").asString());
    }

    @Test
    void aChangeWithoutAReasonIsRefused() {
        List<String> problems = new ArrayList<>();
        assertEquals(0, LabelledChanges.apply(golden(), changes("FASTA", ""), problems));
        assertTrue(problems.get(0).contains("no reason"), problems.toString());
    }

    @Test
    void theCommittedChangeListIsWellFormed() throws Exception {
        try (var in = LabelledChangesTest.class.getResourceAsStream("/golden/labelled-changes.json")) {
            JsonNode list = JSON.readTree(in);
            for (JsonNode change : list.get("changes")) {
                assertTrue(!change.path("id").asString("").isBlank(), change.toString());
                assertTrue(!change.path("reason").asString("").isBlank(), change.toString());
                assertTrue(change.has("from") && change.has("to"), change.toString());
            }
        }
    }
}
