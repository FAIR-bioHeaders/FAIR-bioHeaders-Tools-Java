/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.fairbioheaders;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import tools.jackson.databind.JsonNode;

/**
 * What a reader may say about a record's {@code derivedFrom} parent checksums when it is handed
 * the parent files.
 *
 * <p>A {@code derivedFrom} entry states its parent's FHR-family checksum (FORMAT.md R1-R5), which
 * the child's own checksum coverage says nothing about. The family's position, stated most exactly
 * by the FHT development suite that exercises it (its rule C3, FHT-Specification
 * conformance/README.md), is three-way:
 *
 * <ul>
 *   <li>{@link State#CHECKED_PASS} - the parent bytes were supplied and their recomputed checksum
 *       equals the stated one, compared as exact strings;</li>
 *   <li>{@link State#CHECKED_FAIL} - the parent bytes were supplied and the values differ: an
 *       invalid link, which a reader must report as a rejection rather than pass silently;</li>
 *   <li>{@link State#UNCHECKED} - the parent bytes were not supplied: the child keeps whatever
 *       verdict its own header earns, and no implementation may call the link verified.</li>
 * </ul>
 *
 * <p>{@link BioHeaders#verify(byte[], Kind)} never looks at parent links, so the released default
 * is unaffected; the three-argument overload asks for this check explicitly. The verdict is per
 * entry: one record can carry a passing and a failing link, and it is the failing one that must
 * be reported.
 */
public final class ParentLinks {

    /** The issue label {@link BioHeaders} uses for a failing parent link. */
    public static final String RULE = "C3";

    /** What a reader may say about one stated parent checksum. */
    public enum State {
        CHECKED_PASS,
        CHECKED_FAIL,
        UNCHECKED
    }

    /**
     * A parent file the caller supplied: its container and either its decompressed bytes (R1) or
     * the path to read them from. A path-backed parent ({@link #ofFile}) is streamed when its
     * checksum is computed, so a genome-sized parent is never held in memory; its {@code data()}
     * is {@code null}.
     */
    public record Parent(Kind kind, byte[] data, Path file) {

        public Parent {
            if ((data == null) == (file == null)) {
                throw new IllegalArgumentException("a parent has either bytes or a file, not both");
            }
        }

        /** A parent given as decompressed bytes. */
        public Parent(Kind kind, byte[] data) {
            this(kind, java.util.Objects.requireNonNull(data, "data"), null);
        }

        /** A parent read from {@code file} when needed; the container comes from its name. */
        public static Parent ofFile(Path file) {
            return new Parent(BioHeaders.kindFor(file.getFileName().toString()), null, file);
        }
    }

    /**
     * One link's verdict. {@code statedChecksum} is what the record claims;
     * {@code recomputedChecksum} is what the supplied parent hashes to, or {@code null} for an
     * unchecked link.
     */
    public record Verdict(State state, int index, String headerType, String relationship,
                          String statedChecksum, String recomputedChecksum) {

        public boolean failed() {
            return state == State.CHECKED_FAIL;
        }
    }

    /** Supplies the file a link names, or empty when the caller has none. */
    public interface ParentSource {
        Optional<Parent> parentBytes(String statedChecksum, String headerType, int linkIndex);
    }

    private ParentLinks() {
    }

    /**
     * Verdicts for every {@code derivedFrom} entry of a parsed header, in document order. A link
     * with no stated checksum is {@link State#UNCHECKED}: there is nothing to compare, which is a
     * contract problem for the schema to report, not a failed link check.
     */
    public static List<Verdict> check(JsonNode metadata, ParentSource source) {
        List<Verdict> out = new ArrayList<>();
        if (metadata == null || !metadata.isObject()) {
            return out;
        }
        JsonNode derived = metadata.get("derivedFrom");
        if (derived == null || !derived.isArray()) {
            return out;
        }
        int index = 0;
        for (JsonNode link : derived) {
            String stated = link.path("checksum").asString(null);
            String headerType = link.path("headerType").asString(null);
            String relationship = link.path("relationship").asString(null);
            Optional<Parent> parent = stated == null
                ? Optional.empty()
                : source.parentBytes(stated, headerType, index);
            if (parent.isEmpty()) {
                out.add(new Verdict(State.UNCHECKED, index, headerType, relationship, stated, null));
            } else {
                String recomputed = checksum(parent.get());
                out.add(new Verdict(
                    recomputed.equals(stated) ? State.CHECKED_PASS : State.CHECKED_FAIL,
                    index, headerType, relationship, stated, recomputed));
            }
            index++;
        }
        return out;
    }

    /** The issues a set of verdicts produces: one ERROR per failing link, nothing elsewhere. */
    public static List<Issue> issues(List<Verdict> verdicts) {
        List<Issue> out = new ArrayList<>();
        for (Verdict verdict : verdicts) {
            if (verdict.failed()) {
                out.add(Issue.error(RULE, "parent link " + verdict.index() + " ("
                    + verdict.relationship() + " " + verdict.headerType() + ") states "
                    + verdict.statedChecksum() + " but the supplied parent file hashes to "
                    + verdict.recomputedChecksum() + ": the link is invalid, not unchecked"));
            }
        }
        return out;
    }

    /** A source backed by in-memory files. */
    public static ParentSource inMemory(Map<String, Parent> parentsByStatedChecksum) {
        return (stated, headerType, linkIndex) ->
            Optional.ofNullable(parentsByStatedChecksum.get(stated));
    }

    /**
     * A source over files resolved from a suite directory. Each stated checksum maps to a path
     * relative to {@code root}; every path is normalised and refused if it lands outside
     * {@code root}, and the container comes from the file name the way {@link BioHeaders} infers
     * it everywhere else.
     */
    public static ParentSource relativeTo(Path root, Map<String, String> fileByStatedChecksum) {
        Path base = root.toAbsolutePath().normalize();
        return (stated, headerType, linkIndex) -> {
            String relative = fileByStatedChecksum.get(stated);
            if (relative == null) {
                return Optional.empty();
            }
            Path resolved = base.resolve(relative).normalize();
            if (!resolved.startsWith(base)) {
                throw new IllegalArgumentException(
                    "parent path escapes its suite directory: " + relative);
            }
            if (!Files.isRegularFile(resolved)) {
                return Optional.empty();
            }
            // The lexical check above cannot see symbolic links: compare the real paths too, so a
            // link inside the suite cannot hand over a file from outside it.
            try {
                Path realBase = base.toRealPath();
                Path real = resolved.toRealPath();
                if (!real.startsWith(realBase)) {
                    throw new IllegalArgumentException(
                        "parent path escapes its suite directory through a symbolic link: " + relative);
                }
                return Optional.of(Parent.ofFile(real));
            } catch (IOException e) {
                throw new UncheckedIOException("cannot read parent file " + resolved, e);
            }
        };
    }

    /**
     * The SHA-512/256 checksum a parent file's own header covers, recomputed (R1-R5).
     *
     * @throws IllegalArgumentException when the parent is not a readable headed file
     * @throws UncheckedIOException when a path-backed parent cannot be read
     */
    public static String checksumOf(Parent parent) {
        return checksum(parent);
    }

    private static String checksum(Parent parent) {
        try {
            if (parent.file() == null) {
                return HeaderScan.of(parent.kind(), parent.data()).computedChecksum();
            }
            try (java.io.InputStream in = BioHeaders.open(parent.file())) {
                return HeaderScan.of(parent.kind(), in).computedChecksum();
            } catch (IOException e) {
                throw new UncheckedIOException("cannot read parent file " + parent.file(), e);
            }
        } catch (ScanException e) {
            throw new IllegalArgumentException("the supplied parent file is not a readable headed "
                + parent.kind() + " file: " + e.getMessage(), e);
        }
    }
}
