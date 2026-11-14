/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.fairbioheaders;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import tools.jackson.databind.JsonNode;

/**
 * Entry point for reading, checksum-verifying and schema-validating FAIR-bioHeaders
 * headers (FHR, FHP, FHGFF3) in FASTA, GFA and GFF3 files.
 *
 * <p>This slice covers the file-bound header: exact-byte SHA-512/256 coverage
 * (FORMAT.md R1-R7, R10 and FHGFF3.md G1), JSON-compatible YAML loading (R8, R12,
 * R13), schema selection (SCHEMA-VERSIONS.md V1-V6) and validation against the
 * released draft/2020-12 contracts bundled in this jar, plus the R14 link-scheme
 * and R11 record-marker warnings. It does not write, combine, strip or convert
 * headers, and it does not validate GFF3 feature lines; see docs/STATUS.md.
 */
public final class BioHeaders {

    private final SchemaBundle bundle;
    private final SchemaSelection selection;

    public BioHeaders() {
        this(SchemaBundle.getDefault());
    }

    public BioHeaders(SchemaBundle bundle) {
        this.bundle = bundle;
        this.selection = new SchemaSelection(bundle);
    }

    /** Verify a file on disk; the container is inferred from the file name. */
    public VerifyResult verifyFile(Path file) throws IOException {
        return verify(readAll(file), kindFor(file.getFileName().toString()));
    }

    /** Verify already-read (decompressed) bytes of a given container. */
    public VerifyResult verify(byte[] data, Kind kind) {
        List<Issue> issues = new ArrayList<>();
        String stated = null;
        String computed = null;
        JsonNode metadata = null;
        try {
            HeaderScan.checkBom(kind, data);
            HeaderScan scan = HeaderScan.of(kind, data);
            stated = scan.statedChecksum();
            computed = scan.computedChecksum();
            if (!computed.equals(stated)) {
                issues.add(Issue.error("R3",
                    "checksum mismatch: the file states " + stated + " but the covered bytes hash to "
                        + computed));
            }
            if (kind == Kind.FASTA) {
                for (String line : scan.rawHeaderLines()) {
                    if (line.indexOf('>') >= 0 || line.indexOf('@') >= 0) {
                        issues.add(Issue.warning("R11",
                            "FASTA header line contains a raw record marker: " + line));
                    }
                }
            }
            metadata = PortableYaml.loadSafe(scan.yamlText());
            FhType type;
            String release;
            try {
                SchemaSelection.Selection sel = selection.selectRecord(metadata);
                type = sel.type();
                release = sel.release();
                if (sel.warning() != null) {
                    issues.add(sel.warning());
                }
            } catch (SchemaSelection.Unsupported e) {
                issues.add(Issue.error("V4", e.getMessage()));
                return new VerifyResult(VerifyResult.Status.UNSUPPORTED_VERSION,
                    stated, computed, metadata, issues);
            }
            issues.addAll(SchemaBundle.validate(bundle.contract(type, release), metadata));
            UriSchemes.collect(type, metadata, issues);
        } catch (ScanException e) {
            issues.add(Issue.error(e.rule(), e.getMessage()));
        }
        boolean invalid = issues.stream().anyMatch(i -> i.severity() == Severity.ERROR);
        return new VerifyResult(invalid ? VerifyResult.Status.INVALID : VerifyResult.Status.VALID,
            stated, computed, metadata, issues);
    }

    /** Infer the container from a file name (extension comparisons are case-insensitive). */
    public static Kind kindFor(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".gz")) {
            lower = lower.substring(0, lower.length() - 3);
        }
        if (lower.endsWith(".gfa") || lower.endsWith(".gfa.gz")) {
            return Kind.GFA;
        }
        if (lower.endsWith(".gff3")) {
            return Kind.GFF3;
        }
        return Kind.FASTA;
    }

    /**
     * Read a file, inflating gzip (including multi-member/bgzip streams, which are
     * concatenations of gzip members) transparently: compression is transport, so the
     * checksum covers the decompressed bytes (R1).
     */
    public static byte[] readAll(Path file) throws IOException {
        byte[] raw = Files.readAllBytes(file);
        return inflateIfNeeded(raw);
    }

    static byte[] inflateIfNeeded(byte[] raw) throws IOException {
        return Gunzip.isGzip(raw) ? Gunzip.decompress(raw) : raw;
    }
}
