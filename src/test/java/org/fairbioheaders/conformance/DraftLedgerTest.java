/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.fairbioheaders.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Prints the FHT draft report and writes a receipt of what the draft lane actually ran, so the
 * draft job can assert counts instead of inferring them from a green build.
 *
 * <p>Rule V7 evidence, kept out of the released-contract counts: the receipt names the contract
 * commit and digest and the suite commit and manifest digest, so a reader of the artefact sees
 * exactly which unreleased revision the numbers describe.
 */
@Tag("draft")
class DraftLedgerTest {

    @Test
    void reportAndReceipt() {
        DraftHarness harness = new DraftHarness();
        if (!harness.available()) {
            write(harness, 0, 0, harness.drift() == null
                ? "no FHT-Specification checkout" : "drift: " + harness.drift());
            return;
        }
        List<DraftHarness.Outcome> outcomes = harness.vectors().stream()
            .map(harness::run)
            .toList();
        String report = DraftHarness.report(outcomes, harness);
        System.out.println(report);
        long ran = outcomes.stream().filter(DraftHarness.Outcome::ran).count();
        long passed = outcomes.stream().filter(DraftHarness.Outcome::passed).count();
        write(harness, (int) ran, (int) passed, "");
        assertEquals(harness.manifestVectorCount(), outcomes.size(),
            "every manifest vector is enumerated");
        assertEquals(ran, passed, () -> "every draft vector must pass or the run must say why:\n"
            + report);
        assertTrue(ran > 0, "a draft job with zero vectors ran is a missing fixture, not a pass");
    }

    private static void write(DraftHarness harness, int ran, int passed, String note) {
        Path dir = Path.of(System.getProperty("fh.report.dir", "target"));
        String text = "suite=fht-draft (rule V7, unreleased development contract)\n"
            + "contractCommit=" + harness.pinnedCommit() + '\n'
            + "contractSha256=" + harness.pinnedSha256() + '\n'
            + "suiteCommit=" + harness.suiteCommit() + '\n'
            + "suiteManifestSha256=" + harness.suiteManifestSha256() + '\n'
            + "citation=" + harness.citation() + '\n'
            + "vectorsInManifest=" + harness.manifestVectorCount() + '\n'
            + "vectorsRan=" + ran + '\n'
            + "vectorsPassed=" + passed + '\n'
            + "note=" + note.replace('\n', ' ') + '\n';
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("fht-draft-vectors.properties"), text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
