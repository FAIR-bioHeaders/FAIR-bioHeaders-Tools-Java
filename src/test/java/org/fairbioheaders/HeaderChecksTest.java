/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.fairbioheaders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/**
 * Header-level checks from the c2ee18e review: the excluded line must be the record's checksum
 * (J1), a header type only in its own containers (J2), the plain scalar {@code =} (R12), the fht
 * near-miss wording (Java#2) and R14 interior whitespace.
 */
class HeaderChecksTest {

    private static final String BASE = """
        ;~schema: https://w3id.org/fair-bioheaders/fhr/v0.5.0
        ;~schemaVersion: '2.1'
        ;~genome: Synthetic genome
        ;~taxon:
        ;~  name: Homo sapiens
        ;~  uri: https://identifiers.org/taxonomy:9606
        ;~version: 1.0.0
        ;~metadataAuthor:
        ;~- name: Someone
        ;~assemblyAuthor:
        ;~- name: Someone
        ;~dateCreated: '2026-10-08'
        ;~masking: not-masked
        """;

    /** Fill the excluded line with the digest the scan computes, so R3 holds and only J1 can fail. */
    private static byte[] sealHidden(String text) {
        String slot = "A".repeat(42) + "Q=";
        String computed = HeaderScan.of(Kind.FASTA, text.replace("@@", slot).getBytes(StandardCharsets.UTF_8))
            .computedChecksum();
        return text.replace("@@", computed).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void aChecksumLineHiddenInsideAScalarIsNotTheRecordsChecksum() {
        String[] hidden = {
            ";~x-note: \"start\n;~checksum: @@\n;~end\"\n",
            ";~x-note: 'start\n;~checksum: @@\n;~end'\n",
            ";~x-note: {a: 1,\n;~checksum: @@\n;~}\n",
        };
        for (String h : hidden) {
            byte[] file = sealHidden(BASE + h + ";~\"checksum\": " + "A".repeat(43) + "=\n>c1\nACGT\n");
            VerifyResult r = new BioHeaders().verify(file, Kind.FASTA);
            assertEquals(r.statedChecksum(), r.computedChecksum(), "the decoy line itself matches");
            assertEquals(VerifyResult.Status.INVALID, r.status(), h);
            assertTrue(r.hasRule("R2"), r.issues().toString());
        }
    }

    @Test
    void theOrdinaryChecksumLineStillPasses() {
        byte[] file = Fixtures.fhrFasta(">c\nA\n");
        VerifyResult r = new BioHeaders().verify(file, Kind.FASTA);
        assertTrue(r.isValid(), r.issues().toString());
        assertEquals(null, BioHeaders.checksumLineIdentity(" checksum: '" + r.statedChecksum() + "'",
            r.metadata()));
        assertTrue(BioHeaders.checksumLineIdentity(" checksum: " + "B".repeat(43) + "=",
            r.metadata()) != null);
    }

    private static String fhp() {
        return """
            schema: https://w3id.org/fair-bioheaders/fhp/v0.6.0
            schemaVersion: '1.2'
            proteome: x
            """;
    }

    @Test
    void aHeaderTypeIsAcceptedOnlyInItsOwnContainers() {
        String fhr = "schema: https://w3id.org/fair-bioheaders/fhr/v0.6.0\nschemaVersion: '2.1'\n";
        String fhgff3 = "schema: https://w3id.org/fair-bioheaders/fhgff3/v0.6.0\nschemaVersion: '1.2'\n";
        Object[][] wrong = {
            {Kind.GFF3, fhr, "FHR metadata is incompatible with GFF3; expected FHGFF3 metadata"},
            {Kind.GFF3, fhp(), "FHP metadata is incompatible with GFF3; expected FHGFF3 metadata"},
            {Kind.GFA, fhp(), "FHP metadata is incompatible with GFA; expected FHR metadata"},
            {Kind.GFA, fhgff3, "FHGFF3 metadata is incompatible with GFA; expected FHR metadata"},
            {Kind.FASTA, fhgff3, "FHGFF3 metadata is incompatible with FASTA; expected FHR or FHP metadata"},
        };
        for (Object[] c : wrong) {
            Kind kind = (Kind) c[0];
            byte[] file = Fixtures.sealed(kind, (String) c[1], kind == Kind.GFF3 ? "c\t.\tgene\t1\t2\t.\t+\t.\tID=g\n"
                : kind == Kind.GFA ? "S\t1\tACGT\n" : ">c\nA\n");
            VerifyResult r = new BioHeaders().verify(file, kind);
            assertEquals(VerifyResult.Status.INVALID, r.status());
            assertTrue(r.issues().stream().anyMatch(i -> i.rule().equals("container")
                && i.message().equals(c[2])), r.issues().toString());
        }
        // The right containers raise no container error.
        assertFalse(new BioHeaders().verify(Fixtures.fhrFasta(">c\nA\n"), Kind.FASTA).hasRule("container"));
        assertFalse(new BioHeaders().verify(Fixtures.sealed(Kind.GFA, fhr, "S\t1\tA\n"), Kind.GFA)
            .hasRule("container"));
        assertFalse(new BioHeaders().verify(Fixtures.sealedFasta(fhp(), ">p\nM\n"), Kind.FASTA)
            .hasRule("container"));
    }

    @Test
    void aPlainEqualsSignIsAnR12Disagreement() {
        ScanException e = assertThrows(ScanException.class, () -> PortableYaml.portablePlainTag("="));
        assertEquals("R12", e.rule());
        assertEquals(PortableYaml.Tag.STR, PortableYaml.portablePlainTag("x="));
        assertEquals(PortableYaml.Tag.STR, PortableYaml.portablePlainTag("=="));
        VerifyResult r = new BioHeaders().verify(
            Fixtures.fhrFasta(">c\nA\n", "x-note: ="), Kind.FASTA);
        assertTrue(r.hasRule("R12"), r.issues().toString());
        assertTrue(new BioHeaders().verify(Fixtures.fhrFasta(">c\nA\n", "x-note: '='"), Kind.FASTA)
            .isValid());
    }

    @Test
    void anFhtNearMissNamesFht() throws Exception {
        SchemaSelection selection = new SchemaSelection(SchemaBundle.getDefault());
        Issue warning = selection.select("https://w3id.org/fair-bioheaders/fht/v0.7.0").warning();
        assertTrue(warning.message().contains("read as FHR, not FHT,"), warning.message());
        Issue fhp = selection.select("https://w3id.org/fair-bioheaders/fhp/v9.9").warning();
        assertTrue(fhp == null || !fhp.message().contains("not FHT"));
    }

    @Test
    void whitespaceAnywhereInALinkMeansNoAcceptedScheme() {
        assertTrue(UriSchemes.schemeAccepted("https://example.org/a"));
        for (String v : new String[] {"https://example.org/a b", "https://example.org/a b",
                "https://exa mple.org", "doi:10.1000/a b", "urn:x\tb", " https://example.org"}) {
            assertFalse(UriSchemes.schemeAccepted(v), v);
        }
    }

    @Test
    void authorLinksAreCheckedToo() {
        byte[] file = Fixtures.sealedFasta("""
            schema: https://w3id.org/fair-bioheaders/fhr/v0.6.0
            schemaVersion: '2.1'
            genome: g
            taxon:
              name: Homo sapiens
              uri: https://identifiers.org/taxonomy:9606
            version: 1.0.0
            metadataAuthor:
            - name: A
              uri: javascript:alert(1)
            assemblyAuthor:
            - name: B
            dateCreated: 2026-01-01
            masking: not-masked
            """, ">c\nA\n");
        VerifyResult r = new BioHeaders().verify(file, Kind.FASTA);
        assertTrue(r.warnings().stream().anyMatch(i -> i.rule().equals("R14")
            && i.message().contains("metadataAuthor[].uri")), r.issues().toString());
    }
}
