/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.fairbioheaders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * Parent-link verification ({@link ParentLinks}): the three-way verdict, its per-entry
 * independence, the fact that it is a caller-supplied operation rather than a default, and the
 * guard on paths a suite hands over.
 *
 * <p>Every fixture is sealed (its own checksum line matches its own bytes), so a failure here is
 * about a link and never about file integrity.
 */
class ParentLinksTest {

    /** The parent is an FHR FASTA: the file a link's checksum has to match. */
    private static byte[] parent() {
        return Fixtures.fhrFasta(">seq1\nACGT\n");
    }

    /**
     * A child citing {@code first} as its annotated genome. FHR itself has no derivedFrom field, so
     * the child is an FHGFF3 annotation - the released type whose contract actually states parent
     * links. Its second link names a parent no test supplies.
     */
    private static byte[] child(String first) {
        return Fixtures.fhgff3LinkedTo(first);
    }

    private static ParentLinks.ParentSource supplies(String stated, byte[] file) {
        return (checksum, headerType, index) -> checksum.equals(stated)
            ? Optional.of(new ParentLinks.Parent(Kind.FASTA, file))
            : Optional.empty();
    }

    @Test
    void theFixturesSealThemselves() {
        // The control on the control: each fixture's own root checksum line must match its own
        // bytes, so nothing below can pass or fail on integrity instead of on a link.
        for (byte[] fixture : List.of(parent(), child(Fixtures.ABSENT))) {
            Kind kind = fixture == null ? Kind.FASTA
                : (Fixtures.utf8(fixture).startsWith("##gff-version") ? Kind.GFF3 : Kind.FASTA);
            VerifyResult own = new BioHeaders().verify(fixture, kind);
            assertFalse(own.hasRule("R2"), own.issues().toString());
            assertFalse(own.hasRule("R3"), own.issues().toString());
            assertEquals(Fixtures.statedChecksum(fixture), own.computedChecksum());
            assertTrue(own.isValid(), own.errors().toString());
        }
    }

    @Test
    void aSuppliedParentThatHashesToTheStatedValueIsCheckedPass() {
        byte[] file = parent();
        String truth = Fixtures.statedChecksum(file);
        VerifyResult result = new BioHeaders().verify(child(truth), Kind.GFF3, supplies(truth, file));
        List<ParentLinks.Verdict> verdicts = result.parentLinkVerdicts();
        assertEquals(2, verdicts.size(), "every derivedFrom entry gets a verdict");
        assertEquals(ParentLinks.State.CHECKED_PASS, verdicts.get(0).state(), verdicts.toString());
        assertEquals(truth, verdicts.get(0).recomputedChecksum());
        assertEquals(ParentLinks.State.UNCHECKED, verdicts.get(1).state(),
            "the second link's parent was not supplied");
        assertFalse(result.hasRule(ParentLinks.RULE), verdicts.toString());
        assertTrue(result.isValid(), result.errors().toString());
    }

    @Test
    void aSuppliedParentThatDiffersIsCheckedFailAndRejectsTheRecord() {
        byte[] file = parent();
        String truth = Fixtures.statedChecksum(file);
        // Same shape, different bytes: the only way to state a checksum that a supplied parent
        // contradicts.
        String perturbed = (truth.charAt(0) == 'B' ? "C" : "B") + truth.substring(1);
        VerifyResult result = new BioHeaders().verify(child(perturbed), Kind.GFF3,
            supplies(perturbed, file));
        List<ParentLinks.Verdict> verdicts = result.parentLinkVerdicts();
        assertEquals(ParentLinks.State.CHECKED_FAIL, verdicts.get(0).state(), verdicts.toString());
        assertEquals(truth, verdicts.get(0).recomputedChecksum(), "the report shows both values");
        assertTrue(result.hasRule(ParentLinks.RULE), result.issues().toString());
        assertFalse(result.isValid(), "a failing link is a rejection, not a note");
    }

    @Test
    void verdictsArePerEntrySoAPassingLinkDoesNotMaskAFailingOne() {
        byte[] file = parent();
        String truth = Fixtures.statedChecksum(file);
        String wrong = "B".repeat(43) + "=";
        VerifyResult failing = new BioHeaders().verify(child(wrong), Kind.GFF3, supplies(wrong, file));
        assertEquals(ParentLinks.State.CHECKED_FAIL, failing.parentLinkVerdicts().get(0).state());
        assertEquals(ParentLinks.State.UNCHECKED, failing.parentLinkVerdicts().get(1).state(),
            "an un-supplied link is never silently passed");

        VerifyResult passing = new BioHeaders().verify(child(truth), Kind.GFF3, supplies(truth, file));
        assertEquals(ParentLinks.State.CHECKED_PASS, passing.parentLinkVerdicts().get(0).state());
        assertEquals(ParentLinks.State.UNCHECKED, passing.parentLinkVerdicts().get(1).state());
    }

    @Test
    void anAbsentParentIsUncheckedAndChangesNoVerdict() {
        byte[] file = parent();
        String truth = Fixtures.statedChecksum(file);
        VerifyResult result = new BioHeaders().verify(child(truth), Kind.GFF3,
            (stated, headerType, index) -> Optional.empty());
        assertTrue(result.parentLinkVerdicts().stream()
            .allMatch(v -> v.state() == ParentLinks.State.UNCHECKED),
            result.parentLinkVerdicts().toString());
        assertTrue(result.isValid(), result.errors().toString());
    }

    @Test
    void verifyWithoutAParentSourceNeverConsultsParents() {
        VerifyResult plain = new BioHeaders().verify(child(Fixtures.ABSENT), Kind.GFF3);
        assertTrue(plain.parentLinkVerdicts().isEmpty(), "the default reads one file only");
        assertFalse(plain.hasRule(ParentLinks.RULE));
    }

    @Test
    void aLinkWithoutAChecksumIsUncheckedNotFailed() {
        byte[] noChecksum = Fixtures.fhgff3("chr1\tfixture\tgene\t1\t10\t.\t+\t.\tID=gene1\n",
            "derivedFrom:",
            "- headerType: FHR",
            "  relationship: annotates");
        VerifyResult result = new BioHeaders().verify(noChecksum, Kind.GFF3,
            (stated, headerType, index) -> Optional.empty());
        assertEquals(1, result.parentLinkVerdicts().size(), result.parentLinkVerdicts().toString());
        assertEquals(ParentLinks.State.UNCHECKED, result.parentLinkVerdicts().get(0).state(),
            "a missing statement is a contract problem (the schema reports it), not a bad link");
        assertTrue(result.hasRule("schema"), result.issues().toString());
    }

    @Test
    void aParentPathThatEscapesItsSuiteDirectoryIsRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> ParentLinks.relativeTo(Path.of("target"),
                Map.of(Fixtures.ABSENT, "../outside.fasta")).parentBytes(Fixtures.ABSENT, "FHR", 0));
        assertTrue(e.getMessage().contains("escapes"), e.getMessage());
    }

    @Test
    void aParentThatIsNotAHeadedFileFailsLoudlyInsteadOfReportingUnchecked() {
        byte[] junk = ">seq1\nACGT\n".getBytes(StandardCharsets.UTF_8);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> new BioHeaders().verify(child(Fixtures.ABSENT), Kind.GFF3,
                (stated, headerType, index) -> Optional.of(new ParentLinks.Parent(Kind.FASTA, junk))));
        assertTrue(e.getMessage().contains("not a readable headed"), e.getMessage());
    }

    @Test
    void aSymbolicLinkCannotLeadAParentOutOfItsSuite(@org.junit.jupiter.api.io.TempDir Path dir)
            throws Exception {
        Path suite = java.nio.file.Files.createDirectories(dir.resolve("suite"));
        Path outside = java.nio.file.Files.write(dir.resolve("outside.fasta"), parent());
        String stated = new BioHeaders().verify(parent(), Kind.FASTA).computedChecksum();
        try {
            java.nio.file.Files.createSymbolicLink(suite.resolve("link.fasta"), outside);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "no symbolic links here: " + e);
        }
        ParentLinks.ParentSource source = ParentLinks.relativeTo(suite, Map.of(stated, "link.fasta"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> source.parentBytes(stated, "FHR", 0));
        assertTrue(e.getMessage().contains("symbolic link"), e.getMessage());

        java.nio.file.Files.write(suite.resolve("inside.fasta"), parent());
        ParentLinks.ParentSource ok = ParentLinks.relativeTo(suite, Map.of(stated, "inside.fasta"));
        ParentLinks.Parent p = ok.parentBytes(stated, "FHR", 0).orElseThrow();
        assertEquals(stated, ParentLinks.checksumOf(p), "a path-backed parent is streamed and hashed");
    }
}
