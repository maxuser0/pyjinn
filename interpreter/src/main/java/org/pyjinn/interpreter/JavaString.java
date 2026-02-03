// SPDX-FileCopyrightText: © 2025-2026 Greg Christiana <maxuser@pyjinn.org>
// SPDX-License-Identifier: MIT

package org.pyjinn.interpreter;

/** Wrapper that informs interpreter to treat a string as the Java API instead of Pyjinn API. */
public record JavaString(String string) {
  @Override
  public String toString() {
    return string;
  }
}
