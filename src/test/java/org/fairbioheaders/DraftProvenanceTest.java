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
 * <p>Rule V7 pins a draft the way the family pins a copied tree: the development repository's commit
 * <em>and</em> the SHA-256 of the exact contract bytes. That claim is worth only as much as the check
 * behind it, so this test re-computes every registered draft's digest, requires the two places the
 * pin is recorded ({@code schemas/bundled.json} and {@code schemas/provenance.json}) to agree with
 * the bytes and with each other, and states what a draft row is <em>not</em>: no release, no
 * {@code schemaVersion} row, no w3id alias, no support claim.
 */
class DraftProvenanceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void everyRegisteredDraftMatchesItsPinnedDigest() throws IOException {
        List<SchemaBundle.Draft> drafts = SchemaBundle.getDefault().drafts();
        assertEquals(List.of("FHT", "FHGM"), drafts.stream().map(SchemaBundle.Draft::type).toList(),
            "the drafts registered in this build, each labelled draft and opt-in (rule V7)");
        for (SchemaBundle.Draft draft : drafts) {
            for (SchemaBundle.Draft.Artifact artifact : draft.pinnedArtifacts()) {
                byte[] bytes;
                try (InputStream in = SchemaBundle.getDefault().open(artifact.file())) {
                    assertNotNull(in, draft.type() + " contract " + artifact.file()
                        + " is missing from the jar");
                    bytes = in.readAllBytes();
                }
                assertEquals(artifact.sha256(), SchemaBundle.sha256(bytes),
                    draft.type() + " / " + artifact.file() + " differs from the recorded digest");
                assertTrue(bytes.length > 0);
                JsonNode contract = JSON.readTree(bytes);
                assertEquals("https://json-schema.org/draft/2020-12/schema",
                    contract.path("$schema").asString(""),
                    draft.type() + " / " + artifact.file() + " is not draft/2020-12; this reader does"
                        + " not evaluate another dialect");
            }
            assertEquals(40, draft.commit().length(), "a full commit sha, not an abbreviation");
            assertTrue(draft.citation().startsWith("https://raw.githubusercontent.com/FAIR-bioHeaders/"
                + draft.repository() + "/main/"),
                "V7's lane is the owning repository's raw-main URL, never a tag or w3id form: "
                    + draft.citation());
            String source = draft.source().toLowerCase(java.util.Locale.ROOT);
            assertTrue(source.contains("no released") && source.contains("development"),
                "the registry says where the bytes came from and what they are not: " + draft.source());
        }
    }

    @Test
    void bundledJsonAndProvenanceAgreeOnEveryDraftPin() throws IOException {
        JsonNode provenance = resource("provenance.json");
        JsonNode drafts = provenance.get("drafts");
        assertNotNull(drafts, "provenance.json records the drafts beside the releases");
        List<SchemaBundle.Draft> registryDrafts = SchemaBundle.getDefault().drafts();
        assertEquals(drafts.size(), registryDrafts.size(),
            "every registered draft has a provenance entry, and no more");
        for (SchemaBundle.Draft draft : registryDrafts) {
            JsonNode entry = drafts.get(draft.type());
            assertNotNull(entry, draft.type() + " has no provenance entry");
            assertEquals("draft", entry.get("status").asString(""),
                "a provenance entry that reads like a release would contradict rule V7");
            assertTrue(entry.get("release").isNull(),
                draft.type() + " names no release: " + entry.get("release"));
            assertEquals(draft.commit(), entry.get("commit").asString(), draft.type());
            assertEquals(draft.repository(), entry.get("repository").asString(), draft.type());
            assertEquals(draft.citation(), entry.get("citation").asString(), draft.type());
            assertEquals(draft.suiteCommit(), entry.get("suiteCommit").asString(), draft.type());
            assertEquals(draft.suiteManifestSha256(),
                entry.get("suiteManifestSha256").asString(), draft.type());
            JsonNode artifacts = entry.get("artifacts");
            assertEquals(draft.pinnedArtifacts().size(), artifacts.size(),
                draft.type() + " pins as many files as provenance lists");
            for (SchemaBundle.Draft.Artifact artifact : draft.pinnedArtifacts()) {
                JsonNode recorded = find(artifacts, artifact.file());
                assertEquals(artifact.sha256(), recorded.get("sha256").asString(),
                    draft.type() + " / " + artifact.file());
                assertEquals(artifact.sourcePath(), recorded.get("sourcePath").asString(),
                    draft.type() + " / " + artifact.file());
                assertEquals(recorded.get("size").asInt(),
                    read(artifact.file()).length, draft.type() + " byte count drifted silently");
            }
        }
    }

    @Test
    void aRecordsDraftMustNameItsWholeTransport() {
        SchemaBundle.Draft fhgm = SchemaBundle.getDefault().draft("FHGM");
        assertNotNull(fhgm);
        assertEquals("x-fhgm-models", fhgm.modelsKey());
        assertEquals("FHGFF3", fhgm.carrierFileKey());
        assertEquals(2, fhgm.pinnedArtifacts().size(),
            "the record contract and the transport overlay, both pinned");
        // A row that declares the record contract but not the overlay is refused at load, because a
        // half-declared records lane would silently skip the transport rules it exists to check.
        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> new SchemaBundle(Options.builder().enableDraft("PRM").build(),
                "/org/fairbioheaders/testtypes/", "bundled-incomplete-records.json",
                "/org/fairbioheaders/schemas/"));
        assertTrue(e.getMessage().contains("transportFile"), e.getMessage());
    }

    @Test
    void theReleasedCopiesStillMatchTheirOwnProvenance() throws IOException {
        JsonNode provenance = resource("provenance.json");
        for (JsonNode entry : provenance.get("files")) {
            String file = entry.get("file").asString();
            assertEquals(entry.get("sha256").asString(), SchemaBundle.sha256(read(file)), file);
        }
    }

    @Test
    void aDraftIsNeverSelectedWithoutTheOptIn() throws Exception {
        SchemaBundle.Draft fht = SchemaBundle.getDefault().draft("FHT");
        assertNotNull(fht, "FHT is registered as a draft");
        // Lane off: the citation is rule V5 material and no draft type is registered at all.
        SchemaSelection off = new SchemaSelection(SchemaBundle.getDefault());
        assertEquals(FhType.FHR, off.select(fht.citation()).type());
        assertEquals("main", off.select(fht.citation()).release());
        assertTrue(!off.knownTypes().contains(FhType.of("fht", "FHT", FhType.Status.DRAFT,
            FhType.Transport.EMBEDDED_HEADER)));
        // A release-shaped citation stays V5 with the lane open too: no release registers FHT, so
        // there is nothing for rule V4 to resolve and no version is invented for a draft.
        SchemaSelection on = new SchemaSelection(
            new SchemaBundle(Options.builder().enableDraft("FHT").build()));
        SchemaSelection.Selection releaseForm =
            on.select("https://w3id.org/fair-bioheaders/fht/v0.7.0");
        assertEquals(FhType.FHR, releaseForm.type());
        assertEquals("V5", releaseForm.warning().rule());
        assertTrue(!releaseForm.warning().message().contains("draft"),
            "a release-shaped citation must not be described as a draft reading: "
                + releaseForm.warning().message());
        // The draft's own citation, with the lane open, is the draft.
        SchemaSelection.Selection draftForm = on.select(fht.citation());
        assertEquals("FHT", draftForm.type().fileKey());
        assertTrue(draftForm.type().isDraft());
        assertEquals("main", draftForm.release());
        assertNotNull(draftForm.warning());
        assertEquals("V7", draftForm.warning().rule());
        assertTrue(draftForm.warning().message().contains(fht.commit()));
        assertTrue(draftForm.warning().message().contains(fht.sha256()));
        // A draft has no release list to name (rule V4 stays away from it entirely), and no release
        // can be selected for it.
        assertEquals(List.of("main (draft, experimental)"), on.supported(draftForm.type()));
        assertThrows(IllegalArgumentException.class,
            () -> SchemaBundle.getDefault().contract(draftForm.type(), "v0.7.0"));
    }

    private static JsonNode find(JsonNode artifacts, String file) {
        for (JsonNode artifact : artifacts) {
            if (file.equals(artifact.get("path").asString())) {
                return artifact;
            }
        }
        throw new AssertionError("provenance lists no artifact for " + file);
    }

    private static byte[] read(String file) throws IOException {
        try (InputStream in = SchemaBundle.getDefault().open(file)) {
            assertNotNull(in, "missing bundled file " + file);
            return in.readAllBytes();
        }
    }

    private static JsonNode resource(String name) throws IOException {
        try (InputStream in = DraftProvenanceTest.class.getResourceAsStream(
                "/org/fairbioheaders/schemas/" + name)) {
            assertNotNull(in, "missing resource " + name);
            return JSON.readTree(in);
        }
    }
}
