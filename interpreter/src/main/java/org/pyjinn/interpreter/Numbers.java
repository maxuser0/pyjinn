// SPDX-FileCopyrightText: © 2025 Greg Christiana <maxuser@pyjinn.org>
// SPDX-License-Identifier: MIT

package org.pyjinn.interpreter;

public class Numbers {
  private Numbers() {}

  public static Number add(Number x, Number y) {
    if (x instanceof Double d) {
      return d + y.doubleValue();
    } else if (y instanceof Double d) {
      return x.doubleValue() + d;
    } else if (x instanceof Float f) {
      return f + y.floatValue();
    } else if (y instanceof Float f) {
      return x.floatValue() + f;
    } else if (x instanceof Long l) {
      return l + y.longValue();
    } else if (y instanceof Long l) {
      return x.longValue() + l;
    } else if (x instanceof Integer i) {
      return i + y.intValue();
    } else if (y instanceof Integer i) {
      return x.intValue() + i;
    } else if (x instanceof Short s) {
      return s + y.shortValue();
    } else if (y instanceof Short s) {
      return x.shortValue() + s;
    } else if (x instanceof Byte b) {
      return b + y.byteValue();
    } else if (y instanceof Byte b) {
      return x.byteValue() + b;
    } else {
      throw new IllegalArgumentException(
          String.format(
              "Unable to add numbers: %s + %s (%s + %s)",
              x, y, x.getClass().getName(), y.getClass().getName()));
    }
  }

  public static Number subtract(Number x, Number y) {
    if (x instanceof Double d) {
      return d - y.doubleValue();
    } else if (y instanceof Double d) {
      return x.doubleValue() - d;
    } else if (x instanceof Float f) {
      return f - y.floatValue();
    } else if (y instanceof Float f) {
      return x.floatValue() - f;
    } else if (x instanceof Long l) {
      return l - y.longValue();
    } else if (y instanceof Long l) {
      return x.longValue() - l;
    } else if (x instanceof Integer i) {
      return i - y.intValue();
    } else if (y instanceof Integer i) {
      return x.intValue() - i;
    } else if (x instanceof Short s) {
      return s - y.shortValue();
    } else if (y instanceof Short s) {
      return x.shortValue() - s;
    } else if (x instanceof Byte b) {
      return b - y.byteValue();
    } else if (y instanceof Byte b) {
      return x.byteValue() - b;
    } else {
      throw new IllegalArgumentException(
          String.format(
              "Unable to subtract numbers: %s - %s (%s - %s)",
              x, y, x.getClass().getName(), y.getClass().getName()));
    }
  }

  public static Number multiply(Number x, Number y) {
    if (x instanceof Double d) {
      return d * y.doubleValue();
    } else if (y instanceof Double d) {
      return x.doubleValue() * d;
    } else if (x instanceof Float f) {
      return f * y.floatValue();
    } else if (y instanceof Float f) {
      return x.floatValue() * f;
    } else if (x instanceof Long l) {
      return l * y.longValue();
    } else if (y instanceof Long l) {
      return x.longValue() * l;
    } else if (x instanceof Integer i) {
      return i * y.intValue();
    } else if (y instanceof Integer i) {
      return x.intValue() * i;
    } else if (x instanceof Short s) {
      return s * y.shortValue();
    } else if (y instanceof Short s) {
      return x.shortValue() * s;
    } else if (x instanceof Byte b) {
      return b * y.byteValue();
    } else if (y instanceof Byte b) {
      return x.byteValue() * b;
    } else {
      throw new IllegalArgumentException(
          String.format(
              "Unable to multiply numbers: %s * %s (%s * %s)",
              x, y, x.getClass().getName(), y.getClass().getName()));
    }
  }

  public static Number divide(Number x, Number y) {
    // Like in Python, always treat division as floating point.
    return x.doubleValue() / y.doubleValue();
  }

  public static Number floorDiv(Number x, Number y) {
    if (x instanceof Double d) {
      return Math.floor(d / y.doubleValue());
    } else if (y instanceof Double d) {
      return Math.floor(x.doubleValue() / d);
    } else if (x instanceof Float f) {
      return Math.floor(f / y.floatValue());
    } else if (y instanceof Float f) {
      return Math.floor(x.floatValue() / f);
    } else if (x instanceof Long l) {
      return Math.floorDiv(l, y.longValue());
    } else if (y instanceof Long l) {
      return Math.floorDiv(x.longValue(), l);
    } else if (x instanceof Integer i) {
      return Math.floorDiv(i, y.intValue());
    } else if (y instanceof Integer i) {
      return Math.floorDiv(x.intValue(), i);
    } else if (x instanceof Short s) {
      return Math.floorDiv(s, y.shortValue());
    } else if (y instanceof Short s) {
      return Math.floorDiv(x.shortValue(), s);
    } else if (x instanceof Byte b) {
      return Math.floorDiv(b, y.byteValue());
    } else if (y instanceof Byte b) {
      return Math.floorDiv(x.byteValue(), b);
    } else {
      throw new IllegalArgumentException(
          String.format(
              "Unable to do floor division of numbers: %s // %s (%s * %s)",
              x, y, x.getClass().getName(), y.getClass().getName()));
    }
  }

  private static int pyModInt(int x, int y) {
    return Math.floorMod(x, y);
  }

  private static long pyModLong(long x, long y) {
    return Math.floorMod(x, y);
  }

  private static float pyModFloat(float x, float y) {
    float remainder = x % y;
    if (remainder < 0) {
      remainder += y;
    }
    return remainder;
  }

  private static double pyModDouble(double x, double y) {
    double remainder = x % y;
    if (remainder < 0) {
      remainder += y;
    }
    return remainder;
  }

  public static Number javaMod(Number x, Number y) {
    if (x instanceof Double d) {
      return d % y.doubleValue();
    } else if (y instanceof Double d) {
      return x.doubleValue() % d;
    } else if (x instanceof Float f) {
      return f % y.floatValue();
    } else if (y instanceof Float f) {
      return x.floatValue() % f;
    } else if (x instanceof Long l) {
      return l % y.longValue();
    } else if (y instanceof Long l) {
      return x.longValue() % l;
    } else if (x instanceof Integer i) {
      return i % y.intValue();
    } else if (y instanceof Integer i) {
      return x.intValue() % i;
    } else if (x instanceof Short s) {
      return s % y.shortValue();
    } else if (y instanceof Short s) {
      return x.shortValue() % s;
    } else if (x instanceof Byte b) {
      return b % y.byteValue();
    } else if (y instanceof Byte b) {
      return x.byteValue() % b;
    } else {
      throw new IllegalArgumentException(
          String.format(
              "Unable to take modulus: %s % %s (%s % %s)",
              x, y, x.getClass().getName(), y.getClass().getName()));
    }
  }

  public static Number pyMod(Number x, Number y) {
    if (x instanceof Double d) {
      return pyModDouble(d, y.doubleValue());
    } else if (y instanceof Double d) {
      return pyModDouble(x.doubleValue(), d);
    } else if (x instanceof Float f) {
      return pyModFloat(f, y.floatValue());
    } else if (y instanceof Float f) {
      return pyModFloat(x.floatValue(), f);
    } else if (x instanceof Long l) {
      return pyModLong(l, y.longValue());
    } else if (y instanceof Long l) {
      return pyModLong(x.longValue(), l);
    } else if (x instanceof Integer i) {
      return pyModInt(i, y.intValue());
    } else if (y instanceof Integer i) {
      return pyModInt(x.intValue(), i);
    } else {
      throw new IllegalArgumentException(
          String.format(
              "Unable to take modulus: %s % %s (%s % %s)",
              x, y, x.getClass().getName(), y.getClass().getName()));
    }
  }

  public static Number negate(Number x) {
    if (x instanceof Double d) {
      return -d;
    } else if (x instanceof Float f) {
      return -f;
    } else if (x instanceof Long l) {
      return -l;
    } else if (x instanceof Integer i) {
      return -i;
    } else if (x instanceof Short s) {
      return -s;
    } else if (x instanceof Byte b) {
      return -b;
    } else {
      throw new IllegalArgumentException(
          String.format("Unable to negate number: %s (%s)", x, x.getClass().getName()));
    }
  }

  public static boolean equals(Number x, Number y) {
    if (x instanceof Double d) {
      return d == y.doubleValue();
    } else if (y instanceof Double d) {
      return x.doubleValue() == d;
    } else if (x instanceof Float f) {
      return f == y.floatValue();
    } else if (y instanceof Float f) {
      return x.floatValue() == f;
    } else if (x instanceof Long l) {
      return l == y.longValue();
    } else if (y instanceof Long l) {
      return x.longValue() == l;
    } else if (x instanceof Integer i) {
      return i == y.intValue();
    } else if (y instanceof Integer i) {
      return x.intValue() == i;
    } else if (x instanceof Short s) {
      return s == y.shortValue();
    } else if (y instanceof Short s) {
      return x.shortValue() == s;
    } else if (x instanceof Byte b) {
      return b == y.byteValue();
    } else if (y instanceof Byte b) {
      return x.byteValue() == b;
    } else {
      throw new IllegalArgumentException(
          String.format(
              "Unable to compare numbers: %s == %s (%s == %s)",
              x, y, x.getClass().getName(), y.getClass().getName()));
    }
  }

  public static int compare(Number x, Number y) {
    if (x instanceof Double d) {
      return Double.compare(d, y.doubleValue());
    } else if (y instanceof Double d) {
      return Double.compare(x.doubleValue(), d);
    } else if (x instanceof Float f) {
      return Float.compare(f, y.floatValue());
    } else if (y instanceof Float f) {
      return Float.compare(x.floatValue(), f);
    } else if (x instanceof Long l) {
      return Long.compare(l, y.longValue());
    } else if (y instanceof Long l) {
      return Long.compare(x.longValue(), l);
    } else if (x instanceof Integer i) {
      return Integer.compare(i, y.intValue());
    } else if (y instanceof Integer i) {
      return Integer.compare(x.intValue(), i);
    } else if (x instanceof Short s) {
      return Short.compare(s, y.shortValue());
    } else if (y instanceof Short s) {
      return Short.compare(x.shortValue(), s);
    } else if (x instanceof Byte b) {
      return Byte.compare(b, y.byteValue());
    } else if (y instanceof Byte b) {
      return Byte.compare(x.byteValue(), b);
    } else {
      throw new IllegalArgumentException(
          String.format(
              "Unable to compare numbers %s vs %s (%s vs %s)",
              x, y, x.getClass().getName(), y.getClass().getName()));
    }
  }
}
