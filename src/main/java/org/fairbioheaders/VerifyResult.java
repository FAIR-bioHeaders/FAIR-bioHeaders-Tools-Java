/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.fairbioheaders;

import java.util.List;

import tools.jackson.databind.JsonNode;

/** The outcome of reading, checksum-verifying and validating one headed file. */
public final class VerifyResult {

    /** Coarse verdict. {@code UNSUPPORTED_VERSION} (rule V4) is a failure, not a pass. */
    public enum Status {
        VALID,
        INVALID,
        UNSUPPORTED_VERSION
    }

    private final Status status;
    private final String statedChecksum;
    private final String computedChecksum;
    private final JsonNode metadata;
    private final List<Issue> issues;

    VerifyResult(Status status, String statedChecksum, String computedChecksum,
                 JsonNode metadata, List<Issue> issues) {
        this.status = status;
        this.statedChecksum = statedChecksum;
        this.computedChecksum = computedChecksum;
        this.metadata = metadata;
        this.issues = List.copyOf(issues);
    }

    public Status status() {
        return status;
    }

    public boolean isValid() {
        return status == Status.VALID;
    }

    /** The checksum value the file's own {@code checksum} line states (R5). */
    public String statedChecksum() {
        return statedChecksum;
    }

    /** The SHA-512/256 digest this reader computed over the covered bytes (R3/R4). */
    public String computedChecksum() {
        return computedChecksum;
    }

    /** The parsed header metadata, when the header YAML loaded. */
    public JsonNode metadata() {
        return metadata;
    }

    public List<Issue> issues() {
        return issues;
    }

    public List<Issue> errors() {
        return issues.stream().filter(i -> i.severity() == Severity.ERROR).toList();
    }

    public List<Issue> warnings() {
        return issues.stream().filter(i -> i.severity() == Severity.WARNING).toList();
    }

    /** True when any issue carries the given rule label. */
    public boolean hasRule(String rule) {
        return issues.stream().anyMatch(i -> i.rule().equals(rule));
    }

    @Override
    public String toString() {
        return status + (errors().isEmpty() ? "" : " " + errors());
    }
}
