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
 * Prints both rule V7 draft reports and writes a receipt of what the draft lane actually ran, so the
 * draft job can assert counts instead of inferring them from a green build.
 *
 * <p>Draft evidence, labelled as such: the receipt names each suite's contract commit and digest and
 * suite commit and manifest digest, so a reader of the artefact sees exactly which unreleased
 * revisions the numbers describe. Nothing here is a support or release claim.
 */
@Tag("draft")
class DraftLedgerTest {

    @Test
    void fhtReportAndReceipt() {
        DraftHarness harness = new DraftHarness();
        if (!harness.available()) {
            write("fht-draft-vectors.properties", fht(harness, 0, 0,
                harness.drift() == null ? "no FHT-Specification checkout" : "drift: " + harness.drift()));
            return;
        }
        List<DraftHarness.Outcome> outcomes = harness.vectors().stream().map(harness::run).toList();
        String report = DraftHarness.report(outcomes, harness);
        System.out.println(report);
        long ran = outcomes.stream().filter(DraftHarness.Outcome::ran).count();
        long passed = outcomes.stream().filter(DraftHarness.Outcome::passed).count();
        write("fht-draft-vectors.properties", fht(harness, (int) ran, (int) passed, ""));
        assertEquals(harness.manifestVectorCount(), outcomes.size(),
            "every manifest vector is enumerated");
        assertEquals(ran, passed, () -> "every draft vector must pass or the run must say why:\n"
            + report);
        assertTrue(ran > 0, "a draft job with zero vectors ran is a missing fixture, not a pass");
    }

    private static String fht(DraftHarness harness, int ran, int passed, String note) {
        return "suite=fht-draft (rule V7, unreleased development contract)\n"
            + "contractCommit=" + harness.pinnedCommit() + '\n'
            + "contractSha256=" + harness.pinnedSha256() + '\n'
            + "suiteCommit=" + harness.suiteCommit() + '\n'
            + "suiteManifestSha256=" + harness.suiteManifestSha256() + '\n'
            + "citation=" + harness.citation() + '\n'
            + "vectorsInManifest=" + harness.manifestVectorCount() + '\n'
            + "vectorsRan=" + ran + '\n'
            + "vectorsPassed=" + passed + '\n'
            + "note=" + note.replace('\n', ' ') + '\n';
    }

    @Test
    void fhgmReportAndReceipt() {
        FhgmHarness harness = new FhgmHarness();
        if (!harness.available()) {
            write("fhgm-draft-vectors.properties", fhgm(harness, 0, 0,
                harness.drift() == null ? "no FHGM-Specification checkout" : "drift: " + harness.drift()));
            return;
        }
        List<FhgmHarness.Outcome> outcomes = harness.vectors().stream().map(harness::run).toList();
        String report = FhgmHarness.report(outcomes, harness);
        System.out.println(report);
        long ran = outcomes.stream().filter(FhgmHarness.Outcome::ran).count();
        long passed = outcomes.stream().filter(FhgmHarness.Outcome::passed).count();
        write("fhgm-draft-vectors.properties", fhgm(harness, (int) ran, (int) passed, ""));
        assertEquals(harness.manifestVectorCount(), outcomes.size(),
            "every manifest vector is enumerated");
        assertEquals(ran, passed, () -> "every FHGM draft vector must pass or say why:\n" + report);
        assertTrue(ran > 0, "a draft job with zero vectors ran is a missing fixture, not a pass");
    }

    private static String fhgm(FhgmHarness harness, int ran, int passed, String note) {
        return "suite=fhgm-draft (rule V7, unreleased development contracts)\n"
            + "recordContractCommit=" + harness.pinnedCommit() + '\n'
            + "recordContractSha256=" + harness.pinnedSha256() + '\n'
            + "suiteCommit=" + harness.suiteCommit() + '\n'
            + "suiteManifestSha256=" + harness.suiteManifestSha256() + '\n'
            + "gff3Layer=skipped (feature-line validation belongs to gff3-validator;"
            + " the suite records no rule that depends on it)\n"
            + "vectorsInManifest=" + harness.manifestVectorCount() + '\n'
            + "vectorsRan=" + ran + '\n'
            + "vectorsPassed=" + passed + '\n'
            + "note=" + note.replace('\n', ' ') + '\n';
    }

    private static void write(String name, String text) {
        Path dir = Path.of(System.getProperty("fh.report.dir", "target"));
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(name), text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
