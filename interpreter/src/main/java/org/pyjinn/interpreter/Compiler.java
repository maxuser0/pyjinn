// SPDX-FileCopyrightText: © 2025 Greg Christiana <maxuser@pyjinn.org>
// SPDX-License-Identifier: MIT

package org.pyjinn.interpreter;

import static org.pyjinn.interpreter.Script.getSimpleTypeName;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Function;
import org.pyjinn.interpreter.Script.*;

class Compiler {
  private static boolean debug = false;

  public static void compile(Statement statement, List<Instruction> instructions) {
    var compiler = new Compiler(/* withinFunction= */ false);
    compiler.compileStatement(statement, instructions);
    if (debug) {
      for (int i = 0; i < instructions.size(); ++i) {
        System.out.printf("[%d] %s\n", i, instructions.get(i));
      }
    }
  }

  private static void compileFunctionBody(Statement statement, List<Instruction> instructions) {
    var compiler = new Compiler(/* withinFunction= */ true);
    compiler.compileStatement(statement, instructions);
  }

  private enum LoopType {
    WHILE,
    FOR
  }

  private final boolean withinFunction;
  private final Deque<LoopState> loops = new ArrayDeque<>();

  private Compiler(boolean withinFunction) {
    this.withinFunction = withinFunction;
  }

  private static class LoopState {
    public final LoopType type;
    private final List<Instruction> instructions;
    public final int continueTarget;
    private final ArrayList<BreakInstruction> breakInstructions;

    private record BreakInstruction(int source, Function<Integer, Instruction> instructor) {}

    public LoopState(LoopType type, List<Instruction> instructions) {
      this.type = type;
      this.instructions = instructions;
      continueTarget = instructions.size();
      breakInstructions = new ArrayList<>();
    }

    public void addBreak(Function<Integer, Instruction> breakInstruction) {
      int breakIp = instructions.size();
      instructions.add(null);
      breakInstructions.add(new BreakInstruction(breakIp, breakInstruction));
    }

    public void close() {
      int breakTarget = instructions.size();
      for (var breakInstruction : breakInstructions) {
        instructions.set(breakInstruction.source, breakInstruction.instructor.apply(breakTarget));
      }
    }
  }

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
    } else if (statement instanceof ForBlock forBlock) {
      compileForBlock(forBlock, instructions);
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

    loops.push(new LoopState(LoopType.WHILE, instructions));
    compileExpression(whileBlock.condition(), instructions);
    addBreak(breakPos -> new Instruction.JumpIfFalse(breakPos));
    compileStatement(whileBlock.body(), instructions);
    instructions.add(new Instruction.Jump(continueTarget()));
    loops.pop().close();
  }

  private void compileForBlock(ForBlock forBlock, List<Instruction> instructions) {
    var vars = forBlock.vars();
    final Instruction iterVarAssignment;
    if (vars instanceof Identifier id) {
      iterVarAssignment = new Instruction.AssignVariable(id.name());
    } else if (vars instanceof TupleLiteral lhsTuple) {
      iterVarAssignment =
          new Instruction.AssignTuple(
              lhsTuple.elements().stream().map(Identifier.class::cast).toList());
    } else {
      throw new IllegalArgumentException("Unexpected loop variable type: " + vars.toString());
    }

    // for VAR, ... in ITER:
    //   BODY
    //
    // compiles to instructions:
    //   [0] eval ITER (Iterable<?>)
    //   [1] eval ITER.iterator() (leave on data stack: $iterator)
    //   [2] eval $iterator.hasNext()
    //   [3] JumpIfFalse 7
    //   [4] eval $iterator.next()
    //   [5] assign VAR, ... (rhs = $iterator.next())
    //   [6] execute BODY
    //   [7] Jump 1
    //   [8] pop $iterator
    //
    // `continue` in BODY -> Jump 1
    // `break` in BODY -> Jump 7

    compileExpression(forBlock.iter(), instructions); // [0]
    instructions.add(new Instruction.IterableIterator()); // [1]
    loops.push(new LoopState(LoopType.FOR, instructions));
    instructions.add(new Instruction.IteratorHasNext()); // [2]
    addBreak(breakPos -> new Instruction.JumpIfFalse(breakPos)); // [3]
    instructions.add(new Instruction.IteratorNext()); // [4]
    instructions.add(iterVarAssignment); // [5]
    compileStatement(forBlock.body(), instructions); // [6]
    instructions.add(new Instruction.Jump(continueTarget())); // [7]
    loops.pop().close();
    instructions.add(new Instruction.PopData()); // [8]
  }

  private void compileFunctionDef(FunctionDef function, List<Instruction> instructions) {
    var functionInstructions = new ArrayList<Instruction>();
    compileFunctionBody(function.body(), functionInstructions);

    // Add trailing null return in case there are no earlier returns or earlier returns don't cover
    // all code paths.
    functionInstructions.add(new Instruction.PushData(null));
    functionInstructions.add(new Instruction.FunctionReturn());

    instructions.add(new Instruction.BindFunction(function, functionInstructions));
  }

  private void compileContinueStatement(
      Continue continueStatement, List<Instruction> instructions) {
    instructions.add(new Instruction.Jump(continueTarget()));
  }

  private void compileBreakStatement(Break breakStatement, List<Instruction> instructions) {
    addBreak(breakPos -> new Instruction.Jump(breakPos));
  }

  private void compileReturnStatement(
      ReturnStatement returnStatement, List<Instruction> instructions) {
    if (!withinFunction) {
      throw new IllegalStateException("'return' outside function");
    }

    // Pop iterator from data stack for each enclosing `for` loop. But don't pop or close the loops
    // because there can be instructions following this return statement along other branches.
    loops.stream()
        .filter(loop -> loop.type == LoopType.FOR)
        .forEach(loop -> instructions.add(new Instruction.PopData()));

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
    } else if (expr instanceof TupleLiteral tuple) {
      compileTupleLiteral(tuple, instructions);
    } else if (expr instanceof ListLiteral list) {
      compileListLiteral(list, instructions);
    } else if (expr instanceof SetLiteral set) {
      compileSetLiteral(set, instructions);
    } else if (expr instanceof FunctionCall call) {
      compileFunctionCall(call, instructions);
    } else if (expr instanceof StarredExpression starred) {
      compileExpression(starred.value(), instructions);
      instructions.add(new Instruction.Star());
    } else if (expr instanceof ConstantExpression constant) {
      instructions.add(new Instruction.Constant(constant.value()));
    } else if (expr instanceof UnaryOp unaryOp) {
      compileExpression(unaryOp.operand(), instructions);
      instructions.add(new Instruction.UnaryOp(unaryOp.op()));
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
          "Expression type not supported: " + getSimpleTypeName(expr));
    }
  }

  private void compileTupleLiteral(TupleLiteral tuple, List<Instruction> instructions) {
    // Push elements' values onto the stack in head to tail order.
    for (var element : tuple.elements()) {
      compileExpression(element, instructions);
    }
    instructions.add(new Instruction.LoadTuple(tuple.elements().size()));
  }

  private void compileListLiteral(ListLiteral list, List<Instruction> instructions) {
    // Push elements' values onto the stack in head to tail order.
    for (var element : list.elements()) {
      compileExpression(element, instructions);
    }
    instructions.add(new Instruction.LoadList(list.elements().size()));
  }

  private void compileSetLiteral(SetLiteral set, List<Instruction> instructions) {
    // Push elements' values onto the stack in head to tail order.
    for (var element : set.elements()) {
      compileExpression(element, instructions);
    }
    instructions.add(new Instruction.LoadSet(set.elements().size()));
  }
}
