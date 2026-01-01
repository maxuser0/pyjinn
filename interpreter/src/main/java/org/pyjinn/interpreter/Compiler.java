// SPDX-FileCopyrightText: © 2025 Greg Christiana <maxuser@pyjinn.org>
// SPDX-License-Identifier: MIT

package org.pyjinn.interpreter;

import static org.pyjinn.interpreter.Script.getSimpleTypeName;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import org.pyjinn.interpreter.Code.InstructionList;
import org.pyjinn.interpreter.Script.*;

class Compiler {
  public static void compile(Statement statement, Code code) {
    var compiler = new Compiler(/* withinFunction= */ false);
    compiler.compileStatement(statement, code);
  }

  private static void compileFunctionBody(int lineno, Statement statement, Code code) {
    var compiler = new Compiler(/* withinFunction= */ true);
    compiler.lineno = lineno;
    compiler.compileStatement(statement, code);
  }

  private enum LoopType {
    WHILE,
    FOR
  }

  private final boolean withinFunction;
  private final Deque<LoopState> loops = new ArrayDeque<>();
  private int lineno = -1;

  private Compiler(boolean withinFunction) {
    this.withinFunction = withinFunction;
  }

  // Interface for deferring creation of a jump instruction.
  interface Instructor {
    Instruction createInstruction(int jumpTarget);
  }

  private record DeferredJump(int source, Instructor instructor) {}

  private record DeferredJumpList(List<DeferredJump> jumps, InstructionList instructions) {
    DeferredJumpList(InstructionList instructions) {
      this(new ArrayList<>(), instructions);
    }

    void createDeferredJump(Instructor instructor) {
      int jumpSource = instructions.addPlaceholder();
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

    public LoopState(LoopType type, InstructionList instructions) {
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
    if (statement.lineno() != -1) {
      lineno = statement.lineno();
    }

    if (statement instanceof StatementBlock block) {
      for (var s : block.statements()) {
        compileStatement(s, code);
      }
    } else if (statement instanceof Expression expr) {
      compileExpression(expr, code);
      code.addInstruction(lineno, new Instruction.PopData());
    } else if (statement instanceof Assignment assign) {
      compileAssignment(assign, code);
    } else if (statement instanceof AugmentedAssignment augAssign) {
      compileAugmentedAssignment(augAssign, code);
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
    } else if (statement instanceof ClassDef classDef) {
      compileClassDef(classDef, code);
    } else if (statement instanceof DataclassDefaultInit dataclassInit) {
      compileDataclassDefaultInit(dataclassInit, code);
    } else if (statement instanceof Continue continueStatement) {
      compileContinueStatement(continueStatement, code);
    } else if (statement instanceof Break breakStatement) {
      compileBreakStatement(breakStatement);
    } else if (statement instanceof ReturnStatement returnStatement) {
      compileReturnStatement(returnStatement, code);
    } else if (statement instanceof Deletion deletion) {
      compileDeletion(deletion, code);
    } else {
      throw new IllegalArgumentException("Unsupported statement type: " + statement.getClass());
    }
  }

  private void compileAssignment(Assignment assign, Code code) {
    Expression lhs = assign.lhs();
    if (lhs instanceof Identifier identifier) {
      compileExpression(assign.rhs(), code);
      code.addInstruction(lineno, new Instruction.AssignVariable(identifier.name()));
    } else if (lhs instanceof FieldAccess fieldAccess) {
      compileExpression(assign.rhs(), code);
      compileExpression(fieldAccess.object(), code);
      code.addInstruction(lineno, new Instruction.AssignField(fieldAccess.field().name()));
    } else if (lhs instanceof ArrayIndex arrayIndex) {
      compileExpression(assign.rhs(), code);
      compileExpression(arrayIndex.array(), code);
      compileExpression(arrayIndex.index(), code);
      code.addInstruction(lineno, new Instruction.AssignArrayIndex());
    } else if (lhs instanceof TupleLiteral lhsTuple) {
      compileExpression(assign.rhs(), code);
      code.addInstruction(
          lineno,
          new Instruction.AssignTuple(
              lhsTuple.elements().stream().map(Identifier.class::cast).toList()));
    } else {
      throw new IllegalArgumentException(
          "Cannot assign to %s (line %d)"
              .formatted(lhs.getClass().getSimpleName(), assign.lineno()));
    }
  }

  private void compileAugmentedAssignment(AugmentedAssignment assign, Code code) {
    Expression lhs = assign.lhs();
    if (lhs instanceof Identifier identifier) {
      compileExpression(assign.rhs(), code);
      code.addInstruction(lineno, new Instruction.AugmentVariable(identifier.name(), assign.op()));
    } else if (lhs instanceof FieldAccess fieldAccess) {
      compileExpression(assign.rhs(), code);
      compileExpression(fieldAccess.object(), code);
      code.addInstruction(
          lineno, new Instruction.AugmentField(fieldAccess.field().name(), assign.op()));
    } else if (lhs instanceof ArrayIndex arrayIndex) {
      compileExpression(assign.rhs(), code);
      compileExpression(arrayIndex.array(), code);
      compileExpression(arrayIndex.index(), code);
      code.addInstruction(lineno, new Instruction.AugmentArrayIndex(assign.op()));
    } else {
      throw new IllegalArgumentException(
          "Cannot assign to %s (line %d)"
              .formatted(lhs.getClass().getSimpleName(), assign.lineno()));
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
    compileExpression(ifBlock.condition(), code);

    // Add a placeholder null instruction to be filled in below when the jump target is known.
    int jumpIfBlockSource = instructions.addPlaceholder();
    compileStatement(ifBlock.thenBody(), code);

    if (ifBlock.elseBody().isEmpty()) {
      int jumpIfBlockTarget = instructions.size();
      instructions.set(jumpIfBlockSource, new Instruction.PopJumpIfFalse(jumpIfBlockTarget));
    } else {
      int jumpElseBlockSource = code.addPlaceholder();
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

    loops.push(new LoopState(LoopType.WHILE, code.instructions()));
    compileExpression(whileBlock.condition(), code);
    addBreak(breakTarget -> new Instruction.PopJumpIfFalse(breakTarget));
    compileStatement(whileBlock.body(), code);
    code.addInstruction(lineno, new Instruction.Jump(continueTarget()));
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

    compileExpression(forBlock.iter(), code); // [0]
    code.addInstruction(lineno, new Instruction.IterableIterator()); // [1]
    loops.push(new LoopState(LoopType.FOR, code.instructions()));
    code.addInstruction(lineno, new Instruction.IteratorHasNext()); // [2]
    addBreak(breakTarget -> new Instruction.PopJumpIfFalse(breakTarget)); // [3]
    code.addInstruction(lineno, new Instruction.IteratorNext()); // [4]
    code.addInstruction(lineno, iterVarAssignment); // [5]
    compileStatement(forBlock.body(), code); // [6]
    code.addInstruction(lineno, new Instruction.Jump(continueTarget())); // [7]
    loops.pop().close();
    code.addInstruction(lineno, new Instruction.PopData()); // [8]
  }

  private void compileTryBlock(TryBlock tryBlock, Code code) {
    var instructions = code.instructions();
    boolean hasFinally = tryBlock.finallyBlock().isPresent();
    var jumpsToFinally = hasFinally ? new DeferredJumpList(instructions) : null;
    var jumpsPastExceptionHandlers = hasFinally ? null : new DeferredJumpList(instructions);
    var exceptionHandlers = tryBlock.exceptionHandlers();
    int numHandlers = exceptionHandlers.size();

    int blockStart = instructions.size();
    compileStatement(tryBlock.tryBody(), code);
    int blockEnd = instructions.size();

    // No need to jump to finally unless there are except handlers between try and finally.
    if (hasFinally) {
      if (numHandlers > 0) {
        jumpsToFinally.createDeferredJump(Instruction.Jump::new);
      }
    } else {
      jumpsPastExceptionHandlers.createDeferredJump(Instruction.Jump::new);
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

      blockStart = instructions.size();
      // CatchExceptionType instruction must be inside the [blockStart, blockEnd) range because that
      // instruction rethrows the exception if it doesn't match the formal exception type of the
      // 'except' clause.
      if (handler.exceptionTypeSpec().isPresent()) {
        compileExpression(handler.exceptionTypeSpec().get(), code);
        code.addInstruction(
            lineno,
            new Instruction.CatchExceptionType(handler.exceptionVariable().map(Identifier::name)));
      }
      compileStatement(handler.body(), code);
      blockEnd = instructions.size();

      code.addInstruction(lineno, new Instruction.SwallowException());
      if (!isLastHandler) {
        if (hasFinally) {
          jumpsToFinally.createDeferredJump(Instruction.Jump::new);
        } else {
          jumpsPastExceptionHandlers.createDeferredJump(Instruction.Jump::new);
        }
      }
    }

    if (hasFinally) {
      code.registerExceptionalJump(
          blockStart, blockEnd, instructions.size(), Code.ExceptionClause.FINALLY);
      jumpsToFinally.finalizeJumps();
      compileStatement(tryBlock.finallyBlock().get(), code);
      code.addInstruction(lineno, new Instruction.RethrowException());
    } else {
      jumpsPastExceptionHandlers.finalizeJumps();
    }
  }

  private void compileFunctionDef(FunctionDef function, Code code) {
    code.addInstruction(lineno, new Instruction.BindFunction(function, compileFunction(function)));
  }

  private void compileClassDef(ClassDef classDef, Code code) {
    code.addInstruction(lineno, new Instruction.DefineClass(classDef, this::compileFunction));
  }

  private void compileDataclassDefaultInit(DataclassDefaultInit dataclassInit, Code code) {
    code.addInstruction(lineno, new Instruction.DataclassDefaultInit(dataclassInit));
  }

  private Code compileFunction(FunctionDef function) {
    var code = new Code();
    compileFunctionBody(function.lineno(), function.body(), code);

    // Add trailing null return in case there are no earlier returns or earlier returns don't cover
    // all code paths.
    code.addInstruction(lineno, new Instruction.PushData(null));
    code.addInstruction(lineno, new Instruction.FunctionReturn());
    return code;
  }

  private void compileContinueStatement(Continue continueStatement, Code code) {
    code.addInstruction(lineno, new Instruction.Jump(continueTarget()));
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
        .forEach(loop -> code.addInstruction(lineno, new Instruction.PopData()));

    compileExpression(returnStatement.returnValue(), code);
    code.addInstruction(lineno, new Instruction.FunctionReturn());
  }

  private void compileDeletion(Deletion deletion, Code code) {
    for (var target : deletion.targets()) {
      if (target instanceof Identifier id) {
        code.addInstruction(lineno, new Instruction.DeleteVariable(id.name()));
      } else if (target instanceof ArrayIndex arrayIndex) {
        compileExpression(arrayIndex.array(), code);
        compileExpression(arrayIndex.index(), code);
        code.addInstruction(lineno, new Instruction.DeleteArrayIndex());
      } else {
        throw new IllegalArgumentException("Cannot delete value: " + target.toString());
      }
    }
  }

  private void compileFunctionCall(FunctionCall call, Code code) {
    compileExpression(call.method(), code);
    int numArgs = 0;
    for (var kwarg : call.kwargs().reversed()) {
      code.addInstruction(lineno, new Instruction.PushData(kwarg));
      ++numArgs;
    }
    for (var param : call.params().reversed()) {
      compileExpression(param, code);
      ++numArgs;
    }
    code.addInstruction(
        lineno, new Instruction.FunctionCall(call.filename(), call.lineno(), numArgs));
  }

  private void compileBoundMethod(BoundMethodExpression boundMethod, Code code) {
    compileExpression(boundMethod.object(), code);
    code.addInstruction(
        lineno,
        new Instruction.BoundMethod(
            boundMethod.methodId().name(), boundMethod.symbolCache(), boundMethod.object()));
  }

  private void compileExpressionOrPushNull(Optional<Expression> expr, Code code) {
    if (expr.isPresent()) {
      compileExpression(expr.get(), code);
    } else {
      code.addInstruction(lineno, new Instruction.PushData(null));
    }
  }

  private void compileExpression(Expression expr, Code code) {
    if (expr instanceof Identifier identifier) {
      code.addInstruction(lineno, new Instruction.Identifier(identifier.name()));
    } else if (expr instanceof TupleLiteral tuple) {
      compileTupleLiteral(tuple, code);
    } else if (expr instanceof ListLiteral list) {
      compileListLiteral(list, code);
    } else if (expr instanceof SetLiteral set) {
      compileSetLiteral(set, code);
    } else if (expr instanceof FunctionCall functionCall) {
      compileFunctionCall(functionCall, code);
    } else if (expr instanceof BoundMethodExpression boundMethod) {
      compileBoundMethod(boundMethod, code);
    } else if (expr instanceof StarredExpression starred) {
      compileExpression(starred.value(), code);
      code.addInstruction(lineno, new Instruction.Star());
    } else if (expr instanceof ConstantExpression constant) {
      code.addInstruction(lineno, new Instruction.Constant(constant.value()));
    } else if (expr instanceof JavaClassCall javaClassCall) {
      code.addInstruction(lineno, new Instruction.LoadJavaClass(javaClassCall));
    } else if (expr instanceof UnaryOp unaryOp) {
      compileExpression(unaryOp.operand(), code);
      code.addInstruction(lineno, new Instruction.UnaryOp(unaryOp.op()));
    } else if (expr instanceof BinaryOp binaryOp) {
      compileExpression(binaryOp.lhs(), code);
      compileExpression(binaryOp.rhs(), code);
      code.addInstruction(lineno, new Instruction.BinaryOp(binaryOp.op()));
    } else if (expr instanceof Comparison comparison) {
      compileExpression(comparison.lhs(), code);
      compileExpression(comparison.rhs(), code);
      // If comparison is "lhs not in rhs", compile as "not (lhs in rhs)" to simplify use of rhs
      // overload __contains__.
      if (comparison.op() == Comparison.Op.NOT_IN) {
        code.addInstruction(lineno, new Instruction.Comparison(Comparison.Op.IN));
        code.addInstruction(lineno, new Instruction.UnaryOp(UnaryOp.Op.NOT));
      } else {
        code.addInstruction(lineno, new Instruction.Comparison(comparison.op()));
      }
    } else if (expr instanceof FieldAccess fieldAccess) {
      compileExpression(fieldAccess.object(), code);
      code.addInstruction(lineno, new Instruction.FieldAccess(fieldAccess));
    } else if (expr instanceof ArrayIndex arrayIndex) {
      compileExpression(arrayIndex.array(), code);
      compileExpression(arrayIndex.index(), code);
      code.addInstruction(lineno, new Instruction.ArrayIndex());
    } else if (expr instanceof SliceExpression slice) {
      compileExpressionOrPushNull(slice.start(), code);
      compileExpressionOrPushNull(slice.stop(), code);
      compileExpressionOrPushNull(slice.step(), code);
      code.addInstruction(lineno, new Instruction.Slice());
    } else if (expr instanceof DictLiteral dict) {
      int numItems = dict.keys().size();
      var keys = dict.keys();
      var values = dict.values();
      for (int i = 0; i < numItems; ++i) {
        compileExpression(keys.get(i), code);
        compileExpression(values.get(i), code);
      }
      code.addInstruction(lineno, new Instruction.CreateDict(numItems));
    } else if (expr instanceof FormattedString fstr) {
      for (var value : fstr.values()) {
        compileExpression(value, code);
      }
      code.addInstruction(lineno, new Instruction.FormattedString(fstr.values().size()));
    } else if (expr instanceof FormattedValue formattedValue) {
      compileExpression(formattedValue.value(), code);
      code.addInstruction(lineno, new Instruction.FormattedValue(formattedValue.format()));
    } else if (expr instanceof Lambda lambda) {
      code.addInstruction(
          lineno,
          new Instruction.Lambda(lambda.functionDef(), compileFunction(lambda.functionDef())));
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

      compileExpression(values.get(0), code);
      for (int i = 1; i < values.size(); ++i) {
        placeholderJumps.add(code.addPlaceholder());
        compileExpression(boolOp.values().get(i), code);
      }

      var instructions = code.instructions();
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
      compileExpression(ifExpr.test(), code);

      int elseJumpSource = code.addPlaceholder();

      compileExpression(ifExpr.body(), code);

      int skipElseJumpSource = code.addPlaceholder();

      var instructions = code.instructions();
      int atElseJumpTarget = instructions.size();
      compileExpression(ifExpr.orElse(), code);
      int afterElseJumpTarget = instructions.size();

      instructions.set(elseJumpSource, new Instruction.PopJumpIfFalse(atElseJumpTarget));
      instructions.set(skipElseJumpSource, new Instruction.Jump(afterElseJumpTarget));
    } else if (expr instanceof ListComprehension listComp) {
      // source: [TRANSFORM for TARGET in ITER if IFS[0]...]
      var vars = listComp.target();
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

      var listCompCode = new Code();
      listCompCode.addInstruction(lineno, new Instruction.LoadList(0));
      compileExpression(listComp.iter(), listCompCode);
      listCompCode.addInstruction(lineno, new Instruction.IterableIterator());
      loops.push(new LoopState(LoopType.FOR, listCompCode.instructions()));
      listCompCode.addInstruction(lineno, new Instruction.IteratorHasNext());
      addBreak(breakTarget -> new Instruction.PopJumpIfFalse(breakTarget));
      listCompCode.addInstruction(lineno, new Instruction.IteratorNext());
      listCompCode.addInstruction(lineno, iterVarAssignment);
      for (var ifClause : listComp.ifs()) {
        compileExpression(ifClause, listCompCode);
        listCompCode.addInstruction(lineno, new Instruction.PopJumpIfFalse(continueTarget()));
      }
      compileExpression(listComp.transform(), listCompCode);
      listCompCode.addInstruction(lineno, new Instruction.AppendListAtOffset(-3));
      listCompCode.addInstruction(lineno, new Instruction.Jump(continueTarget()));
      loops.pop().close();
      listCompCode.addInstruction(lineno, new Instruction.PopData()); // pop iter
      listCompCode.addInstruction(lineno, new Instruction.FunctionReturn());

      var function =
          new FunctionDef(
              lineno,
              /* enclosingClassName= */ "<>",
              new Identifier("<listcomp>"),
              /* decorators= */ List.of(),
              /* args= */ List.of(),
              /* vararg= */ Optional.empty(),
              /* kwarg= */ Optional.empty(),
              /* defaults= */ List.of(),
              new Pass()); // Body statement is unused because listCompCode is executed directly.

      code.addInstruction(
          lineno, new Instruction.NullaryCompileOnlyFunctionCall(function, listCompCode));
    } else {
      throw new UnsupportedOperationException(
          "Expression type not supported: " + getSimpleTypeName(expr));
    }
  }

  private void compileTupleLiteral(TupleLiteral tuple, Code code) {
    // Push elements' values onto the stack in head to tail order.
    for (var element : tuple.elements()) {
      compileExpression(element, code);
    }
    code.addInstruction(lineno, new Instruction.LoadTuple(tuple.elements().size()));
  }

  private void compileListLiteral(ListLiteral list, Code code) {
    // Push elements' values onto the stack in head to tail order.
    for (var element : list.elements()) {
      compileExpression(element, code);
    }
    code.addInstruction(lineno, new Instruction.LoadList(list.elements().size()));
  }

  private void compileSetLiteral(SetLiteral set, Code code) {
    // Push elements' values onto the stack in head to tail order.
    for (var element : set.elements()) {
      compileExpression(element, code);
    }
    code.addInstruction(lineno, new Instruction.LoadSet(set.elements().size()));
  }
}
