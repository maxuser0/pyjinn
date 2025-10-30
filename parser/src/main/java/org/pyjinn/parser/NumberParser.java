// SPDX-FileCopyrightText: © 2025 Greg Christiana <maxuser@pyjinn.org>
// SPDX-License-Identifier: MIT

package org.pyjinn.parser;

/** Parser for Pyjinn numeric values. */
public class NumberParser {
  /** Classification of numeric literal kinds. */
  public enum Format {
    INT,
    FLOAT,
    UNKNOWN
  }

  /**
   * Determine whether the provided string represents a Python integer literal, a float literal, or
   * neither.
   *
   * <p>This performs a conservative syntactic classification (it does not fully parse the value).
   * It accepts an optional leading '+' or '-' and underscores inside numeric digits. The method
   * recognizes:
   *
   * <ul>
   *   <li>INT: decimal digits (with optional underscores) or base-prefixed integers (0b/0o/0x)
   *       without float-specific markers.
   *   <li>FLOAT: presence of a decimal point or an exponent ('e' or 'E')
   *   <li>UNKNOWN: anything that doesn't clearly match the INT or FLOAT patterns.
   * </ul>
   *
   * @param value the literal text to classify
   * @return NumberKind.INT if it looks like an integer literal, NumberKind.FLOAT if it looks like a
   *     float literal, otherwise NumberKind.UNKNOWN
   */
  public static Format getFormat(String value) {
    if (value == null) return Format.UNKNOWN;
    String s = value.trim();
    if (s.isEmpty()) return Format.UNKNOWN;

    // Strip leading sign.
    if (s.charAt(0) == '+' || s.charAt(0) == '-') {
      if (s.length() == 1) return Format.UNKNOWN;
      s = s.substring(1);
    }

    // Remove underscores for classification.
    String t = s.replace("_", "");
    if (t.isEmpty()) return Format.UNKNOWN;

    // Check for hex int.
    if (t.length() > 2 && (t.startsWith("0x") || t.startsWith("0X"))) {
      if (t.indexOf('.') >= 0) {
        return Format.UNKNOWN; // No hex floats.
      } else {
        return Format.INT;
      }
    }

    // Other explicit base-prefixed integers (0b, 0o)
    if (t.length() > 2
        && (t.startsWith("0b") || t.startsWith("0B") || t.startsWith("0o") || t.startsWith("0O"))) {
      return Format.INT;
    }

    // Decimal float if contains '.' or exponent 'e'/'E'
    if (t.indexOf('.') >= 0 || t.indexOf('e') >= 0 || t.indexOf('E') >= 0) {
      // Ensure there's at least one digit somewhere (conservative check).
      String digitCandidates = t.replace(".", "").replaceFirst("[eE].*$", "");
      if (digitCandidates.chars().anyMatch(Character::isDigit)) {
        return Format.FLOAT;
      } else {
        return Format.UNKNOWN;
      }
    }

    // Remaining: all digits => int
    if (t.chars().allMatch(Character::isDigit)) {
      return Format.INT;
    }

    return Format.UNKNOWN;
  }

  public static long parseAsLong(String value) {
    return parseAsLongWithBase(value, 0);
  }

  /**
   * Parse the given string as a long integer using the specified base.
   *
   * <p>The method mirrors Python's int(x, base) semantics for integer literal parsing:
   *
   * <ul>
   *   <li>Leading and trailing whitespace are ignored.
   *   <li>An optional leading '+' or '-' sign is accepted.
   *   <li>If base == 0 the base is autodetected from the string prefix: "0b"/"0B" => base 2,
   *       "0o"/"0O" => base 8, "0x"/"0X" => base 16, otherwise base 10.
   *   <li>If base is in the range [2,36] and the string contains a matching prefix ("0b", "0o",
   *       "0x"), the prefix is ignored and parsing proceeds using the provided base.
   * </ul>
   *
   * <p>Behavior:
   *
   * <ul>
   *   <li>Returns the parsed value as a signed long (sign applied).
   *   <li>Throws NumberFormatException if the input is empty (after trimming), consists only of a
   *       sign and/or prefix (e.g. "-", "+", "0x"), or contains invalid digits for the effective
   *       base.
   *   <li>Throws IllegalArgumentException if base is not 0 and not in the range 2..36.
   * </ul>
   *
   * @param value the string representation of the integer to parse
   * @param base the numeric base to use (0 means autodetect)
   * @return the parsed long value (with sign applied)
   * @throws NumberFormatException if the value cannot be parsed as an integer in the effective base
   * @throws IllegalArgumentException if base is not 0 and not between 2 and 36 (inclusive)
   */
  public static long parseAsLongWithBase(String value, int base) {
    // 1. Trim whitespace.
    String s = value.trim();
    if (s.isEmpty()) {
      throw new NumberFormatException(
          "Invalid literal for int() with base %d: '%s'".formatted(base, value));
    }

    // 2. Handle sign.
    int sign = 1;
    int index = 0; // The starting index of the number part
    char firstChar = s.charAt(0);

    if (firstChar == '-') {
      sign = -1;
      index = 1;
    } else if (firstChar == '+') {
      index = 1;
    }

    // Check if string is only a sign.
    if (index == s.length()) {
      throw new NumberFormatException(
          "Invalid literal for int() with base %d: '%s'".formatted(base, value));
    }

    String numberPart = s.substring(index);
    int effectiveBase = base;
    int numberIndex = 0; // The start of the digits within numberPart.

    // 3. Validate base and handle auto-detection (base == 0).
    if (base == 0) {
      if (numberPart.startsWith("0b") || numberPart.startsWith("0B")) {
        effectiveBase = 2;
        numberIndex = 2;
      } else if (numberPart.startsWith("0o") || numberPart.startsWith("0O")) {
        effectiveBase = 8;
        numberIndex = 2;
      } else if (numberPart.startsWith("0x") || numberPart.startsWith("0X")) {
        effectiveBase = 16;
        numberIndex = 2;
      } else {
        effectiveBase = 10;
      }
    } else if (base < 2 || base > 36) {
      throw new IllegalArgumentException("base must be >= 2 and <= 36, or 0");
    } else {
      // 4. Handle optional base prefixes.
      if ((base == 2) && (numberPart.startsWith("0b") || numberPart.startsWith("0B"))) {
        numberIndex = 2;
      } else if ((base == 8) && (numberPart.startsWith("0o") || numberPart.startsWith("0O"))) {
        numberIndex = 2;
      } else if ((base == 16) && (numberPart.startsWith("0x") || numberPart.startsWith("0X"))) {
        numberIndex = 2;
      }
    }

    String finalString = numberPart.replace("_", "").substring(numberIndex);
    if (finalString.isEmpty()) {
      // String was just a sign and/or a prefix (e.g., "0x", "-0b")
      throw new NumberFormatException(
          "Invalid literal for int() with base %d: '%s'".formatted(base, value));
    }

    // 5. Parse using Long.parseLong and apply sign.
    try {
      long result = Long.parseLong(finalString, effectiveBase);
      return result * sign;
    } catch (NumberFormatException e) {
      throw new NumberFormatException(
          "Invalid literal for int() with base %d: '%s'".formatted(effectiveBase, value));
    }
  }
}
