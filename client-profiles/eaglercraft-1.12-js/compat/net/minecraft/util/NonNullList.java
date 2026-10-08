package net.minecraft.util;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public class NonNullList<E> extends AbstractList<E> {
  private final List<E> values;
  private final E defaultValue;

  public static <E> NonNullList<E> func_191196_a() {
    return new NonNullList<>(new ArrayList<>(), null);
  }

  public static <E> NonNullList<E> func_191197_a(int size, E defaultValue) {
    Objects.requireNonNull(defaultValue);
    if (size < 0) {
      throw new NegativeArraySizeException(Integer.toString(size));
    }
    Object[] values = new Object[size];
    Arrays.fill(values, defaultValue);
    @SuppressWarnings("unchecked")
    List<E> fixedSizeValues = (List<E>) (List<?>) Arrays.asList(values);
    return new NonNullList<>(fixedSizeValues, defaultValue);
  }

  @SafeVarargs
  public static <E> NonNullList<E> func_193580_a(E defaultValue, E... values) {
    return new NonNullList<>(Arrays.asList(values), defaultValue);
  }

  protected NonNullList() {
    this(new ArrayList<>(), null);
  }

  protected NonNullList(List<E> values, E defaultValue) {
    this.values = values;
    this.defaultValue = defaultValue;
  }

  @Override
  public E get(int index) {
    return values.get(index);
  }

  @Override
  public E set(int index, E element) {
    return values.set(index, Objects.requireNonNull(element));
  }

  @Override
  public void add(int index, E element) {
    values.add(index, Objects.requireNonNull(element));
  }

  @Override
  public E remove(int index) {
    return values.remove(index);
  }

  @Override
  public int size() {
    return values.size();
  }

  @Override
  public void clear() {
    if (defaultValue == null) {
      super.clear();
      return;
    }
    for (int index = 0; index < size(); index++) {
      set(index, defaultValue);
    }
  }
}
