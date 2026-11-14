/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.fairbioheaders;

/** The header type a record is read as, per FAIR-bioHeaders-Specification docs/SCHEMA-VERSIONS.md. */
public enum FhType {
    FHR("fhr", "FHR"),
    FHP("fhp", "FHP"),
    FHGFF3("fhgff3", "FHGFF3");

    private final String urlKey;
    private final String fileKey;

    FhType(String urlKey, String fileKey) {
        this.urlKey = urlKey;
        this.fileKey = fileKey;
    }

    /** Lower-case name as it appears in schema URLs (``fhr``, ``fhp``, ``fhgff3``). */
    public String urlKey() {
        return urlKey;
    }

    /** Name used in ``schemas/bundled.json`` (``FHR``, ``FHP``, ``FHGFF3``). */
    public String fileKey() {
        return fileKey;
    }
}
