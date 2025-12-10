// SPDX-FileCopyrightText: © 2025 Greg Christiana <maxuser@pyjinn.org>
// SPDX-License-Identifier: MIT

package org.pyjinn.interpreter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Function;
import org.pyjinn.interpreter.Script.*;

class Compiler {
  public static void compile(Statement statement, List<Instruction> instructions) {
    var compiler = new Compiler();
    compiler.compileStatement(statement, instructions);
  }

  private static class LoopState {
    private final List<Instruction> instructions;
    public final int continueTarget;
    private final ArrayList<BreakInstruction> breakInstructions;

    private record BreakInstruction(int source, Function<Integer, Instruction> instructor) {}

    public LoopState(List<Instruction> instructions) {
      this.instructions = instructions;
      continueTarget = instructions.size();
      breakInstructions = new ArrayList<>();
    }

    public void addBreak(Function<Integer, Instruction> breakInstruction) {
      int breakIp = instructions.size();
      instructions.add(null);
      breakInstructions.add(new BreakInstruction(breakIp, breakInstruction));
    }

    public void close(int breakTarget) {
      for (var breakInstruction : breakInstructions) {
        instructions.set(breakInstruction.source, breakInstruction.instructor.apply(breakTarget));
      }
    }
  }

  private final Deque<LoopState> loops = new ArrayDeque<>();

  private LoopState loop() {
    return loops.peek();
  }

  private void addBreak(Function<Integer, Instruction> breakInstruction) {
    if (loops.isEmpty()) {
      throw new IllegalStateException("break statement outside of loop");
    }
    loop().addBreak(breakInstruction);
  }

  private int continueTarget() {
    if (loops.isEmpty()) {
      throw new IllegalStateException("continue statement outside of loop");
    }
    return loop().continueTarget;
  }

  private void compileStatement(Statement statement, List<Instruction> instructions) {
    if (statement instanceof StatementBlock block) {
      for (var s : block.statements()) {
        compileStatement(s, instructions);
      }
    } else if (statement instanceof Expression expr) {
      compileExpression(expr, instructions);
      instructions.add(new Instruction.PopData());
    } else if (statement instanceof Assignment assign) {
      compileAssignment(assign, instructions);
    } else if (statement instanceof IfBlock ifBlock) {
      compileIfBlock(ifBlock, instructions);
    } else if (statement instanceof WhileBlock whileBlock) {
      compileWhileBlock(whileBlock, instructions);
    } else if (statement instanceof FunctionDef function) {
      compileFunctionDef(function, instructions);
    } else if (statement instanceof Continue continueStatement) {
      compileContinueStatement(continueStatement, instructions);
    } else if (statement instanceof Break breakStatement) {
      compileBreakStatement(breakStatement, instructions);
    } else if (statement instanceof ReturnStatement returnStatement) {
      compileReturnStatement(returnStatement, instructions);
    } else {
      throw new IllegalArgumentException("Unsupported statement type: " + statement.getClass());
    }
  }

  private void compileAssignment(Assignment assign, List<Instruction> instructions) {
    Expression lhs = assign.lhs();
    if (lhs instanceof Identifier identifier) {
      compileExpression(assign.rhs(), instructions);
      instructions.add(new Instruction.AssignVariable(identifier.name()));
      /* TODO(maxuser)!
      } else if (lhs instanceof FieldAccess fieldAccess) {
      } else if (lhs instanceof ArrayIndex arrayIndex) {
      } else if (lhs instanceof TupleLiteral tuple) {
      */
    } else {
      throw new IllegalArgumentException("Unsupported lhs of assignment: " + lhs.getClass());
    }
  }

  private void compileIfBlock(IfBlock ifBlock, List<Instruction> instructions) {
    // if CONDITION:
    //   THEN_BODY
    //
    // compiles to instructions:
    //   [0] eval CONDITION
    //   [1] JumpIfFalse 3  # jumpIfBlockSource = 1, jumpIfBlockTarget = 3
    //   [2] execute THEN_BODY
    //   [3] ...
    //
    // if CONDITION:
    //   THEN_BODY
    // else:
    //   ELSE_BODY
    //
    // compiles to instructions:
    //   [0] eval CONDITION
    //   [1] JumpIfFalse 4  # jumpIfBlockSource = 1, jumpIfBlockTarget = 4
    //   [2] execute THEN_BODY
    //   [3] Jump 5  # jumpElseBlockSource = 3, jumpElseBlockTarget = 5
    //   [4] execute ELSE_BODY
    //   [5] ...
    //
    // Note that each "eval" and "execute" operation may expand to multiple instructions.

    compileExpression(ifBlock.condition(), instructions);

    // Add a placeholder null instruction to be filled in below when the jump target is known.
    int jumpIfBlockSource = instructions.size();
    instructions.add(null);
    compileStatement(ifBlock.thenBody(), instructions);

    if (ifBlock.elseBody().isEmpty()) {
      int jumpIfBlockTarget = instructions.size();
      instructions.set(jumpIfBlockSource, new Instruction.JumpIfFalse(jumpIfBlockTarget));
    } else {
      int jumpElseBlockSource = instructions.size();
      instructions.add(null);
      int jumpIfBlockTarget = instructions.size();
      compileStatement(ifBlock.elseBody().get(), instructions);
      int jumpElseBlockTarget = instructions.size();
      instructions.set(jumpIfBlockSource, new Instruction.JumpIfFalse(jumpIfBlockTarget));
      instructions.set(jumpElseBlockSource, new Instruction.Jump(jumpElseBlockTarget));
    }
  }

  private void compileWhileBlock(WhileBlock whileBlock, List<Instruction> instructions) {
    // while CONDITION:
    //   BODY
    //
    // compiles to instructions:
    //   [0] eval CONDITION
    //   [1] JumpIfFalse 4  # breakLoopSource = 1, breakLoopTarget = 4
    //   [2] execute BODY
    //   [3] Jump 0  # continueLoopSource = 3, continueLoopTarget = 0
    //   [4] ...
    //
    // `continue` in BODY -> Jump 0
    // `break` in BODY -> Jump 4

    loops.push(new LoopState(instructions));
    compileExpression(whileBlock.condition(), instructions);
    addBreak(ip -> new Instruction.JumpIfFalse(ip));
    compileStatement(whileBlock.body(), instructions);
    instructions.add(new Instruction.Jump(continueTarget()));
    loops.pop().close(instructions.size());
  }

  private void compileFunctionDef(FunctionDef function, List<Instruction> instructions) {
    var functionInstructions = new ArrayList<Instruction>();
    compile(function.body(), functionInstructions);

    // Add trailing null return in case there are no earlier returns or earlier returns don't cover
    // all code paths.
    instructions.add(new Instruction.PushData(null));
    functionInstructions.add(new Instruction.FunctionReturn());

    instructions.add(new Instruction.BindFunction(function, functionInstructions));
  }

  private void compileContinueStatement(
      Continue continueStatement, List<Instruction> instructions) {
    instructions.add(new Instruction.Jump(continueTarget()));
  }

  private void compileBreakStatement(Break breakStatement, List<Instruction> instructions) {
    addBreak(ip -> new Instruction.Jump(ip));
  }

  private void compileReturnStatement(
      ReturnStatement returnStatement, List<Instruction> instructions) {
    compileExpression(returnStatement.returnValue(), instructions);
    instructions.add(new Instruction.FunctionReturn());
  }

  private void compileFunctionCall(FunctionCall call, List<Instruction> instructions) {
    compileExpression(call.method(), instructions);
    int numArgs = 0;
    for (var kwarg : call.kwargs().reversed()) {
      instructions.add(new Instruction.PushData(kwarg));
      ++numArgs;
    }
    for (var param : call.params().reversed()) {
      compileExpression(param, instructions);
      ++numArgs;
    }
    instructions.add(new Instruction.FunctionCall(call.filename(), call.lineno(), numArgs));
  }

  private void compileExpression(Expression expr, List<Instruction> instructions) {
    if (expr instanceof Identifier identifier) {
      instructions.add(new Instruction.Identifier(identifier.name()));
    } else if (expr instanceof FunctionCall call) {
      compileFunctionCall(call, instructions);
    } else if (expr instanceof StarredExpression starred) {
      compileExpression(starred.value(), instructions);
      instructions.add(new Instruction.Star());
    } else if (expr instanceof ConstantExpression constant) {
      instructions.add(new Instruction.Constant(constant.value()));
    } else if (expr instanceof BinaryOp binaryOp) {
      compileExpression(binaryOp.lhs(), instructions);
      compileExpression(binaryOp.rhs(), instructions);
      instructions.add(new Instruction.BinaryOp(binaryOp.op()));
    } else if (expr instanceof Comparison comparison) {
      compileExpression(comparison.lhs(), instructions);
      compileExpression(comparison.rhs(), instructions);
      instructions.add(new Instruction.Comparison(comparison.op()));
    } else if (expr instanceof IfExpression ifExpr) {
      // source: BODY if TEST else OR_ELSE
      // [0] eval TEST
      // [1] JumpIfFalse 4  # elseJumpSource = 1, elseJumpTarget = 4
      // [2] eval BODY
      // [3] Jump 5  # skipElseJumpSource = 3, skipElseJumpTarget = 5
      // [4] eval OR_ELSE
      // [5] ...
      //
      // Note that each "eval" operation may expand to multiple instructions.
      compileExpression(ifExpr.test(), instructions);

      int elseJumpSource = instructions.size();
      instructions.add(null);

      compileExpression(ifExpr.body(), instructions);

      int skipElseJumpSource = instructions.size();
      instructions.add(null);

      int atElseJumpTarget = instructions.size();
      compileExpression(ifExpr.orElse(), instructions);
      int afterElseJumpTarget = instructions.size();

      instructions.set(elseJumpSource, new Instruction.JumpIfFalse(atElseJumpTarget));
      instructions.set(skipElseJumpSource, new Instruction.Jump(afterElseJumpTarget));
    } else {
      throw new UnsupportedOperationException(
          "Expression type not supported: " + expr.getClass().getName());
    }
  }
}
