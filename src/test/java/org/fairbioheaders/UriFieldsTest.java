/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.fairbioheaders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The rule R14 field list is a property of the contract being applied, so it is derived from the
 * contract's bytes rather than copied from it. This test pins what the derivation says for every
 * bundled copy, which is also the record of where the reviewed released table and the derivation
 * disagree.
 */
class UriFieldsTest {

    private static final List<String> FHR_TABLE = UriSchemes.URI_FIELDS.get(FhType.FHR);
    private static final List<String> FHP_TABLE = UriSchemes.URI_FIELDS.get(FhType.FHP);
    private static final List<String> FHGFF3_TABLE = UriSchemes.URI_FIELDS.get(FhType.FHGFF3);

    @Test
    void derivationReproducesTheReviewedTableForFhpAndFhgff3() {
        SchemaBundle bundle = SchemaBundle.getDefault();
        assertEquals(FHP_TABLE, UriFields.derive(bundle, bundle.currentFile(FhType.FHP)),
            "FHP 1.2");
        assertEquals(FHGFF3_TABLE, UriFields.derive(bundle, bundle.currentFile(FhType.FHGFF3)),
            "FHGFF3 1.2");
    }

    @Test
    void fhrTableNowCoversEveryUriFieldTheContractDeclares() {
        // The reviewed FHR table used to leave out the two author ORCID fields, which the
        // contract declares `format: uri` and the reference toolkit checks. They are in the table
        // now (labelled golden change; docs/STATUS.md), so the derivation and the table agree.
        List<String> derived = UriFields.derive(SchemaBundle.getDefault(),
            SchemaBundle.getDefault().currentFile(FhType.FHR));
        assertEquals(new java.util.TreeSet<>(derived), new java.util.TreeSet<>(FHR_TABLE));
        assertEquals(FHR_TABLE.size(), new java.util.TreeSet<>(FHR_TABLE).size());
    }

    @Test
    void everyBundledContractYieldsASortedUniqueFieldList() {
        SchemaBundle bundle = SchemaBundle.getDefault();
        for (String file : List.of("fhr-1.json", "fhr-2.0.json", "fhr-2.1.json",
                "fhp-1.0.json", "fhp-1.1.json", "fhp-1.2.json",
                "fhgff3-1.0.json", "fhgff3-1.1.json", "fhgff3-1.2.json")) {
            List<String> derived = UriFields.derive(bundle, file);
            assertEquals(derived.size(), UriFields.sorted(derived).size(),
                file + " must not report a field twice");
            assertTrue(new java.util.TreeSet<>(derived).equals(new java.util.LinkedHashSet<>(derived)),
                file + " must come out free of duplicates");
            assertTrue(!derived.isEmpty(), file + " declares no format:uri field at all");
        }
    }

    @Test
    void theFhtDraftRowStatesWhatTheDraftContractActuallyDeclares() {
        List<String> declared = UriFields.derive(SchemaBundle.getDefault(), "fht-draft.json");
        assertEquals(List.of("accessionID.url", "assemblyAuthor[].uri", "assemblySoftware[].uri",
            "derivedFrom[].accessionID.url", "metadataAuthor[].uri", "relatedLink[]", "taxon.uri"),
            declared);
    }
}
