// SPDX-FileCopyrightText: © 2025-2026 Greg Christiana <maxuser@pyjinn.org>
// SPDX-License-Identifier: MIT

package org.pyjinn.interpreter;

import java.lang.reflect.InvocationTargetException;

public interface ConstructorInvoker {
  Object newInstance(Script.Environment env, Object[] params)
      throws IllegalAccessException, InvocationTargetException, InstantiationException;
}
