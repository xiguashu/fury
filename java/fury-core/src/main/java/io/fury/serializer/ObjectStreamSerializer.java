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

package io.fury.serializer;

import io.fury.Fury;
import io.fury.builder.CodecUtils;
import io.fury.builder.Generated;
import io.fury.collection.ObjectArray;
import io.fury.collection.ObjectIntMap;
import io.fury.memory.MemoryBuffer;
import io.fury.resolver.ClassInfo;
import io.fury.resolver.ClassResolver;
import io.fury.resolver.FieldResolver;
import io.fury.resolver.FieldResolver.ClassField;
import io.fury.util.LoggerFactory;
import io.fury.util.Platform;
import io.fury.util.Preconditions;
import io.fury.util.ReflectionUtils;
import io.fury.util.Utils;
import io.fury.util.unsafe._JDKAccess;
import java.io.Externalizable;
import java.io.IOException;
import java.io.InvalidObjectException;
import java.io.NotActiveException;
import java.io.ObjectInputStream;
import java.io.ObjectInputValidation;
import java.io.ObjectOutput;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.io.ObjectStreamField;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.slf4j.Logger;

/**
 * Implement jdk custom serialization only if following conditions are met:
 *
 * <ul>
 *   <li>`writeObject/readObject` occurs only at current class in class hierarchy.
 *   <li>`writeReplace/readResolve` don't occur in class hierarchy.
 *   <li>class hierarchy doesn't have duplicated fields. If any of those conditions are not met,
 *       fallback jdk custom serialization to {@link JavaSerializer}.
 * </ul>
 *
 * <p>`ObjectInputStream#setObjectInputFilter` will be ignored by this serializer.
 *
 * @author chaokunyang
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public class ObjectStreamSerializer extends Serializer {
  private static final Logger LOG = LoggerFactory.getLogger(ObjectStreamSerializer.class);

  private final Constructor constructor;
  private final ClassResolver classResolver;
  private final SlotsInfo[] slotsInfos;

  public ObjectStreamSerializer(Fury fury, Class<?> type) {
    super(fury, type);
    if (!Serializable.class.isAssignableFrom(type)) {
      throw new IllegalArgumentException(
          String.format("Class %s should implement %s.", type, Serializable.class));
    }
    LOG.warn(
        "{} customized jdk serialization, which is inefficient. "
            + "Please replace it with a {} or implements {}",
        type,
        Serializer.class.getName(),
        Externalizable.class.getName());
    // stream serializer may be data serializer of ReplaceResolver serializer.
    fury.getClassResolver().setSerializerIfAbsent(type, this);
    Constructor constructor;
    try {
      constructor = type.getConstructor();
      if (!constructor.isAccessible()) {
        constructor.setAccessible(true);
      }
    } catch (Exception e) {
      constructor =
          (Constructor) ReflectionUtils.getObjectFieldValue(ObjectStreamClass.lookup(type), "cons");
    }
    this.classResolver = fury.getClassResolver();
    this.constructor = constructor;
    List<SlotsInfo> slotsInfoList = new ArrayList<>();
    Class<?> end = type;
    // locate closest non-serializable superclass
    while (end != null && Serializable.class.isAssignableFrom(end)) {
      end = end.getSuperclass();
    }
    while (type != end) {
      slotsInfoList.add(new SlotsInfo(fury, type));
      type = type.getSuperclass();
    }
    Collections.reverse(slotsInfoList);
    slotsInfos = slotsInfoList.toArray(new SlotsInfo[0]);
  }

  @Override
  public void write(MemoryBuffer buffer, Object value) {
    buffer.writeShort((short) slotsInfos.length);
    try {
      for (SlotsInfo slotsInfo : slotsInfos) {
        // create a classinfo to avoid null class bytes when class id is a
        // replacement id.
        classResolver.writeClass(buffer, slotsInfo.classInfo);
        Method writeObjectMethod = slotsInfo.writeObjectMethod;
        if (writeObjectMethod == null) {
          slotsInfo.slotsSerializer.write(buffer, value);
        } else {
          FuryObjectOutputStream objectOutputStream = slotsInfo.objectOutputStream;
          Object oldObject = objectOutputStream.targetObject;
          MemoryBuffer oldBuffer = objectOutputStream.buffer;
          FuryObjectOutputStream.PutFieldImpl oldPutField = objectOutputStream.curPut;
          boolean fieldsWritten = objectOutputStream.fieldsWritten;
          try {
            objectOutputStream.targetObject = value;
            objectOutputStream.buffer = buffer;
            objectOutputStream.curPut = null;
            objectOutputStream.fieldsWritten = false;
            if (slotsInfo.writeObjectFunc != null) {
              slotsInfo.writeObjectFunc.accept(value, objectOutputStream);
            } else {
              writeObjectMethod.invoke(value, objectOutputStream);
            }
          } finally {
            objectOutputStream.targetObject = oldObject;
            objectOutputStream.buffer = oldBuffer;
            objectOutputStream.curPut = oldPutField;
            objectOutputStream.fieldsWritten = fieldsWritten;
          }
        }
      }
    } catch (Exception e) {
      throwSerializationException(type, e);
    }
  }

  @Override
  public Object read(MemoryBuffer buffer) {
    Object obj = null;
    if (constructor != null) {
      try {
        obj = constructor.newInstance();
      } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
        Platform.throwException(e);
      }
    } else {
      obj = Platform.newInstance(type);
    }
    fury.getRefResolver().reference(obj);
    int numClasses = buffer.readShort();
    int slotIndex = 0;
    try {
      TreeMap<Integer, ObjectInputValidation> callbacks = new TreeMap<>(Collections.reverseOrder());
      for (int i = 0; i < numClasses; i++) {
        Class<?> currentClass = classResolver.readClassInternal(buffer);
        SlotsInfo slotsInfo = slotsInfos[slotIndex++];
        while (currentClass != slotsInfo.cls) {
          // the receiver's version extends classes that are not extended by the sender's version.
          Method readObjectNoData = slotsInfo.readObjectNoData;
          if (readObjectNoData != null) {
            if (slotsInfo.readObjectNoDataFunc != null) {
              slotsInfo.readObjectNoDataFunc.accept(obj);
            } else {
              readObjectNoData.invoke(obj);
            }
          }
          slotsInfo = slotsInfos[slotIndex++];
        }
        Method readObjectMethod = slotsInfo.readObjectMethod;
        if (readObjectMethod == null) {
          slotsInfo.slotsSerializer.readAndSetFields(buffer, obj);
        } else {
          FuryObjectInputStream objectInputStream = slotsInfo.objectInputStream;
          MemoryBuffer oldBuffer = objectInputStream.buffer;
          Object oldObject = objectInputStream.targetObject;
          FuryObjectInputStream.GetFieldImpl oldGetField = objectInputStream.getField;
          FuryObjectInputStream.GetFieldImpl getField =
              (FuryObjectInputStream.GetFieldImpl) slotsInfo.getFieldPool.popOrNull();
          if (getField == null) {
            getField = new FuryObjectInputStream.GetFieldImpl(slotsInfo);
          }
          boolean fieldsRead = objectInputStream.fieldsRead;
          try {
            objectInputStream.fieldsRead = false;
            objectInputStream.buffer = buffer;
            objectInputStream.targetObject = obj;
            objectInputStream.getField = getField;
            objectInputStream.callbacks = callbacks;
            if (slotsInfo.readObjectFunc != null) {
              slotsInfo.readObjectFunc.accept(obj, objectInputStream);
            } else {
              readObjectMethod.invoke(obj, objectInputStream);
            }
          } finally {
            objectInputStream.fieldsRead = fieldsRead;
            objectInputStream.buffer = oldBuffer;
            objectInputStream.targetObject = oldObject;
            objectInputStream.getField = oldGetField;
            slotsInfo.getFieldPool.add(getField);
            objectInputStream.callbacks = null;
            Arrays.fill(getField.vals, FuryObjectInputStream.NO_VALUE_STUB);
          }
        }
      }
      for (ObjectInputValidation validation : callbacks.values()) {
        validation.validateObject();
      }
    } catch (InvocationTargetException | IllegalAccessException | InvalidObjectException e) {
      throwSerializationException(type, e);
    }
    return obj;
  }

  private static void throwUnsupportedEncodingException(Class<?> cls)
      throws UnsupportedEncodingException {
    throw new UnsupportedEncodingException(
        String.format(
            "Use %s instead by `fury.registerSerializer(%s, new JavaSerializer(fury, %s))` or "
                + "implement a custom %s.",
            JavaSerializer.class, cls, cls, Serializer.class));
  }

  private static void throwSerializationException(Class<?> type, Exception e) {
    throw new RuntimeException(
        String.format(
            "Serialize object of type %s failed, "
                + "Try to use %s instead by `fury.registerSerializer(%s, new JavaSerializer(fury, %s))` or "
                + "implement a custom %s.",
            type, JavaSerializer.class, type, type, Serializer.class),
        e);
  }

  private static class SlotsInfo {
    private final Class<?> cls;
    private final ClassInfo classInfo;
    private final Method writeObjectMethod;
    private final Method readObjectMethod;
    private final Method readObjectNoData;
    private final BiConsumer writeObjectFunc;
    private final BiConsumer readObjectFunc;
    private final Consumer readObjectNoDataFunc;
    // mark non-final for async-jit to update it to jit-serializer.
    private CompatibleSerializerBase slotsSerializer;
    private final ObjectIntMap<String> fieldIndexMap;
    private final FieldResolver putFieldsResolver;
    private final CompatibleSerializer compatibleStreamSerializer;
    private final FuryObjectOutputStream objectOutputStream;
    private final FuryObjectInputStream objectInputStream;
    private final ObjectArray getFieldPool;

    public SlotsInfo(Fury fury, Class<?> type) {
      this.cls = type;
      classInfo = fury.getClassResolver().newClassInfo(type, null, ClassResolver.NO_CLASS_ID);
      ObjectStreamClass objectStreamClass = ObjectStreamClass.lookup(type);
      // In JDK17, set private jdk method accessible will fail by default, use ObjectStreamClass
      // instead, since it set accessible.
      writeObjectMethod =
          (Method) ReflectionUtils.getObjectFieldValue(objectStreamClass, "writeObjectMethod");
      readObjectMethod =
          (Method) ReflectionUtils.getObjectFieldValue(objectStreamClass, "readObjectMethod");
      readObjectNoData =
          (Method) ReflectionUtils.getObjectFieldValue(objectStreamClass, "readObjectNoData");
      MethodHandles.Lookup lookup = _JDKAccess._trustedLookup(type);
      BiConsumer writeObjectFunc = null, readObjectFunc = null;
      Consumer readObjectNoDataFunc = null;
      try {
        if (writeObjectMethod != null) {
          writeObjectFunc =
              _JDKAccess.makeJDKBiConsumer(lookup, lookup.unreflect(writeObjectMethod));
        }
        if (readObjectMethod != null) {
          readObjectFunc = _JDKAccess.makeJDKBiConsumer(lookup, lookup.unreflect(readObjectMethod));
        }
        if (readObjectNoData != null) {
          readObjectNoDataFunc =
              _JDKAccess.makeJDKConsumer(lookup, lookup.unreflect(readObjectNoData));
        }
      } catch (Exception e) {
        Utils.ignore(e);
      }
      this.writeObjectFunc = writeObjectFunc;
      this.readObjectFunc = readObjectFunc;
      this.readObjectNoDataFunc = readObjectNoDataFunc;
      // `putFields/writeFields` will convert to fields value to be written by
      // `CompatibleSerializer`,
      // since `put` values may not exist in current class, which means container generic type are
      // unavailable.
      // (`serialPersistentFields` field can't provide generics.)
      // So we need to mark all container fields type to `Object` to avoid reader deserialize data
      // using field generic types.
      Class<? extends Serializer> sc = CompatibleSerializer.class;
      FieldResolver fieldResolver = FieldResolver.of(fury, type, false, true);
      if (fury.getConfig().isCodeGenEnabled()
          && CodegenSerializer.supportCodegenForJavaSerialization(cls)) {
        sc =
            fury.getJITContext()
                .registerSerializerJITCallback(
                    () -> CompatibleSerializer.class,
                    () ->
                        CodecUtils.loadOrGenCompatibleCodecClass(
                            cls,
                            fury,
                            fieldResolver,
                            Generated.GeneratedCompatibleSerializer.class),
                    c ->
                        this.slotsSerializer =
                            (CompatibleSerializerBase) Serializers.newSerializer(fury, type, c));
      }
      if (sc == CompatibleSerializer.class) {
        this.slotsSerializer = new CompatibleSerializer(fury, type, fieldResolver);
      } else {
        this.slotsSerializer = (CompatibleSerializerBase) Serializers.newSerializer(fury, type, sc);
      }
      fieldIndexMap = new ObjectIntMap<>(4, 0.4f);
      List<ClassField> allFields = new ArrayList<>();
      for (ObjectStreamField serialField : objectStreamClass.getFields()) {
        allFields.add(new ClassField(serialField.getName(), serialField.getType(), cls));
      }
      if (writeObjectMethod != null || readObjectMethod != null) {
        putFieldsResolver = new FieldResolver(fury, cls, true, allFields, new HashSet<>());
        AtomicInteger idx = new AtomicInteger(0);
        for (FieldResolver.FieldInfo fieldInfo : putFieldsResolver.getAllFieldsList()) {
          fieldIndexMap.put(fieldInfo.getName(), idx.getAndIncrement());
        }
        compatibleStreamSerializer = new CompatibleSerializer(fury, cls, putFieldsResolver);
      } else {
        putFieldsResolver = null;
        compatibleStreamSerializer = null;
      }
      if (writeObjectMethod != null) {
        try {
          objectOutputStream = new FuryObjectOutputStream(this);
        } catch (IOException e) {
          Platform.throwException(e);
          throw new IllegalStateException("unreachable");
        }
      } else {
        objectOutputStream = null;
      }
      if (readObjectMethod != null) {
        try {
          objectInputStream = new FuryObjectInputStream(this);
        } catch (IOException e) {
          Platform.throwException(e);
          throw new IllegalStateException("unreachable");
        }
      } else {
        objectInputStream = null;
      }
      getFieldPool = new ObjectArray();
    }

    @Override
    public String toString() {
      return "SlotsInfo{" + "cls=" + cls + '}';
    }
  }

  /**
   * Implement serialization for object output with `writeObject/readObject` defined by java
   * serialization output spec.
   *
   * @see <a href="https://docs.oracle.com/en/java/javase/18/docs/specs/serialization/output.html">
   *     Java Object Serialization Output Specification</a>
   */
  private static class FuryObjectOutputStream extends ObjectOutputStream {
    private final Fury fury;
    private final SlotsInfo slotsInfo;
    private MemoryBuffer buffer;
    private Object targetObject;
    private boolean fieldsWritten;

    protected FuryObjectOutputStream(SlotsInfo slotsInfo) throws IOException {
      super();
      this.slotsInfo = slotsInfo;
      this.fury = slotsInfo.slotsSerializer.fury;
    }

    protected final void writeObjectOverride(Object obj) throws IOException {
      fury.writeRef(buffer, obj);
    }

    public void writeUnshared(Object obj) throws IOException {
      fury.writeNonRef(buffer, obj);
    }

    /**
     * PutField is used to write fields which may not exists in current class for compatibility.
     * Note that the protocol should be compatible with `defaultReadObject`.
     *
     * @see <a
     *     href="https://docs.oracle.com/en/java/javase/18/docs/specs/serialization/input.html#the-objectinputstream.getfield-class">ObjectInputStream.GetField</a>
     * @see ConcurrentHashMap
     */
    // See `defaultReadObject` in ConcurrentHashMap#readObject skip fields written by
    // `writeFields()`.
    private class PutFieldImpl extends PutField {
      private final Object[] vals;

      PutFieldImpl() {
        vals = new Object[slotsInfo.putFieldsResolver.getNumFields()];
      }

      private void putValue(String name, Object val) {
        int index = slotsInfo.fieldIndexMap.get(name, -1);
        if (index == -1) {
          throw new IllegalArgumentException(
              String.format(
                  "Field name %s not exist in class %s", name, slotsInfo.slotsSerializer.type));
        }
        vals[index] = val;
      }

      @Override
      public void put(String name, boolean val) {
        putValue(name, val);
      }

      @Override
      public void put(String name, byte val) {
        putValue(name, val);
      }

      @Override
      public void put(String name, char val) {
        putValue(name, val);
      }

      @Override
      public void put(String name, short val) {
        putValue(name, val);
      }

      @Override
      public void put(String name, int val) {
        putValue(name, val);
      }

      @Override
      public void put(String name, long val) {
        putValue(name, val);
      }

      @Override
      public void put(String name, float val) {
        putValue(name, val);
      }

      @Override
      public void put(String name, double val) {
        putValue(name, val);
      }

      @Override
      public void put(String name, Object val) {
        putValue(name, val);
      }

      @Deprecated
      @Override
      public void write(ObjectOutput out) throws IOException {
        Class cls = slotsInfo.slotsSerializer.type;
        throwUnsupportedEncodingException(cls);
      }
    }

    private final ObjectArray putFieldsCache = new ObjectArray();
    private PutFieldImpl curPut;

    public PutField putFields() throws IOException {
      if (curPut == null) {
        Object o = putFieldsCache.popOrNull();
        if (o == null) {
          o = new PutFieldImpl();
        }
        curPut = (PutFieldImpl) o;
      }
      return curPut;
    }

    public void writeFields() throws IOException {
      if (fieldsWritten) {
        throw new NotActiveException("not in writeObject invocation or fields already written");
      }
      PutFieldImpl curPut = this.curPut;
      if (curPut == null) {
        throw new NotActiveException("no current PutField object");
      }
      slotsInfo.compatibleStreamSerializer.writeFieldsValues(buffer, curPut.vals);
      Arrays.fill(curPut.vals, null);
      putFieldsCache.add(curPut);
      this.curPut = null;
      fieldsWritten = true;
    }

    public void defaultWriteObject() throws IOException, NotActiveException {
      if (fieldsWritten) {
        throw new NotActiveException("not in writeObject invocation or fields already written");
      }
      slotsInfo.slotsSerializer.write(buffer, targetObject);
      fieldsWritten = true;
    }

    public void reset() throws IOException {
      Class cls = slotsInfo.slotsSerializer.getType();
      // Fury won't invoke this method, throw exception if the user invokes it.
      throwUnsupportedEncodingException(cls);
    }

    protected void annotateClass(Class<?> cl) throws IOException {
      throw new IllegalStateException();
    }

    protected void annotateProxyClass(Class<?> cl) throws IOException {
      throw new IllegalStateException();
    }

    protected void writeClassDescriptor(ObjectStreamClass desc) throws IOException {
      throw new UnsupportedEncodingException();
    }

    protected Object replaceObject(Object obj) throws IOException {
      throw new UnsupportedEncodingException();
    }

    protected boolean enableReplaceObject(boolean enable) throws SecurityException {
      throw new IllegalStateException();
    }

    protected void writeStreamHeader() throws IOException {
      throw new IllegalStateException();
    }

    @Override
    public void write(int b) throws IOException {
      buffer.writeByte((byte) b);
    }

    @Override
    public void write(byte[] b) throws IOException {
      buffer.writeBytes(b);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      buffer.writeBytes(b, off, len);
    }

    @Override
    public void writeBoolean(boolean v) throws IOException {
      buffer.writeBoolean(v);
    }

    @Override
    public void writeByte(int v) throws IOException {
      buffer.writeByte((byte) v);
    }

    @Override
    public void writeShort(int v) throws IOException {
      buffer.writeShort((short) v);
    }

    @Override
    public void writeChar(int v) throws IOException {
      buffer.writeChar((char) v);
    }

    @Override
    public void writeInt(int v) throws IOException {
      buffer.writeInt(v);
    }

    @Override
    public void writeLong(long v) throws IOException {
      buffer.writeLong(v);
    }

    @Override
    public void writeFloat(float v) throws IOException {
      buffer.writeFloat(v);
    }

    @Override
    public void writeDouble(double v) throws IOException {
      buffer.writeDouble(v);
    }

    @Override
    public void writeBytes(String s) throws IOException {
      Preconditions.checkNotNull(s);
      int len = s.length();
      for (int i = 0; i < len; i++) {
        buffer.writeByte((byte) s.charAt(i));
      }
    }

    @Override
    public void writeChars(String s) throws IOException {
      Preconditions.checkNotNull(s);
      fury.writeJavaString(buffer, s);
    }

    @Override
    public void writeUTF(String s) throws IOException {
      Preconditions.checkNotNull(s);
      fury.writeJavaString(buffer, s);
    }

    public void useProtocolVersion(int version) throws IOException {
      Class cls = slotsInfo.cls;
      throwUnsupportedEncodingException(cls);
    }

    public void flush() throws IOException {}

    protected void drain() throws IOException {}

    public void close() throws IOException {}
  }

  /**
   * Implement serialization for object output with `writeObject/readObject` defined by java
   * serialization input spec.
   *
   * @see <a href="https://docs.oracle.com/en/java/javase/18/docs/specs/serialization/input.html">
   *     Java Object Serialization Input Specification</a>
   */
  private static class FuryObjectInputStream extends ObjectInputStream {
    private final Fury fury;
    private final SlotsInfo slotsInfo;
    private MemoryBuffer buffer;
    private Object targetObject;
    private GetFieldImpl getField;
    private boolean fieldsRead;
    private TreeMap<Integer, ObjectInputValidation> callbacks;

    protected FuryObjectInputStream(SlotsInfo slotsInfo) throws IOException {
      this.fury = slotsInfo.slotsSerializer.fury;
      this.slotsInfo = slotsInfo;
    }

    protected Object readObjectOverride() {
      return fury.readRef(buffer);
    }

    public Object readUnshared() {
      return fury.readNonRef(buffer);
    }

    private static final Object NO_VALUE_STUB = new Object();

    private static class GetFieldImpl extends GetField {
      private final SlotsInfo slotsInfo;
      private final Object[] vals;

      GetFieldImpl(SlotsInfo slotsInfo) {
        this.slotsInfo = slotsInfo;
        vals = new Object[slotsInfo.putFieldsResolver.getNumFields()];
        Arrays.fill(vals, NO_VALUE_STUB);
      }

      @Override
      public ObjectStreamClass getObjectStreamClass() {
        return ObjectStreamClass.lookup(slotsInfo.cls);
      }

      @Override
      public boolean defaulted(String name) throws IOException {
        int index = slotsInfo.fieldIndexMap.get(name, -1);
        checkFieldExists(name, index);
        return vals[index] == NO_VALUE_STUB;
      }

      @Override
      public boolean get(String name, boolean val) throws IOException {
        Object fieldValue = getFieldValue(name);
        if (fieldValue == NO_VALUE_STUB) {
          return val;
        }
        return (boolean) fieldValue;
      }

      @Override
      public byte get(String name, byte val) throws IOException {
        Object fieldValue = getFieldValue(name);
        if (fieldValue == NO_VALUE_STUB) {
          return val;
        }
        return (byte) fieldValue;
      }

      @Override
      public char get(String name, char val) throws IOException {
        Object fieldValue = getFieldValue(name);
        if (fieldValue == NO_VALUE_STUB) {
          return val;
        }
        return (char) fieldValue;
      }

      @Override
      public short get(String name, short val) throws IOException {
        Object fieldValue = getFieldValue(name);
        if (fieldValue == NO_VALUE_STUB) {
          return val;
        }
        return (short) fieldValue;
      }

      @Override
      public int get(String name, int val) throws IOException {
        Object fieldValue = getFieldValue(name);
        if (fieldValue == NO_VALUE_STUB) {
          return val;
        }
        return (int) fieldValue;
      }

      @Override
      public long get(String name, long val) throws IOException {
        Object fieldValue = getFieldValue(name);
        if (fieldValue == NO_VALUE_STUB) {
          return val;
        }
        return (long) fieldValue;
      }

      @Override
      public float get(String name, float val) throws IOException {
        Object fieldValue = getFieldValue(name);
        if (fieldValue == NO_VALUE_STUB) {
          return val;
        }
        return (float) fieldValue;
      }

      @Override
      public double get(String name, double val) throws IOException {
        Object fieldValue = getFieldValue(name);
        if (fieldValue == NO_VALUE_STUB) {
          return val;
        }
        return (double) fieldValue;
      }

      @Override
      public Object get(String name, Object val) throws IOException {
        Object fieldValue = getFieldValue(name);
        if (fieldValue == NO_VALUE_STUB) {
          return val;
        }
        return fieldValue;
      }

      private Object getFieldValue(String name) {
        int index = slotsInfo.fieldIndexMap.get(name, -1);
        checkFieldExists(name, index);
        return vals[index];
      }

      private void checkFieldExists(String name, int index) {
        if (index == -1) {
          throw new IllegalArgumentException(
              String.format(
                  "Field name %s not exist in class %s", name, slotsInfo.slotsSerializer.type));
        }
      }
    }

    // `readFields`/`writeFields` can handle fields which doesn't exist in current class.
    // `defaultReadObject` will skip those fields.
    public GetField readFields() throws IOException {
      if (fieldsRead) {
        throw new NotActiveException("not in readObject invocation or fields already read");
      }
      slotsInfo.compatibleStreamSerializer.readFields(buffer, getField.vals);
      fieldsRead = true;
      return getField;
    }

    public void defaultReadObject() throws IOException, ClassNotFoundException {
      if (fieldsRead) {
        throw new NotActiveException("not in readObject invocation or fields already read");
      }
      slotsInfo.slotsSerializer.readAndSetFields(buffer, targetObject);
      fieldsRead = true;
    }

    // At `registerValidation` point int `readObject` root is only partially correct. To fully
    // restore it user may need access to other state which is created by the subclass and
    // at this point will be null. Users thus use `registerValidation`.
    // see `javax.swing.text.AbstractDocument.readObject` as an example.
    public void registerValidation(ObjectInputValidation obj, int prio)
        throws NotActiveException, InvalidObjectException {
      // Since this method is only visible to fury, it won't be invoked by users outside
      // `readObject`,
      // we can skip check whether the caller is in `readObject`.
      if (obj == null) {
        throw new InvalidObjectException("null callback");
      }
      callbacks.put(prio, obj);
    }

    public int read() throws IOException {
      return buffer.readByte();
    }

    public int read(byte[] buf, int offset, int length) throws IOException {
      if (buf == null) {
        throw new NullPointerException();
      }
      int endOffset = offset + length;
      if (offset < 0 || length < 0 || endOffset > buf.length || endOffset < 0) {
        throw new IndexOutOfBoundsException();
      }
      int remaining = buffer.remaining();
      if (remaining < length) {
        buffer.readBytes(buf, offset, remaining);
        return remaining;
      } else {
        buffer.readBytes(buf, offset, length);
        return length;
      }
    }

    public int available() throws IOException {
      return buffer.remaining();
    }

    public boolean readBoolean() throws IOException {
      return buffer.readBoolean();
    }

    public byte readByte() throws IOException {
      return buffer.readByte();
    }

    public int readUnsignedByte() throws IOException {
      int b = buffer.readByte();
      return b & 0xff;
    }

    public short readShort() throws IOException {
      return buffer.readShort();
    }

    public int readUnsignedShort() throws IOException {
      int b = buffer.readShort();
      return b & 0xffff;
    }

    public char readChar() throws IOException {
      return buffer.readChar();
    }

    public int readInt() throws IOException {
      return buffer.readInt();
    }

    public long readLong() throws IOException {
      return buffer.readLong();
    }

    public float readFloat() throws IOException {
      return buffer.readFloat();
    }

    public double readDouble() throws IOException {
      return buffer.readDouble();
    }

    public void readFully(byte[] data) throws IOException {
      buffer.readBytes(data);
    }

    public void readFully(byte[] data, int offset, int size) throws IOException {
      buffer.readBytes(data, offset, size);
    }

    public int skipBytes(int len) throws IOException {
      buffer.increaseReaderIndex(len);
      return len;
    }

    public String readLine() throws IOException {
      throw new UnsupportedOperationException();
    }

    public String readUTF() throws IOException {
      return fury.readJavaString(buffer);
    }

    public void close() throws IOException {}
  }
}
