package com.dalong.dataguard.core;

import java.net.URI;
import java.util.*;

public final class RuleMatcher {
    public record Rule(String host, String pathPrefix) {
        public boolean matches(String address, boolean pathVisible) {
            try {
                URI uri = URI.create(address);
                if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())) return false;
                if (!host.equalsIgnoreCase(uri.getHost())) return false;
                return pathPrefix.isEmpty() || (pathVisible && uri.getPath().startsWith(pathPrefix));
            } catch (RuntimeException e) { return false; }
        }
    }
    public static boolean matches(Set<String> apps, List<Rule> rules, String app, String address,
                                  boolean pathVisible, boolean requireBoth) {
        boolean a = apps.contains(app);
        boolean r = rules.stream().anyMatch(x -> x.matches(address, pathVisible));
        return requireBoth ? a && r : a || r;
    }
}
