// SPDX-FileCopyrightText: © 2025 Greg Christiana <maxuser@pyjinn.org>
// SPDX-License-Identifier: MIT

package org.pyjinn.parser;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.Optional;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.pyjinn.grammar.PythonLexer;
import org.pyjinn.grammar.PythonParser;
import org.pyjinn.grammar.PythonParserBaseVisitor;

public class PyjinnParser {
  public record ParserOutput(
      String source, PythonParser parser, ParseTree parseTree, JsonElement jsonAst) {}

  public static JsonElement parse(String filename, String pyjinnCode) throws Exception {
    return parseTrees(filename, pyjinnCode).jsonAst();
  }

  public static ParserOutput parseTrees(String filename, String pyjinnCode) throws Exception {
    CharStream input = CharStreams.fromString(pyjinnCode);
    PythonLexer lexer = new PythonLexer(input);
    lexer.removeErrorListeners();
    lexer.addErrorListener(new PyjinnErrorListener(filename, pyjinnCode));

    CommonTokenStream tokens = new CommonTokenStream(lexer);
    PythonParser parser = new PythonParser(tokens);
    parser.removeErrorListeners();
    parser.addErrorListener(new PyjinnErrorListener(filename, pyjinnCode));

    ParseTree parseTree = parser.file_input();
    var visitor = new PythonJsonVisitor();
    var ast = visitor.visit(parseTree);
    return new ParserOutput(pyjinnCode, parser, parseTree, ast);
  }
}

class PythonJsonVisitor extends PythonParserBaseVisitor<JsonElement> {

  @Override
  public JsonElement visitFile_input(PythonParser.File_inputContext ctx) {
    var node = createNode(ctx, "Module");
    node.add("body", visitStatements(ctx.statements()));
    return node;
  }

  @Override
  public JsonElement visitStatements(PythonParser.StatementsContext ctx) {
    JsonArray array = new JsonArray();
    for (var stmt : ctx.statement()) {
      JsonElement node = visitStatement(stmt);
      if (node != null && !node.isJsonNull()) {
        if (node.isJsonArray()) {
          array.addAll(node.getAsJsonArray());
        } else if (node.isJsonObject()) {
          array.add(node);
        }
      }
    }
    return array;
  }

  @Override
  public JsonElement visitStatement(PythonParser.StatementContext ctx) {
    if (ctx.simple_stmts() != null) {
      return visitSimple_stmts(ctx.simple_stmts());
    }
    if (ctx.compound_stmt() != null) {
      return visitCompound_stmt(ctx.compound_stmt());
    }
    return defaultResult(ctx);
  }

  @Override
  public JsonElement visitCompound_stmt(PythonParser.Compound_stmtContext ctx) {
    if (ctx.function_def() != null) {
      return visitFunction_def(ctx.function_def());
    }
    if (ctx.class_def() != null) {
      return visitClass_def(ctx.class_def());
    }

    var TODO = new JsonObject();
    if (ctx.if_stmt() != null) {
      return visitIf_stmt(ctx.if_stmt());
    }
    if (ctx.for_stmt() != null) {
      return visitFor_stmt(ctx.for_stmt());
    }
    if (ctx.while_stmt() != null) {
      return visitWhile_stmt(ctx.while_stmt());
    }

    // TODO(maxuser): Support these statement types:
    if (ctx.try_stmt() != null) {
      return visitTry_stmt(ctx.try_stmt());
    }
    if (ctx.with_stmt() != null) {
      TODO.addProperty("TODO", "WithStmt");
    }
    return TODO;
  }

  @Override
  public JsonElement visitTry_stmt(PythonParser.Try_stmtContext ctx) {
    var node = createNode(ctx, "Try");
    node.add("body", visitBlock(ctx.block()));
    var handlers = new JsonArray();
    for (var exceptBlock : ctx.except_block()) {
      var handler = createNode(exceptBlock);
      var expression = exceptBlock.expression();
      var name = exceptBlock.NAME();
      handler.add("type", expression == null ? JsonNull.INSTANCE : visitExpression(expression));
      handler.addProperty("name", name == null ? null : name.getText());
      handler.add("body", visitExcept_block(exceptBlock));
      handlers.add(handler);
    }
    node.add("handlers", handlers);
    if (ctx.finally_block() != null) {
      node.add("finalbody", visitBlock(ctx.finally_block().block()));
    } else {
      node.add("finalbody", new JsonArray());
    }
    return node;
  }

  @Override
  public JsonElement visitIf_stmt(PythonParser.If_stmtContext ctx) {
    return visitIfOrElif(ctx.named_expression(), ctx.block(), ctx.elif_stmt(), ctx.else_block());
  }

  private JsonElement visitIfOrElif(
      PythonParser.Named_expressionContext test,
      PythonParser.BlockContext block,
      PythonParser.Elif_stmtContext elif_stmt,
      PythonParser.Else_blockContext else_block) {
    var node = createNode(test, "If");
    node.add("test", visitNamed_expression(test));
    node.add("body", visitBlock(block));
    if (elif_stmt != null) {
      if (else_block != null && elif_stmt.else_block() != null) {
        throw new IllegalStateException(
            "Unexpected else block to appear on outer if/elif:\n%s\nand inner elif:\n%s"
                .formatted(else_block.getText(), elif_stmt.else_block().getText()));
      }
      var orElse = new JsonArray();
      orElse.add(
          visitIfOrElif(
              elif_stmt.named_expression(),
              elif_stmt.block(),
              elif_stmt.elif_stmt(),
              else_block == null ? elif_stmt.else_block() : else_block));
      node.add("orelse", orElse);
    } else if (else_block != null) {
      node.add("orelse", visitBlock(else_block.block()));
    } else {
      node.add("orelse", new JsonArray());
    }
    return node;
  }

  @Override
  public JsonElement visitRaise_stmt(PythonParser.Raise_stmtContext ctx) {
    var node = createNode(ctx, "Raise");
    var exc = new JsonArray();
    for (var expr : ctx.expression()) {
      exc.add(visitExpression(expr));
    }
    node.add("exc", maybeSingleton(exc));
    return node;
  }

  @Override
  public JsonElement visitGlobal_stmt(PythonParser.Global_stmtContext ctx) {
    var node = createNode(ctx, "Global");
    var names = new JsonArray();
    for (var name : ctx.NAME()) {
      names.add(name.getText());
    }
    node.add("names", names);
    return node;
  }

  @Override
  public JsonElement visitNonlocal_stmt(PythonParser.Nonlocal_stmtContext ctx) {
    var node = createNode(ctx, "Nonlocal");
    var names = new JsonArray();
    for (var name : ctx.NAME()) {
      names.add(name.getText());
    }
    node.add("names", names);
    return node;
  }

  @Override
  public JsonElement visitImport_stmt(PythonParser.Import_stmtContext ctx) {
    if (ctx.import_name() != null) {
      var node = createNode(ctx, "Import");
      var names = new JsonArray();
      for (var name : ctx.import_name().dotted_as_names().dotted_as_name()) {
        var alias = createNode("alias");
        alias.addProperty("name", name.getChild(0).getText());
        if (name.NAME() == null) {
          alias.add("asname", JsonNull.INSTANCE);
        } else {
          alias.addProperty("asname", name.NAME().getText());
        }
        names.add(alias);
      }
      node.add("names", names);
      return node;
    } else if (ctx.import_from() != null) {
      var node = createNode(ctx, "ImportFrom");
      node.addProperty("module", ctx.import_from().dotted_name().getText());
      var names = new JsonArray();
      var import_from_as_names = ctx.import_from().import_from_targets().import_from_as_names();
      if (import_from_as_names == null) {
        var alias = createNode("alias");
        alias.addProperty("name", "*");
        alias.add("asname", JsonNull.INSTANCE);
        names.add(alias);
      } else {
        for (var name : import_from_as_names.import_from_as_name()) {
          var alias = createNode("alias");
          alias.addProperty("name", name.getChild(0).getText());
          if (name.getChildCount() < 3) {
            alias.add("asname", JsonNull.INSTANCE);
          } else {
            // The literal token "as" is child[1].
            alias.addProperty("asname", name.getChild(2).getText());
          }
          names.add(alias);
        }
      }
      node.add("names", names);
      return node;
    }
    return defaultResult(ctx);
  }

  @Override
  public JsonElement visitFor_stmt(PythonParser.For_stmtContext ctx) {
    var node = createNode(ctx, "For");
    node.add("target", singletonOrTuple(visitStar_targets(ctx.star_targets())));
    node.add("iter", singletonOrTuple(visitStar_expressions(ctx.star_expressions())));
    node.add("body", visitBlock(ctx.block()));

    // TODO(maxuser): Support for/else_block.
    if (ctx.else_block() != null) {
      throw new UnsupportedOperationException(
          "for/else not supported: " + ctx.else_block().getText());
    }
    node.add("orelse", new JsonArray());

    return node;
  }

  @Override
  public JsonElement visitWhile_stmt(PythonParser.While_stmtContext ctx) {
    var node = createNode(ctx, "While");
    node.add("test", visitNamed_expression(ctx.named_expression()));
    node.add("body", visitBlock(ctx.block()));

    // TODO(maxuser): Support while/else_block.
    if (ctx.else_block() != null) {
      throw new UnsupportedOperationException(
          "while/else not supported: " + ctx.else_block().getText());
    }
    node.add("orelse", new JsonArray());

    return node;
  }

  @Override
  public JsonElement visitFunction_def(PythonParser.Function_defContext ctx) {
    if (ctx.function_def_raw() != null) {
      var node = visitFunction_def_raw(ctx.function_def_raw());
      var decorators = visitDecorators(ctx.decorators());
      node.getAsJsonObject().add("decorator_list", decorators);
      return node;
    }
    return defaultResult(ctx);
  }

  @Override
  public JsonElement visitDecorators(PythonParser.DecoratorsContext ctx) {
    var array = new JsonArray();
    if (ctx != null) {
      for (var decorator : ctx.named_expression()) {
        array.add(visitNamed_expression(decorator));
      }
    }
    return array;
  }

  @Override
  public JsonElement visitFunction_def_raw(PythonParser.Function_def_rawContext ctx) {
    var node = createNode(ctx, "FunctionDef");
    node.addProperty("name", ctx.NAME().getText());

    var arguments = createNode("arguments");
    var args = new JsonArray();
    var defaults = new JsonArray();

    if (ctx.params() != null) {
      var parameters = ctx.params().parameters();
      if (parameters != null) {
        var paramList = parameters.param_no_default();
        if (paramList != null && !paramList.isEmpty()) {
          for (var param : paramList) {
            var argNode = createNode(param, "arg");
            argNode.addProperty("arg", param.param().NAME().getText());
            args.add(argNode);
          }
        }

        var star = parameters.star_etc();
        if (star == null) {
          arguments.add("vararg", JsonNull.INSTANCE);
          arguments.add("kwarg", JsonNull.INSTANCE);
        } else {
          if (star.getChild(0).getText().equals("*")) {
            var vararg = createNode(star, "arg");
            vararg.addProperty("arg", star.getChild(1).getText().replaceAll(",", ""));
            arguments.add("vararg", vararg);
          } else {
            arguments.add("vararg", JsonNull.INSTANCE);
          }

          if (star.kwds() != null) {
            var kwarg = createNode(star.kwds(), "arg");
            // Assume: star.kwds().getChild(0).getText().equals("**")
            kwarg.addProperty("arg", star.kwds().getChild(1).getText());
            arguments.add("kwarg", kwarg);
          } else {
            arguments.add("kwarg", JsonNull.INSTANCE);
          }
        }

        var defaultParamList = parameters.param_with_default();
        if (defaultParamList != null && !defaultParamList.isEmpty()) {
          for (var defaultParam : defaultParamList) {
            var argNode = createNode(defaultParam, "arg");
            argNode.addProperty("arg", defaultParam.param().NAME().getText());
            args.add(argNode);
            defaults.add(visitExpression(defaultParam.default_assignment().expression()));
          }
        }
      }
    }

    arguments.add("args", args);
    arguments.add("defaults", defaults);
    node.add("args", arguments);

    node.add("body", visitBlock(ctx.block()));

    return node;
  }

  @Override
  public JsonElement visitClass_def(PythonParser.Class_defContext ctx) {
    if (ctx.class_def_raw() != null) {
      var node = visitClass_def_raw(ctx.class_def_raw());
      var decorators = visitDecorators(ctx.decorators());
      node.getAsJsonObject().add("decorator_list", decorators);
      return node;
    }
    return defaultResult(ctx);
  }

  @Override
  public JsonElement visitClass_def_raw(PythonParser.Class_def_rawContext ctx) {
    var node = createNode(ctx, "ClassDef");
    node.addProperty("name", ctx.NAME().getText());
    // TODO(maxuser): Support "bases".
    node.add("body", visitBlock(ctx.block()));
    return node;
  }

  @Override
  public JsonElement visitBlock(PythonParser.BlockContext ctx) {
    if (ctx.statements() != null) {
      return visitStatements(ctx.statements());
    }
    JsonArray array = new JsonArray();
    for (var stmt : ctx.simple_stmts().simple_stmt()) {
      JsonElement node = visitSimple_stmt(stmt);
      if (node != null && !node.isJsonNull()) {
        if (node.isJsonArray()) {
          array.addAll(node.getAsJsonArray());
        } else if (node.isJsonObject()) {
          array.add(node);
        }
      }
    }
    return array;
  }

  @Override
  public JsonElement visitSimple_stmts(PythonParser.Simple_stmtsContext ctx) {
    var array = new JsonArray();
    if (ctx != null && ctx.simple_stmt() != null) {
      for (var stmt : ctx.simple_stmt()) {
        array.add(visitSimple_stmt(stmt));
      }
    }
    if (array.size() == 1) {
      return array.get(0);
    }
    return array;
  }

  @Override
  public JsonElement visitSimple_stmt(PythonParser.Simple_stmtContext ctx) {
    if (ctx.PASS() != null) {
      return createNode(ctx, "Pass");
    }
    if (ctx.BREAK() != null) {
      return createNode(ctx, "Break");
    }
    if (ctx.CONTINUE() != null) {
      return createNode(ctx, "Continue");
    }
    if (ctx.del_stmt() != null) {
      return visitDel_stmt(ctx.del_stmt());
    }
    if (ctx.assignment() != null) {
      return visitAssignment(ctx.assignment());
    }
    if (ctx.return_stmt() != null) {
      return visitReturn_stmt(ctx.return_stmt());
    }
    if (ctx.raise_stmt() != null) {
      return visitRaise_stmt(ctx.raise_stmt());
    }
    if (ctx.global_stmt() != null) {
      return visitGlobal_stmt(ctx.global_stmt());
    }
    if (ctx.nonlocal_stmt() != null) {
      return visitNonlocal_stmt(ctx.nonlocal_stmt());
    }
    if (ctx.import_stmt() != null) {
      return visitImport_stmt(ctx.import_stmt());
    }
    if (ctx.star_expressions() != null) {
      var node = createNode(ctx, "Expr");
      node.add("value", singletonOrTuple(visitStar_expressions(ctx.star_expressions())));
      return node;
    }
    return defaultResult(ctx);
  }

  @Override
  public JsonElement visitDel_stmt(PythonParser.Del_stmtContext ctx) {
    var node = createNode(ctx, "Delete");
    var targets = new JsonArray();
    for (var target : ctx.del_targets().del_target()) {
      var atom = target.del_t_atom();
      if (atom != null) {
        if (atom.NAME() != null) {
          var nameNode = createNode(atom, "Name");
          nameNode.addProperty("id", atom.NAME().getText());
          targets.add(nameNode);
          // TODO(maxuser): What about atom.del_target() and atom.del_targets()?
        }
      } else {
        targets.add(
            handle_tprimary_and_slices(target.t_primary(), target.slices(), target.NAME())
                .orElseGet(() -> defaultResult(ctx)));
      }
    }
    node.add("targets", targets);
    return node;
  }

  private static JsonElement maybeSingleton(JsonElement element) {
    if (element.isJsonArray() && element.getAsJsonArray().size() == 1) {
      return element.getAsJsonArray().get(0);
    }
    return element;
  }

  private static JsonElement singletonOrTuple(JsonElement element) {
    if (element.isJsonArray()) {
      var array = element.getAsJsonArray();
      if (array.size() == 1) {
        return element.getAsJsonArray().get(0);
      }
      var tuple = createNode("Tuple");
      tuple.add("elts", array);
      return tuple;
    }
    return element;
  }

  @Override
  public JsonElement visitReturn_stmt(PythonParser.Return_stmtContext ctx) {
    var returnNode = createNode(ctx, "Return");

    if (ctx.star_expressions() != null) { // Check if there's a return value
      returnNode.add("value", singletonOrTuple(visit(ctx.star_expressions())));
    } else {
      returnNode.add("value", JsonNull.INSTANCE);
    }

    return returnNode;
  }

  private JsonObject createOp(String op) {
    var node = createNode(op);
    return node;
  }

  @Override
  public JsonElement visitAssignment(PythonParser.AssignmentContext ctx) {
    if (ctx.augassign() != null) {
      var assign = createNode(ctx, "AugAssign");
      assign.add("target", visitSingle_target(ctx.single_target()));
      final JsonObject op;
      if (ctx.augassign().PLUSEQUAL() != null) {
        op = createOp("Add");
      } else if (ctx.augassign().MINEQUAL() != null) {
        op = createOp("Sub");
      } else if (ctx.augassign().STAREQUAL() != null) {
        op = createOp("Mult");
      } else if (ctx.augassign().SLASHEQUAL() != null) {
        op = createOp("Div");
      } else {
        // TODO(maxuser): Support the remaining augassign operators:
        // ATEQUAL()
        // PERCENTEQUAL()
        // AMPEREQUAL()
        // VBAREQUAL()
        // CIRCUMFLEXEQUAL()
        // LEFTSHIFTEQUAL()
        // RIGHTSHIFTEQUAL()
        // DOUBLESTAREQUAL()
        // DOUBLESLASHEQUAL()
        throw new UnsupportedOperationException(
            "Unsupported operator: " + ctx.augassign().getText());
      }
      assign.add("op", op);
      assign.add("value", maybeSingleton(visitStar_expressions(ctx.star_expressions())));
      return assign;
    }

    var expression = ctx.expression();
    final boolean annotatedAssign = expression != null;
    JsonObject assignmentNode = createNode(ctx, annotatedAssign ? "AnnAssign" : "Assign");

    var name = ctx.NAME();
    if (name != null) {
      var target = createNode(ctx, "Name");
      target.addProperty("id", name.getText());
      assignmentNode.add("target", target);
    }
    if (annotatedAssign) {
      assignmentNode.add("annotation", visitExpression(expression));
    }
    var annotated_rhs = ctx.annotated_rhs();
    if (annotated_rhs != null) {
      assignmentNode.add("value", singletonOrTuple(visit(annotated_rhs)));
    }
    TerminalNode LPAR = ctx.LPAR();
    if (LPAR != null) {
      assignmentNode.add("LPAR", visit(LPAR));
    }
    var single_target = ctx.single_target();
    if (single_target != null) {
      assignmentNode.add("single_target", visit(single_target));
    }
    TerminalNode RPAR = ctx.RPAR();
    if (RPAR != null) {
      assignmentNode.add("RPAR", visit(RPAR));
    }
    var single_subscript_attribute_target = ctx.single_subscript_attribute_target();
    if (single_subscript_attribute_target != null) {
      assignmentNode.add(
          "single_subscript_attribute_target", visit(single_subscript_attribute_target));
    }
    var yield_expr = ctx.yield_expr();
    if (yield_expr != null) {
      assignmentNode.add("yield_expr", visit(yield_expr));
    }
    var star_expressions = ctx.star_expressions();
    if (star_expressions != null) {
      assignmentNode.add("value", singletonOrTuple(visitStar_expressions(star_expressions)));
    }
    var star_targets = ctx.star_targets();
    if (star_targets != null && !star_targets.isEmpty()) {
      var targets = new JsonArray();
      for (var star_target : star_targets) {
        targets.addAll(visitStar_targets(star_target).getAsJsonArray());
      }
      if (targets.size() <= 1) {
        assignmentNode.add("targets", targets);
      } else {
        var tupleNode = createNode("Tuple");
        tupleNode.add("elts", targets);
        var array = new JsonArray();
        array.add(tupleNode);
        assignmentNode.add("targets", array);
      }
    }
    TerminalNode TYPE_COMMENT = ctx.TYPE_COMMENT();
    if (TYPE_COMMENT != null) {
      assignmentNode.add("TYPE_COMMENT", visit(TYPE_COMMENT));
    }

    return assignmentNode;
  }

  @Override
  public JsonElement visitSingle_target(PythonParser.Single_targetContext ctx) {
    if (ctx.single_subscript_attribute_target() != null) {
      return visitSingle_subscript_attribute_target(ctx.single_subscript_attribute_target());
    } else {
      var node = createNode(ctx, "Name");
      node.addProperty("id", ctx.NAME().getText());
      return node;
    }
  }

  @Override
  public JsonElement visitSingle_subscript_attribute_target(
      PythonParser.Single_subscript_attribute_targetContext ctx) {
    return handle_tprimary_and_slices(ctx.t_primary(), ctx.slices(), ctx.NAME()).get();
  }

  @Override
  public JsonElement visitStar_expressions(PythonParser.Star_expressionsContext ctx) {
    var array = new JsonArray();
    for (var expr : ctx.star_expression()) {
      array.add(visitStar_expression(expr));
    }
    return array;
  }

  @Override
  public JsonElement visitStar_expression(PythonParser.Star_expressionContext ctx) {
    return visitExpression(ctx.expression());
  }

  @Override
  public JsonElement visitStar_targets(PythonParser.Star_targetsContext ctx) {
    var array = new JsonArray();
    for (var target : ctx.star_target()) {
      array.add(visitStar_target(target));
    }
    return array;
  }

  @Override
  public JsonElement visitStar_target(PythonParser.Star_targetContext ctx) {
    return visitTarget_with_star_atom(ctx.target_with_star_atom());
  }

  @Override
  public JsonElement visitTarget_with_star_atom(PythonParser.Target_with_star_atomContext ctx) {
    Optional<JsonElement> optionalResult =
        handle_tprimary_and_slices(ctx.t_primary(), ctx.slices(), ctx.NAME());
    if (optionalResult.isPresent()) {
      return optionalResult.get();
    } else {
      var node = createNode(ctx, "Name");
      if (ctx.star_atom() != null) {
        node.add("id", visitStar_atom(ctx.star_atom()));
      } else {
        node.addProperty("id", ctx.NAME().getText());
      }
      return node;
    }
  }

  private Optional<JsonElement> handle_tprimary_and_slices(
      PythonParser.T_primaryContext t_primary,
      PythonParser.SlicesContext slices,
      TerminalNode name) {
    if (t_primary != null) {
      var value = visitT_primary(t_primary);
      if (slices != null) {
        JsonObject slice = visitSlices(slices).getAsJsonObject();
        var subscript = createNode(slices, "Subscript");
        subscript.add("value", value);
        subscript.add("slice", slice);
        return Optional.of(subscript);
      } else { // TODO(maxuser): check if ctx.DOT() isn't null to indicate attribute?
        var attribute = createNode(t_primary, "Attribute");
        attribute.add("value", value);
        if (name != null) {
          attribute.addProperty("attr", name.getText());
        }
        return Optional.of(attribute);
      }
    }
    return Optional.empty();
  }

  @Override
  public JsonElement visitStar_atom(PythonParser.Star_atomContext ctx) {
    return new JsonPrimitive(ctx.NAME().getSymbol().getText());
  }

  @Override
  public JsonElement visitComparison(PythonParser.ComparisonContext ctx) {
    var bitwise_or_node = visitBitwise_or(ctx.bitwise_or()).getAsJsonObject();

    var comparisons = ctx.compare_op_bitwise_or_pair();
    if (comparisons == null || comparisons.isEmpty()) {
      return bitwise_or_node;
    }

    var node = createNode(ctx, "Compare");
    node.add("left", bitwise_or_node);

    var ops = new JsonArray();
    var comparators = new JsonArray();
    for (var op : comparisons) {
      if (op.eq_bitwise_or() != null) {
        ops.add(createComparisonOp("Eq"));
        comparators.add(visitBitwise_or(op.eq_bitwise_or().bitwise_or()));
      } else if (op.noteq_bitwise_or() != null) {
        ops.add(createComparisonOp("NotEq"));
        comparators.add(visitBitwise_or(op.noteq_bitwise_or().bitwise_or()));
      } else if (op.lte_bitwise_or() != null) {
        ops.add(createComparisonOp("LtE"));
        comparators.add(visitBitwise_or(op.lte_bitwise_or().bitwise_or()));
      } else if (op.lt_bitwise_or() != null) {
        ops.add(createComparisonOp("Lt"));
        comparators.add(visitBitwise_or(op.lt_bitwise_or().bitwise_or()));
      } else if (op.gte_bitwise_or() != null) {
        ops.add(createComparisonOp("GtE"));
        comparators.add(visitBitwise_or(op.gte_bitwise_or().bitwise_or()));
      } else if (op.gt_bitwise_or() != null) {
        ops.add(createComparisonOp("Gt"));
        comparators.add(visitBitwise_or(op.gt_bitwise_or().bitwise_or()));
      } else if (op.notin_bitwise_or() != null) {
        ops.add(createComparisonOp("NotIn"));
        comparators.add(visitBitwise_or(op.notin_bitwise_or().bitwise_or()));
      } else if (op.in_bitwise_or() != null) {
        ops.add(createComparisonOp("In"));
        comparators.add(visitBitwise_or(op.in_bitwise_or().bitwise_or()));
      } else if (op.isnot_bitwise_or() != null) {
        ops.add(createComparisonOp("IsNot"));
        comparators.add(visitBitwise_or(op.isnot_bitwise_or().bitwise_or()));
      } else if (op.is_bitwise_or() != null) {
        ops.add(createComparisonOp("Is"));
        comparators.add(visitBitwise_or(op.is_bitwise_or().bitwise_or()));
      }
    }
    node.add("ops", ops);
    node.add("comparators", comparators);
    return node;
  }

  @Override
  public JsonElement visitSum(PythonParser.SumContext ctx) {
    if (ctx.getChildCount() == 3) { // binary op: left op right
      var node = createNode(ctx, "BinOp");
      node.add("left", visit(ctx.getChild(0))); // Visit the left operand
      final JsonObject op;
      if (ctx.PLUS() != null) {
        op = createOp("Add");
      } else if (ctx.MINUS() != null) {
        op = createOp("Sub");
      } else {
        throw new UnsupportedOperationException(
            "Unsupported operator: " + ctx.getChild(1).getText());
      }
      node.add("op", op);
      node.add("right", visit(ctx.getChild(2))); // Visit the right operand
      return node;
    } else {
      return visit(ctx.term());
    }
  }

  @Override
  public JsonElement visitShift_expr(PythonParser.Shift_exprContext ctx) {
    if (ctx.getChildCount() == 3) { // binary op: left op right
      var node = createNode(ctx, "BinOp");
      node.add("left", visit(ctx.getChild(0))); // Visit the left operand
      final JsonObject op;
      if (ctx.LEFTSHIFT() != null) {
        op = createOp("LShift");
      } else if (ctx.RIGHTSHIFT() != null) {
        op = createOp("RShift");
      } else {
        throw new UnsupportedOperationException(
            "Unsupported operator: " + ctx.getChild(1).getText());
      }
      node.add("op", op);
      node.add("right", visit(ctx.getChild(2))); // Visit the right operand
      return node;
    } else {
      return visit(ctx.sum());
    }
  }

  @Override
  public JsonElement visitTerm(PythonParser.TermContext ctx) {
    if (ctx.getChildCount() == 3) { // binary op: left op right
      var node = createNode(ctx, "BinOp");
      node.add("left", visit(ctx.getChild(0))); // Visit the left operand
      final JsonObject op;
      if (ctx.STAR() != null) {
        op = createOp("Mult");
      } else if (ctx.SLASH() != null) {
        op = createOp("Div");
      } else if (ctx.PERCENT() != null) {
        op = createOp("Mod");
      } else if (ctx.DOUBLESLASH() != null) {
        op = createOp("FloorDiv");
      } else {
        throw new UnsupportedOperationException(
            "Unsupported operator: " + ctx.getChild(1).getText());
      }
      node.add("op", op);
      node.add("right", visit(ctx.getChild(2))); // Visit the right operand
      return node;
    } else if (ctx.factor() != null) {
      return visit(ctx.factor());
    } else if (ctx.term() != null) {
      return visit(ctx.term());
    }
    return defaultResult(ctx);
  }

  @Override
  public JsonElement visitFactor(PythonParser.FactorContext ctx) {
    if (ctx.getChildCount() == 2) {
      var unaryOp = createNode(ctx, "UnaryOp");
      unaryOp.add(
          "op",
          createOp(
              switch (ctx.getChild(0).getText()) {
                case "+" -> "UAdd";
                case "-" -> "USub";
                case "~" -> "Invert";
                default ->
                    throw new UnsupportedOperationException(
                        "Unsupported operator at line %d: %s"
                            .formatted(ctx.start.getLine(), ctx.getChild(0).getText()));
              }));
      unaryOp.add("operand", visit(ctx.getChild(1)));
      return unaryOp;
    } else {
      return visit(ctx.power());
    }
  }

  @Override
  public JsonElement visitPower(PythonParser.PowerContext ctx) {
    if (ctx.getChildCount() == 3 && ctx.DOUBLESTAR() != null) { // binary op: left ** right
      var node = createNode(ctx, "BinOp");
      node.add("left", visit(ctx.getChild(0))); // left operand
      final JsonObject op = createOp("Pow");
      node.add("op", op);
      node.add("right", visit(ctx.getChild(2))); // right operand
      return node;
    } else {
      return visit(ctx.await_primary());
    }
  }

  @Override
  public JsonElement visitAwait_primary(PythonParser.Await_primaryContext ctx) {
    return visit(ctx.primary());
  }

  @Override
  public JsonElement visitPrimary(PythonParser.PrimaryContext ctx) {
    if (ctx.primary() != null) {
      var primary = visitPrimary(ctx.primary()).getAsJsonObject();
      if (ctx.LPAR() != null) {
        var call = visitArguments(ctx.arguments());
        call.add("func", primary);
        addLinenoIfAbsent(call, ctx);
        return call;
      }
      if (ctx.LSQB() != null) {
        var slice = visitSlices(ctx.slices()).getAsJsonObject();
        var subscript = createNode(ctx.slices(), "Subscript");
        subscript.add("value", primary);
        subscript.add("slice", slice);
        return subscript;
      }
      if (ctx.DOT() != null) { // Check for attribute access
        var attributeNode = createNode(ctx.primary(), "Attribute");
        attributeNode.add("value", primary); // expr on lhs of the dot
        attributeNode.addProperty("attr", ctx.NAME().getText()); // name on rhs of the dot
        return attributeNode;
      }
      return primary;
    }
    return visitAtom(ctx.atom());
  }

  @Override
  public JsonElement visitT_primary(PythonParser.T_primaryContext ctx) {
    JsonObject primary =
        ctx.t_primary() == null
            ? new JsonObject()
            : visitT_primary(ctx.t_primary()).getAsJsonObject();
    if (ctx.LPAR() != null) {
      var call = visitArguments(ctx.arguments());
      call.add("func", primary);
      addLinenoIfAbsent(call, ctx);
      return call;
    }
    if (ctx.LSQB() != null) {
      JsonObject slice = visitSlices(ctx.slices()).getAsJsonObject();
      var subscript = createNode(ctx.slices(), "Subscript");
      subscript.add("value", primary);
      subscript.add("slice", slice);
      return subscript;
    }
    if (ctx.DOT() != null) { // Check for attribute access
      JsonObject attributeNode = createNode(ctx.t_primary(), "Attribute");
      attributeNode.add("value", primary); // expr on lhs of the dot
      attributeNode.addProperty("attr", ctx.NAME().getText()); // name on rhs of the dot
      return attributeNode;
    }
    if (ctx.atom() != null) {
      primary.addProperty("type", "Name");
      primary.addProperty("id", ctx.atom().getText());
      return primary;
    }
    return primary;
  }

  @Override
  public JsonObject visitArguments(PythonParser.ArgumentsContext ctx) {
    if (ctx != null && ctx.args() != null) {
      return visitArgs(ctx.args());
    } else {
      var call = createNode("Call");
      call.add("args", new JsonArray());
      call.add("keywords", new JsonArray());
      return call;
    }
  }

  @Override
  public JsonObject visitArgs(PythonParser.ArgsContext ctx) {
    var args = new JsonArray();
    // Ignoring: assignment_expression.
    for (int i = 0; i < ctx.getChildCount(); i++) {
      ParseTree child = ctx.getChild(i);
      if (child instanceof PythonParser.ExpressionContext expr) {
        args.add(visitExpression(expr));
      } else if (child instanceof PythonParser.Starred_expressionContext expr) {
        var starred = createNode(expr, "Starred");
        starred.add("value", visitStarred_expression(expr));
        args.add(starred);
      }
    }
    var keywords = new JsonArray();
    if (ctx.kwargs() != null) {
      for (var kwarg : ctx.kwargs().kwarg_or_starred()) {
        var keyword = createNode(kwarg, "keyword");
        keyword.addProperty("arg", kwarg.NAME().getText());
        keyword.add("value", visitExpression(kwarg.expression()));
        keywords.add(keyword);
      }
      for (var kwarg : ctx.kwargs().kwarg_or_double_starred()) {
        var value = visitExpression(kwarg.expression());
        var keyword = createNode(kwarg, "keyword");
        keyword.add("arg", JsonNull.INSTANCE);
        keyword.add("value", value);
        keywords.add(keyword);
      }
    }
    var call = createNode(ctx, "Call");
    call.add("args", args);
    call.add("keywords", keywords);
    return call;
  }

  @Override
  public JsonElement visitStarred_expression(PythonParser.Starred_expressionContext ctx) {
    return visitExpression(ctx.expression());
  }

  @Override
  public JsonElement visitSlices(PythonParser.SlicesContext ctx) {
    return visitSlice(ctx.slice(0)); // TODO(maxuser): Always one slice?
  }

  @Override
  public JsonElement visitSlice(PythonParser.SliceContext ctx) {
    if (ctx.named_expression() != null) {
      return visitNamed_expression(ctx.named_expression());
    }
    if (ctx.expression() != null) {
      // Expressions provided for slots of the slice expression. Does not contain colons/delimiters.
      var indices = ctx.expression();

      // Counter to get the next expression from 'indices'.
      int expressionIndex = 0;

      // Counter for the current slice part (0=lower, 1=upper, 2=step).
      int slotIndex = 0;

      // Array containing expressions for { lower, upper, step }. Omitted values are left null.
      var slots = new JsonElement[3];

      for (var child : ctx.children) {
        if (":".equals(child.getText())) {
          slotIndex++;
        } else {
          slots[slotIndex] = visitExpression(indices.get(expressionIndex++));
        }
      }

      var node = createNode(ctx, "Slice");
      node.add("lower", slots[0]);
      node.add("upper", slots[1]);
      node.add("step", slots[2]);

      return node;
    }
    return defaultResult(ctx);
  }

  @Override
  public JsonElement visitNamed_expression(PythonParser.Named_expressionContext ctx) {
    if (ctx.assignment_expression() != null) {
      var assignment = ctx.assignment_expression();
      var namedExpr = createNode(ctx, "NamedExpr");
      var target = createNode("Name");
      target.addProperty("id", assignment.NAME().getText());
      namedExpr.add("target", target);
      namedExpr.add("value", visitExpression(assignment.expression()));
      return namedExpr;
    } else if (ctx.expression() != null) {
      return visitExpression(ctx.expression());
    }
    return defaultResult(ctx);
  }

  @Override
  public JsonElement visitExpression(PythonParser.ExpressionContext ctx) {
    if (ctx.lambdef() != null) {
      return visitLambdef(ctx.lambdef());
    } else if (ctx.disjunction().size() > 0) {
      if (ctx.IF() != null && ctx.ELSE() != null) {
        // Conditional expression (e.g., x if y else z)
        var conditional = createNode(ctx, "IfExp");
        conditional.add("test", visit(ctx.disjunction(1))); // Condition
        conditional.add("body", visit(ctx.disjunction(0))); // Value if true
        conditional.add("orelse", visit(ctx.expression())); // Value if false
        return conditional;
      } else if (ctx.disjunction().size() == 1) {
        return visitDisjunction(ctx.disjunction(0)); // Just a single disjunction
      } else {
        // Handle multiple disjunctions.
        var disjunctions = new JsonArray();
        for (PythonParser.DisjunctionContext disjunctionContext : ctx.disjunction()) {
          disjunctions.add(visit(disjunctionContext));
        }
        var disjunctionNode = createNode(ctx, "Disjunctions");
        disjunctionNode.add("values", disjunctions);
        return disjunctionNode;
      }
    }
    throw new UnsupportedOperationException(ctx.toString());
  }

  @Override
  public JsonObject visitLambdef(PythonParser.LambdefContext ctx) {
    var lambdaNode = createNode(ctx, "Lambda");
    var params = ctx.lambda_params();
    lambdaNode.add("args", params == null ? createEmptyLambdaParams() : visit(params));
    lambdaNode.add("body", visit(ctx.expression()));
    return lambdaNode;
  }

  private static JsonObject createEmptyLambdaParams() {
    JsonObject args = createNode("arguments");
    args.add("args", new JsonArray());
    return args;
  }

  @Override
  public JsonElement visitLambda_params(PythonParser.Lambda_paramsContext ctx) {
    return visitLambda_parameters(ctx.lambda_parameters());
  }

  @Override
  public JsonElement visitLambda_parameters(PythonParser.Lambda_parametersContext ctx) {
    JsonObject args = createNode(ctx, "arguments");
    JsonArray argList = new JsonArray();
    if (ctx.lambda_param_no_default() != null) {
      for (var param : ctx.lambda_param_no_default()) {
        var lp = param.lambda_param();
        JsonObject argNode = createNode(lp, "arg");
        argNode.addProperty("arg", lp.NAME().getText());
        argList.add(argNode);
      }
    }
    args.add("args", argList);
    return args;
  }

  @Override
  public JsonElement visitDisjunction(PythonParser.DisjunctionContext ctx) {
    if (ctx.conjunction().size() == 1) {
      return visitConjunction(ctx.conjunction(0));
    } else {
      var disjunction = createNode(ctx, "BoolOp");
      disjunction.add("op", createOp("Or"));
      JsonArray values = new JsonArray();
      for (PythonParser.ConjunctionContext conjunctionContext : ctx.conjunction()) {
        values.add(visit(conjunctionContext));
      }
      disjunction.add("values", values);
      return disjunction;
    }
  }

  @Override
  public JsonElement visitConjunction(PythonParser.ConjunctionContext ctx) {
    if (ctx.inversion().size() == 1) {
      return visitInversion(ctx.inversion(0));
    } else {
      var conjunction = createNode(ctx, "BoolOp");
      conjunction.add("op", createOp("And"));
      JsonArray values = new JsonArray();
      for (PythonParser.InversionContext inversionContext : ctx.inversion()) {
        values.add(visit(inversionContext));
      }
      conjunction.add("values", values);
      return conjunction;
    }
  }

  @Override
  public JsonElement visitInversion(PythonParser.InversionContext ctx) {
    try {
      if (ctx.NOT() != null) {
        var unaryOp = createNode(ctx, "UnaryOp");
        unaryOp.add("op", createOp("Not"));
        unaryOp.add("operand", visitInversion(ctx.inversion()));
        return unaryOp;
      } else {
        return visitComparison(ctx.comparison());
      }
    } catch (Exception e) {
      throw new RuntimeException("Parse error at line %d".formatted(ctx.start.getLine()), e);
    }
  }

  @Override
  public JsonElement visitAtom(PythonParser.AtomContext ctx) {
    if (ctx.NAME() != null) {
      var name = createNode(ctx, "Name");
      name.addProperty("id", ctx.NAME().getText());
      return name;
    } else if (ctx.NUMBER() != null) {
      var number = createNode(ctx, "Constant");
      String numberText = ctx.NUMBER().getText();
      switch (NumberParser.getFormat(numberText)) {
        case INT:
          number.addProperty("value", NumberParser.parseAsLong(numberText));
          number.addProperty("typename", "int");
          break;
        case FLOAT:
          number.addProperty("value", Double.parseDouble(numberText));
          number.addProperty("typename", "float");
          break;
        default:
          throw new NumberFormatException("Unable to parse numeric literal: " + numberText);
      }
      return number;
    } else if (ctx.strings() != null) {
      return visitStrings(ctx.strings());
    } else if (ctx.TRUE() != null) {
      var trueBool = createNode(ctx, "Constant");
      trueBool.addProperty("value", true);
      trueBool.addProperty("typename", "bool");
      return trueBool;
    } else if (ctx.FALSE() != null) {
      var falseBool = createNode(ctx, "Constant");
      falseBool.addProperty("value", false);
      falseBool.addProperty("typename", "bool");
      return falseBool;
    } else if (ctx.NONE() != null) {
      var none = createNode(ctx, "Constant");
      none.add("value", JsonNull.INSTANCE);
      none.addProperty("typename", "NoneType");
      return none;
    } else if (ctx.tuple() != null) {
      var tuple = createNode(ctx, "Tuple");
      tuple.add("elts", visitTuple(ctx.tuple()));
      return tuple;
    } else if (ctx.list() != null) {
      var list = createNode(ctx, "List");
      list.add("elts", visitList(ctx.list()));
      return list;
    } else if (ctx.dict() != null) {
      var dict = createNode(ctx, "Dict");
      var keys = new JsonArray();
      var values = new JsonArray();
      try {
        var kvpairs = ctx.dict().double_starred_kvpairs();
        if (kvpairs != null) {
          var kvpair = kvpairs.double_starred_kvpair();
          for (var kv : kvpair) {
            var pair = kv.kvpair();
            if (pair.expression().size() != 2) {
              throw new UnsupportedOperationException("Unsupported dict pair: " + kv.getText());
            }
            keys.add(visitExpression(pair.expression(0)));
            values.add(visitExpression(pair.expression(1)));
          }
        }
      } catch (Exception e) {
        throw new RuntimeException("Parse error at line %d".formatted(ctx.start.getLine()), e);
      }
      dict.add("keys", keys);
      dict.add("values", values);
      return dict;
    } else if (ctx.set() != null) {
      var set = createNode(ctx, "Set");
      set.add("elts", visitSet(ctx.set()));
      return set;
    } else if (ctx.listcomp() != null) {
      return visitListcomp(ctx.listcomp());
    } else if (ctx.group() != null) {
      return visitNamed_expression(ctx.group().named_expression());
    } else {
      throw new UnsupportedOperationException("Unsupported atom: " + ctx.getText());
    }
    // Add handling for other atom types (e.g., booleans, None, etc.)
  }

  @Override
  public JsonElement visitListcomp(PythonParser.ListcompContext ctx) {
    var node = createNode(ctx, "ListComp");
    node.add("elt", visitNamed_expression(ctx.named_expression()));

    var generators = new JsonArray();
    for (var clause : ctx.for_if_clauses().for_if_clause()) {
      var comprehension = createNode(clause, "comprehension");
      JsonElement target = maybeSingleton(visitStar_targets(clause.star_targets()));
      if (target.isJsonArray()) {
        var tuple = createNode("Tuple");
        tuple.add("elts", target);
        target = tuple;
      }
      comprehension.add("target", target);
      var ifs = new JsonArray();
      boolean first = true;
      for (var disjunction : clause.disjunction()) {
        if (first) {
          comprehension.add("iter", maybeSingleton(visitDisjunction(disjunction)));
          first = false;
        } else {
          ifs.add(maybeSingleton(visitDisjunction(disjunction)));
        }
      }
      comprehension.add("ifs", ifs);
      generators.add(comprehension);
    }
    node.add("generators", generators);
    return node;
  }

  @Override
  public JsonElement visitTuple(PythonParser.TupleContext ctx) {
    JsonArray tuple = new JsonArray();
    if (ctx.star_named_expression() != null) {
      tuple.add(visitStar_named_expression(ctx.star_named_expression()));
    }
    if (ctx.star_named_expressions() != null) {
      tuple.addAll(visitStar_named_expressions(ctx.star_named_expressions()).getAsJsonArray());
    }
    return tuple;
  }

  @Override
  public JsonElement visitList(PythonParser.ListContext ctx) {
    JsonArray list = new JsonArray();
    if (ctx.star_named_expressions() != null) {
      list = visitStar_named_expressions(ctx.star_named_expressions()).getAsJsonArray();
    }
    return list;
  }

  @Override
  public JsonElement visitSet(PythonParser.SetContext ctx) {
    JsonArray elements = new JsonArray();
    if (ctx.star_named_expressions() != null) {
      elements = visitStar_named_expressions(ctx.star_named_expressions()).getAsJsonArray();
    }
    return elements;
  }

  @Override
  public JsonElement visitStar_named_expression(PythonParser.Star_named_expressionContext ctx) {
    if (ctx.named_expression() != null) {
      return visitNamed_expression(ctx.named_expression());
    }
    if (ctx.bitwise_or() != null) {
      var node = createNode(ctx.bitwise_or(), "Starred");
      node.add("value", visitBitwise_or(ctx.bitwise_or()));
      return node;
    }
    return defaultResult(ctx);
  }

  @Override
  public JsonElement visitStar_named_expressions(PythonParser.Star_named_expressionsContext ctx) {
    if (ctx.star_named_expression() != null) {
      var array = new JsonArray();
      for (var expr : ctx.star_named_expression()) {
        array.add(visitStar_named_expression(expr));
      }
      return array;
    }
    return defaultResult(ctx);
  }

  @Override
  public JsonElement visitStrings(PythonParser.StringsContext ctx) {
    JsonArray joinedStrValues = new JsonArray();
    String runningConstant = "";
    for (var child : ctx.children) {
      if (child instanceof PythonParser.FstringContext fstring) {
        for (var middle : fstring.fstring_middle()) {
          var replacementField = middle.fstring_replacement_field();
          if (replacementField != null) {
            var expressions = replacementField.star_expressions();
            if (expressions != null) {
              if (!runningConstant.isEmpty()) {
                joinedStrValues.add(createConstantStringNode(runningConstant));
                runningConstant = "";
              }
              var formattedValue = createNode(expressions, "FormattedValue");
              formattedValue.add("value", maybeSingleton(visitStar_expressions(expressions)));
              joinedStrValues.add(formattedValue);
            }
          } else {
            runningConstant += unescapeString(middle.getText());
          }
        }
      } else if (child instanceof PythonParser.StringContext string) {
        runningConstant += unescapeStringContext(string);
      }
    }

    if (!runningConstant.isEmpty()) {
      if (joinedStrValues.isEmpty()) {
        return createConstantStringNode(runningConstant);
      } else {
        joinedStrValues.add(createConstantStringNode(runningConstant));
      }
    }

    if (joinedStrValues.isEmpty()) {
      return createConstantStringNode("");
    }

    var joinedStr = createNode(ctx, "JoinedStr");
    joinedStr.add("values", joinedStrValues);
    return joinedStr;
  }

  private static JsonObject createConstantStringNode(String str) {
    var node = createNode("Constant");
    node.addProperty("value", str);
    node.addProperty("typename", "str");
    return node;
  }

  private static String unescapeStringContext(PythonParser.StringContext ctx) {
    String str = ctx.STRING().getText();
    if (str == null) {
      return null;
    }

    if (str.length() < 2) {
      throw new IllegalArgumentException("String literal is too short: " + str);
    }

    final boolean isRawString;
    if (str.charAt(0) == 'r' || str.charAt(0) == 'R') {
      isRawString = true;
      str = str.substring(1);
    } else {
      isRawString = false;
    }

    if (str.startsWith("\"\"\"") && str.endsWith("\"\"\"")) {
      str = str.substring(3, str.length() - 3);
    } else if (str.startsWith("'''") && str.endsWith("'''")) {
      str = str.substring(3, str.length() - 3);
    } else if (str.startsWith("\"") && str.endsWith("\"")) {
      str = str.substring(1, str.length() - 1);
    } else if (str.startsWith("'") && str.endsWith("'")) {
      str = str.substring(1, str.length() - 1);
    } else {
      throw new IllegalArgumentException("Invalid string literal: " + str);
    }

    if (isRawString) {
      return str;
    }

    return unescapeString(str);
  }

  private static String unescapeString(String str) {
    var sb = new StringBuilder(str.length());
    for (int i = 0; i < str.length(); i++) {
      char ch = str.charAt(i);
      if (ch == '\\') {
        i++;
        if (i >= str.length()) {
          sb.append('\\'); // Handle trailing backslash
          break;
        }
        char nextChar = str.charAt(i);
        switch (nextChar) {
          case '\\':
            ch = '\\';
            break;
          case 'b':
            ch = '\b';
            break;
          case 'f':
            ch = '\f';
            break;
          case 'n':
            ch = '\n';
            break;
          case 'r':
            ch = '\r';
            break;
          case 't':
            ch = '\t';
            break;
          case '\"':
            ch = '\"';
            break;
          case '\'':
            ch = '\'';
            break;
          // Octal escape?
          case '0':
          case '1':
          case '2':
          case '3':
          case '4':
          case '5':
          case '6':
          case '7':
            int code = nextChar - '0';
            i++;
            if ((i < str.length()) && str.charAt(i) >= '0' && str.charAt(i) <= '7') {
              code = (code << 3) + (str.charAt(i) - '0');
              i++;
              if ((i < str.length()) && str.charAt(i) >= '0' && str.charAt(i) <= '7') {
                code = (code << 3) + (str.charAt(i) - '0');
              }
            }
            ch = (char) code;
            i--;
            break;
          // Hex Unicode escape?
          case 'u':
            if (i >= str.length() - 4) {
              ch = 'u';
              break;
            }
            try {
              int unicode = Integer.parseInt(str.substring(i + 1, i + 5), 16);
              ch = (char) unicode;
              i += 4;
            } catch (NumberFormatException e) {
              ch = 'u';
            }
            break;
          default:
            sb.append('\\');
            ch = nextChar;
        }
      }
      sb.append(ch);
    }
    return sb.toString();
  }

  @Override
  public JsonElement visitAssignment_expression(PythonParser.Assignment_expressionContext ctx) {
    var assignNode = createNode(ctx, "Assign");
    assignNode.addProperty("debug", "TODO");
    // TODO(maxuser): implement
    return assignNode;
  }

  @Override
  protected JsonElement defaultResult() {
    return new JsonObject();
  }

  private JsonElement defaultResult(ParserRuleContext ctx) {
    var node = createNode(ctx, "Unknown");
    node.addProperty("debug_context", ctx.getClass().getSimpleName());
    node.addProperty("debug_text", ctx.getText());
    return node;
  }

  private static void addLinenoIfAbsent(JsonObject node, ParserRuleContext ctx) {
    if (!node.has("lineno")) {
      node.addProperty("lineno", ctx.start.getLine());
    }
  }

  private static JsonObject createComparisonOp(String op) {
    return createNode(op);
  }

  private static JsonObject createNode(String type) {
    var node = new JsonObject();
    node.addProperty("type", type);
    return node;
  }

  private JsonObject createNode(ParserRuleContext ctx, String type) {
    var node = new JsonObject();
    node.addProperty("type", type);
    node.addProperty("lineno", ctx.start.getLine());
    return node;
  }

  private JsonObject createNode(ParserRuleContext ctx) {
    var node = new JsonObject();
    node.addProperty("lineno", ctx.start.getLine());
    return node;
  }
}
