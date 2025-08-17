// SPDX-FileCopyrightText: © 2025 Greg Christiana <maxuser@pyjinn.org>
// SPDX-License-Identifier: MIT

package org.pyjinn.interpreter;

import java.util.Arrays;

/** Superclass for cache keys for constructor and method signatures. */
public abstract class ExecutableCacheKey {
  private final Object[] callSignature;

  protected ExecutableCacheKey(Object[] callSignature) {
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
