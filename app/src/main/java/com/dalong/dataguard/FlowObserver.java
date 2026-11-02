package com.dalong.dataguard;

import android.content.Context;
import android.net.*;
import io.github.libxposed.api.*;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Counts application payload only. It never changes the kernel/billing totals. */
final class FlowObserver {
  static final ScheduledExecutorService sender =
      Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "guard-flow");
        t.setDaemon(true);
        return t;
      });
  static final Map<String, long[]> pending = new HashMap<>();
  static final Map<Object, String> bodies = Collections.synchronizedMap(new WeakHashMap<>());
  static final Set<Method> sources = new HashSet<>();
  static final ThreadLocal<Integer> readDepth = ThreadLocal.withInitial(() -> 0);
  static boolean installed;
  static ScheduledFuture<?> flushTask;
  static final Map<String, Long> lastSent = new HashMap<>();

  static boolean cellular() {
    try {
      Context context =
          (Context)
              Class.forName("android.app.ActivityThread")
                  .getMethod("currentApplication")
                  .invoke(null);
      if (context == null) return false;
      ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
      NetworkCapabilities c = cm.getNetworkCapabilities(cm.getActiveNetwork());
      return c != null
          && c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
          && !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
    } catch (Exception e) {
      return false;
    }
  }

  static void add(String pkg, String url, long rx, long tx) {
    if (rx + tx <= 0 || !cellular()) return;
    try {
      URI u = URI.create(url);
      if (u.getHost() == null) return;
      // Query strings, cookies and bodies never enter observations.
      String key = pkg + "\n" + u.getHost() + "\n" + Objects.toString(u.getRawPath(), "/");
      synchronized (pending) {
        if (pending.size() >= 100 && !pending.containsKey(key)) return;
        long[] n = pending.computeIfAbsent(key, k -> new long[2]);
        n[0] = Math.addExact(n[0], rx);
        n[1] = Math.addExact(n[1], tx);
        long now = android.os.SystemClock.elapsedRealtime();
        boolean urgent =
            now - lastSent.getOrDefault(key, -30000L) >= 10000 || n[0] + n[1] >= 16000000;
        if (flushTask == null || (urgent && flushTask.getDelay(TimeUnit.MILLISECONDS) > 0)) {
          if (flushTask != null) flushTask.cancel(false);
          flushTask = sender.schedule(FlowObserver::flush, urgent ? 0 : 5, TimeUnit.SECONDS);
        }
      }
    } catch (Exception ignored) {
    }
  }

  static void flush() {
    Map<String, long[]> batch;
    synchronized (pending) {
      if (flushTask != null) flushTask.cancel(false);
      flushTask = null;
      batch = new HashMap<>(pending);
      pending.clear();
      long now = android.os.SystemClock.elapsedRealtime();
      lastSent.entrySet().removeIf(e -> now - e.getValue() >= 30000);
      for (String key : batch.keySet()) lastSent.put(key, now);
    }
    for (var e : batch.entrySet())
      try {
        String[] k = e.getKey().split("\n", 3);
        RootClient.request(
            new JSONObject()
                .put("action", "flow")
                .put("package", k[0])
                .put("host", k[1])
                .put("path", k[2])
                .put("rx", e.getValue()[0])
                .put("tx", e.getValue()[1]));
      } catch (Exception ignored) {
      }
  }

  static InputStream input(InputStream in, String pkg, String url) {
    return new FilterInputStream(in) {
      @Override
      public int read() throws IOException {
        int n = in.read();
        if (n >= 0) add(pkg, url, 1, 0);
        return n;
      }

      @Override
      public int read(byte[] b, int o, int n) throws IOException {
        int r = in.read(b, o, n);
        if (r > 0) add(pkg, url, r, 0);
        return r;
      }

      @Override
      public int read(byte[] b) throws IOException {
        return read(b, 0, b.length);
      }

      @Override
      public void close() throws IOException {
        try {
          super.close();
        } finally {
          sender.execute(FlowObserver::flush);
        }
      }
    };
  }

  static OutputStream output(OutputStream out, String pkg, String url) {
    return new FilterOutputStream(out) {
      @Override
      public void write(int b) throws IOException {
        out.write(b);
        add(pkg, url, 0, 1);
      }

      @Override
      public void write(byte[] b, int o, int n) throws IOException {
        out.write(b, o, n);
        add(pkg, url, 0, n);
      }

      @Override
      public void write(byte[] b) throws IOException {
        write(b, 0, b.length);
      }
    };
  }

  static void install(XposedModule module, ClassLoader loader, String pkg) {
    synchronized (FlowObserver.class) {
      if (installed) return;
      installed = true;
    }
    PaomanObserver.install(module, loader, pkg);
    try {
      Class<?> connection =
          Class.forName("com.android.okhttp.internal.huc.HttpURLConnectionImpl", false, loader);
      module
          .hook(connection.getDeclaredMethod("getInputStream"))
          .intercept(chain -> {
            Object value = chain.proceed();
            if (!(value instanceof InputStream)) return value;
            return input(
                (InputStream) value,
                pkg,
                ((URLConnection) chain.getThisObject()).getURL().toString());
          });
      module
          .hook(connection.getDeclaredMethod("getOutputStream"))
          .intercept(chain -> {
            Object value = chain.proceed();
            if (!(value instanceof OutputStream)) return value;
            return output(
                (OutputStream) value,
                pkg,
                ((URLConnection) chain.getThisObject()).getURL().toString());
          });
      module.log(4, "DataGuard", "URLConnection payload observer installed: " + pkg);
    } catch (Throwable e) {
      module.log(
          4, "DataGuard", "URLConnection observer unavailable: " + e.getClass().getSimpleName());
    }
    try {
      Class<?> builder = Class.forName("okhttp3.Response$Builder", false, loader);
      module
          .hook(builder.getDeclaredMethod("build"))
          .intercept(chain -> {
            Object response = chain.proceed();
            try {
              Object request = invoke(response, "request"), body = invoke(response, "body");
              if (body != null) {
                bodies.put(body, invoke(request, "url").toString());
                Method source = body.getClass().getMethod("source");
                source.setAccessible(true);
                synchronized (sources) {
                  if (sources.add(source))
                    module
                        .hook(source)
                        .intercept(read -> {
                          Object original = read.proceed();
                          String url = bodies.get(read.getThisObject());
                          if (original == null
                              || url == null
                              || !source.getReturnType().isInterface()
                              || java.lang.reflect.Proxy.isProxyClass(original.getClass()))
                            return original;
                          return buffered(original, source.getReturnType(), pkg, url);
                        });
                }
              }
            } catch (Throwable ignored) {
            }
            return response;
          });
      module.log(4, "DataGuard", "OkHttp payload observer installed: " + pkg);
    } catch (Throwable e) {
      module.log(4, "DataGuard", "OkHttp observer unavailable: " + e.getClass().getSimpleName());
    }
  }

  static Object invoke(Object value, String method) throws Exception {
    Method m = value.getClass().getMethod(method);
    m.setAccessible(true);
    return m.invoke(value);
  }

  static Object buffered(Object delegate, Class<?> type, String pkg, String url) {
    return java.lang.reflect.Proxy.newProxyInstance(
        type.getClassLoader(),
        new Class<?>[] {type},
        (proxy, method, args) -> {
          Object result;
          int depth = readDepth.get();
          readDepth.set(depth + 1);
          try {
            result = method.invoke(delegate, args);
          } catch (InvocationTargetException e) {
            throw e.getCause();
          } finally {
            readDepth.set(depth);
          }
          if (depth > 0) return result;
          String name = method.getName();
          long n = 0;
          if (name.equals("inputStream") && result instanceof InputStream)
            return input((InputStream) result, pkg, url);
          if (name.startsWith("read")) {
            if (result instanceof byte[]) n = ((byte[]) result).length;
            else if (result instanceof String)
              n = ((String) result).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            else if (name.equals("read") || name.equals("readAll"))
              n = result instanceof Number ? Math.max(0, ((Number) result).longValue()) : 0;
            else if (name.equals("readByteString"))
              try {
                n = ((Number) invoke(result, "size")).longValue();
              } catch (Exception ignored) {
              }
            else if (name.equals("readFully") && args != null) {
              if (args[0] instanceof byte[]) n = ((byte[]) args[0]).length;
              else if (args.length > 1 && args[1] instanceof Number)
                n = ((Number) args[1]).longValue();
            } else if (name.equals("readByte")) n = 1;
            else if (name.startsWith("readShort")) n = 2;
            else if (name.startsWith("readInt")) n = 4;
            else if (name.startsWith("readLong")
                && !name.contains("Decimal")
                && !name.contains("Hexadecimal")) n = 8;
          } else if (name.equals("skip") && args != null && args[0] instanceof Number)
            n = ((Number) args[0]).longValue();
          if (n > 0) add(pkg, url, n, 0);
          if (name.equals("close")) sender.execute(FlowObserver::flush);
          return result == delegate ? proxy : result;
        });
  }
}
