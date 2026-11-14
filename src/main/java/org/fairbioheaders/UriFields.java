/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.fairbioheaders;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The rule R14 field list of a contract, derived from the contract's own bytes.
 *
 * <p>R14 warns about a value whose schema says {@code format: uri} but whose scheme is not one of
 * http, https, ftp, urn, doi or info. Which fields those are is a property of the contract, so
 * they are read off the schema actually being applied rather than off a table copied from it: the
 * derivation is per type <em>and</em> per release, and a newly registered type needs no
 * hand-maintained list. {@code UriFieldsTest} pins the result for every bundled copy, so a
 * contract refresh cannot silently change what R14 watches.
 *
 * <p>Path form is the one the warnings use: dot-separated property names, with {@code []} marking
 * a step through an array's {@code items} ({@code assemblySoftware[].uri},
 * {@code derivedFrom[].accessionID.url}). Local {@code $ref}s are followed at the referring
 * node's path, and {@code allOf}/{@code anyOf}/{@code oneOf} branches are unioned, because the
 * family's contracts state some fields inside a conditional branch.
 */
final class UriFields {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_DEPTH = 32;

    private UriFields() {
    }

    /** The {@code format: uri} field paths of a bundled contract, in first-found order. */
    static List<String> derive(SchemaBundle bundle, String file) {
        JsonNode root;
        try (InputStream in = bundle.open(file)) {
            if (in == null) {
                throw new IllegalStateException("missing bundled schema " + file);
            }
            root = JSON.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return derive(root);
    }

    static List<String> derive(JsonNode root) {
        Set<String> found = new LinkedHashSet<>();
        walk(root, root, "", found, 0);
        found.remove("");
        return List.copyOf(found);
    }

    private static void walk(JsonNode root, JsonNode node, String path, Set<String> found, int depth) {
        if (node == null || depth > MAX_DEPTH || !node.isObject()) {
            return;
        }
        if ("uri".equals(node.path("format").asString("")) && !path.isEmpty()) {
            found.add(path);
        }
        if (node.has("$ref")) {
            JsonNode target = resolve(root, node.get("$ref").asString());
            if (target != null) {
                walk(root, target, path, found, depth + 1);
            }
        }
        JsonNode properties = node.get("properties");
        if (properties != null && properties.isObject()) {
            for (String name : properties.propertyNames()) {
                walk(root, properties.get(name), join(path, name), found, depth + 1);
            }
        }
        JsonNode items = node.get("items");
        if (items != null) {
            if (items.isArray()) {
                for (JsonNode branch : items) {
                    walk(root, branch, path + "[]", found, depth + 1);
                }
            } else {
                walk(root, items, path + "[]", found, depth + 1);
            }
        }
        for (String branchKey : List.of("allOf", "anyOf", "oneOf")) {
            JsonNode branches = node.get(branchKey);
            if (branches != null && branches.isArray()) {
                for (JsonNode branch : branches) {
                    walk(root, branch, path, found, depth + 1);
                }
            }
        }
        JsonNode additional = node.get("additionalProperties");
        if (additional != null && additional.isObject()) {
            walk(root, additional, path, found, depth + 1);
        }
        JsonNode patternProperties = node.get("patternProperties");
        if (patternProperties != null && patternProperties.isObject()) {
            for (String pattern : patternProperties.propertyNames()) {
                walk(root, patternProperties.get(pattern), join(path, pattern), found, depth + 1);
            }
        }
    }

    private static String join(String path, String name) {
        return path.isEmpty() ? name : path + "." + name;
    }

    private static JsonNode resolve(JsonNode root, String ref) {
        if (!ref.startsWith("#/")) {
            return null;
        }
        JsonNode current = root;
        for (String step : ref.substring(2).split("/")) {
            current = current.get(step.replace("~1", "/").replace("~0", "~"));
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    /** Sorted copy, for stable comparison in tests and diagnostics. */
    static List<String> sorted(List<String> fields) {
        List<String> out = new ArrayList<>(fields);
        Collections.sort(out);
        return out;
    }
}
