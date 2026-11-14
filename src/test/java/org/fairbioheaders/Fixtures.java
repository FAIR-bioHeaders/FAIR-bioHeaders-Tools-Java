/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.fairbioheaders;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Header documents for tests, sealed so their own checksum line is correct and, where the test is
 * about something other than the contract, schema-valid too.
 *
 * <p>{@link #sealed} writes the header with a placeholder checksum line, asks the reader for the
 * digest of the covered bytes, then substitutes it. A failure in a test that uses one of these is
 * therefore about the rule under test, never about file integrity. The digest comes from the
 * reader's own recomputation; SHA-512/256 over exact bytes is pinned by {@code ChecksumTest} and
 * the 228 shared vectors.
 */
public final class Fixtures {

    /** A well-formed, canonical checksum value that no fixture's link is expected to match. */
    public static final String ABSENT = "A".repeat(43) + "=";
    private static final String SLOT = "A".repeat(42) + "Q=";

    public static final String FHT_CITATION =
        "https://raw.githubusercontent.com/FAIR-bioHeaders/FHT-Specification/main/fht.json";

    private static final String FHR = """
        schema: https://w3id.org/fair-bioheaders/fhr/v0.6.0
        schemaVersion: '2.1'
        genome: fixture-genome
        taxon:
          name: Homo sapiens
          uri: https://identifiers.org/taxonomy:9606
        version: 1.0.0
        metadataAuthor:
        - name: Synthetic fixture author
        assemblyAuthor:
        - name: Synthetic fixture author
        dateCreated: 2026-01-01
        masking: not-masked
        """;

    /**
     * A minimal valid FHGFF3 1.2 header without a genome link: its {@code derivedFrom} entry is
     * what points at the annotated FHR file, which is also what a parent-link verdict is about.
     */
    private static final String FHGFF3 = """
        schema: https://w3id.org/fair-bioheaders/fhgff3/v0.6.0
        schemaVersion: '1.2'
        annotation: fixture-annotation
        taxon:
          name: Homo sapiens
          uri: https://identifiers.org/taxonomy:9606
        version: 1.0.0
        metadataAuthor:
        - name: Synthetic fixture author
        dateCreated: 2026-01-01
        """;

    private static final String FHT = """
        schema: %s
        schemaVersion: '1.0'
        transcriptome: synthetic fixture transcript set
        assemblyType: de novo
        moleculeType: mRNA
        taxon:
          name: Homo sapiens
          uri: https://identifiers.org/taxonomy:9606
        version: 1.0.0
        metadataAuthor:
        - name: Synthetic fixture author
        dateCreated: 2026-11-14
        """.formatted(FHT_CITATION);

    private Fixtures() {
    }

    /** Prefix every YAML line as a header line of the given container. */
    public static String block(String prefix, String yaml) {
        StringBuilder sb = new StringBuilder();
        for (String line : yaml.split("\n", -1)) {
            if (!line.isEmpty()) {
                sb.append(prefix).append(' ').append(line).append('\n');
            }
        }
        return sb.toString();
    }

    public static String yaml(String base, String... extraLines) {
        List<String> lines = new ArrayList<>(List.of(base.stripTrailing().split("\n", -1)));
        lines.addAll(List.of(extraLines));
        return String.join("\n", lines) + "\n";
    }

    /** A sealed FASTA file of the given YAML and record text. */
    public static byte[] sealedFasta(String yaml, String records) {
        return sealed(Kind.FASTA, yaml, records);
    }

    /** A sealed file in any container: the checksum line closes the header block (R2). */
    public static byte[] sealed(Kind kind, String yaml, String records) {
        String prefix = kind.prefixText();
        String lead = kind == Kind.GFF3 ? "##gff-version 3\n" : "";
        String text = lead + block(prefix, yaml) + prefix + " checksum: " + SLOT + "\n" + records;
        String truth = new BioHeaders().verify(text.getBytes(StandardCharsets.UTF_8), kind)
            .computedChecksum();
        if (truth == null || truth.length() != 44) {
            throw new IllegalStateException("the sealed fixture does not even parse: " + text);
        }
        return (lead + block(prefix, yaml) + prefix + " checksum: " + truth + "\n" + records)
            .getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] fhrFasta(String records, String... extraLines) {
        return sealedFasta(yaml(FHR, extraLines), records);
    }

    public static byte[] fhgff3(String records, String... extraLines) {
        return sealed(Kind.GFF3, yaml(FHGFF3, extraLines), records);
    }

    public static byte[] fhtFasta(String records, String... extraLines) {
        return sealedFasta(yaml(FHT, extraLines), records);
    }

    /** A child FHGFF3 whose first link cites {@code stated} and whose second cites {@link #ABSENT}. */
    public static byte[] fhgff3LinkedTo(String stated) {
        return fhgff3("chr1\tfixture\tgene\t1\t10\t.\t+\t.\tID=gene1\n",
            "derivedFrom:",
            "- headerType: FHR",
            "  relationship: annotates",
            "  checksum: " + stated,
            "- headerType: FHR",
            "  relationship: filteredFrom",
            "  checksum: " + ABSENT);
    }

    /** The checksum value a sealed file states on its own root checksum line. */
    public static String statedChecksum(byte[] sealed) {
        return utf8(sealed).lines()
            .filter(line -> line.startsWith(";~ checksum: ") || line.startsWith("#~ checksum: "))
            .findFirst()
            .map(line -> line.substring(line.indexOf(": ", 2) + 2))
            .orElseThrow(() -> new IllegalStateException("no root checksum line"));
    }

    public static String utf8(byte[] data) {
        return new String(data, StandardCharsets.UTF_8);
    }
}
