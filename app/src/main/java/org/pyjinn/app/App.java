// SPDX-FileCopyrightText: © 2025 Greg Christiana <maxuser@pyjinn.org>
// SPDX-License-Identifier: MIT

package org.pyjinn.app;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Scanner;
import java.util.Set;
import java.util.stream.Collectors;
import org.pyjinn.interpreter.Script;
import org.pyjinn.parser.PyjinnParser;

public class App {
  public static void main(String[] args) throws Exception {
    List<String> argsList = new ArrayList<>(Arrays.asList(args));
    Set<String> argsSet = new HashSet<>(argsList);
    boolean compile = argsSet.contains("-c");
    if (compile) {
      argsSet.remove("-c");
      argsList.remove("-c");
    }

    if (argsSet.contains("-i")) {
      repl(compile);
      return;
    }

    String stdinString =
        new BufferedReader(new InputStreamReader(System.in))
            .lines()
            .collect(Collectors.joining("\n"));

    if (System.getenv("PYJINN_DEBUG") != null) {
      Script.setDebugLogger((message, params) -> System.out.printf(message + "\n", params));
    }

    JsonElement jsonAst = null;
    if (argsSet.contains("read-ast")) {
      jsonAst = JsonParser.parseString(stdinString);
      argsSet.remove("read-ast");
    } else {
      boolean intermediateOutput = false;
      if (argsSet.contains("dump-parse-tree")) {
        var parserOutput = PyjinnParser.parseTrees("<stdin>", stdinString);
        var parser = parserOutput.parser();
        System.out.println(parserOutput.parseTree().toStringTree(parser));
        argsSet.remove("dump-parse-tree");
        intermediateOutput = true;
      }
      if (argsSet.contains("dump-ast")) {
        jsonAst = PyjinnParser.parse("<stdin>", stdinString);
        Gson gson = new GsonBuilder().serializeNulls().setPrettyPrinting().create();
        System.out.println(gson.toJson(jsonAst));
        argsSet.remove("dump-ast");
        intermediateOutput = true;
      }
      if (intermediateOutput) {
        return;
      }
    }

    if (jsonAst == null) {
      jsonAst = PyjinnParser.parse("<stdin>", stdinString);
    }

    var script = new Script();
    try {
      script.parse(jsonAst);
      if (compile) {
        script.compile();
      }
      script.exec();

      if (argsList.size() == 1) {
        var func = script.getFunction(argsList.get(0));
        System.out.println(func);
        var returnValue = func.call(script.mainModule().globals());
        System.out.println(returnValue);
      }
    } finally {
      script.exit(); // Ensure that at-exit callbacks are run.
    }
  }

  public static void repl(boolean compile) throws Exception {
    var version = Script.versionInfo();
    System.out.printf("Pyjinn %s\n", version.pyjinnVersion());
    System.out.printf("[Java %s]\n", version.javaVersion());

    var script = new Script();
    try (var scanner = new Scanner(System.in)) {
      String stdinString = "";
      boolean isCodeIncomplete = false;
      while (true) {
        boolean isFirstLine = stdinString.isEmpty();
        if (isFirstLine) {
          System.out.printf(">>> ");
        } else {
          stdinString += "\n";
          System.out.printf("... ");
        }

        final String line;
        try {
          line = scanner.nextLine();
        } catch (NoSuchElementException e) {
          System.out.println("^D");
          return;
        }

        if (isFirstLine) {
          isCodeIncomplete =
              line.startsWith("@") || line.matches("^(for|while|if|def|class|with) .*");
        }
        stdinString += line;
        if (stdinString.strip().isEmpty()) {
          continue;
        }
        if (isCodeIncomplete && !line.isEmpty()) {
          continue;
        }
        if (line.stripTrailing().matches(".*[:(\\[\\\\]")) {
          isCodeIncomplete = true;
          continue;
        }

        try {
          JsonElement jsonAst = PyjinnParser.parse("<stdin>", stdinString);
          script.parse(jsonAst);
          if (compile) {
            script.compile();
          }
          script.exec();
          var expr = script.mainModule().globals().vars().get("$expr");
          if (expr != null) {
            script.mainModule().globals().set("$expr", null);
            System.out.println(Script.PyjObjects.toString(expr));
          }
          stdinString = "";
        } catch (Exception e) {
          stdinString = "";
          script.mainModule().globals().globalStatements().clear();
          e.printStackTrace();
        }
      }
    } finally {
      script.exit(); // Ensure that at-exit callbacks are run.
    }
  }
}
