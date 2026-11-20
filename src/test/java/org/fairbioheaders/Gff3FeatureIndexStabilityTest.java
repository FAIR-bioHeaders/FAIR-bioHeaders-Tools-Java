/* SPDX-License-Identifier: MPL-2.0 */
package org.fairbioheaders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class Gff3FeatureIndexStabilityTest {
    @Test
    void repeatedLookupsAndSilentDuplicateRowsCannotEraseAParent() {
        byte[] data = ("ctg\tx\tgene\t1\t10\t.\t+\t.\tID=gene1;Parent=outer\n"
            + "ctg\tx\tgene\t1\t10\t.\t+\t.\tID=gene1\n").getBytes(StandardCharsets.UTF_8);
        Gff3FeatureIndex index = Gff3FeatureIndex.of(data);
        for (int i=0;i<3;i++) {
            assertEquals(List.of("gene","gene"), index.typesOf("gene1"));
            assertEquals(Gff3FeatureIndex.ParentState.NESTED, index.parentOf("gene1"));
            assertEquals(1, index.size());
        }
    }
}
