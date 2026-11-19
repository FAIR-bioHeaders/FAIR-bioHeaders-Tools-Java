/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.fairbioheaders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Round-two review cases on head {@code 4afdf50a}: four places where this draft lane asserted more
 * than it had read, or read more than the contract asked.
 *
 * <p>Each case is the counterexample recorded in the second independent review, reproduced as a
 * permanent test:
 *
 * <ol>
 *   <li><b>a target invented by a decoding failure.</b> A gene row written {@code ID=%FF} percent-decodes
 *       to the single byte 0xFF, which is not the UTF-8 encoding of anything. Replacing it with U+FFFD
 *       and then matching it against a record that names {@code "\ufffd"} manufactures a resolution. The
 *       draft's checker indexes the <em>decoded bytes</em> and compares them to the record ID's UTF-8
 *       encoding ({@code validate_fhgm.py:159} and {@code :255}), so {@code %FF} matches no text ID and
 *       K4 does not resolve.</li>
 *   <li><b>an index read before the records were validated.</b> Building the feature index is the
 *       checker's step <em>after</em> whole-array K2 and K3; doing it earlier lets a malformed feature
 *       row report K4 on top of a missing required field or a duplicate identity.</li>
 *   <li><b>a text rule applied where the contract put no text rule.</b> {@code $defs.text} is referenced
 *       by fourteen sites in the pinned record contract; {@code publication.article} is not one of them -
 *       it carries its own DOI pattern. Enforcing the text rule by recursion rejected
 *       {@code 10.1234/ab\u200dcd}, a value the pinned contract accepts: a false invalidity, which the
 *       #264 ruling weighs as heavily as a false pass.</li>
 *   <li><b>the ZWNJ/ZWJ direction of the same mistake.</b> {@code $defs.text}'s own {@code not} pattern
 *       lists U+200B-U+200F explicitly (line 160 of the pinned schema), so a {@code modelId} carrying
 *       ZWNJ or ZWJ is rejected by the contract as frozen, and a cross-family decision taken for FHT
 *       does not license overriding it here.</li>
 * </ol>
 *
 * <p>Tagged {@code draft}: reads the pinned development contracts, so it belongs to the labelled draft
 * job and never to the released-contract acceptance count.
 */
@Tag("draft")
class FhgmRoundTwoReviewTest {

    private static final Options LANE = Options.builder().enableDraft("FHGM").build();

    /** Rows with no {@code toy-a} gene row; each case adds exactly one. */
    private static final String OTHER_ROWS = """
        toy	pilot	lncRNA	40	50	.	+	.	ID=toy-a-rna;Parent=toy-b
        toy	pilot	gene	60	80	.	+	.	ID=toy-b
        """;

    private static FhgmModels models() {
        return new FhgmModels(LANE);
    }

    private static String combined(FhgmModels.Report report) {
        StringBuilder sb = new StringBuilder("state=").append(report.state())
            .append(" carrier=").append(report.carrier().status());
        report.issues().forEach(issue -> sb.append(" [").append(issue.rule()).append("] ")
            .append(issue.message()));
        report.carrier().issues().forEach(issue -> sb.append(" [carrier:").append(issue.rule())
            .append("] ").append(issue.message()));
        return sb.toString();
    }

    private static List<String> rules(FhgmModels.Report report) {
        return report.issues().stream().map(Issue::rule).toList();
    }

    private static FhgmModels.Report check(String modelsYaml, String featureRows) {
        return models().check(Fixtures.fhgm(modelsYaml, featureRows), List.of());
    }

    private static String geneRow(String attributes) {
        return "toy\tpilot\tgene\t10\t30\t.\t+\t.\t" + attributes + "\n";
    }

    /** A record naming {@code featureId} as one double-quoted YAML scalar, escapes intact. */
    private static String recordTargeting(String rawFeatureId) {
        return "- modelId: model-a\n"
            + "  modelVersion: '1'\n"
            + "  featureId: \"" + rawFeatureId + "\"\n"
            + "  reference:\n"
            + "    genomeReference:\n"
            + "      accession: SYNTHETIC_ASSEMBLY.1\n"
            + "      seqcol_id: J3Hvvqu0USaOuxA-3wRFyZ3YPdnSEDCp\n";
    }

    // --- 1. a decoding failure is not a feature ID -------------------------------------------

    @Test
    @DisplayName("K4: a gene row whose ID is undecodable (%FF) is not named by a U+FFFD record")
    void aPercentDecodingFailureDoesNotMatchTheReplacementCharacter() {
        FhgmModels.Report report = check(recordTargeting("\\ufffd"), geneRow("ID=%FF"));
        assertEquals(FhgmModels.State.REJECT, report.state(),
            "0xFF is not the UTF-8 encoding of U+FFFD; replacing it and then matching the "
                + "replacement character invents the one thing K4 must resolve: " + combined(report));
        assertTrue(rules(report).contains("K4"), combined(report));
        assertTrue(combined(report).contains("cannot") || combined(report).contains("resolves to"),
            "the finding must say why the target did not resolve: " + combined(report));
    }

    @Test
    @DisplayName("K4: the undecodable-ID case is distinguishable from a plain missing target")
    void anUndecodableRowIdIsReportedApartFromAnAbsentOne() {
        FhgmModels.Report undecodable = check(recordTargeting("toy-a"), geneRow("ID=%FF"));
        FhgmModels.Report absent = check(recordTargeting("toy-a"), geneRow("ID=toy-q"));
        assertEquals(FhgmModels.State.REJECT, undecodable.state(), combined(undecodable));
        assertEquals(FhgmModels.State.REJECT, absent.state(), combined(absent));
        assertTrue(!combined(undecodable).equals(combined(absent)),
            "two different problems must not collapse into one message: undecodable row ID vs"
                + " no such ID - " + combined(undecodable) + " || " + combined(absent));
        assertTrue(combined(undecodable).toLowerCase().contains("utf")
                || combined(undecodable).toLowerCase().contains("decode"),
            "the undecodable case must name decoding as the reason: " + combined(undecodable));
    }

    @Test
    @DisplayName("K4: real UTF-8 bytes for U+FFFD do resolve (the positive control)")
    void actualReplacementCharacterBytesAreAValidTarget() {
        // %EF%BF%BD IS the UTF-8 encoding of U+FFFD, so a record naming "\ufffd" targets this row.
        FhgmModels.Report report = check(recordTargeting("\\ufffd"), geneRow("ID=%EF%BF%BD"));
        assertEquals(FhgmModels.State.PASS, report.state(), combined(report));
    }

    @Test
    @DisplayName("K4: several undecodable bytes still match nothing")
    void multipleInvalidBytesDoNotResolveEither() {
        FhgmModels.Report report = check(recordTargeting("\\ufffd\\ufffd"),
            geneRow("ID=%FF%FE%FF"));
        assertEquals(FhgmModels.State.REJECT, report.state(), combined(report));
        assertTrue(rules(report).contains("K4"), combined(report));
    }

    @Test
    @DisplayName("K4: percent-decoding of a readable ID still matches (the suite's own pair)")
    void decodedIdsAreComparedNotRawSpellings() {
        assertEquals(FhgmModels.State.PASS,
            check(recordTargeting("toy-a"), geneRow("ID=toy%2Da")).state());
        assertEquals(FhgmModels.State.REJECT,
            check(recordTargeting("toy%2Da"), geneRow("ID=toy%2Da")).state());
    }

    @Test
    @DisplayName("K4: a multi-byte ID that IS valid UTF-8 still resolves (byte-exact, not lossy)")
    void aMultiByteDecodedIdResolvesExactly() {
        // %C3%A9 is the UTF-8 encoding of U+00E9, so a record naming "toy-é" targets this row.
        // Getting this wrong in either direction is the same bug: strict matching that also refuses
        // readable bytes would be over-reach, and replacing unreadable ones is the false pass.
        FhgmModels.Report report = check(recordTargeting("toy-\u00e9"), geneRow("ID=toy-%C3%A9"));
        assertEquals(FhgmModels.State.PASS, report.state(), combined(report));
        // And the composed/decomposed pair does not silently alias: identity is bytes, not meaning.
        FhgmModels.Report other = check(recordTargeting("toy-e\u0301"), geneRow("ID=toy-%C3%A9"));
        assertEquals(FhgmModels.State.REJECT, other.state(), combined(other));
    }

    // --- 2. the feature index is built after K2 and K3 ---------------------------------------

    @Test
    @DisplayName("A malformed feature row does not preempt a missing required field (K2 first)")
    void malformedRowsCannotMaskK2() {
        String noModelId = "- modelVersion: '1'\n"
            + "  featureId: toy-a\n"
            + "  reference:\n"
            + "    genomeReference:\n"
            + "      accession: SYNTHETIC_ASSEMBLY.1\n"
            + "      seqcol_id: J3Hvvqu0USaOuxA-3wRFyZ3YPdnSEDCp\n";
        FhgmModels.Report report = check(noModelId + noModelId, "toy\tpilot\tgene\t10\t30\n");
        assertEquals(FhgmModels.State.REJECT, report.state(), combined(report));
        assertEquals(List.of("K2"), rules(report),
            "the whole-array K2 pass runs before the feature layer is read at all, so a broken row"
                + " cannot report K4 over a record the contract never accepted: " + combined(report));
    }

    @Test
    @DisplayName("A malformed feature row does not preempt a duplicate identity (K3 first)")
    void malformedRowsCannotMaskK3() {
        String pair = Fixtures.record("model-a", "1", "toy-a")
            + Fixtures.record("model-a", "2", "toy-b");
        FhgmModels.Report report = check(pair, "toy\tpilot\tgene\t10\t30\n");
        assertEquals(FhgmModels.State.REJECT, report.state(), combined(report));
        assertEquals(List.of("K3"), rules(report),
            "the snapshot-level ambiguity is named before the unreadable feature layer: "
                + combined(report));
    }

    @Test
    @DisplayName("A malformed feature row alone is still K4, once the records themselves are fine")
    void malformedRowStillRejectsWhenRecordsAreValid() {
        FhgmModels.Report report = check(Fixtures.record("model-a", "1", "toy-a"),
            "toy\tpilot\tgene\t10\t30\n");
        assertEquals(FhgmModels.State.REJECT, report.state(), combined(report));
        assertEquals(List.of("K4"), rules(report), combined(report));
    }

    @Test
    @DisplayName("Disclosed ordering divergence: a broken carrier is refused at B1 before K1 speaks")
    void carrierIntegrityIsSettledBeforeK1() {
        // A carrier the pinned overlay refuses (it cites raw main, which names no registered base
        // release) AND whose checksum is stale at the same time. Upstream validate_fhgm.py runs
        // transport.validate BEFORE the checksum, so it would name the transport defect; this library
        // runs the released carrier check first, so B1 is what a caller sees. Pinned as a test rather
        // than left as a prose claim: the divergence is deliberate (V7 keeps the released reader
        // first), it changes only WHICH boundary a doubly-broken file reports, not whether it is
        // rejected, and docs/STATUS.md states it.
        byte[] rawMainCarrier = Fixtures.fhgmCarrier(
            "https://raw.githubusercontent.com/FAIR-bioHeaders/FAIR-bioHeaders-Specification"
                + "/main/fhgff3.json",
            "1.2", Fixtures.record("model-a", "1", "toy-a"), Fixtures.TOY_FEATURES);
        FhgmModels.Report overlayOnly = models().check(rawMainCarrier, List.of());
        assertEquals(FhgmModels.State.REJECT, overlayOnly.state(), combined(overlayOnly));
        assertTrue(rules(overlayOnly).stream().allMatch("K1"::equals)
                && !rules(overlayOnly).isEmpty(),
            "with an intact carrier every finding is the overlay's: " + combined(overlayOnly));

        FhgmModels.Report bothBroken = models().check(tamper(rawMainCarrier), List.of());
        assertEquals(FhgmModels.State.REJECT, bothBroken.state(), combined(bothBroken));
        assertEquals(List.of("B1"), rules(bothBroken),
            "the released carrier check runs first, so a stale checksum masks the simultaneous K1"
                + " defect - the divergence from upstream documented in docs/STATUS.md: "
                + combined(bothBroken));
    }

    /** One byte of the feature layer changed after sealing: the exact-byte checksum now lies. */
    private static byte[] tamper(byte[] sealed) {
        byte[] copy = sealed.clone();
        byte last = copy[copy.length - 1];
        copy[copy.length - 1] = last == (byte) '9' ? (byte) '8' : (byte) '9';
        return copy;
    }

    // --- 3. the text rule is scoped to the sites the contract types as text -------------------

    @Test
    @DisplayName("publication.article is NOT $defs.text: a ZWJ inside a DOI is accepted")
    void aNonTextFieldKeepsItsOwnConstraint() {
        String yaml = "- modelId: model-a\n"
            + "  modelVersion: '1'\n"
            + "  featureId: toy-a\n"
            + "  publication:\n"
            + "    article: \"10.1234/ab\\u200dcd\"\n"
            + "  reference:\n"
            + "    genomeReference:\n"
            + "      accession: SYNTHETIC_ASSEMBLY.1\n"
            + "      seqcol_id: J3Hvvqu0USaOuxA-3wRFyZ3YPdnSEDCp\n";
        FhgmModels.Report report = check(yaml, geneRow("ID=toy-a") + OTHER_ROWS);
        assertEquals(FhgmModels.State.PASS, report.state(),
            "the pinned article pattern is ^10\\.[0-9]+(\\.[0-9]+)*/\\S+$ and U+200D is not whitespace;"
                + " rejecting it is a false invalidity: " + combined(report));
    }

    @Test
    @DisplayName("A $defs/text field keeps the rule: the same ZWJ in modelId is rejected")
    void aTextFieldStillRejectsTheSameCharacter() {
        String yaml = "- modelId: \"model-a\\u200d\"\n"
            + "  modelVersion: '1'\n"
            + "  featureId: toy-a\n"
            + "  publication:\n"
            + "    article: \"10.1234/ab\\u200dcd\"\n"
            + "  reference:\n"
            + "    genomeReference:\n"
            + "      accession: SYNTHETIC_ASSEMBLY.1\n"
            + "      seqcol_id: J3Hvvqu0USaOuxA-3wRFyZ3YPdnSEDCp\n";
        FhgmModels.Report report = check(yaml, geneRow("ID=toy-a") + OTHER_ROWS);
        assertEquals(FhgmModels.State.REJECT, report.state(), combined(report));
        assertEquals(List.of("K2"), rules(report), combined(report));
        // The finding is the frozen schema's OWN `not` pattern - U+200B-U+200F are listed explicitly
        // at line 160 of the pinned contract - so this rejection does not depend on DraftTextPolicy at
        // all, and no cross-family decision taken for FHT can talk this library out of it.
        assertTrue(combined(report).contains("\\u200b-\\u200f")
                || combined(report).contains("200b"),
            "the report should show the contract's own invisible-character class: "
                + combined(report));
    }

    @Test
    @DisplayName("Both directions at once: article accepts U+200D, modelId rejects it")
    void theSameCharacterIsAcceptedInOneFieldAndRejectedInAnother() {
        String base = "  modelVersion: '1'\n"
            + "  featureId: toy-a\n"
            + "  publication:\n"
            + "    article: \"10.1234/ab\\u200dcd\"\n"
            + "  reference:\n"
            + "    genomeReference:\n"
            + "      accession: SYNTHETIC_ASSEMBLY.1\n"
            + "      seqcol_id: J3Hvvqu0USaOuxA-3wRFyZ3YPdnSEDCp\n";
        assertTrue(check("- modelId: model-a\n" + base, geneRow("ID=toy-a") + OTHER_ROWS)
            .passed(), "article only: the record must pass");
        FhgmModels.Report bad =
            check("- modelId: \"a\\u200db\"\n" + base, geneRow("ID=toy-a") + OTHER_ROWS);
        assertEquals(FhgmModels.State.REJECT, bad.state(), combined(bad));
    }

    @Test
    @DisplayName("A $defs/curie site keeps the rule through its allOf (publication.deposit)")
    void aCurieSiteIsTextConstrainedToo() {
        // "doi:10.1234/x" satisfies curie's own pattern (^[A-Za-z0-9._-]+:\\\S), so the only thing that
        // can refuse the padded spelling is the $defs.text that $defs/curie applies through its allOf.
        String yaml = "- modelId: model-a\n"
            + "  modelVersion: '1'\n"
            + "  featureId: toy-a\n"
            + "  publication:\n"
            + "    deposit: [\"doi:10.1234/x\\u00a0\"]\n"
            + "  reference:\n"
            + "    genomeReference:\n"
            + "      accession: SYNTHETIC_ASSEMBLY.1\n"
            + "      seqcol_id: J3Hvvqu0USaOuxA-3wRFyZ3YPdnSEDCp\n";
        FhgmModels.Report report = check(yaml, geneRow("ID=toy-a") + OTHER_ROWS);
        assertEquals(FhgmModels.State.REJECT, report.state(),
            "publication.deposit is $defs/curie, whose allOf applies $defs.text: " + combined(report));
        assertEquals(List.of("K2"), rules(report), combined(report));
    }

    @Test
    @DisplayName("A $defs/link site is not $defs.text: an ASCII-clean punycode URI passes")
    void aLinkSiteKeepsItsOwnConstraint() {
        String yaml = "- modelId: model-a\n"
            + "  modelVersion: '1'\n"
            + "  featureId: toy-a\n"
            + "  evidence:\n"
            + "  - uri: https://xn--bcher-kva.example/id\n"
            + "    description: Reviewed evidence\n"
            + "  reference:\n"
            + "    genomeReference:\n"
            + "      accession: SYNTHETIC_ASSEMBLY.1\n"
            + "      seqcol_id: J3Hvvqu0USaOuxA-3wRFyZ3YPdnSEDCp\n";
        FhgmModels.Report report = check(yaml, geneRow("ID=toy-a") + OTHER_ROWS);
        assertEquals(FhgmModels.State.PASS, report.state(), combined(report));
    }

    // --- 4. ZWNJ/ZWJ in a text field: the frozen schema governs, not the FHT decision ---------

    @Test
    @DisplayName("ZWNJ and ZWJ are rejected in modelId: $defs.text lists U+200B-U+200F explicitly")
    void zeroWidthJoinersAreRejectedInATextField() {
        for (String name : List.of("200c", "200d")) {
            String yaml = "- modelId: \"model-a\\u" + name + "\"\n"
                + "  modelVersion: '1'\n"
                + "  featureId: toy-a\n"
                + "  reference:\n"
                + "    genomeReference:\n"
                + "      accession: SYNTHETIC_ASSEMBLY.1\n"
                + "      seqcol_id: J3Hvvqu0USaOuxA-3wRFyZ3YPdnSEDCp\n";
            FhgmModels.Report report = check(yaml, geneRow("ID=toy-a") + OTHER_ROWS);
            assertEquals(FhgmModels.State.REJECT, report.state(),
                "U+" + name.toUpperCase() + " in a modelId must reject under the pinned contract: "
                    + combined(report));
            assertEquals(List.of("K2"), rules(report), "U+" + name.toUpperCase());
        }
    }

    @Test
    @DisplayName("A text field the contract leaves legal stays legal: internal hyphenated ID")
    void ordinaryTextIsUnaffectedByTheScoping() {
        FhgmModels.Report report = check(
            Fixtures.record("model-a-1_x", "1", "toy-a"), geneRow("ID=toy-a") + OTHER_ROWS);
        assertEquals(FhgmModels.State.PASS, report.state(), combined(report));
    }
}
