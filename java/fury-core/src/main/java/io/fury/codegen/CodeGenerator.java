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

package io.fury.codegen;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.fury.builder.AccessorHelper;
import io.fury.builder.Generated;
import io.fury.collection.MultiKeyWeakMap;
import io.fury.util.ClassLoaderUtils;
import io.fury.util.ClassLoaderUtils.ByteArrayClassLoader;
import io.fury.util.LoggerFactory;
import io.fury.util.Preconditions;
import io.fury.util.ReflectionUtils;
import io.fury.util.StringUtils;
import java.lang.ref.SoftReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;

/**
 * Code generator will take a list of {@link CompileUnit} and compile it into a list of classes.
 *
 * <p>The compilation will be executed in a thread-pool parallel for speed.
 *
 * @author chaokunyang
 */
public class CodeGenerator {
  private static final Logger LOG = LoggerFactory.getLogger(CodeGenerator.class);

  private static final String CODE_DIR_KEY = "FURY_CODE_DIR";
  private static final String DELETE_CODE_ON_EXIT_KEY = "FURY_DELETE_CODE_ON_EXIT";

  // This is the default value of HugeMethodLimit in the OpenJDK HotSpot JVM,
  // beyond which methods will be rejected from JIT compilation
  static final int DEFAULT_JVM_HUGE_METHOD_LIMIT = 8000;

  static final int DEFAULT_JVM_INLINE_METHOD_LIMIT = 325;

  // The max valid length of method parameters in JVM.
  static final int MAX_JVM_METHOD_PARAMS_LENGTH = 255;

  // FIXME The classloaders will only be reclaimed when the generated class are not be referenced.
  // FIXME CodeGenerator may reference to classloader, thus cause circular reference, neither can
  //  be gc.
  private static final WeakHashMap<ClassLoader, SoftReference<CodeGenerator>> sharedCodeGenerator =
      new WeakHashMap<>();
  private static final MultiKeyWeakMap<SoftReference<CodeGenerator>> sharedCodeGenerator2 =
      new MultiKeyWeakMap<>();

  // use this package when bean class name starts with java.
  private static final String FALLBACK_PACKAGE = Generated.class.getPackage().getName();
  public static final boolean ENABLE_FURY_GENERATED_CLASS_UNIQUE_ID;
  private static int maxPoolSize = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
  private static ListeningExecutorService compilationExecutorService;

  static {
    boolean useUniqueId = StringUtils.isBlank(CodeGenerator.getCodeDir());
    String flagValue =
        System.getProperty(
            "fury.enable_fury_generated_class_unique_id",
            System.getenv("ENABLE_FURY_GENERATED_CLASS_UNIQUE_ID"));
    if (flagValue != null) {
      useUniqueId = "true".equals(flagValue);
    }
    ENABLE_FURY_GENERATED_CLASS_UNIQUE_ID = useUniqueId;
  }

  private ClassLoader classLoader;
  private final Object classLoaderLock;
  private final ConcurrentHashMap<String, CompileState> parallelCompileState;
  private final ConcurrentHashMap<String, DefineState> parallelDefineStatusLock;

  public CodeGenerator(ClassLoader classLoader) {
    Preconditions.checkNotNull(classLoader);
    this.classLoader = classLoader;
    parallelCompileState = new ConcurrentHashMap<>();
    parallelDefineStatusLock = new ConcurrentHashMap<>();
    classLoaderLock = new Object();
  }

  /**
   * Compile code, return as a new classloader. If the class of a compilation unit already exists in
   * previous classloader, skip the corresponding compilation unit.
   *
   * @param units compile units
   */
  public ClassLoader compile(CompileUnit... units) {
    return compile(Arrays.asList(units), compileState -> compileState.lock.lock());
  }

  public ClassLoader compile(List<CompileUnit> units, CompileCallback callback) {
    List<CompileUnit> compileUnits = new ArrayList<>();
    ClassLoader parentClassLoader;
    // Note: avoid deadlock between classloader lock, compiler lock,
    // jit lock and class-def lock.
    synchronized (classLoaderLock) { // protect classLoader.
      for (CompileUnit unit : units) {
        if (!classExists(classLoader, unit.getQualifiedClassName())) {
          compileUnits.add(unit);
        }
      }
      if (compileUnits.isEmpty()) {
        return classLoader;
      }
      parentClassLoader = classLoader;
    }
    CompileState compileState = getCompileState(compileUnits);
    callback.lock(compileState);
    Map<String, byte[]> classes;
    if (compileState.finished) {
      classes = compileState.result;
      compileState.lock.unlock();
    } else {
      try {
        classes =
            JaninoUtils.toBytecode(parentClassLoader, compileUnits.toArray(new CompileUnit[0]));
        compileState.result = classes;
        compileState.finished = true;
      } finally {
        compileState.lock.unlock();
      }
      for (Map.Entry<String, byte[]> e : classes.entrySet()) {
        String key = e.getKey();
        byte[] value = e.getValue();
        if (LOG.isDebugEnabled()) {
          LOG.debug("Code stats for class {} is {}", key, JaninoUtils.getClassStats(value));
        }
      }
    }
    return defineClasses(classes);
  }

  /**
   * Define classes in classloader, create a new classloader if classes can' be loaded into previous
   * classloader.
   */
  private ClassLoader defineClasses(Map<String, byte[]> classes) {
    if (classes.isEmpty()) {
      return getClassLoader();
    }
    ClassLoader resultClassLoader = null;
    boolean isByteArrayClassLoader;
    synchronized (classLoaderLock) {
      isByteArrayClassLoader = classLoader instanceof ByteArrayClassLoader;
      if (isByteArrayClassLoader) {
        resultClassLoader = classLoader;
      }
    }
    if (isByteArrayClassLoader) {
      for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
        String className = fullClassNameFromClassFilePath(entry.getKey());
        DefineState defineState = getDefineState(className);
        // Avoid multi-compile unit classes define operation collision with single compile unit.
        if (!defineState.defined) { // class not defined yet.
          synchronized (defineState.lock) {
            if (!defineState.defined) { // class not defined yet.
              // Even if multiple compile unit is inter-dependent, they can still be defined
              // separately.
              ((ByteArrayClassLoader) (resultClassLoader))
                  .defineClassPublic(className, entry.getValue());
              defineState.defined = true;
            }
          }
        }
      }
    } else {
      synchronized (classLoaderLock) {
        ByteArrayClassLoader bytesClassLoader = new ByteArrayClassLoader(classes, classLoader);
        for (String k : classes.keySet()) {
          String className = fullClassNameFromClassFilePath(k);
          DefineState defineState = getDefineState(className);
          defineState.defined = true; // avoid duplicate def throws LinkError.
        }
        // Set up a class loader that finds and defined the generated classes.
        classLoader = bytesClassLoader;
        resultClassLoader = bytesClassLoader;
      }
    }
    return resultClassLoader;
  }

  public ListenableFuture<Class<?>[]> asyncCompile(CompileUnit... compileUnits) {
    return getCompilationService()
        .submit(
            () -> {
              ClassLoader loader = compile(compileUnits);
              return Arrays.stream(compileUnits)
                  .map(
                      compileUnit -> {
                        try {
                          return (Class<?>) loader.loadClass(compileUnit.getQualifiedClassName());
                        } catch (ClassNotFoundException e) {
                          throw new IllegalStateException(
                              "Impossible because we just compiled class", e);
                        }
                      })
                  .toArray(Class<?>[]::new);
            });
  }

  public static void seMaxCompilationThreadPoolSize(int maxCompilationThreadPoolSize) {
    maxPoolSize = maxCompilationThreadPoolSize;
  }

  public static synchronized ListeningExecutorService getCompilationService() {
    if (compilationExecutorService == null) {
      ThreadPoolExecutor executor =
          new ThreadPoolExecutor(
              maxPoolSize,
              maxPoolSize,
              5L,
              TimeUnit.SECONDS,
              new LinkedBlockingQueue<>(),
              new ThreadFactoryBuilder().setNameFormat("fury-jit-compiler-%d").build(),
              (r, e) -> LOG.warn("Task {} rejected from {}", r.toString(), e));
      // Normally task won't be rejected by executor, since we used an unbound queue.
      // But when we shut down executor for debug, it'll be rejected by executor,
      // in such cases we just ignore the reject exception by log it.
      executor.allowCoreThreadTimeOut(true);
      compilationExecutorService = MoreExecutors.listeningDecorator(executor);
    }
    return compilationExecutorService;
  }

  public ClassLoader getClassLoader() {
    synchronized (classLoaderLock) {
      return classLoader;
    }
  }

  private CompileState getCompileState(List<CompileUnit> toCompile) {
    return parallelCompileState.computeIfAbsent(
        getCompileLockKey(toCompile), k -> new CompileState());
  }

  private String getCompileLockKey(List<CompileUnit> toCompile) {
    if (toCompile.size() == 1) {
      return toCompile.get(0).getQualifiedClassName();
    } else {
      StringJoiner joiner = new StringJoiner(",");
      for (CompileUnit unit : toCompile) {
        joiner.add(unit.getQualifiedClassName());
      }
      return joiner.toString();
    }
  }

  private static class DefineState {
    final Object lock;
    volatile boolean defined;

    private DefineState() {
      this.lock = new Object();
    }
  }

  private DefineState getDefineState(String className) {
    return parallelDefineStatusLock.computeIfAbsent(className, k -> new DefineState());
  }

  private boolean classExists(ClassLoader loader, String className) {
    try {
      loader.loadClass(className);
      return true;
    } catch (ClassNotFoundException e) {
      return false;
    }
  }

  public static synchronized CodeGenerator getSharedCodeGenerator(ClassLoader... classLoaders) {
    SoftReference<CodeGenerator> codeGeneratorWeakRef = sharedCodeGenerator2.get(classLoaders);
    CodeGenerator codeGenerator = codeGeneratorWeakRef != null ? codeGeneratorWeakRef.get() : null;
    if (codeGenerator == null) {
      codeGenerator = new CodeGenerator(new ClassLoaderUtils.ComposedClassLoader(classLoaders));
      sharedCodeGenerator2.put(classLoaders, new SoftReference<>(codeGenerator));
    }
    return codeGenerator;
  }

  public static synchronized CodeGenerator getSharedCodeGenerator(ClassLoader classLoader) {
    if (classLoader == null) {
      classLoader = CodeGenerator.class.getClassLoader();
    }
    SoftReference<CodeGenerator> codeGeneratorWeakRef = sharedCodeGenerator.get(classLoader);
    CodeGenerator codeGenerator = codeGeneratorWeakRef != null ? codeGeneratorWeakRef.get() : null;
    if (codeGenerator == null) {
      codeGenerator = new CodeGenerator(classLoader);
      sharedCodeGenerator.put(classLoader, new SoftReference<>(codeGenerator));
    }
    return codeGenerator;
  }

  /**
   * Can't create a codec class that has package starts with java, which throws
   * java.lang.SecurityException: Prohibited package name.<br>
   * Caution: The runtime package is defined by package name and classloader. see {@link
   * AccessorHelper}.
   */
  public static String getPackage(Class<?> cls) {
    String pkg = ReflectionUtils.getPackage(cls);
    if (pkg.startsWith("java")) {
      return FALLBACK_PACKAGE;
    } else {
      return pkg;
    }
  }

  public static String getClassUniqueId(Class<?> cls) {
    if (!ENABLE_FURY_GENERATED_CLASS_UNIQUE_ID) {
      return "";
    }
    // classLoader will be null for jdk classes.
    ClassLoader classLoader = cls.getClassLoader();
    if (classLoader == null) {
      return String.valueOf(cls.hashCode());
    } else {
      return String.format("%s_%s", classLoader.hashCode(), cls.hashCode());
    }
  }

  public static String getCodeDir() {
    return System.getProperty(CODE_DIR_KEY, System.getenv(CODE_DIR_KEY));
  }

  static boolean deleteCodeOnExit() {
    boolean deleteCodeOnExit = StringUtils.isBlank(getCodeDir());
    String deleteCodeOnExitStr =
        System.getProperty(DELETE_CODE_ON_EXIT_KEY, System.getenv(DELETE_CODE_ON_EXIT_KEY));
    if (deleteCodeOnExitStr != null) {
      deleteCodeOnExit = Boolean.parseBoolean(deleteCodeOnExitStr);
    }
    return deleteCodeOnExit;
  }

  public static String classFilepath(CompileUnit unit) {
    return classFilepath(fullClassName(unit));
  }

  public static String classFilepath(String pkg, String className) {
    return classFilepath(pkg + "." + className);
  }

  public static String classFilepath(String fullClassName) {
    int index = fullClassName.lastIndexOf(".");
    if (index >= 0) {
      return String.format(
          "%s/%s.class",
          fullClassName.substring(0, index).replace(".", "/"), fullClassName.substring(index + 1));
    } else {
      return fullClassName + ".class";
    }
  }

  public static String fullClassName(CompileUnit unit) {
    return unit.pkg + "." + unit.mainClassName;
  }

  public static String fullClassNameFromClassFilePath(String classFilePath) {
    return classFilePath.substring(0, classFilePath.length() - ".class".length()).replace("/", ".");
  }

  /** align code to have 4 spaces indent. */
  public static String alignIndent(String code) {
    return alignIndent(code, 4);
  }

  /** align code to have {@code numSpaces} spaces indent. */
  public static String alignIndent(String code, int numSpaces) {
    if (code == null) {
      return "";
    }
    String[] split = code.split("\n");
    if (split.length == 1) {
      return code;
    } else {
      StringBuilder codeBuilder = new StringBuilder(split[0]).append('\n');
      for (int i = 1; i < split.length; i++) {
        for (int j = 0; j < numSpaces; j++) {
          codeBuilder.append(' ');
        }
        codeBuilder.append(split[i]).append('\n');
      }
      if (code.charAt(code.length() - 1) == '\n') {
        return codeBuilder.toString();
      } else {
        return codeBuilder.substring(0, codeBuilder.length() - 1);
      }
    }
  }

  /** indent code by 4 spaces. */
  static String indent(String code) {
    return indent(code, 2);
  }

  /** The implementation shouldn't add redundant newline separator. */
  static String indent(String code, int numSpaces) {
    if (code == null) {
      return "";
    }
    String[] split = code.split("\n");
    StringBuilder codeBuilder = new StringBuilder();
    for (String line : split) {
      for (int i = 0; i < numSpaces; i++) {
        codeBuilder.append(' ');
      }
      codeBuilder.append(line).append('\n');
    }
    if (code.charAt(code.length() - 1) == '\n') {
      return codeBuilder.toString();
    } else {
      return codeBuilder.substring(0, codeBuilder.length() - 1);
    }
  }

  /**
   * Create spaces.
   *
   * @param numSpaces spaces num
   * @return a string of numSpaces spaces
   */
  static String spaces(int numSpaces) {
    StringBuilder builder = new StringBuilder();
    for (int i = 0; i < numSpaces; i++) {
      builder.append(' ');
    }
    return builder.toString();
  }

  static void appendNewlineIfNeeded(StringBuilder sb) {
    if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') {
      sb.append('\n');
    }
  }

  static StringBuilder stripLastNewline(StringBuilder sb) {
    int length = sb.length();
    Preconditions.checkArgument(length > 0 && sb.charAt(length - 1) == '\n');
    sb.deleteCharAt(length - 1);
    return sb;
  }

  static StringBuilder stripIfHasLastNewline(StringBuilder sb) {
    int length = sb.length();
    if (length > 0 && sb.charAt(length - 1) == '\n') {
      sb.deleteCharAt(length - 1);
    }
    return sb;
  }
}
