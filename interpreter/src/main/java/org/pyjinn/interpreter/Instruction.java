// SPDX-FileCopyrightText: © 2025 Greg Christiana <maxuser@pyjinn.org>
// SPDX-License-Identifier: MIT

package org.pyjinn.interpreter;

import static org.pyjinn.interpreter.Script.convertToBool;
import static org.pyjinn.interpreter.Script.getSimpleTypeName;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.pyjinn.interpreter.Script.*;

sealed interface Instruction {
  Context execute(Context context) throws RuntimeException;

  record PushData(Object value) implements Instruction {
    @Override
    public Context execute(Context context) {
      context.pushData(value);
      ++context.ip;
      return context;
    }
  }

  record BoundMethod(String methodName, SymbolCache symbolCache, Expression objectExpression)
      implements Instruction {
    @Override
    public Context execute(Context context) {
      var object = context.popData();
      context.pushData(new Script.BoundMethod(object, methodName, symbolCache, objectExpression));
      ++context.ip;
      return context;
    }
  }

  record StarredValue(Object value) {}

  record KeywordArg(String name, Object value) {}

  record FunctionCall(String filename, int lineno, int numArgs) implements Instruction {
    @Override
    public Context execute(Context context) {
      Object[] params = new Object[numArgs];
      for (int i = 0; i < numArgs; ++i) {
        Object value = context.popData();
        if (value instanceof Script.KeywordArg arg) {
          params[i] = new KeywordArg(arg.name(), arg.value().eval(context));
        } else if (value instanceof StarredExpression expr) {
          params[i] = new StarredValue(expr.value().eval(context));
        } else {
          params[i] = value;
        }
      }
      var function = context.popData();
      return call(context, function, params);
    }

    private Context call(Context context, Object caller, Object[] params) {
      List<Object> paramValues = resolveParams(params);
      if (caller instanceof Class<?> type) {
        Function function;
        if ((function = InterfaceProxy.getFunctionPassedToInterface(type, paramValues.toArray()))
            != null) {
          context.pushData(
              InterfaceProxy.promoteFunctionToJavaInterface(context.env(), type, function));
          ++context.ip;
          return context;
        }
      }

      // Translate len(x) to x.__len__().
      if (caller instanceof LenFunction && paramValues.size() == 1) {
        var function = getMethod(paramValues.get(0), "__len__");
        if (function != null) {
          return executeCompiledFunction(context, function, paramValues.toArray());
        }
      }

      // Effective caller may be a function that's being delegated to.
      var effectiveCaller = caller;

      // Specialize handling of BoundMethod so instructions can be interrupted.
      if (caller instanceof Script.BoundMethod binding
          && binding.object() instanceof PyjObject pyjObject) {
        var method = pyjObject.__class__.instanceMethods.get(binding.methodName());
        if (method != null && method instanceof BoundFunction function) {
          var methodParams = new ArrayList<Object>(paramValues.size() + 1);
          methodParams.add(pyjObject);
          methodParams.addAll(paramValues);
          return executeCompiledFunction(context, function, methodParams.toArray());
        }

        // If PyjObject's field is assigned to a function, make that function the effective caller.
        var field = pyjObject.__dict__.get(binding.methodName());
        if (field != null && field instanceof Function function) {
          effectiveCaller = function;
        }
      }

      // Specialize handling of BoundFunction so instructions can be interrupted.
      if (effectiveCaller instanceof BoundFunction function) {
        return executeCompiledFunction(context, function, paramValues.toArray());
      }

      if (effectiveCaller instanceof Function function) {
        try {
          context.enterFunction(filename, lineno);
          context.pushData(function.call(context.env(), paramValues.toArray()));
          ++context.ip;
          return context;
        } finally {
          context.leaveFunction();
        }
      }

      throw new IllegalArgumentException(
          String.format(
              "'%s' is not callable", caller == null ? "NoneType" : caller.getClass().getName()));
    }

    static BoundFunction getMethod(Object object, String methodName) {
      if (object instanceof PyjObject pyjObject) {
        var meth = pyjObject.__class__.instanceMethods.get(methodName);
        if (meth != null && meth instanceof BoundFunction function) {
          return function;
        }
      }
      return null;
    }

    private static List<Object> resolveParams(Object[] params) {
      List<Object> paramValues = new ArrayList<>();
      KeywordArgs kwargsMap = null;
      for (var param : params) {
        if (param instanceof StarredValue starred) {
          Object value = starred.value();
          if (value == null) {
            throw new IllegalArgumentException("argument after * must be an iterable, not null");
          }
          value = Script.promoteArrayToTuple(value);
          if (value instanceof Iterable<?> iterable) {
            for (Object element : iterable) {
              paramValues.add(element);
            }
          } else {
            throw new IllegalArgumentException(
                "argument after * must be an iterable, not " + value.getClass().getName());
          }
        } else if (param instanceof KeywordArg kwarg) {
          if (kwargsMap == null) {
            kwargsMap = new KeywordArgs();
          }
          if (kwarg.name() == null) {
            var packedKwarg = kwarg.value();
            if (packedKwarg instanceof PyjDict dict) {
              for (var entry : dict.getJavaMap().entrySet()) {
                if (entry.getKey() instanceof String name) {
                  kwargsMap.put(name, entry.getValue());
                } else {
                  throw new IllegalArgumentException(
                      "Keywords must be strings, not %s"
                          .formatted(entry.getKey() == null ? "null" : entry.getKey().getClass()));
                }
              }
            } else {
              throw new IllegalArgumentException(
                  "Argument after ** must be a mapping, not %s"
                      .formatted(packedKwarg == null ? "null" : packedKwarg.getClass()));
            }
          } else {
            kwargsMap.put(kwarg.name(), kwarg.value());
          }
        } else {
          paramValues.add(param);
        }
      }
      if (kwargsMap != null && !kwargsMap.isEmpty()) {
        paramValues.add(kwargsMap);
      }
      return paramValues;
    }

    private Context executeCompiledFunction(
        Context context, BoundFunction function, Object[] params) {
      return executeCompiledFunction(filename, lineno, context, function, params);
    }

    public static Context executeCompiledFunction(
        String filename, int lineno, Context context, BoundFunction function, Object[] params) {
      if (function.isHalted()) {
        ++context.ip;
        return context;
      } else {
        context.enterFunction(filename, lineno);
        var localContext = function.initLocalContext(/* callingContext= */ context, params);
        localContext.code = function.code();
        return localContext;
      }
    }
  }

  record FunctionReturn() implements Instruction {
    @Override
    public Context execute(Context context) {
      context.leaveFunction();
      var returnValue = context.popData();
      var callingContext = context.callingContext();
      callingContext.pushData(returnValue);
      ++callingContext.ip;
      return callingContext;
    }
  }

  record BindFunction(FunctionDef function, Code code) implements Instruction {
    @Override
    public Context execute(Context context) {
      context.setBoundFunction(new BoundFunction(function, context, code));
      ++context.ip;
      return context;
    }
  }

  record Lambda(FunctionDef function, Code code) implements Instruction {
    @Override
    public Context execute(Context context) {
      context.pushData(new BoundFunction(function, context, code));
      ++context.ip;
      return context;
    }
  }

  /** Executes {@code code} as function with no params, using {@code function} for metadata only. */
  record NullaryCompileOnlyFunctionCall(FunctionDef function, Code code) implements Instruction {
    private static Object[] NO_PARAMS = new Object[] {};

    @Override
    public Context execute(Context context) {
      var listCompFunc = new BoundFunction(function, /* enclosingContext= */ context, code);
      return FunctionCall.executeCompiledFunction(
          /* filename= */ "", /* lineno= */ -1, context, listCompFunc, NO_PARAMS);
    }
  }

  record DefineClass(ClassDef classDef, FunctionCompiler compiler) implements Instruction {
    @Override
    public Context execute(Context context) {
      context.set(classDef.identifier().name(), classDef.compile(context, compiler));
      ++context.ip;
      return context;
    }
  }

  record DataclassDefaultInit(Script.DataclassDefaultInit dataclassInit) implements Instruction {
    @Override
    public Context execute(Context context) {
      context.pushData(dataclassInit.create(context));
      ++context.ip;
      return context;
    }
  }

  record Identifier(String name) implements Instruction {
    @Override
    public Context execute(Context context) {
      context.pushData(context.get(name));
      ++context.ip;
      return context;
    }
  }

  record Constant(Object value) implements Instruction {
    @Override
    public Context execute(Context context) {
      context.pushData(value);
      ++context.ip;
      return context;
    }
  }

  record LoadJavaClass(JavaClassCall javaClassCall) implements Instruction {
    @Override
    public Context execute(Context context) {
      context.pushData(javaClassCall.eval(context));
      ++context.ip;
      return context;
    }
  }

  record UnaryOp(Script.UnaryOp.Op op) implements Instruction {
    @Override
    public Context execute(Context context) {
      var operand = context.popData();
      context.pushData(Script.UnaryOp.doOp(context, op, operand));
      ++context.ip;
      return context;
    }
  }

  record BinaryOp(Script.BinaryOp.Op op) implements Instruction {
    @Override
    public Context execute(Context context) {
      var rhs = context.popData();
      var lhs = context.popData();

      BoundFunction function =
          switch (op) {
            case ADD -> FunctionCall.getMethod(lhs, "__add__");
            case SUB -> FunctionCall.getMethod(lhs, "__sub__");
            case MUL -> FunctionCall.getMethod(lhs, "__mul__");
            case DIV -> FunctionCall.getMethod(lhs, "__truediv__");
            case FLOOR_DIV -> FunctionCall.getMethod(lhs, "__floordiv__");
            case POW -> FunctionCall.getMethod(lhs, "__pow__");
            case MOD -> FunctionCall.getMethod(lhs, "__mod__");
            case LSHIFT -> FunctionCall.getMethod(lhs, "__lshift__");
            case RSHIFT -> FunctionCall.getMethod(lhs, "__rshift__");
          };

      if (function != null) {
        // TODO(maxuser): Use an accurate filename and line number.
        var filename = "";
        int lineno = -1;
        return FunctionCall.executeCompiledFunction(
            filename, lineno, context, function, List.of(lhs, rhs).toArray());
      }

      context.pushData(Script.BinaryOp.doOp(context, op, lhs, rhs));
      ++context.ip;
      return context;
    }
  }

  record Comparison(Script.Comparison.Op op) implements Instruction {
    @Override
    public Context execute(Context context) {
      var rhs = context.popData();
      var lhs = context.popData();

      BoundFunction function =
          switch (op) {
            case EQ -> FunctionCall.getMethod(lhs, "__eq__");
            case LT -> FunctionCall.getMethod(lhs, "__lt__");
            case LT_EQ -> FunctionCall.getMethod(lhs, "__le__");
            case GT -> FunctionCall.getMethod(lhs, "__gt__");
            case GT_EQ -> FunctionCall.getMethod(lhs, "__ge__");
            case NOT_EQ -> FunctionCall.getMethod(lhs, "__ne__");
            case IN -> FunctionCall.getMethod(rhs, "__contains__");
            // NOT_IN handled as multiple instructions ("x not in y" compiled as "not (x in y)" in
            // Compiler::compileExpression.
            default -> null;
          };

      if (function != null) {
        // TODO(maxuser): Use an accurate filename and line number.
        var filename = "";
        int lineno = -1;
        List<Object> params = op == Script.Comparison.Op.IN ? List.of(rhs, lhs) : List.of(lhs, rhs);
        return FunctionCall.executeCompiledFunction(
            filename, lineno, context, function, params.toArray());
      }

      context.pushData(Script.Comparison.doOp(context, op, lhs, rhs));
      ++context.ip;
      return context;
    }
  }

  record FieldAccess(Script.FieldAccess expression) implements Instruction {
    @Override
    public Context execute(Context context) {
      var object = context.popData();
      context.pushData(expression.getField(object));
      ++context.ip;
      return context;
    }
  }

  record Star() implements Instruction {
    @Override
    public Context execute(Context context) {
      var value = context.popData();
      context.pushData(new StarredValue(value));
      ++context.ip;
      return context;
    }
  }

  record AssignVariable(String varName) implements Instruction {
    @Override
    public Context execute(Context context) {
      var rhs = context.popData();
      context.set(varName, rhs);
      ++context.ip;
      return context;
    }
  }

  record AssignField(String fieldName) implements Instruction {
    @Override
    public Context execute(Context context) {
      var object = context.popData();
      var rhs = context.popData();
      if (!Assignment.assignField(object, fieldName, rhs)) {
        throw new IllegalArgumentException(
            "Unsupported expression type for lhs of assignment: %s"
                .formatted(getSimpleTypeName(object)));
      }
      ++context.ip;
      return context;
    }
  }

  record AssignTuple(List<Script.Identifier> varNames) implements Instruction {
    @Override
    public Context execute(Context context) {
      var value = context.popData();
      Assignment.assignIdentifierTuple(context, varNames, value);
      ++context.ip;
      return context;
    }
  }

  record IterableIterator() implements Instruction {
    @Override
    public Context execute(Context context) {
      var iter = context.popData();
      context.pushData(Script.getIterable(iter).iterator());
      ++context.ip;
      return context;
    }
  }

  record IteratorHasNext() implements Instruction {
    @Override
    public Context execute(Context context) {
      var iter = context.peekData();
      if (iter instanceof Iterator<?> iterator) {
        context.pushData(iterator.hasNext());
        ++context.ip;
      } else {
        throw new IllegalStateException(
            "Expected iterator on data stack but got: " + getSimpleTypeName(iter));
      }
      return context;
    }
  }

  record IteratorNext() implements Instruction {
    @Override
    public Context execute(Context context) {
      var iter = context.peekData();
      if (iter instanceof Iterator<?> iterator) {
        context.pushData(iterator.next());
        ++context.ip;
      } else {
        throw new IllegalStateException(
            "Expected iterator on data stack but got: " + getSimpleTypeName(iter));
      }
      return context;
    }
  }

  record PopJumpIfFalse(int jumpTarget) implements Instruction {
    @Override
    public Context execute(Context context) {
      var condition = context.popData();
      if (convertToBool(condition)) {
        ++context.ip;
      } else {
        context.ip = jumpTarget;
      }
      return context;
    }
  }

  record JumpIfTrueOrPop(int jumpTarget) implements Instruction {
    @Override
    public Context execute(Context context) {
      var value = context.peekData();
      if (convertToBool(value)) {
        context.ip = jumpTarget;
      } else {
        context.popData();
        ++context.ip;
      }
      return context;
    }
  }

  record JumpIfFalseOrPop(int jumpTarget) implements Instruction {
    @Override
    public Context execute(Context context) {
      var value = context.peekData();
      if (convertToBool(value)) {
        context.popData();
        ++context.ip;
      } else {
        context.ip = jumpTarget;
      }
      return context;
    }
  }

  record Jump(int jumpTarget) implements Instruction {
    @Override
    public Context execute(Context context) {
      context.ip = jumpTarget;
      return context;
    }
  }

  record CatchExceptionType(Optional<String> variableName) implements Instruction {
    @Override
    public Context execute(Context context) {
      if (context.exception == null) {
        throw new IllegalStateException(
            "Trying to execute 'except' clause with no active exception in this calling context");
      }
      var exceptionTypeSpec = context.popData();
      if (TryBlock.matchesExceptionSpec(
          exceptionTypeSpec, context.exception, /* allowTuple= */ true)) {
        variableName.ifPresent(e -> context.set(e, context.exception));
        context.exception = null;
      } else {
        throw context.exception;
      }
      ++context.ip;
      return context;
    }
  }

  /** Swallows the active exception if there is one. */
  record SwallowException() implements Instruction {
    @Override
    public Context execute(Context context) {
      if (context.exception != null) {
        context.exception = null;
      }
      ++context.ip;
      return context;
    }
  }

  /** Rethrows the active exception if there is one. */
  record RethrowException() implements Instruction {
    @Override
    public Context execute(Context context) {
      if (context.exception != null) {
        throw context.exception;
      }
      ++context.ip;
      return context;
    }
  }

  record PopData() implements Instruction {
    @Override
    public Context execute(Context context) {
      context.popData();
      ++context.ip;
      return context;
    }
  }

  record LoadTuple(int numElements) implements Instruction {
    @Override
    public Context execute(Context context) {
      var list = loadSequence(context, numElements);
      context.pushData(new PyjTuple(list.toArray()));
      ++context.ip;
      return context;
    }
  }

  record LoadList(int numElements) implements Instruction {
    @Override
    public Context execute(Context context) {
      var list = loadSequence(context, numElements);
      context.pushData(new PyjList(list));
      ++context.ip;
      return context;
    }
  }

  record LoadSet(int numElements) implements Instruction {
    @Override
    public Context execute(Context context) {
      var list = loadSequence(context, numElements);
      context.pushData(new PyjSet(Set.copyOf(list)));
      ++context.ip;
      return context;
    }
  }

  // Note that numElements does not reflect the number of elements of starred expressions, so
  // (a, *b, c) counts as 3 elements.
  private static List<Object> loadSequence(Context context, int numElements) {
    var list = new LinkedList<Object>();
    for (int i = 0; i < numElements; ++i) {
      var element = context.popData();
      if (element instanceof StarredValue starred) {
        var values = new ArrayList<Object>();
        Script.getIterable(starred.value()).forEach(values::add);
        for (var value : values.reversed()) {
          list.addFirst(value);
        }
      } else {
        list.addFirst(element);
      }
    }
    return list;
  }

  record AppendListAtOffset(int offset) implements Instruction {
    @Override
    public Context execute(Context context) {
      var list = (PyjList) context.getData(offset);
      list.append(context.popData());

      ++context.ip;
      return context;
    }
  }
}
