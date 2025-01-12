// Copied from
// https://github.com/antlr/grammars-v4/blob/master/python/python3_12/Java/PythonParserBase.java
package org.pyjinn.grammar; // Added for Pyjinn project to encapsulate deps.

import org.antlr.v4.runtime.*;

public abstract class PythonParserBase extends Parser {
  protected PythonParserBase(TokenStream input) {
    super(input);
  }

  // https://docs.python.org/3/reference/lexical_analysis.html#soft-keywords
  public boolean isEqualToCurrentTokenText(String tokenText) {
    return this.getCurrentToken().getText().equals(tokenText);
  }

  public boolean isnotEqualToCurrentTokenText(String tokenText) {
    return !this.isEqualToCurrentTokenText(
        tokenText); // for compatibility with the Python 'not' logical operator
  }
}
