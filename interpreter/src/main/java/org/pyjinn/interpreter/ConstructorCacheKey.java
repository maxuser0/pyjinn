// SPDX-FileCopyrightText: © 2025-2026 Greg Christiana <maxuser@pyjinn.org>
// SPDX-License-Identifier: MIT

package org.pyjinn.interpreter;

/** Cache key for constructor signature. */
public class ConstructorCacheKey extends ExecutableCacheKey {

  public static ConstructorCacheKey of(Class<?> ctorClass, Class<?>[] paramTypes) {
    Object[] callSignature = new Object[paramTypes.length + 1];
    callSignature[0] = ctorClass;
    for (int i = 0; i < paramTypes.length; ++i) {
      callSignature[i + 1] = paramTypes[i];
    }
    return new ConstructorCacheKey(callSignature);
  }

  private ConstructorCacheKey(Object[] callSignature) {
    super(callSignature);
  }
}
