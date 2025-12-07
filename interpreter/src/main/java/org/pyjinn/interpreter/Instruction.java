// SPDX-FileCopyrightText: © 2025 Greg Christiana <maxuser@pyjinn.org>
// SPDX-License-Identifier: MIT

package org.pyjinn.interpreter;

import java.util.ArrayList;
import java.util.List;
import org.pyjinn.interpreter.Script.*;

sealed interface Instruction {
  Context execute(Context context);

  record PushData(Object value) implements Instruction {
    @Override
    public Context execute(Context context) {
      context.dataStack.push(value);
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
        Object value = context.dataStack.pop();
        if (value instanceof Script.KeywordArg arg) {
          params[i] = new KeywordArg(arg.name(), arg.value().eval(context));
        } else if (value instanceof StarredExpression expr) {
          params[i] = new StarredValue(expr.value().eval(context));
        } else {
          params[i] = value;
        }
      }
      var function = context.dataStack.pop();
      return call(context, function, params);
    }

    private Context call(Context context, Object caller, Object[] params) {
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

      if (caller instanceof Class<?> type) {
        Function function;
        if ((function = InterfaceProxy.getFunctionPassedToInterface(type, paramValues.toArray()))
            != null) {
          context.dataStack.push(
              InterfaceProxy.promoteFunctionToJavaInterface(context.env(), type, function));
          ++context.ip;
          return context;
        }
      }

      // Specialize handling of BoundFunction so instructions can be interrupted.
      if (caller instanceof BoundFunction binding) {
        if (binding.isHalted()) {
          ++context.ip;
          return context;
        } else {
          context.enterFunction(filename, lineno);
          var localContext = binding.initLocalContext(context.env(), paramValues.toArray());
          localContext.dataStack.push(context);
          localContext.instructions = binding.instructions();
          return localContext;
        }
      }

      if (caller instanceof Function function) {
        try {
          context.enterFunction(filename, lineno);
          context.dataStack.push(nullable(function.call(context.env(), paramValues.toArray())));
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
  }

  record FunctionReturn() implements Instruction {
    @Override
    public Context execute(Context context) {
      context.leaveFunction();
      var callingContext = (Context) context.dataStack.pop();
      Object returnValue = null; // TODO(maxuser)! supply return value
      callingContext.dataStack.push(nullable(returnValue));
      ++callingContext.ip;
      return callingContext;
    }
  }

  static class NoneType {}

  static final NoneType NONE = new NoneType();

  private static Object nullable(Object value) {
    return value == null ? NONE : value;
  }

  record BindFunction(FunctionDef function, List<Instruction> instructions) implements Instruction {
    @Override
    public Context execute(Context context) {
      context.setBoundFunction(new BoundFunction(function, context, instructions));
      ++context.ip;
      return context;
    }
  }

  record Identifier(String name) implements Instruction {
    @Override
    public Context execute(Context context) {
      context.dataStack.push(context.get(name));
      ++context.ip;
      return context;
    }
  }

  record Constant(Object value) implements Instruction {
    @Override
    public Context execute(Context context) {
      context.dataStack.push(value);
      ++context.ip;
      return context;
    }
  }

  record BinaryOp(Script.BinaryOp.Op op) implements Instruction {
    @Override
    public Context execute(Context context) {
      var rhs = context.dataStack.pop();
      var lhs = context.dataStack.pop();
      context.dataStack.push(Script.BinaryOp.doOp(context, op, lhs, rhs));
      ++context.ip;
      return context;
    }
  }

  record Star() implements Instruction {
    @Override
    public Context execute(Context context) {
      var value = context.dataStack.pop();
      context.dataStack.push(new StarredValue(value));
      ++context.ip;
      return context;
    }
  }

  record AssignVariable(String varName) implements Instruction {
    @Override
    public Context execute(Context context) {
      var value = context.dataStack.pop();
      context.set(varName, value);
      ++context.ip;
      return context;
    }
  }

  record Pass() implements Instruction {
    @Override
    public Context execute(Context context) {
      ++context.ip;
      return context;
    }
  }

  record PopData() implements Instruction {
    @Override
    public Context execute(Context context) {
      context.dataStack.pop();
      ++context.ip;
      return context;
    }
  }
}
