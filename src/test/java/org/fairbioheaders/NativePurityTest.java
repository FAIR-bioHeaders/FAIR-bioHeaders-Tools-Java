/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.fairbioheaders;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * The native-acceptance guard (issue #3): this repository's tests and CI must exercise this Java
 * implementation directly, with no foreign-language runtime behind a wrapper. A sentence saying so
 * in a README is not a guard, so this test checks the two things that are actually checkable from
 * inside the JVM that runs the suite.
 *
 * <ol>
 *   <li><b>No subprocess, no embedded engine, no native library load anywhere in the sources.</b>
 *   The shared conformance suite is consumed as data (JSON plus file bytes); nothing here can hand
 *   work to another interpreter. Patterns are assembled from fragments so that this file's own text
 *   does not match them.</li>
 *   <li><b>The resolved classpath is exactly the documented Java dependency set.</b> Every entry on
 *   the test classpath must be a JDK module or one of the artifacts listed in
 *   docs/DEPENDENCIES.md, so an unexpected runtime - including any language's bridge - fails the
 *   build rather than slipping in as a transitive dependency.</li>
 * </ol>
 *
 * <p>The complementary checks live outside Java, where they belong: {@code build.yml} verifies that
 * no build file pulls in an interpreter plugin, that no foreign project file appears in the tree,
 * and that the reports are non-empty and skip-free; {@code docs/NATIVE-ACCEPTANCE.md} records the
 * commands, the runtime/OS matrix and what each guard covers.
 */
class NativePurityTest {

    /** Fragments, joined at runtime, so scanning this file cannot match its own patterns. */
    private static final String[] FORBIDDEN = {
        "Process" + "Builder",
        "Runtime" + ".getRuntime",
        "getRuntime()" + ".exec",
        "javax." + "script",
        "ScriptEngine" + "Manager",
        "System." + "loadLibrary",
        "System." + "load(",
        "Library" + "Loader",
        "fork" + "Join",
        "py4j",
        "Jython",
        "graal" + ".python",
    };

    /** Exactly the artifacts docs/DEPENDENCIES.md records, plus this project and the JDK. */
    private static final List<String> ALLOWED_ARTIFACTS = List.of(
        "fair-bioheaders-java",
        "json-schema-validator",
        "snakeyaml",
        "jackson-databind",
        "jackson-core",
        "jackson-annotations",
        "jackson-dataformat-yaml",
        "snakeyaml-engine",
        "itu",
        "slf4j-api",
        "junit-jupiter",
        "junit-jupiter-api",
        "junit-jupiter-engine",
        "junit-jupiter-params",
        "junit-platform-commons",
        "junit-platform-engine",
        "opentest4j",
        "apiguardian-api");

    @Test
    void noSourceCanInvokeAnotherImplementation() throws IOException {
        List<Path> sources = new ArrayList<>();
        for (String root : List.of("src/main/java", "src/test/java")) {
            Path dir = Path.of(root);
            assertTrue(Files.isDirectory(dir),
                "this test only means something in a source tree; run it from the repository root"
                    + " (" + dir.toAbsolutePath() + " missing)");
            try (var walk = Files.walk(dir)) {
                walk.filter(p -> p.toString().endsWith(".java")).forEach(sources::add);
            }
        }
        // Quoted fragments: the patterns contain literal parentheses and dots.
        List<String> quoted = new ArrayList<>();
        for (String fragment : FORBIDDEN) {
            quoted.add(Pattern.quote(fragment));
        }
        Pattern forbidden = Pattern.compile(String.join("|", quoted));
        List<String> hits = new ArrayList<>();
        for (Path source : sources) {
            if (source.getFileName().toString().equals("NativePurityTest.java")) {
                continue; // its own pattern fragments are the only occurrence in the tree
            }
            List<String> lines = Files.readAllLines(source, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                Matcher m = forbidden.matcher(lines.get(i));
                if (m.find()) {
                    hits.add(source + ":" + (i + 1) + ": " + lines.get(i).strip());
                }
            }
        }
        assertTrue(hits.isEmpty(),
            () -> "native acceptance must not shell out, embed a script engine or load a foreign"
                + " runtime; found:\n  " + String.join("\n  ", hits));
        assertTrue(sources.size() >= 20, "expected to scan the whole source tree, saw " + sources);
    }

    @Test
    void theTestClasspathHoldsOnlyTheDocumentedJavaDependencies() {
        String classpath = System.getProperty("java.class.path", "");
        List<String> unexpected = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (String entry : classpath.split(java.io.File.pathSeparator)) {
            if (entry.isBlank()) {
                continue;
            }
            String name = Path.of(entry).getFileName().toString();
            if (name.equals("target-classes") || name.equals("classes") || name.equals("test-classes")
                    || name.startsWith("surefire")) {
                continue;
            }
            if (!name.endsWith(".jar")) {
                unexpected.add(entry + " (not a jar and not a project output directory)");
                continue;
            }
            String artifact = name.replaceAll("-\\d[0-9A-Za-z.\\-]*\\.jar$", "");
            seen.add(artifact);
            if (ALLOWED_ARTIFACTS.stream().noneMatch(artifact::equals)) {
                unexpected.add(name);
            }
        }
        assertTrue(unexpected.isEmpty(),
            () -> "undocumented artifact on the acceptance classpath (add it to"
                + " docs/DEPENDENCIES.md with its license, or remove the dependency): " + unexpected);
        assertTrue(seen.contains("json-schema-validator") && seen.contains("snakeyaml"),
            "the positive control: the declared dependencies must actually be present, got " + seen);
    }

    @Test
    void noDependencyDirectoryLooksLikeAForeignRuntime() throws IOException {
        // Belt on the braces above: the repository itself must not carry a foreign project or
        // virtualenv that a test could reach for.
        for (String name : List.of("requirements.txt", "pyproject.toml", "setup.py",
                "environment.yml", "Pipfile", "package.json", "Cargo.toml", "Project.toml",
                "DESCRIPTION", "NAMESPACE", ".venv", "venv", "renv")) {
            assertFalse(Files.exists(Path.of(name)),
                "a Java library must not ship or depend on " + name);
        }
    }
}
