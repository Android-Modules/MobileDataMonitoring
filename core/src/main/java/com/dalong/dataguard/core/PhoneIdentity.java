package com.dalong.dataguard.core;

/** Numbers are used for exact account association, not carrier inference. */
public final class PhoneIdentity {
  public static String normalize(String value) {
    if (value == null) return "";
    String n = value.trim().replace(" ", "").replace("-", "");
    if (n.startsWith("+86")) n = n.substring(3);
    else if (n.startsWith("0086")) n = n.substring(4);
    return n.matches("1[0-9]{10}") ? n : "";
  }

  /** Reads a login cookie's account number; an encrypted or missing value yields nothing. */
  public static String fromCookie(String cookie, String name) {
    if (cookie == null) return "";
    for (String pair : cookie.split(";")) {
      String[] part = pair.trim().split("=", 2);
      if (part.length == 2 && part[0].equals(name)) return normalize(part[1]);
    }
    return "";
  }

  public static String masked(String value) {
    String n = normalize(value);
    return n.isEmpty() ? "" : n.substring(0, 3) + "****" + n.substring(7);
  }

  public static boolean matches(
      String simCarrier,
      String simKey,
      String accountCarrier,
      String accountKey,
      boolean plainNumber) {
    return plainNumber
        && simCarrier != null
        && simCarrier.equals(accountCarrier)
        && simKey != null
        && !simKey.isEmpty()
        && simKey.equals(accountKey);
  }

  private PhoneIdentity() {}
}
