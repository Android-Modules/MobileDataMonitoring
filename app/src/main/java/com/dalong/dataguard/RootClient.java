package com.dalong.dataguard;

import android.content.Context;
import android.net.*;
import java.io.*;
import java.util.concurrent.TimeUnit;
import org.json.*;

final class RootClient {
  static JSONObject request(JSONObject request) throws Exception {
    return request(request, 20000);
  }

  static JSONObject request(JSONObject request, int timeoutMs) throws Exception {
    try (LocalSocket socket = new LocalSocket()) {
      socket.connect(new LocalSocketAddress(GuardDaemon.SOCKET));
      socket.setSoTimeout(timeoutMs);
      byte[] bytes = request.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
      DataOutputStream out = new DataOutputStream(socket.getOutputStream());
      out.writeInt(bytes.length);
      out.write(bytes);
      out.flush();
      DataInputStream in = new DataInputStream(socket.getInputStream());
      int n = in.readInt();
      if (n < 1 || n > 2_000_000) throw new IOException("响应过大");
      byte[] buffer = new byte[n];
      in.readFully(buffer);
      JSONObject result =
          new JSONObject(new String(buffer, java.nio.charset.StandardCharsets.UTF_8));
      if (result.has("error")) throw new IllegalArgumentException(result.getString("error"));
      return result;
    }
  }

  static JSONObject action(String name) throws Exception {
    return request(new JSONObject().put("action", name));
  }

  static synchronized void start(Context context) throws Exception {
    if (GuardApplication.off(context)) throw new IOException("模块已关闭");
    try {
      action("health");
      return;
    } catch (Exception ignored) {
    }
    String script;
    try (InputStream in = context.getAssets().open("guard-supervisor.sh")) {
      script = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }
    String path = "/data/adb/service.d/mobile-data-guard.sh";
    String command =
        "umask 077; mkdir -p /data/adb/mobile-data-guard /data/adb/service.d;"
            + " rm -f /data/adb/mobile-data-guard/disabled; printf %s "
            + quote(script)
            + " > "
            + path
            + "; chmod 700 "
            + path
            + "; nohup /system/bin/sh "
            + path
            + " > /data/adb/mobile-data-guard/supervisor.log 2>&1 < /dev/null &";
    Process process = new ProcessBuilder("su", "-c", command).start();
    process.waitFor(10, TimeUnit.SECONDS);
    for (int i = 0; i < 30; i++) {
      try {
        action("health");
        return;
      } catch (Exception e) {
        Thread.sleep(300);
      }
    }
    throw new IOException("Root 监控进程未启动，请授予 Root 权限");
  }

  static String quote(String value) {
    return "'" + value.replace("'", "'\\''") + "'";
  }
}
