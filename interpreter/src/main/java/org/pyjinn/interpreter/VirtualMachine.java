// SPDX-FileCopyrightText: © 2025 Greg Christiana <maxuser@pyjinn.org>
// SPDX-License-Identifier: MIT

package org.pyjinn.interpreter;

import static org.pyjinn.interpreter.Script.debug;
import static org.pyjinn.interpreter.Script.logger;

import org.pyjinn.interpreter.Script.Context;

public class VirtualMachine {

  private static VirtualMachine INSTANCE = new VirtualMachine();

  public static VirtualMachine getInstance() {
    return INSTANCE;
  }

  private VirtualMachine() {}

  public boolean hasMoreInstructions(Context context) {
    return context.ip < context.code.instructions().size();
  }

  public Context executeNextInstruction(Context context) {
    Instruction instruction = null;
    try {
      instruction = context.code.instructions().get(context.ip);
      if (debug) {
        logger.log("Executing instruction: [%d] %s", context.ip, instruction);
      }
      context = instruction.execute(context);
    } catch (RuntimeException e) {
      if (debug) {
        logger.log("Line info: %s", context.code.getLineInfos());
      }
      int lineno = getCodeLineNumber(context);
      context.appendScriptStack(lineno, e);
      context.exception = e;
      Code.ExceptionalJump jump = null;
      while ((jump = context.code.getExceptionalJump(context.ip)) == null
          && context.callingContext() != null) {
        context.debugLogInstructions();
        var caller = context.callingContext();
        caller.exception = context.exception;
        context.exception = null;
        context = context.callingContext();
      }
      if (jump == null) {
        context.exception = null;
        while (context.dataStackSize() > 0) {
          context.popData();
        }
        context.ip = context.code.instructions().size();
        throw e;
      } else {
        while (context.dataStackSize() > jump.initialStackDepth()) {
          context.popData();
        }
        context.ip = jump.jumpTarget();
      }
    }
    return context;
  }

  private static int getCodeLineNumber(Context context) {
    var code = context.code;
    if (code == null) {
      return -1;
    }

    int ip = context.ip;
    for (var lineInfo : code.getLineInfos()) {
      if (ip >= lineInfo.startInstruction && ip <= lineInfo.endInstruction) {
        return lineInfo.lineno;
      }
    }

    return -1;
  }
}
