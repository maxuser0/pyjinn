// SPDX-FileCopyrightText: © 2025 Greg Christiana <maxuser@pyjinn.org>
// SPDX-License-Identifier: MIT

package org.pyjinn.interpreter;

import java.lang.reflect.InvocationTargetException;

public interface MethodInvoker {
  Object invoke(Script.Environment env, Object object, Object[] params)
      throws IllegalAccessException, InvocationTargetException;
}
