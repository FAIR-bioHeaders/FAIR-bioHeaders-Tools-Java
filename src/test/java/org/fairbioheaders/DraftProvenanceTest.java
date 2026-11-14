/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.fairbioheaders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The draft bytes this jar ships, verified against their own provenance on every build.
 *
 * <p>Rule V7 pins a draft the way the family pins a copied tree: the development repository's
 * commit and the SHA-256 of the exact contract bytes. That claim is only worth as much as the check
 * behind it, so this test re-computes the digest of each registered draft, compares it with both
 * places the pin is recorded ({@code schemas/bundled.json} and {@code schemas/provenance.json}),
 * and requires them to agree. It also states what a draft row is <em>not</em>: no release, no
 * {@code schemaVersion} row, no w3id alias, no support claim.
 */
class DraftProvenanceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void everyRegisteredDraftMatchesItsPinnedDigest() throws IOException {
        List<SchemaBundle.Draft> drafts = SchemaBundle.getDefault().drafts();
        assertTrue(!drafts.isEmpty(), "the registry declares the drafts it pins");
        for (SchemaBundle.Draft draft : drafts) {
            byte[] bytes;
            try (InputStream in = SchemaBundle.getDefault().open(draft.file())) {
                assertNotNull(in, "draft contract " + draft.file() + " is missing from the jar");
                bytes = in.readAllBytes();
            }
            assertEquals(draft.sha256(), SchemaBundle.sha256(bytes),
                draft.type() + " bytes differ from the digest the registry states");
            assertTrue(bytes.length > 0);
            assertTrue(draft.commit().length() == 40, "a full commit sha, not an abbreviation");
            assertTrue(draft.citation().startsWith("https://raw.githubusercontent.com/"
                + "FAIR-bioHeaders/" + draft.repository() + "/main/"),
                "V7's lane is the owning repository's raw-main URL, not a tag or w3id form: "
                    + draft.citation());
            JsonNode contract = JSON.readTree(bytes);
            assertEquals("https://json-schema.org/draft/2020-12/schema",
                contract.path("$schema").asString(""),
                draft.type() + " draft is not draft/2020-12; this reader does not evaluate "
                    + "another dialect");
        }
    }

    @Test
    void bundledJsonAndProvenanceAgreeOnEveryDraftPin() throws IOException {
        JsonNode provenance;
        try (InputStream in = SchemaBundle.getDefault().open("provenance.json")) {
            provenance = JSON.readTree(in);
        }
        JsonNode drafts = provenance.get("drafts");
        assertNotNull(drafts, "provenance.json records the drafts beside the releases");
        List<SchemaBundle.Draft> registryDrafts = SchemaBundle.getDefault().drafts();
        assertEquals(drafts.size(), registryDrafts.size(),
            "every registered draft has a provenance entry, and no more");
        for (SchemaBundle.Draft draft : registryDrafts) {
            JsonNode entry = drafts.get(draft.type());
            assertNotNull(entry, draft.type() + " has no provenance entry");
            assertEquals(draft.sha256(), entry.get("sha256").asString(), draft.type());
            assertEquals(draft.commit(), entry.get("commit").asString(), draft.type());
            assertEquals(draft.repository(), entry.get("repository").asString(), draft.type());
            assertEquals(draft.file(), entry.get("path").asString(), draft.type());
            assertEquals(draft.citation(), entry.get("citation").asString(), draft.type());
            assertEquals("draft", entry.get("status").asString(""),
                "a provenance entry that reads like a release would contradict rule V7");
            assertTrue(entry.get("release").isNull(),
                draft.type() + " names no release: " + entry.get("release"));
        }
    }

    @Test
    void releasedCopiesStillMatchTheirOwnProvenance() throws IOException {
        JsonNode provenance;
        try (InputStream in = SchemaBundle.getDefault().open("provenance.json")) {
            provenance = JSON.readTree(in);
        }
        for (JsonNode entry : provenance.get("files")) {
            String file = entry.get("file").asString();
            byte[] bytes;
            try (InputStream in = SchemaBundle.getDefault().open(file)) {
                assertNotNull(in, file + " is missing from the jar");
                bytes = in.readAllBytes();
            }
            assertEquals(entry.get("sha256").asString(), SchemaBundle.sha256(bytes), file);
        }
    }

    @Test
    void aDraftIsNeverSelectedWithoutTheOptIn() throws Exception {
        SchemaBundle.Draft fht = SchemaBundle.getDefault().draft("FHT");
        assertNotNull(fht, "FHT is registered as a draft");
        // Lane off: the citation is rule V5 material and no draft type exists in the registry.
        SchemaSelection off = new SchemaSelection(SchemaBundle.getDefault());
        assertEquals(FhType.FHR, off.select(fht.citation()).type());
        assertEquals("main", off.select(fht.citation()).release());
        assertTrue(!off.knownTypes().contains(FhType.of("fht", "FHT", FhType.Status.DRAFT,
            FhType.Transport.EMBEDDED_HEADER)));
        // A release-shaped citation stays V5 with the lane open too: no release registers FHT,
        // so there is nothing for rule V4 to resolve and no version is invented for the draft.
        SchemaSelection on = new SchemaSelection(
            new SchemaBundle(Options.builder().enableDraft("FHT").build()));
        SchemaSelection.Selection releaseForm =
            on.select("https://w3id.org/fair-bioheaders/fht/v0.7.0");
        assertEquals(FhType.FHR, releaseForm.type());
        assertEquals("V5", releaseForm.warning().rule());
        assertTrue(!releaseForm.warning().message().contains("draft"),
            "a release-shaped citation must not be described as a draft reading: "
                + releaseForm.warning().message());
        // And the draft's own citation, with the lane open, is the draft.
        SchemaSelection.Selection draftForm = on.select(fht.citation());
        assertEquals("FHT", draftForm.type().fileKey());
        assertTrue(draftForm.type().isDraft());
        assertEquals("main", draftForm.release());
        assertNotNull(draftForm.warning());
        assertEquals("V7", draftForm.warning().rule());
        assertTrue(draftForm.warning().message().contains(fht.commit()));
        assertTrue(draftForm.warning().message().contains(fht.sha256()));
        // A draft has no release list to name (rule V4 stays away from it entirely).
        assertEquals(List.of("main (draft, experimental)"), on.supported(draftForm.type()));
        assertThrows(IllegalArgumentException.class,
            () -> SchemaBundle.getDefault().contract(draftForm.type(), "v0.7.0"));
    }
}
