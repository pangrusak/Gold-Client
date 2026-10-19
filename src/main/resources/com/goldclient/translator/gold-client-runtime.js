// Small compatibility helpers used by generated JavaScript.
// This is not a replacement for Minecraft/Forge or a full JVM runtime.

// Translated classes do not use JavaScript `extends`, and JDK exception
// classes are not part of a mod bundle at all. Record the Java hierarchy here
// so instanceof/checkcast/catch dispatch can still follow Java semantics.
const GOLD_JAVA_SUPERCLASSES = new Map([
  ["java.lang.Throwable", "java.lang.Object"],
  ["java.lang.Error", "java.lang.Throwable"],
  ["java.lang.AssertionError", "java.lang.Error"],
  ["java.lang.LinkageError", "java.lang.Error"],
  ["java.lang.ClassFormatError", "java.lang.LinkageError"],
  ["java.lang.NoClassDefFoundError", "java.lang.LinkageError"],
  ["java.lang.UnsatisfiedLinkError", "java.lang.LinkageError"],
  ["java.lang.ExceptionInInitializerError", "java.lang.LinkageError"],
  ["java.lang.AbstractMethodError", "java.lang.LinkageError"],
  ["java.lang.NoSuchFieldError", "java.lang.LinkageError"],
  ["java.lang.NoSuchMethodError", "java.lang.LinkageError"],
  ["java.lang.IncompatibleClassChangeError", "java.lang.LinkageError"],
  ["java.lang.InstantiationError", "java.lang.IncompatibleClassChangeError"],
  ["java.lang.IllegalAccessError", "java.lang.IncompatibleClassChangeError"],
  ["java.lang.VirtualMachineError", "java.lang.Error"],
  ["java.lang.StackOverflowError", "java.lang.VirtualMachineError"],
  ["java.lang.OutOfMemoryError", "java.lang.VirtualMachineError"],
  ["java.lang.InternalError", "java.lang.VirtualMachineError"],
  ["java.lang.UnknownError", "java.lang.VirtualMachineError"],
  ["java.lang.Exception", "java.lang.Throwable"],
  ["java.lang.RuntimeException", "java.lang.Exception"],
  ["java.lang.ArithmeticException", "java.lang.RuntimeException"],
  ["java.lang.ArrayStoreException", "java.lang.RuntimeException"],
  ["java.lang.ClassCastException", "java.lang.RuntimeException"],
  ["java.lang.EnumConstantNotPresentException", "java.lang.RuntimeException"],
  ["java.lang.IllegalArgumentException", "java.lang.RuntimeException"],
  ["java.lang.IllegalStateException", "java.lang.RuntimeException"],
  ["java.lang.IllegalMonitorStateException", "java.lang.RuntimeException"],
  ["java.lang.IndexOutOfBoundsException", "java.lang.RuntimeException"],
  ["java.lang.ArrayIndexOutOfBoundsException", "java.lang.IndexOutOfBoundsException"],
  ["java.lang.StringIndexOutOfBoundsException", "java.lang.IndexOutOfBoundsException"],
  ["java.lang.NegativeArraySizeException", "java.lang.RuntimeException"],
  ["java.lang.NullPointerException", "java.lang.RuntimeException"],
  ["java.lang.NumberFormatException", "java.lang.IllegalArgumentException"],
  ["java.lang.SecurityException", "java.lang.RuntimeException"],
  ["java.lang.TypeNotPresentException", "java.lang.RuntimeException"],
  ["java.lang.UnsupportedOperationException", "java.lang.RuntimeException"],
  ["java.util.NoSuchElementException", "java.lang.RuntimeException"],
  ["java.util.ConcurrentModificationException", "java.lang.RuntimeException"],
  ["java.util.InputMismatchException", "java.util.NoSuchElementException"],
  ["java.util.EmptyStackException", "java.lang.RuntimeException"],
  ["java.util.MissingResourceException", "java.lang.RuntimeException"],
  ["java.util.IllformedLocaleException", "java.lang.IllegalArgumentException"],
  ["java.lang.ReflectiveOperationException", "java.lang.Exception"],
  ["java.lang.ClassNotFoundException", "java.lang.ReflectiveOperationException"],
  ["java.lang.IllegalAccessException", "java.lang.ReflectiveOperationException"],
  ["java.lang.InstantiationException", "java.lang.ReflectiveOperationException"],
  ["java.lang.NoSuchFieldException", "java.lang.ReflectiveOperationException"],
  ["java.lang.NoSuchMethodException", "java.lang.ReflectiveOperationException"],
  ["java.lang.InterruptedException", "java.lang.Exception"],
  ["java.lang.CloneNotSupportedException", "java.lang.Exception"],
  ["java.io.IOException", "java.lang.Exception"],
  ["java.io.FileNotFoundException", "java.io.IOException"],
  ["java.io.EOFException", "java.io.IOException"],
  ["java.io.UncheckedIOException", "java.lang.RuntimeException"],
  ["java.io.UnsupportedEncodingException", "java.io.IOException"],
  ["java.net.URISyntaxException", "java.lang.Exception"],
  ["java.net.MalformedURLException", "java.io.IOException"],
  ["java.net.SocketException", "java.io.IOException"],
  ["java.net.UnknownHostException", "java.io.IOException"],
  ["java.sql.SQLException", "java.lang.Exception"],
  ["java.text.ParseException", "java.lang.Exception"],
  ["java.security.GeneralSecurityException", "java.lang.Exception"],
  ["java.util.concurrent.ExecutionException", "java.lang.Exception"],
  ["java.util.concurrent.TimeoutException", "java.lang.Exception"],
  ["java.util.concurrent.CancellationException", "java.lang.IllegalStateException"],
  ["java.util.concurrent.CompletionException", "java.lang.RuntimeException"],
  ["javax.crypto.BadPaddingException", "java.security.GeneralSecurityException"],
  ["javax.crypto.IllegalBlockSizeException", "java.security.GeneralSecurityException"],
  ["javax.crypto.NoSuchPaddingException", "java.security.GeneralSecurityException"],
  ["java.util.zip.DataFormatException", "java.lang.Exception"],
  ["java.util.zip.ZipException", "java.io.IOException"]
]);

const GOLD_JAVA_SIMPLE_NAMES = new Map();
for (const name of GOLD_JAVA_SUPERCLASSES.keys()) {
  const simple = name.slice(name.lastIndexOf(".") + 1);
  if (!GOLD_JAVA_SIMPLE_NAMES.has(simple)) GOLD_JAVA_SIMPLE_NAMES.set(simple, name);
}

// Throwable objects need identity, type, message, cause and suppression
// behavior. A plain JavaScript Error cannot carry a Java class name, so this
// subclass records it explicitly.
class GoldClientJavaThrowable extends Error {
  constructor(className, ...args) {
    const message = args[0] === null || args[0] === undefined ? "" : String(args[0]);
    super(message);
    this.name = className;
    this.javaClassName = className;
    this.javaThrowable = true;
    this.cause = args.length > 1 ? args[1] : null;
    this.suppressedExceptions = [];
    this.javaStackTrace = [];
  }
  getMessage() { return this.message; }
  getLocalizedMessage() { return this.message; }
  getCause() { return this.cause ?? null; }
  initCause(cause) {
    if (this.cause != null) throw new GoldClientJavaThrowable("java.lang.IllegalStateException", "Can't overwrite cause");
    this.cause = cause;
    return this;
  }
  addSuppressed(exception) {
    if (exception === this) {
      throw new GoldClientJavaThrowable("java.lang.IllegalArgumentException", "Self-suppression not permitted");
    }
    this.suppressedExceptions.push(exception);
  }
  getSuppressed() { return this.suppressedExceptions.slice(); }
  getStackTrace() { return this.javaStackTrace.slice(); }
  setStackTrace(stackTrace) { this.javaStackTrace = Array.from(stackTrace ?? []); }
  fillInStackTrace() { return this; }
  printStackTrace() { if (typeof console !== "undefined") console.error(this.toString()); }
  toString() { return this.message ? this.name + ": " + this.message : this.name; }
}

function goldClientJavaClassName(value) {
  if (value === null || value === undefined) return null;
  if (typeof value.javaClassName === "string") return value.javaClassName;
  if (typeof value.__goldClassName === "string") return value.__goldClassName;
  const constructorName = value.constructor?.__goldClassName;
  if (typeof constructorName === "string") return constructorName;
  if (value instanceof Error) {
    const message = String(value.message ?? "");
    const match = /^([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)(?::|$)/.exec(message);
    if (match) {
      const resolved = GOLD_JAVA_SIMPLE_NAMES.get(match[1]) ?? match[1];
      if (GOLD_JAVA_SUPERCLASSES.has(resolved)) return resolved;
    }
    return "java.lang.Throwable";
  }
  return null;
}

class GoldClientJavaList {
  constructor(initial = []) {
    this.items = Array.isArray(initial) ? initial.slice() : [];
  }
  add(...args) { if (args.length > 1) { this.addAt(args[0], args[1]); return; } this.items.push(args[0]); return true; }
  offer(value) { this.items.push(value); return true; }
  push(value) { this.items.push(value); return; }
  pop() { if (!this.items.length) throw new Error("NoSuchElementException"); return this.items.pop(); }
  poll() { return this.items.length ? this.items.shift() : null; }
  peek() { return this.items.length ? this.items[0] : null; }
  element() { if (!this.items.length) throw new Error("NoSuchElementException"); return this.items[0]; }
  addFirst(value) { this.items.unshift(value); }
  addLast(value) { this.items.push(value); }
  offerFirst(value) { this.items.unshift(value); return true; }
  offerLast(value) { this.items.push(value); return true; }
  pollFirst() { return this.poll(); }
  pollLast() { return this.items.length ? this.items.pop() : null; }
  peekFirst() { return this.peek(); }
  peekLast() { return this.items.length ? this.items[this.items.length - 1] : null; }
  getFirst() { return this.element(); }
  getLast() { if (!this.items.length) throw new Error("NoSuchElementException"); return this.items[this.items.length - 1]; }
  removeFirst() { return this.removeAt(0); }
  removeLast() { return this.removeAt(this.items.length - 1); }
  addAt(index, value) { this.#checkPosition(index); this.items.splice(index, 0, value); return; }
  addAll(values) {
    const list = values?.items ?? values;
    const copy = Array.from(list ?? []);
    this.items.push(...copy);
    return copy.length > 0;
  }
  addAllAt(index, values) {
    this.#checkPosition(index);
    const copy = Array.from(values?.items ?? values ?? []);
    this.items.splice(index, 0, ...copy);
    return copy.length > 0;
  }
  get(index) { this.#checkIndex(index); return this.items[index]; }
  set(index, value) { this.#checkIndex(index); const old = this.items[index]; this.items[index] = value; return old; }
  size() { return this.items.length; }
  isEmpty() { return this.items.length === 0; }
  contains(value) { return this.items.some(item => GoldClientRuntime.javaEquals(item, value)); }
  containsAll(values) { return Array.from(values?.items ?? values ?? []).every(value => this.contains(value)); }
  indexOf(value) { return this.items.findIndex(item => GoldClientRuntime.javaEquals(item, value)); }
  lastIndexOf(value) {
    for (let i = this.items.length - 1; i >= 0; i--) {
      if (GoldClientRuntime.javaEquals(this.items[i], value)) return i;
    }
    return -1;
  }
  clear() { this.items.length = 0; }
  remove(value) {
    if (Number.isInteger(value)) return this.removeAt(value);
    return this.removeObject(value);
  }
  removeObject(value) {
    const index = this.indexOf(value);
    if (index < 0) return false;
    this.items.splice(index, 1);
    return true;
  }
  removeAt(index) { this.#checkIndex(index); return this.items.splice(index, 1)[0]; }
  removeAll(values) {
    const candidates = Array.from(values?.items ?? values ?? []);
    const before = this.items.length;
    this.items = this.items.filter(item => !candidates.some(value => GoldClientRuntime.javaEquals(item, value)));
    return before !== this.items.length;
  }
  retainAll(values) {
    const candidates = Array.from(values?.items ?? values ?? []);
    const before = this.items.length;
    this.items = this.items.filter(item => candidates.some(value => GoldClientRuntime.javaEquals(item, value)));
    return before !== this.items.length;
  }
  replaceAll(callback) { this.items = this.items.map((value, index) => callback(value, index)); }
  removeIf(predicate) {
    const before = this.items.length;
    this.items = this.items.filter((value, index) => !predicate(value, index));
    return before !== this.items.length;
  }
  subList(from, to) {
    if (from < 0 || to > this.items.length || from > to) throw new RangeError("IndexOutOfBoundsException");
    return new GoldClientJavaList(this.items.slice(from, to));
  }
  toArray() { return this.items.slice(); }
  sort(comparator) { this.items.sort((a, b) => GoldClientRuntime.compareJava(a, b, comparator ?? null)); }
  replace(index, value) { return this.set(index, value); }
  iterator() { return this.items[Symbol.iterator](); }
  listIterator(index = 0) {
    let cursor = index;
    const owner = this;
    return {
      hasNext: () => cursor < owner.items.length,
      next: () => { if (cursor >= owner.items.length) throw new Error("NoSuchElementException"); return owner.items[cursor++]; },
      hasPrevious: () => cursor > 0,
      previous: () => { if (cursor <= 0) throw new Error("NoSuchElementException"); return owner.items[--cursor]; },
      nextIndex: () => cursor,
      previousIndex: () => cursor - 1,
      remove: () => { if (cursor <= 0) throw new Error("IllegalStateException"); owner.items.splice(--cursor, 1); },
      set: value => { if (cursor <= 0) throw new Error("IllegalStateException"); owner.items[cursor - 1] = value; },
      add: value => owner.items.splice(cursor++, 0, value)
    };
  }
  forEach(callback) { this.items.forEach(callback); }
  stream() { return new GoldClientJavaStream(this.items); }
  toString() { return "[" + this.items.map(value => value == null ? "null" : String(value)).join(", ") + "]"; }
  #checkIndex(index) {
    if (!Number.isInteger(index) || index < 0 || index >= this.items.length) throw new RangeError("IndexOutOfBoundsException: " + index);
  }
  #checkPosition(index) {
    if (!Number.isInteger(index) || index < 0 || index > this.items.length) throw new RangeError("IndexOutOfBoundsException: " + index);
  }
  [Symbol.iterator]() { return this.items[Symbol.iterator](); }
}
class GoldClientJavaSet {
  constructor(initial = []) {
    this.items = new Set();
    for (const value of initial?.items ?? initial ?? []) this.add(value);
  }
  add(value) {
    if (Array.from(this.items).some(item => GoldClientRuntime.javaEquals(item, value))) return false;
    this.items.add(value);
    return true;
  }
  addAll(values) { let changed = false; for (const value of values?.items ?? values ?? []) changed = this.add(value) || changed; return changed; }
  contains(value) { return Array.from(this.items).some(item => GoldClientRuntime.javaEquals(item, value)); }
  containsAll(values) { return Array.from(values?.items ?? values ?? []).every(value => this.contains(value)); }
  size() { return this.items.size; }
  isEmpty() { return this.items.size === 0; }
  remove(value) {
    for (const item of this.items) {
      if (GoldClientRuntime.javaEquals(item, value)) return this.items.delete(item);
    }
    return false;
  }
  removeAll(values) { let changed = false; for (const value of values?.items ?? values ?? []) changed = this.remove(value) || changed; return changed; }
  retainAll(values) {
    const keep = Array.from(values?.items ?? values ?? []);
    let changed = false;
    for (const value of this.items) {
      if (!keep.some(candidate => GoldClientRuntime.javaEquals(value, candidate))) {
        this.items.delete(value);
        changed = true;
      }
    }
    return changed;
  }
  clear() { this.items.clear(); }
  toArray() { return Array.from(this.items); }
  forEach(callback) { this.items.forEach(callback); }
  iterator() { return this.items[Symbol.iterator](); }
  stream() { return new GoldClientJavaStream(this.items); }
  [Symbol.iterator]() { return this.items[Symbol.iterator](); }
}
class GoldClientJavaMap {
  constructor(initial = []) {
    const entries = initial?.items instanceof Map ? initial.items : (initial instanceof Map ? initial : (initial?.items ?? initial ?? []));
    this.items = new Map();
    for (const [key, value] of entries) this.put(key, value);
  }
  #lookup(key) {
    for (const storedKey of this.items.keys()) {
      if (GoldClientRuntime.javaEquals(storedKey, key)) return { found: true, key: storedKey };
    }
    return { found: false, key: undefined };
  }
  put(key, value) {
    const match = this.#lookup(key);
    const storedKey = match.found ? match.key : key;
    const old = match.found ? this.items.get(storedKey) : null;
    this.items.set(storedKey, value);
    return old;
  }
  get(key) { const match = this.#lookup(key); return match.found ? this.items.get(match.key) : null; }
  getOrDefault(key, fallback) { const match = this.#lookup(key); return match.found ? this.items.get(match.key) : fallback; }
  containsKey(key) { return this.#lookup(key).found; }
  containsValue(value) { return Array.from(this.items.values()).some(item => GoldClientRuntime.javaEquals(item, value)); }
  size() { return this.items.size; }
  isEmpty() { return this.items.size === 0; }
  remove(key) {
    const match = this.#lookup(key);
    if (!match.found) return null;
    const old = this.items.get(match.key);
    this.items.delete(match.key);
    return old;
  }
  removePair(key, value) {
    const match = this.#lookup(key);
    if (!match.found || !GoldClientRuntime.javaEquals(this.items.get(match.key), value)) return false;
    this.items.delete(match.key);
    return true;
  }
  replace(key, value) {
    const match = this.#lookup(key);
    if (!match.found) return null;
    const old = this.items.get(match.key);
    this.items.set(match.key, value);
    return old;
  }
  replacePair(key, oldValue, newValue) {
    const match = this.#lookup(key);
    if (!match.found || !GoldClientRuntime.javaEquals(this.items.get(match.key), oldValue)) return false;
    this.items.set(match.key, newValue);
    return true;
  }
  putIfAbsent(key, value) {
    const match = this.#lookup(key);
    if (!match.found || this.items.get(match.key) == null) this.put(key, value);
    return this.get(key);
  }
  computeIfAbsent(key, fn) {
    let value = this.get(key);
    if (value == null) { value = fn(key); if (value != null) this.put(key, value); }
    return value ?? null;
  }
  computeIfPresent(key, fn) {
    const match = this.#lookup(key);
    if (!match.found || this.items.get(match.key) == null) return null;
    const value = fn(key, this.items.get(match.key));
    if (value == null) this.items.delete(match.key); else this.items.set(match.key, value);
    return value ?? null;
  }
  compute(key, fn) {
    const match = this.#lookup(key);
    const value = fn(key, match.found ? this.items.get(match.key) : null);
    if (value == null) { if (match.found) this.items.delete(match.key); }
    else this.put(key, value);
    return value ?? null;
  }
  merge(key, value, fn) {
    const match = this.#lookup(key);
    const old = match.found ? this.items.get(match.key) : null;
    const next = old == null ? value : fn(old, value);
    if (next == null) { if (match.found) this.items.delete(match.key); }
    else this.put(key, next);
    return next ?? null;
  }
  clear() { this.items.clear(); }
  putAll(other) { for (const [key, value] of (other?.items ?? other)) this.put(key, value); }
  keySet() { return new GoldClientJavaSet(this.items.keys()); }
  values() { return new GoldClientJavaList(Array.from(this.items.values())); }
  entrySet() { return new GoldClientJavaSet(Array.from(this.items.entries()).map(([key, value]) => ({ key, value, getKey() { return this.key; }, getValue() { return this.value; }, setValue(next) { const old = this.value; this.value = next; return old; } }))); }
  forEach(callback) { this.items.forEach((value, key) => callback(key, value)); }
  toObject() { return Object.fromEntries(this.items); }
  [Symbol.iterator]() { return this.items[Symbol.iterator](); }
}
class GoldClientJavaUuid {
  constructor(value) {
    this.value = String(value).toLowerCase();
    if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(this.value)
        && !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(this.value)) {
      throw new TypeError("IllegalArgumentException: Invalid UUID string");
    }
  }
  toString() { return this.value; }
  equals(other) { return other instanceof GoldClientJavaUuid && this.value === other.value; }
  compareTo(other) { return this.value.localeCompare(String(other)); }
  getMostSignificantBits() { return BigInt("0x" + this.value.replace(/-/g, "").slice(0, 16)); }
  getLeastSignificantBits() { return BigInt("0x" + this.value.replace(/-/g, "").slice(16)); }
  static randomUUID() {
    const bytes = Array.from({ length: 16 }, () => Math.floor(Math.random() * 256));
    bytes[6] = (bytes[6] & 0x0f) | 0x40;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    const hex = bytes.map(value => value.toString(16).padStart(2, "0")).join("");
    return new GoldClientJavaUuid(hex.slice(0, 8) + "-" + hex.slice(8, 12) + "-" + hex.slice(12, 16) + "-" + hex.slice(16, 20) + "-" + hex.slice(20));
  }
  static fromString(value) { return new GoldClientJavaUuid(value); }
}

class GoldClientJavaBitSet {
  constructor(initial = []) {
    this.bits = new Set();
    for (const bit of initial?.bits ?? initial ?? []) if (Number.isInteger(bit) && bit >= 0) this.bits.add(bit);
  }
  set(from, to, value = true) {
    if (typeof to === "boolean") { value = to; to = from + 1; }
    if (to === undefined) to = from + 1;
    if (from < 0 || to < from) throw new RangeError("IndexOutOfBoundsException");
    for (let bit = from; bit < to; bit++) value ? this.bits.add(bit) : this.bits.delete(bit);
  }
  get(from, to) {
    if (to === undefined) return this.bits.has(from);
    const result = new GoldClientJavaBitSet();
    for (const bit of this.bits) if (bit >= from && bit < to) result.bits.add(bit - from);
    return result;
  }
  clear(from, to) {
    if (from === undefined) { this.bits.clear(); return; }
    if (to === undefined) { this.bits.delete(from); return; }
    for (let bit = from; bit < to; bit++) this.bits.delete(bit);
  }
  flip(from, to) {
    if (to === undefined) to = from + 1;
    if (from < 0 || to < from) throw new RangeError("IndexOutOfBoundsException");
    for (let bit = from; bit < to; bit++) this.bits.has(bit) ? this.bits.delete(bit) : this.bits.add(bit);
  }
  nextSetBit(from) { return Array.from(this.bits).filter(bit => bit >= from).sort((a, b) => a - b)[0] ?? -1; }
  nextClearBit(from) { let bit = from; while (this.bits.has(bit)) bit++; return bit; }
  previousSetBit(from) { return Array.from(this.bits).filter(bit => bit <= from).sort((a, b) => b - a)[0] ?? -1; }
  cardinality() { return this.bits.size; }
  length() { return this.bits.size ? Math.max(...this.bits) + 1 : 0; }
  isEmpty() { return this.bits.size === 0; }
  intersects(other) { return Array.from(this.bits).some(bit => other.bits.has(bit)); }
  and(other) { for (const bit of this.bits) if (!other.bits.has(bit)) this.bits.delete(bit); }
  or(other) { for (const bit of other.bits) this.bits.add(bit); }
  xor(other) { for (const bit of other.bits) this.bits.has(bit) ? this.bits.delete(bit) : this.bits.add(bit); }
  andNot(other) { for (const bit of other.bits) this.bits.delete(bit); }
  clone() { return new GoldClientJavaBitSet(this.bits); }
  toArray() { return Array.from(this.bits).sort((a, b) => a - b); }
  toString() { return "{" + this.toArray().join(", ") + "}"; }
  static valueOf(values) {
    const result = new GoldClientJavaBitSet();
    Array.from(values ?? []).forEach((value, index) => { if (value) result.set(index); });
    return result;
  }
}

class GoldClientJavaLocale {
  constructor(language = "", country = "", variant = "") {
    this.language = String(language).toLowerCase();
    this.country = String(country).toUpperCase();
    this.variant = String(variant);
  }
  getLanguage() { return this.language; }
  getCountry() { return this.country; }
  getVariant() { return this.variant; }
  getDisplayLanguage() { return this.language; }
  getDisplayCountry() { return this.country; }
  toLanguageTag() { return [this.language, this.country].filter(Boolean).join("-") || "und"; }
  toString() { return [this.language, this.country, this.variant].filter(Boolean).join(this.variant ? "_" : "_"); }
  equals(other) { return other instanceof GoldClientJavaLocale && this.toLanguageTag() === other.toLanguageTag() && this.variant === other.variant; }
  static forLanguageTag(tag) {
    const parts = String(tag).replace(/_/g, "-").split("-");
    return new GoldClientJavaLocale(parts[0] === "und" ? "" : parts[0], parts[1] ?? "", parts.slice(2).join("_"));
  }
  static getDefault() { return GoldClientJavaLocale.defaultLocale ?? new GoldClientJavaLocale("en", "US"); }
  static setDefault(locale) { GoldClientJavaLocale.defaultLocale = locale; }
}

class GoldClientJavaProperties extends GoldClientJavaMap {
  setProperty(key, value) { const old = this.put(String(key), String(value)); return old; }
  getProperty(key, fallback = null) {
    const value = this.get(String(key));
    return value == null ? fallback : String(value);
  }
  propertyNames() { return this.keySet().iterator(); }
  stringPropertyNames() { return new GoldClientJavaSet(Array.from(this.items.keys()).filter(key => typeof key === "string")); }
  list(output = console) {
    for (const [key, value] of this.items) output?.println?.(key + "=" + value);
  }
  store(output, comment = "") {
    const lines = [];
    if (comment) lines.push("# " + comment);
    lines.push("# " + new Date().toISOString());
    for (const [key, value] of this.items) lines.push(String(key) + "=" + String(value));
    const text = lines.join("\n") + "\n";
    if (typeof output === "function") output(text);
    else if (output && typeof output.write === "function") output.write(text);
    return text;
  }
}

class GoldClientJavaTimeUnit {
  static factors = Object.freeze({
    NANOSECONDS: 1e-9, MICROSECONDS: 1e-6, MILLISECONDS: 1e-3,
    SECONDS: 1, MINUTES: 60, HOURS: 3600, DAYS: 86400
  });
  constructor(name) {
    if (!Object.prototype.hasOwnProperty.call(GoldClientJavaTimeUnit.factors, name)) throw new TypeError("IllegalArgumentException: unknown TimeUnit " + name);
    this.name = name;
    this.factor = GoldClientJavaTimeUnit.factors[name];
  }
  convert(duration, sourceUnit) {
    const seconds = Number(duration) * sourceUnit.factor;
    const result = seconds / this.factor;
    if (this.name === "NANOSECONDS" || this.name === "MICROSECONDS" || this.name === "MILLISECONDS") return Math.trunc(result);
    return Math.trunc(result);
  }
  toNanos(duration) { return Math.trunc(Number(duration) * this.factor / 1e-9); }
  toMicros(duration) { return Math.trunc(Number(duration) * this.factor / 1e-6); }
  toMillis(duration) { return Math.trunc(Number(duration) * this.factor / 1e-3); }
  toSeconds(duration) { return Math.trunc(Number(duration) * this.factor); }
  toMinutes(duration) { return Math.trunc(Number(duration) * this.factor / 60); }
  toHours(duration) { return Math.trunc(Number(duration) * this.factor / 3600); }
  toDays(duration) { return Math.trunc(Number(duration) * this.factor / 86400); }
  toString() { return this.name; }
  static named(name) { return new GoldClientJavaTimeUnit(String(name)); }
}

// java.util.Random's specified 48-bit LCG. Keeping this algorithm exact matters
// for deterministic world generation and mods that share seeded random streams.
class GoldClientJavaRandom {
  static MULTIPLIER = 0x5DEECE66Dn;
  static ADDEND = 0xBn;
  static MASK = (1n << 48n) - 1n;
  constructor(seed = Date.now()) { this.setSeed(seed); }
  setSeed(seed) {
    const numericSeed = typeof seed === "bigint" ? seed : BigInt(Math.trunc(Number(seed)));
    this.seed = (numericSeed ^ GoldClientJavaRandom.MULTIPLIER) & GoldClientJavaRandom.MASK;
    this.haveGaussian = false;
    this.nextGaussianValue = 0;
  }
  next(bits = 32) {
    const count = Math.max(1, Math.min(32, Math.trunc(bits)));
    this.seed = (this.seed * GoldClientJavaRandom.MULTIPLIER + GoldClientJavaRandom.ADDEND)
      & GoldClientJavaRandom.MASK;
    return Number(this.seed >> BigInt(48 - count));
  }
  nextInt(bound) {
    if (bound === undefined) return this.next(32) | 0;
    if (!Number.isInteger(bound) || bound <= 0 || bound > 0x7fffffff) {
      throw new RangeError("IllegalArgumentException: bound must be positive");
    }
    if ((bound & -bound) === bound) {
      return Math.floor(bound * this.next(31) / 2147483648);
    }
    let bits, value;
    do {
      bits = this.next(31);
      value = bits % bound;
    } while (bits - value + (bound - 1) >= 2147483648);
    return value;
  }
  nextLong() {
    const high = BigInt(this.next(32) | 0);
    const low = BigInt(this.next(32) | 0);
    return BigInt.asIntN(64, (high << 32n) + low);
  }
  nextBoolean() { return this.next(1) !== 0; }
  nextFloat() { return this.next(24) / 16777216; }
  nextDouble() {
    return ((this.next(26) * 134217728) + this.next(27)) / 9007199254740992;
  }
  nextBytes(bytes) {
    for (let i = 0; i < bytes.length;) {
      let random = this.nextInt();
      for (let n = Math.min(bytes.length - i, 4); n > 0; n--) {
        bytes[i++] = (random << 24) >> 24;
        random >>= 8;
      }
    }
    return bytes;
  }
  nextGaussian() {
    if (this.haveGaussian) {
      this.haveGaussian = false;
      return this.nextGaussianValue;
    }
    let u, v, s;
    do {
      u = 2 * this.nextDouble() - 1;
      v = 2 * this.nextDouble() - 1;
      s = u * u + v * v;
    } while (s >= 1 || s === 0);
    const multiplier = Math.sqrt(-2 * Math.log(s) / s);
    this.nextGaussianValue = v * multiplier;
    this.haveGaussian = true;
    return u * multiplier;
  }
}
class GoldClientJavaAtomic {
  constructor(value = 0) { this.value = value; }
  get() { return this.value; }
  set(value) { this.value = value; }
  lazySet(value) { this.value = value; }
  getAndSet(value) { const old = this.value; this.value = value; return old; }
  compareAndSet(expected, update) { if (!Object.is(this.value, expected)) return false; this.value = update; return true; }
  weakCompareAndSet(expected, update) { return this.compareAndSet(expected, update); }
  getAndIncrement() { const old = this.value; this.value++; return old; }
  incrementAndGet() { return ++this.value; }
  getAndDecrement() { const old = this.value; this.value--; return old; }
  decrementAndGet() { return --this.value; }
  getAndAdd(delta) { const old = this.value; this.value += delta; return old; }
  addAndGet(delta) { this.value += delta; return this.value; }
  getAndUpdate(update) { const old = this.value; this.value = update(old); return old; }
  updateAndGet(update) { this.value = update(this.value); return this.value; }
  getAndAccumulate(value, fn) { const old = this.value; this.value = fn(old, value); return old; }
  accumulateAndGet(value, fn) { this.value = fn(this.value, value); return this.value; }
  intValue() { return Number(this.value) | 0; }
  longValue() { return this.value; }
  booleanValue() { return Boolean(this.value); }
  toString() { return String(this.value); }
}

class GoldClientJavaPriorityQueue extends GoldClientJavaList {
  constructor(initial = [], comparator = null) {
    super(initial?.items ?? initial ?? []);
    this.comparator = comparator ?? ((a, b) => a < b ? -1 : a > b ? 1 : 0);
    this.#sort();
  }
  #sort() { this.items.sort(this.comparator); }
  add(value) { this.items.push(value); this.#sort(); return true; }
  offer(value) { return this.add(value); }
  addAll(values) { const added = super.addAll(values); this.#sort(); return added; }
  poll() { return this.items.length ? this.items.shift() : null; }
  peek() { return this.items.length ? this.items[0] : null; }
  remove(value) { const removed = super.remove(value); this.#sort(); return removed; }
  clear() { super.clear(); }
  iterator() { return this.items.slice()[Symbol.iterator](); }
}

class GoldClientJavaDate {
  constructor(value = Date.now()) { this.date = value instanceof Date ? new Date(value.getTime()) : new Date(value); }
  getTime() { return this.date.getTime(); }
  setTime(value) { this.date.setTime(Number(value)); return this.getTime(); }
  before(other) { return this.getTime() < other.getTime(); }
  after(other) { return this.getTime() > other.getTime(); }
  compareTo(other) { return Math.sign(this.getTime() - other.getTime()); }
  equals(other) { return other instanceof GoldClientJavaDate && this.getTime() === other.getTime(); }
  toInstant() { return new Date(this.getTime()); }
  toISOString() { return this.date.toISOString(); }
  toString() { return this.date.toString(); }
  static now() { return new GoldClientJavaDate(Date.now()); }
}

class GoldClientJavaStream {
  constructor(values = []) {
    this.values = Array.from(values?.items ?? values ?? []);
    this.closed = false;
  }
  #assertOpen() { if (this.closed) throw new Error("IllegalStateException: stream has already been operated upon or closed"); }
  #next(values) { this.#assertOpen(); return new GoldClientJavaStream(values); }
  map(mapper) { this.#assertOpen(); return this.#next(this.values.map(mapper)); }
  filter(predicate) { this.#assertOpen(); return this.#next(this.values.filter(predicate)); }
  flatMap(mapper) {
    this.#assertOpen();
    const flattened = [];
    for (const value of this.values) {
      const mapped = mapper(value);
      flattened.push(...Array.from(mapped?.values ?? mapped?.items ?? mapped ?? []));
    }
    return this.#next(flattened);
  }
  peek(action) { this.#assertOpen(); this.values.forEach(action); return this; }
  distinct() { this.#assertOpen(); return this.#next(Array.from(new Set(this.values))); }
  sorted(comparator) {
    this.#assertOpen();
    const values = this.values.slice();
    values.sort((a, b) => GoldClientRuntime.compareJava(a, b, comparator ?? null));
    return this.#next(values);
  }
  limit(count) { this.#assertOpen(); if (count < 0) throw new RangeError("IllegalArgumentException: negative limit"); return this.#next(this.values.slice(0, count)); }
  skip(count) { this.#assertOpen(); if (count < 0) throw new RangeError("IllegalArgumentException: negative skip"); return this.#next(this.values.slice(count)); }
  sequential() { this.#assertOpen(); return this; }
  parallel() { this.#assertOpen(); return this; }
  unordered() { this.#assertOpen(); return this; }
  onClose(callback) { this.closeCallback = callback; return this; }
  close() { if (!this.closed) { this.closed = true; this.closeCallback?.(); } }
  forEach(action) { this.#assertOpen(); this.values.forEach(action); this.close(); }
  forEachOrdered(action) { return this.forEach(action); }
  toArray(generator) { this.#assertOpen(); return generator ? generator(this.values.length).map((_, i) => this.values[i]) : this.values.slice(); }
  count() { this.#assertOpen(); return this.values.length; }
  anyMatch(predicate) { this.#assertOpen(); return this.values.some(predicate); }
  allMatch(predicate) { this.#assertOpen(); return this.values.every(predicate); }
  noneMatch(predicate) { this.#assertOpen(); return !this.values.some(predicate); }
  findFirst() { this.#assertOpen(); return this.values.length ? new GoldClientJavaOptional(this.values[0]) : GoldClientJavaOptional.empty(); }
  findAny() { return this.findFirst(); }
  reduce(...args) {
    this.#assertOpen();
    if (args.length > 1) return this.values.reduce(args[1], args[0]);
    return this.values.length ? new GoldClientJavaOptional(this.values.reduce(args[0])) : GoldClientJavaOptional.empty();
  }
  min(comparator) {
    this.#assertOpen();
    if (!this.values.length) return GoldClientJavaOptional.empty();
    return new GoldClientJavaOptional(this.values.reduce((a, b) =>
      GoldClientRuntime.compareJava(a, b, comparator ?? null) <= 0 ? a : b));
  }
  max(comparator) {
    this.#assertOpen();
    if (!this.values.length) return GoldClientJavaOptional.empty();
    return new GoldClientJavaOptional(this.values.reduce((a, b) =>
      GoldClientRuntime.compareJava(a, b, comparator ?? null) >= 0 ? a : b));
  }
  collect(collector, valueMapper, mergeFunction) {
    this.#assertOpen();
    if (collector && typeof collector === "function") return this.values.reduce(collector, valueMapper);
    if (collector?.kind === "list") return new GoldClientJavaList(this.values);
    if (collector?.kind === "set") return new GoldClientJavaSet(this.values);
    if (collector?.kind === "joining") return this.values.map(String).join(collector.delimiter ?? "");
    if (collector?.kind === "counting") return this.values.length;
    if (collector?.kind === "toMap") {
      const map = new GoldClientJavaMap();
      for (const value of this.values) {
        const key = collector.keyMapper(value);
        const mapped = collector.valueMapper(value);
        if (map.containsKey(key)) {
          if (!collector.mergeFunction) throw new Error("IllegalStateException: duplicate key " + key);
          map.put(key, collector.mergeFunction(map.get(key), mapped));
        } else map.put(key, mapped);
      }
      return map;
    }
    return this.values.slice();
  }
  toList() { this.#assertOpen(); return new GoldClientJavaList(this.values); }
  iterator() { this.#assertOpen(); return this.values[Symbol.iterator](); }
  [Symbol.iterator]() { this.#assertOpen(); return this.values[Symbol.iterator](); }
}

class GoldClientJavaMatcher {
  constructor(pattern, input) {
    this.pattern = pattern;
    this.input = String(input);
    this.cursor = 0;
    this.last = null;
  }
  find() {
    const flags = this.pattern.flags.includes("g") ? this.pattern.flags : this.pattern.flags + "g";
    const regex = new RegExp(this.pattern.source, flags);
    regex.lastIndex = this.cursor;
    const match = regex.exec(this.input);
    if (!match) { this.last = null; return false; }
    this.last = match;
    this.cursor = match.index + Math.max(match[0].length, 1);
    return true;
  }
  matches() {
    const regex = new RegExp("^(?:" + this.pattern.source + ")$", this.pattern.flags.replace(/[gy]/g, ""));
    this.last = regex.exec(this.input);
    return this.last !== null;
  }
  lookingAt() {
    const regex = new RegExp("^(?:" + this.pattern.source + ")", this.pattern.flags.replace(/[gy]/g, ""));
    this.last = regex.exec(this.input);
    return this.last !== null;
  }
  group(index = 0) {
    if (!this.last) throw new Error("IllegalStateException: No match available");
    if (index < 0 || index >= this.last.length) throw new RangeError("IndexOutOfBoundsException");
    return this.last[index] ?? null;
  }
  groupCount() { return Math.max(0, (this.last?.length ?? 1) - 1); }
  start(index = 0) {
    if (!this.last) throw new Error("IllegalStateException: No match available");
    if (index === 0) return this.last.index;
    const prefix = this.last[0].indexOf(this.last[index] ?? "");
    return prefix < 0 ? -1 : this.last.index + prefix;
  }
  end(index = 0) {
    const start = this.start(index);
    const value = this.group(index);
    return start < 0 || value == null ? -1 : start + value.length;
  }
  reset(input = this.input) { this.input = String(input); this.cursor = 0; this.last = null; return this; }
  replaceAll(replacement) { return this.input.replace(new RegExp(this.pattern.source, this.pattern.flags.replace(/[gy]/g, "") + "g"), String(replacement)); }
  replaceFirst(replacement) { return this.input.replace(new RegExp(this.pattern.source, this.pattern.flags.replace(/[gy]/g, "")), String(replacement)); }
  usePattern(pattern) { this.pattern = pattern; this.cursor = 0; this.last = null; return this; }
}
class GoldClientJavaPattern {
  constructor(source, flags = "") { this.source = String(source); this.flags = flags; this.regex = new RegExp(this.source, this.flags); }
  matcher(input) { return new GoldClientJavaMatcher(this, input); }
  pattern() { return this.source; }
  flagsValue() { return this.flags; }
  split(input, limit = 0) { return String(input).split(this.regex, limit === 0 ? undefined : limit); }
  toString() { return this.source; }
  static compile(source, flags = "") {
    let jsFlags = "";
    if (typeof flags === "string") jsFlags = flags;
    else {
      if (flags & 2) jsFlags += "i";
      if (flags & 4) jsFlags += "m";
      if (flags & 32) jsFlags += "s";
    }
    return new GoldClientJavaPattern(source, [...new Set(jsFlags)].join(""));
  }
  static matches(regex, input) { return new RegExp("^(?:" + regex + ")$").test(String(input)); }
  static quote(value) { return Array.from(String(value), character => ".^$*+?()[]{}|".includes(character) || character.charCodeAt(0) === 92 ? "\\" + character : character).join(""); }
}

class GoldClientJavaOptional {
  constructor(value, present = value !== null && value !== undefined) {
    this.present = Boolean(present);
    this.value = this.present ? value : null;
  }
  isPresent() { return this.present; }
  isEmpty() { return !this.present; }
  get() {
    if (!this.present) throw new Error("NoSuchElementException: No value present");
    return this.value;
  }
  orElse(fallback) { return this.present ? this.value : fallback; }
  orElseGet(supplier) { return this.present ? this.value : supplier(); }
  orElseThrow(supplier) {
    if (this.present) return this.value;
    throw typeof supplier === "function" ? supplier() : new Error("NoSuchElementException: No value present");
  }
  ifPresent(consumer) { if (this.present) consumer(this.value); }
  ifPresentOrElse(consumer, emptyAction) { if (this.present) consumer(this.value); else emptyAction(); }
  map(mapper) { return this.present ? new GoldClientJavaOptional(mapper(this.value)) : new GoldClientJavaOptional(null, false); }
  flatMap(mapper) {
    if (!this.present) return new GoldClientJavaOptional(null, false);
    const result = mapper(this.value);
    if (!(result instanceof GoldClientJavaOptional)) throw new TypeError("Optional.flatMap mapper must return Optional");
    return result;
  }
  filter(predicate) { return !this.present || predicate(this.value) ? this : new GoldClientJavaOptional(null, false); }
  stream() { return new GoldClientJavaStream(this.present ? [this.value] : []); }
  toString() { return this.present ? "Optional[" + String(this.value) + "]" : "Optional.empty"; }
  static empty() { return new GoldClientJavaOptional(null, false); }
  static of(value) {
    if (value == null) throw new TypeError("NullPointerException");
    return new GoldClientJavaOptional(value);
  }
  static ofNullable(value) { return new GoldClientJavaOptional(value); }
}

class GoldClientJavaStringBuilder {
  constructor(initial = "") { this.value = initial == null ? "" : String(initial); }
  append(value) { this.value += GoldClientRuntime.javaString(value); return this; }
  toString() { return this.value; }
  length() { return this.value.length; }
  charAt(index) {
    if (!Number.isInteger(index) || index < 0 || index >= this.value.length) {
      throw new RangeError("StringIndexOutOfBoundsException: " + index);
    }
    return this.value.charAt(index);
  }
  setLength(length) {
    if (!Number.isInteger(length) || length < 0) throw new RangeError("NegativeArraySizeException: " + length);
    this.value = length <= this.value.length
      ? this.value.slice(0, length) : this.value.padEnd(length, "\0");
    return this;
  }
  delete(start, end) {
    if (!Number.isInteger(start) || !Number.isInteger(end) || start < 0 || start > this.value.length || end < 0 || start > end) {
      throw new RangeError("StringIndexOutOfBoundsException");
    }
    this.value = this.value.slice(0, start) + this.value.slice(Math.min(end, this.value.length));
    return this;
  }
  deleteCharAt(index) {
    if (!Number.isInteger(index) || index < 0 || index >= this.value.length) {
      throw new RangeError("StringIndexOutOfBoundsException: " + index);
    }
    this.value = this.value.slice(0, index) + this.value.slice(index + 1);
    return this;
  }
  insert(index, value) {
    if (!Number.isInteger(index) || index < 0 || index > this.value.length) {
      throw new RangeError("StringIndexOutOfBoundsException: " + index);
    }
    this.value = this.value.slice(0, index) + GoldClientRuntime.javaString(value) + this.value.slice(index);
    return this;
  }
  reverse() { this.value = Array.from(this.value).reverse().join(""); return this; }
}

// Value-type adapters for common Minecraft 1.12.2 APIs. These do not
// emulate a World or server; they implement deterministic math/identity types.
class GoldClientMinecraftResourceLocation {
  constructor(namespaceOrPath, path = undefined) {
    let namespace = path === undefined ? "minecraft" : String(namespaceOrPath);
    let resourcePath = path === undefined ? String(namespaceOrPath) : String(path);
    if (path === undefined && resourcePath.includes(":")) {
      const split = resourcePath.indexOf(":");
      namespace = resourcePath.slice(0, split);
      resourcePath = resourcePath.slice(split + 1);
    }
    this.resourceDomain = namespace.toLowerCase();
    this.resourcePath = resourcePath.toLowerCase();
    if (!/^[a-z0-9_.-]+$/.test(this.resourceDomain)
        || !/^[a-z0-9/._-]+$/.test(this.resourcePath)) {
      throw new TypeError("IllegalArgumentException: invalid resource location");
    }
  }
  getResourceDomain() { return this.resourceDomain; }
  getNamespace() { return this.resourceDomain; }
  getResourcePath() { return this.resourcePath; }
  getPath() { return this.resourcePath; }
  toString() { return this.resourceDomain + ":" + this.resourcePath; }
  equals(other) {
    return other != null && other.resourceDomain === this.resourceDomain
      && other.resourcePath === this.resourcePath;
  }
  hashCode() {
    let hash = 0;
    for (const character of this.toString()) hash = (Math.imul(hash, 31) + character.charCodeAt(0)) | 0;
    return hash;
  }
}

class GoldClientMinecraftBlockPos {
  constructor(x = 0, y = 0, z = 0) {
    if (x != null && typeof x === "object") {
      const position = x;
      this.x = Math.floor(Number(position.x ?? position.getX?.() ?? 0));
      this.y = Math.floor(Number(position.y ?? position.getY?.() ?? 0));
      this.z = Math.floor(Number(position.z ?? position.getZ?.() ?? 0));
    } else {
      this.x = Math.floor(Number(x));
      this.y = Math.floor(Number(y));
      this.z = Math.floor(Number(z));
    }
    if (![this.x, this.y, this.z].every(Number.isFinite)) {
      throw new TypeError("IllegalArgumentException: invalid block position");
    }
  }
  getX() { return this.x; }
  getY() { return this.y; }
  getZ() { return this.z; }
  add(x, y, z) {
    if (x != null && typeof x === "object") return this.add(x.x ?? x.getX?.() ?? 0, x.y ?? x.getY?.() ?? 0, x.z ?? x.getZ?.() ?? 0);
    return new GoldClientMinecraftBlockPos(this.x + Number(x), this.y + Number(y), this.z + Number(z));
  }
  subtract(position) { return this.add(-(position.x ?? position.getX?.() ?? 0), -(position.y ?? position.getY?.() ?? 0), -(position.z ?? position.getZ?.() ?? 0)); }
  up(distance = 1) { return this.add(0, distance, 0); }
  down(distance = 1) { return this.add(0, -distance, 0); }
  north(distance = 1) { return this.add(0, 0, -distance); }
  south(distance = 1) { return this.add(0, 0, distance); }
  east(distance = 1) { return this.add(distance, 0, 0); }
  west(distance = 1) { return this.add(-distance, 0, 0); }
  offset(direction, distance = 1) {
    const x = Number(direction?.getXOffset?.() ?? direction?.x ?? 0);
    const y = Number(direction?.getYOffset?.() ?? direction?.y ?? 0);
    const z = Number(direction?.getZOffset?.() ?? direction?.z ?? 0);
    return this.add(x * distance, y * distance, z * distance);
  }
  distanceSq(x, y, z) {
    if (x != null && typeof x === "object") { z = x.z ?? x.getZ?.() ?? 0; y = x.y ?? x.getY?.() ?? 0; x = x.x ?? x.getX?.() ?? 0; }
    const dx = this.x - Number(x), dy = this.y - Number(y), dz = this.z - Number(z);
    return dx * dx + dy * dy + dz * dz;
  }
  getDistance(x, y, z) { return Math.sqrt(this.distanceSq(x, y, z)); }
  distanceSqToCenter(x, y, z) {
    const dx = this.x + 0.5 - Number(x), dy = this.y + 0.5 - Number(y), dz = this.z + 0.5 - Number(z);
    return dx * dx + dy * dy + dz * dz;
  }
  manhattanDistance(other) {
    return Math.abs(this.x - Number(other.x ?? other.getX?.() ?? 0))
      + Math.abs(this.y - Number(other.y ?? other.getY?.() ?? 0))
      + Math.abs(this.z - Number(other.z ?? other.getZ?.() ?? 0));
  }
  crossProduct(other) {
    const x = Number(other.x ?? other.getX?.() ?? 0), y = Number(other.y ?? other.getY?.() ?? 0), z = Number(other.z ?? other.getZ?.() ?? 0);
    return new GoldClientMinecraftBlockPos(this.y*z-this.z*y, this.z*x-this.x*z, this.x*y-this.y*x);
  }
  compareTo(other) {
    const y = this.y - Number(other.y ?? other.getY?.() ?? 0);
    if (y) return Math.sign(y);
    const z = this.z - Number(other.z ?? other.getZ?.() ?? 0);
    if (z) return Math.sign(z);
    return Math.sign(this.x - Number(other.x ?? other.getX?.() ?? 0));
  }
  toLong() {
    return BigInt.asIntN(64, (BigInt(this.x & 0x3ffffff) << 38n)
      | (BigInt(this.z & 0x3ffffff) << 12n) | BigInt(this.y & 0xfff));
  }
  equals(other) { return other != null && this.x === other.x && this.y === other.y && this.z === other.z; }
  hashCode() { return (Math.imul(this.x, 3129871) ^ Math.imul(this.z, 116129781) ^ this.y) | 0; }
  toString() { return "(" + this.x + ", " + this.y + ", " + this.z + ")"; }
  static fromLong(value) {
    const packed = BigInt.asUintN(64, BigInt(value));
    const signExtend = (number, bits) => Number(BigInt.asIntN(bits, number));
    return new GoldClientMinecraftBlockPos(
      signExtend(packed >> 38n, 26),
      signExtend(packed & 0xfffn, 12),
      signExtend((packed >> 12n) & 0x3ffffffn, 26));
  }
}
GoldClientMinecraftBlockPos.ORIGIN = new GoldClientMinecraftBlockPos(0, 0, 0);

class GoldClientMinecraftEnumAxis {
  constructor(name) { this.name = name; }
  getName() { return this.name.toLowerCase(); }
  isVertical() { return this.name === "Y"; }
  isHorizontal() { return this.name !== "Y"; }
  toString() { return this.getName(); }
  equals(other) { return other != null && this.name === other.name; }
}
GoldClientMinecraftEnumAxis.X = new GoldClientMinecraftEnumAxis("X");
GoldClientMinecraftEnumAxis.Y = new GoldClientMinecraftEnumAxis("Y");
GoldClientMinecraftEnumAxis.Z = new GoldClientMinecraftEnumAxis("Z");
GoldClientMinecraftEnumAxis.VALUES = [
  GoldClientMinecraftEnumAxis.X, GoldClientMinecraftEnumAxis.Y, GoldClientMinecraftEnumAxis.Z
];

class GoldClientMinecraftAxisDirection {
  constructor(name, offset) { this.name = name; this.offset = offset; }
  getName() { return this.name.toLowerCase(); }
  getOffset() { return this.offset; }
  toString() { return this.getName(); }
  equals(other) { return other != null && this.name === other.name; }
}
GoldClientMinecraftAxisDirection.POSITIVE = new GoldClientMinecraftAxisDirection("POSITIVE", 1);
GoldClientMinecraftAxisDirection.NEGATIVE = new GoldClientMinecraftAxisDirection("NEGATIVE", -1);
GoldClientMinecraftAxisDirection.VALUES = [
  GoldClientMinecraftAxisDirection.POSITIVE, GoldClientMinecraftAxisDirection.NEGATIVE
];

class GoldClientMinecraftEnumFacing {
  constructor(name, index, x, y, z, horizontalIndex = -1) {
    this.name = name; this.index = index;
    this.xOffset = x; this.yOffset = y; this.zOffset = z;
    this.horizontalIndex = horizontalIndex;
    this.axis = y !== 0 ? GoldClientMinecraftEnumAxis.Y
      : x !== 0 ? GoldClientMinecraftEnumAxis.X : GoldClientMinecraftEnumAxis.Z;
    this.axisDirection = (x + y + z) > 0
      ? GoldClientMinecraftAxisDirection.POSITIVE : GoldClientMinecraftAxisDirection.NEGATIVE;
  }
  getName() { return this.name.toLowerCase(); }
  getIndex() { return this.index; }
  getHorizontalIndex() { return this.horizontalIndex; }
  getXOffset() { return this.xOffset; }
  getYOffset() { return this.yOffset; }
  getZOffset() { return this.zOffset; }
  getAxis() { return this.axis; }
  getAxisDirection() { return this.axisDirection; }
  getOpposite() { return GoldClientMinecraftEnumFacing.OPPOSITES[this.name]; }
  toString() { return this.getName(); }
  equals(other) { return other != null && this.name === other.name; }
  static VALUES = [];
  static OPPOSITES = {};
  static byName(name) {
    if (name == null) return null;
    return this.VALUES.find(value => value.getName() === String(name).toLowerCase()) ?? null;
  }
  static getFront(index) { return this.VALUES[((Math.trunc(Number(index)) % 6) + 6) % 6]; }
  static getHorizontal(index) {
    const horizontal = [this.SOUTH, this.WEST, this.NORTH, this.EAST];
    return horizontal[((Math.trunc(Number(index)) % 4) + 4) % 4];
  }
}
GoldClientMinecraftEnumFacing.DOWN = new GoldClientMinecraftEnumFacing("DOWN", 0, 0, -1, 0);
GoldClientMinecraftEnumFacing.UP = new GoldClientMinecraftEnumFacing("UP", 1, 0, 1, 0);
GoldClientMinecraftEnumFacing.NORTH = new GoldClientMinecraftEnumFacing("NORTH", 2, 0, 0, -1, 2);
GoldClientMinecraftEnumFacing.SOUTH = new GoldClientMinecraftEnumFacing("SOUTH", 3, 0, 0, 1, 0);
GoldClientMinecraftEnumFacing.WEST = new GoldClientMinecraftEnumFacing("WEST", 4, -1, 0, 0, 1);
GoldClientMinecraftEnumFacing.EAST = new GoldClientMinecraftEnumFacing("EAST", 5, 1, 0, 0, 3);
GoldClientMinecraftEnumFacing.VALUES = [
  GoldClientMinecraftEnumFacing.DOWN, GoldClientMinecraftEnumFacing.UP,
  GoldClientMinecraftEnumFacing.NORTH, GoldClientMinecraftEnumFacing.SOUTH,
  GoldClientMinecraftEnumFacing.WEST, GoldClientMinecraftEnumFacing.EAST
];
GoldClientMinecraftEnumFacing.OPPOSITES = {
  DOWN: GoldClientMinecraftEnumFacing.UP, UP: GoldClientMinecraftEnumFacing.DOWN,
  NORTH: GoldClientMinecraftEnumFacing.SOUTH, SOUTH: GoldClientMinecraftEnumFacing.NORTH,
  WEST: GoldClientMinecraftEnumFacing.EAST, EAST: GoldClientMinecraftEnumFacing.WEST
};

class GoldClientMinecraftAxisAlignedBB {
  constructor(...args) {
    let values;
    if (args.length === 1 && args[0] != null && typeof args[0] === "object") {
      const p = args[0];
      const x = Number(p.x ?? p.getX?.() ?? 0), y = Number(p.y ?? p.getY?.() ?? 0), z = Number(p.z ?? p.getZ?.() ?? 0);
      values = [x, y, z, x + 1, y + 1, z + 1];
    } else if (args.length === 2 && args[0] != null && args[1] != null
        && typeof args[0] === "object" && typeof args[1] === "object") {
      const a = args[0], b = args[1];
      values = [a.x ?? a.getX?.() ?? 0, a.y ?? a.getY?.() ?? 0, a.z ?? a.getZ?.() ?? 0,
        b.x ?? b.getX?.() ?? 0, b.y ?? b.getY?.() ?? 0, b.z ?? b.getZ?.() ?? 0].map(Number);
    } else if (args.length >= 6) values = args.slice(0, 6).map(Number);
    else throw new TypeError("IllegalArgumentException: AxisAlignedBB requires a position or six coordinates");
    this.minX = Math.min(values[0], values[3]); this.minY = Math.min(values[1], values[4]); this.minZ = Math.min(values[2], values[5]);
    this.maxX = Math.max(values[0], values[3]); this.maxY = Math.max(values[1], values[4]); this.maxZ = Math.max(values[2], values[5]);
  }
  intersects(other) {
    return other != null && this.maxX > other.minX && this.minX < other.maxX
      && this.maxY > other.minY && this.minY < other.maxY
      && this.maxZ > other.minZ && this.minZ < other.maxZ;
  }
  intersectsWith(other) { return this.intersects(other); }
  contains(point) {
    return point != null && point.x >= this.minX && point.x <= this.maxX
      && point.y >= this.minY && point.y <= this.maxY
      && point.z >= this.minZ && point.z <= this.maxZ;
  }
  offset(x, y = undefined, z = undefined) {
    if (x != null && typeof x === "object") { z = x.z ?? x.getZ?.() ?? 0; y = x.y ?? x.getY?.() ?? 0; x = x.x ?? x.getX?.() ?? 0; }
    x = Number(x); y = Number(y); z = Number(z);
    return new GoldClientMinecraftAxisAlignedBB(this.minX+x, this.minY+y, this.minZ+z, this.maxX+x, this.maxY+y, this.maxZ+z);
  }
  grow(x, y = x, z = x) {
    return new GoldClientMinecraftAxisAlignedBB(this.minX-Number(x), this.minY-Number(y), this.minZ-Number(z),
      this.maxX+Number(x), this.maxY+Number(y), this.maxZ+Number(z));
  }
  expand(x, y, z) {
    x = Number(x); y = Number(y); z = Number(z);
    return new GoldClientMinecraftAxisAlignedBB(
      this.minX + (x < 0 ? x : 0), this.minY + (y < 0 ? y : 0), this.minZ + (z < 0 ? z : 0),
      this.maxX + (x > 0 ? x : 0), this.maxY + (y > 0 ? y : 0), this.maxZ + (z > 0 ? z : 0));
  }
  contract(x, y, z) { return this.grow(-Number(x), -Number(y), -Number(z)); }
  union(other) {
    return new GoldClientMinecraftAxisAlignedBB(Math.min(this.minX, other.minX), Math.min(this.minY, other.minY), Math.min(this.minZ, other.minZ),
      Math.max(this.maxX, other.maxX), Math.max(this.maxY, other.maxY), Math.max(this.maxZ, other.maxZ));
  }
  calculateXOffset(other, offset) {
    if (other.maxY > this.minY && other.minY < this.maxY && other.maxZ > this.minZ && other.minZ < this.maxZ) {
      if (offset > 0 && other.maxX <= this.minX) offset = Math.min(this.minX - other.maxX, offset);
      else if (offset < 0 && other.minX >= this.maxX) offset = Math.max(this.maxX - other.minX, offset);
    }
    return offset;
  }
  calculateYOffset(other, offset) {
    if (other.maxX > this.minX && other.minX < this.maxX && other.maxZ > this.minZ && other.minZ < this.maxZ) {
      if (offset > 0 && other.maxY <= this.minY) offset = Math.min(this.minY - other.maxY, offset);
      else if (offset < 0 && other.minY >= this.maxY) offset = Math.max(this.maxY - other.minY, offset);
    }
    return offset;
  }
  calculateZOffset(other, offset) {
    if (other.maxX > this.minX && other.minX < this.maxX && other.maxY > this.minY && other.minY < this.maxY) {
      if (offset > 0 && other.maxZ <= this.minZ) offset = Math.min(this.minZ - other.maxZ, offset);
      else if (offset < 0 && other.minZ >= this.maxZ) offset = Math.max(this.maxZ - other.minZ, offset);
    }
    return offset;
  }
  getAverageEdgeLength() { return ((this.maxX-this.minX)+(this.maxY-this.minY)+(this.maxZ-this.minZ))/3; }
  isEmpty() { return this.maxX <= this.minX || this.maxY <= this.minY || this.maxZ <= this.minZ; }
  hasNaN() { return [this.minX,this.minY,this.minZ,this.maxX,this.maxY,this.maxZ].some(Number.isNaN); }
  equals(other) {
    return other != null && this.minX === other.minX && this.minY === other.minY && this.minZ === other.minZ
      && this.maxX === other.maxX && this.maxY === other.maxY && this.maxZ === other.maxZ;
  }
  hashCode() {
    let hash = 1;
    for (const value of [this.minX,this.minY,this.minZ,this.maxX,this.maxY,this.maxZ]) hash = (Math.imul(hash,31) + (Number.isFinite(value) ? (value * 31 | 0) : 0)) | 0;
    return hash;
  }
  toString() { return "box["+this.minX+", "+this.minY+", "+this.minZ+" -> "+this.maxX+", "+this.maxY+", "+this.maxZ+"]"; }
}

class GoldClientMinecraftVec3d {
  constructor(x = 0, y = 0, z = 0) {
    if (x != null && typeof x === "object") {
      const vector = x;
      this.x = Number(vector.x ?? 0); this.y = Number(vector.y ?? 0); this.z = Number(vector.z ?? 0);
    } else { this.x = Number(x); this.y = Number(y); this.z = Number(z); }
  }
  addVector(x, y, z) { return new GoldClientMinecraftVec3d(this.x + x, this.y + y, this.z + z); }
  add(other) { return this.addVector(other.x, other.y, other.z); }
  subtract(other) { return new GoldClientMinecraftVec3d(this.x - other.x, this.y - other.y, this.z - other.z); }
  scale(scale) { return new GoldClientMinecraftVec3d(this.x * scale, this.y * scale, this.z * scale); }
  dotProduct(other) { return this.x * other.x + this.y * other.y + this.z * other.z; }
  crossProduct(other) { return new GoldClientMinecraftVec3d(this.y * other.z - this.z * other.y, this.z * other.x - this.x * other.z, this.x * other.y - this.y * other.x); }
  lengthSquared() { return this.dotProduct(this); }
  lengthVector() { return Math.sqrt(this.lengthSquared()); }
  normalize() { const length = this.lengthVector(); return length < 1e-4 ? new GoldClientMinecraftVec3d(0, 0, 0) : this.scale(1 / length); }
  distanceTo(other) { return Math.sqrt(this.squareDistanceTo(other)); }
  squareDistanceTo(other) { return (this.x-other.x)**2 + (this.y-other.y)**2 + (this.z-other.z)**2; }
  toString() { return "("+this.x+", "+this.y+", "+this.z+")"; }
  equals(other) { return other != null && this.x === other.x && this.y === other.y && this.z === other.z; }
}
GoldClientMinecraftVec3d.ZERO = new GoldClientMinecraftVec3d(0, 0, 0);

// TextFormatting is a deterministic enum of colour/style codes. Only members
// mods actually use are implemented: constants, values/valueOf, colour
// indexes, friendly names, section-sign rendering, and code lookup.
class GoldClientMinecraftTextFormatting {
  constructor(constantName, code, colorIndex, friendlyName, fancy = false) {
    this.constantName = constantName;
    this.formattingCode = code;
    this.colorIndex = colorIndex;
    this.friendlyName = friendlyName;
    this.fancy = fancy;
  }
  name() { return this.constantName; }
  ordinal() { return GoldClientMinecraftTextFormatting.VALUES.indexOf(this); }
  getColorIndex() { return this.colorIndex; }
  getFriendlyName() { return this.friendlyName; }
  isFancyStyling() { return this.fancy; }
  toString() { return "\u00a7" + this.formattingCode; }
  equals(other) { return other != null && this.constantName === other.constantName; }
  hashCode() { return Math.imul(this.constantName.length, 31) + this.ordinal(); }
  compareTo(other) { return Math.sign((other?.ordinal?.() ?? 0) - this.ordinal()); }
  static values() { return GoldClientMinecraftTextFormatting.VALUES.slice(); }
  static valueOf(name) {
    const found = GoldClientMinecraftTextFormatting.VALUES.find(value => value.constantName === String(name));
    if (!found) throw new TypeError("IllegalArgumentException: No enum constant net.minecraft.util.text.TextFormatting." + name);
    return found;
  }
  static fromColorIndex(index) {
    const wanted = Number(index);
    return GoldClientMinecraftTextFormatting.VALUES.find(value => value.colorIndex === wanted) ?? null;
  }
  static getByCode(code) {
    const text = String(code);
    return text.length === 1
      ? GoldClientMinecraftTextFormatting.VALUES.find(value => value.formattingCode === text) ?? null
      : null;
  }
}
const __goldTextFormatting = (name, code, colorIndex, friendlyName, fancy = false) =>
  new GoldClientMinecraftTextFormatting(name, code, colorIndex, friendlyName, fancy);
GoldClientMinecraftTextFormatting.BLACK = __goldTextFormatting("BLACK", "0", 0, "Black");
GoldClientMinecraftTextFormatting.DARK_BLUE = __goldTextFormatting("DARK_BLUE", "1", 1, "Dark Blue");
GoldClientMinecraftTextFormatting.DARK_GREEN = __goldTextFormatting("DARK_GREEN", "2", 2, "Dark Green");
GoldClientMinecraftTextFormatting.DARK_AQUA = __goldTextFormatting("DARK_AQUA", "3", 3, "Dark Aqua");
GoldClientMinecraftTextFormatting.DARK_RED = __goldTextFormatting("DARK_RED", "4", 4, "Dark Red");
GoldClientMinecraftTextFormatting.DARK_PURPLE = __goldTextFormatting("DARK_PURPLE", "5", 5, "Dark Purple");
GoldClientMinecraftTextFormatting.GOLD = __goldTextFormatting("GOLD", "6", 6, "Gold");
GoldClientMinecraftTextFormatting.GRAY = __goldTextFormatting("GRAY", "7", 7, "Gray");
GoldClientMinecraftTextFormatting.DARK_GRAY = __goldTextFormatting("DARK_GRAY", "8", 8, "Dark Gray");
GoldClientMinecraftTextFormatting.BLUE = __goldTextFormatting("BLUE", "9", 9, "Blue");
GoldClientMinecraftTextFormatting.GREEN = __goldTextFormatting("GREEN", "a", 10, "Green");
GoldClientMinecraftTextFormatting.AQUA = __goldTextFormatting("AQUA", "b", 11, "Aqua");
GoldClientMinecraftTextFormatting.RED = __goldTextFormatting("RED", "c", 12, "Red");
GoldClientMinecraftTextFormatting.LIGHT_PURPLE = __goldTextFormatting("LIGHT_PURPLE", "d", 13, "Light Purple");
GoldClientMinecraftTextFormatting.YELLOW = __goldTextFormatting("YELLOW", "e", 14, "Yellow");
GoldClientMinecraftTextFormatting.WHITE = __goldTextFormatting("WHITE", "f", 15, "White");
GoldClientMinecraftTextFormatting.OBFUSCATED = __goldTextFormatting("OBFUSCATED", "k", -1, "Obfuscated", true);
GoldClientMinecraftTextFormatting.BOLD = __goldTextFormatting("BOLD", "l", -1, "Bold", true);
GoldClientMinecraftTextFormatting.STRIKETHROUGH = __goldTextFormatting("STRIKETHROUGH", "m", -1, "Strikethrough", true);
GoldClientMinecraftTextFormatting.UNDERLINE = __goldTextFormatting("UNDERLINE", "n", -1, "Underline", true);
GoldClientMinecraftTextFormatting.ITALIC = __goldTextFormatting("ITALIC", "o", -1, "Italic", true);
GoldClientMinecraftTextFormatting.RESET = __goldTextFormatting("RESET", "r", -1, "Reset");
GoldClientMinecraftTextFormatting.VALUES = [
  GoldClientMinecraftTextFormatting.BLACK, GoldClientMinecraftTextFormatting.DARK_BLUE,
  GoldClientMinecraftTextFormatting.DARK_GREEN, GoldClientMinecraftTextFormatting.DARK_AQUA,
  GoldClientMinecraftTextFormatting.DARK_RED, GoldClientMinecraftTextFormatting.DARK_PURPLE,
  GoldClientMinecraftTextFormatting.GOLD, GoldClientMinecraftTextFormatting.GRAY,
  GoldClientMinecraftTextFormatting.DARK_GRAY, GoldClientMinecraftTextFormatting.BLUE,
  GoldClientMinecraftTextFormatting.GREEN, GoldClientMinecraftTextFormatting.AQUA,
  GoldClientMinecraftTextFormatting.RED, GoldClientMinecraftTextFormatting.LIGHT_PURPLE,
  GoldClientMinecraftTextFormatting.YELLOW, GoldClientMinecraftTextFormatting.WHITE,
  GoldClientMinecraftTextFormatting.OBFUSCATED, GoldClientMinecraftTextFormatting.BOLD,
  GoldClientMinecraftTextFormatting.STRIKETHROUGH, GoldClientMinecraftTextFormatting.UNDERLINE,
  GoldClientMinecraftTextFormatting.ITALIC, GoldClientMinecraftTextFormatting.RESET
];

class GoldClientMinecraftEnumRarity {
  constructor(constantName, color, rarityName) {
    this.constantName = constantName;
    this.rarityColor = color;
    this.rarityName = rarityName;
  }
  name() { return this.constantName; }
  ordinal() { return GoldClientMinecraftEnumRarity.VALUES.indexOf(this); }
  getColor() { return this.rarityColor; }
  getRarityName() { return this.rarityName; }
  toString() { return this.constantName; }
  equals(other) { return other != null && this.constantName === other.constantName; }
  hashCode() { return Math.imul(this.constantName.length, 31) + this.ordinal(); }
  compareTo(other) { return Math.sign((other?.ordinal?.() ?? 0) - this.ordinal()); }
  static values() { return GoldClientMinecraftEnumRarity.VALUES.slice(); }
  static valueOf(name) {
    const found = GoldClientMinecraftEnumRarity.VALUES.find(value => value.constantName === String(name));
    if (!found) throw new TypeError("IllegalArgumentException: No enum constant net.minecraft.item.EnumRarity." + name);
    return found;
  }
}
GoldClientMinecraftEnumRarity.COMMON = new GoldClientMinecraftEnumRarity("COMMON", GoldClientMinecraftTextFormatting.WHITE, "Common");
GoldClientMinecraftEnumRarity.UNCOMMON = new GoldClientMinecraftEnumRarity("UNCOMMON", GoldClientMinecraftTextFormatting.YELLOW, "Uncommon");
GoldClientMinecraftEnumRarity.RARE = new GoldClientMinecraftEnumRarity("RARE", GoldClientMinecraftTextFormatting.AQUA, "Rare");
GoldClientMinecraftEnumRarity.EPIC = new GoldClientMinecraftEnumRarity("EPIC", GoldClientMinecraftTextFormatting.LIGHT_PURPLE, "Epic");
GoldClientMinecraftEnumRarity.VALUES = [
  GoldClientMinecraftEnumRarity.COMMON, GoldClientMinecraftEnumRarity.UNCOMMON,
  GoldClientMinecraftEnumRarity.RARE, GoldClientMinecraftEnumRarity.EPIC
];

function goldClientNonNullListElement(value) {
  if (value === null || value === undefined) {
    throw new TypeError("NullPointerException: NonNullList rejects null elements");
  }
  return value;
}

// Record support: Java records compile equals/hashCode/toString to a single
// invokedynamic call on java.lang.runtime.ObjectMethods. The translated call
// carries the component names and descriptors, and these helpers reproduce the
// generated record semantics (Float/Double.compare for float components,
// 31-fold hashing, and the standard [name=value, ...] rendering).
const __goldRecordBitsBuffer = new ArrayBuffer(8);
const __goldRecordBitsView = new DataView(__goldRecordBitsBuffer);

function goldClientFloatToIntBits(value) {
  __goldRecordBitsView.setFloat32(0, Number(value), false);
  return __goldRecordBitsView.getInt32(0, false);
}

function goldClientDoubleToLongBitsHash(value) {
  __goldRecordBitsView.setFloat64(0, Number(value), false);
  const high = __goldRecordBitsView.getInt32(0, false);
  const low = __goldRecordBitsView.getInt32(4, false);
  return (high ^ low) | 0;
}

function goldClientFloatCompare(a, b) {
  const x = Math.fround(Number(a));
  const y = Math.fround(Number(b));
  if (Number.isNaN(x)) return Number.isNaN(y) ? 0 : 1;
  if (Number.isNaN(y)) return -1;
  if (x === y) {
    if (x === 0 && 1 / x !== 1 / y) return 1 / x < 1 / y ? -1 : 1;
    return 0;
  }
  return x < y ? -1 : 1;
}

function goldClientDoubleCompare(a, b) {
  const x = Number(a);
  const y = Number(b);
  if (Number.isNaN(x)) return Number.isNaN(y) ? 0 : 1;
  if (Number.isNaN(y)) return -1;
  if (x === y) {
    if (x === 0 && 1 / x !== 1 / y) return 1 / x < 1 / y ? -1 : 1;
    return 0;
  }
  return x < y ? -1 : 1;
}

function goldClientRecordComponents(names) {
  const text = String(names ?? "");
  return text === "" ? [] : text.split(";");
}

// NonNullList matches the 1.12.2 usage patterns: create()/create(default) start
// empty and callers add elements, while withSize(size, default) pre-fills every
// slot. All mutators reject null elements.
class GoldClientMinecraftNonNullList extends GoldClientJavaList {
  constructor(items = [], defaultElement = null) {
    super(items);
    this.defaultElement = defaultElement ?? null;
  }
  getDefault() { return this.defaultElement; }
  add(...args) {
    if (args.length > 1) return this.addAt(args[0], args[1]);
    super.add(goldClientNonNullListElement(args[0]));
    return true;
  }
  addAt(index, value) { return super.addAt(index, goldClientNonNullListElement(value)); }
  addAll(values) {
    return super.addAll(Array.from(values?.items ?? values ?? []).map(goldClientNonNullListElement));
  }
  set(index, value) { return super.set(index, goldClientNonNullListElement(value)); }
  setAll(valuesOrElement) {
    const candidate = valuesOrElement?.items ?? valuesOrElement;
    const isCollection = Array.isArray(candidate)
      || (candidate != null && typeof candidate !== "string"
          && typeof candidate[Symbol.iterator] === "function");
    if (isCollection) {
      const copy = Array.from(candidate).map(goldClientNonNullListElement);
      if (copy.length !== this.items.length) {
        throw new RangeError("IllegalArgumentException: setAll collection size must match the list size");
      }
      this.items = copy;
      return true;
    }
    this.items = this.items.map(() => goldClientNonNullListElement(valuesOrElement));
    return true;
  }
  static create(...args) {
    return new GoldClientMinecraftNonNullList([], args.length ? args[0] : null);
  }
  static withSize(size, defaultElement) {
    const count = Number(size);
    if (!Number.isInteger(count) || count < 0) {
      throw new RangeError("IllegalArgumentException: invalid NonNullList size");
    }
    return new GoldClientMinecraftNonNullList(Array(count).fill(defaultElement ?? null), defaultElement ?? null);
  }
}

// Built-in platform adapters. These registrations are the runtime's single
// source of truth for owners served without host bindings;
// gold-client-adapters.json mirrors them for the Java compatibility planner and
// JavaScriptModTranslationExporterTest keeps both lists in sync.
const GoldClientMinecraftAdapters = new Map();
function registerMinecraftAdapter(owner, definition) {
  if (GoldClientMinecraftAdapters.has(owner)) {
    throw new Error("Duplicate built-in adapter: " + owner);
  }
  GoldClientMinecraftAdapters.set(owner, Object.freeze({ owner, kind: "value-type", ...definition }));
}

registerMinecraftAdapter("net.minecraft.util.ResourceLocation", {
  kind: "value-type",
  construct: args => new GoldClientMinecraftResourceLocation(...args)
});
registerMinecraftAdapter("net.minecraft.util.math.BlockPos", {
  kind: "value-type",
  construct: args => new GoldClientMinecraftBlockPos(...args),
  staticFields: { ORIGIN: GoldClientMinecraftBlockPos.ORIGIN },
  staticMethods: {
    fromLong: args => GoldClientMinecraftBlockPos.fromLong(args[0])
  }
});
registerMinecraftAdapter("net.minecraft.util.math.Vec3d", {
  kind: "value-type",
  construct: args => new GoldClientMinecraftVec3d(...args),
  staticFields: { ZERO: GoldClientMinecraftVec3d.ZERO }
});
registerMinecraftAdapter("net.minecraft.util.math.AxisAlignedBB", {
  kind: "value-type",
  construct: args => new GoldClientMinecraftAxisAlignedBB(...args)
});
registerMinecraftAdapter("net.minecraft.util.EnumFacing", {
  kind: "enum",
  staticFields: GoldClientMinecraftEnumFacing,
  staticMethods: {
    values: () => GoldClientMinecraftEnumFacing.VALUES.slice(),
    getFront: args => GoldClientMinecraftEnumFacing.getFront(args[0]),
    getHorizontal: args => GoldClientMinecraftEnumFacing.getHorizontal(args[0]),
    byName: args => GoldClientMinecraftEnumFacing.byName(args[0]),
    getFacingFromVector: args => {
      const [x, y, z] = args.map(Number);
      if (Math.abs(x) > Math.abs(y) && Math.abs(x) > Math.abs(z)) {
        return x < 0 ? GoldClientMinecraftEnumFacing.WEST : GoldClientMinecraftEnumFacing.EAST;
      }
      if (Math.abs(y) > Math.abs(z)) {
        return y < 0 ? GoldClientMinecraftEnumFacing.DOWN : GoldClientMinecraftEnumFacing.UP;
      }
      return z < 0 ? GoldClientMinecraftEnumFacing.NORTH : GoldClientMinecraftEnumFacing.SOUTH;
    }
  }
});
registerMinecraftAdapter("net.minecraft.util.EnumFacing$Axis", {
  kind: "enum",
  staticFields: GoldClientMinecraftEnumAxis,
  staticMethods: {
    values: () => GoldClientMinecraftEnumAxis.VALUES.slice(),
    byName: args => GoldClientMinecraftEnumAxis.VALUES.find(value => value.getName() === String(args[0]).toLowerCase()) ?? null
  }
});
registerMinecraftAdapter("net.minecraft.util.EnumFacing$AxisDirection", {
  kind: "enum",
  staticFields: GoldClientMinecraftAxisDirection,
  staticMethods: {
    values: () => GoldClientMinecraftAxisDirection.VALUES.slice(),
    byName: args => GoldClientMinecraftAxisDirection.VALUES.find(value => value.getName() === String(args[0]).toLowerCase()) ?? null
  }
});
registerMinecraftAdapter("net.minecraft.util.math.MathHelper", {
  kind: "static-utils",
  staticMethods: {
    clamp: args => Math.max(Number(args[1]), Math.min(Number(args[2]), Number(args[0]))),
    clamp_float: args => Math.max(Number(args[1]), Math.min(Number(args[2]), Number(args[0]))),
    clamp_int: args => Math.trunc(Math.max(Number(args[1]), Math.min(Number(args[2]), Number(args[0])))),
    floor: args => Math.floor(Number(args[0])),
    floor_double: args => Math.floor(Number(args[0])),
    fastFloor: args => Math.floor(Number(args[0])),
    ceil: args => Math.ceil(Number(args[0])),
    ceil_double: args => Math.ceil(Number(args[0])),
    abs: args => Math.abs(Number(args[0])),
    abs_int: args => Math.abs(Number(args[0])),
    sqrt: args => Math.sqrt(Number(args[0])),
    sqrt_double: args => Math.sqrt(Number(args[0])),
    sin: args => Math.sin(Number(args[0])),
    cos: args => Math.cos(Number(args[0])),
    atan2: args => Math.atan2(Number(args[0]), Number(args[1])),
    wrapDegrees: args => {
      let degrees = Number(args[0]) % 360;
      if (degrees >= 180) degrees -= 360;
      if (degrees < -180) degrees += 360;
      return degrees;
    },
    normalizeAngle: args => {
      const bound = Number(args[1]);
      return bound === 0 ? 0 : ((Number(args[0]) % bound) + bound) % bound;
    },
    denormalizeClamp: args => {
      const start = Number(args[0]), end = Number(args[1]);
      const amount = Math.max(0, Math.min(1, Number(args[2])));
      return start + (end - start) * amount;
    },
    pct: args => Number(args[0]) / Number(args[1]),
    average: args => {
      const values = Array.from(args[0] ?? []);
      return values.length ? values.reduce((sum, item) => sum + Number(item), 0) / values.length : 0;
    },
    getInt: args => {
      const random = args[0], min = Number(args[1]), max = Number(args[2]);
      return min >= max ? min : min + random.nextInt(max - min + 1);
    },
    getRandomIntegerInRange: args => {
      const random = args[0], min = Number(args[1]), max = Number(args[2]);
      return min >= max ? min : min + random.nextInt(max - min + 1);
    },
    getRandomFloat: args => {
      const random = args[0], min = Number(args[1]), max = Number(args[2]);
      return min + random.nextFloat() * (max - min);
    },
    hsvToRGB: args => {
      const hue = Number(args[0]), saturation = Number(args[1]), brightness = Number(args[2]);
      const rawSector = Math.floor(hue * 6);
      const sector = ((rawSector % 6) + 6) % 6;
      const fraction = hue * 6 - rawSector;
      const p = brightness * (1 - saturation);
      const q = brightness * (1 - fraction * saturation);
      const t = brightness * (1 - (1 - fraction) * saturation);
      const rgb = [[brightness,t,p],[q,brightness,p],[p,brightness,t],[p,q,brightness],[t,p,brightness],[brightness,p,q]][sector];
      return (Math.trunc(rgb[0] * 255) << 16) | (Math.trunc(rgb[1] * 255) << 8) | Math.trunc(rgb[2] * 255);
    }
  }
});
registerMinecraftAdapter("net.minecraft.util.text.TextFormatting", {
  kind: "enum",
  staticFields: GoldClientMinecraftTextFormatting,
  staticMethods: {
    values: () => GoldClientMinecraftTextFormatting.values(),
    valueOf: args => GoldClientMinecraftTextFormatting.valueOf(args[0]),
    fromColorIndex: args => GoldClientMinecraftTextFormatting.fromColorIndex(args[0]),
    getByCode: args => GoldClientMinecraftTextFormatting.getByCode(args[0])
  }
});
registerMinecraftAdapter("net.minecraft.item.EnumRarity", {
  kind: "enum",
  staticFields: GoldClientMinecraftEnumRarity,
  staticMethods: {
    values: () => GoldClientMinecraftEnumRarity.values(),
    valueOf: args => GoldClientMinecraftEnumRarity.valueOf(args[0])
  }
});
registerMinecraftAdapter("net.minecraft.util.NonNullList", {
  kind: "collection",
  construct: args => new GoldClientMinecraftNonNullList(Array.from(args[0]?.items ?? args[0] ?? []), args[1] ?? null),
  staticMethods: {
    create: args => GoldClientMinecraftNonNullList.create(...args),
    withSize: args => GoldClientMinecraftNonNullList.withSize(args[0], args[1])
  }
});

// Forge 1.12 mods commonly register event handlers at runtime with
// MinecraftForge.EVENT_BUS.register(this). The translated class registry keeps
// @SubscribeEvent metadata, so this adapter can route those calls to the real
// host event bus instead of returning a silent no-op.
const GOLD_SUBSCRIBE_EVENT = "net.minecraftforge.fml.common.eventhandler.SubscribeEvent";

function goldForgeEventType(descriptor) {
  const match = /^\(L([^;]+);\)V$/.exec(descriptor ?? "");
  return match ? match[1].replace(/\//g, ".") : null;
}

// Forge also notifies listeners registered for an event's superclasses and
// interfaces. Translated event classes carry that hierarchy in the registry;
// platform event classes that are absent from the bundle keep exact matching.
function goldForgeEventTypes(eventType) {
  const result = [];
  const pending = [eventType];
  const visited = new Set();
  while (pending.length) {
    const name = pending.pop();
    if (!name || name === "java.lang.Object" || visited.has(name)) continue;
    visited.add(name);
    result.push(name);
    const constructor = GoldClientRuntime.resolveClass(name);
    if (!constructor) continue;
    if (constructor.__goldSuperName) pending.push(constructor.__goldSuperName);
    for (const implemented of constructor.__goldInterfaces ?? []) pending.push(implemented);
  }
  return result;
}

class GoldClientForgeEventBus {
  #registrations = new WeakMap();
  #subscriptions = new Set();

  register(target) {
    if (target === null || target === undefined) {
      throw new TypeError("MinecraftForge.EVENT_BUS.register received null");
    }
    if (this.#registrations.has(target)) return;
    const bus = globalThis.goldClientForgeEventBus;
    if (!bus || typeof bus.subscribe !== "function") {
      return GoldClientRuntime.unsupported(
        "missing-forge-event-bus", "MinecraftForge.EVENT_BUS.register");
    }
    // Forge also accepts a Class argument and registers its static @SubscribeEvent
    // methods. The translator represents a class literal as a frozen descriptor,
    // not as the constructor itself, so detect that shape explicitly.
    const classLiteralName = (target.__goldClassName && !target.__goldMethods)
      ? target.__goldClassName : null;
    const staticOnly = classLiteralName !== null;
    const owner = globalThis.goldClientCurrentForgeModId
      ?? ("manual:" + (classLiteralName ?? target.constructor?.__goldClassName ?? "anonymous"));
    const registrations = [];
    const visited = new Set();
    let constructor = staticOnly
      ? GoldClientRuntime.resolveClass(classLiteralName)
      : target.constructor;
    while (constructor && !visited.has(constructor)) {
      visited.add(constructor);
      const ownerClass = constructor;
      for (const [name, candidates] of constructor.__goldMethods ?? []) {
        for (const method of candidates) {
          if (method.isStatic !== staticOnly) continue;
          if (!(method.annotations ?? []).includes(GOLD_SUBSCRIBE_EVENT)) continue;
          const eventType = goldForgeEventType(method.descriptor);
          if (!eventType) continue;
          const callback = staticOnly
            ? event => ownerClass[name](event)
            : event => target[name](event);
          registrations.push(bus.subscribe(eventType, callback, {
            owner,
            priority: method.subscribePriority ?? "NORMAL",
            receiveCanceled: method.receiveCanceled === true
          }));
        }
      }
      constructor = constructor.__goldSuperName
        ? GoldClientRuntime.resolveClass(constructor.__goldSuperName) : null;
    }
    this.#registrations.set(target, Object.freeze(registrations));
    for (const registration of registrations) this.#subscriptions.add(registration);
  }

  unregister(target) {
    const registrations = this.#registrations.get(target);
    if (!registrations) return false;
    for (const registration of registrations) {
      registration.unsubscribe?.();
      this.#subscriptions.delete(registration);
    }
    this.#registrations.delete(target);
    return true;
  }

  isRegistered(target) {
    return this.#registrations.has(target);
  }

  // Drops every registration, including manual:<class> owners created outside a
  // per-mod context, so a full loader stop does not leave live bus listeners.
  clear() {
    let cleared = 0;
    for (const registration of this.#subscriptions) {
      registration.unsubscribe?.();
      cleared++;
    }
    this.#subscriptions.clear();
    this.#registrations = new WeakMap();
    return cleared;
  }

  post(event) {
    const bus = globalThis.goldClientForgeEventBus;
    const eventType = event?.constructor?.__goldClassName;
    if (!bus || typeof bus.post !== "function" || !eventType) {
      return GoldClientRuntime.unsupported(
        "missing-forge-event-bus", "MinecraftForge.EVENT_BUS.post");
    }
    const result = bus.post(eventType, event);
    return result?.event?.isCanceled?.() === true || result?.event?.isCanceled === true;
  }
}

const GoldClientForgeEventBusInstance = new GoldClientForgeEventBus();

// JVM Class objects are singletons. Caching keeps literal identity usable in
// equality checks and lets EVENT_BUS.unregister(SomeClass.class) find its entry.
const goldClientClassLiterals = new Map();

export const GoldClientRuntime = Object.freeze({
  intDiv(a, b) {
    if (b === 0) throw new Error("ArithmeticException: / by zero");
    // JVM IDIV truncates toward zero. Integer.MIN_VALUE / -1 wraps in 32-bit arithmetic.
    if (a === -2147483648 && b === -1) return -2147483648;
    return Math.trunc(a / b);
  },
  intMod(a, b) {
    if (b === 0) throw new Error("ArithmeticException: / by zero");
    // JVM IREM has the dividend's sign; it is not Math.floorMod.
    if (a === -2147483648 && b === -1) return 0;
    return a - Math.trunc(a / b) * b;
  },
  floorDiv(a, b) {
    if (b === 0) throw new Error("ArithmeticException: / by zero");
    if (a === -2147483648 && b === -1) return -2147483648;
    return Math.floor(a / b);
  },
  floorMod(a, b) {
    if (b === 0) throw new Error("ArithmeticException: / by zero");
    if (a === -2147483648 && b === -1) return 0;
    return a - Math.floor(a / b) * b;
  },
  javaEquals(a, b) {
    if (a === null || a === undefined) return a == b;
    if (typeof a.equals === "function") return Boolean(a.equals(b));
    return a === b;
  },
  // Record equals: identity, then a constructor/component comparison matching
  // the java.lang.runtime.ObjectMethods behavior (Float/Double.compare for
  // float components, Objects.equals for references).
  recordEquals(receiver, other, names, ...descriptors) {
    if (receiver === other) return true;
    if (other === null || other === undefined || typeof other !== "object") return false;
    if (receiver === null || receiver === undefined) return false;
    if (receiver.constructor !== other.constructor) return false;
    const components = goldClientRecordComponents(names);
    for (let i = 0; i < components.length; i++) {
      if (!this.recordComponentEquals(
          receiver[components[i]], other[components[i]], descriptors[i])) {
        return false;
      }
    }
    return true;
  },
  recordComponentEquals(a, b, descriptor) {
    const kind = String(descriptor ?? "").charAt(0);
    if (kind === "F") return goldClientFloatCompare(a, b) === 0;
    if (kind === "D") return goldClientDoubleCompare(a, b) === 0;
    if (kind === "J") return this.javaLongEquals(a, b);
    if (kind === "Z" || kind === "B" || kind === "S" || kind === "C" || kind === "I") {
      return Number(a) === Number(b);
    }
    return this.javaEquals(a, b);
  },
  javaLongEquals(a, b) {
    if (typeof a === "bigint" || typeof b === "bigint") {
      try {
        return BigInt(a) === BigInt(b);
      } catch (error) {
        return false;
      }
    }
    return Number(a) === Number(b);
  },
  // Record hashCode: result = 1; result = 31 * result + componentHash(...).
  recordHashCode(receiver, names, ...descriptors) {
    const components = goldClientRecordComponents(names);
    let result = 1;
    for (let i = 0; i < components.length; i++) {
      result = (Math.imul(31, result)
          + this.recordComponentHash(receiver[components[i]], descriptors[i])) | 0;
    }
    return result;
  },
  recordComponentHash(value, descriptor) {
    const kind = String(descriptor ?? "").charAt(0);
    if (kind === "Z") return value ? 1231 : 1237;
    if (kind === "B" || kind === "S" || kind === "C" || kind === "I") return Number(value) | 0;
    if (kind === "J") return this.javaLongHash(value);
    if (kind === "F") return goldClientFloatToIntBits(value);
    if (kind === "D") return goldClientDoubleToLongBitsHash(value);
    if (value === null || value === undefined) return 0;
    if (typeof value.hashCode === "function") return Number(value.hashCode()) | 0;
    return this.javaHashCode(value);
  },
  javaLongHash(value) {
    if (typeof value === "bigint") {
      const bits = BigInt.asUintN(64, value);
      const high = Number((bits >> 32n) & 0xffffffffn) | 0;
      const low = Number(bits & 0xffffffffn) | 0;
      return (high ^ low) | 0;
    }
    const number = Number(value);
    if (!Number.isFinite(number)) return 0;
    const low = number | 0;
    const high = Math.floor(number / 4294967296) | 0;
    return (high ^ low) | 0;
  },
  // Record toString: SimpleName[component=value, ...] using String.valueOf.
  recordToString(receiver, className, names, ...descriptors) {
    const components = goldClientRecordComponents(names);
    const parts = [];
    for (let i = 0; i < components.length; i++) {
      parts.push(components[i] + "="
          + this.recordComponentString(receiver[components[i]], descriptors[i]));
    }
    return className + "[" + parts.join(", ") + "]";
  },
  recordComponentString(value, descriptor) {
    const kind = String(descriptor ?? "").charAt(0);
    if (kind === "C" && typeof value === "number") {
      return String.fromCharCode(value & 0xffff);
    }
    return this.javaString(value);
  },
  compareJava(a, b, comparator = null) {
    if (typeof comparator === "function") return Number(comparator(a, b));
    if (comparator != null && typeof comparator.compare === "function") {
      return Number(comparator.compare(a, b));
    }
    if (a == null || b == null) throw new TypeError("NullPointerException: natural ordering");
    if (typeof a.compareTo === "function") return Number(a.compareTo(b));
    if (typeof a === "number" && typeof b === "number") return a < b ? -1 : a > b ? 1 : 0;
    if (typeof a === "string" && typeof b === "string") return a < b ? -1 : a > b ? 1 : 0;
    if (typeof a === "boolean" && typeof b === "boolean") return a === b ? 0 : a ? 1 : -1;
    throw new TypeError("ClassCastException: values do not implement Java natural ordering");
  },
  javaDeepEquals(a, b) {
    if (a === b) return true;
    if (a == null || b == null) return false;
    const arrayA = Array.isArray(a) || ArrayBuffer.isView(a);
    const arrayB = Array.isArray(b) || ArrayBuffer.isView(b);
    if (arrayA || arrayB) {
      if (!arrayA || !arrayB || a.length !== b.length) return false;
      for (let i = 0; i < a.length; i++) {
        if (!this.javaDeepEquals(a[i], b[i])) return false;
      }
      return true;
    }
    return this.javaEquals(a, b);
  },
  requireNonNull(value, message = "null") {
    if (value === null || value === undefined) throw new TypeError(message);
    return value;
  },
  arrayLength(value) {
    if (value === null || value === undefined) throw new TypeError("NullPointerException: arraylength");
    if (!Array.isArray(value) && typeof value.length !== "number") {
      return this.unsupported("arraylength", "value is not an array");
    }
    return value.length;
  },
  arrayIndex(value, index) {
    if (value === null || value === undefined) throw new TypeError("NullPointerException: array access");
    const i = Number(index);
    if (!Number.isInteger(i) || i < 0 || i >= value.length) {
      throw new RangeError("ArrayIndexOutOfBoundsException: " + index);
    }
    return i;
  },
  arrayGet(value, index) {
    return value[this.arrayIndex(value, index)];
  },
  arraySet(value, index, element) {
    value[this.arrayIndex(value, index)] = element;
    return element;
  },
  newArray(kind, length) {
    const size = Number(length);
    if (!Number.isInteger(size) || size < 0) throw new RangeError("invalid array length");
    const defaults = { boolean: false, byte: 0, char: "\0", short: 0,
      int: 0, long: 0, float: 0, double: 0, Object: null };
    return Array(size).fill(Object.prototype.hasOwnProperty.call(defaults, kind)
      ? defaults[kind] : null);
  },
  newMultiArray(kind, dimensions, totalDimensions = dimensions.length) {
    const sizes = dimensions.map(Number);
    for (const size of sizes) {
      if (!Number.isInteger(size) || size < 0) throw new RangeError("invalid array length");
    }
    const defaults = { boolean: false, byte: 0, char: "\0", short: 0,
      int: 0, long: 0, float: 0, double: 0, Object: null };
    const leafDefault = Object.prototype.hasOwnProperty.call(defaults, kind)
      ? defaults[kind] : null;
    const allocate = depth => {
      const size = sizes[depth];
      if (depth === sizes.length - 1) {
        return Array(size).fill(totalDimensions > sizes.length ? null : leafDefault);
      }
      return Array.from({ length: size }, () => allocate(depth + 1));
    };
    return sizes.length === 0 ? [] : allocate(0);
  },
  unmodifiable(collection) {
    const mutators = new Set([
      "add", "addAt", "addAll", "addAllAt", "remove", "removeAt", "removeAll",
      "retainAll", "removeIf", "replaceAll", "set", "clear", "put", "putAll",
      "putIfAbsent", "compute", "computeIfAbsent", "computeIfPresent", "merge",
      "replace", "replacePair", "removePair", "setProperty", "setTime", "flip",
      "and", "or", "xor", "andNot", "poll", "pollFirst", "pollLast", "push", "pop",
      "addFirst", "addLast", "offer", "offerFirst", "offerLast", "removeFirst", "removeLast"
    ]);
    return new Proxy(collection, {
      get(target, property) {
        const value = Reflect.get(target, property, target);
        if (typeof property === "string" && mutators.has(property) && typeof value === "function") {
          return () => { throw new Error("UnsupportedOperationException: unmodifiable collection"); };
        }
        return typeof value === "function" ? value.bind(target) : value;
      },
      set() { throw new Error("UnsupportedOperationException: unmodifiable collection"); },
      deleteProperty() { throw new Error("UnsupportedOperationException: unmodifiable collection"); }
    });
  },
  javaString(value) {
    if (value === null || value === undefined) return "null";
    if (typeof value === "boolean") return value ? "true" : "false";
    if (typeof value === "object" && value.javaThrowable === true) {
      const name = value.javaClassName ?? value.name ?? "java.lang.Throwable";
      return value.message ? name + ": " + value.message : name;
    }
    if (typeof value === "object") {
      const method = value.toString;
      if (typeof method === "function" && method !== Object.prototype.toString) {
        return String(method.call(value));
      }
    }
    return String(value);
  },
  concat(values) {
    return values.map(value => this.javaString(value)).join("");
  },
  javaHashCode(value) {
    if (value === null || value === undefined) return 0;
    if (typeof value === "number") return Number(value) | 0;
    if (typeof value === "boolean") return value ? 1231 : 1237;
    const text = String(value);
    let hash = 0;
    for (let i = 0; i < text.length; i++) hash = (Math.imul(31, hash) + text.charCodeAt(i)) | 0;
    return hash;
  },
  arraycopy(source, sourceIndex, target, targetIndex, length) {
    if (source == null || target == null) throw new TypeError("null array");
    if (length < 0) throw new RangeError("negative length");
    const values = Array.prototype.slice.call(source, sourceIndex, sourceIndex + length);
    for (let i = 0; i < values.length; i++) target[targetIndex + i] = values[i];
  },
  toInt32(value) {
    return Number(value) | 0;
  },
  toByte(value) {
    const number = Number(value) | 0;
    return (number << 24) >> 24;
  },
  toShort(value) {
    const number = Number(value) | 0;
    return (number << 16) >> 16;
  },
  toChar(value) {
    return (Number(value) | 0) & 0xffff;
  },
  floatToInt(value) {
    const number = Number(value);
    if (Number.isNaN(number)) return 0;
    if (number >= 2147483647) return 2147483647;
    if (number <= -2147483648) return -2147483648;
    return Math.trunc(number);
  },
  floatToLong(value) {
    const number = Number(value);
    if (Number.isNaN(number)) return 0;
    if (number >= 9223372036854775807) return 9223372036854775807;
    if (number <= -9223372036854775808) return -9223372036854775808;
    return Math.trunc(number);
  },
  checkCast(value, className) {
    if (value === null || value === undefined) return value;
    const constructor = this.classes.get(className);
    const known = constructor || GOLD_JAVA_SUPERCLASSES.has(className)
        || className === "java.lang.String" || className === "java.lang.Number"
        || className === "java.lang.Boolean" || className === "java.lang.CharSequence";
    if (known && !this.instanceOf(value, className)) {
      throw new GoldClientJavaThrowable("java.lang.ClassCastException", className);
    }
    return value;
  },
  javaClassName(value) {
    return goldClientJavaClassName(value);
  },
  javaAssignable(actual, target) {
    if (!actual || !target) return false;
    if (target === "java.lang.Object") return true;
    const pending = [actual];
    const visited = new Set();
    while (pending.length) {
      const name = pending.pop();
      if (!name || visited.has(name)) continue;
      visited.add(name);
      if (name === target) return true;
      const translated = this.classes.get(name);
      if (translated) {
        if (translated.__goldSuperName) pending.push(translated.__goldSuperName);
        for (const implemented of translated.__goldInterfaces ?? []) pending.push(implemented);
      } else {
        const parent = GOLD_JAVA_SUPERCLASSES.get(name);
        if (parent) pending.push(parent);
      }
    }
    return false;
  },
  instanceOf(value, className) {
    if (value === null || value === undefined) return false;
    if (className === "java.lang.Object") return true;
    if (className.endsWith("[]")) return Array.isArray(value);
    if (className === "java.lang.String") {
      return typeof value === "string" || value instanceof String;
    }
    if (className === "java.lang.Number") return typeof value === "number";
    if (className === "java.lang.Boolean") return typeof value === "boolean";
    if (className === "java.lang.CharSequence") {
      return typeof value === "string" || value instanceof String;
    }
    const constructor = this.classes.get(className);
    if (constructor && value instanceof constructor) return true;
    const actual = this.javaClassName(value);
    return actual ? this.javaAssignable(actual, className) : false;
  },
  classLiteral(className) {
    const cached = goldClientClassLiterals.get(className);
    if (cached) return cached;
    const runtime = this;
    const literal = Object.freeze({
      __goldClassName: className,
      getName() { return className; },
      getSimpleName() { return className.split(/[.$]/).pop(); },
      isInstance(value) { return runtime.instanceOf(value, className); },
      isAssignableFrom(other) {
        const otherName = other?.__goldClassName ?? other?.name;
        if (otherName === className || className === "java.lang.Object") return true;
        const target = runtime.classes.get(className);
        const source = runtime.classes.get(otherName);
        return Boolean(target && source && source.prototype instanceof target);
      },
      toString() { return (className.endsWith("[]") ? "class " : "class ") + className; }
    });
    goldClientClassLiterals.set(className, literal);
    return literal;
  },
  methodType(descriptor) {
    return Object.freeze({
      descriptor,
      toString() { return descriptor; },
      parameterCount() {
        const args = descriptor.slice(1, descriptor.indexOf(")"));
        return (args.match(/L[^;]+;|\[+[^\[;]+;?|[ZBCSIJFD]/g) ?? []).length;
      }
    });
  },
  classes: new Map(),
  registerClass(name, constructor, staticInitializer = null, metadata = {}) {
    constructor.__goldClassName = name;
    constructor.__goldStaticInitializer = staticInitializer;
    constructor.__goldInitialized = false;
    constructor.__goldSuperName = metadata.superName ?? null;
    constructor.__goldInterfaces = Array.from(metadata.interfaces ?? []);
    constructor.__goldMethods = metadata.methods ?? new Map();
    this.classes.set(name, constructor);
    return constructor;
  },
  ensureInitialized(constructor) {
    if (!constructor || constructor.__goldInitialized) return;
    constructor.__goldInitialized = true;
    if (typeof constructor.__goldStaticInitializer === "function") {
      constructor.__goldStaticInitializer.call(constructor);
    }
  },
  getStaticField(className, fieldName) {
    const adapter = GoldClientMinecraftAdapters.get(className);
    if (adapter?.staticFields
        && Object.prototype.hasOwnProperty.call(adapter.staticFields, fieldName)) {
      return adapter.staticFields[fieldName];
    }
    if (className === "java.util.concurrent.TimeUnit") return GoldClientJavaTimeUnit.named(fieldName);
    if (className === "java.util.Locale") {
      const locales = { ROOT: new GoldClientJavaLocale(), US: new GoldClientJavaLocale("en", "US"),
        UK: new GoldClientJavaLocale("en", "GB"), CANADA: new GoldClientJavaLocale("en", "CA"),
        FRANCE: new GoldClientJavaLocale("fr", "FR"), GERMANY: new GoldClientJavaLocale("de", "DE"),
        JAPAN: new GoldClientJavaLocale("ja", "JP"), CHINA: new GoldClientJavaLocale("zh", "CN") };
      if (Object.prototype.hasOwnProperty.call(locales, fieldName)) return locales[fieldName];
    }
    if (className === "net.minecraftforge.common.MinecraftForge" && fieldName === "EVENT_BUS") {
      return GoldClientForgeEventBusInstance;
    }
    const constructor = this.classes.get(className);
    if (!constructor) {
      const service = this.platformService(className);
      if (service) {
        const bindings = globalThis.window?.goldClientForgeBindings ?? globalThis.goldClientForgeBindings;
        const target = bindings?.[service];
        if (!target || typeof target.get !== "function") {
          return this.unsupported("missing-platform-field-binding", className + "." + fieldName);
        }
        return target.get(className, fieldName);
      }
      return this.unsupported("missing-static-field-class", className);
    }
    this.ensureInitialized(constructor);
    return constructor[fieldName];
  },
  setStaticField(className, fieldName, value) {
    const constructor = this.classes.get(className);
    if (!constructor) {
      const service = this.platformService(className);
      if (service) {
        const bindings = globalThis.window?.goldClientForgeBindings ?? globalThis.goldClientForgeBindings;
        const target = bindings?.[service];
        if (!target || typeof target.set !== "function") {
          return this.unsupported("missing-platform-field-binding", className + "." + fieldName);
        }
        target.set(className, fieldName, value);
        return value;
      }
      return this.unsupported("missing-static-field-class", className);
    }
    this.ensureInitialized(constructor);
    constructor[fieldName] = value;
    return value;
  },
  platformService(owner) {
    if (owner.startsWith("net.minecraft.")) return "minecraft";
    if (owner.startsWith("net.minecraftforge.")) return "forge";
    return null;
  },
  builtinAdapterOwners() {
    return Array.from(GoldClientMinecraftAdapters.keys()).sort();
  },
  forgeEventTypes(eventType) {
    return Object.freeze(goldForgeEventTypes(eventType));
  },
  clearForgeEventBusRegistrations() {
    return GoldClientForgeEventBusInstance.clear();
  },
  invokePlatformStatic(owner, method, ...args) {
    const adapter = GoldClientMinecraftAdapters.get(owner);
    if (adapter?.staticMethods && typeof adapter.staticMethods[method] === "function") {
      return adapter.staticMethods[method](args);
    }
    // The loader knows exactly which mods it started, so Forge's dependency
    // query can be answered without host bindings. This is only active when a
    // generated loader installed its mod set.
    if (owner === "net.minecraftforge.fml.common.Loader" && method === "isModLoaded") {
      const loaded = globalThis.goldClientForgeModIds;
      if (loaded instanceof Set) return loaded.has(String(args[0])) ? 1 : 0;
    }
    const service = this.platformService(owner);
    if (!service) return this.unsupported("unknown-platform-class", owner + "." + method);
    const bindings = globalThis.window?.goldClientForgeBindings ?? globalThis.goldClientForgeBindings;
    const target = bindings?.[service];
    if (!target || typeof target.call !== "function") {
      return this.unsupported("missing-platform-method-binding", owner + "." + method);
    }
    return target.call(owner, method, args);
  },
  resolveClass(name) {
    return this.classes.get(name) ?? null;
  },
  // Constructs a Java throwable without pretending it is an arbitrary host
  // object. Used by the JavaScript translator for `new java.*Exception(...)`.
  javaException(className, ...args) {
    return new GoldClientJavaThrowable(className, ...args);
  },
  create(name, ...args) {
    const adapter = GoldClientMinecraftAdapters.get(name);
    if (adapter?.construct) return adapter.construct(args);
    const constructor = this.classes.get(name);
    if (!constructor) {
      const service = this.platformService(name);
      if (service) {
        const bindings = globalThis.window?.goldClientForgeBindings ?? globalThis.goldClientForgeBindings;
        const target = bindings?.[service];
        if (typeof target?.construct === "function") return target.construct(name, args);
        return this.unsupported("missing-platform-constructor-binding", name);
      }
      return this.unsupported("missing-class-adapter", name);
    }
    return Reflect.construct(constructor, args);
  },
  createJava(name, ...args) {
    switch (name) {
      case "java.util.ArrayList":
      case "java.util.LinkedList":
      case "java.util.Vector":
      case "java.util.Stack":
      case "java.util.ArrayDeque":
      case "java.util.concurrent.ConcurrentLinkedQueue":
      case "java.util.concurrent.CopyOnWriteArrayList":
        return new GoldClientJavaList(Array.from(args[0]?.items ?? args[0] ?? []));
      case "java.util.HashSet":
      case "java.util.LinkedHashSet":
      case "java.util.TreeSet":
        return new GoldClientJavaSet(args[0] ?? []);
      case "java.util.HashMap":
      case "java.util.LinkedHashMap":
      case "java.util.TreeMap":
      case "java.util.IdentityHashMap":
      case "java.util.concurrent.ConcurrentHashMap":
        return new GoldClientJavaMap(args[0] ?? []);
      case "java.lang.StringBuilder":
      case "java.lang.StringBuffer":
        return new GoldClientJavaStringBuilder(args[0] ?? "");
      case "java.util.Optional":
        return new GoldClientJavaOptional(args[0]);
      case "java.util.Date":
        return new GoldClientJavaDate(args.length ? args[0] : Date.now());
      case "java.util.UUID":
        return args.length ? GoldClientJavaUuid.fromString(args[0]) : GoldClientJavaUuid.randomUUID();
      case "java.util.PriorityQueue":
        return new GoldClientJavaPriorityQueue(args[0] ?? [], args[1] ?? null);
      case "java.util.Locale":
        return new GoldClientJavaLocale(args[0] ?? "", args[1] ?? "", args[2] ?? "");
      case "java.util.BitSet":
        return new GoldClientJavaBitSet();
      case "java.util.Random":
      case "java.util.SplittableRandom":
        return new GoldClientJavaRandom(args.length ? args[0] : Date.now());
      case "java.util.concurrent.TimeUnit":
        return GoldClientJavaTimeUnit.named(args[0] ?? "SECONDS");
      case "java.util.Properties":
        return new GoldClientJavaProperties(args[0] ?? []);
      case "java.util.concurrent.atomic.AtomicInteger":
      case "java.util.concurrent.atomic.AtomicLong":
      case "java.util.concurrent.atomic.AtomicBoolean":
      case "java.util.concurrent.atomic.AtomicReference":
        return new GoldClientJavaAtomic(args.length ? args[0] : 0);
      case "java.util.regex.Pattern":
        return new GoldClientJavaPattern(args[0] ?? "");
      case "java.lang.String":
        return String(args[0] ?? "");
      default:
        return this.unsupported("missing-java-class-adapter", name);
    }
  },
  invokeJavaStatic(owner, method, ...args) {
    if (owner === "java.lang.reflect.Array") {
      if (method === "getLength") return this.arrayLength(args[0]);
      if (method === "get") return this.arrayGet(args[0], args[1]);
      if (method === "set") return this.arraySet(args[0], args[1], args[2]);
      if (method === "getBoolean" || method === "getByte" || method === "getChar"
          || method === "getShort" || method === "getInt" || method === "getLong"
          || method === "getFloat" || method === "getDouble") {
        return this.arrayGet(args[0], args[1]);
      }
      if (method === "setBoolean" || method === "setByte" || method === "setChar"
          || method === "setShort" || method === "setInt" || method === "setLong"
          || method === "setFloat" || method === "setDouble") {
        return this.arraySet(args[0], args[1], args[2]);
      }
      if (method === "newInstance") {
        const type = args[0]?.__goldClassName ?? "java.lang.Object";
        const kind = ({ boolean: "boolean", byte: "byte", char: "char", short: "short",
          int: "int", long: "long", float: "float", double: "double" })[type] ?? "Object";
        const dimensions = Array.isArray(args[1]) ? args[1] : [args[1]];
        if (dimensions.length === 1) return this.newArray(kind, dimensions[0]);
        return this.newMultiArray(kind, dimensions, dimensions.length);
      }
    }
    if (owner === "java.lang.System") {
      if (method === "identityHashCode") return this.javaHashCode(args[0]);
      if (method === "currentTimeMillis") return Date.now();
      if (method === "nanoTime") return Date.now() * 1000000;
      if (method === "lineSeparator") return "\n";
      if (method === "getProperty") {
        const properties = { "line.separator": "\n", "file.separator": "/", "path.separator": ":" };
        return properties[args[0]] ?? args[1] ?? null;
      }
      if (method === "gc") return;
    }
    if (owner === "java.util.Objects") {
      if (method === "equals") return this.javaEquals(args[0], args[1]);
      if (method === "deepEquals") return this.javaDeepEquals(args[0], args[1]);
      if (method === "hashCode") return this.javaHashCode(args[0]);
      if (method === "hash") return args.reduce((hash, value) =>
        (Math.imul(hash, 31) + this.javaHashCode(value)) | 0, 1);
      if (method === "toString") return args[0] == null ? args[1] : this.javaString(args[0]);
      if (method === "isNull") return args[0] == null;
      if (method === "nonNull") return args[0] != null;
      if (method === "requireNonNull") {
        return this.requireNonNull(args[0], args[1] ?? "null");
      }
      if (method === "compare") {
        const a = args[0], b = args[1];
        return a < b ? -1 : a > b ? 1 : 0;
      }
    }
    if (owner === "java.lang.Class" && method === "forName") {
      return this.classLiteral(String(args[0]).replace(/\//g, "."));
    }
    if (owner === "java.lang.String") {
      if (method === "valueOf") return args[0] == null ? "null" : this.javaString(args[0]);
      if (method === "join") {
        const delimiter = String(args[0]);
        const values = args.length > 2 ? args.slice(1) : Array.from(args[1] ?? []);
        return values.map(value => value == null ? "" : this.javaString(value)).join(delimiter);
      }
      if (method === "format") {
        let index = 1;
        return String(args[0]).replace(/%([sdif%])/g, (match, specifier) => {
          if (specifier === "%") return "%";
          const value = args[index++];
          if (specifier === "d" || specifier === "i") return String(Number(value) | 0);
          if (specifier === "f") return String(Number(value));
          return this.javaString(value);
        });
      }
    }
    if (owner === "java.lang.Integer" || owner === "java.lang.Short"
        || owner === "java.lang.Byte") {
      if (method === "parseInt" || method === "parseShort" || method === "parseByte") {
        const radix = args[1] == null ? 10 : Number(args[1]);
        const value = Number.parseInt(String(args[0]), radix);
        if (!Number.isInteger(value)) throw new TypeError("NumberFormatException: " + args[0]);
        if (method === "parseShort" && (value < -32768 || value > 32767)) throw new RangeError("NumberFormatException");
        if (method === "parseByte" && (value < -128 || value > 127)) throw new RangeError("NumberFormatException");
        return value | 0;
      }
      if (method === "valueOf") return Number(args[0]) | 0;
      if (method === "toString") return String(Number(args[0]) | 0);
      if (method === "compare") return Math.sign((Number(args[0]) | 0) - (Number(args[1]) | 0));
      if (method === "sum") return (Number(args[0]) + Number(args[1])) | 0;
      if (method === "max") return Math.max(Number(args[0]) | 0, Number(args[1]) | 0);
      if (method === "min") return Math.min(Number(args[0]) | 0, Number(args[1]) | 0);
      if (method === "bitCount") {
        let value = Number(args[0]) | 0, count = 0;
        while (value) { count += value & 1; value >>>= 1; }
        return count;
      }
      if (method === "numberOfLeadingZeros") {
        const value = Number(args[0]) | 0;
        return value === 0 ? 32 : Math.clz32(value);
      }
      if (method === "numberOfTrailingZeros") {
        const value = Number(args[0]) | 0;
        return value === 0 ? 32 : 31 - Math.clz32(value & -value);
      }
    }
    if (owner === "java.lang.Boolean") {
      if (method === "parseBoolean") return String(args[0]).toLowerCase() === "true";
      if (method === "valueOf") return typeof args[0] === "boolean"
        ? args[0] : String(args[0]).toLowerCase() === "true";
      if (method === "toString") return args[0] ? "true" : "false";
      if (method === "compare") return args[0] === args[1] ? 0 : args[0] ? 1 : -1;
    }
    if (owner === "java.lang.Math") {
      if (method === "floorDiv") return this.floorDiv(args[0], args[1]);
      if (method === "floorMod") return this.floorMod(args[0], args[1]);
      if (method === "addExact") {
        const value = Number(args[0]) + Number(args[1]);
        if (!Number.isSafeInteger(value) || value < -2147483648 || value > 2147483647) throw new RangeError("ArithmeticException: integer overflow");
        return value;
      }
      if (method === "subtractExact") {
        const value = Number(args[0]) - Number(args[1]);
        if (!Number.isSafeInteger(value) || value < -2147483648 || value > 2147483647) throw new RangeError("ArithmeticException: integer overflow");
        return value;
      }
      if (method === "multiplyExact") {
        const value = Number(args[0]) * Number(args[1]);
        if (!Number.isSafeInteger(value) || value < -2147483648 || value > 2147483647) throw new RangeError("ArithmeticException: integer overflow");
        return value;
      }
      if (method === "incrementExact" || method === "decrementExact" || method === "negateExact") {
        const value = method === "incrementExact" ? Number(args[0]) + 1
          : method === "decrementExact" ? Number(args[0]) - 1 : -Number(args[0]);
        if (!Number.isSafeInteger(value) || value < -2147483648 || value > 2147483647) throw new RangeError("ArithmeticException: integer overflow");
        return value;
      }
      if (method === "toIntExact") {
        const value = Number(args[0]);
        if (!Number.isInteger(value) || value < -2147483648 || value > 2147483647) throw new RangeError("ArithmeticException: integer overflow");
        return value;
      }
      if (method === "signum") return Math.sign(args[0]);
      if (method === "round") return Math.floor(Number(args[0]) + 0.5);
      if (method === "min") return Math.min(...args);
      if (method === "max") return Math.max(...args);
      if (method === "copySign") return Math.abs(args[0]) * (args[1] < 0 || Object.is(args[1], -0) ? -1 : 1);
      if (method === "nextUp") return args[0] === Infinity ? Infinity : Number(args[0]) + Number.EPSILON;
      if (typeof Math[method] === "function") return Math[method](...args);
    }
    if (owner === "java.util.Arrays") {
      if (method === "asList") return new GoldClientJavaList(args[0] ?? args);
      if (method === "copyOf") {
        const source = Array.from(args[0] ?? []);
        const length = Math.max(0, Number(args[1]) | 0);
        return Array.from({ length }, (_, index) => index < source.length ? source[index] : null);
      }
      if (method === "copyOfRange") {
        const source = Array.from(args[0] ?? []);
        const from = Number(args[1]) | 0;
        const to = Number(args[2]) | 0;
        if (from < 0 || to < from) throw new RangeError("ArrayIndexOutOfBoundsException");
        return Array.from({ length: to - from }, (_, index) => source[from + index] ?? null);
      }
      if (method === "equals" || method === "deepEquals") {
        const a = args[0], b = args[1];
        if (a == null || b == null) return a == b;
        const arrayA = Array.isArray(a) || ArrayBuffer.isView(a);
        const arrayB = Array.isArray(b) || ArrayBuffer.isView(b);
        if (!arrayA || !arrayB || a.length !== b.length) return false;
        return method === "deepEquals"
          ? this.javaDeepEquals(a, b)
          : Array.from(a).every((value, index) => this.javaEquals(value, b[index]));
      }
      if (method === "hashCode") {
        return Array.from(args[0] ?? []).reduce((hash, value) =>
          (Math.imul(hash, 31) + this.javaHashCode(value)) | 0, 1);
      }
      if (method === "fill") {
        const array = args[0];
        if (args.length === 2) array.fill(args[1]);
        else array.fill(args[3], args[1], args[2]);
        return;
      }
      if (method === "sort") {
        const array = args[0];
        const comparator = args[1] ?? null;
        array.sort((a, b) => this.compareJava(a, b, comparator));
        return;
      }
      if (method === "binarySearch") {
        const array = args[0], target = args[1];
        const comparator = args[2] ?? null;
        let low = 0, high = array.length - 1;
        while (low <= high) {
          const mid = (low + high) >>> 1;
          const compare = this.compareJava(array[mid], target, comparator);
          if (compare < 0) low = mid + 1;
          else if (compare > 0) high = mid - 1;
          else return mid;
        }
        return -(low + 1);
      }
      if (method === "toString") return "[" + Array.from(args[0] ?? []).map(value => value == null ? "null" : String(value)).join(", ") + "]";
    }
    if (owner === "java.util.Comparator") {
      if (method === "naturalOrder") return (a, b) => a < b ? -1 : a > b ? 1 : 0;
      if (method === "reverseOrder") return (a, b) => a > b ? -1 : a < b ? 1 : 0;
      if (method === "comparing") {
        const key = args[0];
        return (a, b) => {
          const av = typeof key === "function" ? key(a) : a?.[key];
          const bv = typeof key === "function" ? key(b) : b?.[key];
          return av < bv ? -1 : av > bv ? 1 : 0;
        };
      }
    }
    if (owner === "java.util.Collections") {
      if (method === "emptyList") return new GoldClientJavaList();
      if (method === "emptySet") return new GoldClientJavaSet();
      if (method === "emptyMap") return new GoldClientJavaMap();
      if (method === "nCopies") {
        const count = Number(args[0]);
        if (!Number.isInteger(count) || count < 0) throw new RangeError("IllegalArgumentException: negative copies");
        return this.unmodifiable(new GoldClientJavaList(Array(count).fill(args[1])));
      }
      if (method === "copy") {
        const destination = args[0]?.items ?? args[0];
        const source = Array.from(args[1]?.items ?? args[1] ?? []);
        if (destination == null) throw new TypeError("NullPointerException: destination");
        if (destination.length < source.length) throw new RangeError("IndexOutOfBoundsException: destination is too small");
        for (let i = 0; i < source.length; i++) destination[i] = source[i];
        return;
      }
      if (method === "swap") {
        const values = args[0]?.items ?? args[0];
        if (values == null) throw new TypeError("NullPointerException: list");
        const i = Number(args[1]), j = Number(args[2]);
        if (!Number.isInteger(i) || !Number.isInteger(j) || i < 0 || j < 0 || i >= values.length || j >= values.length) {
          throw new RangeError("IndexOutOfBoundsException");
        }
        [values[i], values[j]] = [values[j], values[i]];
        return;
      }
      if (method === "binarySearch") {
        const values = Array.from(args[0]?.items ?? args[0] ?? []);
        const target = args[1], comparator = args[2] ?? null;
        let low = 0, high = values.length - 1;
        while (low <= high) {
          const mid = (low + high) >>> 1;
          const compare = this.compareJava(values[mid], target, comparator);
          if (compare < 0) low = mid + 1;
          else if (compare > 0) high = mid - 1;
          else return mid;
        }
        return -(low + 1);
      }
      if (method === "singletonList") return new GoldClientJavaList([args[0]]);
      if (method === "singleton") return new GoldClientJavaSet([args[0]]);
      if (method === "singletonMap") return new GoldClientJavaMap([[args[0], args[1]]]);
      if (["unmodifiableList", "unmodifiableSet", "unmodifiableMap"].includes(method)) return this.unmodifiable(args[0]);
      if (["synchronizedList", "synchronizedSet", "synchronizedMap"].includes(method)) return args[0];
      if (method === "addAll") {
        const collection = args[0];
        const values = args.slice(1);
        if (collection?.add) { let changed = false; for (const value of values) changed = collection.add(value) || changed; return changed; }
        if (Array.isArray(collection)) { collection.push(...values); return values.length > 0; }
      }
      if (method === "reverse") { if (args[0]?.items) args[0].items.reverse(); else args[0]?.reverse?.(); return; }
      if (method === "sort") {
        const collection = args[0]?.items ?? args[0];
        if (collection == null) throw new TypeError("NullPointerException: list");
        collection.sort((a, b) => this.compareJava(a, b, args[1] ?? null));
        return;
      }
      if (method === "frequency") {
        const values = args[0]?.items ?? args[0] ?? [];
        return Array.from(values).filter(value => this.javaEquals(value, args[1])).length;
      }
      if (method === "disjoint") {
        const left = Array.from(args[0]?.items ?? args[0] ?? []);
        const right = Array.from(args[1]?.items ?? args[1] ?? []);
        return !left.some(value => right.some(candidate => this.javaEquals(value, candidate)));
      }
      if (method === "min" || method === "max") {
        const values = Array.from(args[0]?.items ?? args[0] ?? []);
        if (!values.length) throw new Error("NoSuchElementException");
        const comparator = args[1] ?? null;
        return values.reduce((best, value) => {
          const compare = this.compareJava(value, best, comparator);
          return method === "min" ? (compare < 0 ? value : best) : (compare > 0 ? value : best);
        });
      }
      if (method === "replaceAll") {
        const collection = args[0], oldValue = args[1], newValue = args[2];
        if (collection?.items) collection.items = collection.items.map(value => this.javaEquals(value, oldValue) ? newValue : value);
        else if (Array.isArray(collection)) for (let i = 0; i < collection.length; i++) if (this.javaEquals(collection[i], oldValue)) collection[i] = newValue;
        return;
      }
      if (method === "rotate") {
        const collection = args[0]?.items ?? args[0];
        if (!collection?.length) return;
        const distance = ((Number(args[1]) % collection.length) + collection.length) % collection.length;
        if (distance) collection.unshift(...collection.splice(collection.length - distance, distance));
        return;
      }
      if (method === "shuffle") {
        const collection = args[0]?.items ?? args[0];
        if (collection == null || typeof collection.length !== "number") {
          throw new TypeError("NullPointerException: collection");
        }
        // java.util.Collections uses the supplied Random (when present) and
        // walks backwards, selecting each swap with nextInt(i + 1).
        const random = args[1] ?? new GoldClientJavaRandom();
        for (let i = collection.length; i > 1; i--) {
          const j = random.nextInt(i);
          [collection[i - 1], collection[j]] = [collection[j], collection[i - 1]];
        }
        return;
      }
    }
    if (owner === "java.util.BitSet" && method === "valueOf") return GoldClientJavaBitSet.valueOf(args[0]);
    if (owner === "java.util.Locale") {
      if (method === "forLanguageTag") return GoldClientJavaLocale.forLanguageTag(args[0]);
      if (method === "getDefault") return GoldClientJavaLocale.getDefault();
      if (method === "setDefault") return GoldClientJavaLocale.setDefault(args[0]);
      if (method === "of") return new GoldClientJavaLocale(args[0] ?? "", args[1] ?? "", args[2] ?? "");
    }
    if (owner === "java.util.UUID") {
      if (method === "randomUUID") return GoldClientJavaUuid.randomUUID();
      if (method === "fromString") return GoldClientJavaUuid.fromString(args[0]);
    }
    if (owner === "java.util.Date" && method === "from") return new GoldClientJavaDate(args[0]);
    if (owner === "java.lang.Character") {
      const value = String(args[0] ?? "").charAt(0);
      if (method === "isDigit") return /^\p{N}$/u.test(value);
      if (method === "isLetter") return /^\p{L}$/u.test(value);
      if (method === "isLetterOrDigit") return /^[\p{L}\p{N}]$/u.test(value);
      if (method === "isWhitespace" || method === "isSpaceChar") return /^\s$/u.test(value);
      if (method === "isUpperCase") return value.toUpperCase() === value && value.toLowerCase() !== value;
      if (method === "isLowerCase") return value.toLowerCase() === value && value.toUpperCase() !== value;
      if (method === "toUpperCase") return value.toUpperCase().charAt(0);
      if (method === "toLowerCase") return value.toLowerCase().charAt(0);
      if (method === "getNumericValue") return Number.isNaN(Number(value)) ? -1 : Number(value);
      if (method === "charCount") return String.fromCodePoint(Number(args[0])).length;
      if (method === "toString") return value;
      if (method === "compare") return String(args[0]).localeCompare(String(args[1]));
    }
    if (["java.lang.Byte", "java.lang.Short", "java.lang.Float", "java.lang.Double", "java.lang.Number"].includes(owner)) {
      if (method === "parseByte" || method === "parseShort" || method === "parseInt" || method === "parseLong") return Number.parseInt(args[0], args[1] ?? 10);
      if (method === "parseFloat" || method === "parseDouble" || method === "valueOf") return Number(args[0]);
      if (method === "toString") return String(args[0]);
      if (method === "isNaN") return Number.isNaN(Number(args[0]));
      if (method === "isInfinite") return !Number.isFinite(Number(args[0]));
      if (method === "isFinite") return Number.isFinite(Number(args[0]));
      if (method === "compare") return Math.sign(Number(args[0]) - Number(args[1]));
    }
    if (owner === "java.util.stream.Stream") {
      if (method === "empty") return new GoldClientJavaStream();
      if (method === "of") return new GoldClientJavaStream(args.length === 1 && Array.isArray(args[0]) ? args[0] : args);
      if (method === "ofNullable") return new GoldClientJavaStream(args[0] == null ? [] : [args[0]]);
      if (method === "concat") return new GoldClientJavaStream([...(args[0]?.values ?? args[0] ?? []), ...(args[1]?.values ?? args[1] ?? [])]);
      if (method === "iterate") {
        const values = [];
        let value = args[0];
        const predicate = args[2];
        for (let i = 0; i < 10000 && (!predicate || predicate(value)); i++) {
          values.push(value);
          value = args[1](value);
        }
        return new GoldClientJavaStream(values);
      }
      if (method === "generate") return new GoldClientJavaStream(Array.from({ length: 1000 }, () => args[0]()));
    }
    if (owner === "java.util.stream.Collectors") {
      if (method === "toList") return { kind: "list" };
      if (method === "toSet") return { kind: "set" };
      if (method === "joining") return { kind: "joining", delimiter: args[0] ?? "" };
      if (method === "counting") return { kind: "counting" };
      if (method === "toMap") return { kind: "toMap", keyMapper: args[0], valueMapper: args[1], mergeFunction: args[2] };
    }
    if (owner === "java.util.regex.Pattern") {
      if (method === "compile") return GoldClientJavaPattern.compile(args[0], args[1] ?? "");
      if (method === "matches") return GoldClientJavaPattern.matches(args[0], args[1]);
      if (method === "quote") return GoldClientJavaPattern.quote(args[0]);
    }
    if (owner === "java.util.Optional") {
      if (method === "empty") return GoldClientJavaOptional.empty();
      if (method === "of") return GoldClientJavaOptional.of(args[0]);
      if (method === "ofNullable") return GoldClientJavaOptional.ofNullable(args[0]);
    }
    if (owner === "java.util.Objects") {
      if (method === "equals") return Object.is(args[0], args[1]);
      if (method === "deepEquals") return JSON.stringify(args[0]) === JSON.stringify(args[1]);
      if (method === "isNull") return args[0] == null;
      if (method === "nonNull") return args[0] != null;
      if (method === "hash") return args.reduce((hash, value) => (Math.imul(hash, 31) + this.javaHashCode(value)) | 0, 1);
      if (method === "toString") return args[0] == null ? (args[1] ?? "null") : String(args[0]);
      if (method === "requireNonNull") return this.requireNonNull(args[0], args[1]);
      if (method === "requireNonNullElse") return args[0] == null ? this.requireNonNull(args[1]) : args[0];
      if (method === "requireNonNullElseGet") return args[0] == null ? this.requireNonNull(args[1]()) : args[0];
      if (method === "compare") return args[0] === args[1] ? 0 : args[0] < args[1] ? -1 : 1;
    }
    if (owner === "java.lang.Integer" || owner === "java.lang.Long") {
      if (method === "parseInt" || method === "parseLong" || method === "valueOf") return Number.parseInt(args[0], args[1] ?? 10);
      if (method === "decode") {
        const value = String(args[0]);
        const sign = value.startsWith("-") ? -1 : 1;
        const unsigned = value.replace(/^[+-]/, "");
        if (/^0x/i.test(unsigned)) return sign * Number.parseInt(unsigned.slice(2), 16);
        if (unsigned.startsWith("#")) return sign * Number.parseInt(unsigned.slice(1), 16);
        if (/^0[0-7]+$/.test(unsigned)) return sign * Number.parseInt(unsigned.slice(1), 8);
        return sign * Number.parseInt(unsigned, 10);
      }
      if (method === "toString") return String(args[0]);
      if (method === "toHexString") return (Number(args[0]) >>> 0).toString(16);
      if (method === "toBinaryString") return (Number(args[0]) >>> 0).toString(2);
      if (method === "toOctalString") return (Number(args[0]) >>> 0).toString(8);
      if (method === "compare") return Math.sign(args[0] - args[1]);
      if (method === "sum") return (Number(args[0]) + Number(args[1])) | 0;
      if (method === "max") return Math.max(args[0], args[1]);
      if (method === "min") return Math.min(args[0], args[1]);
      if (method === "bitCount") {
        let value = Number(args[0]) >>> 0, count = 0;
        while (value) { value &= value - 1; count++; }
        return count;
      }
      if (method === "highestOneBit") {
        const value = Number(args[0]) | 0;
        if (!value) return 0;
        return (1 << (31 - Math.clz32(value >>> 0))) | 0;
      }
      if (method === "lowestOneBit") return (Number(args[0]) | 0) & -(Number(args[0]) | 0);
      if (method === "numberOfLeadingZeros") return Math.clz32(Number(args[0]) >>> 0);
      if (method === "numberOfTrailingZeros") {
        const value = Number(args[0]) >>> 0;
        return value === 0 ? 32 : 31 - Math.clz32(value & -value);
      }
    }
    if (owner === "java.lang.Boolean") {
      if (method === "parseBoolean" || method === "valueOf") return String(args[0]).toLowerCase() === "true";
      if (method === "toString") return String(Boolean(args[0]));
    }
    if (owner === "java.lang.String") {
      if (method === "valueOf") return this.javaString(args[0]);
      if (method === "format") {
        const formatIndex = typeof args[0] === "string" ? 0 : 1;
        const format = String(args[formatIndex] ?? "");
        const values = args.slice(formatIndex + 1);
        let valueIndex = 0;
        return format.replace(/%%|%0?(\d+)?(?:\.(\d+))?([sdif])/g,
          (token, width, precision, type) => {
            if (token === "%%") return "%";
            const value = values[valueIndex++];
            let rendered;
            if (type === "d" || type === "i") rendered = String(Math.trunc(Number(value)));
            else if (type === "f") rendered = Number(value).toFixed(precision === undefined ? 6 : Number(precision));
            else rendered = this.javaString(value);
            if (width) rendered = rendered.padStart(Number(width), token.startsWith("%0") ? "0" : " ");
            return rendered;
          });
      }
    }
    if (owner === "java.lang.System") {
      if (method === "currentTimeMillis") return Date.now();
      if (method === "nanoTime") return Math.floor(performance.now() * 1e6);
      if (method === "identityHashCode") return this.javaHashCode(args[0]);
      if (method === "arraycopy") return this.arraycopy(...args);
      if (method === "getProperties") return new GoldClientJavaProperties([
        ["line.separator", "\n"], ["file.separator", "/"], ["path.separator", ":"],
        ["os.name", "Eaglercraft"], ["java.version", "translated-runtime"]
      ]);
      if (method === "getProperty") {
        const properties = { "line.separator": "\n", "file.separator": "/", "path.separator": ":", "os.name": "Eaglercraft", "java.version": "translated-runtime" };
        return Object.prototype.hasOwnProperty.call(properties, args[0]) ? properties[args[0]] : (args[1] ?? null);
      }
      if (method === "getenv") return args.length ? (args[1] ?? null) : Object.freeze({});
      if (method === "lineSeparator") return "\n";
    }
    const translated = this.classes.get(owner);
    if (translated && typeof translated[method] === "function") return translated[method](...args);
    return this.unsupported("missing-java-static-adapter", owner + "." + method);
  },
  invokeStaticClass(className, methodName, ...args) {
    const constructor = this.classes.get(className);
    if (!constructor) return this.unsupported("missing-static-class", className);
    this.ensureInitialized(constructor);
    const method = constructor[methodName];
    if (typeof method !== "function") {
      return this.unsupported("missing-static-method", className + "." + methodName);
    }
    return method.apply(constructor, args);
  },
  invokeInstance(receiver, methodName, ...args) {
    if (receiver === null || receiver === undefined) {
      throw new TypeError("NullPointerException: " + methodName);
    }
    if (methodName === "equals") return this.javaEquals(receiver, args[0]);
    if (methodName === "hashCode") return this.javaHashCode(receiver);
    if (methodName === "toString") return this.javaString(receiver);
    if (methodName === "getClass") {
      if (Array.isArray(receiver)) return this.classLiteral("java.lang.Object[]");
      return this.classLiteral(
        this.javaClassName(receiver) ?? receiver?.constructor?.__goldClassName ?? "java.lang.Object");
    }
    if (receiver.javaThrowable === true || receiver instanceof Error) {
      switch (methodName) {
        case "getMessage":
        case "getLocalizedMessage":
          return receiver.message ?? null;
        case "getCause":
          return receiver.cause ?? null;
        case "initCause":
          if (receiver.cause != null) {
            throw new GoldClientJavaThrowable("java.lang.IllegalStateException", "Can't overwrite cause");
          }
          receiver.cause = args[0];
          return receiver;
        case "addSuppressed":
          if (args[0] === receiver) {
            throw new GoldClientJavaThrowable("java.lang.IllegalArgumentException", "Self-suppression not permitted");
          }
          (receiver.suppressedExceptions ??= []).push(args[0]);
          return;
        case "getSuppressed":
          return (receiver.suppressedExceptions ?? []).slice();
        case "getStackTrace":
          return (receiver.javaStackTrace ?? []).slice();
        case "setStackTrace":
          receiver.javaStackTrace = Array.from(args[0] ?? []);
          return;
        case "fillInStackTrace":
          return receiver;
        case "printStackTrace":
          if (typeof console !== "undefined") console.error(this.javaString(receiver));
          return;
      }
    }
    if (Array.isArray(receiver) && methodName === "clone") return receiver.slice();
    if (methodName === "compareTo") {
      const a = String(receiver), b = String(args[0]);
      return a < b ? -1 : a > b ? 1 : 0;
    }
    if (typeof receiver === "string" || receiver instanceof String) {
      const value = String(receiver);
      switch (methodName) {
        case "length": return value.length;
        case "isEmpty": return value.length === 0;
        case "charAt":
          if (args[0] < 0 || args[0] >= value.length) throw new RangeError("StringIndexOutOfBoundsException");
          return value.charAt(args[0]);
        case "substring": {
          const start = Number(args[0]);
          const end = args.length === 1 ? value.length : Number(args[1]);
          if (!Number.isInteger(start) || !Number.isInteger(end)
              || start < 0 || end < start || end > value.length) {
            throw new RangeError("StringIndexOutOfBoundsException");
          }
          return value.substring(start, end);
        }
        case "subSequence": {
          const start = Number(args[0]), end = Number(args[1]);
          if (start < 0 || end < start || end > value.length) {
            throw new RangeError("StringIndexOutOfBoundsException");
          }
          return value.substring(start, end);
        }
        case "contains": return value.includes(String(args[0]));
        case "startsWith": return value.startsWith(String(args[0]), args[1] ?? 0);
        case "endsWith": return value.endsWith(String(args[0]));
        case "indexOf": return value.indexOf(String(args[0]), args[1] ?? 0);
        case "lastIndexOf": return value.lastIndexOf(String(args[0]), args[1] ?? value.length);
        case "toLowerCase": return value.toLowerCase();
        case "toUpperCase": return value.toUpperCase();
        case "trim": return value.trim();
        case "strip": return value.trim();
        case "stripLeading": return value.trimStart();
        case "stripTrailing": return value.trimEnd();
        case "equalsIgnoreCase": return value.toLowerCase() === String(args[0]).toLowerCase();
        case "compareToIgnoreCase": {
          const a = value.toLowerCase(), b = String(args[0]).toLowerCase();
          return a < b ? -1 : a > b ? 1 : 0;
        }
        case "matches": return new RegExp("^(?:" + args[0] + ")$").test(value);
        case "replace": return value.split(String(args[0])).join(String(args[1]));
        case "replaceAll": return value.replace(new RegExp(args[0], "g"), String(args[1]));
        case "replaceFirst": return value.replace(new RegExp(args[0]), String(args[1]));
        case "split": return value.split(new RegExp(args[0]), args[1] === 0 ? undefined : args[1]);
        case "toCharArray": return Array.from(value);
        case "getBytes": return Array.from(new TextEncoder().encode(value));
        case "repeat": {
          const count = Number(args[0]);
          if (!Number.isInteger(count) || count < 0) throw new RangeError("IllegalArgumentException");
          return value.repeat(count);
        }
        case "isBlank": return value.trim().length === 0;
        case "codePointAt": return value.codePointAt(args[0]);
      }
    }
    if (typeof receiver === "number" || typeof receiver === "boolean") {
      if (methodName === "intValue" || methodName === "longValue" || methodName === "shortValue" || methodName === "byteValue") return Number(receiver) | 0;
      if (methodName === "doubleValue" || methodName === "floatValue") return Number(receiver);
      if (methodName === "booleanValue") return Boolean(receiver);
    }
    const method = receiver[methodName];
    if (typeof method !== "function") {
      return this.unsupported("missing-instance-method", methodName + " on " + String(receiver));
    }
    return method.apply(receiver, args);
  },
  descriptorScore(descriptor, args) {
    if (typeof descriptor !== "string" || descriptor[0] !== "(") return -1;
    const parameters = [];
    let index = 1;
    while (index < descriptor.length && descriptor[index] !== ")") {
      const start = index;
      while (descriptor[index] === "[") index++;
      if (descriptor[index] === "L") {
        const end = descriptor.indexOf(";", index);
        if (end < 0) return -1;
        index = end + 1;
      } else {
        if (!"ZBCSIJFD".includes(descriptor[index] ?? "")) return -1;
        index++;
      }
      parameters.push(descriptor.slice(start, index));
    }
    if (descriptor[index] !== ")" || parameters.length !== args.length) return -1;
    let score = 0;
    for (let i = 0; i < parameters.length; i++) {
      const type = parameters[i];
      const value = args[i];
      const reference = type[0] === "L" || type[0] === "[";
      if (value == null) {
        if (!reference) return -1;
        score += 1;
        continue;
      }
      if (type[0] === "[") {
        if (!Array.isArray(value) && !ArrayBuffer.isView(value)) return -1;
        score += 8;
        continue;
      }
      if (type[0] === "L") {
        const className = type.slice(1, -1);
        if (className === "java/lang/String") {
          if (typeof value !== "string") return -1;
          score += 8;
        } else if (className === "java/lang/Object") {
          score += 2;
        } else {
          const actualName = value?.constructor?.__goldClassName;
          if (actualName === className.split("/").join(".")) score += 10;
          else if (typeof value === "object") score += 2;
          else return -1;
        }
        continue;
      }
      if (type === "Z") {
        if (typeof value !== "boolean") return -1;
        score += 8;
      } else if (type === "C") {
        if (typeof value === "string" && value.length === 1) score += 8;
        else if (typeof value === "number") score += 3;
        else return -1;
      } else if ("BSIJFD".includes(type)) {
        if (typeof value !== "number") return -1;
        score += 6;
      } else {
        return -1;
      }
    }
    return score;
  },
  bestMethod(candidates, args, isStatic) {
    let best = null;
    let bestScore = -1;
    for (const method of candidates) {
      if (method.isAbstract || method.isStatic !== isStatic
          || method.arity !== args.length) continue;
      const score = this.descriptorScore(method.descriptor, args);
      if (score > bestScore) {
        best = method;
        bestScore = score;
      }
    }
    return best;
  },
  resolveInterfaceMethod(className, name, args, isStatic, visited = new Set()) {
    if (!className || visited.has(className)) return null;
    visited.add(className);
    const current = this.classes.get(className);
    if (!current) return null;
    const candidates = current.__goldMethods?.get(name) ?? [];
    const target = this.bestMethod(candidates, args, isStatic);
    if (target) return target;
    for (const parentInterface of current.__goldInterfaces ?? []) {
      const inherited = this.resolveInterfaceMethod(
        parentInterface, name, args, isStatic, visited);
      if (inherited) return inherited;
    }
    return null;
  },
  resolveClassMethod(className, name, args, isStatic) {
    let currentName = className;
    const visitedClasses = new Set();
    const interfaces = [];
    while (currentName && !visitedClasses.has(currentName)) {
      visitedClasses.add(currentName);
      const current = this.classes.get(currentName);
      if (!current) break;
      interfaces.push(...(current.__goldInterfaces ?? []));
      const candidates = current.__goldMethods?.get(name) ?? [];
      const target = this.bestMethod(candidates, args, isStatic);
      if (target) return target;
      currentName = current.__goldSuperName;
    }
    for (const interfaceName of interfaces) {
      const target = this.resolveInterfaceMethod(interfaceName, name, args, isStatic);
      if (target) return target;
    }
    return null;
  },
  // Runs a specific class's constructor against an existing receiver. This is
  // needed because generated classes have no JavaScript `extends` chain, so a
  // translated constructor cannot call `super(...)` directly.
  invokeNamedConstructor(receiver, className, ...args) {
    if (receiver === null || receiver === undefined) {
      throw new GoldClientJavaThrowable("java.lang.NullPointerException", "<init>");
    }
    const constructor = this.classes.get(className);
    if (!constructor) {
      if (className === "java.lang.Object") return receiver;
      if (GOLD_JAVA_SUPERCLASSES.has(className)) {
        return this.initializeJavaThrowable(receiver, className, args);
      }
      return this.unsupported("missing-super-constructor", className);
    }
    this.ensureInitialized(constructor);
    const candidates = constructor.__goldMethods?.get("<init>") ?? [];
    const target = this.bestMethod(candidates, args, false);
    if (!target) {
      return this.unsupported("missing-super-constructor", className + "/" + args.length);
    }
    return target.fn.apply(receiver, args);
  },
  initializeJavaThrowable(receiver, className, args) {
    if (receiver.javaThrowable) return receiver;
    receiver.javaThrowable = true;
    receiver.javaClassName = receiver.constructor?.__goldClassName ?? className;
    receiver.name = receiver.javaClassName;
    receiver.message = args[0] === null || args[0] === undefined ? "" : String(args[0]);
    receiver.cause = args.length > 1 ? args[1] : null;
    receiver.suppressedExceptions = [];
    receiver.javaStackTrace = [];
    return receiver;
  },
  invoke(receiver, methods, name, args, optional = false) {
    const className = receiver?.constructor?.__goldClassName;
    const target = this.resolveClassMethod(className, name, args, false);
    if (target) return target.fn.apply(receiver, args);
    const declaration = (methods.get(name) ?? []).find(method =>
      method.isAdapterRequired && !method.isStatic && method.arity === args.length);
    if (declaration) return declaration.fn.apply(receiver, args);
    if (optional) return undefined;
    return this.unsupported("missing-instance-method", name + "/" + args.length);
  },
  invokeStatic(receiver, methods, name, args) {
    this.ensureInitialized(receiver);
    const className = typeof receiver === "function"
      ? receiver.__goldClassName : receiver?.constructor?.__goldClassName;
    const target = this.resolveClassMethod(className, name, args, true);
    if (target) return target.fn.apply(receiver, args);
    const declaration = (methods.get(name) ?? []).find(method =>
      method.isAdapterRequired && method.isStatic && method.arity === args.length);
    if (declaration) return declaration.fn.apply(receiver, args);
    return this.unsupported("missing-static-method", name + "/" + args.length);
  },
  invokeDeclaration(detail, receiver, argsLike) {
    const adapter = globalThis.goldClientDeclarationAdapters?.[detail];
    if (typeof adapter !== "function") {
      return this.unsupported("missing-declaration-adapter", detail);
    }
    return adapter(receiver, ...Array.from(argsLike ?? []));
  },
  unsupported(operation, detail = "") {
    throw new Error("Unsupported translated JVM operation: " + operation + " " + detail);
  }
});

// Host-side event dispatch needs the translated event hierarchy. Keep the one
// runtime instance discoverable without importing this module from the host.
globalThis.goldClientRuntime = GoldClientRuntime;
