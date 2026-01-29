// SPDX-FileCopyrightText: © 2026 Greg Christiana <maxuser@pyjinn.org>
// SPDX-License-Identifier: MIT

package org.pyjinn.interpreter;

public class StopIteration extends RuntimeException {
  public final Object value;

  public static final StopIteration DEFAULT = new StopIteration();

  public StopIteration() {
    this(null);
  }

  public StopIteration(Object value) {
    super("StopIteration");
    this.value = value;
  }

  @Override
  public String toString() {
    return "StopIteration(" + Script.PyjObjects.toString(value) + ")";
  }
}
