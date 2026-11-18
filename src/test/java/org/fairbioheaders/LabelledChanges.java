/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.fairbioheaders;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Deliberate, reviewed behavior changes against the default-behavior golden.
 *
 * <p>The golden ({@code golden/default-behavior-v0.6.0.json}) is the fingerprint the old code
 * produced, and its bytes are never regenerated. When behavior is changed on purpose, the change
 * is written down in {@code golden/labelled-changes.json} as one entry per moved fingerprint
 * entry:
 *
 * <pre>
 * { "id": "...", "reason": "...", "section": "kinds",
 *   "match": { "name": "x.txt" },            // which entry of the section (omit for the whole section)
 *   "from": { ...the golden entry... },
 *   "to":   { ...the entry the code now produces... } }
 * </pre>
 *
 * <p>Each entry is applied to the golden before the comparison. {@code from} must equal the
 * golden's current entry exactly, so a change that no longer describes the golden fails instead
 * of quietly doing nothing. When the comparison fails, {@link #writeSuggestions} writes the
 * entries that would explain the difference, as a starting point for a reviewed change - never
 * as something to paste in without reading.
 */
final class LabelledChanges {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The fields that identify one entry of each array section of the fingerprint. */
    static final Map<String, List<String>> KEYS = Map.of(
        "selection", List.of("section", "input", "record"),
        "kinds", List.of("name"),
        "documents", List.of("name"),
        "vectors", List.of("id"));

    private LabelledChanges() {
    }

    /** Apply every change to {@code golden}; returns how many applied, adds stale ones to {@code problems}. */
    static int apply(ObjectNode golden, JsonNode changes, List<String> problems) {
        int applied = 0;
        for (JsonNode change : changes.path("changes")) {
            String id = change.path("id").asString("");
            String section = change.path("section").asString("");
            if (id.isBlank() || change.path("reason").asString("").isBlank()) {
                problems.add("labelled change " + id + " has no id or no reason");
                continue;
            }
            JsonNode match = change.get("match");
            JsonNode from = change.get("from");
            JsonNode to = change.get("to");
            if (from == null || to == null) {
                problems.add("labelled change " + id + " needs both 'from' and 'to'");
                continue;
            }
            if (match == null) {
                JsonNode current = golden.get(section);
                if (current == null || !same(current, from)) {
                    problems.add("labelled change " + id + " is stale: section " + section
                        + " of the golden is not its 'from'");
                    continue;
                }
                golden.set(section, to);
                applied++;
                continue;
            }
            JsonNode array = golden.get(section);
            if (array == null || !array.isArray()) {
                problems.add("labelled change " + id + " names a section that is not a list: " + section);
                continue;
            }
            List<Integer> hits = new ArrayList<>();
            for (int i = 0; i < array.size(); i++) {
                if (matches(array.get(i), match)) {
                    hits.add(i);
                }
            }
            if (hits.size() != 1) {
                problems.add("labelled change " + id + " matches " + hits.size()
                    + " entries of " + section + ", not one: " + JsonBytes.canonical(match));
                continue;
            }
            int index = hits.get(0);
            if (!same(array.get(index), from)) {
                problems.add("labelled change " + id + " is stale: its 'from' is not the golden's "
                    + section + " entry " + JsonBytes.canonical(match));
                continue;
            }
            ((ArrayNode) array).set(index, to);
            applied++;
        }
        return applied;
    }

    /** Write the changes that would turn {@code golden} into {@code actual}, for review. */
    static void writeSuggestions(JsonNode golden, JsonNode actual, Path file) {
        ObjectNode root = JSON.createObjectNode();
        root.put("note", "Suggested entries only: read each one, write its reason, and add it to"
            + " src/test/resources/golden/labelled-changes.json only if the change is intended.");
        ArrayNode out = root.putArray("changes");
        for (Map.Entry<String, List<String>> section : KEYS.entrySet()) {
            JsonNode want = golden.get(section.getKey());
            JsonNode got = actual.get(section.getKey());
            if (want == null || got == null) {
                continue;
            }
            for (JsonNode entry : want) {
                ObjectNode match = JSON.createObjectNode();
                for (String key : section.getValue()) {
                    if (entry.has(key)) {
                        match.set(key, entry.get(key));
                    }
                }
                JsonNode now = null;
                for (JsonNode candidate : got) {
                    if (matches(candidate, match)) {
                        now = candidate;
                        break;
                    }
                }
                if (now != null && !same(entry, now)) {
                    ObjectNode change = out.addObject();
                    change.put("id", "");
                    change.put("reason", "");
                    change.put("section", section.getKey());
                    change.set("match", match);
                    change.set("from", entry);
                    change.set("to", now);
                }
            }
        }
        for (Iterator<String> it = actual.propertyNames().iterator(); it.hasNext(); ) {
            String name = it.next();
            if (!KEYS.containsKey(name) && golden.has(name) && !same(golden.get(name), actual.get(name))) {
                ObjectNode change = out.addObject();
                change.put("id", "");
                change.put("reason", "");
                change.put("section", name);
                change.set("from", golden.get(name));
                change.set("to", actual.get(name));
            }
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, JsonBytes.canonical(root), StandardCharsets.UTF_8);
            System.out.println("suggested labelled changes written to " + file.toAbsolutePath());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean matches(JsonNode entry, JsonNode match) {
        for (Iterator<String> it = match.propertyNames().iterator(); it.hasNext(); ) {
            String key = it.next();
            JsonNode have = entry.get(key);
            if (have == null || !same(have, match.get(key))) {
                return false;
            }
        }
        return true;
    }

    private static boolean same(JsonNode a, JsonNode b) {
        return JsonBytes.canonical(a).equals(JsonBytes.canonical(b));
    }
}
