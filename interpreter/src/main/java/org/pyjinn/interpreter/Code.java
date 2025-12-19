// SPDX-FileCopyrightText: © 2025 Greg Christiana <maxuser@pyjinn.org>
// SPDX-License-Identifier: MIT

package org.pyjinn.interpreter;

import java.util.ArrayList;
import java.util.List;

public class Code {
  final List<Instruction> instructions = new ArrayList<>();
  final List<ExceptionalJump> jumpTable = new ArrayList<>();

  public enum ExceptionClause {
    EXCEPT,
    FINALLY
  }

  public record ExceptionalJump(
      int startInstruction, int endInstruction, int jumpTarget, ExceptionClause clauseType) {}

  List<Instruction> instructions() {
    return instructions;
  }

  void addInstruction(Instruction instruction) {
    instructions.add(instruction);
  }

  void registerExceptionalJump(
      int startInstruction, int endInstruction, int jumpTarget, ExceptionClause clauseType) {
    jumpTable.add(new ExceptionalJump(startInstruction, endInstruction, jumpTarget, clauseType));
  }

  /**
   * Return jump target to exception handler for this instruction pointer, or null if there's no
   * 'except' or 'finally` block in the current context.
   */
  public ExceptionalJump getExceptionalJump(int ip) {
    for (int i = 0; i < jumpTable.size(); ++i) {
      var jumpEntry = jumpTable.get(i);
      if (ip >= jumpEntry.startInstruction && ip < jumpEntry.endInstruction) {
        return jumpEntry;
      }
    }
    return null;
  }
}
