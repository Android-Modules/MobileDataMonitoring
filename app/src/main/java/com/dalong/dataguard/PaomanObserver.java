package com.dalong.dataguard;

import io.github.libxposed.api.XposedModule;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Narrow adapter for the relocated OkHttp response model in Paoman 5.3.1. */
final class PaomanObserver {
  static void install(XposedModule module, ClassLoader loader, String pkg) {
    if (!"com.paoman.lema".equals(pkg)) return;
    try {
      Class<?> builder = Class.forName("u6.e0", false, loader);
      Class<?> response = Class.forName("u6.f0", false, loader);
      Class<?> request = Class.forName("u6.c0", false, loader);
      Class<?> body = Class.forName("u6.h0", false, loader);
      Class<?> url = Class.forName("u6.v", false, loader);
      Method build = builder.getDeclaredMethod("a"), stream = body.getDeclaredMethod("b");
      if (build.getReturnType() != response || stream.getReturnType() != InputStream.class) return;
      Field responseRequest = field(response, request),
          responseBody = field(response, body),
          requestUrl = field(request, url);
      module
          .hook(build)
          .intercept(chain -> {
            Object result = chain.proceed();
            try {
              Object payload = responseBody.get(result), req = responseRequest.get(result);
              if (payload != null && req != null) {
                Object address = requestUrl.get(req);
                if (address != null) FlowObserver.bodies.put(payload, address.toString());
              }
            } catch (Throwable ignored) {
            }
            return result;
          });
      module
          .hook(stream)
          .intercept(chain -> {
            Object result = chain.proceed();
            String address = FlowObserver.bodies.get(chain.getThisObject());
            return result instanceof InputStream && address != null
                ? FlowObserver.input((InputStream) result, pkg, address)
                : result;
          });
      module.log(4, "DataGuard", "Paoman response stream observer installed");
    } catch (Throwable e) {
      module.log(
          4, "DataGuard", "Paoman response adapter unavailable: " + e.getClass().getSimpleName());
    }
  }

  static Field field(Class<?> owner, Class<?> type) throws Exception {
    Field found = null;
    for (Field f : owner.getDeclaredFields()) {
      if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || f.getType() != type) continue;
      if (found != null) throw new NoSuchFieldException("Ambiguous response model");
      found = f;
    }
    if (found == null) throw new NoSuchFieldException("Response model changed");
    found.setAccessible(true);
    return found;
  }
}
