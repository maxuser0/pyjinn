// SPDX-FileCopyrightText: © 2025 Greg Christiana <maxuser@pyjinn.org>
// SPDX-License-Identifier: MIT

package org.pyjinn.interpreter;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.pyjinn.parser.PyjinnParser;

public class ScriptTest {
  private Script script;
  private Script.Environment env;

  @Test
  public void strMethods() throws Exception {
    env = execute("output = 'foobarbaz'.startswith('foo')");
    assertTrue((Boolean) env.get("output"));

    env = execute("output = 'foobarbaz'.startswith('foo', 1)");
    assertFalse((Boolean) env.get("output"));

    env = execute("output = 'foobarbaz'.startswith('ba', 3, 6)");
    assertTrue((Boolean) env.get("output"));

    env = execute("output = 'foobarbaz'.startswith('az', 3, 6)");
    assertFalse((Boolean) env.get("output"));

    env = execute("output = 'foobarbaz'.endswith('baz')");
    assertTrue((Boolean) env.get("output"));

    env = execute("output = 'foobarbaz'.endswith('baz', 8)");
    assertFalse((Boolean) env.get("output"));

    env = execute("output = 'foobarbaz'.endswith('ar', 3, 6)");
    assertTrue((Boolean) env.get("output"));

    env = execute("output = 'foobarbaz'.endswith('ba', 3, 6)");
    assertFalse((Boolean) env.get("output"));

    env = execute("output = 'FooBar'.upper()");
    assertEquals("FOOBAR", (String) env.get("output"));

    env = execute("output = 'FooBar'.lower()");
    assertEquals("foobar", (String) env.get("output"));

    env = execute("output = '::'.join(['foo', 'bar', 'baz'])");
    assertEquals("foo::bar::baz", env.get("output"));

    env = execute("output = 'foo\\tbar   baz  '.split()");
    assertArrayEquals(new String[] {"foo", "bar", "baz"}, (String[]) env.get("output"));

    env = execute("output = 'foo[bar]'.split('[')");
    assertArrayEquals(new String[] {"foo", "bar]"}, (String[]) env.get("output"));

    env = execute("output = ' \\tfoo \\n'.strip()");
    assertEquals("foo", (String) env.get("output"));

    env = execute("output = ' \\tfoo \\n'.lstrip()");
    assertEquals("foo \n", (String) env.get("output"));

    env = execute("output = ' \\tfoo \\n'.rstrip()");
    assertEquals(" \tfoo", (String) env.get("output"));

    env = execute("output = 'abfoocd'.strip('abcd')");
    assertEquals("foo", (String) env.get("output"));

    env = execute("output = 'abfoocd'.lstrip('abcd')");
    assertEquals("foocd", (String) env.get("output"));

    env = execute("output = 'abfoocd'.rstrip('abcd')");
    assertEquals("abfoo", (String) env.get("output"));

    env = execute("output = 'foobarbaz'.find('bar')");
    assertEquals(3, (Integer) env.get("output"));

    env = execute("output = 'foo'.find('bar')");
    assertEquals(-1, (Integer) env.get("output"));

    env = execute("output = 'ofooboozoo'.replace('oo', '--')");
    assertEquals("of--b--z--", (String) env.get("output"));

    env = execute("output = 'ofooboozoo'.replace('oo', '--', -1)");
    assertEquals("of--b--z--", (String) env.get("output"));

    env = execute("output = 'ofooboozoo'.replace('oo', '--', 2)");
    assertEquals("of--b--zoo", (String) env.get("output"));
  }

  @Test
  public void sum() throws Exception {
    env = execute("output = sum(())");
    assertEquals(0, (Integer) env.get("output"));

    env = execute("output = sum((), 42)");
    assertEquals(42, (Integer) env.get("output"));

    env = execute("output = sum([1, 4, 9])");
    assertEquals(14, (Integer) env.get("output"));

    env = execute("output = sum([1, 4, 9], 100)");
    assertEquals(114, (Integer) env.get("output"));
  }

  @Test
  public void javaString() throws Exception {
    // str.split() separator interpreted as a literal string
    env = execute("output = 'foo[xyz]bar'.split('[xyz]')");
    assertArrayEquals(new String[] {"foo", "bar"}, (String[]) env.get("output"));

    // String.split() separator interpreted as a regex
    env = execute("output = JavaString('foo[xyz]bar').split('[xyz]')");
    assertArrayEquals(new String[] {"foo[", "", "", "]bar"}, (String[]) env.get("output"));
  }

  @Test
  public void functionConversionToInterface() throws Exception {
    env =
        execute(
            """
            called = False
            def set_called():
              global called
              called = True

            Runnable = JavaClass("java.lang.Runnable")
            runnable = Runnable(set_called)
            runnable.run()
            """);
    assertTrue((Boolean) env.get("called"));
  }

  public interface NestedInterface {
    void doSomething();
  }

  @Test
  public void functionConversionToNestedInterface() throws Exception {
    env =
        execute(
            """
            called = False
            def set_called():
              global called
              called = True

            ScriptTest = JavaClass("org.pyjinn.interpreter.ScriptTest")
            nested = ScriptTest.NestedInterface(set_called)
            nested.doSomething()
            """);
    assertTrue((Boolean) env.get("called"));
  }

  public record NestedClass(String foo) {}

  @Test
  public void nestedClassCtor() throws Exception {
    env =
        execute(
            """
            ScriptTest = JavaClass("org.pyjinn.interpreter.ScriptTest")
            nested = ScriptTest.NestedClass("hello")
            output = nested.foo()
            """);
    assertEquals("hello", (String) env.get("output"));
  }

  @Test
  public void varargs() throws Exception {
    env =
        execute(
            """
            args_array = None

            def foo(*args):
              global args_array
              args_array = JavaArray(args)

            foo("foo", "bar")
            """);

    assertArrayEquals(new Object[] {"foo", "bar"}, (Object[]) env.get("args_array"));
  }

  @Test
  public void packKeywordArgs() throws Exception {
    env =
        execute(
            """
            a = None
            b = None
            c = None

            def foo(x, y, z):
              global a, b, c
              a = x
              b = y
              c = z

            args = {"x": "first", "y": "second", "z": "third"}
            foo(**args)
            """);

    assertEquals("first", env.get("a"));
    assertEquals("second", env.get("b"));
    assertEquals("third", env.get("c"));
  }

  @Test
  public void unpackKeywordArgs() throws Exception {
    env =
        execute(
            """
            kwargs = None

            def foo(**kw):
              global kwargs
              kwargs = kw

            foo(x=0, y=1, z=2)
            """);

    var kwargs = env.get("kwargs");
    assertNotNull(kwargs);
    assertEquals(Script.PyjDict.class, kwargs.getClass());
    var dict = (Script.PyjDict) kwargs;
    assertEquals(3, dict.__len__());
    assertEquals(0, dict.get("x"));
    assertEquals(1, dict.get("y"));
    assertEquals(2, dict.get("z"));
  }

  @Test
  public void unpackKeywordArgsAfterNamedArgs() throws Exception {
    env =
        execute(
            """
            gx = None
            gy = None
            kwargs = None

            def foo(x, y, **kw):
              global gx, gy, kwargs
              gx = x
              gy = y
              kwargs = kw

            foo(x=0, y=1, z=2)
            """);

    assertEquals(0, env.get("gx"));
    assertEquals(1, env.get("gy"));

    var kwargs = env.get("kwargs");
    assertNotNull(kwargs);
    assertEquals(Script.PyjDict.class, kwargs.getClass());
    var dict = (Script.PyjDict) kwargs;
    assertEquals(1, dict.__len__());
    assertEquals(2, dict.get("z"));
  }

  @Test
  public void dictEmpty() throws Exception {
    env = execute("output = dict()");
    var output = env.get("output");
    assertNotNull(output);
    assertEquals(Script.PyjDict.class, output.getClass());
    var dict = (Script.PyjDict) output;
    assertEquals(0, dict.__len__());
  }

  @Test
  public void dictFromKeywords() throws Exception {
    env = execute("output = dict(x=1, y=2)");
    var output = env.get("output");
    assertNotNull(output);
    assertEquals(Script.PyjDict.class, output.getClass());
    var dict = (Script.PyjDict) output;
    assertEquals(2, dict.__len__());
    assertEquals(1, dict.get("x"));
    assertEquals(2, dict.get("y"));
  }

  @Test
  public void dictFromIterablePairs() throws Exception {
    env = execute("output = dict([(1, 2), (3, 4)])");
    var output = env.get("output");
    assertNotNull(output);
    assertEquals(Script.PyjDict.class, output.getClass());
    var dict = (Script.PyjDict) output;
    assertEquals(2, dict.__len__());
    assertEquals(2, dict.get(1));
    assertEquals(4, dict.get(3));
  }

  @Test
  public void dictCopy() throws Exception {
    env =
        execute(
            """
            d1 = dict(x=0, y=1)
            d2 = dict(d1)
            d2["z"] = "three"
            """);
    var d1 = env.get("d1");
    assertNotNull(d1);
    assertEquals(Script.PyjDict.class, d1.getClass());
    var dict1 = (Script.PyjDict) d1;

    var d2 = env.get("d2");
    assertNotNull(d2);
    assertEquals(Script.PyjDict.class, d2.getClass());
    var dict2 = (Script.PyjDict) d2;

    assertEquals(2, dict1.__len__());
    assertEquals(3, dict2.__len__());
  }

  @Test
  public void floorDivision() throws Exception {
    env =
        execute(
            """
            a = 11 // 2
            b = 11.0 // 2
            c = -11 // 2
            d = 11 // -2
            e = -11 // -2
            f = -11.0 // 2
            """);

    assertVariableValue(5, "a");
    assertVariableValue(5.0, "b");
    assertVariableValue(-6, "c");
    assertVariableValue(-6, "d");
    assertVariableValue(5, "e");
    assertVariableValue(-6.0, "f");
  }

  @Test
  public void registerAtExit() throws Exception {
    execute(
        """
        array = JavaArray((None,))

        def on_exit(message):
          array[0] = message

        __atexit_register__(on_exit, "finished")
        """);

    var array = getVariable(Object[].class, "array");
    assertArrayEquals(new Object[] {null}, array);

    script.exit();
    assertArrayEquals(new Object[] {"finished"}, array);
  }

  @Test
  public void registerAtExitWithKeywordArgs() throws Exception {
    execute(
        """
        array = JavaArray((None,))

        def on_exit(**args):
          array[0] = args

        __atexit_register__(on_exit, x="foo", y="bar")
        """);

    var array = getVariable(Object[].class, "array");
    assertArrayEquals(new Object[] {null}, array);

    script.exit();
    assertEquals(1, array.length);
    assertNotNull(array[0]);
    assertEquals(Script.PyjDict.class, array[0].getClass());

    var dict = (Script.PyjDict) array[0];
    assertEquals(2, dict.__len__());
    assertEquals("foo", dict.get("x"));
    assertEquals("bar", dict.get("y"));
  }

  @Test
  public void registerAtExitWithVarArgsAndKeywordArgs() throws Exception {
    execute(
        """
        array = JavaArray((None, None))

        def on_exit(*args, **kwargs):
          array[0] = args
          array[1] = kwargs

        __atexit_register__(on_exit, 99, 100, x="foo", y="bar")
        """);

    var array = getVariable(Object[].class, "array");
    assertArrayEquals(new Object[] {null, null}, array);

    script.exit();
    assertEquals(2, array.length);

    assertNotNull(array[0]);
    assertEquals(Script.PyjTuple.class, array[0].getClass());
    var args = (Script.PyjTuple) array[0];
    assertEquals(2, args.__len__());
    assertEquals(99, args.__getitem__(0));
    assertEquals(100, args.__getitem__(1));

    assertNotNull(array[1]);
    assertEquals(Script.PyjDict.class, array[1].getClass());
    var kwargs = (Script.PyjDict) array[1];
    assertEquals(2, kwargs.__len__());
    assertEquals("foo", kwargs.get("x"));
    assertEquals("bar", kwargs.get("y"));
  }

  @Test
  public void unregisterAtExit() throws Exception {
    execute(
        """
        array = JavaArray((None,))

        def on_exit(message):
          array[0] = message

        __atexit_register__(on_exit, "finished")
        __atexit_unregister__(on_exit)
        """);

    var array = getVariable(Object[].class, "array");
    assertArrayEquals(new Object[] {null}, array);

    script.exit();
    assertArrayEquals(new Object[] {null}, array);
  }

  @Test
  public void varargExpression() throws Exception {
    execute("output = int(*[3.14])");
    assertEquals(3, getVariable(Integer.class, "output"));
  }

  @Test
  public void emptyVarargs() throws Exception {
    execute("output = int(3.14, *[])");
    assertEquals(3, getVariable(Integer.class, "output"));
  }

  @Test
  public void emptyKeywordArgs() throws Exception {
    // Verifies that `**{}` doesn't produce an additional kwargs param to params to
    // Script.Function.call(env, params).
    execute("output = int(3.14, **{})");
    assertEquals(3, getVariable(Integer.class, "output"));
  }

  @Test
  public void tupleConstructor() throws Exception {
    execute(
        """
        x = type((1, 2, 3))
        y = type(x([1, 2, 3]))
        z = type(tuple([1, 2, 3]))
        """);
    assertEquals(Script.PyjTuple.class, getVariable(JavaClass.class, "x").type());
    assertEquals(Script.PyjTuple.class, getVariable(JavaClass.class, "y").type());
    assertEquals(Script.PyjTuple.class, getVariable(JavaClass.class, "z").type());
    assertEquals(Script.PyjTuple.class, getVariable(JavaClass.class, "tuple").type());
  }

  @Test
  public void listConstructor() throws Exception {
    execute(
        """
        x = type([1, 2, 3])
        y = type(x((1, 2, 3)))
        z = type(list((1, 2, 3)))
        """);
    assertEquals(Script.PyjList.class, getVariable(JavaClass.class, "x").type());
    assertEquals(Script.PyjList.class, getVariable(JavaClass.class, "y").type());
    assertEquals(Script.PyjList.class, getVariable(JavaClass.class, "z").type());
    assertEquals(Script.PyjList.class, getVariable(JavaClass.class, "list").type());
  }

  @Test
  public void dictConstructor() throws Exception {
    execute(
        """
        x = type({1: 2, 3: 4})
        y = type(x([[1, 2], [3, 4]]))
        z = type(dict([[1, 2], [3, 4]]))
        """);
    assertEquals(Script.PyjDict.class, getVariable(JavaClass.class, "x").type());
    assertEquals(Script.PyjDict.class, getVariable(JavaClass.class, "y").type());
    assertEquals(Script.PyjDict.class, getVariable(JavaClass.class, "z").type());
    assertEquals(Script.PyjDict.class, getVariable(JavaClass.class, "dict").type());
  }

  @Test
  public void javaStringConstructor() throws Exception {
    execute(
        """
        String = JavaClass("org.pyjinn.interpreter.JavaString")
        Byte = JavaClass("java.lang.Byte")
        some_bytes = JavaArray(
            (Byte.valueOf("104"),  # 'h'
             Byte.valueOf("101"),  # 'e'
             Byte.valueOf("108"),  # 'l'
             Byte.valueOf("108"),  # 'l'
             Byte.valueOf("111")), # 'o'
            Byte.TYPE)
        output = String(some_bytes)  # String(byte[])
        """);
    assertEquals("hello", getVariable(String.class, "output"));
  }

  @Test
  public void isinstance() throws Exception {
    execute(
        """
        @dataclass
        class Foo:
          x: int = 0

        @dataclass
        class Bar:
          x: int = 0

        foo = Foo()
        test1 = isinstance(foo, Foo)
        test2 = isinstance(foo, Bar)

        none_type = type(None)
        test3 = isinstance(None, none_type)

        test4 = isinstance(0, int)
        test5 = isinstance(0, float)
        test6 = isinstance(True, bool)
        test7 = isinstance(True, str)
        test8 = isinstance("foo", str)
        test9 = isinstance("foo", Foo)
        test10 = isinstance(None, str)
        """);
    assertEquals("NoneType", getVariable(Script.PyjClass.class, "none_type").name);
    assertTrue(getVariable(Boolean.class, "test1"));
    assertFalse(getVariable(Boolean.class, "test2"));
    assertTrue(getVariable(Boolean.class, "test3"));
    assertTrue(getVariable(Boolean.class, "test4"));
    assertFalse(getVariable(Boolean.class, "test5"));
    assertTrue(getVariable(Boolean.class, "test6"));
    assertFalse(getVariable(Boolean.class, "test7"));
    assertTrue(getVariable(Boolean.class, "test8"));
    assertFalse(getVariable(Boolean.class, "test9"));
    assertFalse(getVariable(Boolean.class, "test10"));
  }

  @Test
  public void addTuples() throws Exception {
    execute("output = (2, 4) + (6, 8)");
    var tuple = getVariable(Script.PyjTuple.class, "output");
    assertEquals(4, tuple.__len__());
    assertEquals(2, tuple.__getitem__(0));
    assertEquals(4, tuple.__getitem__(1));
    assertEquals(6, tuple.__getitem__(2));
    assertEquals(8, tuple.__getitem__(3));
  }

  @Test
  public void starExpansionInTupleLiteral() throws Exception {
    execute("output = (*(2, 4), *(6, 8))");
    var tuple = getVariable(Script.PyjTuple.class, "output");
    assertEquals(4, tuple.__len__());
    assertEquals(2, tuple.__getitem__(0));
    assertEquals(4, tuple.__getitem__(1));
    assertEquals(6, tuple.__getitem__(2));
    assertEquals(8, tuple.__getitem__(3));
  }

  @Test
  public void starExpansionInListLiteral() throws Exception {
    execute("output = [*(2, 4), *[6, 8]]");
    var list = getVariable(Script.PyjList.class, "output");
    assertEquals(4, list.__len__());
    assertEquals(2, list.__getitem__(0));
    assertEquals(4, list.__getitem__(1));
    assertEquals(6, list.__getitem__(2));
    assertEquals(8, list.__getitem__(3));
  }

  @Test
  public void returnMultipleValues() throws Exception {
    execute(
        """
        def foo(): return 1, 2
        output = foo()
        """);
    var tuple = getVariable(Script.PyjTuple.class, "output");
    assertEquals(2, tuple.__len__());
    assertEquals(1, tuple.__getitem__(0));
    assertEquals(2, tuple.__getitem__(1));
  }

  @Test
  public void compareTuples() throws Exception {
    execute(
        """
        a = (1, 2, 3) > (1, 2)
        b = (1, 2, 3) >= (1, 2)
        c = (1, 2, 4) < (1, 2, 4)
        d = (1, 2, 4) <= (1, 2, 4)
        e = (1, 2) >= (1, 2, 3)
        """);
    boolean a = getVariable(Boolean.class, "a");
    boolean b = getVariable(Boolean.class, "b");
    boolean c = getVariable(Boolean.class, "c");
    boolean d = getVariable(Boolean.class, "d");
    boolean e = getVariable(Boolean.class, "e");
    assertTrue(a);
    assertTrue(b);
    assertFalse(c);
    assertTrue(d);
    assertFalse(e);
  }

  @Test
  public void compareLists() throws Exception {
    execute(
        """
        a = [1, 2, 3] > [1, 2]
        b = [1, 2, 3] >= [1, 2]
        c = [1, 2, 4] < [1, 2, 4]
        d = [1, 2, 4] <= [1, 2, 4]
        e = [1, 2] >= [1, 2, 3]
        """);
    boolean a = getVariable(Boolean.class, "a");
    boolean b = getVariable(Boolean.class, "b");
    boolean c = getVariable(Boolean.class, "c");
    boolean d = getVariable(Boolean.class, "d");
    boolean e = getVariable(Boolean.class, "e");
    assertTrue(a);
    assertTrue(b);
    assertFalse(c);
    assertTrue(d);
    assertFalse(e);
  }

  public static int numberMethod(int x) {
    return x;
  }

  public static long numberMethod(long x) {
    return x;
  }

  public static float numberMethod(float x) {
    return x;
  }

  public static double numberMethod(double x) {
    return x;
  }

  public static float floatMethod(float x) {
    return x;
  }

  @Test
  public void numericConversions() throws Exception {
    execute(
        """
        ScriptTest = JavaClass("org.pyjinn.interpreter.ScriptTest")
        a = ScriptTest.numberMethod(1234)
        b = ScriptTest.numberMethod(12345678901)
        c = ScriptTest.numberMethod(JavaFloat(3.14))
        d = ScriptTest.numberMethod(3.14)
        e = ScriptTest.floatMethod(3.14)
        f = ScriptTest.numberMethod(JavaInt(12345678901))
        """);
    var a = getVariable(Integer.class, "a");
    var b = getVariable(Long.class, "b");
    var c = getVariable(Float.class, "c");
    var d = getVariable(Double.class, "d");
    var e = getVariable(Float.class, "e");
    var f = getVariable(Integer.class, "f");
    assertEquals(1234, a);
    assertEquals(12345678901L, b);
    assertEquals(3.14f, c);
    assertEquals(3.14, d);
    assertEquals(3.14f, e);
    assertEquals((int) 12345678901L, f);
  }

  @Test
  public void intLiterals() throws Exception {
    execute(
        """
        a = 123
        b = 0x123
        c = 12_345_678_901
        d = 0x123_4567_8901
        e = 0b111
        f = 0b11110000_11110000_11110000_11110000_11110000
        """);
    var a = getVariable(Integer.class, "a");
    var b = getVariable(Integer.class, "b");
    var c = getVariable(Long.class, "c");
    var d = getVariable(Long.class, "d");
    var e = getVariable(Integer.class, "e");
    var f = getVariable(Long.class, "f");
    assertEquals(123, a);
    assertEquals(0x123, b);
    assertEquals(12345678901L, c);
    assertEquals(0x12345678901L, d);
    assertEquals(0b111, e);
    assertEquals(0b1111000011110000111100001111000011110000L, f);
  }

  @Test
  public void intConstructor() throws Exception {
    execute(
        """
        a = int(123)
        b = int("1_23")
        c = int("1_23", 16)
        d = int(12_345_678_901)
        e = int("123_4567_8901", base=16)
        f = int("123", 0)
        g = int("0x123", 0)
        h = int("0b111", 0)
        i = int("1111000011110000111100001111000011110000", 2)
        """);
    var a = getVariable(Integer.class, "a");
    var b = getVariable(Integer.class, "b");
    var c = getVariable(Integer.class, "c");
    var d = getVariable(Long.class, "d");
    var e = getVariable(Long.class, "e");
    var f = getVariable(Integer.class, "f");
    var g = getVariable(Integer.class, "g");
    var h = getVariable(Integer.class, "h");
    var i = getVariable(Long.class, "i");
    assertEquals(123, a);
    assertEquals(123, b);
    assertEquals(0x123, c);
    assertEquals(12345678901L, d);
    assertEquals(0x12345678901L, e);
    assertEquals(123, f);
    assertEquals(0x123, g);
    assertEquals(0b111, h);
    assertEquals(0b1111000011110000111100001111000011110000L, i);
  }

  @Test
  public void pow() throws Exception {
    execute(
        """
        a = 2 ** 0
        b = 2 ** 2
        c = 2 ** 8
        d = 3 ** 2
        e = 6 ** 3
        f = 2 ** 30
        g = 2 ** 31
        h = 2 ** 32
        i = 2 ** 60
        j = 2 ** 64
        """);
    var a = getVariable(Integer.class, "a");
    var b = getVariable(Integer.class, "b");
    var c = getVariable(Integer.class, "c");
    var d = getVariable(Integer.class, "d");
    var e = getVariable(Integer.class, "e");
    var f = getVariable(Integer.class, "f");
    var g = getVariable(Long.class, "g");
    var h = getVariable(Long.class, "h");
    var i = getVariable(Long.class, "i");
    var j = getVariable(Double.class, "j");
    assertEquals(1, a);
    assertEquals(4, b);
    assertEquals(256, c);
    assertEquals(9, d);
    assertEquals(216, e);
    assertEquals(1073741824, f);
    assertEquals(2147483648L, g);
    assertEquals(4294967296L, h);
    assertEquals(1152921504606846976L, i);
    assertEquals(1.8446744073709552E19, j);
  }

  private <T> T getVariable(Class<T> clazz, String variableName) {
    Object object = env.get(variableName);
    assertNotNull(object);
    assertInstanceOf(clazz, object);
    return clazz.cast(object);
  }

  private void assertVariableValue(Object expectedValue, String variableName) {
    Object object = env.get(variableName);
    assertNotNull(object);
    assertEquals(expectedValue, object);
  }

  private Script.Environment execute(String source) throws Exception {
    var jsonAst = PyjinnParser.parse("script_test.pyj", source);
    script = new Script();
    script.parse(jsonAst).exec();
    env = script.mainModule().globals();
    return env;
  }

  // TODO(maxuser): Add tests for:
  // - classes
  // - dataclasses (mutable @dataclass, immutable @dataclass(Frozen=True))
  // - classes with custom __init__ method
  // - assignment to class instance fields
  // - assignment to class-level fields
  // - calling instance methods
  // - calling static methods
  // - calling class methods
  // - indexing string, e.g. "foo"[0] == "f"
  // - dict methods: keys(), values(), setdefault(key, default=None)
  // - iterability of strings with for/list()/tuple()/enumerate()
  // - new built-in functions: abs, round, min, max, ord, chr, enumerate
  // - raising Python-defined exception types
  // - catching exceptions without a declared type or variable name
  // - __getitem__ operator with slices: items[lower:upper:step] (step not implemented)
  // - slice notation for Java List, String, and array types
  // - `is`, `is not`, and `not in` binary in-fix operators
  // - tuple and list constructors which take no params or String, array, or Iterable<?>
  // - tuple assignment from Java array
  // - enforce immutability of tuples (unless Java array is explicitly accessed)
  // - fix bug where -= was behaving like +=
  // - passing script functions to Java methods taking functional params like Predicate<T>
  // - when checking methods of a non-public class, search for a public interface or superclass
  // - bound Python methods, e.g. `f = obj.func` where obj.func(...) is invoked by f(...)
  // - AugAssign for field setters of PyObject, e.g. `self.x += 1`
  // - empty Java array treated as False in a bool context
  // - function defs and calls with default args and kwargs

  // TODO(maxuser): Implement parity for most common str methods
}
