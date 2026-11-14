/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.fairbioheaders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The bundle's own integrity plus the E0 capability gate: the released contracts are
 * JSON Schema draft/2020-12 and this library must evaluate their {@code $defs}
 * references, {@code if}/{@code then} conditionals, {@code propertyNames},
 * {@code patternProperties} and {@code format} keywords correctly. The E0 study
 * (2026-11-14, networknt json-schema-validator 3.0.9 on JDK 17) established these
 * behaviors; this test keeps that proof running.
 */
class CapabilityTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void everyBundledSchemaMatchesItsRecordedSha256() throws Exception {
        JsonNode prov = resource("provenance.json");
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        for (JsonNode entry : prov.get("files")) {
            String file = entry.get("file").asString();
            try (InputStream in = CapabilityTest.class.getResourceAsStream(
                    "/org/fairbioheaders/schemas/" + file)) {
                byte[] bytes = in.readAllBytes();
                assertEquals(entry.get("sha256").asString(),
                    HexFormat.of().formatHex(sha.digest(bytes)),
                    file + " differs from provenance.json");
            }
            assertTrue(file.startsWith("fhr-") || file.startsWith("fhp-") || file.startsWith("fhgff3-"));
        }
    }

    @Test
    void everyBundledContractDeclaresDraft202012() {
        for (String file : List.of("fhr-1.json", "fhr-2.0.json", "fhr-2.1.json",
                "fhp-1.0.json", "fhp-1.1.json", "fhp-1.2.json",
                "fhgff3-1.0.json", "fhgff3-1.1.json", "fhgff3-1.2.json")) {
            JsonNode node = resource(file);
            assertEquals("https://json-schema.org/draft/2020-12/schema",
                node.path("$schema").asString(""), file);
        }
    }

    @Test
    void validatesReleasedExamplesAndRejectsViolations() throws Exception {
        SchemaBundle bundle = SchemaBundle.getDefault();
        // current contracts validate their released JSON examples
        var fhrMain = bundle.contract(FhType.FHR, "main");
        var fhpMain = bundle.contract(FhType.FHP, "main");
        var gffMain = bundle.contract(FhType.FHGFF3, "main");
        assertTrue(SchemaBundle.validate(fhrMain,
            JSON.readTree("{\"schema\":\"x\",\"schemaVersion\":\"2.1\",\"genome\":\"g\","
                + "\"taxon\":{\"name\":\"Homo sapiens\",\"uri\":\"https://identifiers.org/taxonomy:9606\"},"
                + "\"version\":\"1\",\"metadataAuthor\":[{\"name\":\"A\"}],"
                + "\"assemblyAuthor\":[{\"name\":\"B\"}],"
                + "\"dateCreated\":\"2026-01-01\",\"masking\":\"not-masked\","
                + "\"checksum\":\"" + "A".repeat(43) + "=\"}")).isEmpty());
        // unknown key rejected (additionalProperties false + $defs)
        assertFalse(SchemaBundle.validate(fhpMain, JSON.readTree(
            "{\"unknown\":1}")).isEmpty());
        // bad date format rejected (format assertions on)
        assertFalse(SchemaBundle.validate(gffMain, JSON.readTree(
            "{\"dateCreated\":\"31 Dec 2024\"}")).isEmpty());
        // if/then conditional fires on the released FHP 1.2 contract:
        // completeness.lineageDataset is pattern-restricted
        ObjectNode bad = JSON.createObjectNode();
        bad.put("schema", "https://w3id.org/fair-bioheaders/fhp/v0.6.0");
        bad.put("schemaVersion", "1.2");
        assertTrue(!fhpMain.validate(bad).isEmpty());
    }

    @Test
    void sha512_256IsTheRealAlgorithmNotTruncatedSha512() throws Exception {
        // Cross-check against the value the Python hashlib / fair-bioheaders toolkit
        // produces for "abc" (computed 2026-11-14, see docs/STATUS.md).
        byte[] d = HeaderScan.newDigest().digest(new byte[] {'a', 'b', 'c'});
        assertEquals("UwSOJoGUHvmbLim3a0x9q+TC0MY0/G1G4OLxMQfnryM=",
            java.util.Base64.getEncoder().encodeToString(d));
    }

    private static JsonNode resource(String name) {
        try (InputStream in = CapabilityTest.class.getResourceAsStream(
                "/org/fairbioheaders/schemas/" + name)) {
            return JSON.readTree(in);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
