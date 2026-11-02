package com.dalong.dataguard;

import android.app.usage.*;
import android.content.*;
import android.net.*;
import android.telephony.*;
import com.dalong.dataguard.core.CarrierIdentity;
import com.dalong.dataguard.core.PhoneIdentity;
import java.lang.reflect.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import org.json.*;

/** Runs only in the root helper. Raw SIM identifiers never leave this process. */
@android.annotation.SuppressLint(
    "MissingPermission") // Root worker uses privileged Binder; failures stop data.
public final class Platform {
  final Context context;
  final SubscriptionManager subscriptions;
  final TelephonyManager telephony;
  final NetworkStatsManager stats;
  /** Subscriptions the user barred from mobile data; refreshed by every control sample. */
  volatile Set<Integer> blockedSubs = Set.of();

  static final String MOBILE_APP = "com.jsmcc";

  public Platform() throws Exception {
    if (android.os.Process.myUid() != 0) throw new SecurityException("电话与统计适配仅允许 Root 进程");
    Class<?> vm = Class.forName("dalvik.system.VMRuntime");
    vm.getDeclaredMethod("setHiddenApiExemptions", String[].class)
        .invoke(vm.getDeclaredMethod("getRuntime").invoke(null), (Object) new String[] {"L"});
    Class<?> sm = Class.forName("android.os.TelephonyServiceManager");
    Class.forName("android.telephony.TelephonyFrameworkInitializer")
        .getMethod("setTelephonyServiceManager", sm)
        .invoke(null, sm.getConstructor().newInstance());
    Class<?> at = Class.forName("android.app.ActivityThread");
    Object thread = at.getMethod("systemMain").invoke(null);
    context = (Context) at.getMethod("getSystemContext").invoke(thread);
    subscriptions = context.getSystemService(SubscriptionManager.class);
    telephony = context.getSystemService(TelephonyManager.class);
    stats = context.getSystemService(NetworkStatsManager.class);
  }

  public static String hash(String value) throws Exception {
    byte[] b =
        MessageDigest.getInstance("SHA-256")
            .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    StringBuilder s = new StringBuilder();
    for (int i = 0; i < 12; i++) s.append(String.format("%02x", b[i]));
    return s.toString();
  }

  public JSONArray cards() throws Exception {
    JSONArray result = new JSONArray();
    List<SubscriptionInfo> list = subscriptions.getActiveSubscriptionInfoList();
    if (list == null) return result;
    for (SubscriptionInfo s : list) {
      TelephonyManager sim = telephony.createForSubscriptionId(s.getSubscriptionId());
      String imsi = sim.getSubscriberId();
      String icc = s.getIccId();
      if ((icc == null || icc.isEmpty()) && (imsi == null || imsi.isEmpty()))
        throw new IllegalStateException("无法确定卡身份");
      String mccMnc = "";
      int carrierId = -1;
      try {
        mccMnc = sim.getSimOperator();
        carrierId = sim.getSimCarrierId();
      } catch (RuntimeException ignored) {
      }
      if (mccMnc == null || mccMnc.isEmpty())
        mccMnc =
            (s.getMccString() == null ? "" : s.getMccString())
                + (s.getMncString() == null ? "" : s.getMncString());
      CarrierIdentity.Result provider =
          CarrierIdentity.resolve(carrierId, mccMnc, String.valueOf(s.getCarrierName()));
      String phone = "";
      try {
        phone = PhoneIdentity.normalize(subscriptions.getPhoneNumber(s.getSubscriptionId()));
      } catch (RuntimeException ignored) {
      }
      result.put(
          new JSONObject()
              .put("slot", s.getSimSlotIndex() + 1)
              .put("subId", s.getSubscriptionId())
              .put("identity", hash((icc == null ? "" : icc) + ":" + (imsi == null ? "" : imsi)))
              .put("carrierCode", provider.code())
              .put("carrierLabel", provider.label())
              .put("carrierSource", provider.source())
              .put("mccMnc", mccMnc)
              .put("carrierId", carrierId)
              .put("numberMasked", PhoneIdentity.masked(phone))
              .put("numberKey", phone.isEmpty() ? "" : hash(phone))
              .put("numberSource", phone.isEmpty() ? "" : "sim")
              .put("carrier", s.getCarrierName())
              .put(
                  "active",
                  s.getSubscriptionId() == SubscriptionManager.getActiveDataSubscriptionId())
              .put(
                  "default",
                  s.getSubscriptionId() == SubscriptionManager.getDefaultDataSubscriptionId()));
    }
    return result;
  }

  public int defaultSub() {
    return SubscriptionManager.getDefaultDataSubscriptionId();
  }

  public int activeSub() {
    return SubscriptionManager.getActiveDataSubscriptionId();
  }

  public int slotForSub(int sub) throws Exception {
    return slotForSub(cards(), sub);
  }

  static int slotForSub(JSONArray c, int sub) throws Exception {
    for (int i = 0; i < c.length(); i++)
      if (c.getJSONObject(i).getInt("subId") == sub) return c.getJSONObject(i).getInt("slot");
    return 0;
  }

  public String imsi(int sub) {
    String id = telephony.createForSubscriptionId(sub).getSubscriberId();
    if (id == null || id.isEmpty()) throw new IllegalStateException("订阅标识不可读");
    return id;
  }

  public JSONObject usage(int sub, long start, long end) throws Exception {
    String id = imsi(sub);
    // The root process has no package UID. The privileged session avoids claiming
    // that package "android" belongs to UID 0, which Android 16 correctly rejects.
    Object binder =
        Class.forName("android.os.ServiceManager")
            .getMethod("getService", String.class)
            .invoke(null, "netstats");
    Object service =
        Class.forName("android.net.INetworkStatsService$Stub")
            .getMethod("asInterface", android.os.IBinder.class)
            .invoke(null, binder);
    Object session = call(service, "openSession", new Class<?>[0]);
    Class<?> templateClass = Class.forName("android.net.NetworkTemplate");
    Class<?> builderClass = Class.forName("android.net.NetworkTemplate$Builder");
    Object builder = builderClass.getConstructor(int.class).newInstance(1);
    builderClass.getMethod("setSubscriberIds", Set.class).invoke(builder, Set.of(id));
    Object template = builderClass.getMethod("build").invoke(builder);
    JSONObject uids = new JSONObject();
    try {
      Object total =
          call(
              session,
              "getDeviceSummaryForNetwork",
              new Class<?>[] {templateClass, long.class, long.class},
              template,
              start,
              end);
      long raw = (long) total.getClass().getMethod("getTotalBytes").invoke(total);
      Object rows =
          call(
              session,
              "getSummaryForAllUid",
              new Class<?>[] {templateClass, long.class, long.class, boolean.class},
              template,
              start,
              end,
              false);
      Class<?> entryClass = Class.forName("android.net.NetworkStats$Entry");
      int size = (int) rows.getClass().getMethod("size").invoke(rows);
      for (int i = 0; i < size; i++) {
        Object row =
            rows.getClass().getMethod("getValues", int.class, entryClass).invoke(rows, i, null);
        String uid = "" + entryClass.getField("uid").getInt(row);
        long bytes =
            entryClass.getField("rxBytes").getLong(row)
                + entryClass.getField("txBytes").getLong(row);
        uids.put(uid, uids.optLong(uid) + bytes);
      }
      return new JSONObject().put("raw", raw).put("uids", uids);
    } finally {
      call(session, "close", new Class<?>[0]);
    }
  }

  static Object call(Object o, String name, Class<?>[] types, Object... args) throws Exception {
    Method m = o.getClass().getMethod(name, types);
    m.setAccessible(true);
    return m.invoke(o, args);
  }

  public boolean dataEnabled(int sub) {
    return sub >= 0 && telephony.createForSubscriptionId(sub).isDataEnabled();
  }

  void requirePermitted(int sub) {
    if (blockedSubs.contains(sub) && !WifiState.connected(context))
      throw new SecurityException("此卡已设置禁止使用移动数据");
  }

  public void setData(int sub, boolean enabled) throws Exception {
    if (sub < 0 && !enabled) return;
    if (enabled) requirePermitted(sub);
    TelephonyManager.class
        .getMethod("setDataEnabled", boolean.class)
        .invoke(telephony.createForSubscriptionId(sub), enabled);
    long deadline = android.os.SystemClock.elapsedRealtime() + 2000;
    while (dataEnabled(sub) != enabled && android.os.SystemClock.elapsedRealtime() < deadline)
      Thread.sleep(100);
    if (dataEnabled(sub) != enabled) throw new IllegalStateException("移动数据开关未通过核验");
  }

  /** Sends through the telephony binder, so the app itself holds no SMS permission. */
  void sendSms(int sub, String number, String text) throws Exception {
    Object binder =
        Class.forName("android.os.ServiceManager")
            .getMethod("getService", String.class)
            .invoke(null, "isms");
    Object service =
        Class.forName("com.android.internal.telephony.ISms$Stub")
            .getMethod("asInterface", android.os.IBinder.class)
            .invoke(null, binder);
    for (Method m : service.getClass().getMethods()) {
      if (!m.getName().equals("sendTextForSubscriber")) continue;
      Class<?>[] t = m.getParameterTypes();
      Object[] args = new Object[t.length];
      int strings = 0;
      for (int i = 0; i < t.length; i++) {
        if (t[i] == int.class) args[i] = sub;
        else if (t[i] == boolean.class) args[i] = false;
        else if (t[i] == long.class) args[i] = 0L;
        else if (t[i] == String.class)
          args[i] =
              switch (strings++) {
                case 0 -> "com.android.phone";
                case 1 -> null;
                case 2 -> number;
                case 3 -> null;
                default -> text;
              };
      }
      m.invoke(service, args);
      return;
    }
    throw new IllegalStateException("系统短信接口暂不兼容");
  }

  /** Bodies of the replies from one service number on one card since a moment, oldest first. */
  String smsFrom(String number, int sub, long since) throws Exception {
    Process process =
        new ProcessBuilder(
                "/system/bin/content",
                "query",
                "--uri",
                "content://sms/inbox",
                "--projection",
                "sub_id:body",
                "--where",
                // Some systems return nothing for a sub_id condition; the card is checked per row.
                "date>=" + since + " AND address='" + number + "'",
                "--sort",
                "date ASC")
            .redirectErrorStream(true)
            .start();
    String output;
    try (var in = process.getInputStream()) {
      output = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }
    process.waitFor();
    StringBuilder bodies = new StringBuilder();
    java.util.regex.Matcher row =
        java.util.regex.Pattern.compile("(?m)^Row: [0-9]+ sub_id=(-?[0-9]+), body=").matcher(output);
    int start = -1;
    boolean mine = false;
    while (true) {
      boolean found = row.find();
      if (start >= 0 && mine)
        bodies.append(output, start, found ? row.start() : output.length()).append('\n');
      if (!found) break;
      start = row.end();
      mine = row.group(1).equals(String.valueOf(sub));
    }
    return bodies.toString();
  }

  public void switchTo(int sub) throws Exception {
    requirePermitted(sub);
    SubscriptionManager.class
        .getMethod("setDefaultDataSubId", int.class)
        .invoke(subscriptions, sub);
  }

  public Map<String, Long> counters() throws Exception {
    Map<String, Long> r = new TreeMap<>();
    // Conservative budget: count every cellular interface once. May include modem/IMS overhead.
    try (var paths = Files.list(Path.of("/sys/class/net"))) {
      for (Path p : paths.toList()) {
        String name = p.getFileName().toString();
        if (!com.dalong.dataguard.core.UsageMath.cellularInterface(name)) continue;
        long rx = Long.parseLong(read(p.resolve("statistics/rx_bytes")).trim());
        long tx = Long.parseLong(read(p.resolve("statistics/tx_bytes")).trim());
        r.put(name, Math.addExact(rx, tx));
      }
    }
    return r;
  }

  public static String read(Path path) throws Exception {
    return new String(Files.readAllBytes(path), java.nio.charset.StandardCharsets.UTF_8);
  }

  public boolean installedForUser(String pkg, int user) {
    try {
      call(
          context.getPackageManager(),
          "getApplicationInfoAsUser",
          new Class<?>[] {String.class, int.class, int.class},
          pkg,
          0,
          user);
      return true;
    } catch (Exception ignored) {
      return false;
    }
  }

  public void openMobile(int user, JSONObject route) throws Exception {
    openMobile(user, route, -1);
  }

  /** A display id places the page there, in its own task, away from whatever the user sees. */
  void openMobile(int user, JSONObject route, int display) throws Exception {
    if (user != 0 && user != 999) throw new SecurityException("官方应用分身身份不可用");
    if (!installedForUser(MOBILE_APP, user)) throw new IllegalArgumentException("当前卡对应的江苏移动应用未安装");
    Intent intent =
        new Intent()
            .setComponent(
                new ComponentName(
                    MOBILE_APP,
                    route == null
                        ? "com.mc10086.cmcc.base.StartPageActivity"
                        : route.getString("class")))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    if (route != null) intent.putExtras(routeBundle(route.getJSONObject("extras")));
    if (display >= 0) intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
    startActivity(
        intent,
        user,
        display < 0
            ? null
            : android.app.ActivityOptions.makeBasic().setLaunchDisplayId(display).toBundle());
  }

  boolean interactive() {
    return context.getSystemService(android.os.PowerManager.class).isInteractive();
  }

  void openPackage(String pkg, int user) throws Exception {
    if (!installedForUser(pkg, user)) throw new IllegalArgumentException("官方应用未安装");
    Intent intent =
        new Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(pkg)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    var match = context.getPackageManager().queryIntentActivities(intent, 0);
    if (match.isEmpty()) throw new IllegalArgumentException("官方应用入口不可用");
    intent.setClassName(pkg, match.get(0).activityInfo.name);
    startActivity(intent, user);
  }

  String foregroundPackage() {
    try {
      Object manager =
          Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
      Object task = manager.getClass().getMethod("getFocusedRootTaskInfo").invoke(manager);
      if (task == null) return "";
      ComponentName top = (ComponentName) task.getClass().getField("topActivity").get(task);
      return top == null ? "" : top.getPackageName();
    } catch (Exception ignored) {
      return "";
    }
  }

  void directedPrompt(String id) throws Exception {
    startActivity(
        new Intent()
            .setComponent(
                new ComponentName(
                    "com.dalong.dataguard", "com.dalong.dataguard.DirectedPromptActivity"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("episode", id),
        0);
  }

  void startActivity(Intent intent, int user) throws Exception {
    startActivity(intent, user, null);
  }

  /** The app's tasks for this user, so a hidden read can later remove only the ones it made. */
  Set<Integer> appTasks(String pkg, int user) throws Exception {
    Object manager =
        Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
    Object slice =
        manager
            .getClass()
            .getMethod("getRecentTasks", int.class, int.class, int.class)
            // RECENT_WITH_EXCLUDED: a reader page is excluded from recents but still a task.
            .invoke(manager, 100, 1, user);
    Set<Integer> ids = new HashSet<>();
    for (Object o : (List<?>) slice.getClass().getMethod("getList").invoke(slice)) {
      var task = (android.app.ActivityManager.RecentTaskInfo) o;
      ComponentName base = task.baseIntent == null ? null : task.baseIntent.getComponent();
      if ((base != null && pkg.equals(base.getPackageName()))
          || (task.baseActivity != null && pkg.equals(task.baseActivity.getPackageName())))
        ids.add(task.taskId);
    }
    return ids;
  }

  void removeTask(int id) throws Exception {
    Object manager =
        Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
    manager.getClass().getMethod("removeTask", int.class).invoke(manager, id);
  }

  boolean appRunning(String pkg, int user) {
    var processes =
        context.getSystemService(android.app.ActivityManager.class).getRunningAppProcesses();
    if (processes == null) return false;
    for (var p : processes)
      if (p.uid / 100000 == user && p.pkgList != null && Arrays.asList(p.pkgList).contains(pkg))
        return true;
    return false;
  }

  /** This app's own reader page for the web service hall, placed on the given display. */
  void openWebRead(String identity, int display) throws Exception {
    Intent intent =
        new Intent()
            .setComponent(new ComponentName("com.dalong.dataguard", WebReadActivity.class.getName()))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            .putExtra("identity", identity);
    startActivity(
        intent, 0, android.app.ActivityOptions.makeBasic().setLaunchDisplayId(display).toBundle());
  }

  void forceStop(String pkg, int user) throws Exception {
    new ProcessBuilder("/system/bin/am", "force-stop", "--user", String.valueOf(user), pkg)
        .redirectErrorStream(true)
        .start()
        .waitFor();
  }

  void startActivity(Intent intent, int user, android.os.Bundle options) throws Exception {
    // An app_process worker has no registered application-thread token. Use the
    // same privileged Binder entry as `am start`, without a fabricated caller.
    Object manager =
        Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
    Method start =
        Arrays.stream(manager.getClass().getMethods())
            .filter(m ->
                m.getName().equals("startActivityAsUser")
                    && m.getParameterCount() == 12
                    && m.getParameterTypes()[3] == Intent.class)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("系统官方应用入口暂不兼容"));
    start.setAccessible(true);
    int result =
        (int)
            start.invoke(
                manager,
                null,
                "com.android.shell",
                null,
                intent,
                null,
                null,
                null,
                -1,
                0,
                null,
                options,
                user);
    if (result < 0) throw new IllegalStateException("应用页面打开失败，请从桌面进入流量守卫");
  }

  static android.os.Bundle routeBundle(JSONObject value) throws Exception {
    android.os.Bundle b = new android.os.Bundle();
    for (Iterator<String> it = value.keys(); it.hasNext(); ) {
      String key = it.next();
      Object v = value.get(key);
      if (v instanceof JSONObject nested) b.putBundle(key, routeBundle(nested));
      else if (v instanceof String s) b.putString(key, s);
      else if (v instanceof Boolean flag) b.putBoolean(key, flag);
      else if (v instanceof Integer number) b.putInt(key, number);
      else if (v instanceof Long number) b.putLong(key, number);
    }
    return b;
  }

  public String bootId() throws Exception {
    return read(Path.of("/proc/sys/kernel/random/boot_id")).trim();
  }
}
