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

import com.networknt.schema.Schema;

import tools.jackson.databind.ObjectMapper;

/**
 * The ECMA-262 whitespace/anchor floor, read against the pinned FHT draft contract.
 *
 * <p>{@code FAIR-bioHeaders-Specification#264} and {@code docs/REGEX-DIALECT.md} settled that a
 * JSON Schema {@code pattern} is an ECMA-262 regular expression. {@code java.util.regex} differs
 * in exactly the places this family's contracts use: its default {@code \s} is ASCII-only (the
 * ECMA class is the Unicode whitespace union — U+00A0, U+202F and U+FEFF included, U+0085
 * <em>not</em> — and its {@code $} matches before a trailing line terminator.
 * {@link EcmaRegularExpressionFactory} translates both; this test is the witness that the
 * {@code Tools-Java#16} finding demands: a DOI ending in U+00A0 validated here while the
 * reference engine called it INVALID.
 *
 * <p>Every case is asserted against the real shipped pattern bytes — the {@code scholarlyArticle}
 * DOI pattern and the {@code sequencingRuns} end-anchor idiom as vendored — not against a
 * paraphrase, because both directions of divergence are defects: under-widening accepts NBSP (the
 * reported bug), while a shortcut such as {@code UNICODE_CHARACTER_CLASS} would add U+0085 and
 * widen the other character classes, over-rejecting bytes the floor admits — as much a defect as
 * under-rejecting.
 *
 * <p>Tagged {@code draft}: it reads the pinned FHT contract, so it belongs to the labelled draft
 * job and never to the released-contract acceptance count. The sealed FHT-suite vector (an FHT
 * header whose DOI ends in U+00A0, expected invalid, re-sealed by the owning runner) is upstream
 * Track A's surface — the round-14 A2 handoff on FAIR-bioHeaders-Specification#211 names it;
 * this library's obligation is the verdict on today's pattern bytes, which is what is asserted
 * here.
 */
@Tag("draft")
class FhtDraftWhitespaceEcmaTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final FhType FHT_DRAFT = FhType.of("fht", "FHT", FhType.Status.DRAFT,
        FhType.Transport.EMBEDDED_HEADER);

    private static Schema fhtContract() {
        return new SchemaBundle(Options.builder().enableDraft("FHT").build())
            .contract(FHT_DRAFT, "main");
    }

    /** A complete FHT header document (required set satisfied); only the injected member varies. */
    private static List<Issue> validate(String extraMember) {
        StringBuilder json = new StringBuilder("""
            {
              "schema": "https://raw.githubusercontent.com/FAIR-bioHeaders/FHT-Specification/main/fht.json",
              "schemaVersion": "1.0",
              "transcriptome": "synthetic fixture transcript set",
              "assemblyType": "de novo",
              "moleculeType": "mRNA",
              "taxon": {"name": "Homo sapiens"},
              "version": "1.0.0",
              "metadataAuthor": [{"name": "Synthetic fixture author"}],
              "dateCreated": "2026-11-19",
            """);
        if (!extraMember.isEmpty()) {
            json.append(extraMember).append(",\n");
        }
        json.append("""
              "checksum": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
            }
            """);
        return SchemaBundle.validate(fhtContract(), JSON.readTree(json.toString()));
    }

    /**
     * The baseline the fixture is built against: a bare required-set document must be schema-valid,
     * or every "rejected" assertion below could pass on an unrelated defect. Asserted, not assumed.
     */
    private static void requireCleanBaseline() {
        assertEquals(List.of(), validate(""), "the fixture itself must validate");
    }

    /**
     * Whether injecting {@code scholarlyArticle: doi} introduced a violation. networknt reports a
     * top-level {@code pattern} failure without naming the property, so attribution is by
     * comparison with the clean baseline, not by substring matching.
     */
    private static boolean doiRejected(String doi) {
        return validate("\"scholarlyArticle\": \"" + doi + "\"").size() > 0;
    }

    private static boolean runRejected(String run) {
        return validate("\"sequencingRuns\": [\"" + run + "\"]").size() > 0;
    }

    @Test
    @DisplayName("The fixture baseline validates against the pinned FHT contract")
    void baselineIsClean() {
        requireCleanBaseline();
    }

    @Test
    @DisplayName("A plain DOI passes the contract's scholarlyArticle pattern")
    void plainDoiPasses() {
        requireCleanBaseline();
        assertTrue(!doiRejected("10.1234/abc"));
    }

    @Test
    @DisplayName("A DOI ending in ASCII space is INVALID (both engines already agreed here)")
    void asciiSpaceDoiRejected() {
        assertTrue(doiRejected("10.1234/abc "));
    }

    @Test
    @DisplayName("A DOI ending in U+00A0 (NBSP) is INVALID — the Tools-Java#16 divergence, closed")
    void nbspDoiRejected() {
        assertTrue(doiRejected("10.1234/abc "),
            "ECMA-262 \\S excludes U+00A0; an ASCII-only \\s reads this as ordinary text");
    }

    @Test
    @DisplayName("A DOI ending in U+202F (narrow no-break space) is INVALID")
    void narrowNbspDoiRejected() {
        assertTrue(doiRejected("10.1234/abc "),
            "U+202F is ECMA-262 WhiteSpace and outside java.util.regex's ASCII and UNICODE sets "
                + "alike");
    }

    @Test
    @DisplayName("A DOI ending in U+FEFF (BOM/ZWNBSP) is INVALID — ECMA whitespace, which UNICODE_CHARACTER_CLASS omits")
    void bomDoiRejected() {
        assertTrue(doiRejected("10.1234/abc﻿"),
            "U+FEFF is in ECMA-262 WhiteSpace; the UNICODE_CHARACTER_CLASS shortcut would accept it");
    }

    @Test
    @DisplayName("U+0085 (NEL) inside a DOI stays legal — the floor is ECMA, not Python or Java Unicode mode")
    void nelIsNotEcmaWhitespace() {
        assertTrue(!doiRejected("10.1234/abcd"),
            "ECMA-262 does not count U+0085 as whitespace; widening past the floor is as wrong "
                + "as under-widening it");
    }

    @Test
    @DisplayName("A ZWJ inside a DOI stays legal in every engine")
    void zwjInsideDoiUnaffected() {
        assertTrue(!doiRejected("10.1234/ab‍cd"));
    }

    @Test
    @DisplayName("A trailing line feed before the anchor is INVALID — $ means end of string, not before LF")
    void trailingLineFeedDoiRejected() {
        assertTrue(doiRejected("10.1234/abc\\n"),
            "Java's $ matches before a trailing LF; the ECMA floor must not");
    }

    @Test
    @DisplayName("The end-anchor idiom still closes the run-accession pattern (no regression from the \\s translation)")
    void runAccessionAnchorStillCloses() {
        requireCleanBaseline();
        assertTrue(runRejected("SRR12345x"),
            "the translated match-all inside the lookahead must still refuse a trailing character");
        assertTrue(!runRejected("SRR12345"));
    }
}
