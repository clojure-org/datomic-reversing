// Copyright (c) Cognitect, Inc.
// All rights reserved.

// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS-IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package datomic.client.impl.shared;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * <code>InputStream</code> over a <code>ByteBuffer</code>. Duplicates the
 * buffer on construction in order to maintain its own cursor.
 *
 * @see java.io.InputStream
 * @see java.nio.ByteBuffer
 */
public class ByteBufferInputStream extends InputStream {
    private final ByteBuffer buf;

    public ByteBufferInputStream(ByteBuffer buf) {
        this.buf = buf.duplicate();
    }

    @Override
    public synchronized void reset() throws IOException {
        buf.reset();
    }

    @Override
    public synchronized void mark(int readlimit) {
        buf.mark();
    }

    @Override
    public boolean markSupported() {
        return true;
    }

    @Override
    public int read() throws IOException {
        if (!buf.hasRemaining()) {
            return -1;
        }
        int result = buf.get();
        return result & 0xff;
    }

    @Override
    public int read(byte[] bytes, int off, int len) throws IOException {
        if (len == 0) return 0;
        int bytesRead = Math.min((int) len, buf.remaining());
        if (bytesRead <= 0) {
            return -1;
        }
        buf.get(bytes, off, bytesRead);
        return bytesRead;
    }

    @Override
    public long skip(long l) throws IOException {
        // note: buf.remaining() can be negative
        int skipped = Math.min((int) l, buf.remaining());
        if (skipped <= 0) {
            return 0;
        }
        buf.position(buf.position() + skipped);
        return skipped;
    }

    @Override
    public int available() throws IOException {
        return buf.remaining();
    }
}
