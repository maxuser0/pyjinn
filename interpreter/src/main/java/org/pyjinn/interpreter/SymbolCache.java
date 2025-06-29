package org.pyjinn.interpreter;

import java.lang.reflect.Executable;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Cache for managing the mapping between runtime names and pretty names.
 *
 * <p>Runtime names are the names of classes and fields associated with Java object's classes.
 * Pretty names are the unobfuscated names of classes and their members. For unobfuscated code,
 * runtime names and pretty names are the same.
 */
public class SymbolCache {
  // Mapping from class's pretty name to its runtime name.
  private final Function<String, String> classMapping;

  // Mapping from an object's runtime class and its method's pretty name to its runtime
  // names.
  private final BiFunction<Class<?>, String, Set<String>> methodMapping;

  // Mapping from an object's runtime class and its field's pretty name to its runtime
  // name.
  private final BiFunction<Class<?>, String, String> fieldMapping;

  private final ConcurrentHashMap<ExecutableCacheKey, Optional<Executable>> executables =
      new ConcurrentHashMap<>();

  public SymbolCache(
      Function<String, String> classMapping,
      BiFunction<Class<?>, String, String> fieldMapping,
      BiFunction<Class<?>, String, Set<String>> methodMapping) {
    this.classMapping = classMapping;
    this.fieldMapping = fieldMapping;
    this.methodMapping = methodMapping;
  }

  /** Maps a class' pretty name to its runtime name. */
  public String getRuntimeClassName(String className) {
    return classMapping.apply(className);
  }

  /** Maps a class method's pretty name to its runtime names. */
  public Set<String> getRuntimeMethodNames(Class<?> clazz, String methodName) {
    return methodMapping.apply(clazz, methodName);
  }

  /** Maps a class field's pretty name to its runtime name. */
  public String getRuntimeFieldName(Class<?> clazz, String fieldName) {
    return fieldMapping.apply(clazz, fieldName);
  }

  public Optional<Executable> computeIfAbsent(
      ExecutableCacheKey key, Function<ExecutableCacheKey, Optional<Executable>> mapping) {
    return executables.computeIfAbsent(key, mapping);
  }
}
