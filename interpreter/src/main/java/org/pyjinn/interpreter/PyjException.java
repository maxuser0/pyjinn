package org.pyjinn.interpreter;

import java.lang.reflect.InvocationTargetException;
import org.pyjinn.interpreter.Script.PyjObjects;

/**
 * RuntimeException subclass that allows arbitrary Exception types to be thrown without requiring
 * all eval/exec/invoke methods to declare that they throw Exception.
 */
public class PyjException extends RuntimeException {
  public final Object thrown;

  public PyjException(Object thrown) {
    super(PyjObjects.toString(thrown));
    this.thrown = thrown;
  }

  public static Object unwrap(Exception e) {
    Object result = e;
    if (result instanceof PyjException pe) {
      result = pe.thrown;
    }
    while (result instanceof InvocationTargetException ite) {
      result = ite.getTargetException();
    }
    return result;
  }
}
