/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.fairbioheaders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** SCHEMA-VERSIONS.md rules V1-V6 ported from the toolkit's selection.py. */
class SelectionTest {

    private final SchemaSelection selection = new SchemaSelection(SchemaBundle.getDefault());
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String MAIN_FHR =
        "https://raw.githubusercontent.com/FAIR-bioHeaders/FAIR-bioHeaders-Specification/main/fhr.json";
    private static final String MAIN_FHR_OLD =
        "https://raw.githubusercontent.com/FAIR-bioHeaders/FHR-Specification/main/fhr.json";
    private static final String MAIN_FHP =
        "https://raw.githubusercontent.com/FAIR-bioHeaders/FAIR-bioHeaders-Specification/main/fhp.json";

    @Test
    void v1RawMain() throws Exception {
        assertEquals(FhType.FHR, selection.select(MAIN_FHR).type());
        assertEquals("main", selection.select(MAIN_FHR).release());
        assertEquals(FhType.FHR, selection.select(MAIN_FHR_OLD).type()); // old name alias
        assertEquals(FhType.FHP, selection.select(MAIN_FHP).type());
    }

    @Test
    void v2AndV3Releases() throws Exception {
        assertEquals("v0.4.0", selection.select(
            "https://raw.githubusercontent.com/FAIR-bioHeaders/FAIR-bioHeaders-Specification/v0.4.0/fhr.json").release());
        assertEquals(FhType.FHGFF3, selection.select(
            "https://w3id.org/fair-bioheaders/fhgff3/v0.5.0").type());
        assertEquals("v0.5.0", selection.select("https://w3id.org/fair-bioheaders/fhp/v0.5.0/").release());
    }

    @Test
    void v4UnknownReleaseBranchRefAndMissingType() {
        SchemaSelection.Unsupported e = assertThrows(SchemaSelection.Unsupported.class,
            () -> selection.select("https://w3id.org/fair-bioheaders/fhr/v0.0.0"));
        assertTrue(e.getMessage().startsWith("FHR: unsupported schema version"));
        assertTrue(e.getMessage().contains("main (v0.6.0)"));
        assertThrows(SchemaSelection.Unsupported.class, () -> selection.select(
            "https://raw.githubusercontent.com/FAIR-bioHeaders/FAIR-bioHeaders-Specification/my-branch/fhr.json"));
        // v0.3.1 published no FHP schema
        assertThrows(SchemaSelection.Unsupported.class, () -> selection.select(
            "https://raw.githubusercontent.com/FAIR-bioHeaders/FAIR-bioHeaders-Specification/v0.3.1/fhp.json"));
    }

    @Test
    void v5OtherValuesReadAsFhrCurrentWithNearMissOnlyForFairBioHeadersUrls() throws Exception {
        SchemaSelection.Selection s = selection.select("https://example.org/profile.json");
        assertEquals(FhType.FHR, s.type());
        assertEquals("main", s.release());
        assertNull(s.warning()); // third-party URL: silent, like the toolkit

        SchemaSelection.Selection path = selection.select("https://w3id.org/fair-bioheaders/fhr/v0.4.0/fhr.json");
        assertEquals(FhType.FHR, path.type());
        assertNotNull(path.warning()); // w3id path form is a near-miss, still V5
        assertEquals("V5", path.warning().rule());
    }

    @Test
    void v6NumericOneOnRawMainIsFhr1() throws Exception {
        JsonNode num = JSON.readTree("{\"schema\":\"" + MAIN_FHR + "\",\"schemaVersion\":1}");
        SchemaSelection.Selection s = selection.selectRecord(num);
        assertEquals("v0.3.1", s.release());
        assertEquals("V6", s.warning().rule());

        JsonNode numFloat = JSON.readTree("{\"schema\":\"" + MAIN_FHR_OLD + "\",\"schemaVersion\":1.0}");
        assertEquals("v0.3.1", selection.selectRecord(numFloat).release());

        JsonNode str = JSON.readTree("{\"schema\":\"" + MAIN_FHR + "\",\"schemaVersion\":\"1\"}");
        assertEquals("main", selection.selectRecord(str).release()); // string "1" is not V6

        JsonNode bool = JSON.readTree("{\"schema\":\"" + MAIN_FHR + "\",\"schemaVersion\":true}");
        assertEquals("main", selection.selectRecord(bool).release());
    }

    @Test
    void bundleAnswersMatchRegistry() {
        SchemaBundle b = SchemaBundle.getDefault();
        assertEquals("v0.6.0", b.currentRelease());
        assertTrue(b.has(FhType.FHR, "v0.3.0"));
        assertTrue(b.has(FhType.FHR, "v0.3.1"));
        assertTrue(b.has(FhType.FHGFF3, "v0.4.0"));
        assertTrue(!b.has(FhType.FHP, "v0.3.1"));
        assertEquals(java.util.List.of("v0.3.0", "v0.3.1", "v0.4.0", "v0.4.1", "v0.4.2", "v0.5.0", "v0.6.0"),
            b.releasesWith(FhType.FHR));
    }
}
