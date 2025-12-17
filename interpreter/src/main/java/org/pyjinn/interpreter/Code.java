// SPDX-FileCopyrightText: © 2025 Greg Christiana <maxuser@pyjinn.org>
// SPDX-License-Identifier: MIT

package org.pyjinn.interpreter;

import java.util.ArrayList;
import java.util.List;

public record Code(List<Instruction> instructions, JumpTable jumpTable) {
  // TODO(maxuser): Implement jump table for responding to exceptions.
  public record JumpTable() {}

  public Code() {
    this(new ArrayList<>(), new JumpTable());
  }

  void add(Instruction instruction) {
    instructions.add(instruction);
  }
}
