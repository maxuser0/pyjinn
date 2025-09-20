package org.pyjinn.interpreter;

import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.ConcurrentHashMap;
import org.pyjinn.interpreter.Script.Environment;
import org.pyjinn.interpreter.Script.Function;
import org.pyjinn.interpreter.Script.InterfaceProxy;

/**
 * A wrapper for a Java {@link Class} that can be called from a script to construct new instances.
 *
 * <p>This class implements {@link Function} so that it can be treated as a callable within the
 * script environment. When called, it attempts to find a suitable constructor on the wrapped Java
 * class that matches the provided arguments and then invokes it to create a new Java object.
 *
 * <p>Instances of {@code JavaClass} are cached statically to ensure that a single {@code JavaClass}
 * wrapper exists for any given Java {@code Class} across the entire application, preventing
 * inconsistencies when objects are shared between different {@link Script} instances.
 */
public class JavaClass implements Function {
  // javaClasses is static so that the map, and therefore instances of JavaClass, are shared across
  // Script instances. If this weren't the case then JavaClass("X") referenced in one script would
  // compare unequal with JavaClass("X") from another script, which would lead to subtle bugs when
  // objects are shared across scripts in the same process.
  private static ConcurrentHashMap<Class<?>, JavaClass> javaClasses = new ConcurrentHashMap<>();

  private Class<?> type;

  /**
   * Returns a {@code JavaClass} wrapper for the given Java {@link Class}.
   *
   * <p>This method uses a cache to ensure that only one {@code JavaClass} instance exists for each
   * unique Java {@code Class}.
   *
   * @param type The Java {@code Class} to wrap.
   * @return The {@code JavaClass} instance for the specified type.
   */
  public static JavaClass of(Class<?> type) {
    return javaClasses.computeIfAbsent(type, JavaClass::new);
  }

  /**
   * Installs a custom {@code JavaClass} instance into the global cache.
   *
   * <p>This is useful for providing custom construction logic for specific Java classes by using a
   * subclass of {@code JavaClass}.
   *
   * @param javaClass The custom {@code JavaClass} to install.
   * @return The installed {@code JavaClass}.
   * @throws IllegalStateException if a {@code JavaClass} for the given type is already installed.
   */
  public static JavaClass install(JavaClass javaClass) {
    var previousJavaClass = javaClasses.putIfAbsent(javaClass.type(), javaClass);
    if (previousJavaClass != null) {
      throw new IllegalStateException("%s already installed".formatted(javaClass.type()));
    }
    return javaClass;
  }

  protected JavaClass(Class<?> type) {
    this.type = type;
  }

  /**
   * Returns the underlying Java {@link Class} wrapped by this {@code JavaClass}.
   *
   * @return The wrapped {@code Class} object.
   */
  public Class<?> type() {
    return type;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    var that = (JavaClass) o;
    return this.type == that.type;
  }

  @Override
  public int hashCode() {
    return type.hashCode();
  }

  @Override
  public String toString() {
    return String.format("JavaClass(\"%s\")", type.getName());
  }

  @Override
  public Object call(Environment env, Object... params) {
    Function function;
    if ((function = InterfaceProxy.getFunctionPassedToInterface(type, params)) != null) {
      return InterfaceProxy.promoteFunctionToJavaInterface(env, type, function);
    }

    ConstructorInvoker ctor = env.findConstructor(type, params);
    try {
      return ctor.newInstance(env, params);
    } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
      throw new RuntimeException(e);
    }
  }
}
