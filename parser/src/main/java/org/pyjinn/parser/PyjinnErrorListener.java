// SPDX-FileCopyrightText: © 2025-2026 Greg Christiana <maxuser@pyjinn.org>
// SPDX-License-Identifier: MIT

package org.pyjinn.parser;

import java.util.List;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;

public class PyjinnErrorListener extends BaseErrorListener {
  private final String filename;
  private final String sourceCode;

  public PyjinnErrorListener(String filename, String sourceCode) {
    this.filename = filename;
    this.sourceCode = sourceCode;
  }

  @Override
  public void syntaxError(
      Recognizer<?, ?> recognizer,
      Object offendingSymbol,
      int line,
      int column,
      String msg,
      RecognitionException e) {
    List<String> sourceLines = sourceCode.lines().toList();
    String sourceLine =
        (line > 0 && line <= sourceLines.size()) ? sourceLines.get(line - 1) : "<unknown>";

    throw new ParseException(
        "Syntax error at %s line %d column %d: %s".formatted(filename, line, column, msg),
        filename,
        line,
        column,
        sourceLine);
  }
}
