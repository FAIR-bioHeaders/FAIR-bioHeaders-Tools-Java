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
 * Entry point for reading, checksum-verifying and schema-validating FAIR-bioHeaders headers
 * (released: FHR, FHP, FHGFF3) in FASTA, GFA and GFF3 files.
 *
 * <p>This library covers the file-bound header: exact-byte SHA-512/256 coverage (FORMAT.md
 * R1-R7, R10 and FHGFF3.md G1), JSON-compatible YAML loading (R8, R12, R13), schema selection
 * (SCHEMA-VERSIONS.md V1-V6, plus V7 on explicit opt-in) and validation against the
 * draft/2020-12 contracts bundled in this jar, plus the R14 link-scheme and R11 record-marker
 * warnings. It does not write, combine, strip or convert headers, and it does not validate GFF3
 * feature lines; see docs/STATUS.md and docs/CAPABILITY-MATRIX.md.
 *
 * <p>An instance built with {@link Options#defaults()} - which is what {@code new BioHeaders()}
 * uses unless the environment opts in - reads released contracts only. A draft type of the
 * family (FHT, FHGM) becomes readable only through {@link Options.Builder#enableDraftContracts(
 * boolean)} and only against the pinned revision its registry entry names, and every record read
 * that way says so. docs/STATUS.md keeps the current support and non-support list.
 */
public final class BioHeaders {

    private final SchemaBundle bundle;
    private final TypeRegistry types;
    private final SchemaSelection selection;
    private final Options options;

    /** The released-contract reader; the environment may opt in to the rule V7 draft lane. */
    public BioHeaders() {
        this(Options.fromEnvironment());
    }

    public BioHeaders(SchemaBundle bundle) {
        this(bundle, bundle.options());
    }

    /** A reader configured by {@code options}; released contracts unless the lane is opted in. */
    public BioHeaders(Options options) {
        this(new SchemaBundle(options), options);
    }

    private BioHeaders(SchemaBundle bundle, Options options) {
        this(bundle, bundle.types(), options);
    }

    private BioHeaders(SchemaBundle bundle, TypeRegistry types, Options options) {
        this.bundle = bundle;
        this.types = types;
        this.options = options;
        this.selection = new SchemaSelection(bundle, types);
    }

    /** The configuration this reader was built with. */
    public Options options() {
        return options;
    }

    /**
     * The header types this reader can select: the released ones, plus any draft it was opted
     * into. Names of drafts here are not support claims - see docs/CAPABILITY-MATRIX.md.
     */
    public List<FhType> readableTypes() {
        return types.types();
    }

    /**
     * The draft types the bundled registry declares, whether or not this reader was opted in:
     * the discovery call for {@link Options.Builder#enableDraft(String)}.
     */
    public List<String> declaredDraftTypes() {
        return bundle.declaredDraftTypes();
    }

    /** Verify a file on disk; the container is inferred from the file name. */
    public VerifyResult verifyFile(Path file) throws IOException {
        return verifyFile(file, null);
    }

    /**
     * Verify a file on disk and, when the caller supplies parent files, its {@code derivedFrom}
     * links too. {@code null} is exactly {@link #verifyFile(Path)}.
     */
    public VerifyResult verifyFile(Path file, ParentLinks.ParentSource parents) throws IOException {
        return verify(readAll(file), kindFor(file.getFileName().toString()), parents);
    }

    /** Verify already-read (decompressed) bytes of a given container. */
    public VerifyResult verify(byte[] data, Kind kind) {
        return verify(data, kind, null);
    }

    /**
     * Verify bytes and, where the caller hands over the parent files, check the record's
     * {@code derivedFrom} links too (see {@link ParentLinks}).
     *
     * <p>Passing {@code null} is exactly {@link #verify(byte[], Kind)}. With a source, each link
     * whose recomputed parent checksum differs becomes an ERROR under the label
     * {@value ParentLinks#RULE}; a link whose parent was not supplied stays a warning-free
     * {@code UNCHECKED} verdict and changes no verdict, because an absent parent is not evidence
     * about the child. The distinction - and the requirement that a failing link be reported
     * rather than passed - is the SCHEMA/FORMAT position the FHT development suite states as its
     * rule C3.
     */
    public VerifyResult verify(byte[] data, Kind kind, ParentLinks.ParentSource parents) {
        List<Issue> issues = new ArrayList<>();
        String stated = null;
        String computed = null;
        JsonNode metadata = null;
        List<ParentLinks.Verdict> verdicts = List.of();
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
            UriSchemes.collect(types.def(type), metadata, issues);
            if (parents != null) {
                verdicts = ParentLinks.check(metadata, parents);
                issues.addAll(ParentLinks.issues(verdicts));
            }
        } catch (ScanException e) {
            issues.add(Issue.error(e.rule(), e.getMessage()));
        }
        boolean invalid = issues.stream().anyMatch(i -> i.severity() == Severity.ERROR);
        return new VerifyResult(invalid ? VerifyResult.Status.INVALID : VerifyResult.Status.VALID,
            stated, computed, metadata, issues, verdicts);
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
