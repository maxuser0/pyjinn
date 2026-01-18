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

    String debug = System.getenv("PYJINN_DEBUG");
    if (debug != null && !debug.equals("") && !debug.equals("0")) {
      Script.setDebugLogger((message, params) -> System.out.printf(message + "\n", params));
      Script.setVerboseDebugging(true);
    }

    boolean compile = !argsSet.contains("--no-compile");
    if (!compile) {
      argsSet.remove("--no-compile");
    }

    if (argsSet.contains("-i")) {
      repl(compile);
      return;
    }

    String stdinString =
        new BufferedReader(new InputStreamReader(System.in))
            .lines()
            .collect(Collectors.joining("\n"));

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
    } finally {
      script.exit(); // Ensure that at-exit callbacks are run.
    }
  }

  public static void repl(boolean compile) throws Exception {
    var version = Script.versionInfo();
    System.out.printf("Pyjinn %s\n", version.pyjinnVersion());
    System.out.printf("[Java %s]\n", version.javaVersion());

    var script = new Script();

    // Set handler for printing non-null results of global expression statements to stdout.
    script.setGlobalExpressionHandler(
        result -> {
          if (result != null) {
            System.out.println(Script.PyjObjects.toString(result));
          }
        });

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
