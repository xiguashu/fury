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

package io.fury.resolver;

import io.fury.collection.IdentityObjectIntMap;
import io.fury.memory.MemoryBuffer;
import io.fury.type.ClassDef;
import java.util.ArrayList;
import java.util.List;

/**
 * Context for sharing class meta across multiple serialization. Class name, field name and field
 * type will be shared between different serialization.
 *
 * @author chaokunyang
 */
public class MetaContext {
  /** Classes which has sent definitions to peer. */
  public final IdentityObjectIntMap<Class<?>> classMap = new IdentityObjectIntMap<>(8, 0.4f);
  /** Class definitions read from peer. */
  public final List<ClassDef> readClassDefs = new ArrayList<>();

  public final List<ClassInfo> readClassInfos = new ArrayList<>();

  /**
   * New class definition which needs sending to peer. This will be filled up when there are new
   * class definition need sending, and will be cleared after writing to buffer.
   *
   * @see ClassResolver#writeClassDefs(MemoryBuffer)
   */
  public final List<ClassDef> writingClassDefs = new ArrayList<>();
}
