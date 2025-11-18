// SPDX-FileCopyrightText: © 2025 Greg Christiana <maxuser@pyjinn.org>
// SPDX-License-Identifier: MIT

package org.pyjinn.app;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.HashSet;
import java.util.NoSuchElementException;
import java.util.Scanner;
import java.util.Set;
import java.util.stream.Collectors;
import org.pyjinn.interpreter.Script;
import org.pyjinn.parser.PyjinnParser;

public class App {
  public static void main(String[] args) throws Exception {
    Set<String> argsSet = new HashSet<>(Arrays.asList(args));

    if (argsSet.equals(Set.of("-i"))) {
      repl();
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
      script.exec();

      if (args.length == 1) {
        var func = script.getFunction(args[0]);
        System.out.println(func);
        var returnValue = func.call(script.mainModule().globals());
        System.out.println(returnValue);
      }
    } finally {
      script.exit(); // Ensure that at-exit callbacks are run.
    }
  }

  public static void repl() throws Exception {
    var version = Script.versionInfo();
    System.out.printf("Pyjinn %s\n", version.pyjinnVersion());
    System.out.printf("[Java %s]\n", version.javaVersion());

    var script = new Script();
    try (var scanner = new Scanner(System.in)) {
      String stdinString = "";
      boolean startOfBlockElement = false;
      while (true) {
        boolean isFirstParseLine = stdinString.isEmpty();
        if (isFirstParseLine) {
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

        if (isFirstParseLine) {
          startOfBlockElement = line.matches("^(for|while|if|def|class|with) .*");
        }
        stdinString += line;
        if (stdinString.trim().isEmpty()) {
          continue;
        }
        if (startOfBlockElement && !line.isEmpty()) {
          continue;
        }

        try {
          JsonElement jsonAst = PyjinnParser.parse("<stdin>", stdinString);
          script.parse(jsonAst);
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
