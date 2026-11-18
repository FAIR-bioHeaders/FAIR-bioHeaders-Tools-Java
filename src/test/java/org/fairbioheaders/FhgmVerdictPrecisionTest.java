/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.fairbioheaders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The precision of the FHGM draft's rule diagnostics: which shape produces which rule's finding, and
 * what a {@code PASS} is entitled to claim.
 *
 * <p>Each case here came out of the 2026-11-18 review of the round-13 re-pin, and each one is the
 * failure direction that matters for a draft lane - a {@code pass} asserted from something the reader
 * did not actually resolve:
 *
 * <ul>
 *   <li>a {@code Parent} attribute that is <em>present but empty</em>, or a {@code Parent} token that
 *       is malformed, is not the absence of a parent, so it cannot establish K4's root gene row;</li>
 *   <li>a record with no {@code modelId} at all is a shape failure (K2), not a collision (K3) about a
 *       string that does not exist; the pinned contract validates every record's shape before the
 *       checker compares identities, and this reader keeps that order;</li>
 *   <li>the contract's text rule is written <em>in Unicode terms</em> ({@code $defs.text}: "no leading
 *       or trailing whitespace (Unicode whitespace included)"), which an ASCII-only {@code \s} class
 *       does not implement, so U+00A0 and friends must be caught here rather than passed.</li>
 * </ul>
 *
 * <p>Tagged {@code draft}: it reads the pinned development contracts, so it belongs to the labelled
 * draft job and never to the released-contract acceptance count.
 */
@Tag("draft")
class FhgmVerdictPrecisionTest {

    private static final Options LANE = Options.builder().enableDraft("FHGM").build();

    /** Feature rows with no {@code toy-a} gene row: each test adds exactly one row for that ID. */
    private static final String OTHER_ROWS = """
        toy	pilot	lncRNA	40	50	.	+	.	ID=toy-a-rna;Parent=toy-b
        toy	pilot	gene	60	80	.	+	.	ID=toy-b
        """;

    private static FhgmModels models() {
        return new FhgmModels(LANE);
    }

    private static String combined(FhgmModels.Report report) {
        StringBuilder sb = new StringBuilder();
        report.issues().forEach(issue -> sb.append('[').append(issue.rule()).append("] ")
            .append(issue.message()).append("; "));
        report.carrier().issues().forEach(issue -> sb.append("[carrier:").append(issue.rule())
            .append("] ").append(issue.message()).append("; "));
        return sb.toString();
    }

    private static List<String> rules(FhgmModels.Report report) {
        return report.issues().stream().map(Issue::rule).toList();
    }

    /** The draft suite's {@code toy-a} gene row, with the attribute column under test. */
    private static String features(String geneAttributes) {
        return "toy\tpilot\tgene\t10\t30\t.\t+\t.\t" + geneAttributes + "\n" + OTHER_ROWS;
    }

    /**
     * A record whose {@code modelId} is written as one double-quoted YAML scalar with every code
     * point above 0x7E, and every control character, escaped as {@code \\uXXXX}. The file's own bytes
     * stay plain ASCII - so a raw U+2028 cannot break the header framing before the contract sees
     * the value - while the string the contract is handed contains the character under test.
     */
    private static String quotedId(String value) {
        return quotedId(value, "toy-a", "1");
    }

    private static String quotedId(String value, String featureId, String version) {
        return "- modelId: " + escape(value) + "\n"
            + "  modelVersion: '" + version + "'\n"
            + "  featureId: " + featureId + "\n"
            + "  reference:\n"
            + "    genomeReference:\n"
            + "      accession: SYNTHETIC_ASSEMBLY.1\n"
            + "      seqcol_id: J3Hvvqu0USaOuxA-3wRFyZ3YPdnSEDCp\n";
    }

    private static String escape(String value) {
        StringBuilder sb = new StringBuilder("\"");
        value.codePoints().forEach(cp -> {
            if (cp == '"') {
                sb.append("\\\"");
            } else if (cp == '\\') {
                sb.append("\\\\");
            } else if (cp >= 0x20 && cp <= 0x7e) {
                sb.append((char) cp);
            } else {
                sb.append(String.format("\\u%04x", cp));
            }
        });
        return sb.append('"').toString();
    }

    private static FhgmModels.Report check(String modelsYaml, String featureRows) {
        return models().check(Fixtures.fhgm(modelsYaml, featureRows), List.of());
    }

    // --- K4's root condition: four shapes of the Parent attribute ------------------------------

    @Test
    @DisplayName("K4 root: a gene row with no Parent attribute resolves (the positive control)")
    void aGeneRowWithNoParentAttributeIsARoot() {
        FhgmModels.Report report = check(Fixtures.record("model-a", "1", "toy-a"),
            features("ID=toy-a"));
        assertEquals(FhgmModels.State.PASS, report.state(), combined(report));
    }

    @Test
    @DisplayName("K4 root: Parent= is a present tag with an empty value list, not an absent tag")
    void anEmptyParentAttributeDoesNotEstablishARootGene() {
        FhgmModels.Report report = check(Fixtures.record("model-a", "1", "toy-a"),
            features("ID=toy-a;Parent="));
        assertEquals(FhgmModels.State.REJECT, report.state(),
            "an indeterminate Parent attribute cannot support an affirmative root assertion: "
                + combined(report));
        assertTrue(rules(report).contains("K4"), combined(report));
        assertTrue(combined(report).contains("Parent"), combined(report));
    }

    @Test
    @DisplayName("K4 root: a bare Parent token (no '=') is malformed, and malformed is not absent")
    void aMalformedParentTokenDoesNotEstablishARootGene() {
        FhgmModels.Report report = check(Fixtures.record("model-a", "1", "toy-a"),
            features("ID=toy-a;Parent"));
        assertEquals(FhgmModels.State.REJECT, report.state(),
            "an unparseable attribute column says nothing about parenthood: " + combined(report));
        assertTrue(rules(report).contains("K4"), combined(report));
    }

    @Test
    @DisplayName("K4 root: Parent=toy-b is a nested gene and rejects (the re-pin's own case)")
    void aGeneRowWithAParentRejectsAsNested() {
        FhgmModels.Report report = check(Fixtures.record("model-a", "1", "toy-a"),
            features("ID=toy-a;Parent=toy-b"));
        assertEquals(FhgmModels.State.REJECT, report.state(), combined(report));
        assertTrue(rules(report).contains("K4"), combined(report));
        assertTrue(combined(report).contains("has a Parent attribute, so it is not a root gene row"),
            "the suite's reason wording for feature-target-gene-with-parent: " + combined(report));
    }

    @Test
    @DisplayName("K4 root: an empty Parent on some OTHER row does not taint the target row")
    void anEmptyParentElsewhereInTheFileDoesNotChangeTheTargetRowsVerdict() {
        String rows = features("ID=toy-a")
            + "toy\tpilot\tgene\t90\t99\t.\t+\t.\tID=toy-c;Parent=\n";
        FhgmModels.Report report = check(Fixtures.record("model-a", "1", "toy-a"), rows);
        assertEquals(FhgmModels.State.PASS, report.state(), combined(report));
    }

    // --- K2 before K3: shape first, then identity ---------------------------------------------

    @Test
    @DisplayName("Two records with no modelId are a K2 shape failure, never a K3 collision")
    void aMissingModelIdReachesK2AndNotAFabricatedK3() {
        String withoutId = "- modelVersion: '1'\n"
            + "  featureId: toy-a\n"
            + "  reference:\n"
            + "    genomeReference:\n"
            + "      accession: SYNTHETIC_ASSEMBLY.1\n"
            + "      seqcol_id: J3Hvvqu0USaOuxA-3wRFyZ3YPdnSEDCp\n";
        FhgmModels.Report report = check(withoutId + withoutId, features("ID=toy-a"));
        assertEquals(FhgmModels.State.REJECT, report.state(), combined(report));
        assertEquals(List.of("K2"), rules(report),
            "the finding must be the required-property error the contract states, not an ambiguity"
                + " about a string neither record carries: " + combined(report));
        assertTrue(combined(report).contains("modelId"), combined(report));
        assertFalse(combined(report).contains("Ambiguous model ID"),
            "no identity was fabricated out of a missing field: " + combined(report));
    }

    @Test
    @DisplayName("A record whose shape is invalid does not preempt a real K3 collision either way")
    void aCollidingSnapshotWithAnInvalidRecordStillNamesTheCollisionLast() {
        // Neither reading is silent: the snapshot has two defects, and the first one the checker can
        // honestly name is the shape defect, because K3 compares strings the contract has not
        // accepted yet. This pins the ordering rather than trusting it.
        String bad = "- modelId: model-a\n"
            + "  modelVersion: '1'\n"
            + "  featureId: toy-a\n";
        String dupe = Fixtures.record("model-a", "2", "toy-b");
        FhgmModels.Report report = check(bad + dupe, features("ID=toy-a"));
        assertEquals(FhgmModels.State.REJECT, report.state(), combined(report));
        assertEquals("K2", rules(report).get(0),
            "a record the contract rejects has no usable identity to compare: " + combined(report));
    }

    @Test
    @DisplayName("A genuine duplicate identity still outranks the per-record K4/K5 checks")
    void aRealCollisionIsReportedBeforePerRecordRules() {
        String pair = Fixtures.record("model-a", "1", "toy-a")
            + Fixtures.record("model-a", "2", "toy-does-not-exist");
        FhgmModels.Report report = check(pair, features("ID=toy-a"));
        assertEquals(FhgmModels.State.REJECT, report.state(), combined(report));
        assertEquals(List.of("K3"), rules(report),
            "feature-target-gene-with-parent-style per-record findings must not preempt the"
                + " snapshot-level ambiguity: " + combined(report));
        assertTrue(combined(report).contains("Ambiguous model ID"), combined(report));
    }

    @Test
    @DisplayName("Spellings that fold together under NFC are one identity (K3)")
    void distinctSpellingsEqualUnderNfcCollide() {
        String pair = quotedId("model-\u00e9", "toy-a", "1")
            + quotedId("model-e\u0301", "toy-a", "2");
        FhgmModels.Report report = check(pair, features("ID=toy-a"));
        assertEquals(FhgmModels.State.REJECT, report.state(), combined(report));
        assertEquals(List.of("K3"), rules(report), combined(report));
        assertTrue(combined(report).contains("NFC"), combined(report));
    }

    // --- $defs.text in Unicode terms ----------------------------------------------------------

    @Test
    @DisplayName("A text field padded with U+00A0 violates the contract's Unicode whitespace rule")
    void aNonBreakingSpaceIsTrailingWhitespaceForTheDraftContract() {
        FhgmModels.Report report = check(quotedId("model-a\u00a0"), features("ID=toy-a"));
        assertEquals(FhgmModels.State.REJECT, report.state(),
            "$defs.text forbids trailing whitespace and says 'Unicode whitespace included'; an"
                + " ASCII-only \\s class reads U+00A0 as ordinary text: " + combined(report));
        assertEquals(List.of("K2"), rules(report), combined(report));
    }

    @Test
    @DisplayName("A modelId of one U+00A0 is blank text, not a value")
    void aNonBreakingSpaceOnlyIdIsNotNonBlankText() {
        FhgmModels.Report report = check(quotedId("\u00a0"), features("ID=toy-a"));
        assertEquals(FhgmModels.State.REJECT, report.state(), combined(report));
        assertEquals(List.of("K2"), rules(report), combined(report));
    }

    @Test
    @DisplayName("The whitespace set the contract itself enumerates is caught at both ends")
    void unicodeSpaceFamilyIsRejectedAtBothEnds() {
        int[] spaces = {' ', '\t', '\n', 0x0b, '\f', '\r', 0x1c, 0x1d, 0x1e, 0x1f, 0x85, 0xa0,
            0x1680, 0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2007, 0x2008, 0x2009,
            0x200a, 0x2028, 0x2029, 0x202f, 0x205f, 0x3000};
        for (int cp : spaces) {
            String name = "U+" + String.format("%04X", cp);
            for (String value : List.of("model-a" + new String(Character.toChars(cp)),
                new String(Character.toChars(cp)) + "model-a")) {
                FhgmModels.Report report = check(quotedId(value), features("ID=toy-a"));
                assertEquals(FhgmModels.State.REJECT, report.state(),
                    name + " at the edge of a text field must not pass: " + combined(report));
                assertEquals(List.of("K2"), rules(report), name);
            }
        }
    }

    @Test
    @DisplayName("The invisible-character set the contract enumerates is caught anywhere in a field")
    void invisibleCharactersAreRejectedAnywhere() {
        int[] invisibles = {0x0, 0x1f, 0x7f, 0x80, 0x9f, 0x200b, 0x200c, 0x200d, 0x200e, 0x200f,
            0x202a, 0x202b, 0x202c, 0x202d, 0x202e, 0x2060, 0x2061, 0x2062, 0x2063, 0x2064, 0xfeff};
        for (int cp : invisibles) {
            String value = "mo" + new String(Character.toChars(cp)) + "del-a";
            FhgmModels.Report report = check(quotedId(value), features("ID=toy-a"));
            assertEquals(FhgmModels.State.REJECT, report.state(),
                "U+" + String.format("%04X", cp) + " inside a text field must not pass: "
                    + combined(report));
            assertEquals(List.of("K2"), rules(report), "U+" + String.format("%04X", cp));
        }
    }

    @Test
    @DisplayName("An internal ordinary space stays legal: the rule is edges plus invisibles")
    void anInternalOrdinarySpaceIsStillValidText() {
        FhgmModels.Report report = check(quotedId("model a"), features("ID=toy-a"));
        assertEquals(FhgmModels.State.PASS, report.state(), combined(report));
    }

    @Test
    @DisplayName("Every $defs.text field is held to it, not only modelId")
    void theTextRuleCoversEveryTextFieldOfTheRecord() {
        String yaml = "- modelId: model-a\n"
            + "  modelVersion: '1'\n"
            + "  featureId: toy-a\n"
            + "  reuseConditions: \"CC0\\u00a0\"\n"
            + "  reference:\n"
            + "    genomeReference:\n"
            + "      accession: SYNTHETIC_ASSEMBLY.1\n"
            + "      seqcol_id: J3Hvvqu0USaOuxA-3wRFyZ3YPdnSEDCp\n";
        FhgmModels.Report report = check(yaml, features("ID=toy-a"));
        assertEquals(FhgmModels.State.REJECT, report.state(), combined(report));
        assertEquals(List.of("K2"), rules(report),
            "reuseConditions is $defs.text too, so a check wired to one field only would pass it: "
                + combined(report));
    }

    @Test
    @DisplayName("A featureId padded with U+00A0 is caught as shape, before any K4 target claim")
    void aNonBreakingSpaceFeatureTargetIsAShapeFailure() {
        String yaml = "- modelId: model-a\n"
            + "  modelVersion: '1'\n"
            + "  featureId: \"toy-a\\u00a0\"\n"
            + "  reference:\n"
            + "    genomeReference:\n"
            + "      accession: SYNTHETIC_ASSEMBLY.1\n"
            + "      seqcol_id: J3Hvvqu0USaOuxA-3wRFyZ3YPdnSEDCp\n";
        FhgmModels.Report report = check(yaml, features("ID=toy-a"));
        assertEquals(FhgmModels.State.REJECT, report.state(), combined(report));
        assertEquals(List.of("K2"), rules(report),
            "shape comes first, so the padded featureId is a K2 finding about that field: "
                + combined(report));
    }
}
