// SPDX-FileCopyrightText: © 2025-2026 Greg Christiana <maxuser@pyjinn.org>
// SPDX-License-Identifier: MIT

package org.pyjinn.interpreter;

import static org.pyjinn.interpreter.Script.convertToBool;
import static org.pyjinn.interpreter.Script.getSimpleTypeName;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import org.pyjinn.interpreter.Script.*;

sealed interface Instruction {
  Context execute(Context context) throws RuntimeException;

  /**
   * Offset of stack depth from before this instruction to after it executes.
   *
   * <p>-1 for each value popped from the context's data stack. +1 for each value pushed onto the
   * context's data stack.
   */
  default int stackOffset() {
    return 0;
  }

  sealed interface JumpPlaceholder extends Instruction {
    @Override
    default Context execute(Context context) {
      throw new UnsupportedOperationException("Placeholder instruction cannot be executed");
    }

    Instruction createJumpTo(int jumpTarget);
  }

  record PushData(Object value) implements Instruction {
    @Override
    public Context execute(Context context) {
      context.pushData(value);
      ++context.ip;
      return context;
    }

    @Override
    public int stackOffset() {
      return 1;
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

    @Override
    public int stackOffset() {
      return 1;
    }
  }

  record StarredValue(Object value) {}

  record KeywordArg(String name, Object value) {}

  record CreateKeywordArg(String name) implements Instruction {
    @Override
    public Context execute(Context context) {
      context.pushData(new KeywordArg(name, context.popData()));
      ++context.ip;
      return context;
    }
  }

  record FunctionCall(String filename, int lineno, int numArgs) implements Instruction {
    @Override
    public Context execute(Context context) {
      Object[] params = new Object[numArgs];
      for (int i = 0; i < numArgs; ++i) {
        Object value = context.popData();
        params[i] = value;
      }
      var function = context.popData();
      return call(filename, lineno, context, function, params);
    }

    @Override
    public int stackOffset() {
      return -numArgs; // -1 for caller and +1 for return value cancel out.
    }

    public static Context debugCall(Context context, Object caller, Object[] params) {
      return call(/* filename= */ "", /* lineno= */ -1, context, caller, params);
    }

    private static Context call(
        String filename, int lineno, Context context, Object caller, Object[] params) {
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
      // TODO(maxuser): Implement LenFunction as IncrementalFunction to avoid this special case.
      if (caller == LenFunction.INSTANCE && paramValues.size() == 1) {
        var function = getMethod(paramValues.get(0), "__len__");
        if (function != null) {
          return executeCompiledFunction(
              filename, lineno, context, function, paramValues.toArray());
        }
      }

      // Special handling for next(generator).
      // TODO(maxuser): Implement NextFunction as IncrementalFunction to avoid this special case.
      if (caller == NextFunction.INSTANCE
          && paramValues.size() == 1
          && paramValues.get(0) instanceof Generator generator) {
        return generator.startNext(context, filename, lineno);
      }

      // Translate x(...) to x.__call__(...).
      var callMethod = getMethod(caller, "__call__");
      if (callMethod != null) {
        var methodParams = new ArrayList<Object>(paramValues.size() + 1);
        methodParams.add(caller);
        methodParams.addAll(paramValues);
        return executeCompiledFunction(
            filename, lineno, context, callMethod, methodParams.toArray());
      }

      // Effective caller may be a function that's being delegated to.
      var effectiveCaller = caller;

      // Specialize handling of CtorFunction so instructions can be interrupted.
      if (effectiveCaller instanceof Script.PyjClass pyjClass) {
        if (pyjClass.ctor instanceof CtorFunction ctor) {
          var ctorParams = new ArrayList<Object>(paramValues.size() + 1);
          ctorParams.add(new PyjObject(pyjClass));
          ctorParams.addAll(paramValues);
          return executeCompiledFunction(
              filename, lineno, context, ctor.function(), ctorParams.toArray());
        }
        if (pyjClass.ctor instanceof BoundFunction func) {
          return executeCompiledFunction(filename, lineno, context, func, paramValues.toArray());
        }
      }

      // Specialize handling of BoundMethod so instructions can be interrupted.
      if (effectiveCaller instanceof Script.BoundMethod binding) {
        if (binding.object() instanceof PyjObject pyjObject) {
          var method = pyjObject.__class__.instanceMethods.get(binding.methodName());
          if (method != null && method instanceof BoundFunction function) {
            var methodParams = new ArrayList<Object>(paramValues.size() + 1);
            methodParams.add(pyjObject);
            methodParams.addAll(paramValues);
            return executeCompiledFunction(
                filename, lineno, context, function, methodParams.toArray());
          }

          // If PyjObject's field is assigned to a function, make that function the effective
          // caller.
          var field = pyjObject.__dict__.get(binding.methodName());
          if (field != null && field instanceof Function function) {
            effectiveCaller = function;
          }
        } else if (binding.object() instanceof Sendable sendable
            && binding.methodName().equals("send")) {
          // TODO(maxuser): Support Sendable.send() as an IncrementalFunction for the following:
          //   s = g.send
          //   s(99)
          if (paramValues.size() != 1) {
            throw new IllegalArgumentException(
                "send() method expects one argument but got " + paramValues.size());
          }
          return sendable.startSend(paramValues.get(0), context, filename, lineno);
        }
      }

      // Specialize handling of BoundFunction so instructions can be interrupted.
      if (effectiveCaller instanceof BoundFunction function) {
        return executeCompiledFunction(filename, lineno, context, function, paramValues.toArray());
      }

      if (effectiveCaller instanceof Script.PyjClass pyjClass) {
        effectiveCaller = pyjClass.ctor;
      }

      if (effectiveCaller instanceof IncrementalFunction function) {
        return function.enter(context, paramValues.toArray());
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
      if (object instanceof PyjObject pyjObject && pyjObject.__class__ != null) {
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

    // TODO(maxuser): Convert uses of this method to executeCompiledFunction() with an accurate
    // filename and lineno.
    @Deprecated
    public static Context executeCompiledFunctionUnknownSource(
        Context context, BoundFunction function, Object[] params) {
      return executeCompiledFunction(
          /* filename= */ "", /* lineno= */ -1, context, function, params);
    }

    public static Context executeCompiledFunction(
        String filename, int lineno, Context context, BoundFunction function, Object[] params) {
      if (function.isHalted()) {
        ++context.ip;
        return context;
      } else if (function.functionDef().isAsync()) {
        var localContext = function.initLocalContext(context, params, function.isCtor());
        // Set IP to -1 to indicate that it's in its initial state that cannot be jumped back to.
        localContext.ip = -1;
        context.pushData(new Coroutine(localContext));
        ++context.ip;
        return context;
      } else if (function.functionDef().hasYieldExpression()) {
        // Treat functions with yield expressions as generators.
        var localContext =
            function.initLocalContext(/* callingContext= */ context, params, function.isCtor());
        // Set IP to -1 to indicate that it's in its initial state that cannot be jumped back to.
        localContext.ip = -1;
        context.pushData(new Generator(localContext));
        ++context.ip;
        return context;
      } else {
        context.enterFunction(filename, lineno);
        var localContext =
            function.initLocalContext(/* callingContext= */ context, params, function.isCtor());
        return localContext;
      }
    }
  }

  record FunctionReturn() implements Instruction {
    @Override
    public Context execute(Context context) {
      return returnToCallingContext(context);
    }

    private static Context returnToCallingContext(Context context) {
      context.leaveFunction();
      var returnValue = context.checkCtorResult(context.popData());
      context.ip = context.code.instructions().size(); // Put IP past end of function for safety.
      if (context.isGenerator()) {
        throw returnValue == null ? StopIteration.DEFAULT : new StopIteration(returnValue);
      }
      var callingContext = context.callingContext();
      callingContext.pushData(returnValue);
      ++callingContext.ip;
      return callingContext;
    }

    @Override
    public int stackOffset() {
      return 1;
    }
  }

  record GetAwaitable() implements Instruction {
    @Override
    public Context execute(Context context) {
      var object = context.peekData();
      if (!(object instanceof Sendable)) {
        // TODO(maxuser): Check for object.__await__() method.
        throw new IllegalArgumentException("object is not awaitable: " + getSimpleTypeName(object));
      }
      ++context.ip;
      return context;
    }
  }

  record Yield() implements Instruction {
    @Override
    public Context execute(Context context) {
      context.leaveFunction();
      var yieldValue = context.popData();
      ++context.ip;
      var callingContext = context.callingContext();
      callingContext.pushData(yieldValue);
      ++callingContext.ip;
      return callingContext;
    }

    @Override
    public int stackOffset() {
      return -1;
    }
  }

  /** Instruction for setting a provisional return value in try block followed by finally clause. */
  record ProvisionalReturn() implements Instruction {
    @Override
    public Context execute(Context context) {
      context.returnWithValue(context.popData());
      ++context.ip;
      return context;
    }

    @Override
    public int stackOffset() {
      return 1;
    }
  }

  record RaiseException() implements Instruction {
    @Override
    public Context execute(Context context) {
      var exception = context.popData();
      throw new PyjException(exception);
    }

    @Override
    public int stackOffset() {
      return -1;
    }
  }

  record CreateFunction(FunctionDef functionDef, Code code) implements Instruction {
    @Override
    public Context execute(Context context) {
      var defaults = new ArrayList<Object>();
      for (int i = 0; i < functionDef.defaults().size(); ++i) {
        defaults.add(context.popData());
      }
      var keywordDefaults = new ArrayList<Object>();
      for (int i = 0; i < functionDef.keywordDefaults().size(); ++i) {
        keywordDefaults.add(context.popData());
      }
      context.pushData(new BoundFunction(functionDef, context, defaults, keywordDefaults, code));
      ++context.ip;
      return context;
    }

    @Override
    public int stackOffset() {
      return 1 - functionDef.defaults().size() - functionDef.keywordDefaults().size();
    }
  }

  record WalrusOperator(String varName) implements Instruction {
    @Override
    public Context execute(Context context) {
      var value = context.popData();
      context.set(varName, value);
      context.pushData(value);
      ++context.ip;
      return context;
    }
  }

  /** Executes {@code code} as function with no params, using {@code function} for metadata only. */
  record NullaryCompileOnlyFunctionCall(FunctionDef function, Code code) implements Instruction {
    private static Object[] NO_PARAMS = new Object[] {};

    @Override
    public Context execute(Context context) {
      var listCompFunc =
          new BoundFunction(
              function,
              /* enclosingContext= */ context,
              /* defaults= */ List.of(),
              /* keywordDefaults= */ List.of(),
              code);
      return FunctionCall.executeCompiledFunctionUnknownSource(context, listCompFunc, NO_PARAMS);
    }

    @Override
    public int stackOffset() {
      return 1; // +1 for return value from synthetic function call
    }
  }

  record DataclassDefaultCtor(FunctionDef functionDef, Code code) {}

  record CompiledClass(
      ClassDef classDef,
      List<Code> compiledMethods,
      Optional<ClassDef.DataclassCtorFactory> dataclassCtorFactory) {}

  record DefineClass(
      CompiledClass compiledClass, java.util.function.Function<FunctionDef, Code> functionCompiler)
      implements Instruction {
    @Override
    public Context execute(Context context) {
      var classDef = compiledClass.classDef();
      var type = new PyjClassContainer(classDef.identifier().name());
      context.set(
          classDef.identifier().name(),
          classDef.create(
              context,
              type,
              compiledClass.compiledMethods(),
              compiledClass
                  .dataclassCtorFactory()
                  .map(
                      factory -> {
                        var func = factory.createFunctionDef(type);
                        return new DataclassDefaultCtor(func, functionCompiler.apply(func));
                      })));
      ++context.ip;
      return context;
    }

    @Override
    public int stackOffset() {
      var classDef = compiledClass.classDef();
      int offset =
          -classDef.methodDefs().stream()
              .map(m -> m.defaults().size() + m.keywordDefaults().size())
              .mapToInt(Integer::intValue)
              .sum();

      if (classDef.getDataclassDecorator().isEmpty()
          || classDef.methodDefs().stream()
              .noneMatch(methodDef -> methodDef.identifier().name().equals("__init__"))) {
        offset -=
            (int) classDef.fields().stream().filter(f -> f.defaultValue().isPresent()).count();
      }
      return offset;
    }
  }

  record DataclassDefaultInit(Script.DataclassDefaultInit dataclassInit) implements Instruction {
    @Override
    public Context execute(Context context) {
      context.pushData(dataclassInit.create(context));
      ++context.ip;
      return context;
    }

    @Override
    public int stackOffset() {
      return 1;
    }
  }

  record LoadFromLocalVariableByIndex(int varIndex) implements Instruction {
    @Override
    public Context execute(Context context) {
      context.pushData(context.getLocalVarByIndex(varIndex));
      ++context.ip;
      return context;
    }

    @Override
    public int stackOffset() {
      return 1;
    }
  }

  record LoadFromVariableByName(String name) implements Instruction {
    @Override
    public Context execute(Context context) {
      context.pushData(context.get(name));
      ++context.ip;
      return context;
    }

    @Override
    public int stackOffset() {
      return 1;
    }
  }

  record Constant(Object value) implements Instruction {
    @Override
    public Context execute(Context context) {
      context.pushData(value);
      ++context.ip;
      return context;
    }

    @Override
    public int stackOffset() {
      return 1;
    }
  }

  record LoadJavaClass(JavaClassCall javaClassCall) implements Instruction {
    @Override
    public Context execute(Context context) {
      context.pushData(javaClassCall.loadClass(context));
      ++context.ip;
      return context;
    }

    @Override
    public int stackOffset() {
      return 1;
    }
  }

  record ContextIdentifier() implements Instruction {
    @Override
    public Context execute(Context context) {
      context.pushData(context);
      ++context.ip;
      return context;
    }

    @Override
    public int stackOffset() {
      return 1;
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
        return FunctionCall.executeCompiledFunctionUnknownSource(
            context, function, List.of(lhs, rhs).toArray());
      }

      context.pushData(Script.BinaryOp.doOp(context, op, lhs, rhs));
      ++context.ip;
      return context;
    }

    @Override
    public int stackOffset() {
      return -2 + 1;
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
        List<Object> params = op == Script.Comparison.Op.IN ? List.of(rhs, lhs) : List.of(lhs, rhs);
        return FunctionCall.executeCompiledFunctionUnknownSource(
            context, function, params.toArray());
      }

      context.pushData(Script.Comparison.doOp(context, op, lhs, rhs));
      ++context.ip;
      return context;
    }

    @Override
    public int stackOffset() {
      return -2 + 1;
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

  record ArrayIndex() implements Instruction {
    @Override
    public Context execute(Context context) {
      var index = context.popData();
      var array = context.popData();

      var method = FunctionCall.getMethod(array, "__getitem__");
      if (method != null) {
        return FunctionCall.executeCompiledFunctionUnknownSource(
            context, method, List.of(array, index).toArray());
      }

      context.pushData(Script.ArrayIndex.getItem(array, index));
      ++context.ip;
      return context;
    }

    @Override
    public int stackOffset() {
      return -2 + 1;
    }
  }

  record Slice() implements Instruction {
    @Override
    public Context execute(Context context) {
      var step = context.popData();
      var stop = context.popData();
      var start = context.popData();
      context.pushData(
          new Script.Slice(
              start == null ? null : (Integer) start,
              stop == null ? null : (Integer) stop,
              step == null ? null : (Integer) step));
      ++context.ip;
      return context;
    }

    @Override
    public int stackOffset() {
      return -3 + 1;
    }
  }

  record CreateDict(int numItems) implements Instruction {
    @Override
    public Context execute(Context context) {
      var map = new HashMap<Object, Object>();
      for (int i = 0; i < numItems; ++i) {
        var value = context.popData();
        var key = context.popData();
        map.put(key, value);
      }
      context.pushData(new PyjDict(map));
      ++context.ip;
      return context;
    }

    @Override
    public int stackOffset() {
      return -2 * numItems + 1;
    }
  }

  record DeleteVariable(String varName) implements Instruction {
    @Override
    public Context execute(Context context) {
      context.del(varName);
      ++context.ip;
      return context;
    }
  }

  record DeleteArrayIndex() implements Instruction {
    @Override
    public Context execute(Context context) {
      var index = context.popData();
      var array = context.popData();
      if (array instanceof ItemDeleter deleter) {
        deleter.__delitem__(index);
      } else if (array instanceof List list) {
        PyjList.deleteItem(list, index);
      } else if (array instanceof Map map) {
        map.remove(index);
      } else {
        throw new IllegalArgumentException(
            "Object does not support subscript deletion: " + array.getClass().getName());
      }
      ++context.ip;
      return context;
    }

    @Override
    public int stackOffset() {
      return -2;
    }
  }

  record Nop() implements Instruction {
    @Override
    public Context execute(Context context) {
      // Do nothing but advance the instruction pointer.
      ++context.ip;
      return context;
    }
  }

  record FormattedString(int numValues) implements Instruction {
    @Override
    public Context execute(Context context) {
      var values = new ArrayList<String>(numValues);
      for (int i = 0; i < numValues; ++i) {
        values.add(PyjObjects.toString(context.popData()));
      }
      var str = new StringBuilder();
      for (int i = numValues - 1; i >= 0; --i) {
        str.append(values.get(i));
      }
      context.pushData(str.toString());
      ++context.ip;
      return context;
    }

    @Override
    public int stackOffset() {
      return -numValues + 1;
    }
  }

  record FormattedValue(Optional<String> format) implements Instruction {
    @Override
    public Context execute(Context context) {
      var value = context.popData();
      var str = Script.FormattedValue.format(value, format);
      context.pushData(str);
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

  record StoreToLocalVariableByIndex(int varIndex) implements Instruction {
    @Override
    public Context execute(Context context) {
      var rhs = context.popData();
      context.setLocalVarByIndex(varIndex, rhs);
      ++context.ip;
      return context;
    }

    @Override
    public int stackOffset() {
      return -1;
    }
  }

  record StoreToVariableByName(String varName) implements Instruction {
    @Override
    public Context execute(Context context) {
      var rhs = context.popData();
      context.set(varName, rhs);
      ++context.ip;
      return context;
    }

    @Override
    public int stackOffset() {
      return -1;
    }
  }

  record StoreToField(String fieldName) implements Instruction {
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

    @Override
    public int stackOffset() {
      return -2;
    }
  }

  record StoreToArrayIndex() implements Instruction {
    @Override
    public Context execute(Context context) {
      var index = context.popData();
      var array = context.popData();
      var rhs = context.popData();

      var method = FunctionCall.getMethod(array, "__setitem__");
      if (method != null) {
        return FunctionCall.executeCompiledFunctionUnknownSource(
            context, method, List.of(array, index, rhs).toArray());
      }

      Assignment.assignArray(array, index, rhs);

      // Push an empty value for consistency with __setitem__() which returns a value on the stack.
      context.pushData(null);

      ++context.ip;
      return context;
    }

    @Override
    public int stackOffset() {
      return -3 + 1;
    }
  }

  record StoreToVariableTupleByNames(List<Identifier> varNames) implements Instruction {
    @Override
    public Context execute(Context context) {
      var value = context.popData();
      Assignment.assignIdentifierTuple(context, varNames, value);
      ++context.ip;
      return context;
    }

    @Override
    public int stackOffset() {
      return -1;
    }
  }

  record StoreToLocalVariableTupleByIndices(int[] varIndices) implements Instruction {
    @Override
    public Context execute(Context context) {
      var value = context.popData();
      Assignment.assignIdentifierTupleByIndices(context, varIndices, value);
      ++context.ip;
      return context;
    }

    @Override
    public int stackOffset() {
      return -1;
    }
  }

  record AugmentVariableByName(String varName, AugmentedAssignment.Op op) implements Instruction {
    @Override
    public Context execute(Context context) {
      var rhs = context.popData();
      AugmentedAssignment.augmentVariableByName(context, varName, op, rhs);
      ++context.ip;
      return context;
    }

    @Override
    public int stackOffset() {
      return -1;
    }
  }

  record AugmentVariableByIndex(int varIndex, AugmentedAssignment.Op op) implements Instruction {
    @Override
    public Context execute(Context context) {
      var rhs = context.popData();
      AugmentedAssignment.augmentVariableByIndex(context, varIndex, op, rhs);
      ++context.ip;
      return context;
    }

    @Override
    public int stackOffset() {
      return -1;
    }
  }

  record AugmentField(String fieldName, AugmentedAssignment.Op op) implements Instruction {
    @Override
    public Context execute(Context context) {
      var object = context.popData();
      var rhs = context.popData();
      AugmentedAssignment.augmentField(object, fieldName, op, rhs);
      ++context.ip;
      return context;
    }

    @Override
    public int stackOffset() {
      return -2;
    }
  }

  record AugmentArrayIndex(AugmentedAssignment.Op op) implements Instruction {
    @Override
    public Context execute(Context context) {
      var index = context.popData();
      var array = context.popData();
      var rhs = context.popData();
      AugmentedAssignment.augmentArrayIndex(array, index, op, rhs);
      ++context.ip;
      return context;
    }

    @Override
    public int stackOffset() {
      return -3;
    }
  }

  record IterableIterator() implements Instruction {
    @Override
    public Context execute(Context context) {
      var iter = context.popData();
      if (iter instanceof Generator generator) {
        context.pushData(generator);
      } else {
        context.pushData(Script.getIterable(iter).iterator());
      }
      ++context.ip;
      return context;
    }
  }

  /**
   * Peeks the data stack, casts it to Iterator or Generator, and pushes the next element wrapped in
   * an Optional.
   */
  record IteratorNext() implements Instruction {
    @Override
    public Context execute(Context context) {
      var iter = context.peekData();
      if (iter instanceof Iterator<?> iterator) {
        if (iterator.hasNext()) {
          context.pushData(iterator.next());
        } else {
          context.pushData(StopIteration.DEFAULT);
        }
        ++context.ip;
        return context;
      } else if (iter instanceof Generator generator) {
        return generator.startNext(context);
      } else {
        throw new IllegalStateException(
            "Expected iterator or generator on data stack but got: " + getSimpleTypeName(iter));
      }
    }

    @Override
    public int stackOffset() {
      return 1;
    }
  }

  record IfStopIterationThenPopAndJump(int jumpTarget) implements Instruction {
    // This instruction conditionally pops the data stack, but the reported stack offset is 0
    // because the non-jump case is the one that matters for stack-offset computations when handling
    // exceptions.
    public static final int STACK_OFFSET = 0;

    record Placeholder() implements JumpPlaceholder {
      @Override
      public Instruction createJumpTo(int jumpTarget) {
        return new IfStopIterationThenPopAndJump(jumpTarget);
      }

      @Override
      public int stackOffset() {
        return STACK_OFFSET;
      }
    }

    @Override
    public Context execute(Context context) {
      var next = context.peekData();
      if (next instanceof StopIteration) {
        context.popData();
        context.ip = jumpTarget;
      } else {
        ++context.ip;
      }
      return context;
    }

    @Override
    public int stackOffset() {
      return STACK_OFFSET;
    }
  }

  record GeneratorSend() implements Instruction {
    @Override
    public Context execute(Context context) {
      var valueToSendDown = context.popData();
      var iter = context.peekData();
      if (iter instanceof Iterator<?> iterator) {
        // valueToSendDown is ignored for iterators.
        if (iterator.hasNext()) {
          context.pushData(iterator.next());
        } else {
          context.pushData(StopIteration.DEFAULT);
        }
        ++context.ip;
        return context;
      } else if (iter instanceof Sendable sendable) {
        // When send() is completed in the sub-sendable (Coroutine or Generator), the sub-sendable
        // pushes a value onto the super-sendable's data stack.
        return sendable.startSend(valueToSendDown, context);
      } else {
        throw new IllegalStateException(
            "Expected generator on data stack but got: " + getSimpleTypeName(iter));
      }
    }
  }

  record IfPeekStopIterationThenJump(int jumpTarget) implements Instruction {
    record Placeholder() implements JumpPlaceholder {
      @Override
      public Instruction createJumpTo(int jumpTarget) {
        return new IfPeekStopIterationThenJump(jumpTarget);
      }
    }

    @Override
    public Context execute(Context context) {
      var data = context.peekData();
      if (data instanceof StopIteration) {
        context.ip = jumpTarget;
      } else {
        ++context.ip;
      }
      return context;
    }
  }

  record FinishYieldFrom() implements Instruction {
    @Override
    public Context execute(Context context) {
      var stopIteration = (StopIteration) context.popData();
      context.popData();
      context.pushData(stopIteration.value);
      ++context.ip;
      return context;
    }
  }

  record PopJumpIfFalse(int jumpTarget) implements Instruction {
    public static final int STACK_OFFSET = -1;

    record Placeholder() implements JumpPlaceholder {
      @Override
      public Instruction createJumpTo(int jumpTarget) {
        return new PopJumpIfFalse(jumpTarget);
      }

      @Override
      public int stackOffset() {
        return STACK_OFFSET;
      }
    }

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

    @Override
    public int stackOffset() {
      return STACK_OFFSET;
    }
  }

  record JumpIfTrueOrPop(int jumpTarget) implements Instruction {
    public static final int STACK_OFFSET = -1;

    record Placeholder() implements JumpPlaceholder {
      @Override
      public Instruction createJumpTo(int jumpTarget) {
        return new JumpIfTrueOrPop(jumpTarget);
      }

      @Override
      public int stackOffset() {
        return STACK_OFFSET;
      }
    }

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

    @Override
    public int stackOffset() {
      return STACK_OFFSET;
    }
  }

  record JumpIfFalseOrPop(int jumpTarget) implements Instruction {
    public static final int STACK_OFFSET = -1;

    record Placeholder() implements JumpPlaceholder {
      @Override
      public Instruction createJumpTo(int jumpTarget) {
        return new JumpIfFalseOrPop(jumpTarget);
      }

      @Override
      public int stackOffset() {
        return STACK_OFFSET;
      }
    }

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

    @Override
    public int stackOffset() {
      return STACK_OFFSET;
    }
  }

  record Jump(int jumpTarget) implements Instruction {
    public static final int STACK_OFFSET = 0;

    record Placeholder() implements JumpPlaceholder {
      @Override
      public Instruction createJumpTo(int jumpTarget) {
        return new Jump(jumpTarget);
      }

      @Override
      public int stackOffset() {
        return STACK_OFFSET;
      }
    }

    @Override
    public Context execute(Context context) {
      context.ip = jumpTarget;
      return context;
    }

    @Override
    public int stackOffset() {
      return STACK_OFFSET;
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
      var unwrappedException = PyjException.unwrap(context.exception);
      if (TryBlock.matchesExceptionSpec(
          exceptionTypeSpec, unwrappedException, /* allowTuple= */ true)) {
        variableName.ifPresent(e -> context.set(e, unwrappedException));
        context.exception = null;
      } else {
        if (unwrappedException instanceof RuntimeException runtimeException) {
          throw runtimeException;
        } else {
          throw context.exception;
        }
      }
      ++context.ip;
      return context;
    }

    @Override
    public int stackOffset() {
      return -1;
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

  /** Rethrows the active exception or returns the provisional return value, if they exist. */
  record ExitFinallyBlock() implements Instruction {
    @Override
    public Context execute(Context context) {
      if (context.exception != null) {
        throw context.exception;
      } else if (context.hasReturnValue()) {
        context.pushData(context.returnValue());
        return FunctionReturn.returnToCallingContext(context);
      } else {
        ++context.ip;
        return context;
      }
    }
  }

  record PopData() implements Instruction {
    @Override
    public Context execute(Context context) {
      context.popData();
      ++context.ip;
      return context;
    }

    @Override
    public int stackOffset() {
      return -1;
    }
  }

  record GlobalExpressionHandler(Consumer<Object> handler) implements Instruction {
    @Override
    public Context execute(Context context) {
      var data = context.popData();
      if (context instanceof GlobalContext) {
        handler.accept(data);
      }
      ++context.ip;
      return context;
    }

    @Override
    public int stackOffset() {
      return -1;
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

    @Override
    public int stackOffset() {
      return -numElements + 1;
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

    @Override
    public int stackOffset() {
      return -numElements + 1;
    }
  }

  record LoadSetFromElements(int numElements) implements Instruction {
    @Override
    public Context execute(Context context) {
      var list = loadSequence(context, numElements);
      context.pushData(new PyjSet(Set.copyOf(list)));
      ++context.ip;
      return context;
    }

    @Override
    public int stackOffset() {
      return -numElements + 1;
    }
  }

  record LoadSetFromList() implements Instruction {
    @Override
    public Context execute(Context context) {
      var data = context.popData();
      List<?> list = data instanceof PyjList pyjList ? pyjList.getJavaList() : (List<?>) data;
      context.pushData(new PyjSet(Set.copyOf(list)));
      ++context.ip;
      return context;
    }
  }

  // Note that numElements does not reflect the number of elements of starred expressions, so
  // (a, *b, c) counts as 3 elements.
  private static List<Object> loadSequence(Context context, int numElements) {
    var list = new ArrayList<Object>();
    for (int i = 0; i < numElements; ++i) {
      var element = context.popData();
      if (element instanceof StarredValue starred) {
        var values = new ArrayList<Object>();
        Script.getIterable(starred.value()).forEach(values::add);
        for (var value : values.reversed()) {
          list.add(value);
        }
      } else {
        list.add(element);
      }
    }
    Collections.reverse(list);
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

    @Override
    public int stackOffset() {
      return -1;
    }
  }

  record Import(Script.Import importStatement) implements Instruction {
    @Override
    public Context execute(Context context) {
      importStatement.exec(context);
      ++context.ip;
      return context;
    }
  }

  record ImportFrom(Script.ImportFrom importFromStatement) implements Instruction {
    @Override
    public Context execute(Context context) {
      importFromStatement.exec(context);
      ++context.ip;
      return context;
    }
  }
}
