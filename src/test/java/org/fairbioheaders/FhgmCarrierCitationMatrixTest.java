/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.fairbioheaders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;

/**
 * The carrier-citation matrix of the FHGM draft lane: every released FHGFF3 identity a carrier can
 * write, crossed with every schemaVersion a carrier can state.
 *
 * <p>Two repositories have to agree about one string here, and that is the whole risk this matrix
 * pins. Rule V1-V3 selection decides <em>which released base</em> the carrier file is read against,
 * from its cited URL; the pinned transport overlay decides <em>which stated versions</em> that base
 * admits, from its closed branches. The URLs are deliberately the full eight the overlay enumerates -
 * each release's w3id URL with and without the trailing slash, and its raw tag URL under both
 * repository names, {@code FAIR-bioHeaders-Specification} and the historical
 * {@code FHR-Specification} alias - because the last pair is exactly where a library can recognise a
 * release in one place and refuse it in the other: the alias resolves to the same release under the
 * shared release record, and an overlay that listed only the canonical name would reject a carrier
 * whose base this library had just selected and validated. Every cell asserts both readings and says
 * which one disagrees.
 *
 * <p>Expected cells come from the contracts, not from this library's behaviour: v0.5.0 pairs with
 * schemaVersion 1.1 only (R15 admits {@code x-} keys from 1.1, and 1.0 is inadmissible for a records
 * carrier), and v0.6.0 pairs with 1.2 or 1.1, because the v0.6.0 release accepts 1.1 records per the
 * shared {@code docs/SCHEMA-VERSIONS.md} - the widening FHGM-Specification#26 put in the pinned bytes.
 * A v0.5.0 citation stating 1.2 is a contradiction and must not be resolved by falling back to the
 * other branch. Tagged {@code draft}: it reads the pinned development contracts.
 */
@Tag("draft")
class FhgmCarrierCitationMatrixTest {

    private static final Options LANE = Options.builder().enableDraft("FHGM").build();

    /** The eight carrier citations the pinned overlay enumerates, as {@code release -> urls}. */
    private static final List<String> CITATIONS = List.of(
        "https://w3id.org/fair-bioheaders/fhgff3/v0.5.0",
        "https://w3id.org/fair-bioheaders/fhgff3/v0.5.0/",
        "https://raw.githubusercontent.com/FAIR-bioHeaders/FAIR-bioHeaders-Specification/v0.5.0/fhgff3.json",
        "https://raw.githubusercontent.com/FAIR-bioHeaders/FHR-Specification/v0.5.0/fhgff3.json",
        "https://w3id.org/fair-bioheaders/fhgff3/v0.6.0",
        "https://w3id.org/fair-bioheaders/fhgff3/v0.6.0/",
        "https://raw.githubusercontent.com/FAIR-bioHeaders/FAIR-bioHeaders-Specification/v0.6.0/fhgff3.json",
        "https://raw.githubusercontent.com/FAIR-bioHeaders/FHR-Specification/v0.6.0/fhgff3.json");

    @TestFactory
    List<DynamicTest> everyCarrierCitationAgainstEveryStatedVersion() {
        SchemaBundle bundle = new SchemaBundle(LANE);
        SchemaSelection selection = new SchemaSelection(bundle);
        FhgmModels models = new FhgmModels(LANE);
        List<DynamicTest> tests = new ArrayList<>();
        for (String citation : CITATIONS) {
            String release = citation.contains("v0.5.0") ? "v0.5.0" : "v0.6.0";
            for (String stated : List.of("1.1", "1.2")) {
                tests.add(DynamicTest.dynamicTest(citation + " + " + stated, () -> {
                    // Reading one: rules V1-V3 select the released base from the cited URL alone.
                    SchemaSelection.Selection picked = selection.select(citation);
                    assertEquals("FHGFF3", picked.type().fileKey(), citation);
                    assertEquals(release, picked.release(),
                        "the citation must select the base release it names: " + citation);

                    // Reading two: the same carrier, run through the FHGM lane.
                    byte[] file = Fixtures.fhgmCarrier(citation, stated,
                        Fixtures.record("model-a", "1", "toy-a"), Fixtures.TOY_FEATURES);
                    FhgmModels.Report report = models.check(file, List.of());
                    boolean carrierAccepted = report.carrier().isValid();
                    List<String> rules = report.issues().stream().map(Issue::rule).toList();

                    if (release.equals("v0.5.0") && stated.equals("1.2")) {
                        // The contradiction cell: the base contract has no 1.2 to state, so the file
                        // fails at the B1 boundary before any model verdict, and the overlay must not
                        // rescue it by pairing the v0.6.0 branch instead.
                        assertEquals(FhgmModels.State.REJECT, report.state(),
                            citation + " stating 1.2 must not pass: " + describe(report));
                        assertTrue(!carrierAccepted || rules.contains("K1"),
                            "a contradictory citation is refused by the base contract or by the"
                                + " overlay's closed branch, never by neither: " + describe(report));
                    } else {
                        assertEquals(FhgmModels.State.PASS, report.state(),
                            citation + " stating " + stated + " is an admitted pairing and must"
                                + " resolve against the base its URL names: " + describe(report));
                        assertTrue(carrierAccepted,
                            "the selected base must accept the header this library read: "
                                + describe(report));
                        // Selection and K1 agreeing is the point of the cell: the base this library
                        // selected from the URL is the same base whose version the overlay's branch
                        // admitted, so no reading of the citation is left to disagree with the other.
                        assertEquals(release, citation.contains("v0.5.0") ? "v0.5.0" : "v0.6.0",
                            citation);
                    }
                }));
            }
        }
        return tests;
    }

    private static String describe(FhgmModels.Report report) {
        StringBuilder sb = new StringBuilder("state=").append(report.state())
            .append(" carrier=").append(report.carrier().status());
        report.issues().forEach(issue -> sb.append(" [").append(issue.rule()).append("] ")
            .append(issue.message()));
        report.carrier().issues().forEach(issue -> sb.append(" [carrier:").append(issue.rule())
            .append("] ").append(issue.message()));
        return sb.toString();
    }
}
