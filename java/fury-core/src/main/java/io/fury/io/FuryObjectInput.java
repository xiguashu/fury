/*
 * Copyright 2023 The Fury Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fury.io;

import io.fury.Fury;
import io.fury.memory.MemoryBuffer;
import io.fury.serializer.StringSerializer;
import io.fury.util.Preconditions;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInput;

/**
 * ObjectInput based on {@link Fury} and {@link MemoryBuffer}.
 *
 * @author chaokunyang
 */
public class FuryObjectInput extends InputStream implements ObjectInput {
  private final Fury fury;
  private MemoryBuffer buffer;
  private final StringSerializer stringSerializer;

  public FuryObjectInput(Fury fury, MemoryBuffer buffer) {
    this.fury = fury;
    this.buffer = buffer;
    this.stringSerializer = new StringSerializer(fury);
  }

  public MemoryBuffer getBuffer() {
    return buffer;
  }

  public void setBuffer(MemoryBuffer buffer) {
    this.buffer = buffer;
  }

  @Override
  public Object readObject() throws ClassNotFoundException, IOException {
    return fury.readRef(buffer);
  }

  @Override
  public int read() throws IOException {
    return buffer.readByte();
  }

  @Override
  public int read(byte[] b, int off, int len) throws IOException {
    int available = available();
    if (available == 0) {
      return -1;
    }

    len = Math.min(available, len);
    buffer.readBytes(b, off, len);
    return len;
  }

  @Override
  public long skip(long n) throws IOException {
    Preconditions.checkArgument(n < Integer.MAX_VALUE);
    int newIndex = Math.addExact(buffer.readerIndex(), (int) n);
    Preconditions.checkArgument(newIndex < buffer.size());
    buffer.readerIndex(newIndex);
    return n;
  }

  @Override
  public int available() throws IOException {
    return buffer.size() - buffer.readerIndex();
  }

  @Override
  public void close() throws IOException {}

  @Override
  public void readFully(byte[] b) throws IOException {
    readFully(b, 0, b.length);
  }

  @Override
  public void readFully(byte[] b, int off, int len) throws IOException {
    buffer.readBytes(b, off, len);
  }

  @Override
  public int skipBytes(int n) throws IOException {
    buffer.readerIndex(buffer.readerIndex() + n);
    return n;
  }

  @Override
  public boolean readBoolean() throws IOException {
    return buffer.readBoolean();
  }

  @Override
  public byte readByte() throws IOException {
    return buffer.readByte();
  }

  @Override
  public int readUnsignedByte() throws IOException {
    return buffer.readByte() & 0xFF;
  }

  @Override
  public short readShort() throws IOException {
    return buffer.readShort();
  }

  @Override
  public int readUnsignedShort() throws IOException {
    return buffer.readShort() & 0xffff;
  }

  @Override
  public char readChar() throws IOException {
    return buffer.readChar();
  }

  @Override
  public int readInt() throws IOException {
    return buffer.readInt();
  }

  @Override
  public long readLong() throws IOException {
    return buffer.readLong();
  }

  @Override
  public float readFloat() throws IOException {
    return buffer.readFloat();
  }

  @Override
  public double readDouble() throws IOException {
    return buffer.readDouble();
  }

  @Override
  public String readLine() throws IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  public String readUTF() throws IOException {
    return stringSerializer.readJavaString(buffer);
  }
}
