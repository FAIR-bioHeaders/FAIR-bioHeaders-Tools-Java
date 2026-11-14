/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.fairbioheaders;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.JsonNode;

/**
 * The canonical JSON writer for test artifacts: LF line endings, two-space indent, insertion key
 * order, no platform-dependent separators and no dependency on a Jackson version's pretty-printer
 * defaults. Both the default-behavior golden and the acceptance ledger are rendered with it, so a
 * byte comparison between two runs means "the same behavior" and nothing else.
 */
public final class JsonBytes {

    private JsonBytes() {
    }

    /** Canonical text of one node. */
    public static String canonical(JsonNode node) {
        return new String(toBytes(node), StandardCharsets.UTF_8);
    }

    public static byte[] toBytes(JsonNode node) {
        StringBuilder sb = new StringBuilder(1 << 20);
        writeJson(sb, node, 0);
        sb.append('\n');
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void writeJson(StringBuilder sb, JsonNode node, int depth) {
        if (node == null || node.isNull()) {
            sb.append("null");
            return;
        }
        if (node.isObject()) {
            java.util.List<String> names = new java.util.ArrayList<>(node.propertyNames());
            if (names.isEmpty()) {
                sb.append("{}");
                return;
            }
            sb.append("{\n");
            for (int i = 0; i < names.size(); i++) {
                String name = names.get(i);
                indent(sb, depth + 1);
                writeString(sb, name);
                sb.append(": ");
                writeJson(sb, node.get(name), depth + 1);
                sb.append(i + 1 < names.size() ? ",\n" : "\n");
            }
            indent(sb, depth);
            sb.append('}');
            return;
        }
        if (node.isArray()) {
            if (node.size() == 0) {
                sb.append("[]");
                return;
            }
            sb.append("[\n");
            for (int i = 0; i < node.size(); i++) {
                indent(sb, depth + 1);
                writeJson(sb, node.get(i), depth + 1);
                sb.append(i + 1 < node.size() ? ",\n" : "\n");
            }
            indent(sb, depth);
            sb.append(']');
            return;
        }
        if (node.isString()) {
            writeString(sb, node.asString());
            return;
        }
        if (node.isNumber() || node.isBoolean()) {
            sb.append(node.toString());
            return;
        }
        // Any remaining value type is recorded by its text form.
        writeString(sb, node.asString());
    }

    private static void indent(StringBuilder sb, int depth) {
        sb.append("  ".repeat(depth));
    }

    private static void writeString(StringBuilder sb, String value) {
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
