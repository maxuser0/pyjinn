// SPDX-FileCopyrightText: © 2025 Greg Christiana <maxuser@pyjinn.org>
// SPDX-License-Identifier: MIT

package org.pyjinn.parser;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

// TODO(maxsuer): Add tests for:
// del x
// del x.y
// del x[y]
// if not x: y
// for x in y: break
// x: y = z
// x: y = z, w
// x = z, w
// -1.2
// ~1
// blockAttrs = "" if match.group(2) is None else match.group(2)
// "" # should result in an empty str constant, not empty JoinedStr
// return # AST node should have a null value
// for i, x in y, z: w # both i, x and y, z should be tuples in AST
// global x
// global x, y
// raise x
// def f(x, y=None): return
// lambda: x # lambda with no args
//
// try:
//   x
// except:
//   w
// finally:
//  z
//
// try:
//   x
// except y as z:
//   w

class PyjinnParserTest {

  @Test
  void intConstant() throws Exception {
    var parserOutput =
        PyjinnParser.parseTrees(
            """
            123
            """);
    var ast = parserOutput.jsonAst();
    assertConstantValue("int", v -> v.getAsInt() == 123, ast);
  }

  @Test
  void hexIntConstant() throws Exception {
    var parserOutput =
        PyjinnParser.parseTrees(
            """
            0x123abc
            """);
    var ast = parserOutput.jsonAst();
    assertConstantValue("int", v -> v.getAsInt() == 0x123abc, ast);
  }

  @Test
  void maxHexIntConstant() throws Exception {
    var parserOutput =
        PyjinnParser.parseTrees(
            """
            0xffffffff
            """);
    var ast = parserOutput.jsonAst();
    assertConstantValue("int", v -> v.getAsInt() == 0xffffffff, ast);
  }

  @Test
  void minHexLongConstant() throws Exception {
    var parserOutput =
        PyjinnParser.parseTrees(
            """
            0x100000000
            """);
    var ast = parserOutput.jsonAst();
    assertConstantValue("int", v -> v.getAsLong() == 0x100000000L, ast);
  }

  @Test
  void floatConstant() throws Exception {
    var parserOutput =
        PyjinnParser.parseTrees(
            """
            123.
            """);
    var ast = parserOutput.jsonAst();
    double epsilon = 0.00000001;
    assertConstantValue("float", v -> Math.abs(v.getAsDouble() - 123.) < epsilon, ast);
  }

  @Test
  void boolConstant() throws Exception {
    var parserOutput =
        PyjinnParser.parseTrees(
            """
            True
            """);
    var ast = parserOutput.jsonAst();
    assertConstantValue("bool", JsonElement::getAsBoolean, ast);
  }

  @Test
  void noneConstant() throws Exception {
    var parserOutput =
        PyjinnParser.parseTrees(
            """
            None
            """);
    var ast = parserOutput.jsonAst();
    assertConstantValue("NoneType", JsonElement::isJsonNull, ast);
  }

  private void assertConstantValue(
      String expectedTypename, Predicate<JsonElement> expectedValue, JsonElement actual) {
    assertTrue(actual.isJsonObject());
    JsonObject actualObj = actual.getAsJsonObject();
    assertEquals("Module", actualObj.get("type").getAsString());

    JsonElement body = actualObj.get("body");
    assertTrue(body.isJsonArray());
    assertEquals(1, body.getAsJsonArray().size());

    JsonObject expr = body.getAsJsonArray().get(0).getAsJsonObject();
    assertEquals("Expr", expr.get("type").getAsString());

    JsonObject value = expr.get("value").getAsJsonObject();
    assertEquals("Constant", value.get("type").getAsString());
    assertEquals(expectedTypename, value.get("typename").getAsString());
    assertTrue(expectedValue.test(value.get("value")));
  }

  @Test
  void plusEqualAttribute() throws Exception {
    var parserOutput =
        PyjinnParser.parseTrees(
            """
            x.y += z
            """);
    var ast = parserOutput.jsonAst();

    var augAssign = getSingletonStatement(ast).getAsJsonObject();
    assertEquals("AugAssign", augAssign.get("type").getAsString());

    var target = augAssign.get("target").getAsJsonObject();
    assertEquals("Attribute", target.get("type").getAsString());

    var targetValue = target.get("value").getAsJsonObject();
    assertName("x", targetValue);

    assertEquals("y", target.get("attr").getAsString());

    var op = augAssign.get("op").getAsJsonObject();
    assertEquals("Add", op.get("type").getAsString());

    var value = augAssign.get("value").getAsJsonObject();
    assertName("z", value);
  }

  @Test
  void plusEqualSubscript() throws Exception {
    var parserOutput =
        PyjinnParser.parseTrees(
            """
            x[y] += z
            """);
    var ast = parserOutput.jsonAst();

    var augAssign = getSingletonStatement(ast).getAsJsonObject();
    assertEquals("AugAssign", augAssign.get("type").getAsString());

    var target = augAssign.get("target").getAsJsonObject();
    assertEquals("Subscript", target.get("type").getAsString());

    var targetValue = target.get("value").getAsJsonObject();
    assertName("x", targetValue);

    var targetSlice = target.get("slice").getAsJsonObject();
    assertName("y", targetSlice);

    var op = augAssign.get("op").getAsJsonObject();
    assertEquals("Add", op.get("type").getAsString());

    var value = augAssign.get("value").getAsJsonObject();
    assertName("z", value);
  }

  @Test
  void plusEqual() throws Exception {
    var parserOutput =
        PyjinnParser.parseTrees(
            """
            x += y
            """);
    var ast = parserOutput.jsonAst();

    var augAssign = getSingletonStatement(ast).getAsJsonObject();
    assertEquals("AugAssign", augAssign.get("type").getAsString());

    var target = augAssign.get("target").getAsJsonObject();
    assertName("x", target);

    var op = augAssign.get("op").getAsJsonObject();
    assertEquals("Add", op.get("type").getAsString());

    var value = augAssign.get("value").getAsJsonObject();
    assertName("y", value);
  }

  @Test
  void minusEqual() throws Exception {
    var parserOutput =
        PyjinnParser.parseTrees(
            """
            x -= y
            """);
    var ast = parserOutput.jsonAst();

    var augAssign = getSingletonStatement(ast).getAsJsonObject();
    assertEquals("AugAssign", augAssign.get("type").getAsString());

    var target = augAssign.get("target").getAsJsonObject();
    assertName("x", target);

    var op = augAssign.get("op").getAsJsonObject();
    assertEquals("Sub", op.get("type").getAsString());

    var value = augAssign.get("value").getAsJsonObject();
    assertName("y", value);
  }

  @Test
  void timesEqual() throws Exception {
    var parserOutput =
        PyjinnParser.parseTrees(
            """
            x *= y
            """);
    var ast = parserOutput.jsonAst();

    var augAssign = getSingletonStatement(ast).getAsJsonObject();
    assertEquals("AugAssign", augAssign.get("type").getAsString());

    var target = augAssign.get("target").getAsJsonObject();
    assertName("x", target);

    var op = augAssign.get("op").getAsJsonObject();
    assertEquals("Mult", op.get("type").getAsString());

    var value = augAssign.get("value").getAsJsonObject();
    assertName("y", value);
  }

  @Test
  void divideEqual() throws Exception {
    var parserOutput =
        PyjinnParser.parseTrees(
            """
            x /= y
            """);
    var ast = parserOutput.jsonAst();

    var augAssign = getSingletonStatement(ast).getAsJsonObject();
    assertEquals("AugAssign", augAssign.get("type").getAsString());

    var target = augAssign.get("target").getAsJsonObject();
    assertName("x", target);

    var op = augAssign.get("op").getAsJsonObject();
    assertEquals("Div", op.get("type").getAsString());

    var value = augAssign.get("value").getAsJsonObject();
    assertName("y", value);
  }

  @Test
  void listComprehension() throws Exception {
    var parserOutput =
        PyjinnParser.parseTrees(
            """
            u = [x + y for x in z]
            """);
    var ast = parserOutput.jsonAst();

    var assign = getSingletonStatement(ast).getAsJsonObject();
    assertEquals("Assign", assign.get("type").getAsString());

    var targets = assign.get("targets").getAsJsonArray();
    assertEquals(1, targets.size());

    var target = targets.get(0).getAsJsonObject();
    assertName("u", target);

    var value = assign.get("value").getAsJsonObject();
    assertEquals("ListComp", value.get("type").getAsString());

    var elt = value.get("elt").getAsJsonObject();
    assertEquals("BinOp", elt.get("type").getAsString());

    var left = elt.get("left").getAsJsonObject();
    assertName("x", left);

    var op = elt.get("op").getAsJsonObject();
    assertEquals("Add", op.get("type").getAsString());

    var right = elt.get("right").getAsJsonObject();
    assertName("y", right);

    var generators = value.get("generators").getAsJsonArray();
    assertEquals(1, generators.size());

    var comprehension = generators.get(0).getAsJsonObject();
    assertEquals("comprehension", comprehension.get("type").getAsString());

    var targetComp = comprehension.get("target").getAsJsonObject();
    assertName("x", targetComp);

    var iter = comprehension.get("iter").getAsJsonObject();
    assertName("z", iter);

    var ifs = comprehension.get("ifs").getAsJsonArray();
    assertEquals(0, ifs.size());

    // TODO(maxuser): Support "is_async" attr.
    // assertEquals(0, comprehension.get("is_async").getAsInt());
  }

  @Test
  void listComprehensionWithIfs() throws Exception {
    var parserOutput =
        PyjinnParser.parseTrees(
            """
            u = [x + y for x in z if v]
            """);
    var ast = parserOutput.jsonAst();

    var assign = getSingletonStatement(ast).getAsJsonObject();
    assertEquals("Assign", assign.get("type").getAsString());

    var targets = assign.get("targets").getAsJsonArray();
    assertEquals(1, targets.size());

    var target = targets.get(0).getAsJsonObject();
    assertName("u", target);

    var value = assign.get("value").getAsJsonObject();
    assertEquals("ListComp", value.get("type").getAsString());

    var elt = value.get("elt").getAsJsonObject();
    assertEquals("BinOp", elt.get("type").getAsString());

    var left = elt.get("left").getAsJsonObject();
    assertName("x", left);

    var op = elt.get("op").getAsJsonObject();
    assertEquals("Add", op.get("type").getAsString());

    var right = elt.get("right").getAsJsonObject();
    assertName("y", right);

    var generators = value.get("generators").getAsJsonArray();
    assertEquals(1, generators.size());

    var comprehension = generators.get(0).getAsJsonObject();
    assertEquals("comprehension", comprehension.get("type").getAsString());

    var targetComp = comprehension.get("target").getAsJsonObject();
    assertName("x", targetComp);

    var iter = comprehension.get("iter").getAsJsonObject();
    assertName("z", iter);

    var ifs = comprehension.get("ifs").getAsJsonArray();
    assertEquals(1, ifs.size());

    assertName("v", ifs.get(0).getAsJsonObject());

    // TODO(maxuser): Support "is_async" attr.
    // assertEquals(0, comprehension.get("is_async").getAsInt());
  }

  @Test
  void stringInterpolation() throws Exception {
    var parserOutput =
        PyjinnParser.parseTrees(
            """
            "From %s to %s" % (x, y)
            """);
    var ast = parserOutput.jsonAst();

    var expr = getSingletonStatement(ast).getAsJsonObject();
    assertEquals("Expr", expr.get("type").getAsString());

    var value = expr.get("value").getAsJsonObject();
    assertEquals("BinOp", value.get("type").getAsString());

    var left = value.get("left").getAsJsonObject();
    assertEquals("Constant", left.get("type").getAsString());
    assertEquals("From %s to %s", left.get("value").getAsString());
    assertEquals("str", left.get("typename").getAsString());

    var op = value.get("op").getAsJsonObject();
    assertEquals("Mod", op.get("type").getAsString());

    var right = value.get("right").getAsJsonObject();
    assertEquals("Tuple", right.get("type").getAsString());

    var elts = right.get("elts").getAsJsonArray();
    assertEquals(2, elts.size());

    assertName("x", elts.get(0).getAsJsonObject());
    assertName("y", elts.get(1).getAsJsonObject());
  }

  @Test
  void mixedFString() throws Exception {
    var parserOutput =
        PyjinnParser.parseTrees(
            """
            "a" f'b\\t{c}d' "e"
            """);
    var ast = parserOutput.jsonAst();

    var expr = getSingletonStatement(ast).getAsJsonObject();
    assertEquals("Expr", expr.get("type").getAsString());

    var value = expr.get("value").getAsJsonObject();
    assertEquals("JoinedStr", value.get("type").getAsString());

    var values = value.get("values").getAsJsonArray();
    assertEquals(3, values.size());

    var constant1 = values.get(0).getAsJsonObject();
    assertEquals("Constant", constant1.get("type").getAsString());
    assertEquals("ab\t", constant1.get("value").getAsString());
    assertEquals("str", constant1.get("typename").getAsString());

    var formattedValue = values.get(1).getAsJsonObject();
    assertEquals("FormattedValue", formattedValue.get("type").getAsString());

    var formattedValueInner = formattedValue.get("value").getAsJsonObject();
    assertName("c", formattedValueInner);

    // TODO(maxuser): Support "conversion" and "format_spec" attrs.
    // assertEquals(-1, formattedValue.get("conversion").getAsInt());
    // assertTrue(formattedValue.get("format_spec") instanceof JsonNull);

    var constant2 = values.get(2).getAsJsonObject();
    assertEquals("Constant", constant2.get("type").getAsString());
    assertEquals("de", constant2.get("value").getAsString());
    assertEquals("str", constant2.get("typename").getAsString());
  }

  @Test
  void fString() throws Exception {
    var parserOutput =
        PyjinnParser.parseTrees(
            """
            f'{x}-{y}'
            """);
    var ast = parserOutput.jsonAst();

    var expr = getSingletonStatement(ast).getAsJsonObject();
    assertEquals("Expr", expr.get("type").getAsString());

    var value = expr.get("value").getAsJsonObject();
    assertEquals("JoinedStr", value.get("type").getAsString());

    var values = value.get("values").getAsJsonArray();
    assertEquals(3, values.size());

    var formattedValue1 = values.get(0).getAsJsonObject();
    assertEquals("FormattedValue", formattedValue1.get("type").getAsString());
    var formattedValue1Inner = formattedValue1.get("value").getAsJsonObject();
    assertName("x", formattedValue1Inner);
    // TODO(maxuser): Support "conversion" and "format_spec" attrs.
    // assertEquals(-1, formattedValue1.get("conversion").getAsInt());
    // assertTrue(formattedValue1.get("format_spec") instanceof JsonNull);

    var constant = values.get(1).getAsJsonObject();
    assertEquals("Constant", constant.get("type").getAsString());
    assertEquals("-", constant.get("value").getAsString());
    assertEquals("str", constant.get("typename").getAsString());

    var formattedValue2 = values.get(2).getAsJsonObject();
    assertEquals("FormattedValue", formattedValue2.get("type").getAsString());
    var formattedValue2Inner = formattedValue2.get("value").getAsJsonObject();
    assertName("y", formattedValue2Inner);
    // TODO(maxuser): Support "conversion" and "format_spec" attrs.
    // assertEquals(-1, formattedValue2.get("conversion").getAsInt());
    // assertTrue(formattedValue2.get("format_spec") instanceof JsonNull);
  }

  @Test
  void multipartString() throws Exception {
    var parserOutput =
        PyjinnParser.parseTrees(
            """
            'mixed' "quotes"
            """);
    var ast = parserOutput.jsonAst();

    var expr = getSingletonStatement(ast).getAsJsonObject();
    assertEquals("Expr", expr.get("type").getAsString());

    var value = expr.get("value").getAsJsonObject();
    assertEquals("Constant", value.get("type").getAsString());
    assertEquals("mixedquotes", value.get("value").getAsString());
    assertEquals("str", value.get("typename").getAsString());
  }

  @Test
  void singleQuotedString() throws Exception {
    var parserOutput =
        PyjinnParser.parseTrees(
            """
            'this is a "test"'
            """);
    var ast = parserOutput.jsonAst();

    var expr = getSingletonStatement(ast).getAsJsonObject();
    assertEquals("Expr", expr.get("type").getAsString());

    var value = expr.get("value").getAsJsonObject();
    assertEquals("Constant", value.get("type").getAsString());
    assertEquals("this is a \"test\"", value.get("value").getAsString());
    assertEquals("str", value.get("typename").getAsString());
  }

  @Test
  void doubleQuotedString() throws Exception {
    var parserOutput =
        PyjinnParser.parseTrees(
            """
            "this is a 'test'"
            """);
    var ast = parserOutput.jsonAst();

    var expr = getSingletonStatement(ast).getAsJsonObject();
    assertEquals("Expr", expr.get("type").getAsString());

    var value = expr.get("value").getAsJsonObject();
    assertEquals("Constant", value.get("type").getAsString());
    assertEquals("this is a 'test'", value.get("value").getAsString());
    assertEquals("str", value.get("typename").getAsString());
  }

  @Test
  void rawString() throws Exception {
    var parserOutput =
        PyjinnParser.parseTrees(
            """
            r"this\\tis\\na \\"test\\""
            """);
    var ast = parserOutput.jsonAst();

    var expr = getSingletonStatement(ast).getAsJsonObject();
    assertEquals("Expr", expr.get("type").getAsString());

    var value = expr.get("value").getAsJsonObject();
    assertEquals("Constant", value.get("type").getAsString());
    assertEquals("this\\tis\\na \\\"test\\\"", value.get("value").getAsString());
    assertEquals("str", value.get("typename").getAsString());
  }

  @Test
  void stringWithEscapeSequences() throws Exception {
    var parserOutput =
        PyjinnParser.parseTrees(
            """
            "this\\tis\\na \\"test\\""
            """);
    var ast = parserOutput.jsonAst();

    var expr = getSingletonStatement(ast).getAsJsonObject();
    assertEquals("Expr", expr.get("type").getAsString());

    var value = expr.get("value").getAsJsonObject();
    assertEquals("Constant", value.get("type").getAsString());
    assertEquals("this\tis\na \"test\"", value.get("value").getAsString());
    assertEquals("str", value.get("typename").getAsString());
  }

  @Test
  void tripleSingleQuotedString() throws Exception {
    var parserOutput =
        PyjinnParser.parseTrees(
            """
            '''
            this is 'a'
            "test"
            '''
            """);
    var ast = parserOutput.jsonAst();

    var expr = getSingletonStatement(ast).getAsJsonObject();
    assertEquals("Expr", expr.get("type").getAsString());

    var value = expr.get("value").getAsJsonObject();
    assertEquals("Constant", value.get("type").getAsString());
    assertEquals("\nthis is 'a'\n\"test\"\n", value.get("value").getAsString());
    assertEquals("str", value.get("typename").getAsString());
  }

  @Test
  void tripleDoubleQuotedString() throws Exception {
    var parserOutput =
        PyjinnParser.parseTrees(
            """
            \"\"\"
            this is 'a'
            "test"
            \"\"\"
            """);
    var ast = parserOutput.jsonAst();

    var expr = getSingletonStatement(ast).getAsJsonObject();
    assertEquals("Expr", expr.get("type").getAsString());

    var value = expr.get("value").getAsJsonObject();
    assertEquals("Constant", value.get("type").getAsString());
    assertEquals("\nthis is 'a'\n\"test\"\n", value.get("value").getAsString());
    assertEquals("str", value.get("typename").getAsString());
  }

  @Test
  void factorialFunction() throws Exception {
    var parserOutput =
        PyjinnParser.parseTrees(
            """
            def factorial(n):
              if n == 0:
                return 1
              else:
                return n * factorial(n - 1)
            """);
    var ast = parserOutput.jsonAst();

    // TODO(maxuser): Add assertion for presence of empty decorator_list.
    var funcDef = getSingletonStatement(ast).getAsJsonObject();
    assertEquals("FunctionDef", funcDef.get("type").getAsString());
    assertEquals("factorial", funcDef.get("name").getAsString());

    var args = funcDef.get("args").getAsJsonObject();
    assertEquals("arguments", args.get("type").getAsString());

    var argsList = args.get("args").getAsJsonArray();
    assertEquals(1, argsList.size());
    assertEquals("arg", argsList.get(0).getAsJsonObject().get("type").getAsString());
    assertEquals("n", argsList.get(0).getAsJsonObject().get("arg").getAsString());

    var body = funcDef.get("body").getAsJsonArray();
    assertEquals(1, body.size());

    var ifStmt = body.get(0).getAsJsonObject();
    assertEquals("If", ifStmt.get("type").getAsString());

    var test = ifStmt.get("test").getAsJsonObject();
    assertEquals("Compare", test.get("type").getAsString());

    var left = test.get("left").getAsJsonObject();
    assertName("n", left);

    var ops = test.get("ops").getAsJsonArray();
    assertEquals(1, ops.size());
    assertEquals("Eq", ops.get(0).getAsJsonObject().get("type").getAsString());

    var comparators = test.get("comparators").getAsJsonArray();
    assertEquals(1, comparators.size());
    var comparator = comparators.get(0).getAsJsonObject();
    assertEquals("Constant", comparator.get("type").getAsString());
    assertEquals(0, comparator.get("value").getAsInt());

    var ifBody = ifStmt.get("body").getAsJsonArray();
    assertEquals(1, ifBody.size());

    var returnStmt1 = ifBody.get(0).getAsJsonObject();
    assertEquals("Return", returnStmt1.get("type").getAsString());

    var returnValue1 = returnStmt1.get("value").getAsJsonObject();
    assertEquals("Constant", returnValue1.get("type").getAsString());
    assertEquals(1, returnValue1.get("value").getAsInt());

    var orelse = ifStmt.get("orelse").getAsJsonArray();
    assertEquals(1, orelse.size());

    var returnStmt2 = orelse.get(0).getAsJsonObject();
    assertEquals("Return", returnStmt2.get("type").getAsString());

    var returnValue2 = returnStmt2.get("value").getAsJsonObject();
    assertEquals("BinOp", returnValue2.get("type").getAsString());

    var binOpLeft = returnValue2.get("left").getAsJsonObject();
    assertName("n", binOpLeft);

    var binOpOp = returnValue2.get("op").getAsJsonObject();
    assertEquals("Mult", binOpOp.get("type").getAsString());

    var binOpRight = returnValue2.get("right").getAsJsonObject();
    assertEquals("Call", binOpRight.get("type").getAsString());

    var callFunc = binOpRight.get("func").getAsJsonObject();
    assertName("factorial", callFunc);

    var callArgs = binOpRight.get("args").getAsJsonArray();
    assertEquals(1, callArgs.size());

    var callArg = callArgs.get(0).getAsJsonObject();
    assertEquals("BinOp", callArg.get("type").getAsString());

    var callArgLeft = callArg.get("left").getAsJsonObject();
    assertName("n", callArgLeft);

    var callArgOp = callArg.get("op").getAsJsonObject();
    assertEquals("Sub", callArgOp.get("type").getAsString());

    var callArgRight = callArg.get("right").getAsJsonObject();
    assertEquals("Constant", callArgRight.get("type").getAsString());
    assertEquals(1, callArgRight.get("value").getAsInt());
  }

  @Test
  void forStatement() throws Exception {
    var parserOutput =
        PyjinnParser.parseTrees(
            """
            for x in y:
              z
            """);
    var ast = parserOutput.jsonAst();

    var forStmt = getSingletonStatement(ast).getAsJsonObject();
    assertEquals("For", forStmt.get("type").getAsString());

    var target = forStmt.get("target").getAsJsonObject();
    assertName("x", target);

    var iter = forStmt.get("iter").getAsJsonObject();
    assertName("y", iter);

    var body = forStmt.get("body").getAsJsonArray();
    assertEquals(1, body.size());

    var expr = body.get(0).getAsJsonObject();
    assertEquals("Expr", expr.get("type").getAsString());

    var value = expr.get("value").getAsJsonObject();
    assertName("z", value);

    var orelse = forStmt.get("orelse").getAsJsonArray();
    assertEquals(0, orelse.size());
  }

  @Test
  void whileStatement() throws Exception {
    var parserOutput =
        PyjinnParser.parseTrees(
            """
            while x:
              y
            """);
    var ast = parserOutput.jsonAst();

    var whileStmt = getSingletonStatement(ast).getAsJsonObject();
    assertEquals("While", whileStmt.get("type").getAsString());

    var test = whileStmt.get("test").getAsJsonObject();
    assertName("x", test);

    var body = whileStmt.get("body").getAsJsonArray();
    assertEquals(1, body.size());

    var expr = body.get(0).getAsJsonObject();
    assertEquals("Expr", expr.get("type").getAsString());

    var value = expr.get("value").getAsJsonObject();
    assertName("y", value);

    var orelse = whileStmt.get("orelse").getAsJsonArray();
    assertEquals(0, orelse.size());
  }

  @Test
  void ifElifChainStatement() throws Exception {
    var parserOutput =
        PyjinnParser.parseTrees(
            """
            if a:
              b
            elif c:
              d
            elif e:
              f
            else:
              g
            """);
    var ast = parserOutput.jsonAst();

    var ifStmt = getSingletonStatement(ast).getAsJsonObject();
    assertEquals("If", ifStmt.get("type").getAsString());

    var test = ifStmt.get("test").getAsJsonObject();
    assertName("a", test);

    var body = ifStmt.get("body").getAsJsonArray();
    assertEquals(1, body.size());

    var expr = body.get(0).getAsJsonObject();
    assertEquals("Expr", expr.get("type").getAsString());

    var value = expr.get("value").getAsJsonObject();
    assertName("b", value);

    var orelse = ifStmt.get("orelse").getAsJsonArray();
    assertEquals(1, orelse.size());

    var elifStmt1 = orelse.get(0).getAsJsonObject();
    assertEquals("If", elifStmt1.get("type").getAsString());

    var elifTest1 = elifStmt1.get("test").getAsJsonObject();
    assertName("c", elifTest1);

    var elifBody1 = elifStmt1.get("body").getAsJsonArray();
    assertEquals(1, elifBody1.size());

    var elifExpr1 = elifBody1.get(0).getAsJsonObject();
    assertEquals("Expr", elifExpr1.get("type").getAsString());

    var elifValue1 = elifExpr1.get("value").getAsJsonObject();
    assertName("d", elifValue1);

    var elifOrelse1 = elifStmt1.get("orelse").getAsJsonArray();
    assertEquals(1, elifOrelse1.size());

    var elifStmt2 = elifOrelse1.get(0).getAsJsonObject();
    assertEquals("If", elifStmt2.get("type").getAsString());

    var elifTest2 = elifStmt2.get("test").getAsJsonObject();
    assertName("e", elifTest2);

    var elifBody2 = elifStmt2.get("body").getAsJsonArray();
    assertEquals(1, elifBody2.size());

    var elifExpr2 = elifBody2.get(0).getAsJsonObject();
    assertEquals("Expr", elifExpr2.get("type").getAsString());

    var elifValue2 = elifExpr2.get("value").getAsJsonObject();
    assertName("f", elifValue2);

    var elifOrelse2 = elifStmt2.get("orelse").getAsJsonArray();
    assertEquals(1, elifOrelse2.size());

    var elseExpr = elifOrelse2.get(0).getAsJsonObject();
    assertEquals("Expr", elseExpr.get("type").getAsString());

    var elseValue = elseExpr.get("value").getAsJsonObject();
    assertName("g", elseValue);
  }

  @Test
  void ifElifStatement() throws Exception {
    var parserOutput =
        PyjinnParser.parseTrees(
            """
            if a:
              b
            elif c:
              d
            """);
    var ast = parserOutput.jsonAst();

    var ifStmt = getSingletonStatement(ast).getAsJsonObject();
    assertEquals("If", ifStmt.get("type").getAsString());

    var test = ifStmt.get("test").getAsJsonObject();
    assertName("a", test);

    var body = ifStmt.get("body").getAsJsonArray();
    assertEquals(1, body.size());

    var expr = body.get(0).getAsJsonObject();
    assertEquals("Expr", expr.get("type").getAsString());

    var value = expr.get("value").getAsJsonObject();
    assertName("b", value);

    var orelse = ifStmt.get("orelse").getAsJsonArray();
    assertEquals(1, orelse.size());

    var elifStmt = orelse.get(0).getAsJsonObject();
    assertEquals("If", elifStmt.get("type").getAsString());

    var elifTest = elifStmt.get("test").getAsJsonObject();
    assertName("c", elifTest);

    var elifBody = elifStmt.get("body").getAsJsonArray();
    assertEquals(1, elifBody.size());

    var elifExpr = elifBody.get(0).getAsJsonObject();
    assertEquals("Expr", elifExpr.get("type").getAsString());

    var elifValue = elifExpr.get("value").getAsJsonObject();
    assertName("d", elifValue);

    var elifOrelse = elifStmt.get("orelse").getAsJsonArray();
    assertEquals(0, elifOrelse.size());
  }

  @Test
  void ifElseStatement() throws Exception {
    var parserOutput =
        PyjinnParser.parseTrees(
            """
            if x:
              y
            else:
              z
            """);
    var ast = parserOutput.jsonAst();

    var ifStmt = getSingletonStatement(ast).getAsJsonObject();
    assertEquals("If", ifStmt.get("type").getAsString());

    var test = ifStmt.get("test").getAsJsonObject();
    assertName("x", test);

    var body = ifStmt.get("body").getAsJsonArray();
    assertEquals(1, body.size());

    var expr = body.get(0).getAsJsonObject();
    assertEquals("Expr", expr.get("type").getAsString());

    var value = expr.get("value").getAsJsonObject();
    assertName("y", value);

    var orelse = ifStmt.get("orelse").getAsJsonArray();
    assertEquals(1, orelse.size());

    var elseExpr = orelse.get(0).getAsJsonObject();
    assertEquals("Expr", elseExpr.get("type").getAsString());

    var elseValue = elseExpr.get("value").getAsJsonObject();
    assertName("z", elseValue);
  }

  @Test
  void ifStatement() throws Exception {
    var parserOutput =
        PyjinnParser.parseTrees(
            """
            if x:
              y
            """);
    var ast = parserOutput.jsonAst();

    var ifStmt = getSingletonStatement(ast).getAsJsonObject();
    assertEquals("If", ifStmt.get("type").getAsString());

    var test = ifStmt.get("test").getAsJsonObject();
    assertName("x", test);

    var body = ifStmt.get("body").getAsJsonArray();
    assertEquals(1, body.size());

    var expr = body.get(0).getAsJsonObject();
    assertEquals("Expr", expr.get("type").getAsString());

    var value = expr.get("value").getAsJsonObject();
    assertName("y", value);

    var orelse = ifStmt.get("orelse").getAsJsonArray();
    assertEquals(0, orelse.size());
  }

  @Test
  void subscriptFieldFunctionCallMedley() throws Exception {
    var parserOutput =
        PyjinnParser.parseTrees(
            """
            u[v](w).x(y)[z] = a[b](c).d(e)[f]
            """);
    var ast = parserOutput.jsonAst();

    var assign = getSingletonStatement(ast).getAsJsonObject();
    assertEquals("Assign", assign.get("type").getAsString());

    var targets = assign.get("targets").getAsJsonArray();
    assertEquals(1, targets.size());

    var targetSubscript = targets.get(0).getAsJsonObject();
    assertEquals("Subscript", targetSubscript.get("type").getAsString());

    var targetCall = targetSubscript.get("value").getAsJsonObject();
    assertEquals("Call", targetCall.get("type").getAsString());

    var targetFunc = targetCall.get("func").getAsJsonObject();
    assertEquals("Attribute", targetFunc.get("type").getAsString());

    var targetFuncValue = targetFunc.get("value").getAsJsonObject();
    assertEquals("Call", targetFuncValue.get("type").getAsString());

    var targetFuncValueFunc = targetFuncValue.get("func").getAsJsonObject();
    assertEquals("Subscript", targetFuncValueFunc.get("type").getAsString());

    var targetFuncValueFuncValue = targetFuncValueFunc.get("value").getAsJsonObject();
    assertName("u", targetFuncValueFuncValue);

    var targetFuncValueFuncSlice = targetFuncValueFunc.get("slice").getAsJsonObject();
    assertName("v", targetFuncValueFuncSlice);

    var targetFuncValueArgs = targetFuncValue.get("args").getAsJsonArray();
    assertEquals(1, targetFuncValueArgs.size());
    assertName("w", targetFuncValueArgs.get(0).getAsJsonObject());

    assertEquals("x", targetFunc.get("attr").getAsString());

    var targetCallArgs = targetCall.get("args").getAsJsonArray();
    assertEquals(1, targetCallArgs.size());
    assertName("y", targetCallArgs.get(0).getAsJsonObject());

    var targetSubscriptSlice = targetSubscript.get("slice").getAsJsonObject();
    assertName("z", targetSubscriptSlice);

    var valueSubscript = assign.get("value").getAsJsonObject();
    assertEquals("Subscript", valueSubscript.get("type").getAsString());

    var valueCall = valueSubscript.get("value").getAsJsonObject();
    assertEquals("Call", valueCall.get("type").getAsString());

    var valueFunc = valueCall.get("func").getAsJsonObject();
    assertEquals("Attribute", valueFunc.get("type").getAsString());

    var valueFuncValue = valueFunc.get("value").getAsJsonObject();
    assertEquals("Call", valueFuncValue.get("type").getAsString());

    var valueFuncValueFunc = valueFuncValue.get("func").getAsJsonObject();
    assertEquals("Subscript", valueFuncValueFunc.get("type").getAsString());

    var valueFuncValueFuncValue = valueFuncValueFunc.get("value").getAsJsonObject();
    assertName("a", valueFuncValueFuncValue);

    var valueFuncValueFuncSlice = valueFuncValueFunc.get("slice").getAsJsonObject();
    assertName("b", valueFuncValueFuncSlice);

    var valueFuncValueArgs = valueFuncValue.get("args").getAsJsonArray();
    assertEquals(1, valueFuncValueArgs.size());
    assertName("c", valueFuncValueArgs.get(0).getAsJsonObject());

    assertEquals("d", valueFunc.get("attr").getAsString());

    var valueCallArgs = valueCall.get("args").getAsJsonArray();
    assertEquals(1, valueCallArgs.size());
    assertName("e", valueCallArgs.get(0).getAsJsonObject());

    var valueSubscriptSlice = valueSubscript.get("slice").getAsJsonObject();
    assertName("f", valueSubscriptSlice);
  }

  @Test
  void subscriptFieldAssignment() throws Exception {
    var parserOutput =
        PyjinnParser.parseTrees(
            """
            x[y].z = w
            """);
    var ast = parserOutput.jsonAst();

    var assign = getSingletonStatement(ast).getAsJsonObject();
    assertEquals("Assign", assign.get("type").getAsString());

    var targets = assign.get("targets").getAsJsonArray();
    assertEquals(1, targets.size());

    var attribute = targets.get(0).getAsJsonObject();
    assertEquals("Attribute", attribute.get("type").getAsString());

    var subscript = attribute.get("value").getAsJsonObject();
    assertEquals("Subscript", subscript.get("type").getAsString());

    var subscriptValue = subscript.get("value").getAsJsonObject();
    assertName("x", subscriptValue);

    var subscriptSlice = subscript.get("slice").getAsJsonObject();
    assertName("y", subscriptSlice);

    assertEquals("z", attribute.get("attr").getAsString());

    var assignValue = assign.get("value").getAsJsonObject();
    assertName("w", assignValue);
  }

  @Test
  void fieldSubscriptAssignment() throws Exception {
    var parserOutput =
        PyjinnParser.parseTrees(
            """
            x.y[z] = w
            """);
    var ast = parserOutput.jsonAst();

    var assign = getSingletonStatement(ast).getAsJsonObject();
    assertEquals("Assign", assign.get("type").getAsString());

    var targets = assign.get("targets").getAsJsonArray();
    assertEquals(1, targets.size());

    var subscript = targets.get(0).getAsJsonObject();
    assertEquals("Subscript", subscript.get("type").getAsString());

    var subscriptValue = subscript.get("value").getAsJsonObject();
    assertEquals("Attribute", subscriptValue.get("type").getAsString());

    var attributeValue = subscriptValue.get("value").getAsJsonObject();
    assertName("x", attributeValue);
    assertEquals("y", subscriptValue.get("attr").getAsString());

    var subscriptSlice = subscript.get("slice").getAsJsonObject();
    assertName("z", subscriptSlice);

    var assignValue = assign.get("value").getAsJsonObject();
    assertName("w", assignValue);
  }

  @Test
  void fieldAssignment() throws Exception {
    var parserOutput =
        PyjinnParser.parseTrees(
            """
            x.y = z
            """);
    var ast = parserOutput.jsonAst();

    var assign = getSingletonStatement(ast).getAsJsonObject();
    assertEquals("Assign", assign.get("type").getAsString());

    var targets = assign.get("targets").getAsJsonArray();
    assertEquals(1, targets.size());

    var attribute = targets.get(0).getAsJsonObject();
    assertEquals("Attribute", attribute.get("type").getAsString());

    var value = attribute.get("value").getAsJsonObject();
    assertName("x", value);

    assertEquals("y", attribute.get("attr").getAsString());

    var assignValue = assign.get("value").getAsJsonObject();
    assertName("z", assignValue);
  }

  @Test
  void frozenDataclass() throws Exception {
    var parserOutput =
        PyjinnParser.parseTrees(
            """
            @dataclass(frozen=True)
            class Foo:
              x: int
              y: str
            """);
    var ast = parserOutput.jsonAst();

    var classDef = getSingletonStatement(ast).getAsJsonObject();
    assertEquals("ClassDef", classDef.get("type").getAsString());
    assertEquals("Foo", classDef.get("name").getAsString());

    var body = classDef.get("body").getAsJsonArray();
    assertEquals(2, body.size());

    var annAssign1 = body.get(0).getAsJsonObject();
    assertEquals("AnnAssign", annAssign1.get("type").getAsString());
    var target1 = annAssign1.get("target").getAsJsonObject();
    assertName("x", target1);
    var annotation1 = annAssign1.get("annotation").getAsJsonObject();
    assertName("int", annotation1);
    // TODO(maxuser): Support value and simple attrs.
    // assertTrue(annAssign1.get("value") instanceof JsonNull);
    // assertEquals(1, annAssign1.get("simple").getAsInt());

    var annAssign2 = body.get(1).getAsJsonObject();
    assertEquals("AnnAssign", annAssign2.get("type").getAsString());
    var target2 = annAssign2.get("target").getAsJsonObject();
    assertName("y", target2);
    var annotation2 = annAssign2.get("annotation").getAsJsonObject();
    assertName("str", annotation2);
    // TODO(maxuser): Support value and simple attrs.
    // assertTrue(annAssign2.get("value") instanceof JsonNull);
    // assertEquals(1, annAssign2.get("simple").getAsInt());

    var decoratorList = classDef.get("decorator_list").getAsJsonArray();
    assertEquals(1, decoratorList.size());

    var decorator = decoratorList.get(0).getAsJsonObject();
    assertEquals("Call", decorator.get("type").getAsString());

    var func = decorator.get("func").getAsJsonObject();
    assertName("dataclass", func);

    var keywords = decorator.get("keywords").getAsJsonArray();
    assertEquals(1, keywords.size());

    var keyword = keywords.get(0).getAsJsonObject();
    assertEquals("keyword", keyword.get("type").getAsString());
    assertEquals("frozen", keyword.get("arg").getAsString());

    var keywordValue = keyword.get("value").getAsJsonObject();
    assertEquals("Constant", keywordValue.get("type").getAsString());
    assertEquals(true, keywordValue.get("value").getAsBoolean());
    assertEquals("bool", keywordValue.get("typename").getAsString());
  }

  @Test
  void dataclass() throws Exception {
    var parserOutput =
        PyjinnParser.parseTrees(
            """
            @dataclass
            class Foo:
              x: int
              y: str
            """);
    var ast = parserOutput.jsonAst();

    var classDef = getSingletonStatement(ast).getAsJsonObject();
    assertEquals("ClassDef", classDef.get("type").getAsString());
    assertEquals("Foo", classDef.get("name").getAsString());

    var body = classDef.get("body").getAsJsonArray();
    assertEquals(2, body.size());

    var annAssign1 = body.get(0).getAsJsonObject();
    assertEquals("AnnAssign", annAssign1.get("type").getAsString());
    var target1 = annAssign1.get("target").getAsJsonObject();
    assertName("x", target1);
    var annotation1 = annAssign1.get("annotation").getAsJsonObject();
    assertName("int", annotation1);
    // TODO(maxuser): Support value and simple attrs.
    // assertTrue(annAssign1.get("value") instanceof JsonNull);
    // assertEquals(1, annAssign1.get("simple").getAsInt());

    var annAssign2 = body.get(1).getAsJsonObject();
    assertEquals("AnnAssign", annAssign2.get("type").getAsString());
    var target2 = annAssign2.get("target").getAsJsonObject();
    assertName("y", target2);
    var annotation2 = annAssign2.get("annotation").getAsJsonObject();
    assertName("str", annotation2);
    // TODO(maxuser): Support value and simple attrs.
    // assertTrue(annAssign2.get("value") instanceof JsonNull);
    // assertEquals(1, annAssign2.get("simple").getAsInt());

    var decoratorList = classDef.get("decorator_list").getAsJsonArray();
    assertEquals(1, decoratorList.size());
    var decorator = decoratorList.get(0).getAsJsonObject();
    assertName("dataclass", decorator);
  }

  @Test
  void classDef() throws Exception {
    var parserOutput =
        PyjinnParser.parseTrees(
            """
            class Foo:
              def __init__(self, x):
                self.x = x

              def bar(self):
                return self.x
            """);
    var ast = parserOutput.jsonAst();

    // TODO(maxuser): Add assertion for presence of empty decorator_list on class and funcs.
    var classDef = getSingletonStatement(ast).getAsJsonObject();
    assertEquals("ClassDef", classDef.get("type").getAsString());
    assertEquals("Foo", classDef.get("name").getAsString());

    var body = classDef.get("body").getAsJsonArray();
    assertEquals(2, body.size());

    var initMethod = body.get(0).getAsJsonObject();
    assertEquals("FunctionDef", initMethod.get("type").getAsString());
    assertEquals("__init__", initMethod.get("name").getAsString());

    var initArgs = initMethod.get("args").getAsJsonObject();
    assertEquals("arguments", initArgs.get("type").getAsString());

    var initArgsList = initArgs.get("args").getAsJsonArray();
    assertEquals(2, initArgsList.size());
    assertEquals("arg", initArgsList.get(0).getAsJsonObject().get("type").getAsString());
    assertEquals("self", initArgsList.get(0).getAsJsonObject().get("arg").getAsString());
    assertEquals("arg", initArgsList.get(1).getAsJsonObject().get("type").getAsString());
    assertEquals("x", initArgsList.get(1).getAsJsonObject().get("arg").getAsString());

    var initBody = initMethod.get("body").getAsJsonArray();
    assertEquals(1, initBody.size());

    var initAssign = initBody.get(0).getAsJsonObject();
    assertEquals("Assign", initAssign.get("type").getAsString());

    var initTargets = initAssign.get("targets").getAsJsonArray();
    assertEquals(1, initTargets.size());

    var initAttribute = initTargets.get(0).getAsJsonObject();
    assertEquals("Attribute", initAttribute.get("type").getAsString());

    var initAttributeValue = initAttribute.get("value").getAsJsonObject();
    assertName("self", initAttributeValue);
    assertEquals("x", initAttribute.get("attr").getAsString());

    var initAssignValue = initAssign.get("value").getAsJsonObject();
    assertName("x", initAssignValue);

    var barMethod = body.get(1).getAsJsonObject();
    assertEquals("FunctionDef", barMethod.get("type").getAsString());
    assertEquals("bar", barMethod.get("name").getAsString());

    var barArgs = barMethod.get("args").getAsJsonObject();
    assertEquals("arguments", barArgs.get("type").getAsString());

    var barArgsList = barArgs.get("args").getAsJsonArray();
    assertEquals(1, barArgsList.size());
    assertEquals("arg", barArgsList.get(0).getAsJsonObject().get("type").getAsString());
    assertEquals("self", barArgsList.get(0).getAsJsonObject().get("arg").getAsString());

    var barBody = barMethod.get("body").getAsJsonArray();
    assertEquals(1, barBody.size());

    var returnStmt = barBody.get(0).getAsJsonObject();
    assertEquals("Return", returnStmt.get("type").getAsString());

    var returnValue = returnStmt.get("value").getAsJsonObject();
    assertEquals("Attribute", returnValue.get("type").getAsString());

    var returnAttributeValue = returnValue.get("value").getAsJsonObject();
    assertName("self", returnAttributeValue);
    assertEquals("x", returnValue.get("attr").getAsString());
  }

  @Test
  void dictLiteral() throws Exception {
    var parserOutput =
        PyjinnParser.parseTrees(
            """
            x = {y: z, a: b}
            """);
    var ast = parserOutput.jsonAst();

    var assign = getSingletonStatement(ast).getAsJsonObject();
    assertEquals("Assign", assign.get("type").getAsString());

    var targets = assign.get("targets").getAsJsonArray();
    assertEquals(1, targets.size());

    var target = targets.get(0).getAsJsonObject();
    assertName("x", target);

    var value = assign.get("value").getAsJsonObject();
    assertEquals("Dict", value.get("type").getAsString());

    var keys = value.get("keys").getAsJsonArray();
    assertEquals(2, keys.size());
    assertName("y", keys.get(0).getAsJsonObject());
    assertName("a", keys.get(1).getAsJsonObject());

    var values = value.get("values").getAsJsonArray();
    assertEquals(2, values.size());
    assertName("z", values.get(0).getAsJsonObject());
    assertName("b", values.get(1).getAsJsonObject());
  }

  @Test
  void lambdaExpression() throws Exception {
    var parserOutput =
        PyjinnParser.parseTrees(
            """
            lambda x, y: x + y
            """);
    var ast = parserOutput.jsonAst();

    var expr = getSingletonStatement(ast).getAsJsonObject();
    assertEquals("Expr", expr.get("type").getAsString());

    var value = expr.get("value").getAsJsonObject();
    assertEquals("Lambda", value.get("type").getAsString());

    var args = value.get("args").getAsJsonObject();
    assertEquals("arguments", args.get("type").getAsString());

    var argsList = args.get("args").getAsJsonArray();
    assertEquals(2, argsList.size());

    assertEquals("arg", argsList.get(0).getAsJsonObject().get("type").getAsString());
    assertEquals("x", argsList.get(0).getAsJsonObject().get("arg").getAsString());

    assertEquals("arg", argsList.get(1).getAsJsonObject().get("type").getAsString());
    assertEquals("y", argsList.get(1).getAsJsonObject().get("arg").getAsString());

    var body = value.get("body").getAsJsonObject();
    assertEquals("BinOp", body.get("type").getAsString());

    var left = body.get("left").getAsJsonObject();
    assertName("x", left);

    var op = body.get("op").getAsJsonObject();
    assertEquals("Add", op.get("type").getAsString());

    var right = body.get("right").getAsJsonObject();
    assertName("y", right);
  }

  @Test
  void destructuredAssignment() throws Exception {
    var parserOutput =
        PyjinnParser.parseTrees(
            """
            x, y = foo()
            """);
    var ast = parserOutput.jsonAst();

    var assign = getSingletonStatement(ast).getAsJsonObject();
    assertEquals("Assign", assign.get("type").getAsString());

    var targets = assign.get("targets").getAsJsonArray();
    assertEquals(1, targets.size());

    var tuple = targets.get(0).getAsJsonObject();
    assertEquals("Tuple", tuple.get("type").getAsString());

    var elts = tuple.get("elts").getAsJsonArray();
    assertEquals(2, elts.size());

    assertName("x", elts.get(0).getAsJsonObject());
    assertName("y", elts.get(1).getAsJsonObject());

    var value = assign.get("value").getAsJsonObject();
    assertEquals("Call", value.get("type").getAsString());

    var func = value.get("func").getAsJsonObject();
    assertName("foo", func);

    var args = value.get("args").getAsJsonArray();
    assertEquals(0, args.size());
  }

  @Test
  void simpleAssignment() throws Exception {
    var parserOutput =
        PyjinnParser.parseTrees(
            """
            x = y
            """);
    var ast = parserOutput.jsonAst();

    var assign = getSingletonStatement(ast).getAsJsonObject();
    assertEquals("Assign", assign.get("type").getAsString());

    var targets = assign.get("targets").getAsJsonArray();
    assertEquals(1, targets.size());

    var target = targets.get(0).getAsJsonObject();
    assertName("x", target);

    var value = assign.get("value").getAsJsonObject();
    assertName("y", value);
  }

  @Test
  void tupleLiteral() throws Exception {
    var parserOutput =
        PyjinnParser.parseTrees(
            """
            (x, y, z)
            """);
    var ast = parserOutput.jsonAst();

    var expr = getSingletonStatement(ast).getAsJsonObject();
    assertEquals("Expr", expr.get("type").getAsString());

    var value = expr.get("value").getAsJsonObject();
    assertEquals("Tuple", value.get("type").getAsString());

    var elts = value.get("elts").getAsJsonArray();
    assertEquals(3, elts.size());

    assertName("x", elts.get(0).getAsJsonObject());
    assertName("y", elts.get(1).getAsJsonObject());
    assertName("z", elts.get(2).getAsJsonObject());
  }

  @Test
  void listLiteral() throws Exception {
    var parserOutput =
        PyjinnParser.parseTrees(
            """
            [x, y, z]
            """);
    var ast = parserOutput.jsonAst();

    var expr = getSingletonStatement(ast).getAsJsonObject();
    assertEquals("Expr", expr.get("type").getAsString());

    var value = expr.get("value").getAsJsonObject();
    assertEquals("List", value.get("type").getAsString());

    var elts = value.get("elts").getAsJsonArray();
    assertEquals(3, elts.size());

    assertName("x", elts.get(0).getAsJsonObject());
    assertName("y", elts.get(1).getAsJsonObject());
    assertName("z", elts.get(2).getAsJsonObject());
  }

  @Test
  void orderOfOperations() throws Exception {
    var parserOutput =
        PyjinnParser.parseTrees(
            """
            x + y * z - w
            """);
    var ast = parserOutput.jsonAst();

    var expr = getSingletonStatement(ast).getAsJsonObject();
    assertEquals("Expr", expr.get("type").getAsString());

    var value = expr.get("value").getAsJsonObject();
    assertEquals("BinOp", value.get("type").getAsString());

    var left = value.get("left").getAsJsonObject();
    assertEquals("BinOp", left.get("type").getAsString());

    var leftLeft = left.get("left").getAsJsonObject();
    assertName("x", leftLeft);

    var leftOp = left.get("op").getAsJsonObject();
    assertEquals("Add", leftOp.get("type").getAsString());

    var leftRight = left.get("right").getAsJsonObject();
    assertEquals("BinOp", leftRight.get("type").getAsString());

    var leftRightLeft = leftRight.get("left").getAsJsonObject();
    assertName("y", leftRightLeft);

    var leftRightOp = leftRight.get("op").getAsJsonObject();
    assertEquals("Mult", leftRightOp.get("type").getAsString());

    var leftRightRight = leftRight.get("right").getAsJsonObject();
    assertName("z", leftRightRight);

    var op = value.get("op").getAsJsonObject();
    assertEquals("Sub", op.get("type").getAsString());

    var right = value.get("right").getAsJsonObject();
    assertName("w", right);
  }

  @Test
  void multiplication() throws Exception {
    var parserOutput =
        PyjinnParser.parseTrees(
            """
            x * y
            """);
    var ast = parserOutput.jsonAst();

    var expr = getSingletonStatement(ast).getAsJsonObject();
    assertEquals("Expr", expr.get("type").getAsString());

    var term = expr.get("value").getAsJsonObject();
    assertEquals("BinOp", term.get("type").getAsString());
    assertName("x", term.get("left"));
    assertEquals("Mult", term.get("op").getAsJsonObject().get("type").getAsString());
    assertName("y", term.get("right"));
  }

  @Test
  void subtraction() throws Exception {
    var parserOutput =
        PyjinnParser.parseTrees(
            """
            x - y
            """);
    var ast = parserOutput.jsonAst();

    var expr = getSingletonStatement(ast).getAsJsonObject();
    assertEquals("Expr", expr.get("type").getAsString());

    var sum = expr.get("value").getAsJsonObject();
    assertEquals("BinOp", sum.get("type").getAsString());
    assertName("x", sum.get("left"));
    assertEquals("Sub", sum.get("op").getAsJsonObject().get("type").getAsString());
    assertName("y", sum.get("right"));
  }

  @Test
  void fieldAccess() throws Exception {
    var parserOutput =
        PyjinnParser.parseTrees(
            """
            x.y.z
            """);
    var ast = parserOutput.jsonAst();

    var expr = getSingletonStatement(ast).getAsJsonObject();
    var outerAttr = expr.get("value").getAsJsonObject();
    assertEquals("Attribute", outerAttr.get("type").getAsString());
    assertEquals("z", outerAttr.get("attr").getAsString());

    var innerAttr = outerAttr.get("value").getAsJsonObject();
    assertEquals("Attribute", innerAttr.get("type").getAsString());
    assertEquals("y", innerAttr.get("attr").getAsString());
  }

  @Test
  void functionCall() throws Exception {
    var parserOutput =
        PyjinnParser.parseTrees(
            """
            f(x, y)
            """);
    var ast = parserOutput.jsonAst();

    var expr = getSingletonStatement(ast).getAsJsonObject();
    assertEquals("Expr", expr.get("type").getAsString());

    var call = expr.get("value").getAsJsonObject();
    assertEquals("Call", call.get("type").getAsString());

    var func = call.get("func").getAsJsonObject();
    assertName("f", func);

    var args = call.get("args").getAsJsonArray();
    assertEquals(2, args.size());
    assertName("x", args.get(0).getAsJsonObject());
    assertName("y", args.get(1).getAsJsonObject());
  }

  @Test
  void functionCallWithStarredArg() throws Exception {
    var parserOutput =
        PyjinnParser.parseTrees(
            """
            foo(first_arg, *starred_arg, last_arg)
            """);
    var ast = parserOutput.jsonAst();

    var expr = getSingletonStatement(ast).getAsJsonObject();
    assertEquals("Expr", expr.get("type").getAsString());

    var call = expr.get("value").getAsJsonObject();
    assertEquals("Call", call.get("type").getAsString());

    var func = call.get("func").getAsJsonObject();
    assertName("foo", func);

    var args = call.get("args").getAsJsonArray();
    assertEquals(3, args.size());

    // first_arg
    assertName("first_arg", args.get(0).getAsJsonObject());

    // *starred_arg
    var starred = args.get(1).getAsJsonObject();
    assertEquals("Starred", starred.get("type").getAsString());
    var starredValue = starred.get("value").getAsJsonObject();
    assertName("starred_arg", starredValue);

    // last_arg
    assertName("last_arg", args.get(2).getAsJsonObject());

    // keywords should be empty
    var keywords = call.get("keywords").getAsJsonArray();
    assertEquals(0, keywords.size());
  }

  @Test
  void subscriptEmptyRange() throws Exception {
    var parserOutput =
        PyjinnParser.parseTrees(
            """
            x[:]
            """);
    var ast = parserOutput.jsonAst();

    var expr = getSingletonStatement(ast).getAsJsonObject();
    assertEquals("Expr", expr.get("type").getAsString());

    var subscript = expr.get("value").getAsJsonObject();
    assertEquals("Subscript", subscript.get("type").getAsString());

    var value = subscript.get("value").getAsJsonObject();
    assertName("x", value);

    var slice = subscript.get("slice").getAsJsonObject();
    assertEquals("Slice", slice.get("type").getAsString());

    assertEquals(JsonNull.INSTANCE, slice.get("lower"));
    assertEquals(JsonNull.INSTANCE, slice.get("upper"));
    assertEquals(JsonNull.INSTANCE, slice.get("step"));
  }

  @Test
  void subscriptValue() throws Exception {
    var parserOutput =
        PyjinnParser.parseTrees(
            """
            x[77]
            """);
    var ast = parserOutput.jsonAst();

    var expr = getSingletonStatement(ast).getAsJsonObject();
    assertEquals("Expr", expr.get("type").getAsString());

    var subscript = expr.get("value").getAsJsonObject();
    assertEquals("Subscript", subscript.get("type").getAsString());

    var value = subscript.get("value").getAsJsonObject();
    assertName("x", value);

    var slice = subscript.get("slice").getAsJsonObject();
    assertEquals("Constant", slice.get("type").getAsString());

    assertEquals(77, slice.get("value").getAsInt());
  }

  @Test
  void subscriptColon() throws Exception {
    var parserOutput =
        PyjinnParser.parseTrees(
            """
            x[:]
            """);
    var ast = parserOutput.jsonAst();

    var expr = getSingletonStatement(ast).getAsJsonObject();
    assertEquals("Expr", expr.get("type").getAsString());

    var subscript = expr.get("value").getAsJsonObject();
    assertEquals("Subscript", subscript.get("type").getAsString());

    var value = subscript.get("value").getAsJsonObject();
    assertName("x", value);

    var slice = subscript.get("slice").getAsJsonObject();
    assertEquals("Slice", slice.get("type").getAsString());

    assertEquals(JsonNull.INSTANCE, slice.get("lower"));
    assertEquals(JsonNull.INSTANCE, slice.get("upper"));
    assertEquals(JsonNull.INSTANCE, slice.get("step"));
  }

  @Test
  void subscriptColonColon() throws Exception {
    var parserOutput =
        PyjinnParser.parseTrees(
            """
            x[::]
            """);
    var ast = parserOutput.jsonAst();

    var expr = getSingletonStatement(ast).getAsJsonObject();
    assertEquals("Expr", expr.get("type").getAsString());

    var subscript = expr.get("value").getAsJsonObject();
    assertEquals("Subscript", subscript.get("type").getAsString());

    var value = subscript.get("value").getAsJsonObject();
    assertName("x", value);

    var slice = subscript.get("slice").getAsJsonObject();
    assertEquals("Slice", slice.get("type").getAsString());

    assertEquals(JsonNull.INSTANCE, slice.get("lower"));
    assertEquals(JsonNull.INSTANCE, slice.get("upper"));
    assertEquals(JsonNull.INSTANCE, slice.get("step"));
  }

  @Test
  void subscriptColonValue() throws Exception {
    var parserOutput =
        PyjinnParser.parseTrees(
            """
            x[:99]
            """);
    var ast = parserOutput.jsonAst();

    var expr = getSingletonStatement(ast).getAsJsonObject();
    assertEquals("Expr", expr.get("type").getAsString());

    var subscript = expr.get("value").getAsJsonObject();
    assertEquals("Subscript", subscript.get("type").getAsString());

    var value = subscript.get("value").getAsJsonObject();
    assertName("x", value);

    var slice = subscript.get("slice").getAsJsonObject();
    assertEquals("Slice", slice.get("type").getAsString());

    assertEquals(JsonNull.INSTANCE, slice.get("lower"));

    var upper = slice.get("upper").getAsJsonObject();
    assertEquals("Constant", upper.get("type").getAsString());
    assertEquals(99, upper.get("value").getAsInt());

    assertEquals(JsonNull.INSTANCE, slice.get("step"));
  }

  @Test
  void subscriptColonValueColonValue() throws Exception {
    var parserOutput =
        PyjinnParser.parseTrees(
            """
            x[:99:77]
            """);
    var ast = parserOutput.jsonAst();

    var expr = getSingletonStatement(ast).getAsJsonObject();
    assertEquals("Expr", expr.get("type").getAsString());

    var subscript = expr.get("value").getAsJsonObject();
    assertEquals("Subscript", subscript.get("type").getAsString());

    var value = subscript.get("value").getAsJsonObject();
    assertName("x", value);

    var slice = subscript.get("slice").getAsJsonObject();
    assertEquals("Slice", slice.get("type").getAsString());

    assertEquals(JsonNull.INSTANCE, slice.get("lower"));

    var upper = slice.get("upper").getAsJsonObject();
    assertEquals("Constant", upper.get("type").getAsString());
    assertEquals(99, upper.get("value").getAsInt());

    var step = slice.get("step").getAsJsonObject();
    assertEquals("Constant", step.get("type").getAsString());
    assertEquals(77, step.get("value").getAsInt());
  }

  @Test
  void subscriptValueColonValue() throws Exception {
    var parserOutput =
        PyjinnParser.parseTrees(
            """
            x[77:99]
            """);
    var ast = parserOutput.jsonAst();

    var expr = getSingletonStatement(ast).getAsJsonObject();
    assertEquals("Expr", expr.get("type").getAsString());

    var subscript = expr.get("value").getAsJsonObject();
    assertEquals("Subscript", subscript.get("type").getAsString());

    var value = subscript.get("value").getAsJsonObject();
    assertName("x", value);

    var slice = subscript.get("slice").getAsJsonObject();
    assertEquals("Slice", slice.get("type").getAsString());

    var lower = slice.get("lower").getAsJsonObject();
    assertEquals("Constant", lower.get("type").getAsString());
    assertEquals(77, lower.get("value").getAsInt());

    var upper = slice.get("upper").getAsJsonObject();
    assertEquals("Constant", upper.get("type").getAsString());
    assertEquals(99, upper.get("value").getAsInt());

    assertEquals(JsonNull.INSTANCE, slice.get("step"));
  }

  @Test
  void subscriptColonColonValue() throws Exception {
    var parserOutput =
        PyjinnParser.parseTrees(
            """
            x[::3]
            """);
    var ast = parserOutput.jsonAst();

    var expr = getSingletonStatement(ast).getAsJsonObject();
    assertEquals("Expr", expr.get("type").getAsString());

    var subscript = expr.get("value").getAsJsonObject();
    assertEquals("Subscript", subscript.get("type").getAsString());

    var value = subscript.get("value").getAsJsonObject();
    assertName("x", value);

    var slice = subscript.get("slice").getAsJsonObject();
    assertEquals("Slice", slice.get("type").getAsString());

    assertEquals(JsonNull.INSTANCE, slice.get("lower"));
    assertEquals(JsonNull.INSTANCE, slice.get("upper"));

    var step = slice.get("step").getAsJsonObject();
    assertEquals("Constant", step.get("type").getAsString());
    assertEquals(3, step.get("value").getAsInt());
  }

  @Test
  void simpleAdditionFunctionDef() throws Exception {
    var parserOutput =
        PyjinnParser.parseTrees(
            """
            def foo(x, y):
              return x + y
            """);
    var ast = parserOutput.jsonAst();

    // TODO(maxuser): Add assertion for presence of empty decorator_list
    var functionDef = getSingletonStatement(ast).getAsJsonObject();
    assertEquals("FunctionDef", functionDef.get("type").getAsString());
    assertEquals("foo", functionDef.get("name").getAsString());

    var argsObject = functionDef.get("args").getAsJsonObject();
    assertEquals("arguments", argsObject.get("type").getAsString());

    var argsArray = argsObject.get("args").getAsJsonArray();
    assertEquals(2, argsArray.size());

    var xArg = argsArray.get(0).getAsJsonObject();
    assertEquals("arg", xArg.get("type").getAsString());

    var yArg = argsArray.get(1).getAsJsonObject();
    assertEquals("arg", yArg.get("type").getAsString());

    var body = functionDef.get("body").getAsJsonArray();
    assertEquals(1, body.size());

    var returnStatement = body.get(0).getAsJsonObject();
    assertEquals("Return", returnStatement.get("type").getAsString());

    var sum = returnStatement.get("value").getAsJsonObject();
    assertEquals("BinOp", sum.get("type").getAsString());
    assertName("x", sum.get("left"));
    assertEquals("Add", sum.get("op").getAsJsonObject().get("type").getAsString());
    assertName("y", sum.get("right"));
  }

  @Test
  void multipleStatementsFunctionDef() throws Exception {
    var parserOutput =
        PyjinnParser.parseTrees(
            """
            def foo(x, y):
              x
              y
            """);
    var ast = parserOutput.jsonAst();

    // TODO(maxuser): Add assertion for presence of empty decorator_list
    var functionDef = getSingletonStatement(ast).getAsJsonObject();
    assertEquals("FunctionDef", functionDef.get("type").getAsString());
    assertEquals("foo", functionDef.get("name").getAsString());

    var argsObject = functionDef.get("args").getAsJsonObject();
    assertEquals("arguments", argsObject.get("type").getAsString());

    var argsArray = argsObject.get("args").getAsJsonArray();
    assertEquals(2, argsArray.size());

    var xArg = argsArray.get(0).getAsJsonObject();
    assertEquals("arg", xArg.get("type").getAsString());

    var yArg = argsArray.get(1).getAsJsonObject();
    assertEquals("arg", yArg.get("type").getAsString());

    var body = functionDef.get("body").getAsJsonArray();
    assertEquals(2, body.size());

    var x = body.get(0).getAsJsonObject();
    assertEquals("Expr", x.get("type").getAsString());
    assertName("x", x.get("value"));

    var y = body.get(1).getAsJsonObject();
    assertEquals("Expr", y.get("type").getAsString());
    assertName("y", y.get("value"));
  }

  private JsonElement getSingletonStatement(JsonElement astRoot) {
    var module = astRoot.getAsJsonObject();
    assertEquals("Module", module.get("type").getAsString());

    var body = module.get("body").getAsJsonArray();
    assertTrue(body.isJsonArray());
    assertEquals(1, body.size());
    return body.get(0);
  }

  private void assertName(String expectedName, JsonElement node) {
    assertTrue(node.isJsonObject());
    var object = node.getAsJsonObject();
    assertEquals("Name", object.get("type").getAsString());
    assertEquals(expectedName, object.get("id").getAsString());
  }

  private void printParserOutput(PyjinnParser.ParserOutput parserOutput) {
    System.out.println("source:\n" + parserOutput.source());

    var parser = parserOutput.parser();
    var parseTree = parserOutput.parseTree();
    System.out.println("parse tree: " + parseTree.toStringTree(parser));
    var ast = parserOutput.jsonAst();

    System.out.println();

    Gson gson = new GsonBuilder().serializeNulls().setPrettyPrinting().create();
    System.out.println("json ast: " + gson.toJson(ast));
  }
}
