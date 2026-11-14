/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.fairbioheaders;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Decompression of gzip streams, including the concatenated members a bgzip file is
 * made of. Java 17's {@link java.util.zip.GZIPInputStream} stops after the first
 * member and buffers input ahead of the member boundary, so members are walked
 * explicitly here (RFC 1952): header, raw deflate stream (input consumption read
 * back from the inflater), then the CRC32/ISIZE trailer.
 */
final class Gunzip {

    private Gunzip() {
    }

    /** True when the bytes start with the gzip magic. */
    static boolean isGzip(byte[] raw) {
        return raw.length >= 2 && (raw[0] & 0xFF) == 0x1F && (raw[1] & 0xFF) == 0x8B;
    }

    static byte[] decompress(byte[] raw) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, raw.length * 4));
        int pos = 0;
        while (pos < raw.length) {
            pos = decompressMember(raw, pos, out);
        }
        return out.toByteArray();
    }

    private static int decompressMember(byte[] raw, int pos, ByteArrayOutputStream out) throws IOException {
        if (pos + 10 > raw.length) {
            throw new IOException("truncated gzip header");
        }
        if ((raw[pos] & 0xFF) != 0x1F || (raw[pos + 1] & 0xFF) != 0x8B || raw[pos + 2] != 8) {
            throw new IOException("not a gzip member at offset " + pos);
        }
        int flags = raw[pos + 3] & 0xFF;
        if ((flags & 0xE0) != 0) {
            throw new IOException("reserved gzip header flags set");
        }
        int p = pos + 10;
        if ((flags & 0x04) != 0) { // FEXTRA
            if (p + 2 > raw.length) {
                throw new IOException("truncated gzip FEXTRA");
            }
            int xlen = (raw[p] & 0xFF) | ((raw[p + 1] & 0xFF) << 8);
            p += 2 + xlen;
        }
        if ((flags & 0x08) != 0) { // FNAME
            while (p < raw.length && raw[p] != 0) {
                p++;
            }
            p++;
        }
        if ((flags & 0x10) != 0) { // FCOMMENT
            while (p < raw.length && raw[p] != 0) {
                p++;
            }
            p++;
        }
        if ((flags & 0x02) != 0) { // FHCRC
            p += 2;
        }
        if (p > raw.length) {
            throw new IOException("truncated gzip header");
        }

        int mark = out.size();
        CRC32 crc = new CRC32();
        Inflater inflater = new Inflater(true);
        try {
            inflater.setInput(raw, p, raw.length - p);
            byte[] buffer = new byte[8192];
            while (!inflater.finished()) {
                int n;
                try {
                    n = inflater.inflate(buffer);
                } catch (DataFormatException e) {
                    throw new IOException("corrupt gzip deflate stream: " + e.getMessage(), e);
                }
                if (n == 0 && inflater.needsInput()) {
                    throw new IOException("truncated gzip deflate stream");
                }
                crc.update(buffer, 0, n);
                out.write(buffer, 0, n);
            }
            p += inflater.getBytesRead(); // first byte after this member's deflate data
        } finally {
            inflater.end();
        }
        if (p + 8 > raw.length) {
            throw new IOException("truncated gzip trailer");
        }
        long wantCrc = readLe32(raw, p);
        long wantSize = readLe32(raw, p + 4);
        if (crc.getValue() != wantCrc || ((out.size() - mark) & 0xFFFFFFFFL) != wantSize) {
            throw new IOException("corrupt gzip trailer");
        }
        return p + 8;
    }

    private static long readLe32(byte[] b, int at) {
        return (b[at] & 0xFFL) | ((b[at + 1] & 0xFFL) << 8)
            | ((b[at + 2] & 0xFFL) << 16) | ((b[at + 3] & 0xFFL) << 24);
    }
}
