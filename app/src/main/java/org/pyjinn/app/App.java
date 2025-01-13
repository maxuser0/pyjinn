// SPDX-FileCopyrightText: © 2025 Greg Christiana <maxuser@pyjinn.org>
// SPDX-License-Identifier: MIT

package org.pyjinn.app;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.stream.Collectors;
import org.pyjinn.interpreter.Script;
import org.pyjinn.parser.PyjinnParser;

public class App {
  public static void main(String[] args) throws Exception {
    String stdinString =
        new BufferedReader(new InputStreamReader(System.in))
            .lines()
            .collect(Collectors.joining("\n"));

    final JsonElement jsonAst;
    if (args.length > 0 && args[0].equals("read-ast")) {
      jsonAst = JsonParser.parseString(stdinString);
      String[] shiftedArgs = new String[args.length - 1];
      System.arraycopy(args, 1, shiftedArgs, 0, args.length - 1);
      args = shiftedArgs;
    } else if (args.length > 0 && args[0].equals("dump-ast")) {
      jsonAst = PyjinnParser.parse(stdinString);
      Gson gson = new GsonBuilder().serializeNulls().setPrettyPrinting().create();
      System.out.println(gson.toJson(jsonAst));
      return;
    } else {
      jsonAst = PyjinnParser.parse(stdinString);
    }

    var script = new Script();
    script.parse(jsonAst);
    script.exec();

    if (args.length == 1) {
      var func = script.getFunction(args[0]);
      System.out.println(func);
      var returnValue = script.invoke(func);
      System.out.println(returnValue);
    }
  }
}
