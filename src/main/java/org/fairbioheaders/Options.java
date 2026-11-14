/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.fairbioheaders;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * How a {@link BioHeaders} instance is configured. Everything defaults to the published
 * released-contract behavior: {@link #defaults()} is exactly the behavior this library has
 * always had, and each knob turns on extra, explicitly requested behavior.
 *
 * <p>Every knob resolves in the same order - first a value set on the builder, then the
 * system property, then the environment variable - so an application can opt in
 * programmatically while an operator can opt in for a single command line.
 *
 * <h2>Draft contracts (rule V7)</h2>
 *
 * {@link Builder#enableDraftContracts(boolean)} opts in to FAIR-bioHeaders-Specification
 * docs/SCHEMA-VERSIONS.md rule V7: the reader also recognises the <em>pinned draft</em>
 * contract bytes of a type development repository, besides the released contracts of rules
 * V1-V6. The lane is never the default. The reference toolkit's equivalent is
 * {@code BIOHEADERS_EXPERIMENTAL_FHT}; the native equivalents here are the system property
 * {@value #DRAFT_PROPERTY} and the environment variable {@value #DRAFT_ENVIRONMENT}, each
 * accepting {@code 1}, {@code true}, {@code yes} or {@code on} (case-insensitive, the same
 * truth set as the toolkit).
 *
 * <p>With the lane on, every record read through it carries a warning naming the draft, the
 * commit and the SHA-256 digest its bytes are pinned to, and saying the result is not a
 * released-contract result. A draft never takes a release form: it has no {@code schemaVersion}
 * in the shared registry, no w3id URL, no {@code schemas/versions.json} row, and using the lane
 * is not a claim that the type is supported. See docs/STATUS.md and docs/CAPABILITY-MATRIX.md.
 */
public final class Options {

    /** System property that opts in to the rule V7 draft lane. */
    public static final String DRAFT_PROPERTY = "org.fairbioheaders.experimental.drafts";

    /** Environment variable that opts in to the rule V7 draft lane. */
    public static final String DRAFT_ENVIRONMENT = "FAIR_BIOHEADERS_EXPERIMENTAL_DRAFTS";

    /** The same truth set the reference toolkit accepts for its draft opt-in. */
    private static final Set<String> TRUTHY = Set.of("1", "true", "yes", "on");

    private final boolean draftContractsEnabled;
    private final Set<String> draftSelection;

    private Options(boolean draftContractsEnabled, Collection<String> draftSelection) {
        this.draftContractsEnabled = draftContractsEnabled;
        Set<String> names = new LinkedHashSet<>();
        for (String name : draftSelection) {
            names.add(name.toUpperCase(Locale.ROOT));
        }
        this.draftSelection = Set.copyOf(names);
    }

    /** The published released-contract behavior: no draft lane, no parent links. */
    public static Options defaults() {
        return new Options(false, List.of());
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * The configuration the environment asks for: {@link #defaults()} unless
     * {@value #DRAFT_PROPERTY} or {@value #DRAFT_ENVIRONMENT} opts in to the draft lane.
     * This is what {@code new BioHeaders()} uses.
     */
    public static Options fromEnvironment() {
        return builder().build();
    }

    /** Whether any draft contract may be recognised (rule V7, explicit opt-in). */
    public boolean draftContractsEnabled() {
        return draftContractsEnabled;
    }

    /**
     * The draft type names the caller asked for, upper case; empty means "every draft this
     * bundle carries".
     */
    public Set<String> draftSelection() {
        return draftSelection;
    }

    /**
     * Whether the named draft is active. An empty {@link #draftSelection()} enables every
     * draft the registry declares, so the caller never has to know the names in advance.
     */
    public boolean draftEnabled(String fileKey) {
        return draftContractsEnabled
            && (draftSelection.isEmpty() || draftSelection.contains(fileKey.toUpperCase(Locale.ROOT)));
    }

    @Override
    public String toString() {
        return "Options{draftContracts=" + draftContractsEnabled
            + (draftSelection.isEmpty() ? "" : ", drafts=" + draftSelection) + "}";
    }

    /** Fluent builder; {@link #build()} applies the environment fallbacks. */
    public static final class Builder {

        private Boolean draftContracts;
        private final Set<String> drafts = new LinkedHashSet<>();

        private Builder() {
        }

        /** Opt in (or explicitly out) of the rule V7 draft lane. */
        public Builder enableDraftContracts(boolean enabled) {
            this.draftContracts = enabled;
            return this;
        }

        /**
         * Opt in to one draft type by name ({@code "FHT"}, {@code "FHGM"}); implies
         * {@link #enableDraftContracts(boolean) enabled}. Naming a type restricts the lane to
         * the names given.
         */
        public Builder enableDraft(String fileKey) {
            String name = fileKey == null ? "" : fileKey.strip().toUpperCase(Locale.ROOT);
            if (name.isEmpty()) {
                throw new IllegalArgumentException("draft type name must not be blank");
            }
            drafts.add(name);
            draftContracts = Boolean.TRUE;
            return this;
        }

        public Builder enableDrafts(Collection<String> fileKeys) {
            fileKeys.forEach(this::enableDraft);
            return this;
        }

        public Options build() {
            boolean enabled = draftContracts != null ? draftContracts : environmentOptIn();
            return new Options(enabled, enabled ? drafts : Set.of());
        }

        private static boolean environmentOptIn() {
            String property = System.getProperty(DRAFT_PROPERTY);
            if (property != null && !property.isBlank()) {
                return TRUTHY.contains(property.strip().toLowerCase(Locale.ROOT));
            }
            String env = System.getenv(DRAFT_ENVIRONMENT);
            return env != null && TRUTHY.contains(env.strip().toLowerCase(Locale.ROOT));
        }
    }
}
