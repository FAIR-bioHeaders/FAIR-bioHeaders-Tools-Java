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

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The {@link Options} knobs: the default is the released-contract behavior, the rule V7 lane is
 * opt-in, and the CLI-shaped environment fallbacks resolve in the documented order.
 */
class OptionsTest {

    @AfterEach
    void clearProperty() {
        System.clearProperty(Options.DRAFT_PROPERTY);
    }

    @Test
    void defaultsAreReleasedContractsOnly() {
        Options options = Options.defaults();
        assertFalse(options.draftContractsEnabled());
        assertFalse(options.draftEnabled("FHT"));
        assertFalse(options.draftEnabled("FHGM"));
        assertTrue(options.draftSelection().isEmpty());
    }

    @Test
    void builderTurnsTheLaneOnAndNamesDraftsCaseInsensitively() {
        Options all = Options.builder().enableDraftContracts(true).build();
        assertTrue(all.draftContractsEnabled());
        assertTrue(all.draftEnabled("FHT"), "an empty selection means every draft the bundle pins");
        assertTrue(all.draftEnabled("fhgm"));

        Options one = Options.builder().enableDraft("fht").build();
        assertTrue(one.draftContractsEnabled(), "naming a draft opts in");
        assertEquals(Set.of("FHT"), one.draftSelection());
        assertTrue(one.draftEnabled("FHT"));
        assertFalse(one.draftEnabled("FHGM"), "naming one draft does not enable the others");
        assertThrows(IllegalArgumentException.class, () -> Options.builder().enableDraft("  "));
        assertThrows(IllegalArgumentException.class, () -> Options.builder().enableDraft(null));
    }

    @Test
    void explicitOptOutBeatsTheEnvironment() {
        System.setProperty(Options.DRAFT_PROPERTY, "1");
        Options off = Options.builder().enableDraftContracts(false).build();
        assertFalse(off.draftContractsEnabled(), "a value set on the builder wins");
    }

    @Test
    void systemPropertyOptsIn() {
        if (System.getenv(Options.DRAFT_ENVIRONMENT) != null) {
            return; // the fallback chain below assumes a clean environment
        }
        for (String truth : List.of("1", "true", "TRUE", " yes ", "On")) {
            System.setProperty(Options.DRAFT_PROPERTY, truth);
            assertTrue(Options.fromEnvironment().draftContractsEnabled(), truth);
        }
        for (String falsey : List.of("", "  ", "0", "no", "off", "banana")) {
            System.setProperty(Options.DRAFT_PROPERTY, falsey);
            assertFalse(Options.fromEnvironment().draftContractsEnabled(), "[" + falsey + "]");
        }
    }

    @Test
    void toStringDoesNotClaimMoreThanTheKnobs() {
        assertEquals("Options{draftContracts=false}", Options.defaults().toString());
        assertEquals("Options{draftContracts=true}",
            Options.builder().enableDraftContracts(true).build().toString());
        assertEquals("Options{draftContracts=true, drafts=[FHT]}",
            Options.builder().enableDraft("FHT").build().toString());
    }

    @Test
    void aReaderBuiltWithoutOptionsReadsReleasedContractsOnly() {
        BioHeaders reader = new BioHeaders();
        assertEquals(List.of(FhType.FHR, FhType.FHP, FhType.FHGFF3), reader.readableTypes());
        assertFalse(reader.options().draftContractsEnabled());
        for (FhType type : reader.readableTypes()) {
            assertFalse(type.isDraft(), "no draft is readable without the opt-in: " + type);
        }
        // Whatever drafts the shipped registry may come to declare, none of them is readable or
        // selected by default (rule V7: the lane is never the default).
        for (String name : reader.declaredDraftTypes()) {
            assertFalse(reader.options().draftEnabled(name), name);
        }
    }

    @Test
    void fhTypeValuesAreValueEqualAndOrderedByFileKey() {
        assertEquals(FhType.FHR, FhType.of("fhr", "FHR", FhType.Status.RELEASED,
            FhType.Transport.EMBEDDED_HEADER));
        assertEquals(FhType.FHR.hashCode(),
            FhType.of("fhr", "FHR", FhType.Status.RELEASED, FhType.Transport.EMBEDDED_HEADER).hashCode());
        FhType draft = FhType.of("fhr", "FHR", FhType.Status.DRAFT, FhType.Transport.EMBEDDED_HEADER);
        assertFalse(FhType.FHR.equals(draft), "status is part of the identity");
        assertTrue(FhType.FHP.compareTo(FhType.FHR) < 0, "ordering is by file key");
        assertEquals("FHR", FhType.FHR.toString());
        assertEquals("FHR (draft)", draft.toString());
        assertEquals(List.of(FhType.FHR, FhType.FHP, FhType.FHGFF3), FhType.releasedTypes());
        assertEquals(3, FhType.values().length);
    }
}
