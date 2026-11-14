/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.fairbioheaders;

import java.util.List;
import java.util.Objects;

/**
 * A FAIR-bioHeaders header type: the family name ({@code FHR}), the lower-case segment used
 * in schema URLs ({@code fhr}), whether its contract is a released one or an unreleased
 * draft, and how its records reach a reader.
 *
 * <p>The three <em>released</em> types are the constants {@link #FHR}, {@link #FHP} and
 * {@link #FHGFF3}. Types are not a closed set in this class: a draft type such as
 * {@code FHT} is <em>registered data</em> (see {@link TypeRegistry} and
 * {@code schemas/bundled.json}), so adding support for another type of the five-type family
 * means adding a registry entry, its pinned contract bytes and their provenance - not editing
 * the reader. Instances are interned per {@link TypeRegistry} and compared by value.
 *
 * <p>A draft type carries no {@code schemaVersion} of its own, no w3id URL, no entry in the
 * shared {@code schemas/versions.json} registry and no support claim: rule V7 is an opt-in
 * reading lane over pinned development bytes, and a draft never takes a release form.
 */
public final class FhType implements Comparable<FhType> {

    /** Whether the contract behind a type is a released one or an unreleased draft. */
    public enum Status {
        /** Published by FAIR-bioHeaders-Specification under a release tag and a registry row. */
        RELEASED,
        /** A development-repository draft, readable only through the rule V7 lane. */
        DRAFT
    }

    /** How a type's records arrive at the reader. */
    public enum Transport {
        /** A file-bound {@code ;~} or {@code #~} header block with a root checksum line. */
        EMBEDDED_HEADER,
        /**
         * Records carried inside another type's header under an {@code x-} extension key
         * (FORMAT.md R15). The enclosing file is routed by its own header citation; the lane
         * selects only the contract and validator applied to the records.
         */
        EMBEDDED_RECORDS
    }

    private final String urlKey;
    private final String fileKey;
    private final Status status;
    private final Transport transport;

    private FhType(String urlKey, String fileKey, Status status, Transport transport) {
        this.urlKey = Objects.requireNonNull(urlKey, "urlKey");
        this.fileKey = Objects.requireNonNull(fileKey, "fileKey");
        this.status = Objects.requireNonNull(status, "status");
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    /** The instance a registry declares for these coordinates; used by {@link TypeRegistry}. */
    static FhType of(String urlKey, String fileKey, Status status, Transport transport) {
        return new FhType(urlKey, fileKey, status, transport);
    }

    /** Released FHR: the reference-genome header (FAIR-bioHeaders-Specification {@code fhr.json}). */
    public static final FhType FHR = new FhType("fhr", "FHR", Status.RELEASED, Transport.EMBEDDED_HEADER);

    /** Released FHP: the protein-set header ({@code fhp.json}). */
    public static final FhType FHP = new FhType("fhp", "FHP", Status.RELEASED, Transport.EMBEDDED_HEADER);

    /** Released FHGFF3: the GFF3 annotation header ({@code fhgff3.json}). */
    public static final FhType FHGFF3 =
        new FhType("fhgff3", "FHGFF3", Status.RELEASED, Transport.EMBEDDED_HEADER);

    /** The released types, in registry-declaration order. */
    public static List<FhType> releasedTypes() {
        return List.of(FHR, FHP, FHGFF3);
    }

    /** The released constants; {@link #releasedTypes()} is the same list. */
    public static FhType[] values() {
        return new FhType[] {FHR, FHP, FHGFF3};
    }

    /** Lower-case name as it appears in schema URLs ({@code fhr}, {@code fhp}, {@code fhgff3}). */
    public String urlKey() {
        return urlKey;
    }

    /** Name used in {@code schemas/bundled.json} ({@code FHR}, {@code FHP}, {@code FHGFF3}). */
    public String fileKey() {
        return fileKey;
    }

    /** Released or draft; a draft is only readable through the rule V7 lane. */
    public Status status() {
        return status;
    }

    /** Whether the type's records are a file-bound header or records inside one. */
    public Transport transport() {
        return transport;
    }

    public boolean isDraft() {
        return status == Status.DRAFT;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof FhType t
            && t.urlKey.equals(urlKey) && t.fileKey.equals(fileKey)
            && t.status == status && t.transport == transport;
    }

    @Override
    public int hashCode() {
        return Objects.hash(urlKey, fileKey, status, transport);
    }

    @Override
    public int compareTo(FhType other) {
        return fileKey.compareTo(other.fileKey);
    }

    @Override
    public String toString() {
        return status == Status.RELEASED ? fileKey : fileKey + " (draft)";
    }
}
