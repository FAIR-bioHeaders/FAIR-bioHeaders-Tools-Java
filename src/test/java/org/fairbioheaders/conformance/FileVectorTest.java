/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.fairbioheaders.conformance;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * One dynamic test per shared conformance vector of the embedded-header file formats
 * ({@code fasta}, {@code gfa}, {@code fhp-fasta}, {@code fhgff3}) from
 * FAIR-bioHeaders-Specification {@code conformance/manifest.json}.
 *
 * <p>The vectors are the family's language-neutral acceptance suite; this library
 * claims nothing beyond what runs here. The {@code microdata} and {@code jsonld}
 * vectors are not implemented by this slice and are not run or claimed
 * (docs/STATUS.md). Without a specification checkout every test is aborted
 * (reported as skipped), never as passing.
 */
class FileVectorTest {

    @TestFactory
    List<DynamicTest> fileVectors() throws Exception {
        ConformanceHarness harness = new ConformanceHarness();
        List<DynamicTest> tests = new ArrayList<>();
        if (!harness.vectorsAvailable()) {
            tests.add(DynamicTest.dynamicTest("conformance-vectors-unavailable", () ->
                assumeTrue(false,
                    "FAIR-bioHeaders-Specification checkout not found (set -Dfh.spec.dir=...)")));
            return tests;
        }
        for (var vector : harness.fileVectors()) {
            String id = vector.get("id").asString();
            tests.add(DynamicTest.dynamicTest(id, () -> {
                ConformanceHarness.Outcome outcome = harness.run(vector);
                assertTrue(outcome.ran(), "vector " + outcome.id() + " should be in scope");
                assertTrue(outcome.passed(), "vector " + outcome.id() + " failed: " + outcome.detail());
            }));
        }
        return tests;
    }
}
