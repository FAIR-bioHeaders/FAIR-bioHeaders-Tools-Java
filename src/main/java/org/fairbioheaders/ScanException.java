/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.fairbioheaders;

/** Exception carrying a FORMAT rule label, thrown by the byte-level scanner. */
final class ScanException extends RuntimeException {

    private final String rule;

    ScanException(String rule, String message) {
        super(message);
        this.rule = rule;
    }

    String rule() {
        return rule;
    }
}
