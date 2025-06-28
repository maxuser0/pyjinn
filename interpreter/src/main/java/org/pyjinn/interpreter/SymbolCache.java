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

  // Mapping from an object's runtime class and its member's pretty name to its runtime
  // name.
  private final BiFunction<Class<?>, String, Set<String>> memberMapping;

  private final ConcurrentHashMap<ExecutableCacheKey, Optional<Executable>> executables =
      new ConcurrentHashMap<>();

  public SymbolCache(
      Function<String, String> classMapping,
      BiFunction<Class<?>, String, Set<String>> memberMapping) {
    this.classMapping = classMapping;
    this.memberMapping = memberMapping;
  }

  /** Maps a class' pretty name to its runtime name. */
  public String getRuntimeClassName(String className) {
    return classMapping.apply(className);
  }

  /** Maps a class member's pretty name to its runtime name. */
  public Set<String> getRuntimeMemberNames(Class<?> clazz, String memberName) {
    return memberMapping.apply(clazz, memberName);
  }

  public Optional<Executable> computeIfAbsent(
      ExecutableCacheKey key, Function<ExecutableCacheKey, Optional<Executable>> mapping) {
    return executables.computeIfAbsent(key, mapping);
  }
}
