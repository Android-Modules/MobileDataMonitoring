package com.dalong.dataguard.core;

/** A package typed by hand may not be installed yet, so only its form can be checked. */
public final class AppPackage {
  private AppPackage() {}

  public static boolean valid(String name) {
    return name != null && name.matches("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+");
  }

  /** Accepts a pasted address; rules are stored as host or host/path without a scheme. */
  public static String ruleLine(String line) {
    return line.trim().replaceFirst("(?i)^https?://", "");
  }
}
