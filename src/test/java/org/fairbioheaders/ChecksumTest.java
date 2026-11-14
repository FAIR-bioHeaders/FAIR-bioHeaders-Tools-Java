/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.fairbioheaders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.MessageDigest;
import java.util.Base64;

import org.junit.jupiter.api.Test;

/**
 * SHA-512/256 availability and exactness (FORMAT.md R4). The reference values were
 * computed on 2026-11-14 with Python's {@code hashlib.new("sha512_256")} (the same
 * primitive the fair-bioheaders toolkit uses) and with the released toolkit's
 * {@code bioheaders.cli.checksum()} on a real vector; see docs/STATUS.md.
 */
class ChecksumTest {

    private static MessageDigest digest() {
        return HeaderScan.newDigest();
    }

    @Test
    void jdkProvidesSha512_256WithCorrectAnchors() {
        byte[] abc = digest().digest("abc".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(32, abc.length);
        assertEquals("UwSOJoGUHvmbLim3a0x9q+TC0MY0/G1G4OLxMQfnryM=",
            Base64.getEncoder().encodeToString(abc));
        byte[] empty = digest().digest(new byte[0]);
        assertEquals("xnK40e9W7Sirh8NiLFEUBpvdOte4+XN0mNDAHs7wlno=",
            Base64.getEncoder().encodeToString(empty));
    }

    @Test
    void digestIsNotTruncatedSha512() {
        byte[] real = digest().digest("abc".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] fake;
        try {
            fake = MessageDigest.getInstance("SHA-512")
                .digest("abc".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        assertTrue(!Base64.getEncoder().encodeToString(real)
            .equals(Base64.getEncoder().encodeToString(java.util.Arrays.copyOf(fake, 32))),
            "SHA-512/256 must not be computed as truncated SHA-512 (R4)");
    }

    @Test
    void canonicalBase64LastCharAlphabetIsEnforced() {
        // R4: the character before "=" must be one of AEIMQUYcgkosw048.
        String good = "Z1p/siczUlN/N+lDB5Kg6vFRrztGErgLw9H4MfVfZeU=";
        assertTrue(HeaderScan.CHECKSUM_LINE.matcher("checksum: " + good).matches());
        String bad = good.substring(0, 42) + "B" + good.substring(43);
        assertTrue(!HeaderScan.CHECKSUM_LINE.matcher("checksum: " + bad).matches());
    }
}
