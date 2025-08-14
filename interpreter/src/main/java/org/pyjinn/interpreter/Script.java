// SPDX-FileCopyrightText: © 2025 Greg Christiana <maxuser@pyjinn.org>
// SPDX-License-Identifier: MIT

package org.pyjinn.interpreter;

import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.io.PrintStream;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.pyjinn.parser.PyjinnParser;

public class Script {

  private record ClassMethodName(String type, String method) {}

  private record CallSite(ClassMethodName classMethodName, String filename, int lineno) {
    @Override
    public String toString() {
      return "%s.%s(%s:%d)"
          .formatted(classMethodName.type(), classMethodName.method(), filename, lineno);
    }
  }

  private final ThreadLocal<Deque<CallSite>> callStack =
      ThreadLocal.withInitial(ArrayDeque<CallSite>::new);

  public static class Module {
    private final String name;
    private final GlobalContext globals;

    public Module(Script script, String filename, String name) {
      this.name = name;
      this.globals = GlobalContext.create(filename, script.symbolCache);
      globals.setVariable("__script__", script);
      globals.setVariable("__name__", name);
    }

    public String name() {
      return name;
    }

    public String filename() {
      return globals.moduleFilename;
    }

    public Environment globals() {
      return globals;
    }

    public void parse(JsonElement element, String scriptFilename) {
      if (globals.getVariable("__script__") instanceof Script script) {
        var parser =
            new JsonAstParser(
                this,
                scriptFilename,
                script.moduleHandler,
                script.classLoader,
                globals.symbolCache);
        parser.parseGlobals(element, globals);
      } else {
        throw new IllegalStateException(
            "Expected module %s to have global __script__ of type %s but got %s"
                .formatted(
                    name,
                    Script.class.getSimpleName(),
                    globals.getVariable("__script__").getClass().getSimpleName()));
      }
    }

    public void exec() {
      if (globals.getVariable("__script__") instanceof Script script) {
        script.moduleHandler.onExecModule(this);
      }
      globals.execGlobalStatements();
    }
  }

  // If enabled, allow field access of attributes in JsonObject, array index into JsonArray, and
  // automatic unboxing of JsonPrimitive.
  private static boolean enableSimplifiedJsonSyntax = true;

  public interface DebugLogger {
    /** Formats `message` containing printf-style "%s", "%d", etc with values from `args`. */
    void log(String message, Object... args);
  }

  private static DebugLogger logger = (message, args) -> {};

  // To enable debug logging to stderr:
  // Script.setDebugLogger((str, args) -> System.err.printf(str + "%n", args));
  public static void setDebugLogger(DebugLogger newLogger) {
    logger = newLogger;
  }

  private static VersionInfo versionInfo = null;

  public static VersionInfo versionInfo() throws Exception {
    if (versionInfo == null) {
      versionInfo = VersionInfo.load();
    }
    return versionInfo;
  }

  // javaClasses is static so that the map, and therefore instances of JavaClass, are shared across
  // Script instances. If this weren't the case then JavaClass("X") referenced in one script would
  // compare unequal with JavaClass("X") from another script, which would lead to subtle bugs when
  // objects are shared across scripts in the same process.
  private static ConcurrentHashMap<Class<?>, JavaClass> javaClasses = new ConcurrentHashMap<>();

  private static JavaClass getJavaClass(Class<?> type) {
    return javaClasses.computeIfAbsent(type, JavaClass::new);
  }

  public interface ModuleHandler {
    default void onParseImport(Module module, Import importStatement) {}

    default void onParseImport(Module module, ImportFrom importFromStatement) {}

    default Path getModulePath(String name) {
      String platformSeparator = System.getProperty("file.separator");
      String filename = name.replace(".", platformSeparator) + ".py";
      Path path = Paths.get(filename);
      return path;
    }

    default void onExecModule(Module module) {}
  }

  private final ClassLoader classLoader;
  private final ModuleHandler moduleHandler;
  private final SymbolCache symbolCache;

  // Map from module filensame ("foo/bar/baz.py") to Module instance.
  // Every value in this map is unique.
  private final Map<String, Module> modulesByFilename = new ConcurrentHashMap<>();

  // Map from module (e.g. "foo.bar.baz") to Module instance.
  // Values in this map may be duplicates if the same module is referenced by distinct names.
  private final Map<String, Module> modulesByName = new ConcurrentHashMap<>();

  public interface ZombieCallbackHandler {
    void handle(String script, String callable, int count);
  }

  private ZombieCallbackHandler zombieCallbackHandler =
      (script, callable, count) -> {
        throw new IllegalStateException(
            "Illegal invocation of %s (count: %d) defined in script that already exited: %s"
                .formatted(callable, count, script));
      };

  public Consumer<String> stdout = System.out::println;
  public Consumer<String> stderr = System.err::println;

  // Set to null once the script has exited.
  private LinkedList<Consumer<Integer>> atExitListeners = new LinkedList<>();

  // For use by apps that need to share custom data across modules.
  public final PyDict vars = new PyDict();

  public Script() {
    this(
        "<stdin>",
        ClassLoader.getSystemClassLoader(),
        new ModuleHandler() {},
        className -> className,
        className -> className,
        (clazz, fieldName) -> fieldName,
        (clazz, methodName) -> Set.of(methodName));
  }

  private static final String MAIN_MODULE_NAME = "__main__";

  public Script(
      String scriptFilename,
      ClassLoader classLoader,
      ModuleHandler moduleHandler,
      java.util.function.Function<String, String> toRuntimeClassName,
      java.util.function.Function<String, String> toPrettyClassName,
      BiFunction<Class<?>, String, String> toRuntimeFieldName,
      BiFunction<Class<?>, String, Set<String>> toRuntimeMethodNames) {
    this.classLoader = classLoader;
    this.moduleHandler = moduleHandler;
    this.symbolCache =
        new SymbolCache(
            toRuntimeClassName, toPrettyClassName, toRuntimeFieldName, toRuntimeMethodNames);
    this.modulesByName.put(MAIN_MODULE_NAME, new Module(this, scriptFilename, MAIN_MODULE_NAME));
  }

  public void atExit(Consumer<Integer> atExit) {
    if (atExitListeners == null) {
      // Script has already exited.
      return;
    }
    atExitListeners.add(atExit);
  }

  public void exit(int status) {
    if (atExitListeners == null) {
      // Script has already exited.
      return;
    }
    var atExitListenersReversed = atExitListeners.reversed();
    atExitListeners = null; // null indicates that the script has exited.
    try {
      // Don't iterate over modulesByFilename because it doesn't include __main__.
      for (var module : modulesByName.values()) {
        module.globals().halt();
      }
    } finally {
      for (var listener : atExitListenersReversed) {
        listener.accept(status);
      }
    }
  }

  public Module mainModule() {
    return modulesByName.get(MAIN_MODULE_NAME);
  }

  public void redirectStdout(Consumer<String> out) {
    this.stdout = out;
  }

  public void redirectStderr(Consumer<String> err) {
    this.stderr = err;
  }

  /** Sets a handler for when script functions are called after the enclosing script has exited. */
  public void setZombieCallbackHandler(ZombieCallbackHandler handler) {
    zombieCallbackHandler = handler;
  }

  public Script parse(JsonElement element) {
    parse(element, "<stdin>");
    return this;
  }

  public Script parse(JsonElement element, String scriptFilename) {
    mainModule().parse(element, scriptFilename);
    return this;
  }

  public Script exec() {
    mainModule().exec();
    return this;
  }

  public BoundFunction getFunction(String name) {
    return (BoundFunction) mainModule().globals.getVariable(name);
  }

  public record JsonAstParser(
      Module module,
      String filename,
      ModuleHandler moduleHandler,
      ClassLoader classLoader,
      SymbolCache symbolCache) {

    public void parseGlobals(JsonElement element, GlobalContext globals) {
      String type = getType(element);
      switch (type) {
        case "Module":
          {
            for (var global : getBody(element)) {
              parseGlobals(global, globals);
            }
            return;
          }

        default:
          globals.addGlobalStatement(parseStatements(element));
          return;
      }
    }

    public Statement parseStatementBlock(JsonArray block) {
      if (block.size() == 1) {
        return parseStatements(block.get(0));
      } else {
        return new StatementBlock(
            getLineno(block),
            StreamSupport.stream(block.spliterator(), false)
                .map(elem -> parseStatements(elem))
                .toList());
      }
    }

    private Import parseImport(JsonElement element) {
      var importStatement =
          new Import(
              getLineno(element),
              StreamSupport.stream(getAttr(element, "names").getAsJsonArray().spliterator(), false)
                  .map(
                      e ->
                          new ImportName(
                              getAttr(e, "name").getAsString(),
                              Optional.ofNullable(getAttrOrJavaNull(e, "asname"))
                                  .map(a -> a.getAsString())))
                  .toList());
      moduleHandler.onParseImport(module, importStatement);
      return importStatement;
    }

    private ImportFrom parseImportFrom(JsonElement element) {
      var importStatement =
          new ImportFrom(
              getLineno(element),
              getAttr(element, "module").getAsString(),
              StreamSupport.stream(getAttr(element, "names").getAsJsonArray().spliterator(), false)
                  .map(
                      e ->
                          new ImportName(
                              getAttr(e, "name").getAsString(),
                              Optional.ofNullable(getAttrOrJavaNull(e, "asname"))
                                  .map(a -> a.getAsString())))
                  .toList());
      moduleHandler.onParseImport(module, importStatement);
      return importStatement;
    }

    private ClassDef parseClassDef(JsonElement element) {
      var identifier = new Identifier(getAttr(element, "name").getAsString());
      var fields = new ArrayList<ClassFieldDef>();
      var methodDefs = new ArrayList<FunctionDef>();
      StreamSupport.stream(getBody(element).spliterator(), false)
          .forEach(
              elem -> {
                var type = getType(elem);
                switch (type) {
                  case "FunctionDef":
                    methodDefs.add(parseFunctionDef(identifier.name(), elem));
                    break;
                  case "Assign":
                    fields.add(parseClassFieldDef(elem, true));
                    break;
                  case "AnnAssign":
                    fields.add(parseClassFieldDef(elem, false));
                    break;
                }
              });
      return new ClassDef(
          getLineno(element), identifier, getDecorators(element), fields, methodDefs);
    }

    private ClassFieldDef parseClassFieldDef(JsonElement element, boolean multipleTargets) {
      var target =
          multipleTargets
              ? getAttr(element, "targets").getAsJsonArray().get(0).getAsJsonObject()
              : getAttr(element, "target").getAsJsonObject();
      var value = getAttr(element, "value");
      return new ClassFieldDef(
          new Identifier(target.get("id").getAsString()),
          value == null || value.isJsonNull()
              ? Optional.empty()
              : Optional.of(parseExpression(value)));
    }

    private List<Decorator> getDecorators(JsonElement element) {
      return StreamSupport.stream(
              getAttr(element, "decorator_list").getAsJsonArray().spliterator(), false)
          .map(
              e -> {
                String type = getType(e);
                if ("Call".equals(type)) {
                  return Optional.of(
                      new Decorator(
                          getAttr(getAttr(e, "func"), "id").getAsString(),
                          getAttr(e, "keywords").getAsJsonArray().asList()));
                } else if ("Name".equals(type)) {
                  return Optional.of(new Decorator(getAttr(e, "id").getAsString(), List.of()));
                } else {
                  // TODO(maxuser): What other kinds of decorator expressions are there?
                  return Optional.<Decorator>empty();
                }
              })
          .filter(Optional::isPresent)
          .map(Optional::get)
          .toList();
    }

    private FunctionDef parseFunctionDef(String enclosingClassName, JsonElement element) {
      var identifier = new Identifier(getAttr(element, "name").getAsString());
      var decorators = getDecorators(element);
      var argsObject = getAttr(element, "args").getAsJsonObject();
      List<FunctionArg> args = parseFunctionArgs(getAttr(argsObject, "args").getAsJsonArray());
      var star = getAttr(argsObject, "vararg");
      Optional<FunctionArg> vararg =
          star == null || star.isJsonNull()
              ? Optional.empty()
              : Optional.of(
                  new FunctionArg(
                      new Identifier(getAttr(star.getAsJsonObject(), "arg").getAsString())));

      List<Expression> defaults =
          Optional.ofNullable(getAttr(argsObject, "defaults"))
              .map(
                  d ->
                      StreamSupport.stream(d.getAsJsonArray().spliterator(), false)
                          .map(this::parseExpression)
                          .toList())
              .orElse(List.of());
      Statement body = parseStatementBlock(getBody(element));
      // TODO(maxuser): Support **kwargs.
      var func =
          new FunctionDef(
              getLineno(element),
              enclosingClassName,
              identifier,
              decorators,
              args,
              vararg,
              defaults,
              body);
      return func;
    }

    public Statement parseStatements(JsonElement element) {
      try {
        String type = getType(element);
        switch (type) {
          case "Import":
            return parseImport(element);

          case "ImportFrom":
            return parseImportFrom(element);

          case "ClassDef":
            return parseClassDef(element);

          case "FunctionDef":
            return parseFunctionDef("<>", element);

          case "AnnAssign":
            {
              Expression lhs = parseExpression(element.getAsJsonObject().get("target"));
              Expression rhs = parseExpression(getAttr(element, "value"));
              return new Assignment(getLineno(element), lhs, rhs);
            }

          case "Assign":
            {
              Expression lhs = parseExpression(getTargets(element).get(0));
              Expression rhs = parseExpression(getAttr(element, "value"));
              return new Assignment(getLineno(element), lhs, rhs);
            }

          case "AugAssign":
            {
              Expression lhs = parseExpression(getTarget(element));
              Expression rhs = parseExpression(getAttr(element, "value"));
              String opName = getType(getAttr(element, "op"));
              final AugmentedAssignment.Op op;
              switch (opName) {
                case "Add":
                  op = AugmentedAssignment.Op.ADD_EQ;
                  break;
                case "Sub":
                  op = AugmentedAssignment.Op.SUB_EQ;
                  break;
                case "Mult":
                  op = AugmentedAssignment.Op.MULT_EQ;
                  break;
                case "Div":
                  op = AugmentedAssignment.Op.DIV_EQ;
                  break;
                default:
                  throw new IllegalArgumentException(
                      "Unsupported type of augmented assignment: " + opName);
              }
              switch (lhs) {
                case Identifier id:
                  return new AugmentedAssignment(getLineno(element), lhs, op, rhs);
                case ArrayIndex index:
                  return new AugmentedAssignment(getLineno(element), lhs, op, rhs);
                case FieldAccess access:
                  return new AugmentedAssignment(getLineno(element), lhs, op, rhs);
                default:
                  throw new IllegalArgumentException(
                      String.format(
                          "Unsupported expression type for lhs of assignment: `%s` (%s)",
                          lhs, lhs.getClass().getSimpleName()));
              }
            }

          case "Delete":
            return new Deletion(
                getLineno(element),
                StreamSupport.stream(getTargets(element).spliterator(), false)
                    .map(this::parseExpression)
                    .toList());

          case "Global":
            {
              return new GlobalVarDecl(
                  getLineno(element),
                  StreamSupport.stream(
                          getAttr(element, "names").getAsJsonArray().spliterator(), false)
                      .map(name -> new Identifier(name.getAsString()))
                      .toList());
            }

          case "Nonlocal":
            {
              return new NonlocalVarDecl(
                  getLineno(element),
                  StreamSupport.stream(
                          getAttr(element, "names").getAsJsonArray().spliterator(), false)
                      .map(name -> new Identifier(name.getAsString()))
                      .toList());
            }

          case "Expr":
            return parseExpression(getAttr(element, "value"));

          case "If":
            {
              var elseElement = getAttr(element, "orelse").getAsJsonArray();
              return new IfBlock(
                  getLineno(element),
                  parseExpression(getAttr(element, "test")),
                  parseStatementBlock(getBody(element)),
                  elseElement.isEmpty()
                      ? Optional.empty()
                      : Optional.of(parseStatementBlock(elseElement)));
            }

          case "For":
            return new ForBlock(
                getLineno(element),
                parseExpression(getAttr(element, "target")),
                parseExpression(getAttr(element, "iter")),
                parseStatementBlock(getBody(element)));

          case "While":
            return new WhileBlock(
                getLineno(element),
                parseExpression(getAttr(element, "test")),
                parseStatementBlock(getBody(element)));

          case "Pass":
            return new Pass();

          case "Break":
            return new Break();

          case "Continue":
            return new Continue();

          case "Try":
            {
              JsonArray finalBody = getAttr(element, "finalbody").getAsJsonArray();
              return new TryBlock(
                  getLineno(element),
                  parseStatementBlock(getBody(element)),
                  StreamSupport.stream(
                          getAttr(element, "handlers").getAsJsonArray().spliterator(), false)
                      .map(this::parseExceptionHandler)
                      .toList(),
                  finalBody.isEmpty()
                      ? Optional.empty()
                      : Optional.of(parseStatementBlock(finalBody)));
            }

          case "Raise":
            return new RaiseStatement(getLineno(element), parseExpression(getAttr(element, "exc")));

          case "Return":
            {
              var returnValue = getAttr(element, "value");
              if (returnValue.isJsonNull()) {
                // No return value. This differs from `return None` in terms of the AST, but the two
                // evaluate the same.
                return new ReturnStatement(getLineno(element), null);
              } else {
                return new ReturnStatement(getLineno(element), parseExpression(returnValue));
              }
            }
        }
      } catch (ParseException e) {
        throw e;
      } catch (Exception e) {
        throw new ParseException(
            "Syntax error in %s, line %s:\n%s"
                .formatted(filename, getAttr(element, "lineno"), element),
            e);
      }
      throw new IllegalArgumentException("Unknown statement type: " + element.toString());
    }

    private int getLineno(JsonElement element) {
      if (element.isJsonObject()) {
        var obj = element.getAsJsonObject();
        final String LINENO = "lineno";
        if (obj.has(LINENO)) {
          return obj.get(LINENO).getAsNumber().intValue();
        }
      }
      return -1;
    }

    class ParseException extends RuntimeException {
      public ParseException(String message, Throwable cause) {
        super(message, cause);
      }
    }

    private ExceptionHandler parseExceptionHandler(JsonElement element) {
      var type = getAttr(element, "type");
      var name = getAttr(element, "name");
      return new ExceptionHandler(
          type.isJsonNull()
              ? Optional.empty()
              : Optional.of(new Identifier(getAttr(getAttr(element, "type"), "id").getAsString())),
          name.isJsonNull()
              ? Optional.empty()
              : Optional.of(new Identifier(getAttr(element, "name").getAsString())),
          parseStatementBlock(getBody(element)));
    }

    private List<FunctionArg> parseFunctionArgs(JsonArray args) {
      return StreamSupport.stream(args.spliterator(), false)
          .map(
              arg ->
                  new FunctionArg(
                      new Identifier(getAttr(arg.getAsJsonObject(), "arg").getAsString())))
          .toList();
    }

    private enum ParseContext {
      // Default context. Attributes parsed within NONE context are field accesses: foo.member
      NONE,

      // Attributes parsed within CALLER context are method bindings: foo.method(...)
      CALLER,
    }

    public Expression parseExpression(JsonElement element) {
      return parseExpressionWithContext(element, ParseContext.NONE);
    }

    private Expression parseExpressionWithContext(JsonElement element, ParseContext parseContext) {
      String type = getType(element);
      switch (type) {
        case "UnaryOp":
          return new UnaryOp(
              UnaryOp.parse(getType(getAttr(element, "op"))),
              parseExpression(getAttr(element, "operand")));

        case "BinOp":
          return new BinaryOp(
              parseExpression(getAttr(element, "left")),
              BinaryOp.parse(getType(getAttr(element, "op"))),
              parseExpression(getAttr(element, "right")));

        case "Compare":
          return new Comparison(
              parseExpression(getAttr(element, "left")),
              Comparison.parse(getType(getAttr(element, "ops").getAsJsonArray().get(0))),
              parseExpression(getAttr(element, "comparators").getAsJsonArray().get(0)));

        case "BoolOp":
          return new BoolOp(
              BoolOp.parse(getType(getAttr(element, "op"))),
              StreamSupport.stream(getAttr(element, "values").getAsJsonArray().spliterator(), false)
                  .map(this::parseExpression)
                  .toList());

        case "Name":
          {
            Identifier id = getId(element);
            if (id.name().equals("JavaClass")) {
              return new JavaClassKeyword();
            } else {
              return id;
            }
          }

        case "Starred":
          return new StarredExpression(parseExpression(getAttr(element, "value")));

        case "Constant":
          return ConstantExpression.parse(
              getAttr(element, "typename").getAsString(), getAttr(element, "value"));

        case "Call":
          {
            var func = parseExpressionWithContext(getAttr(element, "func"), ParseContext.CALLER);
            var args = getAttr(element, "args").getAsJsonArray();
            Optional<JsonArray> keywords =
                Optional.ofNullable(getAttr(element, "keywords")).map(JsonElement::getAsJsonArray);
            if (func instanceof JavaClassKeyword) {
              if (args.size() != 1) {
                throw new IllegalArgumentException(
                    "Expected exactly one argument to JavaClass but got " + args.size());
              }
              var arg = parseExpression(args.get(0));
              if (arg instanceof ConstantExpression constExpr
                  && constExpr.value() instanceof String constString) {
                return new JavaClassCall(constString, symbolCache, classLoader);
              } else {
                throw new IllegalArgumentException(
                    String.format(
                        "Expected JavaClass argument to be a string literal but got %s (%s)",
                        arg, args.get(0)));
              }
            } else {
              return new FunctionCall(
                  filename,
                  getLineno(element),
                  func,
                  StreamSupport.stream(args.spliterator(), false)
                      .map(this::parseExpression)
                      .toList(),
                  keywords
                      .map(
                          k ->
                              StreamSupport.stream(k.spliterator(), false)
                                  .map(this::parseKeyword)
                                  .toList())
                      .orElse(List.of()));
            }
          }

        case "Attribute":
          {
            var object = parseExpression(getAttr(element, "value"));
            var attr = new Identifier(getAttr(element, "attr").getAsString());
            if (parseContext == ParseContext.CALLER) {
              return new BoundMethodExpression(object, attr, symbolCache);
            } else {
              return new FieldAccess(object, attr, symbolCache);
            }
          }

        case "Subscript":
          return new ArrayIndex(
              parseExpression(getAttr(element, "value")),
              parseSliceExpression(getAttr(element, "slice")));

        case "IfExp":
          return new IfExpression(
              parseExpression(getAttr(element, "test")),
              parseExpression(getAttr(element, "body")),
              parseExpression(getAttr(element, "orelse")));

        case "ListComp":
          {
            var generator = getAttr(element, "generators").getAsJsonArray().get(0);
            var generatorType = getType(generator);
            if (!generatorType.equals("comprehension")) {
              throw new UnsupportedOperationException(
                  "Unsupported expression type in list comprehension: " + generatorType);
            }
            return new ListComprehension(
                parseExpression(getAttr(element, "elt")),
                parseExpression(getAttr(generator, "target")),
                parseExpression(getAttr(generator, "iter")),
                StreamSupport.stream(
                        getAttr(generator, "ifs").getAsJsonArray().spliterator(), false)
                    .map(this::parseExpression)
                    .toList());
          }

        case "Tuple":
          return new TupleLiteral(
              StreamSupport.stream(getAttr(element, "elts").getAsJsonArray().spliterator(), false)
                  .map(this::parseExpression)
                  .toList());

        case "List":
          return new ListLiteral(
              StreamSupport.stream(getAttr(element, "elts").getAsJsonArray().spliterator(), false)
                  .map(this::parseExpression)
                  .toList());

        case "Dict":
          return new DictLiteral(
              StreamSupport.stream(getAttr(element, "keys").getAsJsonArray().spliterator(), false)
                  .map(this::parseExpression)
                  .toList(),
              StreamSupport.stream(getAttr(element, "values").getAsJsonArray().spliterator(), false)
                  .map(this::parseExpression)
                  .toList());

        case "Lambda":
          {
            return new Lambda(
                parseFunctionArgs(
                    getAttr(getAttr(element, "args").getAsJsonObject(), "args").getAsJsonArray()),
                parseExpression(getAttr(element, "body")),
                getLineno(element));
          }

        case "JoinedStr":
          return new FormattedString(
              StreamSupport.stream(getAttr(element, "values").getAsJsonArray().spliterator(), false)
                  .map(
                      v ->
                          getType(v).equals("FormattedValue")
                              ? parseExpression(getAttr(v, "value"))
                              : parseExpression(v))
                  .toList());
      }
      throw new IllegalArgumentException("Unknown expression type: " + element.toString());
    }

    private KeywordArg parseKeyword(JsonElement element) {
      String type = getType(element);
      if (!"keyword".equals(type)) {
        throw new IllegalArgumentException(
            "Expected function call arg of type \"keyword\" but got: " + type);
      }
      var arg = getAttr(element, "arg");
      var value = getAttr(element, "value");
      return new KeywordArg(arg.getAsString(), parseExpression(value));
    }

    private Expression parseSliceExpression(JsonElement element) {
      if ("Slice".equals(getType(element))) {
        var lower = getAttr(element, "lower");
        var upper = getAttr(element, "upper");
        var step = getAttr(element, "step");
        return new SliceExpression(
            lower.isJsonNull() ? Optional.empty() : Optional.of(parseExpression(lower)),
            upper.isJsonNull() ? Optional.empty() : Optional.of(parseExpression(upper)),
            step.isJsonNull() ? Optional.empty() : Optional.of(parseExpression(step)));
      } else {
        return parseExpression(element);
      }
    }

    private static String getType(JsonElement element) {
      return element.getAsJsonObject().get("type").getAsString();
    }

    private static JsonObject getTarget(JsonElement element) {
      return element.getAsJsonObject().get("target").getAsJsonObject();
    }

    private static JsonArray getTargets(JsonElement element) {
      return element.getAsJsonObject().get("targets").getAsJsonArray();
    }

    private static JsonElement getAttr(JsonElement element, String attr) {
      return element.getAsJsonObject().get(attr);
    }

    private static JsonElement getAttrOrJavaNull(JsonElement element, String attr) {
      var result = element.getAsJsonObject().get(attr);
      return result.isJsonNull() ? null : result;
    }

    private static Identifier getId(JsonElement element) {
      return new Identifier(element.getAsJsonObject().get("id").getAsString());
    }

    private static JsonArray getBody(JsonElement element) {
      return element.getAsJsonObject().get("body").getAsJsonArray();
    }
  }

  public interface Statement {
    default void exec(Context context) {
      throw new UnsupportedOperationException(
          "Execution of statement type not implemented: " + getClass().getSimpleName());
    }

    default int lineno() {
      return -1;
    }
  }

  public static boolean convertToBool(Object value) {
    if (value == null) {
      return false;
    } else if (value instanceof Boolean bool) {
      return bool;
    } else if (value instanceof String str) {
      return !str.isEmpty() && !str.equals("False");
    } else if (value.getClass().isArray()) {
      return Array.getLength(value) > 0;
    } else if (value instanceof Lengthable lengthable) {
      return lengthable.__len__() != 0;
    } else if (value instanceof Collection<?> collection) {
      return !collection.isEmpty();
    } else if (value instanceof Map<?, ?> map) {
      return !map.isEmpty();
    } else if (value instanceof Number number) {
      return number.doubleValue() != 0.;
    } else {
      return true;
    }
  }

  public record Decorator(String name, List<JsonElement> keywords) {}

  public record BoundFunction(
      FunctionDef function,
      Context enclosingContext,
      List<Object> defaults,
      AtomicInteger zombieCounter)
      implements Function {
    public BoundFunction(FunctionDef function, Context enclosingContext) {
      this(
          function,
          enclosingContext,
          function.evalArgDefaults(enclosingContext),
          new AtomicInteger());
    }

    @Override
    public Object call(Environment env, Object... params) {
      if (enclosingContext.env().halted()) {
        // TODO(maxuser): Clear function's internal state to avoid memory leak of the entire script.
        var script = (Script) enclosingContext.env().getVariable("__script__");
        script.zombieCallbackHandler.handle(
            script.mainModule().filename(),
            "function '%s'".formatted(function.identifier().name()),
            zombieCounter.incrementAndGet());
        return null;
      }

      final List<FunctionArg> args = function.args;
      int numParams = params.length;
      var kwargs = (numParams > 0 && params[numParams - 1] instanceof KeywordArgs k) ? k : null;
      if (kwargs != null) {
        --numParams; // Ignore the final arg when it's kwargs.
      }

      Set<String> assignedArgs = new HashSet<String>();
      Set<String> unassignedArgs = args.stream().map(a -> a.identifier().name()).collect(toSet());

      var localContext =
          enclosingContext.createLocalContext(
              function.enclosingClassName, function.identifier.name());

      // Assign additional args to vararg if there is one.
      if (function.vararg().isPresent()) {
        if (numParams < args.size()) {
          throw new IllegalArgumentException(
              "%s() missing %d required positional argument%s"
                  .formatted(
                      function.identifier,
                      args.size() - numParams,
                      (args.size() - numParams) == 1 ? "" : "s"));
        }
        var vararg = new Object[numParams - args.size()];
        for (int i = args.size(); i < numParams; ++i) {
          vararg[i - args.size()] = params[i];
        }
        localContext.setVariable(function.vararg().get().identifier().name(), new PyTuple(vararg));
        numParams = args.size();
      } else if (numParams > args.size()) {
        throw new IllegalArgumentException(
            "%s() takes %d positional argument%s but %d %s given"
                .formatted(
                    function.identifier,
                    args.size(),
                    args.size() == 1 ? "" : "s",
                    params.length,
                    params.length == 1 ? "was" : "were"));
      }

      // Assign positional args.
      for (int i = 0; i < numParams; ++i) {
        var arg = args.get(i);
        var argValue = params[i];
        String name = arg.identifier().name();
        assignedArgs.add(name);
        unassignedArgs.remove(name);
        localContext.setVariable(name, argValue);
      }

      // Assign kwargs.
      if (kwargs != null) {
        for (var entry : kwargs.entrySet()) {
          var name = entry.getKey();
          if (assignedArgs.contains(name)) {
            throw new IllegalArgumentException(
                "%s() got multiple values for argument '%s'"
                    .formatted(function.identifier.name(), name));
          }
          if (!unassignedArgs.contains(name)) {
            throw new IllegalArgumentException(
                "%s() got an unexpected keyword argument '%s'"
                    .formatted(function.identifier.name(), name));
          }
          assignedArgs.add(name);
          unassignedArgs.remove(name);
          localContext.setVariable(name, entry.getValue());
        }
      }

      // Assign default values to unassigned args.
      int defaultsOffset = args.size() - defaults.size();
      for (int i = 0; i < defaults.size(); ++i) {
        String name = args.get(i + defaultsOffset).identifier().name();
        if (unassignedArgs.contains(name)) {
          assignedArgs.add(name);
          unassignedArgs.remove(name);
          localContext.setVariable(name, defaults.get(i));
        }
      }

      // Verify that all args are assigned.
      if (!unassignedArgs.isEmpty()) {
        throw new IllegalArgumentException(
            "%s() missing %d positional arguments: %s"
                .formatted(function.identifier.name(), unassignedArgs.size(), unassignedArgs));
      }

      localContext.exec(function.body);
      return localContext.returnValue();
    }
  }

  // `type` is an array of length 1 because CtorFunction needs to be instantiated before the
  // surrounding class is fully defined. (Alternatively, PyClass could be mutable so that it's
  // instantiated before CtorFunction.)
  public record CtorFunction(PyClass[] type, BoundFunction function) implements Function {
    @Override
    public Object call(Environment env, Object... params) {
      Object[] ctorParams = new Object[params.length + 1];
      var self = new PyObject(type[0]);
      ctorParams[0] = self;
      System.arraycopy(params, 0, ctorParams, 1, params.length);
      function.call(env, ctorParams);
      return self;
    }
  }

  private Module importModule(String name) throws Exception {
    Module module;

    // First try to find the module by the name in the import statement.
    module = modulesByName.get(name);
    if (module != null) {
      return module;
    }

    // Get the file location of the requested modile.
    Path modulePath = moduleHandler.getModulePath(name);
    if (!Files.exists(modulePath)) {
      throw new IllegalArgumentException("No module named '%s' at %s".formatted(name, modulePath));
    }

    // Next try to find the module by its filename.
    String moduleFilename = modulePath.toString();
    module = modulesByFilename.get(moduleFilename);
    if (module != null) {
      return module;
    }

    // Couldn't find the cached module, so create it.
    String scriptCode = Files.readString(modulePath);
    JsonElement scriptAst = PyjinnParser.parse(moduleFilename, scriptCode);
    module = new Module(this, moduleFilename, filenameToModuleName(moduleFilename));
    module.parse(scriptAst, moduleFilename);
    modulesByName.put(name, module);
    modulesByFilename.put(moduleFilename, module);
    module.exec();
    return module;
  }

  public static String filenameToModuleName(String filename) {
    // Remove the ".py" extension
    String moduleName = filename;
    if (moduleName.endsWith(".py")) {
      moduleName = moduleName.substring(0, moduleName.length() - 3); // -3 for ".py"
    }

    // Replace file separators ('/' or '\') with dots
    String platformSeparator = System.getProperty("file.separator");
    moduleName = moduleName.replace(platformSeparator, ".");

    return moduleName;
  }

  public record ImportName(String name, Optional<String> alias) {
    public String importedName() {
      return alias.orElse(name);
    }
  }

  public record Import(int lineno, List<ImportName> modules) implements Statement {
    @Override
    public void exec(Context context) {
      try {
        if (context.globals.getVariable("__script__") instanceof Script script) {
          for (var importModule : modules) {
            var module = script.importModule(importModule.name());

            // To support modules with dots in their name, e.g. `import foo.bar.baz`,
            // iterate the name parts in reverse order. For the last part (`baz`),
            // create a PyObject whose __dict__ is the imported module's global vars
            // and wrap each previous name part in a PyObject's __dict__. It's equivalent to:
            //
            // baz = imported_module.globals()
            // _bar = object()
            // _bar.__dict__["baz"] = _baz  # Note that Python doesn't create __dict__ for object().
            // foo = object()
            // foo.__dict__["bar"] = _bar
            String[] nameParts = importModule.importedName().split("\\.");
            var object = new PyObject(PyClass.MODULE_TYPE, module.globals().vars());
            for (int i = nameParts.length - 1; i > 0; --i) {
              var prevObject = object;
              object = new PyObject(PyClass.MODULE_TYPE);
              object.__dict__.__setitem__(nameParts[i], prevObject);
            }
            context.setVariable(nameParts[0], object);
          }
        }
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  public record ImportFrom(int lineno, String module, List<ImportName> names) implements Statement {
    @Override
    public void exec(Context context) {
      try {
        if (context.globals.getVariable("__script__") instanceof Script script) {
          var module = script.importModule(module());
          if (names().size() == 1 && names().get(0).name().equals("*")) {
            for (var entry : module.globals().vars().getJavaMap().entrySet()) {
              var name = entry.getKey();
              var value = entry.getValue();
              context.setVariable((String) name, value);
            }
          } else {
            for (var importName : names()) {
              var importedEntity = module.globals().getVariable(importName.name());
              context.setVariable(importName.importedName(), importedEntity);
            }
          }
        }
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  public record ClassFieldDef(Identifier identifier, Optional<Expression> defaultValue) {}

  public record ClassDef(
      int lineno,
      Identifier identifier,
      List<Decorator> decorators,
      List<ClassFieldDef> fields,
      List<FunctionDef> methodDefs)
      implements Statement {
    /** Adds this class definition to the specified {@code context}. */
    @Override
    public void exec(Context context) {
      var type = new PyClass[1]; // Using array to circumvent immutability constraint for record.
      Function ctor;
      Optional<Decorator> dataclass =
          decorators.stream().filter(d -> d.name().equals("dataclass")).findFirst();
      if (dataclass.isPresent()) {
        // Validate that all fields with default values appear after all fields without defaults.
        List<Expression> defaults = new ArrayList<>();
        for (var field : fields) {
          if (field.defaultValue().isPresent()) {
            defaults.add(field.defaultValue().get());
          } else if (!defaults.isEmpty()) {
            throw new IllegalArgumentException(
                "non-default argument '%s' follows default argument"
                    .formatted(field.identifier().name()));
          }
        }

        ctor =
            new BoundFunction(
                new FunctionDef(
                    lineno,
                    identifier.name(),
                    new Identifier("__init__"),
                    /* decorators= */ List.of(),
                    /* args= */ fields.stream().map(f -> new FunctionArg(f.identifier)).toList(),
                    /* vararg= */ Optional.empty(),
                    defaults,
                    new Statement() {
                      @Override
                      public void exec(Context context) {
                        var object = new PyObject(type[0]);
                        for (var field : fields) {
                          String name = field.identifier().name();
                          object.__dict__.__setitem__(name, context.getVariable(name));
                        }
                        context.returnWithValue(object);
                      }

                      @Override
                      public int lineno() {
                        return lineno;
                      }
                    }),
                context);
      } else {
        ctor =
            (env, params) -> {
              Function.expectNumParams(params, 0, identifier.name() + ".__init__");
              return new PyObject(type[0]);
            };
      }

      var instanceMethods = new HashMap<String, Function>();
      var classLevelMethods = new HashMap<String, ClassLevelMethod>();
      for (var methodDef : methodDefs) {
        String methodName = methodDef.identifier().name();
        // TODO(maxuser): Support __str__/__rep__ methods for custom string output.
        if ("__init__".equals(methodName)) {
          ctor = new CtorFunction(type, new BoundFunction(methodDef, context));
          instanceMethods.put(methodName, ctor);
        } else if (methodDef.decorators().stream().anyMatch(d -> d.name().equals("classmethod"))) {
          classLevelMethods.put(
              methodName, new ClassLevelMethod(true, new BoundFunction(methodDef, context)));
        } else if (methodDef.decorators().stream().anyMatch(d -> d.name().equals("staticmethod"))) {
          classLevelMethods.put(
              methodName, new ClassLevelMethod(false, new BoundFunction(methodDef, context)));
        } else {
          instanceMethods.put(methodName, new BoundFunction(methodDef, context));
        }
      }
      // Example of "@dataclass(frozen=True)":
      // [{"type":"keyword","arg":"frozen","value":{"value":true}}]
      boolean isFrozen =
          dataclass
              .map(
                  d ->
                      d.keywords().stream()
                          .anyMatch(
                              k ->
                                  JsonAstParser.getType(k).equals("keyword")
                                      && JsonAstParser.getAttr(k, "arg")
                                          .getAsString()
                                          .equals("frozen")
                                      && JsonAstParser.getAttr(
                                              JsonAstParser.getAttr(k, "value"), "value")
                                          .getAsBoolean()))
              .orElse(false);
      type[0] =
          new PyClass(
              identifier.name(),
              ctor,
              isFrozen,
              instanceMethods,
              classLevelMethods,
              dataclass.map(d -> dataclassHashCode(fields)),
              dataclass.map(d -> dataclassToString(fields)));
      context.setVariable(identifier.name(), type[0]);
      if (!dataclass.isPresent()) {
        for (var field : fields) {
          field
              .defaultValue()
              .ifPresent(
                  v -> type[0].__dict__.__setitem__(field.identifier().name(), v.eval(context)));
        }
      }
    }

    private static java.util.function.Function<PyObject, Integer> dataclassHashCode(
        List<ClassFieldDef> fields) {
      return dataObject ->
          Objects.hash(
              fields.stream()
                  .map(f -> dataObject.__dict__.__getitem__(f.identifier().name()))
                  .toArray());
    }

    private static java.util.function.Function<PyObject, String> dataclassToString(
        List<ClassFieldDef> fields) {
      return dataObject ->
          fields.stream()
              .map(
                  field -> {
                    var fieldName = field.identifier().name();
                    return "%s=%s".formatted(fieldName, dataObject.__dict__.__getitem__(fieldName));
                  })
              .collect(joining(", ", dataObject.type.name + "(", ")"));
    }

    @Override
    public String toString() {
      // TODO(maxuser): Parse Decorator.keywords from JsonElement to list of record (name, value).
      String decoratorString =
          decorators.stream()
              .map(
                  d ->
                      "@"
                          + d.name()
                          + d.keywords().stream()
                              .map(
                                  k ->
                                      JsonAstParser.getType(k).equals("keyword")
                                              && JsonAstParser.getAttr(k, "arg")
                                                  .getAsString()
                                                  .equals("frozen")
                                              && JsonAstParser.getAttr(
                                                      JsonAstParser.getAttr(k, "value"), "value")
                                                  .getAsBoolean()
                                          ? "frozen=True"
                                          : "")
                              .collect(joining(", ", "(", ")"))
                          + "\n")
              .collect(joining("\n"));

      var fieldsString =
          fields.stream()
              .map(
                  f ->
                      "\n  %s: any%s"
                          .formatted(
                              f.identifier(),
                              f.defaultValue().map(v -> " = " + v.toString()).orElse("")))
              .collect(joining());

      var methodsString =
          methodDefs.stream()
              .map(m -> "\n  " + m.toString().replaceAll("\n", "\n  "))
              .collect(joining());

      return "%sclass %s:%s%s"
          .formatted(decoratorString, identifier.name(), fieldsString, methodsString);
    }
  }

  public static class PyObject {
    public final PyClass type;
    public final PyDict __dict__;

    public PyObject(PyClass type) {
      this(type, new PyDict());
    }

    public PyObject(PyClass type, PyDict dict) {
      this.type = type;
      this.__dict__ = dict;
    }

    /**
     * Calls PyObject method named {@code methodName} with {@code params}.
     *
     * <p>Return type is an array rather than Optional because Optional cannot store null.
     *
     * @param methodName name of PyObject method to call
     * @param params arguments passed to PyObject method
     * @return return value wrapped in an array of 1 element, or empty array if no matching method
     */
    public Object[] callMethod(Environment env, String methodName, Object... params) {
      var field = __dict__.get(methodName);
      if (field != null && field instanceof Function function) {
        return new Object[] {function.call(env, params)};
      }

      var method = type.instanceMethods.get(methodName);
      if (method != null) {
        Object[] methodParams = new Object[params.length + 1];
        methodParams[0] = this;
        System.arraycopy(params, 0, methodParams, 1, params.length);
        return new Object[] {method.call(env, methodParams)};
      }

      return new Object[] {};
    }

    @Override
    public boolean equals(Object other) {
      if (other instanceof PyObject pyOther
          && type == pyOther.type
          && type.isFrozen
          && type.hashMethod.isPresent()) {
        return hashCode() == other.hashCode();
      } else {
        return super.equals(other);
      }
    }

    @Override
    public int hashCode() {
      if (type.hashMethod.isPresent()) {
        return type.hashMethod.get().apply(this);
      } else {
        return System.identityHashCode(this);
      }
    }

    @Override
    public String toString() {
      if (type.strMethod.isPresent()) {
        return type.strMethod.get().apply(this);
      } else {
        return "<%s object at 0x%x>".formatted(type.name, System.identityHashCode(this));
      }
    }
  }

  public static class PyObjects {
    public static String toString(Object value) {
      if (value == null) {
        return "None";
      } else if (value.getClass().isArray()) {
        var out = new StringBuilder("[");
        int length = Array.getLength(value);
        for (int i = 0; i < length; i++) {
          if (i != 0) {
            out.append(", ");
          }
          out.append(toRepr(Array.get(value, i)));
        }
        out.append("]");
        return out.toString();
      } else if (value instanceof PyList pyList) {
        return pyList.getJavaList().stream()
            .map(PyObjects::toRepr)
            .collect(joining(", ", "[", "]"));
      } else if (value instanceof List<?> list) {
        return list.stream().map(PyObjects::toRepr).collect(joining(", ", "[", "]"));
      } else if (value instanceof Boolean bool) {
        return bool ? "True" : "False";
      } else {
        return value.toString();
      }
    }

    public static String toRepr(Object value) {
      if (value instanceof String string) {
        Gson gson =
            new GsonBuilder()
                .setPrettyPrinting() // Optional: for pretty printing
                .disableHtmlEscaping() // Important: to prevent double escaping
                .create();
        return gson.toJson(string);
      } else {
        return PyObjects.toString(value);
      }
    }
  }

  public record ClassLevelMethod(boolean isClassmethod, Function function) {}

  public static class PyClass extends PyObject implements Function {
    public final String name;
    public final Function ctor;
    public final boolean isFrozen;
    public final Map<String, Function> instanceMethods;
    public final Map<String, ClassLevelMethod> classLevelMethods;
    public final Optional<java.util.function.Function<PyObject, Integer>> hashMethod;
    public final Optional<java.util.function.Function<PyObject, String>> strMethod;

    public static final PyClass CLASS_TYPE =
        new PyClass(
            "type",
            (env, params) -> null,
            false,
            Map.of(),
            Map.of(),
            Optional.empty(),
            Optional.empty());

    private static final PyClass MODULE_TYPE =
        new PyClass(
            "module",
            (env, params) -> null,
            false,
            Map.of(),
            Map.of(),
            Optional.empty(),
            Optional.empty());

    public PyClass(
        String name,
        Function ctor,
        boolean isFrozen,
        Map<String, Function> instanceMethods,
        Map<String, ClassLevelMethod> classLevelMethods,
        Optional<java.util.function.Function<PyObject, Integer>> hashMethod,
        Optional<java.util.function.Function<PyObject, String>> strMethod) {
      super(CLASS_TYPE);
      this.name = name;
      this.ctor = ctor;
      this.isFrozen = isFrozen;
      this.instanceMethods = instanceMethods;
      this.classLevelMethods = classLevelMethods;
      this.hashMethod = hashMethod;
      this.strMethod = strMethod;
    }

    @Override
    public Object call(Environment env, Object... params) {
      return ctor.call(env, params);
    }

    @Override
    public Object[] callMethod(Environment env, String methodName, Object... params) {
      var method = classLevelMethods.get(methodName);
      if (method == null) {
        return new Object[] {};
      }
      final Object[] methodParams;
      if (method.isClassmethod()) {
        methodParams = new Object[params.length + 1];
        methodParams[0] = this;
        System.arraycopy(params, 0, methodParams, 1, params.length);
      } else {
        methodParams = params;
      }
      return new Object[] {method.function().call(env, methodParams)};
    }

    @Override
    public String toString() {
      return "<class '%s'>".formatted(name);
    }
  }

  public record FunctionDef(
      int lineno,
      String enclosingClassName,
      Identifier identifier,
      List<Decorator> decorators,
      List<FunctionArg> args,
      Optional<FunctionArg> vararg,
      List<Expression> defaults,
      Statement body)
      implements Statement {
    /** Adds this function to the specified {@code context}. */
    @Override
    public void exec(Context context) {
      context.setBoundFunction(new BoundFunction(this, context));
    }

    public List<Object> evalArgDefaults(Context context) {
      return defaults.stream().map(d -> d.eval(context)).toList();
    }

    @Override
    public String toString() {
      String decoratorString =
          decorators.stream().map(d -> "@%s\n".formatted(d.name())).collect(joining());
      String bodyString = "  " + body.toString().replaceAll("\n", "\n  ");
      return "%sdef %s(%s):\n%s"
          .formatted(
              decoratorString,
              identifier.name(),
              args.stream().map(a -> a.identifier().name()).collect(joining(", ")),
              bodyString);
    }
  }

  public record FunctionArg(Identifier identifier) {}

  public record IfBlock(
      int lineno, Expression condition, Statement thenBody, Optional<Statement> elseBody)
      implements Statement {
    @Override
    public void exec(Context context) {
      if (context.skipStatement()) {
        return;
      }
      if (convertToBool(condition.eval(context))) {
        context.exec(thenBody);
      } else {
        elseBody.ifPresent(e -> context.exec(e));
      }
    }

    @Override
    public String toString() {
      var out = new StringBuilder("if ");
      out.append(condition.toString());
      out.append(":\n  ");
      out.append(thenBody.toString().replaceAll("\n", "\n  "));
      elseBody.ifPresent(
          e -> {
            out.append("\nelse:\n  ");
            out.append(e.toString().replaceAll("\n", "\n  "));
          });
      return out.toString();
    }
  }

  public record ForBlock(int lineno, Expression vars, Expression iter, Statement body)
      implements Statement {
    @Override
    public void exec(Context context) {
      if (context.skipStatement()) {
        return;
      }
      final Identifier loopVar;
      final TupleLiteral loopVars;
      if (vars instanceof Identifier id) {
        loopVar = id;
        loopVars = null;
      } else if (vars instanceof TupleLiteral tuple) {
        loopVar = null;
        loopVars = tuple;
      } else {
        throw new IllegalArgumentException("Unexpected loop variable type: " + vars.toString());
      }
      try {
        context.enterLoop();
        for (var value : getIterable(iter.eval(context))) {
          if (loopVar != null) {
            context.setVariable(loopVar.name(), value);
          } else {
            Assignment.assignTuple(context, loopVars, value);
          }
          context.exec(body);
          if (context.shouldBreak()) {
            break;
          }
          if (context.shouldContinue()) {
            context.resetContinueBit();
            continue;
          }
        }
      } finally {
        context.exitLoop();
      }
    }

    @Override
    public String toString() {
      return "for %s in %s:\n%s"
          .formatted(vars, iter, body.toString().replaceAll("^", "  ").replaceAll("\n", "\n  "));
    }
  }

  public record WhileBlock(int lineno, Expression condition, Statement body) implements Statement {
    @Override
    public void exec(Context context) {
      if (context.skipStatement()) {
        return;
      }
      try {
        context.enterLoop();
        while (convertToBool(condition.eval(context))) {
          context.exec(body);
          if (context.shouldBreak()) {
            break;
          }
          if (context.shouldContinue()) {
            context.resetContinueBit();
            continue;
          }
        }
      } finally {
        context.exitLoop();
      }
    }
  }

  public record Pass() implements Statement {
    @Override
    public void exec(Context context) {
      // Do nothing.
    }
  }

  public record Break() implements Statement {
    @Override
    public void exec(Context context) {
      context.breakLoop();
    }
  }

  public record Continue() implements Statement {
    @Override
    public void exec(Context context) {
      context.continueLoop();
    }
  }

  public record Identifier(String name) implements Expression {
    @Override
    public Object eval(Context context) {
      return context.getVariable(name);
    }

    @Override
    public String toString() {
      return name;
    }
  }

  public record ExceptionHandler(
      Optional<Identifier> exceptionType, Optional<Identifier> exceptionVariable, Statement body) {}

  public record TryBlock(
      int lineno,
      Statement tryBody,
      List<ExceptionHandler> exceptionHandlers,
      Optional<Statement> finallyBlock)
      implements Statement {
    @Override
    public void exec(Context context) {
      if (context.skipStatement()) {
        return;
      }
      PyException pyException = null;
      try {
        context.exec(tryBody);
      } catch (Exception e) {
        // PyException exists only to prevent all eval/exec/invoke methods from declaring that they
        // throw Exception.  Unwrap the underlying exception here.
        final Object exception;
        if (e instanceof PyException pe) {
          exception = pe.thrown;
        } else {
          exception = e;
        }
        boolean handled = false;
        for (var handler : exceptionHandlers) {
          var exceptionType = handler.exceptionType().map(t -> context.getVariable(t.name()));
          if (exceptionType.isEmpty()
              || (exceptionType.get() instanceof PyClass declaredType
                  && exception instanceof PyObject thrownObject
                  && thrownObject.type == declaredType)
              || (exceptionType.get() instanceof JavaClass javaClassId
                  && javaClassId.type().isAssignableFrom(exception.getClass()))) {
            handler
                .exceptionVariable()
                .ifPresent(
                    name -> {
                      context.setVariable(name, exception);
                    });
            context.exec(handler.body);
            handled = true;
            break;
          }
        }
        if (!handled) {
          pyException = new PyException(e);
          pyException.setStackTrace(e.getStackTrace()); // Keep original (script) stack trace.
          throw pyException;
        }
      } finally {
        try {
          finallyBlock.ifPresent(fb -> context.exec(fb));
        } catch (Exception e) {
          if (pyException != null) {
            // When suppressing an old exception, limit the stack frames of the new exception.
            e.addSuppressed(pyException);
            throw e;
          }
        }
      }
    }

    @Override
    public String toString() {
      var out = new StringBuilder("try:\n");
      out.append("  " + tryBody.toString().replaceAll("\n", "\n  ") + "\n");
      for (var handler : exceptionHandlers) {
        out.append("except");
        boolean hasExceptionType = handler.exceptionType().isPresent();
        boolean hasExceptionVariable = handler.exceptionVariable().isPresent();
        if (hasExceptionType && hasExceptionVariable) {
          out.append(
              " %s as %s".formatted(handler.exceptionType.get(), handler.exceptionVariable.get()));
        } else if (!hasExceptionType && hasExceptionVariable) {
          out.append(" " + handler.exceptionVariable.get());
        } else if (hasExceptionType && !hasExceptionVariable) {
          out.append(" " + handler.exceptionType.get());
        }
        out.append(":\n");
        out.append("  " + handler.body().toString().replaceAll("\n", "\n  ") + "\n");
      }
      finallyBlock.ifPresent(
          fb -> {
            out.append(" finally\n");
            out.append("  " + fb.toString().replaceAll("\n", "\n  ") + "\n");
          });
      return out.toString();
    }
  }

  /**
   * RuntimeException subclass that allows arbitrary Exception types to be thrown without requiring
   * all eval/exec/invoke methods to declare that they throw Exception.
   */
  public static class PyException extends RuntimeException {
    public final Object thrown;

    public PyException(Object thrown) {
      super(PyObjects.toString(thrown));
      this.thrown = thrown;
    }
  }

  public record RaiseStatement(int lineno, Expression exception) implements Statement {
    @Override
    public void exec(Context context) {
      if (context.skipStatement()) {
        return;
      }
      throw new PyException(exception.eval(context));
    }

    @Override
    public String toString() {
      return String.format("throw %s", exception);
    }
  }

  public interface Expression extends Statement {
    @Override
    default void exec(Context context) {
      if (context.skipStatement()) {
        return;
      }
      eval(context);
    }

    default JsonElement astNode() {
      return JsonNull.INSTANCE;
    }

    default Object eval(Context context) {
      throw new UnsupportedOperationException(
          String.format(
              "Eval for expression %s not implemented: %s", getClass().getSimpleName(), this));
    }
  }

  public record StatementBlock(int lineno, List<Statement> statements) implements Statement {
    @Override
    public void exec(Context context) {
      for (var statement : statements) {
        if (context.skipStatement()) {
          break;
        }
        context.exec(statement);
      }
    }

    @Override
    public String toString() {
      return statements.stream().map(Object::toString).collect(joining("\n"));
    }
  }

  public record ClassAliasAssignment(int lineno, Identifier identifier, Class<?> clss)
      implements Statement {
    @Override
    public void exec(Context context) {}

    @Override
    public String toString() {
      // TODO(maxuser): Format as Python assignment.
      return String.format("import %s as %s", clss.getName(), identifier);
    }
  }

  public static class FrozenInstanceError extends RuntimeException {
    public FrozenInstanceError(String message) {
      super(message);
    }
  }

  public record Assignment(int lineno, Expression lhs, Expression rhs) implements Statement {
    public Assignment {
      // TODO(maxuser): Support destructuring assignment more than one level of identifiers deep.
      if (!(lhs instanceof Identifier
          || lhs instanceof FieldAccess
          || lhs instanceof ArrayIndex
          || (lhs instanceof TupleLiteral tuple
              && tuple.elements().stream().allMatch(Identifier.class::isInstance)))) {
        throw new IllegalArgumentException(
            "Unsupported expression type for lhs of assignment: `%s` (%s)"
                .formatted(lhs, lhs.getClass().getSimpleName()));
      }
    }

    public static void assignTuple(Context context, TupleLiteral lhsTuple, Object rhsValue) {
      List<Identifier> lhsVars = lhsTuple.elements().stream().map(Identifier.class::cast).toList();
      rhsValue = promoteArrayToTuple(rhsValue);
      if (rhsValue instanceof ItemGetter getter && rhsValue instanceof Lengthable lengthable) {
        int lengthToUnpack = lengthable.__len__();
        if (lengthToUnpack != lhsVars.size()) {
          throw new IllegalArgumentException(
              String.format(
                  "Cannot unpack %d values into %d variables: %s",
                  lengthToUnpack, lhsVars.size(), rhsValue));
        }
        for (int i = 0; i < lengthToUnpack; ++i) {
          context.setVariable(lhsVars.get(i).name(), getter.__getitem__(i));
        }
      } else {
        throw new IllegalArgumentException(
            "Cannot unpack value to tuple: " + PyObjects.toString(rhsValue));
      }
    }

    @SuppressWarnings("unchecked")
    @Override
    public void exec(Context context) {
      if (context.skipStatement()) {
        return;
      }
      Object rhsValue = rhs.eval(context);
      if (lhs instanceof Identifier lhsId) {
        context.setVariable(lhsId, rhsValue);
        return;
      } else if (lhs instanceof FieldAccess lhsFieldAccess) {
        var lhsObject = lhsFieldAccess.object().eval(context);
        if (lhsObject instanceof PyObject pyObject) {
          String fieldName = lhsFieldAccess.field().name();
          if (pyObject.type.isFrozen) {
            throw new FrozenInstanceError(
                "cannot assign to field '%s' of type '%s'"
                    .formatted(fieldName, pyObject.type.name));
          }
          pyObject.__dict__.__setitem__(fieldName, rhsValue);
          return;
        }
      } else if (lhs instanceof TupleLiteral lhsTuple) {
        assignTuple(context, lhsTuple, rhsValue);
        return;
      } else if (lhs instanceof ArrayIndex lhsArrayIndex) {
        var array = lhsArrayIndex.array().eval(context);
        var index = lhsArrayIndex.index().eval(context);
        if (array.getClass().isArray()) {
          Array.set(array, ((Number) index).intValue(), rhsValue);
          return;
        } else if (array instanceof ItemSetter itemSetter) {
          itemSetter.__setitem__(index, rhsValue);
          return;
        } else if (array instanceof List list) {
          list.set(((Number) index).intValue(), rhsValue);
          return;
        } else if (array instanceof Map map) {
          map.put(index, rhsValue);
          return;
        }
        throw new IllegalArgumentException(
            "Unsupported subscript assignment to %s (%s)"
                .formatted(array, array.getClass().getSimpleName()));
      }
      throw new IllegalArgumentException(
          "Unsupported expression type for lhs of assignment: `%s` (%s)"
              .formatted(lhs, lhs.getClass().getSimpleName()));
    }

    @Override
    public String toString() {
      if (lhs instanceof Identifier lhsId) {
        return String.format("%s = %s", lhsId.name(), rhs);
      } else if (lhs instanceof ArrayIndex arrayIndex) {
        return String.format("%s[%s] = %s", arrayIndex.array(), arrayIndex.index(), rhs);
      } else {
        return String.format("%s = %s", lhs, rhs);
      }
    }
  }

  public record AugmentedAssignment(int lineno, Expression lhs, Op op, Expression rhs)
      implements Statement {
    public enum Op {
      ADD_EQ("+="),
      SUB_EQ("-="),
      MULT_EQ("*="),
      DIV_EQ("/=");

      private final String symbol;

      Op(String symbol) {
        this.symbol = symbol;
      }

      public String symbol() {
        return symbol;
      }

      public Object apply(Object lhs, Object rhs) {
        switch (this) {
          case ADD_EQ:
            if (lhs instanceof Number lhsNum && rhs instanceof Number rhsNum) {
              return Numbers.add(lhsNum, rhsNum);
            } else if (lhs instanceof String lhsStr && rhs instanceof String rhsStr) {
              return lhsStr + rhsStr;
            }
            if (lhs instanceof PyList pyList) {
              pyList.__iadd__(rhs);
              return null; // Return value unused because op has already been applied to the list.
            }
            break;

          case SUB_EQ:
            if (lhs instanceof Number lhsNum && rhs instanceof Number rhsNum) {
              return Numbers.subtract(lhsNum, rhsNum);
            }
            break;

          case MULT_EQ:
            if (lhs instanceof Number lhsNum && rhs instanceof Number rhsNum) {
              return Numbers.multiply(lhsNum, rhsNum);
            }
            break;

          case DIV_EQ:
            if (lhs instanceof Number lhsNum && rhs instanceof Number rhsNum) {
              return Numbers.divide(lhsNum, rhsNum);
            }
            break;
        }
        String lhsType = lhs == null ? "None" : lhs.getClass().getName();
        String rhsType = rhs == null ? "None" : rhs.getClass().getName();
        throw new IllegalArgumentException(
            String.format(
                "unsupported operand type(s) for %s: '%s' and '%s' ('%s %s %s')",
                symbol(), lhsType, rhsType, lhs, symbol(), rhs));
      }
    }

    @SuppressWarnings("unchecked")
    @Override
    public void exec(Context context) {
      if (context.skipStatement()) {
        return;
      }
      Object rhsValue = rhs.eval(context);
      if (lhs instanceof Identifier lhsId) {
        var oldValue = context.getVariable(lhsId.name());
        var newValue = op.apply(oldValue, rhsValue);
        if (newValue != null) {
          context.setVariable(lhsId, newValue);
        }
        return;
      } else if (lhs instanceof ArrayIndex lhsArrayIndex) {
        var array = lhsArrayIndex.array().eval(context);
        var index = lhsArrayIndex.index().eval(context);
        if (array.getClass().isArray()) {
          int intKey = ((Number) index).intValue();
          var oldValue = Array.get(array, intKey);
          Array.set(array, intKey, op.apply(oldValue, rhsValue));
          return;
        } else if (array instanceof ItemGetter itemGetter
            && array instanceof ItemSetter itemSetter) {
          var oldValue = itemGetter.__getitem__(index);
          itemSetter.__setitem__(index, op.apply(oldValue, rhsValue));
          return;
        } else if (array instanceof List list) {
          int intKey = ((Number) index).intValue();
          var oldValue = list.get(intKey);
          list.set(intKey, op.apply(oldValue, rhsValue));
          return;
        } else if (array instanceof Map map) {
          var oldValue = map.get(index);
          map.put(index, op.apply(oldValue, rhsValue));
          return;
        }
      } else if (lhs instanceof FieldAccess lhsFieldAccess) {
        var lhsObject = lhsFieldAccess.object().eval(context);
        if (lhsObject instanceof PyObject pyObject) {
          String fieldName = lhsFieldAccess.field().name();
          if (pyObject.type.isFrozen) {
            throw new FrozenInstanceError(
                "cannot assign to field '%s' of type '%s'"
                    .formatted(fieldName, pyObject.type.name));
          }
          var oldValue = pyObject.__dict__.__getitem__(fieldName);
          pyObject.__dict__.__setitem__(fieldName, op.apply(oldValue, rhsValue));
          return;
        }
      }
      throw new IllegalArgumentException(
          String.format(
              "Unsupported expression type for lhs of assignment: `%s` (%s)",
              lhs, lhs.getClass().getSimpleName()));
    }

    @Override
    public String toString() {
      return String.format("%s %s %s", lhs, op.symbol(), rhs);
    }
  }

  public record Deletion(int lineno, List<Expression> targets) implements Statement {
    @Override
    public void exec(Context context) {
      for (var target : targets) {
        if (target instanceof Identifier id) {
          context.deleteVariable(id.name());
        } else if (target instanceof ArrayIndex arrayIndex) {
          var array = arrayIndex.array().eval(context);
          var index = arrayIndex.index().eval(context);
          if (array instanceof ItemDeleter deleter) {
            deleter.__delitem__(index);
          } else if (array instanceof List list) {
            list.remove((int) (Integer) index);
          } else if (array instanceof Map map) {
            map.remove(index);
          } else {
            throw new IllegalArgumentException(
                "Object does not support subscript deletion: " + array.getClass().getName());
          }
        } else {
          throw new IllegalArgumentException("Cannot delete value: " + target.toString());
        }
      }
    }

    @Override
    public String toString() {
      return String.format("del %s", targets.stream().map(Object::toString).collect(joining(", ")));
    }
  }

  public record GlobalVarDecl(int lineno, List<Identifier> globalVars) implements Statement {
    @Override
    public void exec(Context context) {
      for (var identifier : globalVars) {
        context.declareGlobalVar(identifier.name());
      }
    }

    @Override
    public String toString() {
      return String.format(
          "global %s", globalVars.stream().map(Object::toString).collect(joining(", ")));
    }
  }

  public record NonlocalVarDecl(int lineno, List<Identifier> nonlocalVars) implements Statement {
    @Override
    public void exec(Context context) {
      for (var identifier : nonlocalVars) {
        context.declareNonlocalVar(identifier.name());
      }
    }

    @Override
    public String toString() {
      return String.format(
          "nonlocal %s", nonlocalVars.stream().map(Object::toString).collect(joining(", ")));
    }
  }

  public record ReturnStatement(int lineno, Expression returnValue) implements Statement {
    @Override
    public void exec(Context context) {
      if (context.skipStatement()) {
        return;
      }
      context.returnWithValue(returnValue == null ? null : returnValue.eval(context));
    }

    @Override
    public String toString() {
      if (returnValue == null) {
        return "return";
      } else {
        return String.format("return %s", returnValue);
      }
    }
  }

  public static Number parseIntegralValue(Number value) {
    long l = value.longValue();
    int i = (int) l;
    if (l == i) {
      return i;
    } else {
      return l;
    }
  }

  public static Number parseFloatingPointValue(Number value) {
    double d = value.doubleValue();
    float f = (float) d;
    if (d == f) {
      return f;
    } else {
      return d;
    }
  }

  public record ConstantExpression(Object value) implements Expression {
    public static ConstantExpression parse(String typename, JsonElement value) {
      switch (typename) {
        case "bool":
          return new ConstantExpression(value.getAsBoolean());
        case "int":
          return new ConstantExpression(parseIntegralValue(value.getAsNumber()));
        case "float":
          return new ConstantExpression(parseFloatingPointValue(value.getAsNumber()));
        case "str":
          return new ConstantExpression(value.getAsString());
        case "NoneType":
          return new ConstantExpression(null);
      }
      throw new IllegalArgumentException(
          String.format("Unsupported primitive type: %s (%s)", value, typename));
    }

    @Override
    public Object eval(Context context) {
      return value;
    }

    @Override
    public String toString() {
      return PyObjects.toRepr(value);
    }
  }

  public record StarredExpression(Expression value) implements Expression {}

  public record Comparison(Expression lhs, Op op, Expression rhs) implements Expression {
    public enum Op {
      IS("is"),
      IS_NOT("is not"),
      EQ("=="),
      LT("<"),
      LT_EQ("<="),
      GT(">"),
      GT_EQ(">="),
      NOT_EQ("!="),
      IN("in"),
      NOT_IN("not in");

      private final String symbol;

      Op(String symbol) {
        this.symbol = symbol;
      }

      public String symbol() {
        return symbol;
      }
    }

    public static Op parse(String opName) {
      switch (opName) {
        case "Is":
          return Op.IS;
        case "IsNot":
          return Op.IS_NOT;
        case "Eq":
          return Op.EQ;
        case "Lt":
          return Op.LT;
        case "LtE":
          return Op.LT_EQ;
        case "Gt":
          return Op.GT;
        case "GtE":
          return Op.GT_EQ;
        case "NotEq":
          return Op.NOT_EQ;
        case "In":
          return Op.IN;
        case "NotIn":
          return Op.NOT_IN;
        default:
          throw new UnsupportedOperationException("Unsupported comparison op: " + opName);
      }
    }

    @SuppressWarnings("unchecked")
    @Override
    public Object eval(Context context) {
      var lhsValue = lhs.eval(context);
      var rhsValue = rhs.eval(context);
      switch (op) {
        case IS:
          return lhsValue == rhsValue;
        case IS_NOT:
          return lhsValue != rhsValue;
        case EQ:
          if (lhsValue instanceof Number lhsNumber && rhsValue instanceof Number rhsNumber) {
            return Numbers.equals(lhsNumber, rhsNumber);
          } else {
            return lhsValue.equals(rhsValue);
          }
        case LT:
          if (lhsValue instanceof Number lhsNumber && rhsValue instanceof Number rhsNumber) {
            return Numbers.lessThan(lhsNumber, rhsNumber);
          } else if (lhsValue instanceof Comparable lhsComp
              && rhsValue instanceof Comparable rhsComp
              && lhsValue.getClass() == rhsValue.getClass()) {
            return lhsComp.compareTo(rhsComp) < 0;
          }
        case LT_EQ:
          if (lhsValue instanceof Number lhsNumber && rhsValue instanceof Number rhsNumber) {
            return Numbers.lessThanOrEquals(lhsNumber, rhsNumber);
          } else if (lhsValue instanceof Comparable lhsComp
              && rhsValue instanceof Comparable rhsComp
              && lhsValue.getClass() == rhsValue.getClass()) {
            return lhsComp.compareTo(rhsComp) <= 0;
          }
        case GT:
          if (lhsValue instanceof Number lhsNumber && rhsValue instanceof Number rhsNumber) {
            return Numbers.greaterThan(lhsNumber, rhsNumber);
          } else if (lhsValue instanceof Comparable lhsComp
              && rhsValue instanceof Comparable rhsComp
              && lhsValue.getClass() == rhsValue.getClass()) {
            return lhsComp.compareTo(rhsComp) > 0;
          }
        case GT_EQ:
          if (lhsValue instanceof Number lhsNumber && rhsValue instanceof Number rhsNumber) {
            return Numbers.greaterThanOrEquals(lhsNumber, rhsNumber);
          } else if (lhsValue instanceof Comparable lhsComp
              && rhsValue instanceof Comparable rhsComp
              && lhsValue.getClass() == rhsValue.getClass()) {
            return lhsComp.compareTo(rhsComp) >= 0;
          }
        case NOT_EQ:
          if (lhsValue instanceof Number lhsNumber && rhsValue instanceof Number rhsNumber) {
            return !Numbers.equals(lhsNumber, rhsNumber);
          } else {
            return !lhsValue.equals(rhsValue);
          }
        case IN:
          {
            var result = isIn(lhsValue, rhsValue);
            if (result.isPresent()) {
              return result.get();
            }
          }
        case NOT_IN:
          {
            var result = isIn(lhsValue, rhsValue);
            if (result.isPresent()) {
              return !result.get();
            }
          }
      }
      throw new UnsupportedOperationException(
          String.format("Comparison op not supported: %s %s %s", lhs, op.symbol(), rhs));
    }

    private static final Optional<Boolean> isIn(Object lhsValue, Object rhsValue) {
      if (rhsValue instanceof Collection<?> collection) {
        return Optional.of(collection.contains(lhsValue));
      }
      if (rhsValue instanceof ItemContainer container) {
        return Optional.of(container.__contains__(lhsValue));
      }
      if (rhsValue instanceof List list) {
        return Optional.of(list.contains(lhsValue));
      }
      if (rhsValue instanceof Map map) {
        return Optional.of(map.containsKey(lhsValue));
      }
      if (lhsValue instanceof String lhsStr && rhsValue instanceof String rhsStr) {
        return Optional.of(rhsStr.contains(lhsStr));
      }
      var promotedRhs = promoteArrayToTuple(rhsValue);
      if (promotedRhs instanceof Iterable<?> iter) {
        for (var item : iter) {
          if (item.equals(lhsValue)) {
            return Optional.of(true);
          }
        }
        return Optional.of(false);
      }
      return Optional.empty();
    }

    @Override
    public String toString() {
      return String.format("%s %s %s", lhs, op.symbol(), rhs);
    }
  }

  public record BoolOp(Op op, List<Expression> values) implements Expression {
    public enum Op {
      AND("and"),
      OR("or");

      private final String symbol;

      Op(String symbol) {
        this.symbol = symbol;
      }

      public String symbol() {
        return symbol;
      }
    }

    public static Op parse(String opName) {
      switch (opName) {
        case "And":
          return Op.AND;
        case "Or":
          return Op.OR;
        default:
          throw new UnsupportedOperationException("Unsupported bool op: " + opName);
      }
    }

    @Override
    public Object eval(Context context) {
      switch (op) {
        case AND:
          {
            Object result = null;
            for (var expr : values) {
              result = expr.eval(context);
              if (!convertToBool(result)) {
                break;
              }
            }
            return result;
          }

        case OR:
          {
            Object result = null;
            for (var expr : values) {
              result = expr.eval(context);
              if (convertToBool(result)) {
                break;
              }
            }
            return result;
          }
      }
      throw new UnsupportedOperationException("Boolean op not supported: " + toString());
    }

    @Override
    public String toString() {
      return values.stream().map(Object::toString).collect(joining(" " + op.symbol() + " "));
    }
  }

  public record UnaryOp(Op op, Expression operand) implements Expression {
    public enum Op {
      SUB("-"),
      NOT("not");

      private final String symbol;

      Op(String symbol) {
        this.symbol = symbol;
      }

      public String symbol() {
        return symbol;
      }
    }

    public static Op parse(String opName) {
      switch (opName) {
        // TODO(maxuser): Support "UAdd" (+) and "Invert" (~).
        case "USub":
          return Op.SUB;
        case "Not":
          return Op.NOT;
        default:
          throw new UnsupportedOperationException("Unsupported unary op: " + opName);
      }
    }

    @Override
    public Object eval(Context context) {
      var value = operand.eval(context);
      switch (op) {
        case SUB:
          if (value instanceof Number number) {
            return Numbers.negate(number);
          }
          break;
        case NOT:
          return !convertToBool(value);
      }
      throw new IllegalArgumentException(
          String.format(
              "bad operand type for unary %s: '%s' (%s)",
              op.symbol(), value.getClass().getName(), operand));
    }

    @Override
    public String toString() {
      return String.format("%s%s%s", op, op == Op.NOT ? " " : "", op.symbol(), operand);
    }
  }

  public record BinaryOp(Expression lhs, Op op, Expression rhs) implements Expression {
    public enum Op {
      ADD("+"),
      SUB("-"),
      MUL("*"),
      DIV("/"),
      POW("**"),
      MOD("%"),
      LSHIFT("<<"),
      RSHIFT(">>");

      private final String symbol;

      Op(String symbol) {
        this.symbol = symbol;
      }

      public String symbol() {
        return symbol;
      }
    }

    public static Op parse(String opName) {
      switch (opName) {
        case "Add":
          return Op.ADD;
        case "Sub":
          return Op.SUB;
        case "Mult":
          return Op.MUL;
        case "Div":
          return Op.DIV;
        case "Pow":
          return Op.POW;
        case "Mod":
          return Op.MOD;
        case "LShift":
          return Op.LSHIFT;
        case "RShift":
          return Op.RSHIFT;
        default:
          throw new UnsupportedOperationException("Unsupported binary op: " + opName);
      }
    }

    @Override
    public Object eval(Context context) {
      var lhsValue = lhs.eval(context);
      var rhsValue = rhs.eval(context);
      switch (op) {
        case ADD:
          if (lhsValue instanceof Number lhsNum && rhsValue instanceof Number rhsNum) {
            return Numbers.add(lhsNum, rhsNum);
          } else if (lhsValue instanceof String lhsString && rhsValue instanceof String rhsString) {
            return lhsString + rhsString;
          }
          if (lhsValue instanceof PyList pyList) {
            lhsValue = pyList.getJavaList();
          }
          if (rhsValue instanceof PyList pyList) {
            rhsValue = pyList.getJavaList();
          }
          if (lhsValue instanceof List lhsList && rhsValue instanceof List rhsList) {
            @SuppressWarnings("unchecked")
            var newList = new PyList(new ArrayList<Object>(lhsList));
            newList.__iadd__(rhsList);
            return newList;
          }
          break;
        case SUB:
          return Numbers.subtract((Number) lhsValue, (Number) rhsValue);
        case MUL:
          if (lhsValue instanceof String lhsString && rhsValue instanceof Integer rhsInt) {
            return lhsString.repeat(rhsInt);
          } else if (lhsValue instanceof Integer lhsInt && rhsValue instanceof String rhsString) {
            return rhsString.repeat(lhsInt);
          } else {
            return Numbers.multiply((Number) lhsValue, (Number) rhsValue);
          }
        case DIV:
          if (lhsValue instanceof Number lhsNum && rhsValue instanceof Number rhsNum) {
            return Numbers.divide(lhsNum, rhsNum);
          }
          break;
        case POW:
          if (lhsValue instanceof Number lhsNum && rhsValue instanceof Number rhsNum) {
            double d = Math.pow(lhsNum.doubleValue(), rhsNum.doubleValue());
            int i = (int) d;
            return i == d ? (Number) i : (Number) d;
          }
          break;
        case MOD:
          {
            if (lhsValue instanceof String lhsString) {
              if (rhsValue instanceof PyTuple tuple) {
                return String.format(
                    lhsString, StreamSupport.stream(tuple.spliterator(), false).toArray());
              } else {
                return String.format(lhsString, rhsValue);
              }
            } else if (lhsValue instanceof Number lhsNum && rhsValue instanceof Number rhsNum) {
              return Numbers.pyMod(lhsNum, rhsNum);
            }
            break;
          }
        case LSHIFT:
          return convertLongToIntIfFits(checkNumberAsLong(lhsValue) << checkNumberAsLong(rhsValue));
        case RSHIFT:
          return convertLongToIntIfFits(
              checkNumberAsLong(lhsValue) >>> checkNumberAsLong(rhsValue));
      }
      throw new UnsupportedOperationException(
          String.format(
              "Binary op not implemented for types `%s %s %s`: %s",
              lhsValue.getClass().getSimpleName(),
              op.symbol(),
              rhsValue.getClass().getSimpleName(),
              this));
    }

    private static long checkNumberAsLong(Object value) {
      if (value instanceof Number number && (number instanceof Long || number instanceof Integer)) {
        return number.longValue();
      } else {
        throw new IllegalArgumentException(
            "Unexpected operand type for bit-shift operation: " + value.getClass());
      }
    }

    private static Number convertLongToIntIfFits(Long longValue) {
      if (longValue > MAX_UNSIGNED_32_BIT_INTEGER || longValue < Integer.MIN_VALUE) {
        return longValue;
      } else {
        return longValue.intValue();
      }
    }

    @Override
    public String toString() {
      return String.format("(%s %s %s)", lhs, op.symbol(), rhs);
    }
  }

  private static final long MAX_UNSIGNED_32_BIT_INTEGER = 0xFFFFFFFFL;

  public record SliceExpression(
      Optional<Expression> lower, Optional<Expression> upper, Optional<Expression> step)
      implements Expression {
    @Override
    public Object eval(Context context) {
      try {
        return new SliceValue(
            lower.map(s -> (Integer) s.eval(context)),
            upper.map(s -> (Integer) s.eval(context)),
            step.map(s -> (Integer) s.eval(context)));
      } catch (ClassCastException e) {
        var string =
            Stream.of(lower, upper, step)
                .map(x -> x.map(Object::toString).orElse(""))
                .collect(joining(":", "[", "]"));
        throw new RuntimeException("Slice indices must be integers but got: %s".formatted(string));
      }
    }
  }

  public record SliceValue(
      Optional<Integer> lower, Optional<Integer> upper, Optional<Integer> step) {
    public ResolvedSliceIndices resolveIndices(int sequenceLength) {
      int normLower = lower.map(n -> n < 0 ? sequenceLength + n : n).orElse(0);
      int normUpper = upper.map(n -> n < 0 ? sequenceLength + n : n).orElse(sequenceLength);
      var indices = new ResolvedSliceIndices(normLower, normUpper, step.orElse(1));
      if (indices.step() != 1) {
        throw new IllegalArgumentException(
            "Slice steps other than 1 are not supported (got step=%d)".formatted(indices.step()));
      }
      return indices;
    }

    public static int resolveIndex(int i, int length) {
      return i < 0 ? length + i : i;
    }
  }

  /** Slice indices resolved for a particular length sequence to avoid negative or empty values. */
  public record ResolvedSliceIndices(int lower, int upper, int step) {
    public int length() {
      return upper - lower;
    }
  }

  public record ArrayIndex(Expression array, Expression index) implements Expression {
    @Override
    public Object eval(Context context) {
      var arrayValue = array.eval(context);
      var indexValue = index.eval(context);
      if (arrayValue == null || indexValue == null) {
        throw new NullPointerException(
            String.format("%s=%s, %s=%s in %s", array, arrayValue, index, indexValue, this));
      }

      if (arrayValue instanceof ItemGetter itemGetter) {
        return itemGetter.__getitem__(indexValue);
      } else if (arrayValue.getClass().isArray()) {
        if (indexValue instanceof SliceValue sliceValue) {
          var slice = sliceValue.resolveIndices(Array.getLength(arrayValue));
          Object copiedArray =
              Array.newInstance(arrayValue.getClass().getComponentType(), slice.length());
          System.arraycopy(arrayValue, slice.lower(), copiedArray, 0, slice.length());
          return copiedArray;
        } else {
          int intKey =
              SliceValue.resolveIndex(
                  ((Number) indexValue).intValue(), Array.getLength(arrayValue));
          return Array.get(arrayValue, intKey);
        }
      } else if (arrayValue instanceof List list) {
        if (indexValue instanceof SliceValue sliceValue) {
          var slice = sliceValue.resolveIndices(list.size());
          return list.subList(slice.lower(), slice.upper());
        } else {
          int intKey = SliceValue.resolveIndex(((Number) indexValue).intValue(), list.size());
          return list.get(intKey);
        }
      } else if (arrayValue instanceof Map map) {
        return map.get(indexValue);
      } else if (arrayValue instanceof String string) {
        if (indexValue instanceof SliceValue sliceValue) {
          var slice = sliceValue.resolveIndices(string.length());
          return string.substring(slice.lower(), slice.upper());
        } else {
          return String.valueOf(
              string.charAt(SliceValue.resolveIndex((Integer) indexValue, string.length())));
        }
      } else if (enableSimplifiedJsonSyntax && arrayValue instanceof JsonArray jsonArray) {
        if (indexValue instanceof SliceValue sliceValue) {
          var list = jsonArray.asList();
          var slice = sliceValue.resolveIndices(list.size());
          return list.subList(slice.lower(), slice.upper());
        } else {
          int intKey = SliceValue.resolveIndex(((Number) indexValue).intValue(), jsonArray.size());
          return unboxJsonPrimitive(jsonArray.get(intKey));
        }
      }

      throw new IllegalArgumentException(
          String.format(
              "Eval for ArrayIndex expression not supported for types: %s[%s] (evaluated as:"
                  + " %s[%s])",
              array, index, arrayValue, indexValue));
    }

    @Override
    public String toString() {
      return String.format("%s[%s]", array, index);
    }
  }

  public record IfExpression(Expression test, Expression body, Expression orElse)
      implements Expression {
    @Override
    public Object eval(Context context) {
      return convertToBool(test.eval(context)) ? body.eval(context) : orElse.eval(context);
    }

    @Override
    public String toString() {
      return String.format("%s if %s else %s", body, test, orElse);
    }
  }

  public static class InterpreterException extends RuntimeException {
    private final String shortMessage;

    public InterpreterException(String shortMessage, String fullMessage) {
      super(fullMessage);
      this.shortMessage = shortMessage;
    }

    /** Gets a short version of the error message suitable for space-constrained output. */
    public String getShortMessage() {
      return shortMessage;
    }
  }

  public static class TypeChecker {
    public static class Diagnostics {
      private final java.util.function.Function<String, String> toPrettyClassName;
      private final StringBuilder shortOutput = new StringBuilder();
      private final StringBuilder fullOutput = new StringBuilder();
      private int superclassTraversalDepth = 0;

      public Diagnostics(java.util.function.Function<String, String> toPrettyClassName) {
        this.toPrettyClassName = toPrettyClassName;
      }

      public void append(String message) {
        fullOutput.append(message);
        if (superclassTraversalDepth == 0) {
          shortOutput.append(message);
        }
      }

      public void startTraversingSuperclasses() {
        ++superclassTraversalDepth;
      }

      public void stopTraversingSuperclasses() {
        --superclassTraversalDepth;
      }

      public InterpreterException createTruncatedException() {
        String shortened = shortOutput.toString();
        return new InterpreterException(shortened, shortened);
      }

      public InterpreterException createException() {
        return new InterpreterException(shortOutput.toString(), fullOutput.toString());
      }

      /**
       * Generates a debug string representation of a method or constructor call.
       *
       * <p>Generates a string suitable for a constructor call if {@code methodName} is empty.
       * Otherwise generates a string for a method call.
       */
      public String getDebugInvocationString(
          String className, Optional<String> methodName, Object[] params) {
        String invocation =
            String.format(
                "%s%s(%s)",
                className,
                methodName.map(s -> "." + s).orElse(""),
                Arrays.stream(params)
                    .map(
                        v ->
                            v == null
                                ? "null"
                                : "(%s) %s".formatted(v.getClass().getName(), PyObjects.toRepr(v)))
                    .collect(joining(", ")));

        String invocationWithPrettyNames =
            String.format(
                "%s%s(%s)",
                toPrettyClassName.apply(className),
                methodName.map(s -> "." + s).orElse(""),
                Arrays.stream(params)
                    .map(
                        v ->
                            v == null
                                ? "null"
                                : "(%s) %s"
                                    .formatted(
                                        toPrettyClassName.apply(v.getClass().getName()),
                                        PyObjects.toRepr(v)))
                    .collect(joining(", ")));

        if (!invocation.equals(invocationWithPrettyNames)) {
          invocation += " [Mapped names: " + invocationWithPrettyNames + "]";
        }
        return invocation;
      }
    }

    public static Optional<Method> findBestMatchingMethod(
        Class<?> clss,
        boolean isStaticMethod,
        BiFunction<Class<?>, String, Set<String>> toRuntimeMethodNames,
        String methodName,
        Object[] paramValues,
        Diagnostics diagnostics) {
      if (diagnostics != null) {
        diagnostics.append("Resolving method call:\n  ");
        diagnostics.append(
            diagnostics.getDebugInvocationString(
                clss.getName(), Optional.of(methodName), paramValues));
        diagnostics.append("\n");
      }
      logger.log(
          "Searching '%s' for method named '%s' with param length %d",
          clss, methodName, paramValues.length);
      var bestMethod =
          findBestMatchingExecutable(
              clss,
              c -> {
                var names = toRuntimeMethodNames.apply(c, methodName);
                logger.log("  mapped '%s' method '%s' to: %s", c, methodName, names);
                if (diagnostics != null) {
                  if (names.size() != 1 || !names.contains(methodName)) {
                    diagnostics.append(
                        "- mapped '%s' method '%s' to %s\n".formatted(c, methodName, names));
                  }
                }
                return names;
              },
              Class<?>::getMethods,
              (method, runtimeMethodNames) ->
                  Modifier.isStatic(method.getModifiers()) == isStaticMethod
                      && runtimeMethodNames.contains(method.getName()),
              paramValues,
              /* traverseSuperclasses= */ true,
              diagnostics);
      if (diagnostics != null) {
        if (bestMethod.isEmpty()) {
          diagnostics.append("No matching method found in class, superclass, or interfaces:\n  ");
          diagnostics.append(
              diagnostics.getDebugInvocationString(
                  clss.getName(), Optional.of(methodName), paramValues));
        } else {
          diagnostics.append("Found matching method:\n  ");
          diagnostics.append(bestMethod.get().toString());
        }
      }
      return bestMethod;
    }

    public static Optional<Constructor<?>> findBestMatchingConstructor(
        Class<?> clss, Object[] paramValues, Diagnostics diagnostics) {
      if (diagnostics != null) {
        diagnostics.append("Resolving constructor call:\n  ");
        diagnostics.append(
            diagnostics.getDebugInvocationString(clss.getName(), Optional.empty(), paramValues));
        diagnostics.append("\n");
      }
      logger.log("Searching '%s' for ctor with param length %d", clss, paramValues.length);
      var bestCtor =
          findBestMatchingExecutable(
              clss,
              c -> null,
              Class<?>::getConstructors,
              (c, p) -> true,
              paramValues,
              /* traverseSuperclasses= */ false,
              diagnostics);
      if (diagnostics != null) {
        if (bestCtor.isEmpty()) {
          diagnostics.append("No matching constructor found:\n  ");
          diagnostics.append(
              diagnostics.getDebugInvocationString(clss.getName(), Optional.empty(), paramValues));
        } else {
          diagnostics.append("Found matching constructor:\n  ");
          diagnostics.append(bestCtor.get().toString());
        }
      }
      return bestCtor;
    }

    private static <T extends Executable, U> Optional<T> findBestMatchingExecutable(
        Class<?> clss,
        java.util.function.Function<Class<?>, U> perClassDataFactory,
        java.util.function.Function<Class<?>, T[]> executableGetter,
        BiPredicate<T, U> filter,
        Object[] paramValues,
        boolean traverseSuperclasses,
        Diagnostics diagnostics) {
      // TODO(maxuser): Generalize the restriction on class names and make it user-configurable.
      boolean isAccessibleClass = isPublic(clss) && !clss.getName().startsWith("sun.");

      if (isAccessibleClass) {
        U perClassData = perClassDataFactory.apply(clss);
        int bestScore = 0; // Zero means that no viable executable has been found.
        Optional<T> bestExecutable = Optional.empty();
        T[] executables = executableGetter.apply(clss);
        if (diagnostics != null) {
          diagnostics.append(
              "Options considered in '%s': %d\n".formatted(clss, executables.length));
        }
        int numAttributeMismatches = 0;
        for (T executable : executables) {
          if (filter.test(executable, perClassData)) {
            int score = getTypeCheckScore(executable.getParameterTypes(), paramValues);
            if (score > bestScore) {
              bestScore = score;
              bestExecutable = Optional.of(executable);
            }
            logger.log("    callable member of '%s' with score %d: %s", clss, score, executable);
            if (diagnostics != null) {
              if (score == 0) {
                diagnostics.append("- param mismatch: %s\n".formatted(executable));
              } else {
                diagnostics.append("- param match (score=%d): %s\n".formatted(score, executable));
              }
            }
          } else {
            ++numAttributeMismatches;
            if (diagnostics != null && executables.length < 5) {
              diagnostics.append("- name/static mismatch: %s\n".formatted(executable));
            }
            logger.log("    callable member of '%s' not viable: %s", clss, executable);
          }
        }
        if (diagnostics != null) {
          if (numAttributeMismatches > 0 && executables.length >= 5) {
            diagnostics.append(
                "- options with name/static mismatch: %d\n".formatted(numAttributeMismatches));
          }
          if (bestExecutable.isPresent()) {
            diagnostics.append("Best match: %s\n".formatted(bestExecutable.get()));
          }
        }
        if (bestExecutable.isPresent()) {
          return bestExecutable;
        }
      }

      if (traverseSuperclasses) {
        if (diagnostics != null) {
          diagnostics.startTraversingSuperclasses();
          if (clss.getInterfaces().length > 0 || clss.getSuperclass() != null) {
            diagnostics.append("Traversed superclasses of '%s'...\n".formatted(clss.getName()));
          }
        }
        try {
          for (var iface : clss.getInterfaces()) {
            logger.log("  searching interface '%s'", iface);
            var viableExecutable =
                findBestMatchingExecutable(
                    iface,
                    perClassDataFactory,
                    executableGetter,
                    filter,
                    paramValues,
                    true,
                    diagnostics);
            if (viableExecutable.isPresent()) {
              return viableExecutable;
            }
          }
          var superclass = clss.getSuperclass();
          if (superclass != null) {
            logger.log("  searching superclass '%s'", superclass);
            return findBestMatchingExecutable(
                superclass,
                perClassDataFactory,
                executableGetter,
                filter,
                paramValues,
                true,
                diagnostics);
          }
        } finally {
          if (diagnostics != null) {
            diagnostics.stopTraversingSuperclasses();
          }
        }
      }

      return Optional.empty();
    }

    private static final int PUBLIC_MODIFIER = 0x1;

    private static boolean isPublic(Class<?> clss) {
      // See defintion of modifiers in the JVM spec:
      // https://docs.oracle.com/javase/specs/jvms/se7/html/jvms-4.html#jvms-4.1-200-E.1
      return (clss.getModifiers() & PUBLIC_MODIFIER) != 0;
    }

    /**
     * Computes a score for how well {@code paramValues} matches the {@code formalParamTypes}.
     *
     * <p>Add 1 point for a param requiring conversion, 2 points for a param that's an exact match.
     * Return value of 0 indicates that {@code paramValues} are incompatible with {@code
     * formalParamTypes}.
     */
    private static int getTypeCheckScore(Class<?>[] formalParamTypes, Object[] paramValues) {
      if (formalParamTypes.length != paramValues.length) {
        return 0;
      }

      // Start score at 1 so it's non-zero if there's an exact match of no params.
      int score = 1;

      for (int i = 0; i < formalParamTypes.length; ++i) {
        Class<?> type = promotePrimitiveType(formalParamTypes[i]);
        Object value = paramValues[i];
        if (value == null) {
          // null is convertible to everything except primitive types.
          if (type != formalParamTypes[i]) {
            return 0;
          }
          if (type.isArray()) {
            score += 1;
          } else {
            score += 2;
          }
          continue;
        }
        Class<?> valueType = value.getClass();
        if (valueType == type) {
          score += 2;
          continue;
        }
        if (Number.class.isAssignableFrom(type)
            && Number.class.isAssignableFrom(valueType)
            && numericTypeIsConvertible(valueType, type)) {
          score += 1;
          continue;
        }
        // Allow implementations of Function to be passed to params expecting an interface, but
        // don't boost the score for this iffy conversion.
        if (Function.class.isAssignableFrom(valueType) && type.isInterface()) {
          continue;
        }
        if (!type.isAssignableFrom(value.getClass())) {
          return 0;
        }
      }
      return score;
    }

    private static Class<?> promotePrimitiveType(Class<?> type) {
      if (type == boolean.class) {
        return Boolean.class;
      } else if (type == int.class) {
        return Integer.class;
      } else if (type == long.class) {
        return Long.class;
      } else if (type == float.class) {
        return Float.class;
      } else if (type == double.class) {
        return Double.class;
      } else if (type == char.class) {
        return Character.class;
      } else {
        return type;
      }
    }

    private static boolean numericTypeIsConvertible(Class<?> from, Class<?> to) {
      if (to == Double.class) {
        return true;
      }
      if (to == Float.class) {
        return from != Double.class;
      }
      if (to == Long.class) {
        return from == Integer.class || from == Long.class;
      }
      if (to == Integer.class) {
        return from == Integer.class;
      }
      return false;
    }

    public String getDebugMethodCallString(
        SymbolCache symbolCache, Class<?> type, String methodName, Object[] params) {
      String method =
          String.format(
              "%s.%s(%s)",
              type.getName(),
              methodName,
              Arrays.stream(params)
                  .map(
                      v ->
                          v == null
                              ? "null"
                              : "(%s) %s".formatted(v.getClass().getName(), PyObjects.toRepr(v)))
                  .collect(joining(", ")));

      String methodWithPrettyNames =
          String.format(
              "%s.%s(%s)",
              symbolCache.getPrettyClassName(type.getName()),
              methodName,
              Arrays.stream(params)
                  .map(
                      v ->
                          v == null
                              ? "null"
                              : "(%s) %s"
                                  .formatted(
                                      symbolCache.getPrettyClassName(v.getClass().getName()),
                                      PyObjects.toRepr(v)))
                  .collect(joining(", ")));

      if (!method.equals(methodWithPrettyNames)) {
        method += " [Mapped names: " + methodWithPrettyNames + "]";
      }
      return method;
    }
  }

  public record ListComprehension(
      Expression transform, Expression target, Expression iter, List<Expression> ifs)
      implements Expression {
    @Override
    public Object eval(Context context) {
      var localContext = context.createLocalContext();
      var list = new PyList();
      // TODO(maxuser): Share portions of impl with ForBlock::exec.
      final Identifier loopVar;
      final TupleLiteral loopVars;
      if (target instanceof Identifier id) {
        loopVar = id;
        loopVars = null;
      } else if (target instanceof TupleLiteral tuple) {
        loopVar = null;
        loopVars = tuple;
      } else {
        throw new IllegalArgumentException("Unexpected loop variable type: " + target.toString());
      }
      outerLoop:
      for (var value : getIterable(iter.eval(localContext))) {
        if (loopVar != null) {
          localContext.setVariable(loopVar.name(), value);
        } else {
          Assignment.assignTuple(localContext, loopVars, value);
        }
        for (var ifExpr : ifs) {
          if (!convertToBool(ifExpr.eval(localContext))) {
            continue outerLoop;
          }
        }
        list.append(transform.eval(localContext));
      }
      return list;
    }

    @Override
    public String toString() {
      var out = new StringBuilder("[");
      out.append(transform.toString());
      out.append(" for ");
      out.append(target.toString());
      out.append(" in ");
      out.append(iter.toString());
      out.append(ifs.stream().map(i -> " if " + i.toString()).collect(joining()));
      out.append("]");
      return out.toString();
    }
  }

  public record TupleLiteral(List<Expression> elements) implements Expression {
    @Override
    public Object eval(Context context) {
      return new PyTuple(elements.stream().map(e -> e.eval(context)).toArray());
    }

    @Override
    public String toString() {
      return elements.size() == 1
          ? String.format("(%s,)", elements.get(0))
          : elements.stream().map(Object::toString).collect(joining(", ", "(", ")"));
    }
  }

  public record ListLiteral(List<Expression> elements) implements Expression {
    @Override
    public Object eval(Context context) {
      // Stream.toList() returns immutable list, so using Stream.collect(toList()) for mutable List.
      return new PyList(elements.stream().map(e -> e.eval(context)).collect(toList()));
    }

    @Override
    public String toString() {
      return elements.stream().map(Object::toString).collect(joining(", ", "[", "]"));
    }
  }

  public interface Lengthable {
    int __len__();
  }

  public interface ItemGetter extends Lengthable {
    Object __getitem__(Object key);

    default int resolveIndex(int i) {
      return SliceValue.resolveIndex(i, __len__());
    }
  }

  public interface ItemSetter {
    void __setitem__(Object key, Object value);
  }

  public interface ItemContainer {
    boolean __contains__(Object item);
  }

  public interface ItemDeleter {
    void __delitem__(Object key);
  }

  public static class PyList
      implements PyStreamable,
          Iterable<Object>,
          ItemGetter,
          ItemSetter,
          ItemContainer,
          ItemDeleter {
    private final List<Object> list;

    public PyList() {
      list = new ArrayList<>();
    }

    public PyList(List<Object> list) {
      this.list = list;
    }

    public List<Object> getJavaList() {
      return list;
    }

    @Override
    public boolean equals(Object value) {
      return value instanceof PyList pyList && this.list.equals(pyList.list);
    }

    @Override
    public Iterator<Object> iterator() {
      return list.iterator();
    }

    @Override
    public Stream<Object> stream() {
      return list.stream();
    }

    @Override
    public String toString() {
      return list.stream().map(PyObjects::toString).collect(joining(", ", "[", "]"));
    }

    public PyList __add__(Object value) {
      PyList newList = copy();
      if (value instanceof PyList pyList) {
        newList.list.addAll(pyList.list);
      } else if (value instanceof List<?> list) {
        newList.list.addAll(list);
      } else {
        throw new IllegalArgumentException(
            String.format(
                "can only concatenate list (not \"%s\") to list",
                value == null ? "None" : value.getClass().getName()));
      }
      return newList;
    }

    @Override
    public boolean __contains__(Object key) {
      return list.contains(key);
    }

    @Override
    public void __delitem__(Object key) {
      list.remove((int) (Integer) key);
    }

    public boolean __eq__(Object value) {
      return this.equals(value);
    }

    public boolean __ne__(Object value) {
      return !this.equals(value);
    }

    public void __iadd__(Object value) {
      if (value instanceof PyList pyList) {
        this.list.addAll(pyList.list);
      } else if (value instanceof List<?> list) {
        this.list.addAll(list);
      } else {
        throw new IllegalArgumentException(
            String.format(
                "can only concatenate list (not \"%s\") to list",
                value == null ? "None" : value.getClass().getName()));
      }
    }

    @Override
    public int __len__() {
      return list.size();
    }

    @Override
    public Object __getitem__(Object key) {
      if (key instanceof Integer i) {
        return list.get(SliceValue.resolveIndex(i, __len__()));
      } else if (key instanceof SliceValue sliceValue) {
        var slice = sliceValue.resolveIndices(list.size());
        // TODO(maxuser): SliceValue.step not supported.
        return new PyList(list.subList(slice.lower(), slice.upper()));
      }
      throw new IllegalArgumentException(
          String.format(
              "list indices must be integers or slices of integers, not %s (%s)",
              key.getClass().getName(), key));
    }

    // TODO(maxuser): Support slice notation.
    @Override
    public void __setitem__(Object key, Object value) {
      if (key instanceof Integer i) {
        list.set(i, value);
        return;
      }
      throw new IllegalArgumentException(
          String.format(
              "list indices must be integers or slices, not %s (%s)",
              key.getClass().getName(), key));
    }

    public void append(Object object) {
      list.add(object);
    }

    public void clear() {
      list.clear();
    }

    public PyList copy() {
      return new PyList(new ArrayList<>(list));
    }

    public long count(Object value) {
      return list.stream().filter(o -> o.equals(value)).count();
    }

    public void extend(Iterable<?> iterable) {
      for (var value : iterable) {
        list.add(value);
      }
    }

    public int index(Object value) {
      int index = list.indexOf(value);
      if (index == -1) {
        throw new NoSuchElementException(String.format("%s is not in list", value));
      }
      return index;
    }

    public void insert(int index, Object object) {
      list.add(index, object);
    }

    public Object pop() {
      return list.remove(list.size() - 1);
    }

    public Object pop(int index) {
      return list.remove(index);
    }

    public void remove(Object value) {
      pop(index(value));
    }

    public void reverse() {
      Collections.reverse(list);
    }

    public void sort() {
      list.sort(null);
    }
  }

  public interface PyStreamable {
    Stream<Object> stream();
  }

  // TODO(maxuser): Enforce immutability of tuples so that `t[0] = 0` is illegal.
  public static class PyTuple implements PyStreamable, Iterable<Object>, ItemGetter, ItemContainer {
    private final Object[] array;

    public PyTuple(Object[] array) {
      this.array = array;
    }

    public Object[] getJavaArray() {
      return array;
    }

    @Override
    public boolean equals(Object value) {
      return value instanceof PyTuple pyTuple && Arrays.equals(this.array, pyTuple.array);
    }

    @Override
    public int hashCode() {
      return Arrays.hashCode(array);
    }

    @Override
    public Stream<Object> stream() {
      return Arrays.stream(array);
    }

    @Override
    public Iterator<Object> iterator() {
      return Arrays.stream(array).iterator();
    }

    @Override
    public String toString() {
      return array.length == 1
          ? String.format("(%s,)", PyObjects.toString(array[0]))
          : Arrays.stream(array).map(PyObjects::toString).collect(joining(", ", "(", ")"));
    }

    public PyTuple __add__(Object value) {
      if (value instanceof PyTuple tuple) {
        return new PyTuple(
            Stream.concat(Arrays.stream(array), Arrays.stream(tuple.array)).toArray());
      }
      throw new IllegalArgumentException(
          String.format(
              "Can only concatenate tuple (not \"%s\") to tuple", value.getClass().getName()));
    }

    @Override
    public boolean __contains__(Object key) {
      return Arrays.asList(array).contains(key);
    }

    public boolean __eq__(Object value) {
      return this.equals(value);
    }

    public boolean __ne__(Object value) {
      return !this.equals(value);
    }

    @Override
    public int __len__() {
      return array.length;
    }

    @Override
    public Object __getitem__(Object key) {
      if (key instanceof Integer i) {
        return array[i];
      } else if (key instanceof SliceValue sliceValue) {
        var slice = sliceValue.resolveIndices(array.length);
        // TODO(maxuser): SliceValue.step not supported.
        return new PyTuple(Arrays.copyOfRange(array, slice.lower(), slice.upper()));
      }
      throw new IllegalArgumentException(
          String.format(
              "Tuple indices must be integers or slices, not %s (%s)",
              key.getClass().getName(), key));
    }

    public long count(Object value) {
      return Arrays.stream(array).filter(o -> o.equals(value)).count();
    }

    public int index(Object value) {
      for (int i = 0; i < array.length; ++i) {
        if (array[i].equals(value)) {
          return i;
        }
      }
      throw new IllegalArgumentException(
          String.format("tuple.index(%s): value not in tuple", value));
    }
  }

  public record DictLiteral(List<Expression> keys, List<Expression> values) implements Expression {
    public DictLiteral {
      if (keys.size() != values.size()) {
        throw new IllegalArgumentException(
            String.format(
                "Size mismatch between keys and values: %d and %d", keys.size(), values.size()));
      }
    }

    @Override
    public Object eval(Context context) {
      var map = new HashMap<Object, Object>();
      for (int i = 0; i < keys.size(); ++i) {
        map.put(keys.get(i).eval(context), values.get(i).eval(context));
      }
      return new PyDict(map);
    }

    @Override
    public String toString() {
      var out = new StringBuilder("{");
      for (int i = 0; i < keys.size(); ++i) {
        if (i > 0) {
          out.append(", ");
        }
        out.append(keys.get(i));
        out.append(": ");
        out.append(values.get(i));
      }
      out.append("}");
      return out.toString();
    }
  }

  public record Lambda(
      List<FunctionArg> args, Expression body, int lineno, AtomicInteger zombieCounter)
      implements Expression {
    public Lambda(List<FunctionArg> args, Expression body, int lineno) {
      this(args, body, lineno, new AtomicInteger());
    }

    @Override
    public Object eval(Context context) {
      return createFunction(context);
    }

    private Function createFunction(Context enclosingContext) {
      return (env, params) -> {
        if (enclosingContext.env().halted()) {
          // TODO(maxuser): Clear lambda's internal state to avoid memory leak of the entire script.
          var script = (Script) enclosingContext.env().getVariable("__script__");
          script.zombieCallbackHandler.handle(
              script.mainModule().filename(),
              "lambda from line " + lineno,
              zombieCounter.incrementAndGet());
          return null;
        }

        if (args.size() != params.length) {
          throw new IllegalArgumentException(
              String.format(
                  "Invoking lambda with %d args but %d required", params.length, args.size()));
        }

        var localContext = enclosingContext.createLocalContext();
        for (int i = 0; i < args.size(); ++i) {
          var arg = args.get(i);
          var argValue = params[i];
          localContext.setVariable(arg.identifier().name(), argValue);
        }
        return body.eval(localContext);
      };
    }

    @Override
    public String toString() {
      return String.format(
          "lambda(%s): %s",
          args.stream().map(a -> a.identifier().name()).collect(joining(", ")), body);
    }
  }

  public record FormattedString(List<Expression> values) implements Expression {
    @Override
    public Object eval(Context context) {
      return values.stream().map(v -> PyObjects.toString(v.eval(context))).collect(joining());
    }

    @Override
    public String toString() {
      return String.format(
          "f\"%s\"",
          values.stream()
              .map(
                  v ->
                      v instanceof ConstantExpression constExpr
                              && constExpr.value() instanceof String strValue
                          ? strValue
                          : String.format("{%s}", v))
              .collect(joining()));
    }
  }

  public static class PyDict
      implements Iterable<Object>, ItemGetter, ItemSetter, ItemContainer, ItemDeleter {
    private static final Object NOT_FOUND = new Object();
    private final Map<Object, Object> map;

    public PyDict() {
      map = new HashMap<>();
    }

    public PyDict(Map<Object, Object> map) {
      this.map = map;
    }

    public Map<Object, Object> getJavaMap() {
      return map;
    }

    @Override
    public boolean equals(Object value) {
      return value instanceof PyDict pyDict && this.map.equals(pyDict.map);
    }

    @Override
    public Iterator<Object> iterator() {
      return map.keySet().iterator();
    }

    public Iterable<PyTuple> items() {
      return map.entrySet().stream().map(e -> new PyTuple(new Object[] {e.getKey(), e.getValue()}))
          ::iterator;
    }

    public Iterable<Object> keys() {
      return map.keySet();
    }

    public Iterable<Object> values() {
      return map.values();
    }

    @Override
    public int __len__() {
      return map.size();
    }

    public Object get(Object key) {
      return map.get(key);
    }

    public Object get(Object key, Object defaultValue) {
      return map.getOrDefault(key, defaultValue);
    }

    public Object setdefault(Object key) {
      return setdefault(key, null);
    }

    public Object setdefault(Object key, Object defaultValue) {
      if (map.containsKey(key)) {
        return map.get(key);
      } else {
        map.put(key, defaultValue);
        return defaultValue;
      }
    }

    @Override
    public Object __getitem__(Object key) {
      var value = map.getOrDefault(key, NOT_FOUND);
      if (value == NOT_FOUND) {
        throw new NoSuchElementException("Key not found: " + PyObjects.toString(key));
      }
      return value;
    }

    @Override
    public void __setitem__(Object key, Object value) {
      map.put(key, value);
    }

    @Override
    public boolean __contains__(Object key) {
      return map.containsKey(key);
    }

    @Override
    public void __delitem__(Object key) {
      map.remove(key);
    }

    @Override
    public String toString() {
      var out = new StringBuilder("{");
      boolean firstEntry = true;
      for (var entry : map.entrySet()) {
        if (!firstEntry) {
          out.append(", ");
        }
        out.append(PyObjects.toString(entry.getKey()));
        out.append(": ");
        out.append(PyObjects.toString(entry.getValue()));
        firstEntry = false;
      }
      out.append("}");
      return out.toString();
    }
  }

  public record JavaClassKeyword() implements Expression {
    @Override
    public Object eval(Context context) {
      throw new UnsupportedOperationException("JavaClass can be called but not evaluated");
    }

    @Override
    public String toString() {
      return "JavaClass";
    }
  }

  public record JavaClassCall(String name, SymbolCache symbolCache, ClassLoader classLoader)
      implements Expression {
    @Override
    public Object eval(Context context) {
      final Class<?> type;
      try {
        type = classLoader.loadClass(symbolCache.getRuntimeClassName(name));
      } catch (ClassNotFoundException e) {
        throw new IllegalArgumentException(e);
      }
      return getJavaClass(type);
    }
  }

  public record JavaClass(Class<?> type) implements Function {
    @Override
    public String toString() {
      return String.format("JavaClass(\"%s\")", type.getName());
    }

    @Override
    public Object call(Environment env, Object... params) {
      var type = type();

      // Treat calls on an interface as a "cast" that attempts to promote params[0] from a
      // Script.Function to a proxy for this interface.
      if (type.isInterface()) {
        if (params.length != 1) {
          throw new IllegalArgumentException(
              "Calling interface %s with %d params but expected 1"
                  .formatted(type.getName(), params.length));
        }
        var param = params[0];
        if (param instanceof Function function) {
          return InterfaceProxy.promoteFunctionToJavaInterface(env, type, function);
        } else {
          throw new IllegalArgumentException(
              "Calling interface %s with non-function param of type %s"
                  .formatted(type.getName(), param.getClass().getName()));
        }
      }

      Constructor<?> ctor = env.findConstructor(type(), params);
      try {
        InterfaceProxy.promoteFunctionalParams(env, ctor, params);
        return ctor.newInstance(params);
      } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
        throw new RuntimeException(e);
      }
    }
  }

  public interface Function {
    Object call(Environment env, Object... params);

    static void expectNumParams(Object[] params, int n, Object message) {
      if (params.length != n) {
        throw new IllegalArgumentException(
            String.format(
                "Expected %d params but got %d for function: %s", n, params.length, message));
      }
    }

    default void expectMinParams(Object[] params, int n) {
      if (params.length < n) {
        throw new IllegalArgumentException(
            String.format(
                "Expected at least %d param%s but got %d for function: %s",
                n, n == 1 ? "" : "s", params.length, this));
      }
    }

    default void expectMaxParams(Object[] params, int n) {
      if (params.length > n) {
        throw new IllegalArgumentException(
            String.format(
                "Expected at most %d param%s but got %d for function: %s",
                n, n == 1 ? "" : "s", params.length, this));
      }
    }

    default void expectNumParams(Object[] params, int n) {
      if (params.length != n) {
        throw new IllegalArgumentException(
            String.format(
                "Expected %d params but got %d for function: %s", n, params.length, this));
      }
    }
  }

  public static class GlobalsFunction implements Function {
    public static final GlobalsFunction INSTANCE = new GlobalsFunction();

    @Override
    public Object call(Environment env, Object... params) {
      expectNumParams(params, 0);
      return env.vars();
    }
  }

  public static class HexFunction implements Function {
    public static final HexFunction INSTANCE = new HexFunction();

    @Override
    public Object call(Environment env, Object... params) {
      expectNumParams(params, 1);
      var value = params[0];

      if (value instanceof Byte b) {
        return String.format("0x%02x", b);
      }
      if (value instanceof Short s) {
        return String.format("0x%04x", s);
      }
      if (value instanceof Integer i) {
        return "0x" + Integer.toHexString(i);
      }
      if (value instanceof Long l) {
        if (l > MAX_UNSIGNED_32_BIT_INTEGER || l < Integer.MIN_VALUE) {
          return "0x" + Long.toHexString(l);
        } else {
          return "0x" + Integer.toHexString(l.intValue());
        }
      }
      throw new IllegalArgumentException(
          "'%s' of type '%s' cannot be interpreted as integer for hex()"
              .formatted(value, value.getClass().getName()));
    }
  }

  public static class IntFunction implements Function {
    public static final IntFunction INSTANCE = new IntFunction();

    @Override
    public Object call(Environment env, Object... params) {
      expectNumParams(params, 1);
      var value = params[0];
      if (value instanceof String string) {
        return parseIntegralValue(Long.parseLong(string));
      } else {
        return parseIntegralValue((Number) value);
      }
    }
  }

  public static class FloatFunction implements Function {
    public static final FloatFunction INSTANCE = new FloatFunction();

    @Override
    public Object call(Environment env, Object... params) {
      expectNumParams(params, 1);
      var value = params[0];
      if (value instanceof String string) {
        return parseFloatingPointValue(Double.parseDouble(string));
      } else {
        return parseFloatingPointValue((Number) value);
      }
    }
  }

  public static class StrFunction implements Function {
    public static final StrFunction INSTANCE = new StrFunction();

    @Override
    public Object call(Environment env, Object... params) {
      expectNumParams(params, 1);
      return PyObjects.toString(params[0]);
    }
  }

  public static class BoolFunction implements Function {
    public static final BoolFunction INSTANCE = new BoolFunction();

    @Override
    public Object call(Environment env, Object... params) {
      expectNumParams(params, 1);
      return convertToBool(params[0]);
    }
  }

  public static class LenFunction implements Function {
    public static final LenFunction INSTANCE = new LenFunction();

    @Override
    public Object call(Environment env, Object... params) {
      expectNumParams(params, 1);
      var value = params[0];
      if (value.getClass().isArray()) {
        return Array.getLength(value);
      } else if (value instanceof Lengthable lengthable) {
        return lengthable.__len__();
      } else if (value instanceof Collection<?> collection) {
        return collection.size();
      } else if (value instanceof Map map) {
        return map.size();
      } else if (value instanceof String str) {
        return str.length();
      } else if (value instanceof JsonArray arr) {
        return arr.size();
      }
      throw new IllegalArgumentException(
          String.format("Object of type '%s' has no len(): %s", value.getClass().getName(), this));
    }
  }

  public static class TupleFunction implements Function {
    public static final TupleFunction INSTANCE = new TupleFunction();

    @Override
    public Object call(Environment env, Object... params) {
      expectMaxParams(params, 1);
      if (params.length == 0) {
        return new PyTuple(new Object[] {});
      } else {
        Iterable<?> iterable = getIterable(params[0]);
        return new PyTuple(StreamSupport.stream(iterable.spliterator(), false).toArray());
      }
    }
  }

  public static class ListFunction implements Function {
    public static final ListFunction INSTANCE = new ListFunction();

    @Override
    public Object call(Environment env, Object... params) {
      expectMaxParams(params, 1);
      if (params.length == 0) {
        return new PyList();
      } else {
        @SuppressWarnings("unchecked")
        Iterable<Object> iterable = (Iterable<Object>) getIterable(params[0]);
        // Stream.toList() returns immutable list, so using Stream.collect(toList()) for mutable
        // List.
        return new PyList(StreamSupport.stream(iterable.spliterator(), false).collect(toList()));
      }
    }
  }

  public static class TracebackFormatStackFunction implements Function {
    public static final TracebackFormatStackFunction INSTANCE = new TracebackFormatStackFunction();

    @Override
    public Object call(Environment env, Object... params) {
      expectNumParams(params, 0);
      var globals = (GlobalContext) env;
      return new PyList(globals.getCallStack().stream().map(c -> (Object) c.toString()).toList());
    }
  }

  public static class PrintFunction implements Function {
    public static final PrintFunction INSTANCE = new PrintFunction();

    @Override
    public Object call(Environment env, Object... params) {
      int numParams = params.length;
      var kwargs = (numParams > 0 && params[numParams - 1] instanceof KeywordArgs k) ? k : null;
      var script = (Script) env.getVariable("__script__");
      final Consumer<String> out;
      if (kwargs == null) {
        out = script.stdout;
      } else {
        --numParams; // Ignore the final arg when it's kwargs.
        var file = kwargs.get("file");
        if (file == null) {
          out = script.stdout;
        } else if (file instanceof PrintStream printer) {
          if (printer == System.out) {
            out = script.stdout;
          } else if (printer == System.err) {
            out = script.stderr;
          } else {
            out = printer::println;
          }
        } else {
          throw new IllegalArgumentException(
              "'%s' object passed as 'file' keyword arg to 'print()' does not implement java.io.PrintStream"
                  .formatted(file.getClass().getName()));
        }
      }

      if (out != null) {
        out.accept(
            Arrays.stream(params, 0, numParams).map(PyObjects::toString).collect(joining(" ")));
      }
      return null;
    }
  }

  public record TypeFunction() implements Function {
    public static final TypeFunction INSTANCE = new TypeFunction();

    @Override
    public Object call(Environment env, Object... params) {
      expectNumParams(params, 1);
      var value = params[0];
      if (value instanceof JavaClass classId) {
        return classId.type();
      } else if (value instanceof PyObject pyObject) {
        return pyObject == PyClass.CLASS_TYPE ? PyClass.CLASS_TYPE : pyObject.type;
      } else {
        var type = value.getClass();
        return getJavaClass(type);
      }
    }
  }

  public static class RangeFunction implements Function {
    public static final RangeFunction INSTANCE = new RangeFunction();

    @Override
    public Object call(Environment env, Object... params) {
      return new RangeIterable(params);
    }
  }

  public static class EnumerateFunction implements Function {
    public static final EnumerateFunction INSTANCE = new EnumerateFunction();

    @Override
    public Object call(Environment env, Object... params) {
      if (params.length == 0 || params.length > 2) {
        throw new IllegalArgumentException(
            "Expected 1 or 2 params but got %d for function: enumerate".formatted(params.length));
      }
      int start = params.length > 1 ? (Integer) params[1] : 0;
      return new EnumerateIterable(getIterable(params[0]), start);
    }
  }

  public static class ExitFunction implements Function {
    public static final ExitFunction INSTANCE = new ExitFunction();

    @Override
    public Object call(Environment env, Object... params) {
      expectMinParams(params, 0);
      expectMaxParams(params, 1);
      if (env.getVariable("__script__") instanceof Script script) {
        int status =
            (params.length == 1 && params[0] != null) ? ((Number) params[0]).intValue() : 0;
        script.exit(status);
      }
      return null;
    }
  }

  public static Iterable<?> getIterable(Object object) {
    object = promoteArrayToTuple(object);
    if (object instanceof String string) {
      return new IterableString(string);
    } else {
      return (Iterable<?>) object;
    }
  }

  /**
   * Promotes {@code object} to {@code PyTuple} if it's an array, or else returns {@code object}.
   */
  public static Object promoteArrayToTuple(Object object) {
    if (object.getClass().isArray()) {
      if (object instanceof Object[] objectArray) {
        return new PyTuple(objectArray);
      } else {
        int length = Array.getLength(object);
        Object[] array = new Object[length];
        for (int i = 0; i < length; i++) {
          array[i] = Array.get(object, i);
        }
        return new PyTuple(array);
      }
    } else {
      return object;
    }
  }

  public record IterableString(String string) implements Iterable<String> {
    @Override
    public Iterator<String> iterator() {
      var list = new ArrayList<String>();
      for (int i = 0; i < string.length(); ++i) {
        list.add(String.valueOf(string.charAt(i)));
      }
      return list.iterator();
    }
  }

  public static class AbsFunction implements Function {
    public static final AbsFunction INSTANCE = new AbsFunction();

    @Override
    public Object call(Environment env, Object... params) {
      expectNumParams(params, 1);
      var num = (Number) params[0];
      return num.doubleValue() > 0. ? num : Numbers.negate(num);
    }
  }

  public static class RoundFunction implements Function {
    public static final RoundFunction INSTANCE = new RoundFunction();

    @Override
    public Object call(Environment env, Object... params) {
      expectNumParams(params, 1);
      var num = (Number) params[0];
      return Math.round(num.floatValue());
    }
  }

  public static class MinFunction implements Function {
    public static final MinFunction INSTANCE = new MinFunction();

    @Override
    public Object call(Environment env, Object... params) {
      if (params.length == 0) {
        throw new IllegalArgumentException("min expected at least 1 argument, got 0");
      }
      var currentMin = (Number) params[0];
      for (var value : params) {
        var num = (Number) value;
        if (Numbers.lessThan(num, currentMin)) {
          currentMin = num;
        }
      }
      return currentMin;
    }
  }

  public static class MaxFunction implements Function {
    public static final MaxFunction INSTANCE = new MaxFunction();

    @Override
    public Object call(Environment env, Object... params) {
      if (params.length == 0) {
        throw new IllegalArgumentException("max expected at least 1 argument, got 0");
      }
      var currentMax = (Number) params[0];
      for (var value : params) {
        var num = (Number) value;
        if (Numbers.greaterThan(num, currentMax)) {
          currentMax = num;
        }
      }
      return currentMax;
    }
  }

  public static class OrdFunction implements Function {
    public static final OrdFunction INSTANCE = new OrdFunction();

    @Override
    public Object call(Environment env, Object... params) {
      expectNumParams(params, 1);
      if (params[0] instanceof String string && string.length() == 1) {
        return (int) string.charAt(0);
      } else {
        throw new IllegalArgumentException(
            "ord() expected string of length 1, but got %s".formatted(params[0]));
      }
    }
  }

  public static class ChrFunction implements Function {
    public static final ChrFunction INSTANCE = new ChrFunction();

    @Override
    public Object call(Environment env, Object... params) {
      expectNumParams(params, 1);
      if (params[0] instanceof Integer codePointInteger) {
        int codePoint = codePointInteger;
        if (codePoint < 0 || codePoint > Character.MAX_CODE_POINT) {
          throw new IllegalArgumentException("chr(): Invalid code point: " + codePoint);
        }

        if (Character.isBmpCodePoint(codePoint)) {
          return String.valueOf((char) codePoint);
        } else {
          return String.valueOf(Character.toChars(codePoint));
        }
      } else {
        throw new IllegalArgumentException(
            "chr() requires an integer but got %s".formatted(params[0]));
      }
    }
  }

  private record KeywordArg(String name, Expression value) {}

  /**
   * Trivial subclass of HashMap for keyword args.
   *
   * <p>Implementations of Function::call can check the last param for whether its runtime type is
   * {@code KeywordArgs}.
   */
  public static class KeywordArgs extends HashMap<String, Object> {}

  public record FunctionCall(
      String filename,
      int lineno,
      Expression method,
      List<Expression> params,
      List<KeywordArg> kwargs)
      implements Expression {
    @Override
    public Object eval(Context context) {
      var caller = method.eval(context);
      // Stream.toList() returns immutable list, so using Stream.collect(toList()) for mutable List.
      List<Object> paramValues = new ArrayList<>();
      for (Expression param : params) {
        if (param instanceof StarredExpression starred) {
          Object value = starred.value().eval(context);
          if (value == null) {
            throw new IllegalArgumentException("argument after * must be an iterable, not null");
          }
          value = promoteArrayToTuple(value);
          if (value instanceof Iterable<?> iterable) {
            for (Object element : iterable) {
              paramValues.add(element);
            }
          } else {
            throw new IllegalArgumentException(
                "argument after * must be an iterable, not " + value.getClass().getName());
          }
        } else {
          paramValues.add(param.eval(context));
        }
      }
      if (!kwargs.isEmpty()) {
        var kwargsMap = new KeywordArgs();
        for (var kwarg : kwargs) {
          kwargsMap.put(kwarg.name(), kwarg.value().eval(context));
        }
        paramValues.add(kwargsMap);
      }
      if (caller instanceof Function function) {
        try {
          context.enterFunction(filename, lineno);
          return function.call(context.env(), paramValues.toArray(Object[]::new));
        } finally {
          context.leaveFunction();
        }
      }

      throw new IllegalArgumentException(
          String.format(
              "'%s' is not callable: %s",
              caller == null ? "NoneType" : caller.getClass().getName(), method));
    }

    @Override
    public String toString() {
      return String.format(
          "%s(%s)", method, params.stream().map(Object::toString).collect(joining(", ")));
    }
  }

  public static class RangeIterable implements Iterable<Integer> {
    private final int start;
    private final int stop;
    private final int step;

    public RangeIterable(Object[] params) {
      switch (params.length) {
        case 1:
          start = 0;
          stop = ((Number) params[0]).intValue();
          step = 1;
          break;
        case 2:
          start = ((Number) params[0]).intValue();
          stop = ((Number) params[1]).intValue();
          step = 1;
          break;
        case 3:
          start = ((Number) params[0]).intValue();
          stop = ((Number) params[1]).intValue();
          step = ((Number) params[2]).intValue();
          break;
        default:
          throw new IllegalArgumentException(
              "range expected 1 to 3 arguments, got " + params.length);
      }
    }

    public Iterator<Integer> iterator() {
      return new Iterator<Integer>() {
        private int pos = start;

        @Override
        public boolean hasNext() {
          return pos < stop;
        }

        @Override
        public Integer next() {
          int n = pos;
          pos += step;
          return n;
        }
      };
    }
  }

  public static class EnumerateIterable implements Iterable<PyTuple> {
    private final Iterable<?> iterable;
    private final int start;

    public EnumerateIterable(Iterable<?> iterable, int start) {
      this.iterable = iterable;
      this.start = start;
    }

    public Iterator<PyTuple> iterator() {
      var iter = iterable.iterator();
      return new Iterator<PyTuple>() {
        private int pos = start;

        @Override
        public boolean hasNext() {
          return iter.hasNext();
        }

        @Override
        public PyTuple next() {
          var next = iter.next();
          return new PyTuple(new Object[] {pos++, next});
        }
      };
    }
  }

  public record BoundMethodExpression(
      Expression object, Identifier methodId, SymbolCache symbolCache) implements Expression {
    @Override
    public Object eval(Context context) {
      return new BoundMethod(object.eval(context), methodId.name(), symbolCache, object);
    }

    @Override
    public String toString() {
      return String.format("%s.%s", object, methodId);
    }
  }

  public record BoundMethod(
      Object object, String methodName, SymbolCache symbolCache, Expression objectExpression)
      implements Function {
    @Override
    public Object call(Environment env, Object... params) {
      Object[] pyObjectMethodResult;
      if (object instanceof PyObject pyObject
          && (pyObjectMethodResult = pyObject.callMethod(env, methodName, params)).length == 1) {
        return pyObjectMethodResult[0];
      }

      final boolean isStaticMethod;
      final Class<?> clss;
      if (object instanceof JavaClass classId) {
        isStaticMethod = true;
        clss = classId.type();
      } else {
        if (object == null) {
          throw new NullPointerException(
              "Cannot invoke method \"%s.%s()\" because \"%s\" is null"
                  .formatted(objectExpression, methodName, objectExpression));
        }
        isStaticMethod = false;
        clss = object.getClass();
      }

      Object[] mappedParams = mapMethodParams(clss, isStaticMethod, methodName, params);
      String mappedMethodName = mapMethodName(clss, isStaticMethod, methodName);
      var cacheKey = ExecutableCacheKey.forMethod(clss, isStaticMethod, methodName, mappedParams);
      Optional<Method> matchedMethod =
          symbolCache
              .getExecutable(
                  cacheKey,
                  ignoreKey ->
                      TypeChecker.findBestMatchingMethod(
                              clss,
                              isStaticMethod,
                              symbolCache::getRuntimeMethodNames,
                              mappedMethodName,
                              mappedParams,
                              /* diagnostics= */ null)
                          .map(Executable.class::cast))
              .map(Method.class::cast);
      if (matchedMethod.isPresent()) {
        InterfaceProxy.promoteFunctionalParams(env, matchedMethod.get(), mappedParams);
        try {
          return matchedMethod.get().invoke(isStaticMethod ? null : object, mappedParams);
        } catch (IllegalAccessException | InvocationTargetException e) {
          throw new RuntimeException(e);
        }
      } else if (object instanceof String delimiter
          && !isStaticMethod
          && methodName.equals("join")
          && params.length == 1
          && params[0] instanceof Iterable<?> sequence) {
        // TODO(maxuser): Find a better way to support str methods.
        return strJoin(delimiter, sequence);
      } else {
        // Re-run type checker with the same args but with error diagnostics for creating exception.
        var diagnostics = new TypeChecker.Diagnostics(symbolCache::getPrettyClassName);
        TypeChecker.findBestMatchingMethod(
            clss,
            isStaticMethod,
            symbolCache::getRuntimeMethodNames,
            mappedMethodName,
            mappedParams,
            diagnostics);
        throw diagnostics.createException();
      }
    }
  }

  private static String strJoin(String delimiter, Iterable<?> sequence) {
    var out = new StringBuilder();
    int i = 0;
    for (var element : sequence) {
      if (i > 0) {
        out.append(delimiter);
      }
      if (element instanceof String str) {
        out.append(str);
      } else {
        throw new IllegalArgumentException(
            "sequence item %d: expected str instance, %s found"
                .formatted(i, element.getClass().getName()));
      }
      ++i;
    }
    return out.toString();
  }

  private static Object[] mapMethodParams(
      Class<?> clss, boolean isStaticMethod, String methodName, Object[] params) {
    if (clss == String.class && !isStaticMethod) {
      if (methodName.equals("split") && params.length == 0) {
        return new Object[] {"\\s+"};
      }
    }
    return params;
  }

  private static String mapMethodName(Class<?> clss, boolean isStaticMethod, String methodName) {
    if (clss == String.class && !isStaticMethod) {
      if (methodName.equals("startswith")) {
        return "startsWith";
      } else if (methodName.equals("endswith")) {
        return "endsWith";
      }
    }
    return methodName;
  }

  public static class InterfaceProxy implements InvocationHandler {

    private static final Object[] EMPTY_ARGS = new Object[0];
    private final Environment env;
    private final Function function;
    private final boolean multipleMethods;

    private InterfaceProxy(Environment env, Function function, boolean multipleMethods) {
      this.env = env;
      this.function = function;
      this.multipleMethods = multipleMethods;
    }

    /**
     * Create a proxy for {@code iface} interface that proxies to {@code function}.
     *
     * @param iface
     * @param function
     */
    public static Object implement(Environment env, Class<?> iface, Function function) {
      return Proxy.newProxyInstance(
          iface.getClassLoader(),
          new Class<?>[] {iface},
          new InterfaceProxy(env, function, numAbstractMethods(iface) > 1));
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
      if (args == null) {
        args = EMPTY_ARGS;
      }
      if (multipleMethods) {
        return function.call(env, method, args);
      } else {
        return function.call(env, args);
      }
    }

    public static void promoteFunctionalParams(
        Environment env, Executable executable, Object[] params) {
      for (int i = 0; i < params.length; ++i) {
        var param = params[i];
        Class<?> functionalParamType;
        if (param instanceof Function function
            && (functionalParamType = executable.getParameterTypes()[i]).isInterface()
            && functionalParamType != Function.class) {
          params[i] = implement(env, functionalParamType, function);
        }
      }
    }

    public static Object promoteFunctionToJavaInterface(
        Environment env, Class<?> clazz, Function function) {
      if (clazz.isInterface() && clazz != Function.class) {
        return implement(env, clazz, function);
      }
      return function;
    }

    private static long numAbstractMethods(Class<?> clss) {
      return Arrays.stream(clss.getMethods())
          .filter(m -> java.lang.reflect.Modifier.isAbstract(m.getModifiers()))
          .filter(m -> !m.isDefault())
          .count();
    }
  }

  public record FieldAccess(Expression object, Identifier field, SymbolCache symbolCache)
      implements Expression {
    @Override
    public Object eval(Context context) {
      var objectValue = object.eval(context);
      if (objectValue instanceof PyObject pyObject) {
        if (field.name().equals("__dict__")) {
          return pyObject.__dict__;
        } else if (pyObject.__dict__.__contains__(field.name())) {
          return pyObject.__dict__.__getitem__(field.name());
        } else if (pyObject.type.__dict__.__contains__(field.name())) {
          return pyObject.type.__dict__.__getitem__(field.name());
        } else if (pyObject.type.instanceMethods.containsKey(field.name())) {
          return new BoundMethod(pyObject, field.name(), symbolCache, object);
        } else {
          throw new NoSuchElementException(
              "Type %s has no field or method named `%s`".formatted(pyObject.type.name, field));
        }
      }

      if (enableSimplifiedJsonSyntax && objectValue instanceof JsonObject json) {
        return unboxJsonPrimitive(json.get(field.name()));
      }

      if (objectValue == null) {
        throw new NullPointerException(
            "Cannot get field \"%s.%s\" because \"%s\" is null".formatted(object, field, object));
      }

      final boolean isClass;
      final Class<?> objectClass;
      if (objectValue instanceof JavaClass javaClassId) {
        isClass = true;
        objectClass = javaClassId.type();
      } else {
        isClass = false;
        objectClass = objectValue.getClass();
      }

      var memberAccessor =
          symbolCache.getMember(
              new SymbolCache.MemberKey(isClass, objectClass, field.name()), this::getMember);

      if (memberAccessor == null) {
        throw new IllegalArgumentException(
            "Object '%s' with value '%s' has no member named '%s'"
                .formatted(object, objectValue, field.name()));
      }
      try {
        return memberAccessor.from(objectValue);
      } catch (ReflectiveOperationException e) {
        throw new RuntimeException(e);
      }
    }

    private SymbolCache.MemberAccessor getMember(SymbolCache.MemberKey key) {
      if (key.isClass()) {
        SymbolCache.MemberAccessor accessor;
        boolean isFieldCapitalized = Character.isUpperCase(field.name().charAt(0));
        if (isFieldCapitalized) {
          // Check for nested class first because member is capitalized.
          accessor = getNestedClass(key.type());
          if (accessor != null) {
            return accessor;
          }
          return getClassField(key.type());
        } else {
          // Check for class-level field first because member is not capitalized.
          accessor = getClassField(key.type());
          if (accessor != null) {
            return accessor;
          }
          return getNestedClass(key.type());
        }
      } else {
        return getInstanceField(key.type());
      }
    }

    private SymbolCache.NestedClassAccessor getNestedClass(Class<?> type) {
      for (var nestedClass : type.getClasses()) {
        String prettyNestedClassName = symbolCache.getPrettyClassName(nestedClass.getName());
        int lastDollarIndex = prettyNestedClassName.lastIndexOf('$');
        if (lastDollarIndex != -1 && lastDollarIndex != prettyNestedClassName.length() - 1) {
          String nestedClassName = prettyNestedClassName.substring(lastDollarIndex + 1);
          if (nestedClassName.equals(field.name())) {
            return new SymbolCache.NestedClassAccessor(nestedClass);
          }
        }
      }
      return null; // No matching nested class found.
    }

    private SymbolCache.ClassFieldAccessor getClassField(Class<?> type) {
      String fieldName = symbolCache.getRuntimeFieldName(type, field.name());
      try {
        return new SymbolCache.ClassFieldAccessor(type.getField(fieldName));
      } catch (NoSuchFieldException e) {
        return null;
      }
    }

    private SymbolCache.MemberAccessor getInstanceField(Class<?> type) {
      String fieldName = symbolCache.getRuntimeFieldName(type, field.name());
      try {
        return new SymbolCache.InstanceFieldAccessor(type.getField(fieldName));
      } catch (NoSuchFieldException e) {
        return null;
      }
    }

    @Override
    public String toString() {
      return String.format("%s.%s", object, field);
    }
  }

  private static Object unboxJsonPrimitive(JsonElement json) {
    if (json.isJsonNull()) {
      return null;
    } else if (json.isJsonPrimitive()) {
      var primitive = json.getAsJsonPrimitive();
      if (primitive.isString()) {
        return primitive.getAsString();
      } else if (primitive.isBoolean()) {
        return primitive.getAsBoolean();
      } else if (primitive.isNumber()) {
        return primitive.getAsNumber();
      }
    }
    return json;
  }

  public interface Environment {
    Object getVariable(String name);

    void setVariable(String name, Object value);

    void deleteVariable(String name);

    PyDict vars();

    List<Statement> globalStatements();

    /** Prepare for exit initiated by {@code Script.exit(int)}. */
    void halt();

    /** Has the script been halted? */
    boolean halted();

    Constructor<?> findConstructor(Class<?> clss, Object... params);
  }

  private static class GlobalContext extends Context implements Environment {
    private final String moduleFilename;
    private final SymbolCache symbolCache;
    private final List<Statement> globalStatements = new ArrayList<>();
    private boolean halted = false; // If true, the script is exiting and this module must halt.

    private GlobalContext(String moduleFilename, SymbolCache symbolCache) {
      globals = this;
      this.moduleFilename = moduleFilename;
      this.symbolCache = symbolCache;
    }

    public Deque<CallSite> getCallStack() {
      var script = (Script) getVariable("__script__");
      return script.callStack.get();
    }

    private static JavaClass MATH_CLASS = getJavaClass(math.class);

    public static GlobalContext create(String moduleFilename, SymbolCache symbolCache) {
      var context = new GlobalContext(moduleFilename, symbolCache);

      // TODO(maxuser): Organize groups of symbols into modules for more efficient initialization of
      // globals.
      context.setVariable("math", MATH_CLASS);
      context.setVariable("globals", GlobalsFunction.INSTANCE);
      context.setVariable("hex", HexFunction.INSTANCE);
      context.setVariable("int", IntFunction.INSTANCE);
      context.setVariable("float", FloatFunction.INSTANCE);
      context.setVariable("str", StrFunction.INSTANCE);
      context.setVariable("bool", BoolFunction.INSTANCE);
      context.setVariable("len", LenFunction.INSTANCE);
      context.setVariable("tuple", TupleFunction.INSTANCE);
      context.setVariable("list", ListFunction.INSTANCE);
      context.setVariable("print", PrintFunction.INSTANCE);
      context.setVariable("range", RangeFunction.INSTANCE);
      context.setVariable("enumerate", EnumerateFunction.INSTANCE);
      context.setVariable("abs", AbsFunction.INSTANCE);
      context.setVariable("round", RoundFunction.INSTANCE);
      context.setVariable("min", MinFunction.INSTANCE);
      context.setVariable("max", MaxFunction.INSTANCE);
      context.setVariable("ord", OrdFunction.INSTANCE);
      context.setVariable("chr", ChrFunction.INSTANCE);
      context.setVariable("type", TypeFunction.INSTANCE);
      context.setVariable("__traceback_format_stack__", TracebackFormatStackFunction.INSTANCE);
      context.setVariable("__exit__", ExitFunction.INSTANCE);
      return context;
    }

    public void addGlobalStatement(Statement statement) {
      globalStatements.add(statement);
    }

    @Override
    public PyDict vars() {
      return vars;
    }

    @Override
    public List<Statement> globalStatements() {
      return globalStatements;
    }

    /**
     * Executes statements added via {@code addGlobalStatement} since last call to {@code
     * execGlobalStatements}.
     */
    public void execGlobalStatements() {
      for (var statement : globalStatements) {
        globals.exec(statement);
      }
      globalStatements.clear();
    }

    @Override
    public void halt() {
      halted = true;
    }

    @Override
    public boolean halted() {
      return halted;
    }

    public Constructor<?> findConstructor(Class<?> clss, Object... params) {
      var cacheKey = ExecutableCacheKey.forConstructor(clss, params);
      var ctor =
          symbolCache
              .getExecutable(
                  cacheKey,
                  ignoreKey ->
                      TypeChecker.findBestMatchingConstructor(clss, params, /* diagnostics= */ null)
                          .map(Executable.class::cast))
              .map(Constructor.class::cast);
      if (ctor.isPresent()) {
        return ctor.get();
      }

      // Re-run type checker with the same args but with error diagnostics for creating exception.
      var diagnostics = new TypeChecker.Diagnostics(symbolCache::getPrettyClassName);
      TypeChecker.findBestMatchingConstructor(clss, params, diagnostics);
      throw diagnostics.createException();
    }
  }

  private static class Context {
    private static final Object NOT_FOUND = new Object();

    private final Context enclosingContext;
    private final ClassMethodName classMethodName;
    private Set<String> globalVarNames = null;
    private Set<String> nonlocalVarNames = null;
    private Object returnValue;
    private boolean returned = false;
    private int loopDepth = 0;
    private boolean breakingLoop = false;
    private boolean continuingLoop = false;

    protected GlobalContext globals;
    protected final PyDict vars = new PyDict();

    // Default constructor is used only for GlobalContext subclass.
    private Context() {
      enclosingContext = null;
      classMethodName = new ClassMethodName("<>", "<>");
    }

    private Context(GlobalContext globals, Context enclosingContext) {
      this(globals, enclosingContext, enclosingContext.classMethodName);
    }

    private Context(
        GlobalContext globals, Context enclosingContext, ClassMethodName classMethodName) {
      this.globals = globals;
      this.enclosingContext = enclosingContext == globals ? null : enclosingContext;
      this.classMethodName = classMethodName;
    }

    public Environment env() {
      return globals;
    }

    public void enterFunction(String filename, int lineno) {
      globals.getCallStack().push(new CallSite(classMethodName, filename, lineno));
    }

    public void leaveFunction() {
      globals.getCallStack().pop();
    }

    public Context createLocalContext(String enclosingClassName, String enclosingMethodName) {
      return new Context(
          globals, this, new ClassMethodName(enclosingClassName, enclosingMethodName));
    }

    public Context createLocalContext() {
      return new Context(globals, this);
    }

    public void declareGlobalVar(String name) {
      if (globalVarNames == null) {
        globalVarNames = new HashSet<>();
      }
      globalVarNames.add(name);
    }

    public void declareNonlocalVar(String name) {
      if (nonlocalVarNames == null) {
        nonlocalVarNames = new HashSet<>();
      }
      nonlocalVarNames.add(name);
    }

    private static boolean isPyjinnSource(String filename) {
      filename = filename.toLowerCase();
      return filename.endsWith(".pyj") || filename.endsWith(".py") || filename.equals("<stdin>");
    }

    /** Call this instead of Statement.exec directly for proper attribution with exceptions. */
    public void exec(Statement statement) {
      try {
        statement.exec(this);
      } catch (Exception e) {
        var callStack = globals.getCallStack();
        var stackTrace = e.getStackTrace();
        if (stackTrace.length > 0 && !isPyjinnSource(stackTrace[0].getFileName())) {
          var scriptStack = new ArrayList<CallSite>();
          scriptStack.add(
              new CallSite(classMethodName, globals.moduleFilename, statement.lineno()));
          scriptStack.addAll(callStack);

          var newStackTrace = new StackTraceElement[stackTrace.length + scriptStack.size()];
          for (int i = 0; i < scriptStack.size(); ++i) {
            var scriptFrame = scriptStack.get(i);
            newStackTrace[i] =
                new StackTraceElement(
                    scriptFrame == null ? "<null>" : scriptFrame.classMethodName().type,
                    scriptFrame == null ? "<null>" : scriptFrame.classMethodName().method,
                    scriptFrame == null ? "<null>" : scriptFrame.filename,
                    scriptFrame == null ? -1 : scriptFrame.lineno());
          }
          System.arraycopy(stackTrace, 0, newStackTrace, scriptStack.size(), stackTrace.length);
          e.setStackTrace(newStackTrace);
        }
        throw e;
      }
    }

    public void setBoundFunction(BoundFunction boundFunction) {
      setVariable(boundFunction.function().identifier().name(), boundFunction);
    }

    public void setVariable(String name, Object value) {
      if (this != globals && globalVarNames != null && globalVarNames.contains(name)) {
        globals.vars.__setitem__(name, value);
      } else if (enclosingContext != null
          && nonlocalVarNames != null
          && nonlocalVarNames.contains(name)) {
        enclosingContext.vars.__setitem__(name, value);
      } else {
        vars.__setitem__(name, value);
      }
    }

    public void setVariable(Identifier id, Object value) {
      setVariable(id.name(), value);
    }

    public Object getVariable(String name) {
      if (this != globals && globalVarNames != null && globalVarNames.contains(name)) {
        return globals.getVariable(name);
      }
      var value = vars.get(name, NOT_FOUND);
      if (value != NOT_FOUND) {
        return value;
      } else if (enclosingContext != null) {
        return enclosingContext.getVariable(name);
      } else if (this != globals) {
        return globals.getVariable(name);
      } else {
        throw new IllegalArgumentException("Variable not found: " + name);
      }
    }

    public void deleteVariable(String name) {
      if (this != globals && globalVarNames != null && globalVarNames.contains(name)) {
        globals.deleteVariable(name);
        return;
      }
      if (!vars.__contains__(name)) {
        throw new IllegalArgumentException(String.format("Name '%s' is not defined", name));
      }
      vars.__delitem__(name);
    }

    public void enterLoop() {
      ++loopDepth;
    }

    public void exitLoop() {
      --loopDepth;
      if (loopDepth < 0) {
        throw new IllegalStateException("Exited more loops than were entered");
      }
      breakingLoop = false;
      continuingLoop = false;
    }

    public void breakLoop() {
      if (loopDepth <= 0) {
        throw new IllegalStateException("'break' outside loop");
      }
      breakingLoop = true;
    }

    public void continueLoop() {
      if (loopDepth <= 0) {
        throw new IllegalStateException("'continue' outside loop");
      }
      continuingLoop = true;
    }

    public void returnWithValue(Object returnValue) {
      if (this == globals) {
        throw new IllegalStateException("'return' outside function");
      }
      this.returnValue = returnValue;
      this.returned = true;
    }

    public boolean skipStatement() {
      return returned || breakingLoop || continuingLoop || globals.halted();
    }

    public boolean shouldBreak() {
      return breakingLoop;
    }

    public boolean shouldContinue() {
      return continuingLoop;
    }

    public boolean resetContinueBit() {
      return continuingLoop = false;
    }

    public Object returnValue() {
      return returnValue;
    }
  }

  /** Emulation of Python math module. */
  public static class math {
    public static final double pi = Math.PI;
    public static final double e = Math.E;
    public static final double tau = Math.TAU;

    public static double sqrt(Number x) {
      return Math.sqrt(x.doubleValue());
    }

    public static Number fmod(Number x, Number y) {
      return Numbers.javaMod(x, y);
    }
  }
}
