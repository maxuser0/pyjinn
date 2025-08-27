// SPDX-FileCopyrightText: © 2025 Greg Christiana <maxuser@pyjinn.org>
// SPDX-License-Identifier: MIT

package org.pyjinn.interpreter;

import java.lang.reflect.Field;
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
  private final Function<String, String> toRuntimeClassName;

  // Mapping from class's runtime name to its pretty name.
  private final Function<String, String> toPrettyClassName;

  // Mapping from an object's runtime class and its method's pretty name to its runtime
  // names.
  private final BiFunction<Class<?>, String, Set<String>> toRuntimeMethodNames;

  // Mapping from an object's runtime class and its field's pretty name to its runtime
  // name.
  private final BiFunction<Class<?>, String, String> toRuntimeFieldName;

  private final ConcurrentHashMap<ConstructorCacheKey, Optional<ConstructorInvoker>> constructors =
      new ConcurrentHashMap<>();

  private final ConcurrentHashMap<MethodCacheKey, Optional<MethodInvoker>> methods =
      new ConcurrentHashMap<>();

  // Class members associated with the class itself, i.e. static fields and nested classes that can
  // be referenced from the class. E.g. from `class Foo`: `Foo.some_field` or `Foo.SomeNestedClass`.
  private final ConcurrentHashMap<MemberKey, MemberAccessor> classMembers =
      new ConcurrentHashMap<>();

  public record MemberKey(boolean isClass, Class<?> type, String memberName) {}

  public interface MemberAccessor {
    Object from(Object object) throws IllegalAccessException;
  }

  public record ClassFieldAccessor(Field field) implements MemberAccessor {
    @Override
    public Object from(Object object) throws IllegalAccessException {
      return field.get(null);
    }
  }

  public record InstanceFieldAccessor(Field field) implements MemberAccessor {
    @Override
    public Object from(Object object) throws IllegalAccessException {
      return field.get(object);
    }
  }

  public record NestedClassAccessor(Class<?> nestedClass) implements MemberAccessor {
    @Override
    public Object from(Object object) {
      return Script.getJavaClass(nestedClass);
    }
  }

  public SymbolCache(
      Function<String, String> toRuntimeClassName,
      Function<String, String> toPrettyClassName,
      BiFunction<Class<?>, String, String> toRuntimeFieldName,
      BiFunction<Class<?>, String, Set<String>> toRuntimeMethodNames) {
    this.toRuntimeClassName = toRuntimeClassName;
    this.toPrettyClassName = toPrettyClassName;
    this.toRuntimeFieldName = toRuntimeFieldName;
    this.toRuntimeMethodNames = toRuntimeMethodNames;
  }

  /** Maps a class' pretty name to its runtime name. */
  public String getRuntimeClassName(String className) {
    return toRuntimeClassName.apply(className);
  }

  /** Maps a class' runtime name to its pretty name. */
  public String getPrettyClassName(String className) {
    return toPrettyClassName.apply(className);
  }

  /** Maps a class method's pretty name to its runtime names. */
  public Set<String> getRuntimeMethodNames(Class<?> type, String methodName) {
    return toRuntimeMethodNames.apply(type, methodName);
  }

  /** Maps a class field's pretty name to its runtime name. */
  public String getRuntimeFieldName(Class<?> type, String fieldName) {
    return toRuntimeFieldName.apply(type, fieldName);
  }

  public Optional<ConstructorInvoker> getConstructor(
      ConstructorCacheKey key,
      Function<ConstructorCacheKey, Optional<ConstructorInvoker>> mapping) {
    return constructors.computeIfAbsent(key, mapping);
  }

  public Optional<MethodInvoker> getMethodInvoker(
      MethodCacheKey key, Function<MethodCacheKey, Optional<MethodInvoker>> mapping) {
    return methods.computeIfAbsent(key, mapping);
  }

  public MemberAccessor getMember(MemberKey key, Function<MemberKey, MemberAccessor> mapping) {
    return classMembers.computeIfAbsent(key, mapping);
  }
}
