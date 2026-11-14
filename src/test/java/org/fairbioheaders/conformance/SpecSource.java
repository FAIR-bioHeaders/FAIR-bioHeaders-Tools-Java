/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.fairbioheaders.conformance;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;

/**
 * Where a conformance suite comes from, and what a named-but-wrong location means.
 *
 * <p>The rule is deliberately asymmetric:
 *
 * <ul>
 *   <li>when a location <em>is</em> named (system property or environment variable) it is
 *   authoritative: if it does not hold the suite, that is a configuration error and resolution
 *   fails. Silently continuing elsewhere is how a run ends up measuring a different checkout than
 *   the one it was told to use;</li>
 *   <li>when nothing is named, sibling discovery is a convenience for local work, and finding
 *   nothing yields {@code null} - which the caller reports as skipped, never as passed.</li>
 * </ul>
 *
 * <p>The first rule exists because of an observation, not a hypothesis. During the deliberate
 * failure demonstration for issue #3, {@code -Dfh.spec.dir} was pointed at a directory with no
 * manifest. On the Linux leg the run went red, as intended. On the macOS legs it stayed green: the
 * fallback candidate {@code FAIR-bioHeaders-Specification} resolved, case-insensitively, to the
 * {@code fair-bioheaders-specification} checkout the workflow had placed inside the workspace, so
 * the vectors ran from a path nobody had asked for. A named path must therefore never fall through.
 */
final class SpecSource {

    private SpecSource() {
    }

    /** The shared-manifest layout: {@code <dir>/conformance/manifest.json}. */
    static final Predicate<Path> SPECIFICATION =
        dir -> Files.isRegularFile(dir.resolve("conformance/manifest.json"));

    /** The FHT layout: the suite directory itself holds {@code manifest.json} and {@code valid/}. */
    static final Predicate<Path> SUITE_DIRECTORY =
        dir -> Files.isRegularFile(dir.resolve("manifest.json")) && Files.isDirectory(dir.resolve("valid"));

    /**
     * @param namedProperty the value of the {@code fh.*.dir} system property, or null
     * @param namedEnvironment the value of the corresponding environment variable, or null
     * @param propertyName the property's name, for the failure message
     * @param environmentName the variable's name, for the failure message
     * @param fallbacks sibling directories consulted only when nothing was named
     * @param isSuite what makes a directory hold this suite
     * @return the suite directory, or null when nothing was named and no sibling holds a suite
     * @throws IllegalStateException when something was named and does not hold the suite
     */
    static Path resolve(String namedProperty, String namedEnvironment, String propertyName,
                        String environmentName, List<Path> fallbacks, Predicate<Path> isSuite) {
        if (namedProperty != null && !namedProperty.isBlank()) {
            return named(namedProperty.strip(), propertyName, isSuite);
        }
        if (namedEnvironment != null && !namedEnvironment.isBlank()) {
            return named(namedEnvironment.strip(), environmentName, isSuite);
        }
        for (Path candidate : fallbacks) {
            if (isSuite.test(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static Path named(String value, String source, Predicate<Path> isSuite) {
        Path dir = Path.of(value);
        if (isSuite.test(dir)) {
            return dir;
        }
        throw new IllegalStateException(source + "=" + value + " names a directory that does not"
            + " hold the conformance suite (looked at " + dir.toAbsolutePath() + "). Fix the path or"
            + " unset it to use sibling discovery; the suite is never read from a location the run"
            + " did not ask for.");
    }
}
