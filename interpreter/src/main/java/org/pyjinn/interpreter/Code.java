// SPDX-FileCopyrightText: © 2025-2026 Greg Christiana <maxuser@pyjinn.org>
// SPDX-License-Identifier: MIT

package org.pyjinn.interpreter;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class Code {
  final InstructionList instructions = new InstructionList();
  final List<ExceptionalJump> jumpTable = new ArrayList<>();
  final List<LineInfo> lineInfos = new ArrayList<>();

  public enum ExceptionClause {
    EXCEPT,
    FINALLY
  }

  public record ExceptionalJump(
      int startInstruction,
      int endInstruction,
      int initialStackDepth,
      int jumpTarget,
      ExceptionClause clauseType) {}

  // Using class instead of record so endInstruction is mutable.
  public static class LineInfo {
    public final int startInstruction;
    public int endInstruction;
    public final int lineno;

    public LineInfo(int startInstruction, int endInstruction, int lineno) {
      this.startInstruction = startInstruction;
      this.endInstruction = endInstruction;
      this.lineno = lineno;
    }

    @Override
    public String toString() {
      return "LineInfo[%d..%d -> line %d]".formatted(startInstruction, endInstruction, lineno);
    }
  }

  public static class InstructionList implements Iterable<Instruction> {
    private final List<Instruction> instructions = new ArrayList<>();

    @Override
    public Iterator<Instruction> iterator() {
      return instructions.iterator();
    }

    public Instruction get(int i) {
      return instructions.get(i);
    }

    public Instruction set(int i, Instruction instruction) {
      return instructions.set(i, instruction);
    }

    public int size() {
      return instructions.size();
    }

    // Unlike List::add, this method is private to restrict additions to the enclosing class.
    private void add(Instruction instruction) {
      instructions.add(instruction);
    }

    public String toStringWithInstructionPointer(int ip) {
      var output = new StringBuilder();
      for (int i = 0; i < size(); ++i) {
        var instruction = get(i);
        String prefix = i == ip ? "> " : "  ";
        output.append("%s[%d] %s\n".formatted(prefix, i, instruction));
      }
      return output.toString();
    }
  }

  List<LineInfo> getLineInfos() {
    return lineInfos;
  }

  public InstructionList instructions() {
    return instructions;
  }

  /** Adds an instruction, returning the instruction pointer of the added instruction. */
  int addInstruction(int lineno, Instruction instruction) {
    int pos = instructions.size();
    if (lineInfos.isEmpty()) {
      lineInfos.add(new LineInfo(pos, pos, lineno));
    } else {
      // If the most recently added LineInfo has the same lineno as the new instruction, update the
      // last LineInfo's endInstruction to include this instruction instead of creating a new
      // LineInfo for the same lineno.
      var last = lineInfos.getLast();
      if (last.lineno == lineno) {
        last.endInstruction = pos;
      } else {
        lineInfos.add(new LineInfo(pos, pos, lineno));
      }
    }
    instructions.add(instruction);
    return pos;
  }

  void registerExceptionalJump(
      int startInstruction,
      int endInstruction,
      int initialStackDepth,
      int jumpTarget,
      ExceptionClause clauseType) {
    jumpTable.add(
        new ExceptionalJump(
            startInstruction, endInstruction, initialStackDepth, jumpTarget, clauseType));
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
