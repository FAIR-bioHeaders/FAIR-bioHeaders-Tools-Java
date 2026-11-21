/* SPDX-License-Identifier: MPL-2.0 */
package org.fairbioheaders;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class SchemaVersionIsolationTest {
    @Test
    void sameDeclaredSchemaIdDoesNotAliasDifferentReleaseBytesInEitherOrder() {
        var record = new BioHeaders().verify(Fixtures.fhrFasta(">sequence\nACGT\n"), Kind.FASTA).metadata();
        for (boolean oldFirst : new boolean[] {true, false}) {
            var bundle = new SchemaBundle();
            if (oldFirst) bundle.contract(FhType.FHR, "v0.4.0");
            else bundle.contract(FhType.FHR, "v0.6.0");
            var oldContract = bundle.contract(FhType.FHR, "v0.4.0");
            var currentContract = bundle.contract(FhType.FHR, "v0.6.0");
            assertFalse(SchemaBundle.validate(oldContract, record).isEmpty(),
                "FHR2.0 contract must reject the newer schemaVersion2.1 record");
            assertTrue(SchemaBundle.validate(currentContract, record).isEmpty(),
                "FHR2.1 contract must accept its own complete valid record");
            assertFalse(SchemaBundle.validate(oldContract, record).isEmpty(),
                "revisiting the older contract after newer validation must stay isolated");
        }
    }
}
