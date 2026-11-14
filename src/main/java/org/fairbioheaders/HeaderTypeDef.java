/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.fairbioheaders;

import java.util.List;
import java.util.Objects;

/**
 * One row of the header-type registry: the type, the {@code format: uri} fields rule R14
 * warns about, and, for a draft, the pinned development bytes that give the type its
 * contract. Registry rows are data ({@code schemas/bundled.json}); this is what makes a new
 * family type additive instead of a change to the reader.
 */
record HeaderTypeDef(FhType type, List<String> uriFields, DraftPin draft) {

    HeaderTypeDef {
        Objects.requireNonNull(type, "type");
        uriFields = List.copyOf(uriFields);
        if (type.isDraft() && draft == null) {
            throw new IllegalStateException("draft type " + type.fileKey() + " has no pin");
        }
        if (!type.isDraft() && draft != null) {
            throw new IllegalStateException("released type " + type.fileKey() + " carries a draft pin");
        }
    }

    static HeaderTypeDef released(FhType type, List<String> uriFields) {
        return new HeaderTypeDef(type, uriFields, null);
    }

    boolean isDraft() {
        return type.isDraft();
    }

    /** The rule V7 pin of a draft type: the bytes, where they came from, and the citation. */
    record DraftPin(String file, String citation, String repository, String commit, String sha256,
                    String retrieved, String source, String enable) {

        DraftPin {
            for (String required : new String[] {file, citation, repository, commit, sha256, retrieved}) {
                if (required == null || required.isBlank()) {
                    throw new IllegalStateException("incomplete draft registry entry: " + this);
                }
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
