/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.fairbioheaders;

/** Quoting untrusted header text inside an issue message. */
final class Text {

    private Text() {
    }

    /**
     * {@code text} with C0/C1 control characters, DEL, U+2028/U+2029 and bidirectional-format
     * controls written as backslash-x or backslash-u hex escapes, so a value quoted in a message cannot
     * move the cursor, end the line or reorder what a terminal shows.
     */
    static String printable(String text) {
        StringBuilder sb = null;
        for (int k = 0; k < text.length(); k++) {
            char c = text.charAt(k);
            boolean control = c < 0x20 || (c >= 0x7F && c <= 0x9F) || c == '\u2028' || c == '\u2029'
                || (c >= '\u202A' && c <= '\u202E') || (c >= '\u2066' && c <= '\u2069')
                || c == '\u200E' || c == '\u200F';
            if (control && sb == null) {
                sb = new StringBuilder(text.length() + 8).append(text, 0, k);
            }
            if (sb != null) {
                if (control) {
                    sb.append(c <= 0xFF ? String.format("\\x%02X", (int) c) : String.format("\\u%04X", (int) c));
                } else {
                    sb.append(c);
                }
            }
        }
        return sb == null ? text : sb.toString();
    }
}
