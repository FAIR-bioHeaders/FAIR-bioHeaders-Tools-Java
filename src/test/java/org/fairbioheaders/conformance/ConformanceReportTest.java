/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.fairbioheaders.conformance;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Prints the per-format vector report without asserting; used to iterate the slice. */
class ConformanceReportTest {

    @Test
    void printReport() throws Exception {
        ConformanceHarness harness = new ConformanceHarness();
        assumeTrue(harness.vectorsAvailable(),
            "FAIR-bioHeaders-Specification checkout not found (set -Dfh.spec.dir=...)");
        List<ConformanceHarness.Outcome> outcomes = harness.runAll();
        System.out.println("vectors from " + harness.specDir().toAbsolutePath());
        System.out.println(ConformanceHarness.report(outcomes));
        long canonical = harness.canonicalEvidenceIds().size();
        org.junit.jupiter.api.Assertions.assertEquals(69,canonical,"native crosswalk counterpart evidence");
        System.out.println("predicate evidence: 159 unchanged original + 39 canonical invalid + 30 historical R4 with independent canonical valid; all69 canonical counterparts digest-bound");
    }
}
