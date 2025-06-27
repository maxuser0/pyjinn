package org.pyjinn.interpreter;

import java.util.Arrays;

/** Cache key for constructor and method signatures. */
public class ExecutableCacheKey {
  private final Object[] callSignature;

  private enum ExecutableType {
    INSTANCE_METHOD,
    STATIC_METHOD,
    CONSTRUCTOR
  }

  public static ExecutableCacheKey forMethod(
      Class<?> methodClass, boolean isStaticMethod, String methodName, Object[] paramValues) {
    Object[] callSignature = new Object[paramValues.length + 3];
    callSignature[0] =
        isStaticMethod
            ? ExecutableType.STATIC_METHOD.ordinal()
            : ExecutableType.INSTANCE_METHOD.ordinal();
    callSignature[1] = methodClass;
    callSignature[2] = methodName;
    for (int i = 0; i < paramValues.length; ++i) {
      Object paramValue = paramValues[i];
      callSignature[i + 3] = paramValue == null ? null : paramValue.getClass();
    }
    return new ExecutableCacheKey(callSignature);
  }

  public static ExecutableCacheKey forConstructor(Class<?> ctorClass, Object[] paramValues) {
    Object[] callSignature = new Object[paramValues.length + 2];
    callSignature[0] = ExecutableType.CONSTRUCTOR.ordinal();
    callSignature[1] = ctorClass;
    for (int i = 0; i < paramValues.length; ++i) {
      Object paramValue = paramValues[i];
      callSignature[i + 2] = paramValue == null ? null : paramValue.getClass();
    }
    return new ExecutableCacheKey(callSignature);
  }

  private ExecutableCacheKey(Object[] callSignature) {
    this.callSignature = callSignature;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    ExecutableCacheKey other = (ExecutableCacheKey) o;
    return Arrays.equals(callSignature, other.callSignature);
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(callSignature);
  }

  @Override
  public String toString() {
    return Arrays.deepToString(callSignature);
  }
}
