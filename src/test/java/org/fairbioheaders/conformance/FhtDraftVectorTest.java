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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * One dynamic test per vector of the FHT-Specification draft suite, read through the rule V7 lane
 * against the pinned development contract.
 *
 * <p>Tagged {@code draft}: the released-contract acceptance job excludes the tag and this runs in
 * its own labelled job, so draft counts never mix with the 291 released vectors. See
 * {@link DraftHarness} for what each vector must satisfy and docs/CAPABILITY-MATRIX.md for what
 * FHT is (an unreleased draft — nothing here is a support or release claim).
 */
@Execution(ExecutionMode.SAME_THREAD)
@Tag("draft")
class FhtDraftVectorTest {

    @TestFactory
    List<DynamicTest> fhtDraftVectors() {
        DraftHarness harness = new DraftHarness();
        List<DynamicTest> tests = new ArrayList<>();
        if (!harness.available()) {
            String reason = harness.drift() != null
                ? "pinned FHT draft bytes or suite manifest differ from the checkout: "
                    + harness.drift()
                : "FHT-Specification checkout not found (set -Dfh.fht.dir=...)";
            tests.add(DynamicTest.dynamicTest("fht-draft-vectors-unavailable",
                () -> assumeTrue(false, reason)));
            return tests;
        }
        for (var vector : harness.vectors()) {
            String id = vector.get("id").asString();
            tests.add(DynamicTest.dynamicTest(id, () -> {
                DraftHarness.Outcome outcome = harness.run(vector);
                assertTrue(outcome.ran(), "vector " + outcome.id() + " should be in scope");
                assertTrue(outcome.passed(),
                    "FHT draft vector " + outcome.id() + " failed: " + outcome.detail());
            }));
        }
        return tests;
    }
}
