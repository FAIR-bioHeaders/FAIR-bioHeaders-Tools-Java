/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.fairbioheaders;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One row of the header-type registry: the type, the {@code format: uri} fields rule R14
 * warns about, the containers a header of the type may be embedded in, and, for a draft, the pinned development bytes that give the type its
 * contract. Registry rows are data ({@code schemas/bundled.json}); this is what makes a new
 * family type additive instead of a change to the reader.
 */
record HeaderTypeDef(FhType type, List<String> uriFields, DraftPin draft, Set<Kind> containers) {

    HeaderTypeDef {
        Objects.requireNonNull(type, "type");
        uriFields = List.copyOf(uriFields);
        containers = Set.copyOf(containers);
        if (type.isDraft() && draft == null) {
            throw new IllegalStateException("draft type " + type.fileKey() + " has no pin");
        }
        if (!type.isDraft() && draft != null) {
            throw new IllegalStateException("released type " + type.fileKey() + " carries a draft pin");
        }
    }

    HeaderTypeDef(FhType type, List<String> uriFields, DraftPin draft) {
        this(type, uriFields, draft, Set.of());
    }

    static HeaderTypeDef released(FhType type, List<String> uriFields) {
        return new HeaderTypeDef(type, uriFields, null);
    }

    /**
     * Whether a header of this type may be embedded in {@code kind}: the row's {@code containers}
     * (the toolkit's {@code validate_header_family} table, docs/CAPABILITY-MATRIX.md section 2).
     * A row that declares none is not restricted.
     */
    boolean carriedBy(Kind kind) {
        return containers.isEmpty() || containers.contains(kind);
    }

    boolean isDraft() {
        return type.isDraft();
    }

    /**
     * The rule V7 pin of a draft type: the bytes, where they came from, and the citation.
     *
     * <p>The last seven components exist for a records-carrying draft ({@code embedded-records},
     * the FHGM shape). Such a type has no citation of its own in a file: it needs the transport
     * overlay that admits the records, the digest and the development commit that overlay is
     * pinned to, the extension key that holds the records, and the type whose header carries
     * them, alongside the record contract above. The overlay is pinned to its own commit
     * because its bytes can move on a revision the record contract did not touch. They stay
     * null for a file-bound draft, where rules V1-V7 supply the routing.
     */
    record DraftPin(String file, String citation, String repository, String commit, String sha256,
                    String retrieved, String source, String enable, String suiteCommit,
                    String suiteManifestSha256, String transportFile, String transportSha256,
                    String transportCommit, String modelsKey, String carrierFileKey) {

        DraftPin {
            for (String required : new String[] {file, citation, repository, commit, sha256, retrieved}) {
                if (required == null || required.isBlank()) {
                    throw new IllegalStateException("incomplete draft registry entry: " + file);
                }
            }
        }

        /**
         * A records-carrying draft must name every half of its transport. A row that declared the
         * record contract but not the overlay, the key or the carrier type would silently skip the
         * checks that make its suite worth running.
         */
        void requireCompleteFor(FhType.Transport transport) {
            if (transport != FhType.Transport.EMBEDDED_RECORDS) {
                return;
            }
            requireNamed(transportFile, "transportFile");
            requireNamed(transportSha256, "transportSha256");
            requireNamed(transportCommit, "transportCommit");
            requireNamed(modelsKey, "modelsKey");
            requireNamed(carrierFileKey, "carrierFileKey");
        }

        private void requireNamed(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalStateException("draft " + citation + " is an embedded-records type"
                    + " but its registry entry does not name '" + field + "'; refusing to read a"
                    + " records lane whose transport is only half declared");
            }
        }

        /**
         * The warning rule V7 requires on every record read through the lane: it names the
         * draft, the commit and the digest the bytes are pinned to, and says the result is not
         * a released-contract result. Worded as the rule's example words it
         * ({@code <type>: warning: schema ... (commit <full sha>, sha256 <digest> of those
         * bytes); read against that pinned draft, not a released schema}).
         */
        String warningMessage(String typeFileKey) {
            return typeFileKey + ": warning: schema " + citation + " is an unreleased draft (commit "
                + commit + ", sha256 " + sha256 + " of those bytes); read against that pinned draft, "
                + "not a released schema";
        }
    }
}
