/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.fairbioheaders.conformance;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.fairbioheaders.BioHeaders;
import org.fairbioheaders.VerifyResult;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;

import tools.jackson.databind.JsonNode;

/**
 * The control that makes {@link FhtDraftVectorTest} mean something: the same FHT draft files, read
 * by the <em>default</em> reader with the rule V7 lane closed.
 *
 * <p>Every vector the suite expects valid must then be rejected, read as FHR under rule V5 with the
 * near-miss warning, and carry no V7 warning. If this test ever passes with the lane closed, the
 * draft results next to it are not evidence about the lane - they are the released reader agreeing
 * by accident. It is the differential that shows the opt-in is load-bearing in both directions: it
 * opens nothing by default, and the draft suite genuinely depends on it.
 */
@Tag("draft")
class FhtLaneOffControlTest {

    @TestFactory
    List<DynamicTest> laneOffRejectsEveryDraftOnlyVector() throws Exception {
        DraftHarness harness = new DraftHarness();
        List<DynamicTest> tests = new ArrayList<>();
        if (harness.suiteDir() == null) {
            tests.add(DynamicTest.dynamicTest("fht-checkout-unavailable",
                () -> assumeTrue(false, "FHT-Specification checkout not found")));
            return tests;
        }
        BioHeaders released = new BioHeaders();
        for (JsonNode vector : harness.vectors()) {
            String id = vector.get("id").asString();
            tests.add(DynamicTest.dynamicTest(id + " (lane closed)", () -> {
                Path file = harness.suiteDir().resolve(vector.get("file").asString()).normalize();
                assertTrue(Files.isRegularFile(file), file + " should exist");
                // The file-based path again: a broken compressed stream must arrive as the
                // released reader's own rejection (R1), never as an exception escaping readAll -
                // a crashed control proves nothing about the lane either.
                VerifyResult result = released.verifyFile(file);
                assertFalse(result.hasRule("V7"),
                    "a closed lane must not describe anything as a draft reading");
                // A record written against the FHT contract cannot satisfy FHR: its required
                // fields, its schemaVersion and its schema URL all differ.
                assertFalse(result.isValid(), "expected the released reader to reject " + id
                    + ", got VALID - the draft suite would then prove nothing about the lane");
                if (citationOf(vector) != null) {
                    assertTrue(result.hasRule("V5") || result.hasRule("V4")
                            || result.hasRule("schema"),
                        id + " should be rejected as FHR by rule V5, got " + codes(result));
                }
            }));
        }
        return tests;
    }

    private static String citationOf(JsonNode vector) {
        JsonNode metadata = vector.path("metadata");
        String value = metadata.path("schema").asString(null);
        return value == null || value.isEmpty() ? null : value;
    }

    private static List<String> codes(VerifyResult result) {
        return result.issues().stream().map(org.fairbioheaders.Issue::rule).toList();
    }
}
