// SPDX-FileCopyrightText: © 2025 Greg Christiana <maxuser@pyjinn.org>
// SPDX-License-Identifier: MIT

package org.pyjinn.interpreter;

import static org.pyjinn.interpreter.Script.getSimpleTypeName;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.pyjinn.interpreter.Script.*;

class Compiler {
  public static void compile(Statement statement, Code code) {
    var compiler = new Compiler(/* withinFunction= */ false);
    compiler.compileStatement(statement, code);
  }

  private static void compileFunctionBody(Statement statement, Code code) {
    var compiler = new Compiler(/* withinFunction= */ true);
    compiler.compileStatement(statement, code);
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

  // Interface for deferring creation of a jump instruction.
  interface Instructor {
    Instruction createInstruction(int jumpTarget);
  }

  private record DeferredJump(int source, Instructor instructor) {}

  private record DeferredJumpList(List<DeferredJump> jumps, List<Instruction> instructions) {
    DeferredJumpList(List<Instruction> instructions) {
      this(new ArrayList<>(), instructions);
    }

    void createDeferredJump(Instructor instructor) {
      int jumpSource = instructions.size();
      instructions.add(null);
      jumps.add(new DeferredJump(jumpSource, instructor));
    }

    void finalizeJumps() {
      int jumpTarget = instructions.size();
      for (var jump : jumps) {
        instructions.set(jump.source, jump.instructor.createInstruction(jumpTarget));
      }
    }
  }

  private static class LoopState {
    public final LoopType type;
    public final int continueTarget;
    private final DeferredJumpList deferredBreaks;

    public LoopState(LoopType type, List<Instruction> instructions) {
      this.type = type;
      continueTarget = instructions.size();
      deferredBreaks = new DeferredJumpList(instructions);
    }

    public void addBreak(Instructor breakInstructor) {
      deferredBreaks.createDeferredJump(breakInstructor);
    }

    public void close() {
      deferredBreaks.finalizeJumps();
    }
  }

  private LoopState loop() {
    return loops.peek();
  }

  private void addBreak(Instructor breakInstructor) {
    if (loops.isEmpty()) {
      throw new IllegalStateException("break statement outside of loop");
    }
    loop().addBreak(breakInstructor);
  }

  private int continueTarget() {
    if (loops.isEmpty()) {
      throw new IllegalStateException("continue statement outside of loop");
    }
    return loop().continueTarget;
  }

  private void compileStatement(Statement statement, Code code) {
    if (statement instanceof StatementBlock block) {
      for (var s : block.statements()) {
        compileStatement(s, code);
      }
    } else if (statement instanceof Expression expr) {
      compileExpression(expr, code.instructions());
      code.instructions().add(new Instruction.PopData());
    } else if (statement instanceof Assignment assign) {
      compileAssignment(assign, code);
    } else if (statement instanceof IfBlock ifBlock) {
      compileIfBlock(ifBlock, code);
    } else if (statement instanceof WhileBlock whileBlock) {
      compileWhileBlock(whileBlock, code);
    } else if (statement instanceof ForBlock forBlock) {
      compileForBlock(forBlock, code);
    } else if (statement instanceof TryBlock tryBlock) {
      compileTryBlock(tryBlock, code);
    } else if (statement instanceof FunctionDef function) {
      compileFunctionDef(function, code);
    } else if (statement instanceof Continue continueStatement) {
      compileContinueStatement(continueStatement, code);
    } else if (statement instanceof Break breakStatement) {
      compileBreakStatement(breakStatement);
    } else if (statement instanceof ReturnStatement returnStatement) {
      compileReturnStatement(returnStatement, code);
    } else {
      throw new IllegalArgumentException("Unsupported statement type: " + statement.getClass());
    }
  }

  private void compileAssignment(Assignment assign, Code code) {
    Expression lhs = assign.lhs();
    if (lhs instanceof Identifier identifier) {
      compileExpression(assign.rhs(), code.instructions());
      code.addInstruction(new Instruction.AssignVariable(identifier.name()));
      /* TODO(maxuser)! support all forms of assignment
      } else if (lhs instanceof FieldAccess fieldAccess) {
      } else if (lhs instanceof ArrayIndex arrayIndex) {
      } else if (lhs instanceof TupleLiteral tuple) {
      */
    } else {
      throw new IllegalArgumentException("Unsupported lhs of assignment: " + lhs.getClass());
    }
  }

  private void compileIfBlock(IfBlock ifBlock, Code code) {
    // if CONDITION:
    //   THEN_BODY
    //
    // compiles to instructions:
    //   [0] eval CONDITION
    //   [1] PopJumpIfFalse 3  # jumpIfBlockSource = 1, jumpIfBlockTarget = 3
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
    //   [1] PopJumpIfFalse 4  # jumpIfBlockSource = 1, jumpIfBlockTarget = 4
    //   [2] execute THEN_BODY
    //   [3] Jump 5  # jumpElseBlockSource = 3, jumpElseBlockTarget = 5
    //   [4] execute ELSE_BODY
    //   [5] ...
    //
    // Note that each "eval" and "execute" operation may expand to multiple instructions.

    var instructions = code.instructions();
    compileExpression(ifBlock.condition(), code.instructions());

    // Add a placeholder null instruction to be filled in below when the jump target is known.
    int jumpIfBlockSource = instructions.size();
    instructions.add(null);
    compileStatement(ifBlock.thenBody(), code);

    if (ifBlock.elseBody().isEmpty()) {
      int jumpIfBlockTarget = instructions.size();
      instructions.set(jumpIfBlockSource, new Instruction.PopJumpIfFalse(jumpIfBlockTarget));
    } else {
      int jumpElseBlockSource = instructions.size();
      instructions.add(null);
      int jumpIfBlockTarget = instructions.size();
      compileStatement(ifBlock.elseBody().get(), code);
      int jumpElseBlockTarget = instructions.size();
      instructions.set(jumpIfBlockSource, new Instruction.PopJumpIfFalse(jumpIfBlockTarget));
      instructions.set(jumpElseBlockSource, new Instruction.Jump(jumpElseBlockTarget));
    }
  }

  private void compileWhileBlock(WhileBlock whileBlock, Code code) {
    // while CONDITION:
    //   BODY
    //
    // compiles to instructions:
    //   [0] eval CONDITION
    //   [1] PopJumpIfFalse 4  # breakLoopSource = 1, breakLoopTarget = 4
    //   [2] execute BODY
    //   [3] Jump 0  # continueLoopSource = 3, continueLoopTarget = 0
    //   [4] ...
    //
    // `continue` in BODY -> Jump 0
    // `break` in BODY -> Jump 4

    var instructions = code.instructions();
    loops.push(new LoopState(LoopType.WHILE, instructions));
    compileExpression(whileBlock.condition(), instructions);
    addBreak(breakTarget -> new Instruction.PopJumpIfFalse(breakTarget));
    compileStatement(whileBlock.body(), code);
    instructions.add(new Instruction.Jump(continueTarget()));
    loops.pop().close();
  }

  private void compileForBlock(ForBlock forBlock, Code code) {
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
    //   [3] PopJumpIfFalse 7
    //   [4] eval $iterator.next()
    //   [5] assign VAR, ... (rhs = $iterator.next())
    //   [6] execute BODY
    //   [7] Jump 1
    //   [8] pop $iterator
    //
    // `continue` in BODY -> Jump 1
    // `break` in BODY -> Jump 7

    var instructions = code.instructions();
    compileExpression(forBlock.iter(), instructions); // [0]
    instructions.add(new Instruction.IterableIterator()); // [1]
    loops.push(new LoopState(LoopType.FOR, instructions));
    instructions.add(new Instruction.IteratorHasNext()); // [2]
    addBreak(breakTarget -> new Instruction.PopJumpIfFalse(breakTarget)); // [3]
    instructions.add(new Instruction.IteratorNext()); // [4]
    instructions.add(iterVarAssignment); // [5]
    compileStatement(forBlock.body(), code); // [6]
    instructions.add(new Instruction.Jump(continueTarget())); // [7]
    loops.pop().close();
    instructions.add(new Instruction.PopData()); // [8]
  }

  private void compileTryBlock(TryBlock tryBlock, Code code) {
    var instructions = code.instructions();
    boolean hasFinally = tryBlock.finallyBlock().isPresent();
    var jumpsToFinally = hasFinally ? new DeferredJumpList(instructions) : null;
    var exceptionHandlers = tryBlock.exceptionHandlers();
    int numHandlers = exceptionHandlers.size();

    int blockStart = instructions.size();
    compileStatement(tryBlock.tryBody(), code);
    int blockEnd = instructions.size();

    // Set to non-negative if there's no finally block and try block needs to jump past the except
    // blocks.
    int jumpPastExceptionHandlers = -1;

    // No need to jump to finally unless there are except handlers between try and finally.
    if (hasFinally) {
      if (numHandlers == 0) {
        code.registerExceptionalJump(
            blockStart, blockEnd, instructions.size(), Code.ExceptionClause.FINALLY);
      } else {
        jumpsToFinally.createDeferredJump(Instruction.Jump::new);
      }
    } else {
      jumpPastExceptionHandlers = instructions.size();
      instructions.add(null);
    }

    for (int i = 0; i < numHandlers; ++i) {
      var handler = exceptionHandlers.get(i);
      boolean isLastHandler = i == numHandlers - 1;

      // For the sequence of blocks 'try', 'except', ..., chain one block's range to jump to the
      // next block's start. Each 'except' block with a declared exception type gets instructions
      // inserted at its beginning that checks if the thrown exception matches the declared
      // exception type.
      code.registerExceptionalJump(
          blockStart, blockEnd, instructions.size(), Code.ExceptionClause.EXCEPT);

      // TODO(maxuser)! Insert instructions for matching exception type. If handler.exceptionType()
      // is present and doesn't matches the active exception, jump to the next 'except/finally'
      // block.

      blockStart = instructions.size();
      compileStatement(handler.body(), code);
      blockEnd = instructions.size();

      instructions.add(new Instruction.SwallowException());
      if (hasFinally && !isLastHandler) {
        jumpsToFinally.createDeferredJump(Instruction.Jump::new);
      }
    }

    if (hasFinally) {
      jumpsToFinally.finalizeJumps();
      compileStatement(tryBlock.finallyBlock().get(), code);
      instructions.add(new Instruction.RethrowException());
    } else {
      instructions.set(jumpPastExceptionHandlers, new Instruction.Jump(instructions.size()));
    }
  }

  private void compileFunctionDef(FunctionDef function, Code code) {
    var functionCode = new Code();
    compileFunctionBody(function.body(), functionCode);

    // Add trailing null return in case there are no earlier returns or earlier returns don't cover
    // all code paths.
    functionCode.instructions().add(new Instruction.PushData(null));
    functionCode.instructions().add(new Instruction.FunctionReturn());

    code.instructions().add(new Instruction.BindFunction(function, functionCode));
  }

  private void compileContinueStatement(Continue continueStatement, Code code) {
    code.addInstruction(new Instruction.Jump(continueTarget()));
  }

  private void compileBreakStatement(Break breakStatement) {
    addBreak(breakTarget -> new Instruction.Jump(breakTarget));
  }

  private void compileReturnStatement(ReturnStatement returnStatement, Code code) {
    if (!withinFunction) {
      throw new IllegalStateException("'return' outside function");
    }

    // Pop iterator from data stack for each enclosing `for` loop. But don't pop or close the loops
    // because there can be instructions following this return statement along other branches.
    loops.stream()
        .filter(loop -> loop.type == LoopType.FOR)
        .forEach(loop -> code.addInstruction(new Instruction.PopData()));

    compileExpression(returnStatement.returnValue(), code.instructions());
    code.addInstruction(new Instruction.FunctionReturn());
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

  private void compileBoundMethod(
      BoundMethodExpression boundMethod, List<Instruction> instructions) {
    compileExpression(boundMethod.object(), instructions);
    instructions.add(
        new Instruction.BoundMethod(
            boundMethod.methodId().name(), boundMethod.symbolCache(), boundMethod.object()));
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
    } else if (expr instanceof FunctionCall functionCall) {
      compileFunctionCall(functionCall, instructions);
    } else if (expr instanceof BoundMethodExpression boundMethod) {
      compileBoundMethod(boundMethod, instructions);
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
    } else if (expr instanceof BoolOp boolOp) {
      // source: VALUE1 and VALUE2 and VALUE3...
      // [0] eval VALUE1
      // [1] JumpIfFalseOrPop 5
      // [2] eval VALUE2
      // [3] JumpIfFalseOrPop 5
      // [4] eval VALUE3
      // [5] ...

      // source: VALUE1 or VALUE2 or VALUE3...
      // [0] eval VALUE1
      // [1] JumpIfTrueOrPop 5
      // [2] eval VALUE2
      // [3] JumpIfTrueOrPop 5
      // [4] eval VALUE3
      // [5] ...

      var values = boolOp.values();
      var placeholderJumps = new ArrayList<Integer>();

      compileExpression(values.get(0), instructions);
      for (int i = 1; i < values.size(); ++i) {
        placeholderJumps.add(instructions.size());
        instructions.add(null);
        compileExpression(boolOp.values().get(i), instructions);
      }

      int jumpTarget = instructions.size();
      var jumpInstruction =
          boolOp.op() == BoolOp.Op.AND
              ? new Instruction.JumpIfFalseOrPop(jumpTarget)
              : new Instruction.JumpIfTrueOrPop(jumpTarget);
      for (int jumpSource : placeholderJumps) {
        instructions.set(jumpSource, jumpInstruction);
      }
    } else if (expr instanceof IfExpression ifExpr) {
      // source: BODY if TEST else OR_ELSE
      // [0] eval TEST
      // [1] PopJumpIfFalse 4  # elseJumpSource = 1, elseJumpTarget = 4
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

      instructions.set(elseJumpSource, new Instruction.PopJumpIfFalse(atElseJumpTarget));
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
