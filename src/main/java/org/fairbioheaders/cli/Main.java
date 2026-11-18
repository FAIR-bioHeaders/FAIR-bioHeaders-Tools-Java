/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.fairbioheaders.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.fairbioheaders.BioHeaders;
import org.fairbioheaders.Issue;
import org.fairbioheaders.Options;
import org.fairbioheaders.ParentLinks;
import org.fairbioheaders.SchemaBundle;
import org.fairbioheaders.VerifyResult;

/**
 * Minimal command line: {@code bioheaders-java verify [flags] FILE...} and
 * {@code bioheaders-java version}. Exit 0 when every file verifies, 1 otherwise, 2 on a usage error.
 *
 * <p>This is a convenience wrapper over {@link BioHeaders}; the reference CLI with
 * combine/strip/convert stays the Python toolkit ({@code pip install fair-bioheaders}).
 *
 * <p>{@link #run(String[], PrintStream)} holds the behaviour and returns the exit code;
 * {@link #main(String[])} only turns that into {@code System.exit}, so the CLI is testable from
 * Java without spawning a process ({@code CliTest}).
 */
public final class Main {

    /** The version string this build reports; {@code 0.1.0-SNAPSHOT} is a development marker. */
    static final String VERSION = "0.1.0-SNAPSHOT";

    private Main() {
    }

    public static void main(String[] args) {
        // The schema validator logs through the SLF4J API, and no logging backend ships with this
        // command; without this, SLF4J prints a three-line "no providers" notice on every run.
        if (System.getProperty("slf4j.internal.verbosity") == null) {
            System.setProperty("slf4j.internal.verbosity", "ERROR");
        }
        System.exit(run(args, System.out));
    }

    /** Parse, verify and report; returns the process exit code. */
    public static int run(String[] args, PrintStream out) {
        List<String> files = new ArrayList<>();
        List<String> parents = new ArrayList<>();
        List<String> draftNames = new ArrayList<>();
        boolean draftFlag = false;
        String command = null;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "verify", "version", "help" -> command = command == null ? arg : command;
                case "--experimental-drafts" -> draftFlag = true;
                case "--draft" -> {
                    if (i + 1 >= args.length) {
                        out.println("--draft needs a type name");
                        return 2;
                    }
                    draftNames.add(args[++i]);
                }
                case "--parent" -> {
                    if (i + 1 >= args.length) {
                        out.println("--parent needs a file");
                        return 2;
                    }
                    parents.add(args[++i]);
                }
                case "-h", "--help" -> {
                    usage(out);
                    return 0;
                }
                default -> {
                    if (arg.startsWith("-")) {
                        print(out, "unknown flag: " + arg);
                        usage(out);
                        return 2;
                    }
                    files.add(arg);
                }
            }
        }

        if (command == null) {
            usage(out);
            return 2;
        }
        if (command.equals("help")) {
            usage(out);
            return 0;
        }
        if (command.equals("version")) {
            version(out);
            return 0;
        }
        if (files.isEmpty()) {
            out.println("verify needs at least one file");
            usage(out);
            return 2;
        }

        Options.Builder builder = Options.builder();
        if (draftFlag) {
            builder.enableDraftContracts(true);
        }
        builder.enableDrafts(draftNames);
        Options options = draftFlag || !draftNames.isEmpty() ? builder.build() : Options.fromEnvironment();
        BioHeaders verifier = new BioHeaders(options);
        if (options.draftContractsEnabled()) {
            out.println("note: the rule V7 draft lane is ON; a record read through an unreleased"
                + " contract carries a V7 warning naming the pinned commit and SHA-256, and is not"
                + " a released-contract result.");
        }
        ParentLinks.ParentSource source;
        try {
            source = parentSource(parents);
        } catch (IllegalArgumentException e) {
            print(out, "cannot use --parent: " + e.getMessage());
            return 2;
        }
        boolean allValid = true;
        for (String name : files) {
            VerifyResult result;
            try {
                result = verifier.verifyFile(Path.of(name), source);
            } catch (IOException | UncheckedIOException | IllegalArgumentException e) {
                allValid = false;
                print(out, name + ": cannot read file: " + e.getMessage());
                continue;
            } catch (OutOfMemoryError e) {
                // Verification streams the file and holds only the bounded header, so this is a
                // pathological input or a very small heap; report it as a failure, not a crash.
                allValid = false;
                print(out, name + ": cannot verify file: ran out of memory (" + e.getMessage()
                    + "); raise the heap with -Xmx or check the input");
                continue;
            }
            for (Issue issue : result.issues()) {
                print(out, name + ": " + issue);
            }
            for (ParentLinks.Verdict verdict : result.parentLinkVerdicts()) {
                print(out, name + ": parent link " + verdict.index() + " (" + verdict.relationship()
                    + " " + verdict.headerType() + ") " + verdict.state());
            }
            if (result.isValid()) {
                print(out, name + ": checksum verified; metadata valid ("
                    + result.computedChecksum() + ")");
            } else {
                allValid = false;
                print(out, name + ": " + result.status());
            }
        }
        return allValid ? 0 : 1;
    }

    /**
     * Print one line with terminal control characters made visible. File names and header values
     * come from untrusted input; printed raw, an ESC or CR in them could rewrite the terminal or
     * forge a line of this report.
     */
    static void print(PrintStream out, String line) {
        out.println(printable(line));
    }

    /** {@code text} with C0/C1 controls, DEL, line/paragraph separators and bidi controls escaped. */
    static String printable(String text) {
        StringBuilder sb = null;
        for (int k = 0; k < text.length(); k++) {
            char c = text.charAt(k);
            boolean control = c < 0x20 || (c >= 0x7F && c <= 0x9F) || c == '\u2028' || c == '\u2029'
                || (c >= '\u202A' && c <= '\u202E') || (c >= '\u2066' && c <= '\u2069')
                || c == '\u200E' || c == '\u200F';
            if (control && sb == null) {
                sb = new StringBuilder(text.length() + 8).append(text, 0, k);
            }
            if (sb != null) {
                if (control) {
                    sb.append(c <= 0xFF ? String.format("\\x%02X", (int) c) : String.format("\\u%04X", (int) c));
                } else {
                    sb.append(c);
                }
            }
        }
        return sb == null ? text : sb.toString();
    }

    /** What {@code version} prints, as one line plus the draft registrations. */
    static void version(PrintStream out) {
        SchemaBundle bundle = SchemaBundle.getDefault();
        StringBuilder sb = new StringBuilder("fair-bioheaders-java ").append(VERSION)
            .append(" (bundled schemas: current = ").append(bundle.currentRelease());
        List<SchemaBundle.Draft> drafts = bundle.drafts();
        if (!drafts.isEmpty()) {
            sb.append("; registered drafts (rule V7, opt-in, not released):");
            for (SchemaBundle.Draft draft : drafts) {
                sb.append(' ').append(draft.type()).append('@')
                    .append(draft.commit(), 0, 7)
                    .append(" (sha256 ").append(draft.sha256(), 0, 12).append(" of ")
                    .append(draft.file()).append(" from ")
                    .append(draft.repository()).append(" main)");
            }
        }
        out.println(sb.append(')').toString());
    }

    /**
     * Resolves {@code --parent} arguments. Two forms, because a link states only a checksum:
     *
     * <ul>
     *   <li>{@code FILE} - a candidate parent. A link whose stated checksum equals the digest of a
     *       candidate is {@code CHECKED_PASS}; a link no candidate satisfies stays
     *       {@code UNCHECKED}, which is the truth about it.</li>
     *   <li>{@code CHECKSUM=FILE} - an explicit pairing: "this file is the parent that link
     *       names". Then a difference is a real {@code CHECKED_FAIL}, which is the only way a
     *       caller can turn a stale link into a rejection.</li>
     * </ul>
     */
    private static ParentLinks.ParentSource parentSource(List<String> specs) {
        if (specs.isEmpty()) {
            return null;
        }
        Map<String, ParentLinks.Parent> explicit = new java.util.LinkedHashMap<>();
        List<ParentLinks.Parent> candidates = new ArrayList<>();
        for (String spec : specs) {
            // A checksum is exactly 44 characters ending in '=', so the explicit form is the one
            // where character 45 is the separator; a plain path (however it is spelled) never is.
            boolean explicitForm = spec.length() > 45 && spec.charAt(44) == '='
                && spec.charAt(43) == '=';
            String stated = explicitForm ? spec.substring(0, 44) : null;
            String path = explicitForm ? spec.substring(45) : spec;
            ParentLinks.Parent parent = load(Path.of(path));
            if (stated == null) {
                candidates.add(parent);
            } else {
                explicit.put(stated, parent);
            }
        }
        // Each candidate is hashed once, streamed, the first time a link asks; an unreadable or
        // unheaded candidate simply satisfies no link.
        Map<ParentLinks.Parent, Optional<String>> digests = new java.util.IdentityHashMap<>();
        return (checksum, headerType, linkIndex) -> {
            ParentLinks.Parent named = explicit.get(checksum);
            if (named != null) {
                return Optional.of(named);
            }
            for (ParentLinks.Parent candidate : candidates) {
                Optional<String> digest = digests.computeIfAbsent(candidate, c -> {
                    try {
                        return Optional.of(ParentLinks.checksumOf(c));
                    } catch (IllegalArgumentException | UncheckedIOException e) {
                        return Optional.empty();
                    }
                });
                if (digest.isPresent() && checksum.equals(digest.get())) {
                    return Optional.of(candidate);
                }
            }
            return Optional.empty();
        };
    }

    private static ParentLinks.Parent load(Path file) {
        if (!java.nio.file.Files.isReadable(file)) {
            throw new IllegalArgumentException("cannot read --parent file " + file);
        }
        return ParentLinks.Parent.ofFile(file);
    }

    private static void usage(PrintStream out) {
        out.println("""
            usage: java -jar fair-bioheaders-java.jar <command> [flags] [args]

            commands:
              verify [flags] FILE...   read, checksum-verify and schema-validate a
                                       headed FASTA (.fa .fasta .fas .fna .faa .ffn
                                       .frn .fsa .mpfa), GFA (.gfa) or GFF3 (.gff3
                                       .gff) file, optionally ending .gz or .bgz; gzip
                                       and bgzip are decompressed as the file streams
                                       (FORMAT.md R1)
              version                  print this library version, the bundled schema
                                       release and any registered draft contract
              help                     print this text

            verify flags:
              --experimental-drafts    opt in to the rule V7 draft lane: read the pinned
                                       unreleased contract (see `version`) instead of
                                       falling back to rule V5. Records read this way
                                       carry a V7 warning naming the commit and SHA-256
                                       and are not released-contract results. The same
                                       opt-in is available through
                                       -Dorg.fairbioheaders.experimental.drafts=1 or
                                       FAIR_BIOHEADERS_EXPERIMENTAL_DRAFTS=1.
              --draft TYPE             opt in to one named draft only (repeatable), e.g.
                                       --draft FHT; implies --experimental-drafts
              --parent FILE            offer a candidate parent file (repeatable). A
                                       link whose stated checksum equals a candidate's
                                       digest is CHECKED_PASS; a link no candidate
                                       satisfies stays UNCHECKED, never "verified".
              --parent CHECKSUM=FILE   name the parent of a link explicitly (repeatable):
                                       "this file is the parent that checksum refers to".
                                       A difference is then CHECKED_FAIL, which is a
                                       rejection - the only way a stale link can be
                                       reported as one.
            """);
    }
}
