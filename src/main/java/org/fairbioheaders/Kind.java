/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.fairbioheaders;

import java.nio.charset.StandardCharsets;

/** File container a header is embedded in: FASTA (FHR/FHP), GFA (FHR) or GFF3 (FHGFF3). */
public enum Kind {
    FASTA(";~"),
    GFA("#~"),
    GFF3("#~");

    private final String prefixText;
    private final byte[] prefix;

    Kind(String prefixText) {
        this.prefixText = prefixText;
        this.prefix = prefixText.getBytes(StandardCharsets.UTF_8);
    }

    /** The FHR line prefix, {@code ;~} in FASTA and {@code #~} in GFA and GFF3 (FORMAT.md R2). */
    public byte[] prefix() {
        return prefix.clone();
    }

    public String prefixText() {
        return prefixText;
    }
}
