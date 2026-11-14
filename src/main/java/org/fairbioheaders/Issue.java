/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.fairbioheaders;

import java.util.Objects;

/** One finding from a verification run: rule label (FORMAT.md Rn, FHGFF3.md Gn,
 * SCHEMA-VERSIONS.md Vn, or a toolkit label such as {@code schema}) and a message. */
public final class Issue {

    private final Severity severity;
    private final String rule;
    private final String message;

    public Issue(Severity severity, String rule, String message) {
        this.severity = Objects.requireNonNull(severity);
        this.rule = Objects.requireNonNull(rule);
        this.message = Objects.requireNonNull(message);
    }

    public static Issue error(String rule, String message) {
        return new Issue(Severity.ERROR, rule, message);
    }

    public static Issue warning(String rule, String message) {
        return new Issue(Severity.WARNING, rule, message);
    }

    public Severity severity() {
        return severity;
    }

    public String rule() {
        return rule;
    }

    public String message() {
        return message;
    }

    @Override
    public String toString() {
        return severity + " [" + rule + "] " + message;
    }
}
