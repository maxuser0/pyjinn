// SPDX-FileCopyrightText: © 2026 Greg Christiana <maxuser@pyjinn.org>
// SPDX-License-Identifier: MIT

package org.pyjinn.interpreter;

public class StopIteration extends RuntimeException {
  public StopIteration() {
    super("StopIteration");
  }
}
