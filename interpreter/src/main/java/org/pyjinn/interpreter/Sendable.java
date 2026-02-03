// SPDX-FileCopyrightText: © 2025-2026 Greg Christiana <maxuser@pyjinn.org>
// SPDX-License-Identifier: MIT

package org.pyjinn.interpreter;

import org.pyjinn.interpreter.Script.Context;

interface Sendable {
  default Context startSend(Object value, Context callingContext) {
    return startSend(value, callingContext, callingContext.classMethodName.toString(), -1);
  }

  Context startSend(Object value, Context callingContext, String filename, int lineno);
}
