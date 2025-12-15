// SPDX-FileCopyrightText: © 2025 Greg Christiana <maxuser@pyjinn.org>
// SPDX-License-Identifier: MIT

package org.pyjinn.interpreter;

import static org.pyjinn.interpreter.Script.convertToBool;
import static org.pyjinn.interpreter.Script.getSimpleTypeName;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import org.pyjinn.interpreter.Script.*;

sealed interface Instruction {
  Context execute(Context context);

  record PushData(Object value) implements Instruction {
    @Override
    public Context execute(Context context) {
      context.pushData(value);
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
          context.pushData(
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
          localContext.pushData(context);
          localContext.instructions = binding.instructions();
          return localContext;
        }
      }

      if (caller instanceof Function function) {
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
  }

  record FunctionReturn() implements Instruction {
    @Override
    public Context execute(Context context) {
      context.leaveFunction();
      var returnValue = context.popData();
      var callingContext = (Context) context.popData();
      callingContext.pushData(returnValue);
      ++callingContext.ip;
      return callingContext;
    }
  }

  record BindFunction(FunctionDef function, List<Instruction> instructions) implements Instruction {
    @Override
    public Context execute(Context context) {
      context.setBoundFunction(new BoundFunction(function, context, instructions));
      ++context.ip;
      return context;
    }

    @Override
    public String toString() {
      return "BindFunction[function=%s, %d instructions]"
          .formatted(function.identifier().name(), instructions.size());
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
      context.pushData(Script.Comparison.doOp(context, op, lhs, rhs));
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
      var value = context.popData();
      context.set(varName, value);
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
}
