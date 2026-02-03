// SPDX-FileCopyrightText: © 2025-2026 Greg Christiana <maxuser@pyjinn.org>
// SPDX-License-Identifier: MIT

package org.pyjinn.interpreter;

/** Cache key for constructor and method signatures. */
public class MethodCacheKey extends ExecutableCacheKey {

  public static MethodCacheKey of(
      Class<?> methodClass, boolean isStaticMethod, String methodName, Class<?>[] paramTypes) {
    Object[] callSignature = new Object[paramTypes.length + 3];
    callSignature[0] = isStaticMethod;
    callSignature[1] = methodClass;
    callSignature[2] = methodName;
    for (int i = 0; i < paramTypes.length; ++i) {
      callSignature[i + 3] = paramTypes[i];
    }
    return new MethodCacheKey(callSignature);
  }

  private MethodCacheKey(Object[] callSignature) {
    super(callSignature);
  }
}
