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

import java.io.ByteArrayOutputStream;


public class BytesOutputStream extends ByteArrayOutputStream {
    public BytesOutputStream() {
    }

    public BytesOutputStream(int i) {
        super(i);
    }

    public byte[] internalBuffer() {
        return buf;
    }

    public int length() {
        return count;
    }
}
