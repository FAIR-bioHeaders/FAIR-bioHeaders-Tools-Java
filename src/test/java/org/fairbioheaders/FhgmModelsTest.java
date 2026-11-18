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

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The FHGM draft's model-level rules, on synthetic sealed fixtures.
 *
 * <p>These are the unit-level counterparts of the FHGM-Specification vectors: same rules, but built
 * so that each case changes exactly one thing, and so that the port is testable without a checkout of
 * another repository. Tagged {@code draft}: it reads pinned development contracts and belongs to the
 * labelled draft job, never to the released-contract acceptance count.
 */
@Tag("draft")
class FhgmModelsTest {

    private static final Options LANE = Options.builder().enableDraft("FHGM").build();

    private static FhgmModels models() {
        return new FhgmModels(LANE);
    }

    private static List<String> rules(FhgmModels.Report report) {
        return report.issues().stream().map(Issue::rule).toList();
    }

    @Test
    void theLaneMustBeOpenBeforeAnyDraftByteIsRead() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> new FhgmModels(Options.defaults()));
        assertTrue(e.getMessage().contains("not opted"), e.getMessage());
        // And with the lane open the registry's own pin is what the instance reports.
        FhgmModels opened = models();
        assertEquals("x-fhgm-models", opened.modelsKey());
        assertEquals("06176262f9824f26d974a15d8feeebde1fbeb0a8", opened.pinnedCommit());
        assertEquals("b7075e5872e0a5ffce5ae61c590ce182f1f219fa269413cad3b1a966753cfeae",
            opened.pinnedSha256());
    }

    @Test
    void aWellFormedRecordPassesAndSaysItWasReadFromADraft() {
        byte[] file = Fixtures.fhgm(Fixtures.record("model-a", "1", "toy-a"),
            Fixtures.TOY_FEATURES);
        FhgmModels.Report report = models().check(file, List.of());
        assertTrue(report.passed(), report.issues() + " / " + report.carrier().issues());
        assertTrue(report.issues().isEmpty(), report.issues().toString());
        assertEquals(List.of("model-a"), report.modelIds());
        assertEquals(1, report.draftWarnings().size(), "one V7 warning per record read");
        Issue warning = report.draftWarnings().get(0);
        assertEquals("V7", warning.rule());
        assertTrue(warning.message().contains("commit 06176262f9824f26d974a15d8feeebde1fbeb0a8"),
            warning.message());
        assertTrue(warning.message().contains("sha256 b7075e58"), warning.message());
        assertTrue(warning.message().contains("not a released schema"), warning.message());
        assertTrue(warning.message().contains("x-fhgm-models"), warning.message());
    }

    @Test
    void k1RequiresTheTransportOverlayTheRecordsKeyAndTheReleasedIdentity() {
        // No records at all: the overlay's required x-fhgm-models key is missing.
        byte[] noRecords = Fixtures.sealed(Kind.GFF3, """
            schema: https://w3id.org/fair-bioheaders/fhgff3/v0.5.0
            schemaVersion: '1.1'
            annotation: fixture-annotation-without-records
            taxon:
              name: Synthetic example
            version: '1'
            metadataAuthor:
            - name: Synthetic fixture author
            dateCreated: 2026-01-01
            genomeReference:
              accession: SYNTHETIC_ASSEMBLY.1
              seqcol_id: J3Hvvqu0USaOuxA-3wRFyZ3YPdnSEDCp
            """, Fixtures.TOY_FEATURES);
        FhgmModels.Report report = models().check(noRecords, List.of());
        assertEquals(FhgmModels.State.REJECT, report.state());
        assertEquals("K1", rules(report).get(0), report.issues().toString());

        // FHGFF3 1.0 cannot carry the extension at all, and that is the base schema's business (B1),
        // not a model verdict: the released contract rejects the unknown key.
        byte[] asOneOh = Fixtures.utf8(noRecords)
            .replace("fhgff3/v0.5.0", "fhgff3/v0.4.0").replace("schemaVersion: '1.1'",
                "schemaVersion: '1.0'")
            .replace("#~ checksum: " + Fixtures.statedChecksum(noRecords), "#~ checksum: "
                + Fixtures.ABSENT)
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        FhgmModels.Report old = models().check(asOneOh, List.of());
        assertFalse(old.passed(), old.issues().toString());
        assertEquals("B1", rules(old).get(0), old.issues().toString());
    }

    @Test
    void k2IsThePinnedRecordContractRequiringFourFieldsAndClosingEachObject() {
        FhgmModels checker = models();
        // A record with no reference at all.
        byte[] missingReference = Fixtures.fhgm("""
            - modelId: model-a
              modelVersion: '1'
              featureId: toy-a
            """, Fixtures.TOY_FEATURES);
        FhgmModels.Report r1 = checker.check(missingReference, List.of());
        assertEquals("K2", rules(r1).get(0), r1.issues().toString());
        assertTrue(combined(r1).contains("reference"), combined(r1));

        // An unknown key inside a record.
        byte[] unknownKey = Fixtures.fhgm(Fixtures.record("model-a", "1", "toy-a")
            + "  notAField: 1\n", Fixtures.TOY_FEATURES);
        FhgmModels.Report r2 = checker.check(unknownKey, List.of());
        assertEquals("K2", rules(r2).get(0), r2.issues().toString());
        assertTrue(combined(r2).contains("notAField"), combined(r2));

        // A version written as a number rather than an opaque string.
        byte[] numeric = Fixtures.fhgm("""
            - modelId: model-a
              modelVersion: 1
              featureId: toy-a
              reference:
                genomeReference:
                  accession: SYNTHETIC_ASSEMBLY.1
                  seqcol_id: J3Hvvqu0USaOuxA-3wRFyZ3YPdnSEDCp
            """, Fixtures.TOY_FEATURES);
        FhgmModels.Report r3 = checker.check(numeric, List.of());
        assertEquals("K2", rules(r3).get(0), r3.issues().toString());
    }

    @Test
    void k4RequiresExactlyOneLocalGeneRow() {
        FhgmModels checker = models();
        // No row carries that ID.
        assertEquals("K4", rules(checker.check(
            Fixtures.fhgm(Fixtures.record("model-a", "1", "toy-z"), Fixtures.TOY_FEATURES),
            List.of())).get(0));
        // A transcript row, not a gene row.
        assertEquals("K4", rules(checker.check(
            Fixtures.fhgm(Fixtures.record("model-a", "1", "toy-a-rna"), Fixtures.TOY_FEATURES),
            List.of())).get(0));
        // Two gene rows sharing one ID: the target is not selectable.
        String duplicated = Fixtures.TOY_FEATURES + "toy\tpilot\tgene\t90\t99\t.\t+\t.\tID=toy-a\n";
        assertEquals("K4", rules(checker.check(
            Fixtures.fhgm(Fixtures.record("model-a", "1", "toy-a"), duplicated), List.of())).get(0));
        // The SO accession for gene is accepted as the same class of row.
        String soTyped = "toy\tpilot\tSO:0000704\t10\t30\t.\t+\t.\tID=toy-so\n";
        assertTrue(checker.check(
            Fixtures.fhgm(Fixtures.record("model-so", "1", "toy-so"), soTyped), List.of())
            .passed(), combined(checker.check(
            Fixtures.fhgm(Fixtures.record("model-so", "1", "toy-so"), soTyped), List.of())));
    }

    @Test
    void k5RequiresOneComparableFormAndForbidsContradiction() {
        FhgmModels checker = models();
        // The carrier links an assembly accession + SeqCol digest; a record bound to a different
        // accession contradicts it.
        byte[] contradicting = Fixtures.fhgm("""
            - modelId: model-a
              modelVersion: '1'
              featureId: toy-a
              reference:
                genomeReference:
                  accession: OTHER_ASSEMBLY.9
                  seqcol_id: J3Hvvqu0USaOuxA-3wRFyZ3YPdnSEDCp
            """, Fixtures.TOY_FEATURES);
        FhgmModels.Report conflict = checker.check(contradicting, List.of());
        assertEquals("K5", rules(conflict).get(0), conflict.issues().toString());
        assertTrue(combined(conflict).contains("contradicts the file genome link"),
            combined(conflict));

        // A record bound only to an FHR parent checksum, in a file whose link is an accession: the
        // two halves share no comparable form, so the record cannot be located against the file.
        byte[] notComparable = Fixtures.fhgm(
            Fixtures.recordByParent("model-a", "1", "toy-a", Fixtures.ABSENT),
            Fixtures.TOY_FEATURES);
        FhgmModels.Report loose = checker.check(notComparable, List.of());
        assertEquals("K5", rules(loose).get(0), loose.issues().toString());
        assertTrue(combined(loose).contains("no reference form comparable"), combined(loose));

        // The parent-checksum form is comparable when the carrier links the same FHR parent.
        byte[] parent = Fixtures.fhrFasta(">seq1\nACGT\n");
        String parentChecksum = Fixtures.statedChecksum(parent);
        byte[] carrier = Fixtures.sealed(Kind.GFF3, """
            schema: https://w3id.org/fair-bioheaders/fhgff3/v0.5.0
            schemaVersion: '1.1'
            annotation: fixture-annotation-linked-by-parent
            taxon:
              name: Synthetic example
            version: '1'
            metadataAuthor:
            - name: Synthetic fixture author
            dateCreated: 2026-01-01
            derivedFrom:
            - headerType: FHR
              relationship: annotates
              checksum: %s
            x-fhgm-models:
            """.formatted(parentChecksum) + Fixtures.recordByParent("model-a", "1", "toy-a",
            parentChecksum), Fixtures.TOY_FEATURES);
        FhgmModels.Report bound = checker.check(carrier, List.of());
        assertTrue(bound.passed(), combined(bound) + bound.carrier().issues().toString());
    }

    @Test
    void k3RejectsTwoRecordsSharingOneModelIdInOneSnapshot() {
        String duplicated = Fixtures.record("model-a", "1", "toy-a")
            + Fixtures.record("model-a", "2", "toy-b");
        FhgmModels.Report report = models().check(
            Fixtures.fhgm(duplicated, Fixtures.TOY_FEATURES), List.of());
        assertEquals(FhgmModels.State.REJECT, report.state());
        assertEquals("K3", rules(report).get(0), report.issues().toString());
        assertTrue(combined(report).contains("Ambiguous model ID"), combined(report));
        // Two distinct IDs are fine, and each targets its own row.
        assertTrue(models().check(Fixtures.fhgm(
            Fixtures.record("model-a", "1", "toy-a") + Fixtures.record("model-b", "1", "toy-b"),
            Fixtures.TOY_FEATURES), List.of()).passed());
    }

    @Test
    void k6ResolvesAPointerOnlyAgainstSuppliedHistory() {
        FhgmModels checker = models();
        byte[] v1 = Fixtures.fhgm(Fixtures.record("model-a", "1", "toy-a"), Fixtures.TOY_FEATURES);
        String v1Checksum = Fixtures.statedChecksum(v1);
        byte[] v2 = Fixtures.fhgm(
            Fixtures.withPointer(Fixtures.record("model-a", "2", "toy-a"), v1Checksum, "model-a", "1"),
            Fixtures.TOY_FEATURES);

        // No history supplied: the claim is unresolvable, and that is a rejection with a reason.
        FhgmModels.Report alone = checker.check(v2, List.of());
        assertEquals("K6", rules(alone).get(0), alone.issues().toString());
        assertTrue(combined(alone).contains("was not supplied"), combined(alone));
        assertEquals(Map.of("model-a", FhgmModels.PointerVerdict.UNRESOLVABLE), alone.pointers());

        // The right history supplied: resolvable, and the pointer verdict is reported.
        FhgmModels.Report resolved = checker.check(v2, List.of(v1));
        assertTrue(resolved.passed(), combined(resolved));
        assertEquals(Map.of("model-a", FhgmModels.PointerVerdict.RESOLVABLE), resolved.pointers());

        // A revision that reuses the predecessor's version is not a revision.
        byte[] reused = Fixtures.fhgm(
            Fixtures.withPointer(Fixtures.record("model-a", "1", "toy-a"), v1Checksum, "model-a", "1"),
            Fixtures.TOY_FEATURES);
        FhgmModels.Report reuse = checker.check(reused, List.of(v1));
        assertEquals("K6", rules(reuse).get(0), reuse.issues().toString());
        assertTrue(combined(reuse).contains("reuses the predecessor model version"), combined(reuse));

        // A pointer naming a different model ID cannot select its predecessor.
        byte[] wrongId = Fixtures.fhgm(
            Fixtures.withPointer(Fixtures.record("model-a", "2", "toy-a"), v1Checksum, "model-b", "1"),
            Fixtures.TOY_FEATURES);
        FhgmModels.Report misnamed = checker.check(wrongId, List.of(v1));
        assertTrue(combined(misnamed).contains("names another model ID"), combined(misnamed));

        // A pointer at the current file is unresolvable, not self-evidently satisfied.
        String v2Checksum = Fixtures.statedChecksum(v2);
        byte[] selfPointer = Fixtures.fhgm(
            Fixtures.withPointer(Fixtures.record("model-a", "2", "toy-a"), v2Checksum, "model-a", "1"),
            Fixtures.TOY_FEATURES);
        FhgmModels.Report self = checker.check(selfPointer, List.of(v1));
        assertTrue(combined(self).contains("is the current file or was not supplied"),
            combined(self));
    }

    @Test
    void aPredecessorSnapshotWithAmbiguousIdsIsRefusedRatherThanUsed() {
        FhgmModels checker = models();
        byte[] ambiguous = Fixtures.fhgm(
            Fixtures.record("model-a", "1", "toy-a") + Fixtures.record("model-a", "2", "toy-b"),
            Fixtures.TOY_FEATURES);
        String ambiguousChecksum = Fixtures.statedChecksum(ambiguous);
        byte[] child = Fixtures.fhgm(Fixtures.withPointer(
            Fixtures.record("model-a", "3", "toy-a"), ambiguousChecksum, "model-a", "1"),
            Fixtures.TOY_FEATURES);
        FhgmModels.Report report = checker.check(child, List.of(ambiguous));
        assertEquals("K3", rules(report).get(0), report.issues().toString());
        assertTrue(combined(report).contains("Ambiguous model ID"), combined(report));
    }

    @Test
    void aBrokenCarrierIsBoundaryB1NotAModelVerdict() {
        // Tamper a record byte after sealing: the file-bound checksum stops covering the bytes, so
        // no FHGM verdict exists at all. This is the suite's tampered-record-bytes vector in
        // miniature, and the reason an FHGM pass can never be read as an integrity statement.
        byte[] sealed = Fixtures.fhgm(Fixtures.record("model-a", "1", "toy-a"),
            Fixtures.TOY_FEATURES);
        String text = Fixtures.utf8(sealed).replace("modelId: model-a", "modelId: modelA");
        FhgmModels.Report report = models().check(text.getBytes(java.nio.charset.StandardCharsets.UTF_8),
            List.of());
        assertFalse(report.passed());
        assertEquals("B1", rules(report).get(0), report.issues().toString());
        assertTrue(report.carrier().hasRule("R3"), report.carrier().issues().toString());
    }

    private static String combined(FhgmModels.Report report) {
        StringBuilder sb = new StringBuilder();
        report.issues().forEach(i -> sb.append('[').append(i.rule()).append("] ")
            .append(i.message()).append("; "));
        return sb.toString();
    }
}
