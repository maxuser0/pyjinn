package org.pyjinn.interpreter;

import java.util.function.Supplier;

public class MemoizingSupplier<T> implements Supplier<T> {

  private final Supplier<T> delegate;
  private volatile T value;
  private final Object lock = new Object();

  public static <T> MemoizingSupplier<T> of(Supplier<T> delegate) {
    return new MemoizingSupplier<>(delegate);
  }

  private MemoizingSupplier(Supplier<T> delegate) {
    this.delegate = delegate;
  }

  @Override
  public T get() {
    if (value == null) {
      synchronized (lock) {
        if (value == null) {
          value = delegate.get();
        }
      }
    }
    return value;
  }
}
