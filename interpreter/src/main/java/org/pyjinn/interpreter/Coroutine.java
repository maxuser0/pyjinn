// SPDX-FileCopyrightText: © 2025-2026 Greg Christiana <maxuser@pyjinn.org>
// SPDX-License-Identifier: MIT

package org.pyjinn.interpreter;

import org.pyjinn.interpreter.Script.Context;

public record Coroutine(Context context) implements Sendable {
  @Override
  public Context startSend(Object value, Context callingContext, String filename, int lineno) {
    callingContext.enterFunction(filename, lineno);
    this.context.setCaller(callingContext);
    // IP -1 indicates that the coroutine is in its initial state and needs to be reset to 0.
    if (this.context.ip == -1) {
      if (value != null) {
        throw new IllegalArgumentException(
            "Can't send non-None value to start a coroutine; sent "
                + Script.getSimpleTypeName(value));
      }
      this.context.ip = 0;
    } else {
      this.context.pushData(value);
    }
    return this.context;
  }
}
