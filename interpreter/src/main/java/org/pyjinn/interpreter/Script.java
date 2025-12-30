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
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.pyjinn.parser.NumberParser;
import org.pyjinn.parser.PyjinnParser;

public class Script {

  static {
    // Install JavaClass subclasses with custom constructors. These need to be installed before
    // JavaClass.of(...) is called with the associated types, e.g. String.class, Integer.class, etc.
    JavaClass.install(new BoolClass());
    JavaClass.install(new FloatClass());
    JavaClass.install(new IntClass());
    JavaClass.install(new JavaFloatClass());
    JavaClass.install(new JavaIntClass());
    JavaClass.install(new JavaStringClass());
    JavaClass.install(new StrClass());
  }

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

  /** Convenience method for reading main module's global variable with the given name. */
  public Object get(String name) {
    return mainModule().globals().get(name);
  }

  /** Convenience method for writing main module's global variable with the given name. */
  public void set(String name, Object value) {
    mainModule().globals().set(name, value);
  }

  /** Convenience method for undefining main module's global variable with the given name. */
  public void del(String name) {
    mainModule().globals().del(name);
  }

  /** Return the module with the given name, or null if no such module has been loaded. */
  public Module module(String name) {
    return modulesByName.get(name);
  }

  public static class Module {
    private final String name;
    private final GlobalContext globals;

    public Module(Script script, String filename, String name) {
      this.name = name;
      this.globals = GlobalContext.create(filename, script.symbolCache);
      globals.set("__script__", script);
      globals.set("__name__", name);
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
      if (globals.get("__script__") instanceof Script script) {
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
                    globals.get("__script__").getClass().getSimpleName()));
      }
    }

    public void compile() {
      globals.compileGlobalStatements();
    }

    public void exec() {
      if (globals.get("__script__") instanceof Script script) {
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

  static DebugLogger logger = (message, args) -> {};

  static boolean debug = false;

  public static void setVerboseDebugging(boolean enable) {
    debug = enable;
  }

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

  // Listeners from outside the script that are called when the script exits.  Set to null once the
  // script has exited.
  private LinkedList<Consumer<Integer>> externalAtExitListeners = new LinkedList<>();

  // Listeners from inside the script that are called when the script exits.
  private LinkedList<AtExitCallback> internalAtExitListeners = new LinkedList<>();

  // For use by apps that need to share custom data across modules.
  public final PyjDict vars = new PyjDict();

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

  /**
   * Registers a listener outside the script to run when the script exits, passing the exit status.
   */
  public void atExit(Consumer<Integer> atExit) {
    if (externalAtExitListeners == null) {
      // Script has already exited.
      return;
    }
    externalAtExitListeners.add(atExit);
  }

  private record AtExitCallback(Function callback, Environment env, Object[] params) {
    public Object call() {
      return callback.call(env, params);
    }
  }

  // Registers a listener within the script to run when the script exits.
  void registerAtExit(AtExitCallback callback) {
    if (externalAtExitListeners == null) {
      // Script has already exited.
      return;
    }
    internalAtExitListeners.add(callback);
  }

  // Unregisters listeners previously registered with the same callback with registerAtExit.
  void unregisterAtExit(Function callback) {
    if (externalAtExitListeners == null) {
      // Script has already exited.
      return;
    }
    internalAtExitListeners.removeIf(cb -> cb.callback == callback);
  }

  /** Exits the script with a successful status (0). */
  public void exit() {
    exit(0);
  }

  /**
   * Exits the script with the given status.
   *
   * <p>Listeners registered from within the script via {@code __atexit_register__()} are run first
   * in reverse order from their registration, then all modules in the script are halted to prevent
   * them from executing further, then finally listeners registered from outside the script via
   * {@code Script::atExit} are run in reverse order from their registration.
   */
  public void exit(int status) {
    if (externalAtExitListeners == null) {
      // Script has already exited.
      return;
    }
    var atExitListenersReversed = externalAtExitListeners.reversed();
    externalAtExitListeners = null; // null indicates that the script has exited.

    for (var listener : internalAtExitListeners.reversed()) {
      try {
        listener.call();
      } catch (Exception e) {
        // TODO(maxuser): Log Pyjinn stacktrace, or at least the immediate caller.
        logger.log("Exception thrown in callback at exit: %s", e);
      }
    }

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

  public Script compile() {
    mainModule().compile();
    return this;
  }

  public Script exec() {
    mainModule().exec();
    return this;
  }

  public BoundFunction getFunction(String name) {
    return (BoundFunction) mainModule().globals.get(name);
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

      var doubleStar = getAttr(argsObject, "kwarg");
      Optional<FunctionArg> kwarg =
          doubleStar == null || doubleStar.isJsonNull()
              ? Optional.empty()
              : Optional.of(
                  new FunctionArg(
                      new Identifier(getAttr(doubleStar.getAsJsonObject(), "arg").getAsString())));

      List<Expression> defaults =
          Optional.ofNullable(getAttr(argsObject, "defaults"))
              .map(
                  d ->
                      StreamSupport.stream(d.getAsJsonArray().spliterator(), false)
                          .map(this::parseExpression)
                          .toList())
              .orElse(List.of());

      Statement body = parseStatementBlock(getBody(element));
      var func =
          new FunctionDef(
              getLineno(element),
              enclosingClassName,
              identifier,
              decorators,
              args,
              vararg,
              kwarg,
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
              : Optional.of(parseExpression(getAttr(element, "type"))),
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

        case "Set":
          return new SetLiteral(
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

        case "NamedExpr":
          {
            return new WalrusExpression(
                getId(getAttr(element, "target")), parseExpression(getAttr(element, "value")));
          }
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
      return new KeywordArg(arg.isJsonNull() ? null : arg.getAsString(), parseExpression(value));
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
      Code code,
      AtomicInteger zombieCounter)
      implements Function {
    public BoundFunction(FunctionDef function, Context enclosingContext) {
      this(
          function,
          enclosingContext,
          function.evalArgDefaults(enclosingContext),
          /* code= */ null,
          new AtomicInteger());
    }

    public BoundFunction(FunctionDef function, Context enclosingContext, Code code) {
      this(
          function,
          enclosingContext,
          function.evalArgDefaults(enclosingContext),
          code,
          new AtomicInteger());
    }

    @Override
    public Object call(Environment env, Object... params) {
      if (isHalted()) {
        return null;
      }
      // TODO(maxuser): Either change the in-script call to this call() method to somehow pass the
      // callingContext or eliminate this tree-walk implementation in favor of the compiled form in
      // Instruction.java.
      var localContext = initLocalContext(/* callingContext= */ null, params);
      localContext.exec(function.body);
      return localContext.returnValue();
    }

    boolean isHalted() {
      if (enclosingContext.env().halted()) {
        // TODO(maxuser): Clear function's internal state to avoid memory leak of the entire script.
        var script = (Script) enclosingContext.env().get("__script__");
        script.zombieCallbackHandler.handle(
            script.mainModule().filename(),
            "function '%s'".formatted(function.identifier().name()),
            zombieCounter.incrementAndGet());
        return true;
      } else {
        return false;
      }
    }

    Context initLocalContext(Context callingContext, Object[] params) {
      var localContext =
          enclosingContext.createLocalContext(
              callingContext, function.enclosingClassName, function.identifier.name());

      final List<FunctionArg> args = function.args;
      int numParams = params.length;
      var kwargs = (numParams > 0 && params[numParams - 1] instanceof KeywordArgs k) ? k : null;
      if (kwargs != null) {
        --numParams; // Ignore the final arg when it's kwargs.
      }

      Set<String> assignedArgs = new HashSet<String>();
      Set<String> unassignedArgs = args.stream().map(a -> a.identifier().name()).collect(toSet());

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
        localContext.set(function.vararg().get().identifier().name(), new PyjTuple(vararg));
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
        localContext.set(name, argValue);
      }

      PyjDict kwarg = null; // Populated if there's a **-prefixed arg.
      if (function.kwarg().isPresent()) {
        kwarg = new PyjDict();
        String kwargName = function.kwarg().get().identifier().name();
        localContext.set(kwargName, kwarg);
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
          if (unassignedArgs.contains(name)) {
            assignedArgs.add(name);
            unassignedArgs.remove(name);
            localContext.set(name, entry.getValue());
          } else {
            if (kwarg != null) {
              kwarg.__setitem__(name, entry.getValue());
            } else {
              throw new IllegalArgumentException(
                  "%s() got an unexpected keyword argument '%s'"
                      .formatted(function.identifier.name(), name));
            }
          }
        }
      }

      // Assign default values to unassigned args.
      int defaultsOffset = args.size() - defaults.size();
      for (int i = 0; i < defaults.size(); ++i) {
        String name = args.get(i + defaultsOffset).identifier().name();
        if (unassignedArgs.contains(name)) {
          assignedArgs.add(name);
          unassignedArgs.remove(name);
          localContext.set(name, defaults.get(i));
        }
      }

      // Verify that all args are assigned.
      if (!unassignedArgs.isEmpty()) {
        throw new IllegalArgumentException(
            "%s() missing %d positional arguments: %s"
                .formatted(function.identifier.name(), unassignedArgs.size(), unassignedArgs));
      }
      return localContext;
    }

    @Override
    public String toString() {
      return "<function %s at 0x%x>".formatted(function.identifier().name(), hashCode());
    }
  }

  // `type` is an array of length 1 because CtorFunction needs to be instantiated before the
  // surrounding class is fully defined. (Alternatively, PyjClass could be mutable so that it's
  // instantiated before CtorFunction.)
  public record CtorFunction(PyjClass[] type, BoundFunction function) implements Function {
    @Override
    public Object call(Environment env, Object... params) {
      Object[] ctorParams = new Object[params.length + 1];
      var self = new PyjObject(type[0]);
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
        if (context.globals.get("__script__") instanceof Script script) {
          for (var importModule : modules) {
            var module = script.importModule(importModule.name());

            // To support modules with dots in their name, e.g. `import foo.bar.baz`,
            // iterate the name parts in reverse order. For the last part (`baz`),
            // create a PyjObject whose __dict__ is the imported module's global vars
            // and wrap each previous name part in a PyjObject's __dict__. It's equivalent to:
            //
            // baz = imported_module.globals()
            // _bar = object()
            // _bar.__dict__["baz"] = _baz  # Note that Python doesn't create __dict__ for object().
            // foo = object()
            // foo.__dict__["bar"] = _bar
            String[] nameParts = importModule.importedName().split("\\.");
            var object = new PyjObject(PyjClass.MODULE_TYPE, module.globals().vars());
            for (int i = nameParts.length - 1; i > 0; --i) {
              var prevObject = object;
              object = new PyjObject(PyjClass.MODULE_TYPE);
              object.__dict__.__setitem__(nameParts[i], prevObject);
            }
            context.set(nameParts[0], object);
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
        if (context.globals.get("__script__") instanceof Script script) {
          var module = script.importModule(module());
          if (names().size() == 1 && names().get(0).name().equals("*")) {
            for (var entry : module.globals().vars().getJavaMap().entrySet()) {
              var name = (String) entry.getKey();
              if (name.startsWith("__")) {
                continue; // Skip special module variables like __name__.
              }
              var value = entry.getValue();
              context.set(name, value);
            }
          } else {
            for (var importName : names()) {
              var importedEntity = module.globals().get(importName.name());
              context.set(importName.importedName(), importedEntity);
            }
          }
        }
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  interface FunctionCompiler {
    Code compile(FunctionDef functionDef);

    static FunctionCompiler NULL_CODE_GENERATOR = f -> null;
  }

  public record ClassFieldDef(Identifier identifier, Optional<Expression> defaultValue) {}

  // Type passed as an array of one element to defer creation of the type.
  public record DataclassDefaultInit(PyjClass[] type, List<ClassFieldDef> fields, int lineno)
      implements Statement {
    @Override
    public void exec(Context context) {
      context.returnWithValue(create(context));
    }

    public PyjObject create(Context context) {
      var object = new PyjObject(type[0]);
      for (var field : fields) {
        String name = field.identifier().name();
        object.__dict__.__setitem__(name, context.get(name));
      }
      return object;
    }

    @Override
    public int lineno() {
      return lineno;
    }
  }

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
      context.set(
          identifier.name(),
          compile(context, /* compiler= */ FunctionCompiler.NULL_CODE_GENERATOR));
    }

    PyjClass compile(Context context, FunctionCompiler compiler) {
      var type = new PyjClass[1]; // Using array to pass to lambda for deferred type creation.
      Function ctor = null;
      Optional<Decorator> dataclass =
          decorators.stream().filter(d -> d.name().equals("dataclass")).findFirst();
      var instanceMethods = new HashMap<String, Function>();
      var classLevelMethods = new HashMap<String, ClassLevelMethod>();
      for (var methodDef : methodDefs) {
        String methodName = methodDef.identifier().name();
        // TODO(maxuser): Support __str__/__rep__ methods for custom string output.
        if ("__init__".equals(methodName)) {
          ctor =
              new CtorFunction(
                  type, new BoundFunction(methodDef, context, compiler.compile(methodDef)));
          instanceMethods.put(methodName, ctor);
        } else if (methodDef.decorators().stream().anyMatch(d -> d.name().equals("classmethod"))) {
          classLevelMethods.put(
              methodName,
              new ClassLevelMethod(
                  true, new BoundFunction(methodDef, context, compiler.compile(methodDef))));
        } else if (methodDef.decorators().stream().anyMatch(d -> d.name().equals("staticmethod"))) {
          classLevelMethods.put(
              methodName,
              new ClassLevelMethod(
                  false, new BoundFunction(methodDef, context, compiler.compile(methodDef))));
        } else {
          instanceMethods.put(
              methodName, new BoundFunction(methodDef, context, compiler.compile(methodDef)));
        }
      }

      // Create default ctor if one hasn't been defined above.
      if (ctor == null) {
        if (dataclass.isPresent()) {
          ctor = getDataclassDefaultCtor(context, compiler, type);
        } else {
          ctor =
              (env, params) -> {
                Function.expectNumParams(params, 0, identifier.name() + ".__init__");
                return new PyjObject(type[0]);
              };
        }
      }

      type[0] =
          new PyjClass(
              identifier.name(),
              ctor,
              isFrozenDataclass(dataclass),
              instanceMethods,
              classLevelMethods,
              dataclass.map(d -> dataclassHashCode(fields)),
              dataclass.map(d -> dataclassToString(fields)));
      if (!dataclass.isPresent()) {
        for (var field : fields) {
          field
              .defaultValue()
              .ifPresent(
                  v -> type[0].__dict__.__setitem__(field.identifier().name(), v.eval(context)));
        }
      }
      return type[0];
    }

    // Type passed as an array of one element to defer creation of the type.
    private Function getDataclassDefaultCtor(
        Context context, FunctionCompiler compiler, PyjClass[] type) {
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

      var functionDef =
          new FunctionDef(
              lineno,
              identifier.name(),
              new Identifier("__init__"),
              /* decorators= */ List.of(),
              /* args= */ fields.stream().map(f -> new FunctionArg(f.identifier)).toList(),
              /* vararg= */ Optional.empty(),
              /* kwarg= */ Optional.empty(),
              defaults,
              new DataclassDefaultInit(type, fields, lineno));
      return new BoundFunction(functionDef, context, compiler.compile(functionDef));
    }

    // Example of "@dataclass(frozen=True)":
    // [{"type":"keyword","arg":"frozen","value":{"value":true}}]
    private static boolean isFrozenDataclass(Optional<Decorator> dataclass) {
      return dataclass
          .map(
              d ->
                  d.keywords().stream()
                      .anyMatch(
                          k ->
                              JsonAstParser.getType(k).equals("keyword")
                                  && JsonAstParser.getAttr(k, "arg").getAsString().equals("frozen")
                                  && JsonAstParser.getAttr(
                                          JsonAstParser.getAttr(k, "value"), "value")
                                      .getAsBoolean()))
          .orElse(false);
    }

    private static java.util.function.Function<PyjObject, Integer> dataclassHashCode(
        List<ClassFieldDef> fields) {
      return dataObject ->
          Objects.hash(
              fields.stream()
                  .map(f -> dataObject.__dict__.__getitem__(f.identifier().name()))
                  .toArray());
    }

    private static java.util.function.Function<PyjObject, String> dataclassToString(
        List<ClassFieldDef> fields) {
      return dataObject ->
          fields.stream()
              .map(
                  field -> {
                    var fieldName = field.identifier().name();
                    return "%s=%s".formatted(fieldName, dataObject.__dict__.__getitem__(fieldName));
                  })
              .collect(joining(", ", dataObject.__class__.name + "(", ")"));
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

  public static class PyjObject implements Function {
    public final PyjClass __class__;
    public final PyjDict __dict__;

    // Special value that indicates that callMethod() has an undefined return value, distinct from
    // null/None.
    public static final Object UNDEFINED_RESULT = new Object();

    public PyjObject(PyjClass type) {
      this(type, new PyjDict());
    }

    public PyjObject(PyjClass type, PyjDict dict) {
      this.__class__ = type;
      this.__dict__ = dict;
    }

    /**
     * Calls PyjObject method named {@code methodName} with {@code params}.
     *
     * <p>Returns UNDEFINED_RESULT if no appropriate method could be found.
     *
     * @param methodName name of PyjObject method to call
     * @param params arguments passed to PyjObject method
     * @return return value wrapped in an array of 1 element, or empty array if no matching method
     */
    public Object callMethod(Environment env, String methodName, Object... params) {
      if (__dict__ == null) {
        return UNDEFINED_RESULT;
      }

      var field = __dict__.get(methodName);
      if (field != null && field instanceof Function function) {
        return function.call(env, params);
      }

      var method = __class__.instanceMethods.get(methodName);
      if (method != null) {
        if (method instanceof BoundFunction boundFunction && boundFunction.code() != null) {
          throw new IllegalStateException(
              "BoundFunction with code executed via recursive tree evaluation but should be"
                  + " executed via iterative virtual machine: %s.%s(...)"
                      .formatted(__class__.name, boundFunction.function().identifier().name()));
        }
        Object[] methodParams = new Object[params.length + 1];
        methodParams[0] = this;
        System.arraycopy(params, 0, methodParams, 1, params.length);
        return method.call(env, methodParams);
      }

      return UNDEFINED_RESULT;
    }

    @Override
    public Object call(Environment env, Object... params) {
      Object result = callMethod(env, "__call__", params);
      if (result == UNDEFINED_RESULT) {
        throw new IllegalArgumentException("'%s' object is not callable".formatted(__class__.name));
      }
      return result;
    }

    @Override
    public boolean equals(Object other) {
      if (other instanceof PyjObject pyOther
          && __class__ == pyOther.__class__
          && __class__.isFrozen
          && __class__.hashMethod.isPresent()) {
        return hashCode() == other.hashCode();
      } else {
        return super.equals(other);
      }
    }

    @Override
    public int hashCode() {
      if (__class__.hashMethod.isPresent()) {
        return __class__.hashMethod.get().apply(this);
      } else {
        return System.identityHashCode(this);
      }
    }

    @Override
    public String toString() {
      if (__class__.strMethod.isPresent()) {
        return __class__.strMethod.get().apply(this);
      } else {
        return "<%s object at 0x%x>".formatted(__class__.name, System.identityHashCode(this));
      }
    }
  }

  public static class PyjObjects {
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
      } else if (value instanceof PyjList pyList) {
        return pyList.getJavaList().stream()
            .map(PyjObjects::toRepr)
            .collect(joining(", ", "[", "]"));
      } else if (value instanceof List<?> list) {
        return list.stream().map(PyjObjects::toRepr).collect(joining(", ", "[", "]"));
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
        return PyjObjects.toString(value);
      }
    }
  }

  public record ClassLevelMethod(boolean isClassmethod, Function function) {}

  public static class PyjClass extends PyjObject {
    public final String name;
    public final Function ctor;
    public final boolean isFrozen;
    public final Map<String, Function> instanceMethods;
    public final Map<String, ClassLevelMethod> classLevelMethods;
    public final Optional<java.util.function.Function<PyjObject, Integer>> hashMethod;
    public final Optional<java.util.function.Function<PyjObject, String>> strMethod;

    static final PyjClass TYPE =
        new PyjClass(
            "type",
            /* ctor= */ (Environment env, Object... params) -> {
              Function.expectNumParams(params, 1, "type");
              var value = params[0];
              if (value instanceof JavaClass classId) {
                return classId.type();
              } else if (value instanceof PyjObject pyObject) {
                return pyObject == PyjClass.TYPE ? PyjClass.TYPE : pyObject.__class__;
              } else if (value == null) {
                return PyjClass.NONE_TYPE;
              } else {
                var type = value.getClass();
                return JavaClass.of(type);
              }
            });

    private static final PyjClass NONE_TYPE =
        new PyjClass(
            "NoneType",
            (env, params) -> {
              Function.expectNumParams(params, 0, "NoneType()");
              return null;
            });

    private static final PyjClass MODULE_TYPE =
        new PyjClass(
            "module",
            (env, params) -> {
              throw new UnsupportedOperationException();
            });

    public PyjClass(String name, Function ctor) {
      this(name, ctor, false, Map.of(), Map.of(), Optional.empty(), Optional.empty());
    }

    public PyjClass(String name, Function ctor, Map<String, Function> instanceMethods) {
      this(name, ctor, false, instanceMethods, Map.of(), Optional.empty(), Optional.empty());
    }

    public PyjClass(
        String name,
        Function ctor,
        boolean isFrozen,
        Map<String, Function> instanceMethods,
        Map<String, ClassLevelMethod> classLevelMethods,
        Optional<java.util.function.Function<PyjObject, Integer>> hashMethod,
        Optional<java.util.function.Function<PyjObject, String>> strMethod) {
      super(TYPE);
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
    public Object callMethod(Environment env, String methodName, Object... params) {
      var method = classLevelMethods.get(methodName);
      if (method == null) {
        return UNDEFINED_RESULT;
      }
      final Object[] methodParams;
      if (method.isClassmethod()) {
        methodParams = new Object[params.length + 1];
        methodParams[0] = this;
        System.arraycopy(params, 0, methodParams, 1, params.length);
      } else {
        methodParams = params;
      }
      return method.function().call(env, methodParams);
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
      Optional<FunctionArg> kwarg,
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
            context.set(loopVar.name(), value);
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
      return context.get(name);
    }

    @Override
    public String toString() {
      return name;
    }
  }

  public record ExceptionHandler(
      Optional<Expression> exceptionTypeSpec, // Evaluates to a type or tuple of types.
      Optional<Identifier> exceptionVariable,
      Statement body) {}

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
      PyjException pyException = null;
      try {
        context.exec(tryBody);
      } catch (Exception e) {
        // PyjException exists only to prevent all eval/exec/invoke methods from declaring that they
        // throw Exception.  Unwrap the underlying exception here.
        final Object exception;
        if (e instanceof PyjException pe) {
          exception = pe.thrown;
        } else {
          exception = e;
        }
        boolean handled = false;
        for (var handler : exceptionHandlers) {
          var exceptionType = handler.exceptionTypeSpec().map(t -> t.eval(context));
          if (exceptionType.isEmpty()
              || matchesExceptionSpec(exceptionType.get(), exception, /* allowTuple= */ true)) {
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
          pyException = new PyjException(e);
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

    public static boolean matchesExceptionSpec(
        Object exceptionSpec, Object exception, boolean allowTuple) {
      if (exceptionSpec instanceof PyjClass declaredType
          && exception instanceof PyjObject thrownObject
          && thrownObject.__class__ == declaredType) {
        return true;
      }
      if (exceptionSpec instanceof JavaClass javaClassId
          && javaClassId.type().isAssignableFrom(exception.getClass())) {
        return true;
      }
      if (allowTuple && exceptionSpec instanceof PyjTuple tuple) {
        for (var type : tuple) {
          // Allow tuple only at the top-level of the exception type(s), not recursively.
          if (matchesExceptionSpec(type, exception, /* allowTuple= */ false)) {
            return true;
          }
        }
      }
      return false;
    }

    @Override
    public String toString() {
      var out = new StringBuilder("try:\n");
      out.append("  " + tryBody.toString().replaceAll("\n", "\n  ") + "\n");
      for (var handler : exceptionHandlers) {
        out.append("except");
        boolean hasExceptionType = handler.exceptionTypeSpec().isPresent();
        boolean hasExceptionVariable = handler.exceptionVariable().isPresent();
        if (hasExceptionType && hasExceptionVariable) {
          out.append(
              " %s as %s"
                  .formatted(handler.exceptionTypeSpec.get(), handler.exceptionVariable.get()));
        } else if (!hasExceptionType && hasExceptionVariable) {
          out.append(" " + handler.exceptionVariable.get());
        } else if (hasExceptionType && !hasExceptionVariable) {
          out.append(" " + handler.exceptionTypeSpec.get());
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
  public static class PyjException extends RuntimeException {
    public final Object thrown;

    public PyjException(Object thrown) {
      super(PyjObjects.toString(thrown));
      this.thrown = thrown;
    }
  }

  public record RaiseStatement(int lineno, Expression exception) implements Statement {
    @Override
    public void exec(Context context) {
      if (context.skipStatement()) {
        return;
      }
      throw new PyjException(exception.eval(context));
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
      // Set $expr variable for interactive interpreter to print result of expression statements.
      context.set("$expr", eval(context));
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

  public record WalrusExpression(Identifier identifier, Expression rhs) implements Expression {
    @Override
    public Object eval(Context context) {
      var value = rhs.eval(context);
      context.setVariable(identifier, value);
      return value;
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
      assignIdentifierTuple(context, lhsVars, rhsValue);
    }

    @SuppressWarnings("unchecked")
    public static void assignArray(Object array, Object index, Object rhsValue) {
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
          "'%s' object does not support item assignment".formatted(getSimpleTypeName(array)));
    }

    public static void assignIdentifierTuple(
        Context context, List<Identifier> lhsVars, Object rhsValue) {
      var rhsIter = getIterable(rhsValue).iterator();
      int numToAssign = lhsVars.size();
      for (int i = 0; i < numToAssign; ++i) {
        if (!rhsIter.hasNext()) {
          throw new IllegalArgumentException(
              "Not enough values to unpack (expected %d, got %d)".formatted(numToAssign, i));
        }
        context.set(lhsVars.get(i).name(), rhsIter.next());
      }
      if (rhsIter.hasNext()) {
        throw new IllegalArgumentException(
            "Too many values to unpack (expected %d)".formatted(numToAssign));
      }
    }

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
        String fieldName = lhsFieldAccess.field().name();
        if (assignField(lhsObject, fieldName, rhsValue)) {
          return;
        }
      } else if (lhs instanceof TupleLiteral lhsTuple) {
        assignTuple(context, lhsTuple, rhsValue);
        return;
      } else if (lhs instanceof ArrayIndex lhsArrayIndex) {
        var array = lhsArrayIndex.array().eval(context);
        var index = lhsArrayIndex.index().eval(context);
        if (array instanceof PyjObject pyjObject
            && pyjObject.callMethod(context.globals, "__setitem__", new Object[] {index, rhsValue})
                != PyjObject.UNDEFINED_RESULT) {
          return;
        } else {
          assignArray(array, index, rhsValue);
          return;
        }
      }
      throw new IllegalArgumentException(
          "Unsupported expression type for lhs of assignment: '%s' (%s)"
              .formatted(lhs, lhs.getClass().getSimpleName()));
    }

    public static boolean assignField(Object lhsObject, String fieldName, Object rhsValue) {
      if (lhsObject instanceof PyjObject pyObject) {
        if (pyObject.__class__.isFrozen) {
          throw new FrozenInstanceError(
              "cannot assign to field '%s' of type '%s'"
                  .formatted(fieldName, pyObject.__class__.name));
        }
        pyObject.__dict__.__setitem__(fieldName, rhsValue);
        return true;
      }
      return false;
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
            if (lhs instanceof PyjList pyList) {
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

    @Override
    public void exec(Context context) {
      if (context.skipStatement()) {
        return;
      }
      Object rhsValue = rhs.eval(context);
      if (lhs instanceof Identifier lhsId) {
        augmentVariable(context, lhsId.name(), op, rhsValue);
        return;
      } else if (lhs instanceof ArrayIndex lhsArrayIndex) {
        var array = lhsArrayIndex.array().eval(context);
        var index = lhsArrayIndex.index().eval(context);
        augmentArrayIndex(array, index, op, rhsValue);
        return;
      } else if (lhs instanceof FieldAccess lhsFieldAccess) {
        var lhsObject = lhsFieldAccess.object().eval(context);
        String fieldName = lhsFieldAccess.field().name();
        augmentField(lhsObject, fieldName, op, rhsValue);
        return;
      }
      throw new IllegalArgumentException(
          String.format(
              "Unsupported expression type for lhs of assignment: '%s' (%s)",
              lhs, lhs.getClass().getSimpleName()));
    }

    public static void augmentVariable(Context context, String varName, Op op, Object rhs) {
      var oldValue = context.get(varName);
      var newValue = op.apply(oldValue, rhs);
      if (newValue != null) {
        context.set(varName, newValue);
      }
    }

    @SuppressWarnings("unchecked")
    public static void augmentArrayIndex(Object array, Object index, Op op, Object rhsValue) {
      if (array.getClass().isArray()) {
        int intKey = ((Number) index).intValue();
        var oldValue = Array.get(array, intKey);
        Array.set(array, intKey, op.apply(oldValue, rhsValue));
      } else if (array instanceof ItemGetter itemGetter && array instanceof ItemSetter itemSetter) {
        var oldValue = itemGetter.__getitem__(index);
        itemSetter.__setitem__(index, op.apply(oldValue, rhsValue));
      } else if (array instanceof List list) {
        int intKey = ((Number) index).intValue();
        var oldValue = list.get(intKey);
        list.set(intKey, op.apply(oldValue, rhsValue));
      } else if (array instanceof Map map) {
        var oldValue = map.get(index);
        map.put(index, op.apply(oldValue, rhsValue));
      } else {
        throw new IllegalArgumentException(
            "Unsupported expression type for lhs of assignment: %s[%s]"
                .formatted(getSimpleTypeName(array), getSimpleTypeName(index)));
      }
    }

    public static void augmentField(Object lhsObject, String fieldName, Op op, Object rhsValue) {
      if (lhsObject instanceof PyjObject pyjObject) {
        if (pyjObject.__class__.isFrozen) {
          throw new FrozenInstanceError(
              "cannot assign to field '%s' of type '%s'"
                  .formatted(fieldName, pyjObject.__class__.name));
        }
        var oldValue = pyjObject.__dict__.__getitem__(fieldName);
        pyjObject.__dict__.__setitem__(fieldName, op.apply(oldValue, rhsValue));
        return;
      }
      throw new IllegalArgumentException(
          "Unsupported expression type for lhs of assignment: %s.%s"
              .formatted(getSimpleTypeName(lhsObject), fieldName));
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
          context.del(id.name());
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

  public static Number fitIntegralValue(Number value) {
    long l = value.longValue();
    int i = (int) l;
    if (l == i) {
      return i;
    } else {
      return l;
    }
  }

  public record ConstantExpression(Object value) implements Expression {
    public static ConstantExpression parse(String typename, JsonElement value) {
      switch (typename) {
        case "bool":
          return new ConstantExpression(value.getAsBoolean());
        case "int":
          return new ConstantExpression(fitIntegralValue(value.getAsNumber()));
        case "float":
          return new ConstantExpression(value.getAsNumber().doubleValue());
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
      return PyjObjects.toRepr(value);
    }
  }

  public record StarredExpression(Expression value) implements Expression {}

  private static int pyjCompare(Object lhsValue, Object rhsValue) {
    return pyjCompare(lhsValue, rhsValue, null);
  }

  private static boolean pyjEquals(Object lhs, Object rhs) {
    if (lhs == rhs) {
      return true;
    } else if (lhs == null || rhs == null) {
      return false;
    } else {
      return lhs.equals(rhs);
    }
  }

  @SuppressWarnings("unchecked")
  private static int pyjCompare(Object lhs, Object rhs, String op) {
    if (pyjEquals(lhs, rhs)) {
      return 0;
    }
    if (lhs instanceof Number lhsNumber && rhs instanceof Number rhsNumber) {
      return Numbers.compare(lhsNumber, rhsNumber);
    } else if (lhs instanceof Comparable lhsComp
        && rhs != null
        && lhs.getClass() == rhs.getClass()) {
      return lhsComp.compareTo(rhs);
    }
    throw new UnsupportedOperationException(
        String.format(
            "Comparison op not implemented for types: %s %s %s",
            getSimpleTypeName(lhs), op, getSimpleTypeName(rhs)));
  }

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

    @Override
    public Object eval(Context context) {
      var lhsValue = lhs.eval(context);
      var rhsValue = rhs.eval(context);
      return doOp(context, op, lhsValue, rhsValue);
    }

    static Object doOp(Context context, Op op, Object lhsValue, Object rhsValue) {
      switch (op) {
        case IS:
          return lhsValue == rhsValue;
        case IS_NOT:
          return lhsValue != rhsValue;
        case EQ:
          if (lhsValue instanceof PyjObject pyjObject) {
            var result = pyjObject.callMethod(context.globals, "__eq__", rhsValue);
            if (result != PyjObject.UNDEFINED_RESULT) {
              return result;
            }
          }
          return pyjEquals(lhsValue, rhsValue);
        case LT:
          if (lhsValue instanceof PyjObject pyjObject) {
            var result = pyjObject.callMethod(context.globals, "__lt__", rhsValue);
            if (result != PyjObject.UNDEFINED_RESULT) {
              return result;
            }
          }
          return pyjCompare(lhsValue, rhsValue, op.symbol()) < 0;
        case LT_EQ:
          if (lhsValue instanceof PyjObject pyjObject) {
            var result = pyjObject.callMethod(context.globals, "__le__", rhsValue);
            if (result != PyjObject.UNDEFINED_RESULT) {
              return result;
            }
          }
          return pyjCompare(lhsValue, rhsValue, op.symbol()) <= 0;
        case GT:
          if (lhsValue instanceof PyjObject pyjObject) {
            var result = pyjObject.callMethod(context.globals, "__gt__", rhsValue);
            if (result != PyjObject.UNDEFINED_RESULT) {
              return result;
            }
          }
          return pyjCompare(lhsValue, rhsValue, op.symbol()) > 0;
        case GT_EQ:
          if (lhsValue instanceof PyjObject pyjObject) {
            var result = pyjObject.callMethod(context.globals, "__ge__", rhsValue);
            if (result != PyjObject.UNDEFINED_RESULT) {
              return result;
            }
          }
          return pyjCompare(lhsValue, rhsValue, op.symbol()) >= 0;
        case NOT_EQ:
          if (lhsValue instanceof PyjObject pyjObject) {
            var result = pyjObject.callMethod(context.globals, "__ne__", rhsValue);
            if (result != PyjObject.UNDEFINED_RESULT) {
              return result;
            }
          }
          return !pyjEquals(lhsValue, rhsValue);
        case IN:
          {
            if (rhsValue instanceof PyjObject pyjObject) {
              var result = pyjObject.callMethod(context.globals, "__contains__", lhsValue);
              if (result != PyjObject.UNDEFINED_RESULT) {
                return result;
              }
            }
            var result = isIn(lhsValue, rhsValue);
            if (result.isPresent()) {
              return result.get();
            }
          }
        case NOT_IN:
          {
            if (rhsValue instanceof PyjObject pyjObject) {
              var result = pyjObject.callMethod(context.globals, "__contains__", lhsValue);
              if (result != PyjObject.UNDEFINED_RESULT && result instanceof Boolean contains) {
                return !contains;
              }
            }
            var result = isIn(lhsValue, rhsValue);
            if (result.isPresent()) {
              return !result.get();
            }
          }
      }
      throw new UnsupportedOperationException(
          String.format(
              "Comparison op not supported: %s %s %s",
              getSimpleTypeName(lhsValue), op.symbol(), getSimpleTypeName(rhsValue)));
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
      return doOp(context, op, value);
    }

    static Object doOp(Context context, Op op, Object operand) {
      switch (op) {
        case SUB:
          if (operand instanceof Number number) {
            return Numbers.negate(number);
          }
          break;
        case NOT:
          return !convertToBool(operand);
      }
      throw new IllegalArgumentException(
          String.format(
              "bad operand type for unary %s: '%s' (%s)",
              op.symbol(), getSimpleTypeName(operand), operand));
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
      FLOOR_DIV("//"),
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
        case "FloorDiv":
          return Op.FLOOR_DIV;
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
      return doOp(context, op, lhsValue, rhsValue);
    }

    static Object doOp(Context context, Op op, Object lhsValue, Object rhsValue) {
      switch (op) {
        case ADD:
          if (lhsValue instanceof Number lhsNum && rhsValue instanceof Number rhsNum) {
            return Numbers.add(lhsNum, rhsNum);
          } else if (lhsValue instanceof String lhsString && rhsValue instanceof String rhsString) {
            return lhsString + rhsString;
          }
          if (lhsValue instanceof PyjList pyList) {
            lhsValue = pyList.getJavaList();
          }
          if (rhsValue instanceof PyjList pyList) {
            rhsValue = pyList.getJavaList();
          }
          if (lhsValue instanceof List lhsList && rhsValue instanceof List rhsList) {
            @SuppressWarnings("unchecked")
            var newList = new PyjList(new ArrayList<Object>(lhsList));
            newList.__iadd__(rhsList);
            return newList;
          }
          if (lhsValue instanceof PyjObject pyjObject) {
            var result = pyjObject.callMethod(context.globals, "__add__", rhsValue);
            if (result != PyjObject.UNDEFINED_RESULT) {
              return result;
            }
          }
          if (lhsValue instanceof PyjTuple lhsTuple && rhsValue instanceof PyjTuple rhsTuple) {
            int lhsLen = lhsTuple.__len__();
            int rhsLen = rhsTuple.__len__();
            Object[] array = new Object[lhsLen + rhsLen];
            System.arraycopy(lhsTuple.getJavaArray(), 0, array, 0, lhsLen);
            System.arraycopy(rhsTuple.getJavaArray(), 0, array, lhsLen, rhsLen);
            return new PyjTuple(array);
          }
          break;
        case SUB:
          if (lhsValue instanceof PyjObject pyjObject) {
            var result = pyjObject.callMethod(context.globals, "__sub__", rhsValue);
            if (result != PyjObject.UNDEFINED_RESULT) {
              return result;
            }
          } else {
            return Numbers.subtract((Number) lhsValue, (Number) rhsValue);
          }
        case MUL:
          if (lhsValue instanceof String lhsString && rhsValue instanceof Integer rhsInt) {
            return lhsString.repeat(rhsInt);
          } else if (lhsValue instanceof Integer lhsInt && rhsValue instanceof String rhsString) {
            return rhsString.repeat(lhsInt);
          } else if (lhsValue instanceof PyjObject pyjObject) {
            var result = pyjObject.callMethod(context.globals, "__mul__", rhsValue);
            if (result != PyjObject.UNDEFINED_RESULT) {
              return result;
            }
          } else {
            return Numbers.multiply((Number) lhsValue, (Number) rhsValue);
          }
        case DIV:
          if (lhsValue instanceof PyjObject pyjObject) {
            var result = pyjObject.callMethod(context.globals, "__truediv__", rhsValue);
            if (result != PyjObject.UNDEFINED_RESULT) {
              return result;
            }
          } else if (lhsValue instanceof Number lhsNum && rhsValue instanceof Number rhsNum) {
            return Numbers.divide(lhsNum, rhsNum);
          }
          break;
        case FLOOR_DIV:
          if (lhsValue instanceof PyjObject pyjObject) {
            var result = pyjObject.callMethod(context.globals, "__floordiv__", rhsValue);
            if (result != PyjObject.UNDEFINED_RESULT) {
              return result;
            }
          } else if (lhsValue instanceof Number lhsNum && rhsValue instanceof Number rhsNum) {
            return Numbers.floorDiv(lhsNum, rhsNum);
          }
          break;
        case POW:
          if (lhsValue instanceof PyjObject pyjObject) {
            var result = pyjObject.callMethod(context.globals, "__pow__", rhsValue);
            if (result != PyjObject.UNDEFINED_RESULT) {
              return result;
            }
          } else if (lhsValue instanceof Number lhsNum && rhsValue instanceof Number rhsNum) {
            double d = Math.pow(lhsNum.doubleValue(), rhsNum.doubleValue());
            long l = (long) d;
            return l == d ? fitIntegralValue(l) : (Number) d;
          }
          break;
        case MOD:
          if (lhsValue instanceof PyjObject pyjObject) {
            var result = pyjObject.callMethod(context.globals, "__mod__", rhsValue);
            if (result != PyjObject.UNDEFINED_RESULT) {
              return result;
            }
          } else if (lhsValue instanceof String lhsString) {
            if (rhsValue instanceof PyjTuple tuple) {
              return String.format(
                  lhsString, StreamSupport.stream(tuple.spliterator(), false).toArray());
            } else {
              return String.format(lhsString, rhsValue);
            }
          } else if (lhsValue instanceof Number lhsNum && rhsValue instanceof Number rhsNum) {
            return Numbers.pyMod(lhsNum, rhsNum);
          }
          break;
        case LSHIFT:
          if (lhsValue instanceof PyjObject pyjObject) {
            var result = pyjObject.callMethod(context.globals, "__lshift__", rhsValue);
            if (result != PyjObject.UNDEFINED_RESULT) {
              return result;
            }
          } else {
            return convertLongToUnsignedIntIfFits(
                checkNumberAsLong(lhsValue) << checkNumberAsLong(rhsValue));
          }
          break;
        case RSHIFT:
          if (lhsValue instanceof PyjObject pyjObject) {
            var result = pyjObject.callMethod(context.globals, "__rshift__", rhsValue);
            if (result != PyjObject.UNDEFINED_RESULT) {
              return result;
            }
          } else {
            return convertLongToUnsignedIntIfFits(
                checkNumberAsLong(lhsValue) >>> checkNumberAsLong(rhsValue));
          }
          break;
      }
      throw new UnsupportedOperationException(
          String.format(
              "Binary op not supported: %s %s %s",
              getSimpleTypeName(lhsValue), op.symbol(), getSimpleTypeName(rhsValue)));
    }

    static long checkNumberAsLong(Object value) {
      if (value instanceof Number number && (number instanceof Long || number instanceof Integer)) {
        return number.longValue();
      } else {
        throw new IllegalArgumentException(
            "Unexpected operand type for bit-shift operation: " + value.getClass());
      }
    }

    static Number convertLongToUnsignedIntIfFits(Long longValue) {
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

  static String getSimpleTypeName(Object value) {
    if (value == null) {
      return "NoneType";
    } else if (value instanceof PyjObject pyjObject) {
      return pyjObject.__class__.name;
    } else {
      return value.getClass().getSimpleName();
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

      if (arrayValue instanceof PyjObject pyjObject) {
        var result = pyjObject.callMethod(context.globals, "__getitem__", indexValue);
        if (result != PyjObject.UNDEFINED_RESULT) {
          return result;
        }
      }

      return getItem(arrayValue, indexValue);
    }

    public static Object getItem(Object arrayValue, Object indexValue) {
      if (arrayValue == null || indexValue == null) {
        throw new NullPointerException(
            String.format("%s[%s]", getSimpleTypeName(arrayValue), getSimpleTypeName(indexValue)));
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
          String.format("'%s' is not subscriptable", getSimpleTypeName(arrayValue)));
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
          String className, Optional<String> methodName, Class<?>[] paramTypes) {
        String invocation =
            String.format(
                "%s%s(%s)",
                className,
                methodName.map(s -> "." + s).orElse(""),
                Arrays.stream(paramTypes)
                    .map(t -> t == null ? "null" : t.getName())
                    .collect(joining(", ")));

        String invocationWithPrettyNames =
            String.format(
                "%s%s(%s)",
                toPrettyClassName.apply(className),
                methodName.map(s -> "." + s).orElse(""),
                Arrays.stream(paramTypes)
                    .map(t -> t == null ? "null" : toPrettyClassName.apply(t.getName()))
                    .collect(joining(", ")));

        if (!invocation.equals(invocationWithPrettyNames)) {
          invocation += " [Mapped names: " + invocationWithPrettyNames + "]";
        }
        return invocation;
      }
    }

    public static Class<?>[] getTypes(Object[] params) {
      Class<?>[] types = new Class<?>[params.length];
      for (int i = 0; i < params.length; ++i) {
        Object param = params[i];
        types[i] = param == null ? null : param.getClass();
      }
      return types;
    }

    private static Object unwrapJavaString(Object object) {
      if (object instanceof JavaString jstring) {
        return jstring.string();
      } else {
        return object;
      }
    }

    private static void unwrapJavaStrings(Object[] objects) {
      for (int i = 0; i < objects.length; ++i) {
        if (objects[i] instanceof JavaString jstring) {
          objects[i] = jstring.string();
        }
      }
    }

    /** Convert numeric params to float for formal float params. */
    private static void convertFloatParams(List<Integer> floatParamIndices, Object[] objects) {
      for (int index : floatParamIndices) {
        if (objects[index] instanceof Number number) {
          objects[index] = number.floatValue();
        }
      }
    }

    // paramTypes is destructive because it can overwrite type like JavaString -> String
    public static Optional<MethodInvoker> findBestMatchingMethod(
        Class<?> clazz,
        boolean isStaticMethod,
        BiFunction<Class<?>, String, Set<String>> toRuntimeMethodNames,
        String methodName,
        Class<?>[] paramTypes,
        Diagnostics diagnostics) {

      // Translate Java string methods to Pyjinn string methods.
      if (clazz == String.class) {
        var strMethod = PyjString.translateStringMethod(isStaticMethod, methodName, paramTypes);
        // TODO(maxuser): When strMethod is empty, stop falling back to String methods below.
        if (strMethod.isPresent()) {
          return strMethod;
        }
      }

      final boolean isObjectJavaStringWrapper = clazz == JavaString.class;
      if (isObjectJavaStringWrapper) {
        clazz = String.class;
      }

      // Unwrap JavaString wrappers to expose plain ol' String.
      boolean hasJavaStringParam = false;
      for (int i = 0; i < paramTypes.length; ++i) {
        if (paramTypes[i] == JavaString.class) {
          paramTypes[i] = String.class;
          hasJavaStringParam = true;
          break;
        }
      }
      final boolean hasAnyJavaStringParams = hasJavaStringParam;

      if (diagnostics != null) {
        diagnostics.append(
            "Resolving %smethod call for %s.%s()\n"
                .formatted(isStaticMethod ? "static " : "", clazz.getSimpleName(), methodName));
      }
      logger.log(
          "Searching '%s' for method named '%s' with param length %d",
          clazz, methodName, paramTypes.length);
      Optional<Method> bestMethod =
          findBestMatchingExecutable(
              clazz,
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
              paramTypes,
              /* traverseSuperclasses= */ true,
              diagnostics);
      if (diagnostics != null) {
        if (bestMethod.isEmpty()) {
          diagnostics.append("No matching method found in class, superclass, or interfaces:\n  ");
          diagnostics.append(
              diagnostics.getDebugInvocationString(
                  clazz.getName(), Optional.of(methodName), paramTypes));
        } else {
          diagnostics.append("Found matching method:\n  ");
          diagnostics.append(bestMethod.get().toString());
        }
      }

      List<Integer> floatParams = null;
      if (bestMethod.isPresent()) {
        Method m = bestMethod.get();
        Class<?>[] formalParams = m.getParameterTypes();
        for (int i = 0; i < formalParams.length; ++i) {
          if (formalParams[i] == float.class || formalParams[i] == Float.class) {
            if (floatParams == null) {
              floatParams = new ArrayList<>();
            }
            floatParams.add(i);
          }
        }
      }
      final List<Integer> floatParamIndices = floatParams;

      return bestMethod.map(
          method -> {
            // Compute requiresFunctionalParamPromotion before this invoker gets inserted into the
            // cache.
            boolean requiresFunctionalParamPromotion =
                InterfaceProxy.requiresFunctionalParamPromotion(method, paramTypes);
            return (Environment env, Object object, Object[] params) -> {
              if (env != null && requiresFunctionalParamPromotion) {
                InterfaceProxy.promoteFunctionalParams(env, method, params);
              }
              if (isObjectJavaStringWrapper) {
                object = unwrapJavaString(object);
              }
              if (hasAnyJavaStringParams) {
                unwrapJavaStrings(params);
              }
              if (floatParamIndices != null) {
                convertFloatParams(floatParamIndices, params);
              }
              return method.invoke(isStaticMethod ? null : object, params);
            };
          });
    }

    // paramTypes is destructive because it can overwrite type like JavaString -> String
    public static Optional<ConstructorInvoker> findBestMatchingConstructor(
        Class<?> clazz, Class<?>[] paramTypes, Diagnostics diagnostics) {
      // Unwrap JavaString wrappers to expose plain ol' String.
      boolean hasJavaStringParam = false;
      for (int i = 0; i < paramTypes.length; ++i) {
        if (paramTypes[i] == JavaString.class) {
          paramTypes[i] = String.class;
          hasJavaStringParam = true;
          break;
        }
      }
      final boolean hasAnyJavaStringParams = hasJavaStringParam;

      if (diagnostics != null) {
        diagnostics.append("Resolving constructor call for ");
        diagnostics.append(clazz.getName());
        diagnostics.append(".\n");
      }
      logger.log("Searching '%s' for ctor with param length %d", clazz, paramTypes.length);
      var bestCtor =
          findBestMatchingExecutable(
              clazz,
              c -> null,
              Class<?>::getConstructors,
              (c, p) -> true,
              paramTypes,
              /* traverseSuperclasses= */ false,
              diagnostics);
      if (diagnostics != null) {
        if (bestCtor.isEmpty()) {
          diagnostics.append("No matching constructor found:\n  ");
          diagnostics.append(
              diagnostics.getDebugInvocationString(clazz.getName(), Optional.empty(), paramTypes));
        } else {
          diagnostics.append("Found matching constructor:\n  ");
          diagnostics.append(bestCtor.get().toString());
        }
      }

      return bestCtor.map(
          ctor -> {
            // Compute requiresFunctionalParamPromotion before this invoker gets inserted into the
            // cache.
            boolean requiresFunctionalParamPromotion =
                InterfaceProxy.requiresFunctionalParamPromotion(ctor, paramTypes);
            return (Environment env, Object[] params) -> {
              if (env != null && requiresFunctionalParamPromotion) {
                InterfaceProxy.promoteFunctionalParams(env, ctor, params);
              }
              if (hasAnyJavaStringParams) {
                unwrapJavaStrings(params);
              }
              return ctor.newInstance(params);
            };
          });
    }

    private static <T extends Executable, U> Optional<T> findBestMatchingExecutable(
        Class<?> clazz,
        java.util.function.Function<Class<?>, U> perClassDataFactory,
        java.util.function.Function<Class<?>, T[]> executableGetter,
        BiPredicate<T, U> filter,
        Class<?>[] paramTypes,
        boolean traverseSuperclasses,
        Diagnostics diagnostics) {
      // TODO(maxuser): Generalize the restriction on class names and make it user-configurable.
      boolean isAccessibleClass = isPublic(clazz) && !clazz.getName().startsWith("sun.");

      if (isAccessibleClass) {
        U perClassData = perClassDataFactory.apply(clazz);
        int bestScore = 0; // Zero means that no viable executable has been found.
        Optional<T> bestExecutable = Optional.empty();
        T[] executables = executableGetter.apply(clazz);
        if (diagnostics != null) {
          diagnostics.append(
              "Options considered in '%s': %d\n".formatted(clazz, executables.length));
        }
        for (T executable : executables) {
          if (filter.test(executable, perClassData)) {
            int score = getTypeCheckScore(executable.getParameterTypes(), paramTypes);
            if (score > bestScore) {
              bestScore = score;
              bestExecutable = Optional.of(executable);
            }
            logger.log("    callable member of '%s' with score %d: %s", clazz, score, executable);
            if (diagnostics != null) {
              if (score == 0) {
                diagnostics.append("- param mismatch: %s\n".formatted(executable));
              } else {
                diagnostics.append("- param match (score=%d): %s\n".formatted(score, executable));
              }
            }
          } else {
            logger.log("    callable member of '%s' not viable: %s", clazz, executable);
          }
        }
        if (diagnostics != null) {
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
          if (clazz.getInterfaces().length > 0 || clazz.getSuperclass() != null) {
            diagnostics.append("Traversed superclasses of '%s'...\n".formatted(clazz.getName()));
          }
        }
        try {
          for (var iface : clazz.getInterfaces()) {
            logger.log("  searching interface '%s'", iface);
            var viableExecutable =
                findBestMatchingExecutable(
                    iface,
                    perClassDataFactory,
                    executableGetter,
                    filter,
                    paramTypes,
                    true,
                    diagnostics);
            if (viableExecutable.isPresent()) {
              return viableExecutable;
            }
          }
          var superclass = clazz.getSuperclass();
          if (superclass != null) {
            logger.log("  searching superclass '%s'", superclass);
            return findBestMatchingExecutable(
                superclass,
                perClassDataFactory,
                executableGetter,
                filter,
                paramTypes,
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
     * <p>Add 1 point for a param requiring narrowing floating-point conversion (double to float), 2
     * points for a param requiring widening numeric conversion or integer conversion to floating
     * point (int to long, int or long to float or double, or float to double), 3 points for a param
     * that's an exact match. Return value of 0 indicates that {@code paramValues} are incompatible
     * with {@code formalParamTypes}.
     */
    private static int getTypeCheckScore(Class<?>[] formalParamTypes, Class<?>[] paramTypes) {
      if (formalParamTypes.length != paramTypes.length) {
        return 0;
      }

      // Start score at 1 so it's non-zero if there's an exact match of no params.
      int score = 1;

      for (int i = 0; i < formalParamTypes.length; ++i) {
        Class<?> formalType = promotePrimitiveType(formalParamTypes[i]);
        Class<?> actualType = paramTypes[i];
        if (actualType == null) {
          // null is convertible to everything except primitive types.
          if (formalType != formalParamTypes[i]) {
            return 0;
          }
          if (formalType.isArray()) {
            score += 2;
          } else {
            score += 3;
          }
          continue;
        }
        if (actualType == formalType) {
          score += 3;
          continue;
        }
        if (actualType == JavaString.class && formalType.isAssignableFrom(String.class)) {
          // JavaString auto-converts to String.
          score += 3;
          continue;
        }
        if (actualType == Double.class && formalType == Float.class) {
          score += 1;
          continue;
        }
        if (Number.class.isAssignableFrom(formalType)
            && Number.class.isAssignableFrom(actualType)) {
          int numericConversionScore = getNumericConversionScore(actualType, formalType);
          if (numericConversionScore > 0) {
            score += numericConversionScore;
            continue;
          }
        }
        // Allow implementations of Function to be passed to params expecting an interface, but
        // don't boost the score for this iffy conversion.
        if (Function.class.isAssignableFrom(actualType) && formalType.isInterface()) {
          continue;
        }
        if (!formalType.isAssignableFrom(actualType)) {
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

    private static int getNumericConversionScore(Class<?> from, Class<?> to) {
      // Add 1 point for a param requiring narrowing float-point conversion (double to float) 2
      // points for a param requiring widening numeric conversion or integer conversion to floating
      // point (int to long, int or long to float or double, or float to double), 3 points for a
      // param that's an exact match. Return value of 0 indicates that there's no viable conversion
      // from floating point to integral type.
      int fromTypeId = getNumericTypeId(from);
      int toTypeId = getNumericTypeId(to);
      if (fromTypeId == toTypeId) {
        return 3;
      } else if (fromTypeId < toTypeId) {
        return 2;
      } else if (toTypeId >= FLOAT_TYPE_ID) {
        return 1;
      } else {
        return 0;
      }
    }

    private static final int BYTE_TYPE_ID = 1;
    private static final int SHORT_TYPE_ID = 2;
    private static final int INT_TYPE_ID = 3;
    private static final int LONG_TYPE_ID = 4;
    private static final int FLOAT_TYPE_ID = 5;
    private static final int DOUBLE_TYPE_ID = 6;

    private static int getNumericTypeId(Class<?> type) {
      if (type == Double.class || type == double.class) {
        return DOUBLE_TYPE_ID;
      } else if (type == Float.class || type == float.class) {
        return FLOAT_TYPE_ID;
      } else if (type == Long.class || type == long.class) {
        return LONG_TYPE_ID;
      } else if (type == Integer.class || type == int.class) {
        return INT_TYPE_ID;
      } else if (type == Short.class || type == short.class) {
        return SHORT_TYPE_ID;
      } else if (type == Byte.class || type == byte.class) {
        return BYTE_TYPE_ID;
      } else {
        return 0;
      }
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
                              : "(%s) %s".formatted(v.getClass().getName(), PyjObjects.toRepr(v)))
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
                                      PyjObjects.toRepr(v)))
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
      // TODO(maxuser): Either change the in-script call to this eval() method to somehow pass the
      // callingContext or eliminate this tree-walk implementation in favor of the compiled form in
      // Instruction.java.
      var localContext = context.createLocalContext(/* callingContext= */ null);
      var list = new PyjList();
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
          localContext.set(loopVar.name(), value);
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
      return new PyjTuple(
          elements.stream()
              .mapMulti(
                  (expr, downstream) -> {
                    if (expr instanceof StarredExpression starredExpr) {
                      getIterable(starredExpr.value().eval(context)).forEach(downstream);
                    } else {
                      downstream.accept(expr.eval(context));
                    }
                  })
              .toArray());
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
      return new PyjList(
          elements.stream()
              .mapMulti(
                  (expr, downstream) -> {
                    if (expr instanceof StarredExpression starredExpr) {
                      getIterable(starredExpr.value().eval(context)).forEach(downstream);
                    } else {
                      downstream.accept(expr.eval(context));
                    }
                  })
              .collect(toList()));
    }

    @Override
    public String toString() {
      return elements.stream().map(Object::toString).collect(joining(", ", "[", "]"));
    }
  }

  public record SetLiteral(List<Expression> elements) implements Expression {
    @Override
    public Object eval(Context context) {
      // Stream.toList() returns immutable list, so using Stream.collect(toList()) for mutable List.
      return new PyjSet(
          elements.stream()
              .mapMulti(
                  (expr, downstream) -> {
                    if (expr instanceof StarredExpression starredExpr) {
                      getIterable(starredExpr.value().eval(context)).forEach(downstream);
                    } else {
                      downstream.accept(expr.eval(context));
                    }
                  })
              .collect(toSet()));
    }

    @Override
    public String toString() {
      if (elements.isEmpty()) {
        return "set()";
      } else {
        return elements.stream().map(Object::toString).collect(joining(", ", "{", "}"));
      }
    }
  }

  public interface Lengthable extends Iterable<Object> {
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

  public static class PyjList extends PyjObject
      implements ItemGetter, ItemSetter, ItemContainer, ItemDeleter, Comparable<PyjList> {
    private final List<Object> list;

    static final PyjClass TYPE =
        new PyjClass(
            "list",
            /* ctor= */ (Environment env, Object... params) -> {
              Function.expectMaxParams(params, 1, "list");
              if (params.length == 0) {
                return new PyjList();
              } else {
                @SuppressWarnings("unchecked")
                Iterable<Object> iterable = (Iterable<Object>) getIterable(params[0]);
                // Stream.toList() returns immutable list, so using Stream.collect(toList()) for
                // mutable List.
                return new PyjList(
                    StreamSupport.stream(iterable.spliterator(), false).collect(toList()));
              }
            });

    public PyjList() {
      this(new ArrayList<>());
    }

    public PyjList(List<Object> list) {
      super(TYPE, PyjDict.EMPTY);
      this.list = list;
    }

    // Package-private for access from JavaList().
    List<Object> getJavaList() {
      return list;
    }

    @Override
    public boolean equals(Object value) {
      return value instanceof PyjList pyList && this.list.equals(pyList.list);
    }

    @Override
    public int compareTo(PyjList other) {
      if (this.list == other.list) {
        return 0;
      }
      int minLength = Math.min(this.list.size(), other.list.size());
      for (int i = 0; i < minLength; ++i) {
        var thisElement = this.list.get(i);
        var otherElement = other.list.get(i);
        if (thisElement != otherElement) {
          int elementComparison = pyjCompare(thisElement, otherElement);
          if (elementComparison != 0) {
            return elementComparison;
          }
        }
      }
      return this.list.size() - other.list.size();
    }

    @Override
    public Iterator<Object> iterator() {
      return list.iterator();
    }

    @Override
    public String toString() {
      return list.stream().map(PyjObjects::toString).collect(joining(", ", "[", "]"));
    }

    public PyjList __add__(Object value) {
      PyjList newList = copy();
      if (value instanceof PyjList pyList) {
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
      if (value instanceof PyjList pyList) {
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
        return new PyjList(new ArrayList<>(list.subList(slice.lower(), slice.upper())));
      }
      throw new IllegalArgumentException(
          String.format(
              "list indices must be integers or slices of integers, not %s (%s)",
              key.getClass().getName(), key));
    }

    @Override
    public void __setitem__(Object key, Object value) {
      if (key instanceof Integer i) {
        list.set(i, value);
        return;
      }

      if (key instanceof SliceValue sliceValue) {
        var slice = sliceValue.resolveIndices(list.size());
        // TODO(maxuser): SliceValue.step not supported.
        list.subList(slice.lower(), slice.upper()).clear();
        if (value instanceof Collection<?> collection) {
          list.addAll(slice.lower(), collection);
        } else if (value instanceof PyjList pyjList) {
          list.addAll(slice.lower(), pyjList.getJavaList());
        } else {
          var sliceList = new ArrayList<Object>();
          for (var item : getIterable(value)) {
            sliceList.add(item);
          }
          list.addAll(slice.lower(), sliceList);
        }
        return;
      }

      throw new IllegalArgumentException(
          String.format(
              "list indices must be integers or slices, not %s (%s)", getSimpleTypeName(key), key));
    }

    public void append(Object object) {
      list.add(object);
    }

    public void clear() {
      list.clear();
    }

    public PyjList copy() {
      return new PyjList(new ArrayList<>(list));
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

  public static class PyjSet extends PyjObject implements ItemContainer, Lengthable {
    private Set<Object> set;

    static final PyjClass TYPE =
        new PyjClass(
            "set",
            /* ctor= */ (env, params) -> {
              Function.expectMaxParams(params, 1, "set");
              if (params.length == 0) {
                return new PyjSet();
              } else {
                var iterable = (Iterable<?>) getIterable(params[0]);
                return new PyjSet(
                    StreamSupport.stream(iterable.spliterator(), false).collect(toSet()));
              }
            },
            /* instanceMethods= */ Map.of(
                "__lt__",
                (env, params) -> {
                  Function.expectNumParams(params, 2, "set.__lt__");
                  var lhs = (PyjSet) params[0];
                  var rhs = params[1];
                  if (!(rhs instanceof PyjSet || rhs instanceof Set<?>)) {
                    throw new IllegalArgumentException(
                        "'<' not supported between instances of 'set' and '%s'"
                            .formatted(getSimpleTypeName(rhs)));
                  }
                  return !lhs.equals(rhs) && lhs.issubset(rhs);
                },
                "__le__",
                (env, params) -> {
                  Function.expectNumParams(params, 2, "set.__le__");
                  var lhs = (PyjSet) params[0];
                  var rhs = params[1];
                  if (!(rhs instanceof PyjSet || rhs instanceof Set<?>)) {
                    throw new IllegalArgumentException(
                        "'<=' not supported between instances of 'set' and '%s'"
                            .formatted(getSimpleTypeName(rhs)));
                  }
                  return lhs.issubset(rhs);
                },
                "__gt__",
                (env, params) -> {
                  Function.expectNumParams(params, 2, "set.__gt__");
                  var lhs = (PyjSet) params[0];
                  var rhs = params[1];
                  if (!(rhs instanceof PyjSet || rhs instanceof Set<?>)) {
                    throw new IllegalArgumentException(
                        "'>' not supported between instances of 'set' and '%s'"
                            .formatted(getSimpleTypeName(rhs)));
                  }
                  return !lhs.equals(rhs) && lhs.issuperset(rhs);
                },
                "__ge__",
                (env, params) -> {
                  Function.expectNumParams(params, 2, "set.__ge__");
                  var lhs = (PyjSet) params[0];
                  var rhs = params[1];
                  if (!(rhs instanceof PyjSet || rhs instanceof Set<?>)) {
                    throw new IllegalArgumentException(
                        "'>=' not supported between instances of 'set' and '%s'"
                            .formatted(getSimpleTypeName(rhs)));
                  }
                  return lhs.issuperset(rhs);
                }));

    public PyjSet() {
      this(new HashSet<>());
    }

    public PyjSet(Set<Object> set) {
      super(TYPE, PyjDict.EMPTY);
      this.set = set;
    }

    // Package-private for access from JavaSet().
    Set<Object> getJavaSet() {
      return set;
    }

    @Override
    public boolean equals(Object value) {
      if (value instanceof PyjSet pyjSet) {
        return this.set.equals(pyjSet.set);
      }
      if (value instanceof Set<?> other) {
        return this.set.equals(other);
      }
      return false;
    }

    @Override
    public boolean __contains__(Object element) {
      return set.contains(element);
    }

    @Override
    public int __len__() {
      return set.size();
    }

    @Override
    public Iterator<Object> iterator() {
      return set.iterator();
    }

    public void add(Object element) {
      this.set.add(element);
    }

    /** Removes all elements from the set. */
    public void clear() {
      this.set.clear();
    }

    /** Removes the specified element from the set if it is present. */
    public void discard(Object element) {
      this.set.remove(element);
    }

    /**
     * Removes and returns an arbitrary element from the set. Throws a NoSuchElementException if the
     * set is empty.
     */
    public Object pop() {
      if (this.set.isEmpty()) {
        throw new java.util.NoSuchElementException("pop from an empty set");
      }
      // Iterate to get an arbitrary element to remove (Java sets don't have a direct pop())
      java.util.Iterator<Object> iterator = this.set.iterator();
      Object element = iterator.next();
      iterator.remove();
      return element;
    }

    /**
     * Removes the specified element from the set. Unlike discard(), this might throw an exception
     * if the element is not found, adhering strictly to the 'signature' requirement mimicking
     * Python's KeyError, although standard Java Set.remove() just returns false. (We will stick to
     * standard Java behavior here for simplicity.)
     */
    public void remove(Object element) {
      boolean existed = this.set.remove(element);
      if (!existed) {
        throw new RuntimeException("KeyError: Element not found in set.");
      }
    }

    /** Adds elements from an iterable to the current set (in-place union). */
    public void update(Iterable<?> others) {
      others.forEach(this.set::add);
    }

    /**
     * Returns a new set containing elements present in the original set but not in the other
     * specified collection(s).
     */
    public PyjSet difference(Iterable<?> other) {
      Set<Object> result = new HashSet<>(this.set);
      other.forEach(result::remove);
      return new PyjSet(result);
    }

    /**
     * Updates the current set by removing all elements found in the other collection(s) (in-place
     * difference).
     */
    public void difference_update(Iterable<?> other) {
      other.forEach(this.set::remove);
    }

    /**
     * Returns a new set containing only the elements common to the current set and the other
     * collection(s).
     */
    public PyjSet intersection(Iterable<?> other) {
      var resultSet = new HashSet<Object>();
      for (Object item : other) {
        if (this.set.contains(item)) {
          resultSet.add(item);
        }
      }
      return new PyjSet(resultSet);
    }

    /**
     * Updates the current set with only the common elements found in the other collection(s)
     * (in-place intersection).
     */
    public void intersection_update(Iterable<?> other) {
      var resultSet = new HashSet<Object>();
      for (Object item : other) {
        if (this.set.contains(item)) {
          resultSet.add(item);
        }
      }
      this.set = resultSet;
    }

    /** Returns a new set with elements that are in either set but not in both. */
    public PyjSet symmetric_difference(Iterable<?> other) {
      Set<Object> workingSet = new HashSet<>(this.set);
      other.forEach(workingSet::add);
      for (var element : other) {
        if (this.set.contains(element)) {
          workingSet.remove(element);
        }
      }
      return new PyjSet(workingSet);
    }

    /**
     * Updates the current set with the symmetric difference of itself and another set (in-place
     * symmetric difference).
     */
    public void symmetric_difference_update(Iterable<?> other) {
      other.forEach(this.set::add);
      for (var element : other) {
        if (this.set.contains(element)) {
          this.set.remove(element);
        }
      }
    }

    /** Returns a new set containing all unique elements from all specified collections (union). */
    public PyjSet union(Iterable<?> other) {
      Set<Object> workingSet = new HashSet<>(this.set);
      other.forEach(workingSet::add);
      return new PyjSet(workingSet);
    }

    /** Returns a shallow copy of the set. */
    public PyjSet copy() {
      return new PyjSet(new HashSet<>(this.set));
    }

    /** Returns true if two sets have no common elements. */
    public boolean isdisjoint(Iterable<?> other) {
      for (Object element : other) {
        if (this.set.contains(element)) {
          return false; // Found a common element
        }
      }
      return true; // No common elements found
    }

    /** Returns true if all elements of the current set are present in the other collection. */
    public boolean issubset(Object other) {
      if (other instanceof Collection<?> collection) {
        return collection.containsAll(this.set);
      } else if (other instanceof Iterable<?> iterable) {
        var workingSet = new HashSet<Object>(this.set);
        iterable.forEach(workingSet::remove);
        return workingSet.isEmpty();
      } else {
        throw new IllegalArgumentException(
            "'%s' object is not iterable"
                .formatted(other == null ? "NoneType" : other.getClass().getName()));
      }
    }

    /** Returns true if all elements of the other collection are present in the current set. */
    public boolean issuperset(Object other) {
      if (other instanceof Collection<?> collection) {
        return this.set.containsAll(collection);
      } else if (other instanceof Iterable<?> iterable) {
        for (var e : iterable) {
          if (!this.set.contains(e)) {
            return false;
          }
        }
        return true;
      } else {
        throw new IllegalArgumentException(
            "'%s' object is not iterable"
                .formatted(other == null ? "NoneType" : other.getClass().getName()));
      }
    }

    @Override
    public String toString() {
      if (this.set.isEmpty()) {
        return "set()";
      } else {
        return this.set.stream().map(PyjObjects::toString).collect(joining(", ", "{", "}"));
      }
    }
  }

  // TODO(maxuser): Enforce immutability of tuples despite getJavaArray() returning array with
  // mutable elements.
  public static class PyjTuple extends PyjObject
      implements ItemGetter, ItemContainer, Comparable<PyjTuple> {
    private final Object[] array;
    private static final Object[] EMPTY_ARRAY = new Object[] {};

    static final PyjClass TYPE =
        new PyjClass(
            "tuple",
            /* ctor= */ (Environment env, Object... params) -> {
              Function.expectMaxParams(params, 1, "tuple");
              if (params.length == 0) {
                return new PyjTuple(EMPTY_ARRAY);
              } else {
                Iterable<?> iterable = getIterable(params[0]);
                return new PyjTuple(StreamSupport.stream(iterable.spliterator(), false).toArray());
              }
            });

    public PyjTuple(Object[] array) {
      super(TYPE, PyjDict.EMPTY);
      this.array = array;
    }

    // Package-private for access from JavaArray().
    Object[] getJavaArray() {
      return array;
    }

    @Override
    public boolean equals(Object value) {
      return value instanceof PyjTuple pyTuple && Arrays.equals(this.array, pyTuple.array);
    }

    @Override
    public int hashCode() {
      return Arrays.hashCode(array);
    }

    @Override
    public int compareTo(PyjTuple other) {
      return Arrays.compare(array, other.array, Script::pyjCompare);
    }

    @Override
    public Iterator<Object> iterator() {
      return Arrays.stream(array).iterator();
    }

    @Override
    public String toString() {
      return array.length == 1
          ? String.format("(%s,)", PyjObjects.toString(array[0]))
          : Arrays.stream(array).map(PyjObjects::toString).collect(joining(", ", "(", ")"));
    }

    public PyjTuple __add__(Object value) {
      if (value instanceof PyjTuple tuple) {
        return new PyjTuple(
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
        return new PyjTuple(Arrays.copyOfRange(array, slice.lower(), slice.upper()));
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
      return new PyjDict(map);
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

  public record Lambda(FunctionDef functionDef) implements Expression {

    private static Identifier LAMBDA_IDENTIFIER = new Identifier("<lambda>");

    // TODO(maxuser): Support arg defaults, *vararg, and **kwarg.
    public Lambda(List<FunctionArg> args, Expression body, int lineno) {
      this(
          new FunctionDef(
              lineno,
              /* enclosingClassName= */ "<>",
              LAMBDA_IDENTIFIER,
              /* decorators= */ List.of(),
              args,
              /* vararg= */ Optional.empty(),
              /* kwarg= */ Optional.empty(),
              /* defaults= */ List.of(),
              new ReturnStatement(lineno, body)));
    }

    @Override
    public Object eval(Context context) {
      return new BoundFunction(functionDef, context);
    }
  }

  public record FormattedString(List<Expression> values) implements Expression {
    @Override
    public Object eval(Context context) {
      return values.stream().map(v -> PyjObjects.toString(v.eval(context))).collect(joining());
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

  public static class PyjDict extends PyjObject
      implements ItemGetter, ItemSetter, ItemContainer, ItemDeleter {
    private static final Object NOT_FOUND = new Object();
    private final Map<Object, Object> map;

    private static final PyjDict EMPTY = new PyjDict(Map.of());

    static final PyjClass TYPE =
        new PyjClass(
            "dict",
            /* ctor= */ (Environment env, Object... params) -> {
              if (params.length == 0) {
                return new PyjDict();
              }

              if (params.length != 1) {
                throw new IllegalArgumentException(
                    "dict() takes 0 args, keywords args, dict, or an iterable of pairs but got %d args"
                        .formatted(params.length));
              }

              if (params[0] instanceof PyjDict dict) {
                return new PyjDict(new HashMap<>(dict.getJavaMap()));
              }

              if (params[0] instanceof KeywordArgs kwargs) {
                var dict = new PyjDict();
                dict.getJavaMap().putAll(kwargs);
                return dict;
              }

              if (params[0] instanceof Iterable<?> iterableElements) {
                var dict = new PyjDict();
                int i = -1;
                for (var element : iterableElements) {
                  ++i;
                  if (element instanceof Iterable<?> iterable) {
                    List<?> list = StreamSupport.stream(iterable.spliterator(), false).toList();
                    if (list.size() == 2) {
                      dict.__setitem__(list.get(0), list.get(1));
                    } else {
                      throw new IllegalArgumentException(
                          "dictionary sequence element #%d has length %d; 2 is required"
                              .formatted(i, list.size()));
                    }
                  } else {
                    throw new IllegalArgumentException(
                        "dictionary sequence element #%d is not iterable: %s"
                            .formatted(i, element == null ? "null" : element.getClass()));
                  }
                }
                return dict;
              }

              throw new IllegalArgumentException(
                  "dict() takes 0 args, keywords args, or 1 iterable of pairs but got 1 arg of type %s"
                      .formatted(params[0] == null ? "null" : params[0].getClass()));
            });

    public PyjDict() {
      this(new HashMap<>());
    }

    public PyjDict(Map<Object, Object> map) {
      super(TYPE, PyjDict.EMPTY);
      this.map = map;
    }

    // Package-private for access from JavaMap().
    Map<Object, Object> getJavaMap() {
      return map;
    }

    @Override
    public boolean equals(Object value) {
      return value instanceof PyjDict pyDict && this.map.equals(pyDict.map);
    }

    @Override
    public Iterator<Object> iterator() {
      return map.keySet().iterator();
    }

    public Iterable<PyjTuple> items() {
      return map.entrySet().stream().map(e -> new PyjTuple(new Object[] {e.getKey(), e.getValue()}))
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
        throw new NoSuchElementException("Key not found: " + PyjObjects.toString(key));
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
        out.append(PyjObjects.toString(entry.getKey()));
        out.append(": ");
        out.append(PyjObjects.toString(entry.getValue()));
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
      return JavaClass.of(type);
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

    static void expectMinParams(Object[] params, int n, Object message) {
      if (params.length < n) {
        throw new IllegalArgumentException(
            String.format(
                "Expected at least %d param%s but got %d for function: %s",
                n, n == 1 ? "" : "s", params.length, message));
      }
    }

    static void expectMaxParams(Object[] params, int n, Object message) {
      if (params.length > n) {
        throw new IllegalArgumentException(
            String.format(
                "Expected at most %d param%s but got %d for function: %s",
                n, n == 1 ? "" : "s", params.length, message));
      }
    }

    default void expectMinParams(Object[] params, int n) {
      expectMinParams(params, n, this);
    }

    default void expectMaxParams(Object[] params, int n) {
      expectMaxParams(params, n, this);
    }

    default void expectNumParams(Object[] params, int n) {
      expectNumParams(params, n, this);
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

  public static class IsinstanceFunction implements Function {
    public static final IsinstanceFunction INSTANCE = new IsinstanceFunction();

    @Override
    public Object call(Environment env, Object... params) {
      expectNumParams(params, 2);
      Object object = params[0];
      if (params[1] instanceof PyjTuple tuple) {
        for (var type : tuple) {
          if (isInstance(object, type)) {
            return true;
          }
        }
        return false;
      } else {
        return isInstance(object, params[1]);
      }
    }

    private static boolean isInstance(Object object, Object type) {
      if (type instanceof Class<?> clazz) {
        return clazz.isInstance(object);
      } else if (type instanceof JavaClass javaClass) {
        if (javaClass.type() == PyjInt.class) {
          return object instanceof Integer || object instanceof Long;
        } else {
          return javaClass.type().isInstance(object);
        }
      } else if (type instanceof PyjClass pyjClass) {
        if (object == null) {
          return pyjClass == PyjClass.NONE_TYPE;
        } else {
          return object instanceof PyjObject pyjObject && pyjObject.__class__ == pyjClass;
        }
      }
      throw new IllegalArgumentException(
          "isinstance() arg 2 must be a type or tuple of types but got '%s'"
              .formatted(type == null ? "None" : type.getClass()));
    }
  }

  /** Pseudo superclass for Integer and Long in Pyjinn type system. */
  private interface PyjInt {}

  public static class IntClass extends JavaClass {
    public IntClass() {
      super(PyjInt.class);
    }

    @Override
    public Object call(Environment env, Object... params) {
      if (params.length == 0) {
        return 0;
      }

      expectMinParams(params, 1);
      expectMaxParams(params, 2);
      var value = params[0];
      if (value instanceof String string) {
        if (params.length == 1) {
          return fitIntegralValue(NumberParser.parseAsLongWithBase(string, 10));
        } else if (params[1] instanceof Integer base) {
          return fitIntegralValue(NumberParser.parseAsLongWithBase(string, base));
        } else if (params[1] instanceof KeywordArgs kwargs
            && kwargs.size() == 1
            && kwargs.containsKey("base")) {
          var base = kwargs.get("base");
          if (base instanceof Integer intBase) {
            return fitIntegralValue(NumberParser.parseAsLongWithBase(string, intBase));
          } else {
            throw new IllegalArgumentException(
                "Expected 'base' keyword arg to int() to be int but got %s (%s)"
                    .formatted(base, base == null ? "None" : base.getClass().getName()));
          }
        } else {
          throw new IllegalArgumentException(
              "Expected second arg to int() to be int but got %s (%s)"
                  .formatted(
                      params[1], params[1] == null ? "None" : params[1].getClass().getName()));
        }
      } else {
        expectNumParams(params, 1);
        return fitIntegralValue((Number) value);
      }
    }
  }

  public static class FloatClass extends JavaClass {
    public FloatClass() {
      super(Double.class);
    }

    @Override
    public Object call(Environment env, Object... params) {
      if (params.length == 0) {
        return 0.0;
      }

      expectNumParams(params, 1);
      var value = params[0];
      if (value instanceof String string) {
        return Double.parseDouble(string);
      } else {
        return ((Number) value).doubleValue();
      }
    }
  }

  public static class StrClass extends JavaClass {
    public StrClass() {
      super(String.class);
    }

    @Override
    public Object call(Environment env, Object... params) {
      if (params.length == 0) {
        return "";
      }

      expectNumParams(params, 1);
      return PyjObjects.toString(params[0]);
    }
  }

  public static class JavaStringClass extends JavaClass {
    public JavaStringClass() {
      super(JavaString.class);
    }

    @Override
    public Object call(Environment env, Object... params) {
      // This implementaiton is a fork of JavaClass::call but drops param support for InterfaceProxy
      // because String constructors don't have params that are interface types.
      ConstructorInvoker ctor = env.findConstructor(String.class, params);
      try {
        return ctor.newInstance(env, params);
      } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
        throw new RuntimeException(e);
      }
    }
  }

  public static class SumFunction implements Function {
    public static final SumFunction INSTANCE = new SumFunction();

    @Override
    public Object call(Environment env, Object... params) {
      expectMinParams(params, 1);
      expectMaxParams(params, 2);
      if (params.length == 1) {
        Number sum = sum(getIterable(params[0]), 0);
        if (sum != null) {
          return sum;
        }
      } else if (params[1] instanceof Number start) {
        Number sum = sum(getIterable(params[0]), start);
        if (sum != null) {
          return sum;
        }
      }
      throw new IllegalArgumentException(
          "Expected sum(iterable, start=0) with iterable of numbers but got (%s)"
              .formatted(
                  Arrays.stream(params)
                      .map(p -> p == null ? "NoneType" : p.getClass().getName())
                      .collect(joining(", "))));
    }

    Number sum(Iterable<?> iterable, Number start) {
      Number sum = start;
      for (var element : iterable) {
        if (element instanceof Number number) {
          sum = Numbers.add(sum, number);
        } else {
          return null;
        }
      }
      return sum;
    }
  }

  public static class BoolClass extends JavaClass {
    public BoolClass() {
      super(Boolean.class);
    }

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
      } else if (value instanceof PyjObject pyjObject) {
        var result = pyjObject.callMethod(env, "__len__");
        if (result != PyjObject.UNDEFINED_RESULT) {
          return result;
        }
      }
      throw new IllegalArgumentException(
          String.format("Object of type '%s' has no len() operator", getSimpleTypeName(value)));
    }
  }

  public static class TracebackFormatStackFunction implements Function {
    public static final TracebackFormatStackFunction INSTANCE = new TracebackFormatStackFunction();

    @Override
    public Object call(Environment env, Object... params) {
      expectNumParams(params, 0);
      var globals = (GlobalContext) env;
      return new PyjList(globals.getCallStack().stream().map(c -> (Object) c.toString()).toList());
    }
  }

  public static class PrintFunction implements Function {
    public static final PrintFunction INSTANCE = new PrintFunction();

    @Override
    public Object call(Environment env, Object... params) {
      int numParams = params.length;
      var kwargs = (numParams > 0 && params[numParams - 1] instanceof KeywordArgs k) ? k : null;
      var script = (Script) env.get("__script__");
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
            Arrays.stream(params, 0, numParams).map(PyjObjects::toString).collect(joining(" ")));
      }
      return null;
    }
  }

  public record AtexitRegsisterFunction() implements Function {
    public static final AtexitRegsisterFunction INSTANCE = new AtexitRegsisterFunction();

    @Override
    public Object call(Environment env, Object... params) {
      expectMinParams(params, 1);
      var value = params[0];
      if (value instanceof Function callback) {
        var script = (Script) env.get("__script__");
        script.registerAtExit(
            new AtExitCallback(callback, env, Arrays.copyOfRange(params, 1, params.length)));
        return null;
      } else {
        throw new IllegalArgumentException(
            "Expected argument to __atexit_register__() to be callable but got '%s'"
                .formatted(value == null ? "null" : value.getClass()));
      }
    }
  }

  public record AtexitUnregsisterFunction() implements Function {
    public static final AtexitUnregsisterFunction INSTANCE = new AtexitUnregsisterFunction();

    @Override
    public Object call(Environment env, Object... params) {
      expectNumParams(params, 1);
      var value = params[0];
      if (value instanceof Function callback) {
        var script = (Script) env.get("__script__");
        script.unregisterAtExit(callback);
        return null;
      } else {
        throw new IllegalArgumentException(
            "Expected argument to __atexit_unregister__() to be callable but got '%s'"
                .formatted(value == null ? "null" : value.getClass()));
      }
    }
  }

  public record JavaStringFunction() implements Function {
    public static final JavaStringFunction INSTANCE = new JavaStringFunction();

    @Override
    public Object call(Environment env, Object... params) {
      expectNumParams(params, 1);
      var value = params[0];
      if (value instanceof String string) {
        return new JavaString(string);
      } else {
        throw new IllegalArgumentException(
            "JavaString() requires a String/str object but got '%s'"
                .formatted(value.getClass().getName()));
      }
    }
  }

  public static Object[] getJavaArray(PyjTuple pyTuple) {
    return pyTuple.getJavaArray();
  }

  public record JavaArrayFunction() implements Function {
    public static final JavaArrayFunction INSTANCE = new JavaArrayFunction();

    @Override
    public Object call(Environment env, Object... params) {
      expectMinParams(params, 1);
      expectMaxParams(params, 2);
      if (params[0] instanceof PyjTuple pyTuple) {
        final Class<?> clazz;
        if (params.length == 2) {
          if (params[1] instanceof Class<?> classParam) {
            clazz = classParam;
          } else if (params[1] instanceof JavaClass javaClass) {
            clazz = javaClass.type();
          } else {
            throw new IllegalArgumentException(
                "Optional second param to JavaArray() must be Class<?> or JavaClass but got '%s'"
                    .formatted(params[1].getClass().getName()));
          }
        } else {
          clazz = Object.class;
        }
        if (clazz == Object.class) {
          return pyTuple.getJavaArray();
        } else {
          Object[] objectArray = pyTuple.getJavaArray();
          Object specificArray = Array.newInstance(clazz, objectArray.length);
          if (clazz.isPrimitive()) {
            if (clazz == byte.class) {
              for (int i = 0; i < objectArray.length; ++i) {
                Array.setByte(specificArray, i, (Byte) objectArray[i]);
              }
            } else if (clazz == int.class) {
              for (int i = 0; i < objectArray.length; ++i) {
                Array.setInt(specificArray, i, (Integer) objectArray[i]);
              }
            } else if (clazz == long.class) {
              for (int i = 0; i < objectArray.length; ++i) {
                Array.setLong(specificArray, i, (Long) objectArray[i]);
              }
            } else if (clazz == float.class) {
              for (int i = 0; i < objectArray.length; ++i) {
                Array.setFloat(specificArray, i, (Float) objectArray[i]);
              }
            } else if (clazz == double.class) {
              for (int i = 0; i < objectArray.length; ++i) {
                Array.setDouble(specificArray, i, (Double) objectArray[i]);
              }
            } else if (clazz == char.class) {
              for (int i = 0; i < objectArray.length; ++i) {
                Array.setChar(specificArray, i, (Character) objectArray[i]);
              }
            } else if (clazz == short.class) {
              for (int i = 0; i < objectArray.length; ++i) {
                Array.setShort(specificArray, i, (Short) objectArray[i]);
              }
            } else {
              throw new IllegalArgumentException(
                  "Unexpected primitive type '%s' passed as second param to JavaArray"
                      .formatted(clazz.getName()));
            }
          } else {
            for (int i = 0; i < objectArray.length; ++i) {
              Array.set(specificArray, i, objectArray[i]);
            }
          }
          return specificArray;
        }
      } else {
        throw new IllegalArgumentException(
            "JavaArray() requires a tuple object (PyjTuple) but got '%s'"
                .formatted(params[0].getClass().getName()));
      }
    }
  }

  public static class JavaIntClass extends JavaClass {
    public JavaIntClass() {
      super(Integer.class);
    }

    @Override
    public Object call(Environment env, Object... params) {
      if (params.length == 0) {
        return 0;
      }

      expectNumParams(params, 1);
      var value = params[0];
      if (value instanceof Number number) {
        return number.intValue();
      } else {
        throw new IllegalArgumentException(
            "JavaInt() requires a numeric param but got '%s'"
                .formatted(value.getClass().getName()));
      }
    }
  }

  public static class JavaFloatClass extends JavaClass {
    public JavaFloatClass() {
      super(Float.class);
    }

    @Override
    public Object call(Environment env, Object... params) {
      if (params.length == 0) {
        return 0.0f;
      }

      expectNumParams(params, 1);
      var value = params[0];
      if (value instanceof Number number) {
        return number.floatValue();
      } else {
        throw new IllegalArgumentException(
            "JavaFloat() requires a numeric param but got '%s'"
                .formatted(value.getClass().getName()));
      }
    }
  }

  public static List<Object> getJavaList(PyjList pyjList) {
    return pyjList.getJavaList();
  }

  public record JavaListFunction() implements Function {
    public static final JavaListFunction INSTANCE = new JavaListFunction();

    @Override
    public Object call(Environment env, Object... params) {
      expectNumParams(params, 1);
      var value = params[0];
      if (value instanceof PyjList pyList) {
        return pyList.getJavaList();
      } else {
        throw new IllegalArgumentException(
            "JavaList() requires a list object (PyjList) but got '%s'"
                .formatted(value.getClass().getName()));
      }
    }
  }

  public record JavaSetFunction() implements Function {
    public static final JavaSetFunction INSTANCE = new JavaSetFunction();

    @Override
    public Object call(Environment env, Object... params) {
      expectNumParams(params, 1);
      var value = params[0];
      if (value instanceof PyjSet pyjSet) {
        return pyjSet.getJavaSet();
      } else {
        throw new IllegalArgumentException(
            "JavaSet() requires a set object (PyjSet) but got '%s'"
                .formatted(value.getClass().getName()));
      }
    }
  }

  public record JavaMapFunction() implements Function {
    public static final JavaMapFunction INSTANCE = new JavaMapFunction();

    @Override
    public Object call(Environment env, Object... params) {
      expectNumParams(params, 1);
      var value = params[0];
      if (value instanceof PyjDict pyDict) {
        return pyDict.getJavaMap();
      } else {
        throw new IllegalArgumentException(
            "JavaMap() requires a dict object (PyjDict) but got '%s'"
                .formatted(value.getClass().getName()));
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
      if (env.get("__script__") instanceof Script script) {
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
   * Promotes {@code object} to {@code PyjTuple} if it's an array, or else returns {@code object}.
   */
  public static Object promoteArrayToTuple(Object object) {
    if (object.getClass().isArray()) {
      if (object instanceof Object[] objectArray) {
        return new PyjTuple(objectArray);
      } else {
        int length = Array.getLength(object);
        Object[] array = new Object[length];
        for (int i = 0; i < length; i++) {
          array[i] = Array.get(object, i);
        }
        return new PyjTuple(array);
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
        if (Numbers.compare(num, currentMin) < 0) {
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
        if (Numbers.compare(num, currentMax) > 0) {
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

  record KeywordArg(String name, Expression value) {}

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
          if (kwarg.name() == null) {
            var packedKwarg = kwarg.value().eval(context);
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
            kwargsMap.put(kwarg.name(), kwarg.value().eval(context));
          }
        }
        if (!kwargsMap.isEmpty()) {
          paramValues.add(kwargsMap);
        }
      }

      if (caller instanceof Class<?> type) {
        Function function;
        if ((function = InterfaceProxy.getFunctionPassedToInterface(type, paramValues.toArray()))
            != null) {
          return InterfaceProxy.promoteFunctionToJavaInterface(context.env(), type, function);
        }
      }

      if (caller instanceof Function function) {
        try {
          context.enterFunction(filename, lineno);
          return function.call(context.env(), paramValues.toArray());
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

  public static class EnumerateIterable implements Iterable<PyjTuple> {
    private final Iterable<?> iterable;
    private final int start;

    public EnumerateIterable(Iterable<?> iterable, int start) {
      this.iterable = iterable;
      this.start = start;
    }

    public Iterator<PyjTuple> iterator() {
      var iter = iterable.iterator();
      return new Iterator<PyjTuple>() {
        private int pos = start;

        @Override
        public boolean hasNext() {
          return iter.hasNext();
        }

        @Override
        public PyjTuple next() {
          var next = iter.next();
          return new PyjTuple(new Object[] {pos++, next});
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
      Object pyjObjectMethodResult;
      if (object instanceof PyjObject pyObject
          && (pyjObjectMethodResult = pyObject.callMethod(env, methodName, params))
              != PyjObject.UNDEFINED_RESULT) {
        return pyjObjectMethodResult;
      }

      final boolean isStaticMethod;
      final Class<?> clazz;
      if (object instanceof JavaClass classId) {
        isStaticMethod = true;
        clazz = classId.type();
        var memberAccessor = FieldAccess.getMember(object, methodName, symbolCache);
        if (memberAccessor instanceof SymbolCache.NestedClassAccessor classAccessor) {
          return JavaClass.of(classAccessor.nestedClass()).call(env, params);
        }
      } else {
        if (object == null) {
          throw new NullPointerException(
              "Cannot invoke method \"%s.%s()\" because \"%s\" is null"
                  .formatted(objectExpression, methodName, objectExpression));
        }
        isStaticMethod = false;
        clazz = object.getClass();
      }

      Class<?>[] paramTypes = TypeChecker.getTypes(params);
      var cacheKey = MethodCacheKey.of(clazz, isStaticMethod, methodName, paramTypes);
      Optional<MethodInvoker> matchedMethod =
          symbolCache.getMethodInvoker(
              cacheKey,
              ignoreKey ->
                  TypeChecker.findBestMatchingMethod(
                      clazz,
                      isStaticMethod,
                      symbolCache::getRuntimeMethodNames,
                      methodName,
                      paramTypes,
                      /* diagnostics= */ null));
      if (matchedMethod.isPresent()) {
        try {
          return matchedMethod.get().invoke(env, object, params);
        } catch (IllegalAccessException | InvocationTargetException e) {
          throw new RuntimeException(e);
        }
      } else {
        // Re-run type checker with the same args but with error diagnostics for creating exception.
        var diagnostics = new TypeChecker.Diagnostics(symbolCache::getPrettyClassName);
        TypeChecker.findBestMatchingMethod(
            clazz,
            isStaticMethod,
            symbolCache::getRuntimeMethodNames,
            methodName,
            paramTypes,
            diagnostics);
        throw diagnostics.createException();
      }
    }
  }

  public class PyjString {
    public static Optional<MethodInvoker> translateStringMethod(
        boolean isStaticMethod, String methodName, Class<?>[] paramTypes) {
      if (isStaticMethod) {
        return Optional.empty();
      }
      switch (methodName) {
        case "startswith":
          return Optional.of(startswith(paramTypes));

        case "endswith":
          return Optional.of(endswith(paramTypes));

        case "upper":
          return Optional.of(upper(paramTypes));

        case "lower":
          return Optional.of(lower(paramTypes));

        case "join":
          return Optional.of(join(paramTypes));

        case "split":
          return Optional.of(split(paramTypes));

        case "strip":
          return Optional.of(strip(paramTypes, /* stripLeft= */ true, /* stripRight= */ true));

        case "lstrip":
          return Optional.of(strip(paramTypes, /* stripLeft= */ true, /* stripRight= */ false));

        case "rstrip":
          return Optional.of(strip(paramTypes, /* stripLeft= */ false, /* stripRight= */ true));

        case "find":
          return Optional.of(find(paramTypes));

        case "replace":
          return Optional.of(replace(paramTypes));
      }
      return Optional.empty();
    }

    private static boolean isAssignableFromStringType(Class<?> clazz) {
      return clazz.isAssignableFrom(String.class) || clazz == JavaString.class;
    }

    private static MethodInvoker startswith(Class<?>[] paramTypes) {
      if (paramTypes.length >= 1
          && paramTypes.length <= 3
          && isAssignableFromStringType(paramTypes[0])) {
        boolean startIsEmpty = paramTypes.length < 2 || paramTypes[1] == null;
        if (startIsEmpty) {
          return (env, object, params) ->
              ((String) object).startsWith((String) TypeChecker.unwrapJavaString(params[0]));
        } else if (paramTypes[1] == Integer.class) {
          boolean endIsEmpty = paramTypes.length < 3 || paramTypes[2] == null;
          if (endIsEmpty) {
            return (env, object, params) ->
                ((String) object)
                    .substring((Integer) params[1])
                    .startsWith(((String) TypeChecker.unwrapJavaString(params[0])));
          } else if (paramTypes[2] == Integer.class) {
            return (env, object, params) ->
                ((String) object)
                    .substring((Integer) params[1], (Integer) params[2])
                    .startsWith(((String) TypeChecker.unwrapJavaString(params[0])));
          }
        }
      }
      return (env, object, params) -> {
        throw new IllegalArgumentException(
            "Expected str.startswith(prefix:str, start:int=None, end:int=None) but got (%s)"
                .formatted(formatTypes(paramTypes)));
      };
    }

    private static MethodInvoker endswith(Class<?>[] paramTypes) {
      if (paramTypes.length >= 1
          && paramTypes.length <= 3
          && isAssignableFromStringType(paramTypes[0])) {
        boolean startIsEmpty = paramTypes.length < 2 || paramTypes[1] == null;
        if (startIsEmpty) {
          return (env, object, params) ->
              ((String) object).endsWith((String) TypeChecker.unwrapJavaString(params[0]));
        } else if (paramTypes[1] == Integer.class) {
          boolean endIsEmpty = paramTypes.length < 3 || paramTypes[2] == null;
          if (endIsEmpty) {
            return (env, object, params) ->
                ((String) object)
                    .substring((Integer) params[1])
                    .endsWith(((String) TypeChecker.unwrapJavaString(params[0])));
          } else if (paramTypes[2] == Integer.class) {
            return (env, object, params) ->
                ((String) object)
                    .substring((Integer) params[1], (Integer) params[2])
                    .endsWith(((String) TypeChecker.unwrapJavaString(params[0])));
          }
        }
      }
      return (env, object, params) -> {
        throw new IllegalArgumentException(
            "Expected str.endswith(prefix:str, start:int=None, end:int=None) but got (%s)"
                .formatted(formatTypes(paramTypes)));
      };
    }

    private static MethodInvoker upper(Class<?>[] paramTypes) {
      if (paramTypes.length == 0) {
        return (env, object, params) -> ((String) object).toUpperCase();
      }
      return (env, object, params) -> {
        throw new IllegalArgumentException(
            "Expected str.upper() but got (%s)".formatted(formatTypes(paramTypes)));
      };
    }

    private static MethodInvoker lower(Class<?>[] paramTypes) {
      if (paramTypes.length == 0) {
        return (env, object, params) -> ((String) object).toLowerCase();
      }
      return (env, object, params) -> {
        throw new IllegalArgumentException(
            "Expected str.lower() but got (%s)".formatted(formatTypes(paramTypes)));
      };
    }

    private static MethodInvoker join(Class<?>[] paramTypes) {
      if (paramTypes.length == 1 && Iterable.class.isAssignableFrom(paramTypes[0])) {
        return (env, object, params) -> {
          String delimiter = (String) object;
          Iterable<?> sequence = (Iterable<?>) params[0];
          var out = new StringBuilder();
          int i = 0;
          for (var element : sequence) {
            if (i > 0) {
              out.append(delimiter);
            }
            if (TypeChecker.unwrapJavaString(element) instanceof String str) {
              out.append(str);
            } else {
              throw new IllegalArgumentException(
                  "sequence item %d: expected str instance, %s found"
                      .formatted(i, element.getClass().getName()));
            }
            ++i;
          }
          return out.toString();
        };
      }

      return (env, object, params) -> {
        throw new IllegalArgumentException(
            "Expected str.join(iterable) but got (%s)".formatted(formatTypes(paramTypes)));
      };
    }

    private static MethodInvoker split(Class<?>[] paramTypes) {
      if (paramTypes.length <= 2
          && (paramTypes.length < 1
              || paramTypes[0] == null
              || isAssignableFromStringType(paramTypes[0]))
          && (paramTypes.length < 2 || paramTypes[1] == null || paramTypes[1] == Integer.class)) {
        return (env, object, params) -> {
          String str = (String) object;
          String sep = params.length > 0 ? (String) TypeChecker.unwrapJavaString(params[0]) : null;
          int maxsplit = params.length > 1 ? (Integer) params[1] : -1;
          if (sep != null && sep.isEmpty()) {
            throw new IllegalArgumentException("empty separator in str.split()");
          }

          // Splits by any consecutive whitespace and discards empty strings.
          if (sep == null) {
            String trimmed = str.trim();
            if (trimmed.isEmpty()) {
              return new String[0]; // Splitting an empty or whitespace-only string gives [].
            }
            // The regex \\s+ matches one or more whitespace characters.
            // Java's limit = Python's maxsplit + 1. If maxsplit is -1 (all), Java's limit is 0.
            int limit = (maxsplit == -1) ? 0 : maxsplit + 1;
            return trimmed.split("\\s+", limit);
          } else {
            // Keep trailing empty strings, Java's limit parameter must be negative for unlimited
            // splits.
            int limit = (maxsplit == -1) ? -1 : maxsplit + 1;

            // Quote the separator in case it contains special regex characters (e.g., "." or "|").
            return str.split(Pattern.quote(sep), limit);
          }
        };
      }

      return (env, object, params) -> {
        throw new IllegalArgumentException(
            "Expected str.split(sep:str=None, maxsplit:int=-1) but got (%s)"
                .formatted(formatTypes(paramTypes)));
      };
    }

    private static MethodInvoker strip(
        Class<?>[] paramTypes, boolean stripLeft, boolean stripRight) {
      if (paramTypes.length <= 1
          && (paramTypes.length < 1
              || paramTypes[0] == null
              || isAssignableFromStringType(paramTypes[0]))) {
        return (env, object, params) -> {
          String str = (String) object;
          String chars =
              params.length > 0 ? (String) TypeChecker.unwrapJavaString(params[0]) : null;
          if (chars == null) {
            if (stripLeft && stripRight) {
              return str.strip();
            } else if (stripLeft) {
              return str.stripLeading();
            } else {
              return str.stripTrailing();
            }
          }

          if (chars.isEmpty()) {
            return str;
          }

          Set<Character> charSet = new HashSet<>();
          for (char c : chars.toCharArray()) {
            charSet.add(c);
          }

          int start = 0;
          int end = str.length();
          if (stripLeft) {
            while (start < end && charSet.contains(str.charAt(start))) {
              start++;
            }
          }
          if (stripRight) {
            while (end > start && charSet.contains(str.charAt(end - 1))) {
              end--;
            }
          }
          return str.substring(start, end);
        };
      }

      return (env, object, params) -> {
        final String methodName;
        if (stripLeft && stripRight) {
          methodName = "strip";
        } else if (stripLeft) {
          methodName = "lstrip";
        } else {
          methodName = "rstrip";
        }
        throw new IllegalArgumentException(
            "Expected str.%s(chars:str=None) but got (%s)"
                .formatted(methodName, formatTypes(paramTypes)));
      };
    }

    private static MethodInvoker find(Class<?>[] paramTypes) {
      if (paramTypes.length >= 1
          && paramTypes.length <= 3
          && isAssignableFromStringType(paramTypes[0])) {
        boolean startIsEmpty = paramTypes.length < 2 || paramTypes[1] == null;
        if (startIsEmpty) {
          return (env, object, params) ->
              ((String) object).indexOf((String) TypeChecker.unwrapJavaString(params[0]));
        } else if (paramTypes[1] == Integer.class) {
          boolean endIsEmpty = paramTypes.length < 3 || paramTypes[2] == null;
          if (endIsEmpty) {
            return (env, object, params) ->
                ((String) object)
                    .indexOf(
                        ((String) TypeChecker.unwrapJavaString(params[0])), (Integer) params[1]);
          } else if (paramTypes[2] == Integer.class) {
            return (env, object, params) ->
                ((String) object)
                    .indexOf(
                        ((String) TypeChecker.unwrapJavaString(params[0])),
                        (Integer) params[1],
                        (Integer) params[2]);
          }
        }
      }
      return (env, object, params) -> {
        throw new IllegalArgumentException(
            "Expected str.endswith(prefix:str, start:int=None, end:int=None) but got (%s)"
                .formatted(formatTypes(paramTypes)));
      };
    }

    private static MethodInvoker replace(Class<?>[] paramTypes) {
      if (paramTypes.length >= 2
          && paramTypes.length <= 3
          && isAssignableFromStringType(paramTypes[0])
          && isAssignableFromStringType(paramTypes[1])
          && (paramTypes.length < 3 || paramTypes[2] == Integer.class)) {
        return (env, object, params) -> {
          if (params.length == 2 || ((Integer) params[2] < 0)) {
            return ((String) object)
                .replace(
                    (String) TypeChecker.unwrapJavaString(params[0]),
                    (String) TypeChecker.unwrapJavaString(params[1]));
          } else {
            return String.join(
                (String) TypeChecker.unwrapJavaString(params[1]),
                ((String) object)
                    .split(
                        Pattern.quote((String) TypeChecker.unwrapJavaString(params[0])),
                        (Integer) params[2] + 1));
          }
        };
      }
      return (env, object, params) -> {
        throw new IllegalArgumentException(
            "Expected str.replace(old:str, new:str, count:int=-1) but got (%s)"
                .formatted(formatTypes(paramTypes)));
      };
    }

    private static String formatTypes(Class<?>[] types) {
      return Arrays.stream(types)
          .map(t -> t == null ? "NoneType" : t.getName())
          .collect(joining(", "));
    }

    private PyjString() {}
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

    public static boolean requiresFunctionalParamPromotion(
        Executable executable, Class<?>[] paramTypes) {
      Class<?>[] formalParamTypes = executable.getParameterTypes();
      for (int i = 0; i < paramTypes.length; ++i) {
        var actualParamType = paramTypes[i];
        Class<?> formalParamType = formalParamTypes[i];
        if (actualParamType != null
            && formalParamType.isInterface()
            && !formalParamType.isAssignableFrom(actualParamType)
            && Function.class.isAssignableFrom(actualParamType)) {
          return true;
        }
      }
      return false;
    }

    public static void promoteFunctionalParams(
        Environment env, Executable executable, Object[] params) {
      Class<?>[] formalParamTypes = executable.getParameterTypes();
      for (int i = 0; i < params.length; ++i) {
        var actualParam = params[i];
        Class<?> formalParamType = formalParamTypes[i];
        if (formalParamType.isInterface()
            && !formalParamType.isInstance(actualParam)
            && actualParam instanceof Function function) {
          params[i] = implement(env, formalParamType, function);
        }
      }
    }

    public static Function getFunctionPassedToInterface(Class<?> clazz, Object[] params) {
      return clazz.isInterface()
              && clazz != Function.class
              && params.length == 1
              && params[0] instanceof Function function
          ? function
          : null;
    }

    public static Object promoteFunctionToJavaInterface(
        Environment env, Class<?> interfaceType, Function function) {
      // Treat calls on an interface as a "cast" that attempts to promote function to a proxy for
      // this interface.
      return implement(env, interfaceType, function);
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
      return getField(objectValue);
    }

    public Object getField(Object objectValue) {
      if (objectValue instanceof PyjObject pyObject) {
        if (field.name().equals("__class__")) {
          return pyObject.__class__;
        } else if (field.name().equals("__dict__")) {
          return pyObject.__dict__;
        } else if (pyObject.__dict__.__contains__(field.name())) {
          return pyObject.__dict__.__getitem__(field.name());
        } else if (pyObject.__class__.__dict__.__contains__(field.name())) {
          return pyObject.__class__.__dict__.__getitem__(field.name());
        } else if (pyObject.__class__.instanceMethods.containsKey(field.name())) {
          return new BoundMethod(pyObject, field.name(), symbolCache, object);
        } else {
          throw new NoSuchElementException(
              "Type %s has no field or method named `%s`"
                  .formatted(pyObject.__class__.name, field));
        }
      }

      if (enableSimplifiedJsonSyntax && objectValue instanceof JsonObject json) {
        return unboxJsonPrimitive(json.get(field.name()));
      }

      if (objectValue == null) {
        throw new NullPointerException(
            "Cannot get field \"%s.%s\" because \"%s\" is null".formatted(object, field, object));
      }

      var memberAccessor = getMember(objectValue, field.name(), symbolCache);

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

    public static SymbolCache.MemberAccessor getMember(
        Object object, String memberName, SymbolCache symbolCache) {
      final boolean isClass;
      final Class<?> objectClass;
      if (object instanceof JavaClass javaClassId) {
        isClass = true;
        objectClass = javaClassId.type();
      } else {
        isClass = false;
        objectClass = object.getClass();
      }

      return symbolCache.getMember(
          new SymbolCache.MemberKey(isClass, objectClass, memberName),
          key -> getMemberUncached(key, memberName, symbolCache));
    }

    private static SymbolCache.MemberAccessor getMemberUncached(
        SymbolCache.MemberKey key, String memberName, SymbolCache symbolCache) {
      if (key.isClass()) {
        SymbolCache.MemberAccessor accessor;
        boolean isFieldCapitalized = Character.isUpperCase(memberName.charAt(0));
        if (isFieldCapitalized) {
          // Check for nested class first because member is capitalized.
          accessor = getNestedClass(key.type(), memberName, symbolCache);
          if (accessor != null) {
            return accessor;
          }
          return getClassField(key.type(), memberName, symbolCache);
        } else {
          // Check for class-level field first because member is not capitalized.
          accessor = getClassField(key.type(), memberName, symbolCache);
          if (accessor != null) {
            return accessor;
          }
          return getNestedClass(key.type(), memberName, symbolCache);
        }
      } else {
        return getInstanceField(key.type(), memberName, symbolCache);
      }
    }

    private static SymbolCache.NestedClassAccessor getNestedClass(
        Class<?> type, String memberName, SymbolCache symbolCache) {
      for (var nestedClass : type.getClasses()) {
        String prettyNestedClassName = symbolCache.getPrettyClassName(nestedClass.getName());
        int lastDollarIndex = prettyNestedClassName.lastIndexOf('$');
        if (lastDollarIndex != -1 && lastDollarIndex != prettyNestedClassName.length() - 1) {
          String nestedClassName = prettyNestedClassName.substring(lastDollarIndex + 1);
          if (nestedClassName.equals(memberName)) {
            return new SymbolCache.NestedClassAccessor(nestedClass);
          }
        }
      }
      return null; // No matching nested class found.
    }

    private static SymbolCache.ClassFieldAccessor getClassField(
        Class<?> type, String memberName, SymbolCache symbolCache) {
      String fieldName = symbolCache.getRuntimeFieldName(type, memberName);
      try {
        return new SymbolCache.ClassFieldAccessor(type.getField(fieldName));
      } catch (NoSuchFieldException e) {
        return null;
      }
    }

    static SymbolCache.MemberAccessor getInstanceField(
        Class<?> type, String memberName, SymbolCache symbolCache) {
      String fieldName = symbolCache.getRuntimeFieldName(type, memberName);
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
    Object get(String name);

    void set(String name, Object value);

    void del(String name);

    PyjDict vars();

    List<Statement> globalStatements();

    /** Prepare for exit initiated by {@code Script.exit(int)}. */
    void halt();

    /** Has the script been halted? */
    boolean halted();

    ConstructorInvoker findConstructor(Class<?> clss, Object... params);
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
      var script = (Script) get("__script__");
      return script.callStack.get();
    }

    public static GlobalContext create(String moduleFilename, SymbolCache symbolCache) {
      var context = new GlobalContext(moduleFilename, symbolCache);

      // TODO(maxuser): Organize groups of symbols into modules for more efficient initialization of
      // globals.
      context.set("Exception", JavaClass.of(Exception.class));
      context.set("JavaArray", JavaArrayFunction.INSTANCE);
      context.set("JavaFloat", JavaClass.of(Float.class));
      context.set("JavaInt", JavaClass.of(Integer.class));
      context.set("JavaList", JavaListFunction.INSTANCE);
      context.set("JavaMap", JavaMapFunction.INSTANCE);
      context.set("JavaSet", JavaSetFunction.INSTANCE);
      context.set("JavaString", JavaStringFunction.INSTANCE);
      context.set("__atexit_register__", AtexitRegsisterFunction.INSTANCE);
      context.set("__atexit_unregister__", AtexitUnregsisterFunction.INSTANCE);
      context.set("__exit__", ExitFunction.INSTANCE);
      context.set("__traceback_format_stack__", TracebackFormatStackFunction.INSTANCE);
      context.set("abs", AbsFunction.INSTANCE);
      context.set("bool", JavaClass.of(Boolean.class));
      context.set("chr", ChrFunction.INSTANCE);
      context.set("dict", PyjDict.TYPE);
      context.set("enumerate", EnumerateFunction.INSTANCE);
      context.set("float", JavaClass.of(Double.class));
      context.set("globals", GlobalsFunction.INSTANCE);
      context.set("hex", HexFunction.INSTANCE);
      context.set("isinstance", IsinstanceFunction.INSTANCE);
      context.set("int", JavaClass.of(PyjInt.class));
      context.set("len", LenFunction.INSTANCE);
      context.set("list", PyjList.TYPE);
      context.set("max", MaxFunction.INSTANCE);
      context.set("min", MinFunction.INSTANCE);
      context.set("ord", OrdFunction.INSTANCE);
      context.set("print", PrintFunction.INSTANCE);
      context.set("range", RangeFunction.INSTANCE);
      context.set("round", RoundFunction.INSTANCE);
      context.set("set", PyjSet.TYPE);
      context.set("str", JavaClass.of(String.class));
      context.set("sum", SumFunction.INSTANCE);
      context.set("tuple", PyjTuple.TYPE);
      context.set("type", PyjClass.TYPE);
      return context;
    }

    public void addGlobalStatement(Statement statement) {
      globalStatements.add(statement);
    }

    @Override
    public PyjDict vars() {
      return vars;
    }

    @Override
    public List<Statement> globalStatements() {
      return globalStatements;
    }

    /**
     * Compiles statements added via {@code addGlobalStatement} since last call to {@code
     * compileGlobalStatements}.
     */
    public void compileGlobalStatements() {
      if (code == null) {
        code = new Code();
      }
      for (var statement : globalStatements) {
        Compiler.compile(statement, code);
      }
      globalStatements.clear();
    }

    /**
     * Executes statements added via {@code addGlobalStatement} since last call to {@code
     * execGlobalStatements}.
     */
    public void execGlobalStatements() {
      if (code == null) {
        for (var statement : globalStatements) {
          globals.exec(statement);
        }
      } else {
        Context context = this;
        var virtualMachine = VirtualMachine.getInstance();
        while (virtualMachine.hasMoreInstructions(context)) {
          context = virtualMachine.executeNextInstruction(context);
        }
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

    public ConstructorInvoker findConstructor(Class<?> clazz, Object... params) {
      Class<?>[] paramTypes = TypeChecker.getTypes(params);
      var cacheKey = ConstructorCacheKey.of(clazz, paramTypes);
      var ctor =
          symbolCache.getConstructor(
              cacheKey,
              ignoreKey ->
                  TypeChecker.findBestMatchingConstructor(
                      clazz, paramTypes, /* diagnostics= */ null));
      if (ctor.isPresent()) {
        return ctor.get();
      }

      // Re-run type checker with the same args but with error diagnostics for creating exception.
      var diagnostics = new TypeChecker.Diagnostics(symbolCache::getPrettyClassName);
      TypeChecker.findBestMatchingConstructor(clazz, paramTypes, diagnostics);
      throw diagnostics.createException();
    }
  }

  static class Context {
    private static final Object NOT_FOUND = new Object();
    private final Context callingContext; // for returning to caller in compiled mode
    private final Context enclosingContext; // for resolving variables
    private final ClassMethodName classMethodName;
    private Set<String> globalVarNames = null;
    private Set<String> nonlocalVarNames = null;
    private Object returnValue;
    private boolean returned = false;
    private int loopDepth = 0;
    private boolean breakingLoop = false;
    private boolean continuingLoop = false;

    protected GlobalContext globals;
    protected final PyjDict vars = new PyjDict();

    private List<Object> dataStack = new ArrayList<>();
    Code code = null;
    int ip = 0; // instruction pointer

    public RuntimeException exception; // Set when an exception is active in this context.

    public Context callingContext() {
      return callingContext;
    }

    public void pushData(Object data) {
      if (debug) logger.log("push: " + data);
      dataStack.add(data);
    }

    public Object popData() {
      var data = dataStack.remove(dataStack.size() - 1);
      if (debug) logger.log("pop: " + data);
      return data;
    }

    public Object peekData() {
      if (dataStack.isEmpty()) {
        throw new IllegalStateException("Context data stack is empty");
      }
      var data = dataStack.get(dataStack.size() - 1);
      if (debug) logger.log("peek: " + data);
      return data;
    }

    public Object getData(int index) {
      if (index < 0) {
        index = dataStack.size() + index;
      }
      var data = dataStack.get(index);
      if (debug) logger.log("get[%d]: %s", index, data);
      return data;
    }

    public void debugLogInstructions() {
      if (debug && code != null) {
        for (int i = 0; i < code.instructions().size(); ++i) {
          var instruction = code.instructions().get(i);
          String prefix = i == ip ? "> " : "  ";
          logger.log("%s[%d] %s", prefix, i, instruction);
        }
      }
    }

    // Default constructor is used only for GlobalContext subclass.
    private Context() {
      callingContext = null;
      enclosingContext = null;
      classMethodName = new ClassMethodName("<>", "<>");
    }

    private Context(GlobalContext globals, Context callingContext, Context enclosingContext) {
      this(globals, callingContext, enclosingContext, enclosingContext.classMethodName);
    }

    private Context(
        GlobalContext globals,
        Context callingContext,
        Context enclosingContext,
        ClassMethodName classMethodName) {
      this.globals = globals;
      this.callingContext = callingContext;
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

    public Context createLocalContext(
        Context callingContext, String enclosingClassName, String enclosingMethodName) {
      return new Context(
          globals,
          callingContext,
          /* enclosingContext= */ this,
          new ClassMethodName(enclosingClassName, enclosingMethodName));
    }

    public Context createLocalContext(Context callingContext) {
      return new Context(globals, callingContext, /* enclosingContext= */ this);
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
      if (filename == null) {
        return false;
      }
      filename = filename.toLowerCase();
      return filename.endsWith(".pyj") || filename.endsWith(".py") || filename.equals("<stdin>");
    }

    /** Call this instead of Statement.exec directly for proper attribution with exceptions. */
    public void exec(Statement statement) {
      try {
        statement.exec(this);
      } catch (Exception e) {
        appendScriptStack(statement.lineno(), e);
        throw e;
      }
    }

    void appendScriptStack(int lineno, Exception e) {
      var callStack = globals.getCallStack();
      var stackTrace = e.getStackTrace();
      if (stackTrace.length > 0 && !isPyjinnSource(stackTrace[0].getFileName())) {
        var scriptStack = new ArrayList<CallSite>();
        scriptStack.add(new CallSite(classMethodName, globals.moduleFilename, lineno));
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
    }

    public void setBoundFunction(BoundFunction boundFunction) {
      set(boundFunction.function().identifier().name(), boundFunction);
    }

    public void set(String name, Object value) {
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
      set(id.name(), value);
    }

    public Object get(String name) {
      if (this != globals && globalVarNames != null && globalVarNames.contains(name)) {
        return globals.get(name);
      }
      var value = vars.get(name, NOT_FOUND);
      if (value != NOT_FOUND) {
        return value;
      } else if (enclosingContext != null) {
        return enclosingContext.get(name);
      } else if (this != globals) {
        return globals.get(name);
      } else {
        throw new IllegalArgumentException("Variable not found: " + name);
      }
    }

    public void del(String name) {
      if (this != globals && globalVarNames != null && globalVarNames.contains(name)) {
        globals.del(name);
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
}
