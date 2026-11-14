/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.fairbioheaders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The header-type registry: the shipped rows, and the proof that a header type is registry data
 * rather than reader code (FAIR-bioHeaders-Tools-Java#4, issue #2 acceptance "refactor to a
 * five-type-extensible registry/API so FHT and FHGM are additive").
 *
 * <p>The proof uses {@code src/test/resources/org/fairbioheaders/testtypes/bundled-proof.json},
 * which declares two types the library has never heard of - {@code PRT} (a draft file-bound
 * header) and {@code PRM} (a draft carried as records inside another type's header, the FHGM
 * shape) - pinned to a test contract. No production class is touched to add them.
 */
class TypeRegistryTest {

    private static final String TESTTYPES = "/org/fairbioheaders/testtypes/";
    private static final String PRT_CITATION =
        "https://raw.githubusercontent.com/FAIR-bioHeaders/Proof-Specification/main/prt.json";
    private static final String PRM_CITATION =
        "https://raw.githubusercontent.com/FAIR-bioHeaders/Proof-Specification/main/prm.json";

    private static SchemaBundle proofBundle(Options options, String registry) {
        return new SchemaBundle(options, TESTTYPES, registry, TESTTYPES);
    }

    @Test
    void shippedRegistryRegistersThreeReleasedTypesAndOneOptInDraft() {
        SchemaBundle bundle = SchemaBundle.getDefault();
        assertEquals(List.of("FHR", "FHP", "FHGFF3"),
            bundle.types().released().stream().map(d -> d.type().fileKey()).toList(),
            "rules V1-V3 recognise exactly the released rows");
        assertTrue(bundle.types().drafts().isEmpty(),
            "no draft is active on a default bundle (rule V7 is never the default)");
        assertEquals(List.of("FHT"), bundle.declaredDraftTypes(),
            "FHT is registered as a pinned draft, opt-in only (docs/CAPABILITY-MATRIX.md)");
        assertEquals(List.of("fhr", "fhp", "fhgff3"),
            bundle.types().released().stream().map(d -> d.type().urlKey()).toList());
        assertEquals(3, new BioHeaders().readableTypes().size());
        // Opting in is what makes it readable; the released three are unchanged by it.
        BioHeaders optedIn = new BioHeaders(Options.builder().enableDraft("FHT").build());
        assertEquals(List.of("FHR", "FHP", "FHGFF3", "FHT"),
            optedIn.readableTypes().stream().map(FhType::fileKey).toList());
        // The pin the V7 warning must name.
        SchemaBundle.Draft fht = bundle.draft("FHT");
        assertEquals("226f923c28b680edb3c546e99a751482d06aef1a", fht.commit());
        assertEquals("59ad9641adcb0e5591045c2d894c066d1adc9984cca84c5ff6502f8dad88487e", fht.sha256());
        assertEquals("https://raw.githubusercontent.com/FAIR-bioHeaders/FHT-Specification/main/fht.json",
            fht.citation());
    }

    @Test
    void releasedRowsMustNameAConstant() {
        // A registry cannot mint public API: a released row without a matching FhType constant
        // is a build error, not a silently ignored entry.
        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> proofBundle(Options.defaults(), "bundled-unknown-released.json"));
        assertTrue(e.getMessage().contains("no released FhType constant for NEWT"), e.getMessage());
    }

    @Test
    void unknownDraftWithoutOptInStaysRuleV5() throws Exception {
        SchemaBundle bundle = proofBundle(Options.defaults(), "bundled-proof.json");
        BioHeaders reader = new BioHeaders(bundle);
        assertEquals(3, reader.readableTypes().size(), "the drafts are not registered by default");
        assertEquals(List.of("PRT", "PRM"), reader.declaredDraftTypes(),
            "the discovery API names what the bundle declares, opted in or not");

        SchemaSelection.Selection sel = new SchemaSelection(bundle, bundle.types())
            .select(PRT_CITATION);
        assertEquals(FhType.FHR, sel.type(), "rule V5: an unrecognised citation reads as FHR");
        assertEquals("main", sel.release());
        assertTrue(sel.warning() != null && sel.warning().rule().equals("V5"),
            "a FAIR-bioHeaders-shaped URL that matches no rule is a near-miss: " + sel.warning());
    }

    @Test
    void optInRegistersTheNewTypeWithNoCodeChange() throws Exception {
        Options options = Options.builder().enableDraftContracts(true).build();
        SchemaBundle bundle = proofBundle(options, "bundled-proof.json");
        BioHeaders reader = new BioHeaders(bundle);
        assertEquals(List.of("FHR", "FHP", "FHGFF3", "PRT", "PRM"),
            reader.readableTypes().stream().map(FhType::fileKey).toList(),
            "a registry row is what adds a type");

        SchemaSelection.Selection sel = new SchemaSelection(bundle, bundle.types())
            .select(PRT_CITATION);
        assertEquals("PRT", sel.type().fileKey());
        assertEquals("main", sel.release());
        assertTrue(sel.type().isDraft());
        assertTrue(sel.type().transport() == FhType.Transport.EMBEDDED_HEADER);
        assertEquals(2, bundle.types().def(sel.type()).uriFields().size(),
            "the R14 field list comes from the row");
    }

    @Test
    void aRecordsCarryingTypeIsNeverRoutedByItsOwnCitation() throws Exception {
        // Rule V7 routes a file by its own header citation. FHGM-shaped records carry no schema
        // field, so their draft's citation must not become a file-level selection, even opted in.
        Options options = Options.builder().enableDraftContracts(true).build();
        SchemaBundle bundle = proofBundle(options, "bundled-proof.json");
        SchemaSelection selection = new SchemaSelection(bundle, bundle.types());

        SchemaSelection.Selection sel = selection.select(PRM_CITATION);
        assertEquals(FhType.FHR, sel.type(),
            "an embedded-records draft is not selected by a schema URL");
        assertEquals("main", sel.release());
    }

    @Test
    void selectingOneDraftByNameDoesNotEnableTheOther() throws Exception {
        Options options = Options.builder().enableDraft("PRT").build();
        SchemaBundle bundle = proofBundle(options, "bundled-proof.json");
        assertEquals(List.of("PRT"),
            bundle.types().drafts().stream().map(d -> d.type().fileKey()).toList());
        SchemaSelection selection = new SchemaSelection(bundle, bundle.types());
        assertEquals("PRT", selection.select(PRT_CITATION).type().fileKey());
        assertEquals(FhType.FHR, selection.select(PRM_CITATION).type());
    }

    @Test
    void aDraftWhoseBytesDontMatchItsPinFailsInsteadOfBeingRead() {
        Options options = Options.builder().enableDraftContracts(true).build();
        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> proofBundle(options, "bundled-tampered.json"));
        assertTrue(e.getMessage().contains("refusing to read drafts that do not match"),
            e.getMessage());
        assertTrue(e.getMessage().contains("deadbeef"), e.getMessage());
        assertTrue(e.getMessage().contains("3490101dc053a38d07399185e6dfd28a3ecbb6bc966b813cf8f8e90359e1fed9"),
            e.getMessage());
        // The same registry is harmless with the lane off: nothing loads, so nothing to verify.
        assertEquals(3, proofBundle(Options.defaults(), "bundled-tampered.json")
            .types().released().size());
    }

    @Test
    void releasedSelectionStillResolvesEveryBundledRelease() throws Exception {
        // The registry-driven patterns must not change what V1-V6 select.
        SchemaBundle bundle = proofBundle(Options.defaults(), "bundled-proof.json");
        SchemaSelection selection = new SchemaSelection(bundle, bundle.types());
        assertEquals(FhType.FHGFF3, selection.select(
            "https://w3id.org/fair-bioheaders/fhgff3/v0.6.0").type());
        assertEquals("v0.3.1", selection.select(
            "https://raw.githubusercontent.com/FAIR-bioHeaders/FAIR-bioHeaders-Specification"
                + "/v0.3.1/fhr.json").release());
        assertThrows(SchemaSelection.Unsupported.class, () -> selection.select(
            "https://w3id.org/fair-bioheaders/fhgff3/v0.3.1"));
    }
}
