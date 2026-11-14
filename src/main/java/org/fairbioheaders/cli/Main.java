/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.fairbioheaders.cli;

import java.io.IOException;
import java.nio.file.Path;

import org.fairbioheaders.BioHeaders;
import org.fairbioheaders.Issue;
import org.fairbioheaders.VerifyResult;

/**
 * Minimal command line: {@code bioheaders-java verify FILE...} and
 * {@code bioheaders-java version}. Exits 0 when every file verifies, 1 otherwise.
 * This is a convenience wrapper over {@link BioHeaders}; the reference CLI with
 * combine/strip/convert stays the Python toolkit ({@code pip install fair-bioheaders}).
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            usage();
            System.exit(2);
        }
        switch (args[0]) {
            case "version":
                System.out.println("fair-bioheaders-java 0.1.0-SNAPSHOT "
                    + "(bundled schemas: current = " + org.fairbioheaders.SchemaBundle.getDefault().currentRelease() + ")");
                System.exit(0);
                break;
            case "verify": {
                if (args.length < 2) {
                    usage();
                    System.exit(2);
                }
                BioHeaders verifier = new BioHeaders();
                boolean allValid = true;
                for (int i = 1; i < args.length; i++) {
                    Path file = Path.of(args[i]);
                    VerifyResult result;
                    try {
                        result = verifier.verifyFile(file);
                    } catch (IOException e) {
                        allValid = false;
                        System.out.println(args[i] + ": cannot read file: " + e.getMessage());
                        continue;
                    }
                    for (Issue issue : result.issues()) {
                        System.out.println(args[i] + ": " + issue);
                    }
                    if (result.isValid()) {
                        System.out.println(args[i] + ": checksum verified; metadata valid ("
                            + result.computedChecksum() + ")");
                    } else {
                        allValid = false;
                        System.out.println(args[i] + ": " + result.status());
                    }
                }
                System.exit(allValid ? 0 : 1);
                break;
            }
            default:
                usage();
                System.exit(2);
        }
    }

    private static void usage() {
        System.out.println("""
            usage: bioheaders-java <command> [args]

            commands:
              verify FILE...   read, checksum-verify and schema-validate a
                               headed FASTA (.fasta/.fa/.fna/.fhr/.fhp), GFA (.gfa)
                               or GFF3 (.gff3) file; gzip and bgzip are decompressed
                               for the checksum (FORMAT.md R1)
              version          print this library version and the bundled schema release
            """);
    }
}
