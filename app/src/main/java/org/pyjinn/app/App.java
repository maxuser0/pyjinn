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
import java.util.Set;
import java.util.stream.Collectors;
import org.pyjinn.interpreter.Script;
import org.pyjinn.parser.PyjinnParser;

public class App {
  public static void main(String[] args) throws Exception {
    String stdinString =
        new BufferedReader(new InputStreamReader(System.in))
            .lines()
            .collect(Collectors.joining("\n"));

    Set<String> argsSet = new HashSet<>(Arrays.asList(args));

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
    script.parse(jsonAst);
    script.exec();

    if (args.length == 1) {
      var func = script.getFunction(args[0]);
      System.out.println(func);
      var returnValue = func.call(script.mainModule().globals());
      System.out.println(returnValue);
    }
  }
}
