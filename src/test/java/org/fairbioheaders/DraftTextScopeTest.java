/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.fairbioheaders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The scope of the draft-lane text rule, read off the pinned record contract instead of kept by hand.
 *
 * <p>{@code $defs.text} is referenced by fourteen sites in the frozen schema; {@code publication.article}
 * is not one of them, and neither is any {@code $defs.link} field. The set below is what
 * {@link DraftTextPolicy#textPaths} must return for the bytes this jar digests, so a deliberate re-pin
 * that adds or removes a {@code $ref} moves the check automatically - and a hand-written field list,
 * which is what the first version of this fix effectively was, cannot. It lives in its own class so the
 * behavioural counterexamples stay runnable against an unpatched head.
 *
 * <p>Tagged {@code draft}: it reads the pinned development contract.
 */
@Tag("draft")
class DraftTextScopeTest {

    @Test
    @DisplayName("The constrained paths are read off the schema: 14 sites, article not among them")
    void theConstrainedPathSetIsDerivedFromTheSchema() {
        java.util.Set<String> expected = java.util.Set.of(
            "modelId", "modelVersion", "featureId", "reuseConditions",
            "predecessor.modelId", "predecessor.modelVersion",
            "contributors[].name", "contributors[].role",
            "evidence[].description",
            "methods.software[].name", "methods.software[].version", "methods.limitations[]",
            "publication.deposit[]", "reference.genomeReference.assemblyName");
        try (var in = SchemaBundle.getDefault().open("fhgm-model-draft.json")) {
            var contract = new tools.jackson.databind.ObjectMapper().readTree(in);
            assertEquals(expected, DraftTextPolicy.textPaths(contract),
                "the scope of the text rule is whatever the pinned contract references, exactly:"
                    + " adding a $defs/text site must widen it, and dropping one must narrow it");
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        assertTrue(!expected.contains("publication.article"),
            "publication.article carries its own DOI pattern and no $defs/text reference");
    }

}
