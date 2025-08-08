// SPDX-FileCopyrightText: © 2025 Greg Christiana <maxuser@pyjinn.org>
// SPDX-License-Identifier: MIT

package org.pyjinn.parser;

public class ParseException extends RuntimeException {
  public final String filename;
  public final int errorLine;
  public final int errorColumn;
  public final String sourceLine;

  public ParseException(
      String message, String filename, int errorLine, int errorColumn, String sourceLine) {
    super(buildMessage(message, errorLine, errorColumn, sourceLine));
    this.filename = filename;
    this.errorLine = errorLine;
    this.errorColumn = errorColumn;
    this.sourceLine = sourceLine;
  }

  private static String buildMessage(String message, int line, int column, String sourceLine) {
    final String lineBeforeError;
    final String lineAfterError;
    if (column >= 0 && column < sourceLine.length()) {
      lineBeforeError = sourceLine.substring(0, column);
      lineAfterError = sourceLine.substring(column);
    } else {
      lineBeforeError = "ERROR ";
      lineAfterError = " " + sourceLine;
    }
    var out = new StringBuilder();
    out.append(message);
    out.append("\n");
    out.append(lineBeforeError);
    out.append(">>>");
    out.append(lineAfterError);
    return out.toString();
  }
}
