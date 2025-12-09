// SPDX-FileCopyrightText: © 2025 Greg Christiana <maxuser@pyjinn.org>
// SPDX-License-Identifier: MIT

package org.pyjinn.interpreter;

import java.util.ArrayList;
import java.util.List;
import org.pyjinn.interpreter.Script.*;

class Compiler {
  public static void compile(Statement statement, List<Instruction> instructions) {
    compileStatement(statement, instructions);
  }

  private static void compileStatement(Statement statement, List<Instruction> instructions) {
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
    } else if (statement instanceof FunctionDef function) {
      compileFunctionDef(function, instructions);
    } else if (statement instanceof ReturnStatement returnStatement) {
      compileReturnStatement(returnStatement, instructions);
    } else {
      throw new IllegalArgumentException("Unsupported statement type: " + statement.getClass());
    }
  }

  private static void compileAssignment(Assignment assign, List<Instruction> instructions) {
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

  private static void compileIfBlock(IfBlock ifBlock, List<Instruction> instructions) {
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

  private static void compileFunctionDef(FunctionDef function, List<Instruction> instructions) {
    var functionInstructions = new ArrayList<Instruction>();
    compile(function.body(), functionInstructions);

    // Add trailing null return in case there are no earlier returns or earlier returns don't cover
    // all code paths.
    instructions.add(new Instruction.PushData(null));
    functionInstructions.add(new Instruction.FunctionReturn());

    instructions.add(new Instruction.BindFunction(function, functionInstructions));
  }

  private static void compileReturnStatement(
      ReturnStatement returnStatement, List<Instruction> instructions) {
    compileExpression(returnStatement.returnValue(), instructions);
    instructions.add(new Instruction.FunctionReturn());
  }

  private static void compileFunctionCall(FunctionCall call, List<Instruction> instructions) {
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

  private static void compileExpression(Expression expr, List<Instruction> instructions) {
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
    } else {
      throw new UnsupportedOperationException(
          "Expression type not supported: " + expr.getClass().getName());
    }
  }
}
