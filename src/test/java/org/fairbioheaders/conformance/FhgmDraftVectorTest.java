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

/**
 * One dynamic test per vector of the FHGM-Specification draft suite, read through the rule V7 lane
 * by the native model-level checker.
 *
 * <p>Tagged {@code draft}: it runs in the labelled {@code draft-vectors} job and is excluded from
 * the released-contract acceptance job, so draft counts never enter the 291. See {@link FhgmHarness}
 * for what each vector must satisfy, and docs/CAPABILITY-MATRIX.md for what FHGM is (an unreleased
 * draft of records carried inside an FHGFF3 header - not a supported type).
 */
@Tag("draft")
class FhgmDraftVectorTest {

    @TestFactory
    List<DynamicTest> fhgmDraftVectors() {
        FhgmHarness harness = new FhgmHarness();
        List<DynamicTest> tests = new ArrayList<>();
        if (!harness.available()) {
            String reason = harness.drift() != null
                ? "pinned FHGM draft bytes or suite manifest differ from the checkout: "
                    + harness.drift()
                : "FHGM-Specification checkout not found (set -Dfh.fhgm.dir=...)";
            tests.add(DynamicTest.dynamicTest("fhgm-draft-vectors-unavailable",
                () -> assumeTrue(false, reason)));
            return tests;
        }
        for (var vector : harness.vectors()) {
            String id = vector.get("id").asString();
            tests.add(DynamicTest.dynamicTest(id, () -> {
                FhgmHarness.Outcome outcome = harness.run(vector);
                assertTrue(outcome.ran(), "vector " + outcome.id() + " should be in scope");
                assertTrue(outcome.passed(),
                    "FHGM draft vector " + outcome.id() + " failed: " + outcome.detail());
            }));
        }
        return tests;
    }
}
