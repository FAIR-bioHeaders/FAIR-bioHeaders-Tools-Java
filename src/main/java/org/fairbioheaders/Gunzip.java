/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.fairbioheaders;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Streaming decompression of gzip input, including the concatenated members a bgzip file is
 * made of. Java 17's {@link java.util.zip.GZIPInputStream} stops after the first member and
 * buffers input ahead of the member boundary, so members are walked explicitly here (RFC 1952):
 * header, raw deflate stream, then the CRC32/ISIZE trailer, checked for every member.
 *
 * <p>Nothing is held beyond one input buffer and the inflater's window: the decompressed bytes
 * are handed to the reader as they are produced, so the size of the input does not decide how
 * much memory a verification takes. After the last member, NUL padding is skipped (as Python's
 * {@code gzip} module, and with it the reference toolkit, does); any other trailing byte is an
 * error, as is a truncated or corrupt member.
 */
final class Gunzip extends InputStream {

    /** Corrupt, truncated or otherwise unreadable gzip input: the file, not the disk, is at fault. */
    static final class FormatException extends IOException {
        private static final long serialVersionUID = 1L;

        FormatException(String message) {
            super(message);
        }

        FormatException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final InputStream in;
    private final byte[] buf = new byte[1 << 16];
    private int pos;
    private int lim;
    private boolean inputEnded;

    private Inflater inflater;
    private final CRC32 crc = new CRC32();
    private long memberSize;
    private boolean finished;
    private boolean started;

    private Gunzip(InputStream in) {
        this.in = in;
    }

    /** True when the bytes start with the gzip magic. */
    static boolean isGzip(byte[] raw) {
        return raw.length >= 2 && (raw[0] & 0xFF) == 0x1F && (raw[1] & 0xFF) == 0x8B;
    }

    /**
     * {@code in} itself, or a decompressing view of it when it starts with the gzip magic.
     * {@code in} must support mark/reset (a {@link java.io.BufferedInputStream} does).
     */
    static InputStream maybeDecompress(InputStream in) throws IOException {
        in.mark(2);
        int a = in.read();
        int b = a < 0 ? -1 : in.read();
        in.reset();
        return a == 0x1F && b == 0x8B ? new Gunzip(in) : in;
    }

    /**
     * Decompress a whole in-memory gzip stream, refusing to produce more than {@code limit}
     * bytes (a small file can inflate a thousandfold).
     */
    static byte[] decompress(byte[] raw, long limit) throws IOException {
        try (InputStream gz = new Gunzip(new ByteArrayInputStream(raw))) {
            return readBounded(gz, limit);
        }
    }

    /** Read a stream to its end, or fail cleanly once it passes {@code limit} bytes. */
    static byte[] readBounded(InputStream in, long limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[1 << 16];
        long total = 0;
        int n;
        while ((n = in.read(chunk)) > 0) {
            total += n;
            if (total > limit) {
                throw new IOException("the input is larger than the " + (limit >> 20)
                    + " MiB this in-memory call accepts; verify the file with"
                    + " BioHeaders.verifyFile, which streams it");
            }
            out.write(chunk, 0, n);
        }
        return out.toByteArray();
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        return n < 0 ? -1 : one[0] & 0xFF;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (len == 0) {
            return 0;
        }
        while (true) {
            if (finished) {
                return -1;
            }
            if (inflater == null) {
                if (!startMember()) {
                    finished = true;
                    return -1;
                }
            }
            int n;
            try {
                n = inflater.inflate(b, off, len);
            } catch (DataFormatException e) {
                throw new FormatException("corrupt gzip deflate stream: " + e.getMessage(), e);
            }
            if (n > 0) {
                crc.update(b, off, n);
                memberSize += n;
                return n;
            }
            if (inflater.finished()) {
                pos = lim - inflater.getRemaining();
                inflater.end();
                inflater = null;
                finishMember();
                continue;
            }
            if (inflater.needsDictionary()) {
                throw new FormatException("corrupt gzip deflate stream: preset dictionary");
            }
            if (inflater.needsInput()) {
                pos = lim;
                if (!fill()) {
                    throw new FormatException("truncated gzip deflate stream");
                }
                inflater.setInput(buf, pos, lim - pos);
            }
        }
    }

    @Override
    public void close() throws IOException {
        if (inflater != null) {
            inflater.end();
            inflater = null;
        }
        in.close();
    }

    /** Parse the next member header; false at the clean end of the stream. */
    private boolean startMember() throws IOException {
        if (started) {
            // Between members, and after the last one, NUL padding is skipped (Python gzip).
            int c;
            do {
                c = next();
            } while (c == 0);
            if (c < 0) {
                return false;
            }
            pos--;
        }
        started = true;
        int id1 = next();
        int id2 = next();
        if (id1 != 0x1F || id2 != 0x8B) {
            throw new FormatException(id2 < 0 && id1 >= 0
                ? "truncated gzip header" : "not a gzip member: trailing bytes after the gzip data");
        }
        int method = next();
        int flags = next();
        if (method < 0 || flags < 0) {
            throw new FormatException("truncated gzip header");
        }
        if (method != 8) {
            throw new FormatException("unknown gzip compression method " + method);
        }
        if ((flags & 0xE0) != 0) {
            throw new FormatException("reserved gzip header flags set");
        }
        skip(6); // MTIME, XFL, OS
        if ((flags & 0x04) != 0) { // FEXTRA
            int lo = next();
            int hi = next();
            if (lo < 0 || hi < 0) {
                throw new FormatException("truncated gzip FEXTRA");
            }
            skip(lo | (hi << 8));
        }
        if ((flags & 0x08) != 0) { // FNAME
            skipZeroTerminated();
        }
        if ((flags & 0x10) != 0) { // FCOMMENT
            skipZeroTerminated();
        }
        if ((flags & 0x02) != 0) { // FHCRC
            skip(2);
        }
        crc.reset();
        memberSize = 0;
        inflater = new Inflater(true);
        if (pos == lim && !fill()) {
            throw new FormatException("truncated gzip deflate stream");
        }
        inflater.setInput(buf, pos, lim - pos);
        return true;
    }

    private void finishMember() throws IOException {
        long wantCrc = readLe32("truncated gzip trailer");
        long wantSize = readLe32("truncated gzip trailer");
        if (crc.getValue() != wantCrc) {
            throw new FormatException("corrupt gzip trailer: CRC-32 check failed");
        }
        if ((memberSize & 0xFFFFFFFFL) != wantSize) {
            throw new FormatException("corrupt gzip trailer: length check failed");
        }
    }

    private long readLe32(String truncated) throws IOException {
        long value = 0;
        for (int k = 0; k < 4; k++) {
            int c = next();
            if (c < 0) {
                throw new FormatException(truncated);
            }
            value |= ((long) c) << (8 * k);
        }
        return value;
    }

    private void skip(int count) throws IOException {
        for (int k = 0; k < count; k++) {
            if (next() < 0) {
                throw new FormatException("truncated gzip header");
            }
        }
    }

    private void skipZeroTerminated() throws IOException {
        int c;
        do {
            c = next();
            if (c < 0) {
                throw new FormatException("truncated gzip header");
            }
        } while (c != 0);
    }

    /** The next raw byte, or -1 at the end of the input. */
    private int next() throws IOException {
        if (pos == lim && !fill()) {
            return -1;
        }
        return buf[pos++] & 0xFF;
    }

    /** Refill the empty buffer; false at the end of the input. */
    private boolean fill() throws IOException {
        if (inputEnded) {
            return false;
        }
        int n = in.read(buf, 0, buf.length);
        while (n == 0) {
            n = in.read(buf, 0, buf.length);
        }
        if (n < 0) {
            inputEnded = true;
            pos = lim = 0;
            return false;
        }
        pos = 0;
        lim = n;
        return true;
    }
}
