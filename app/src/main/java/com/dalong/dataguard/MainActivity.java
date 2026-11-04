package com.dalong.dataguard;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.*;
import android.os.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import com.dalong.dataguard.core.OfficialTotals;
import com.dalong.dataguard.core.PolicyEngine;
import com.dalong.dataguard.core.UsageMath;
import java.math.*;
import java.text.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** UI owns presentation and drafts; root owns identity, accounting and all data controls. */
public final class MainActivity extends Activity {
  final ExecutorService io = Executors.newSingleThreadExecutor();
  final Handler main = new Handler(Looper.getMainLooper());
  GuardUi ui;
  LinearLayout base, body;
  ScrollView scroll;
  TextView statusLine;
  JSONObject status;
  int slot = 2, tab;
  boolean busy, foreground, webReading;
  int switching;
  boolean initialCard = true;
  long lastTouch;
  long shownBudget = Long.MIN_VALUE;
  String logFilter = "all";
  int importSlot = 2;
  final Runnable poll =
      new Runnable() {
        public void run() {
          if (!foreground) return;
          // A hidden official page can hold window focus; a pending result is still fetched.
          boolean pending = !getPreferences(0).getString("officialPending", "").isEmpty();
          if (!busy
              && (pending
                  || (hasWindowFocus() && SystemClock.elapsedRealtime() - lastTouch > 2000)))
            load(false, true);
          long used = status == null ? Long.MIN_VALUE : status.optLong("budgetUsed");
          boolean moving = used != shownBudget;
          shownBudget = used;
          // Follow a download closely; an idle page does not keep parsing a full status.
          main.postDelayed(this, pending ? 2000 : moving ? 3000 : 15000);
        }
      };

  @Override
  public void onCreate(Bundle state) {
    setTheme(
        (getResources().getConfiguration().uiMode & 48) == 32
            ? R.style.AppThemeDark
            : R.style.AppTheme);
    super.onCreate(state);
    ui = new GuardUi(this);
    slot = 2;
    if (getIntent().hasExtra("slot")) {
      slot = getIntent().getIntExtra("slot", 2);
      tab = getIntent().getIntExtra("tab", 0);
      initialCard = false;
    }
    if (state != null) {
      initialCard = false;
      slot = state.getInt("slot", 2);
      tab = state.getInt("tab", 0);
    }
    if (slot != 1 && slot != 2) slot = 2;
    if (tab < 0 || tab > 4) tab = 0;
    getWindow().setStatusBarColor(ui.bg);
    getWindow().setNavigationBarColor(ui.bg);
    int light =
        ui.dark
            ? 0
            : WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
    getWindow()
        .getDecorView()
        .getWindowInsetsController()
        .setSystemBarsAppearance(
            light,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
    if (!GuardApplication.off(this)) startForegroundService(new Intent(this, GuardService.class));
    // Asked once: a refusal is the user's answer, and the settings page keeps the way back.
    if (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
            != PackageManager.PERMISSION_GRANTED
        && !getPreferences(0).getBoolean("notifyAsked", false)) {
      getPreferences(0).edit().putBoolean("notifyAsked", true).apply();
      requestPermissions(new String[] {"android.permission.POST_NOTIFICATIONS"}, 1);
    }
    render();
    load(true);
  }

  @Override
  public void onSaveInstanceState(Bundle s) {
    s.putInt("slot", slot);
    s.putInt("tab", tab);
    super.onSaveInstanceState(s);
  }

  @Override
  protected void onResume() {
    super.onResume();
    foreground = true;
    if (body != null) load(false, true);
    main.removeCallbacks(poll);
    main.postDelayed(poll, 3000);
  }

  @Override
  protected void onPause() {
    foreground = false;
    main.removeCallbacks(poll);
    super.onPause();
  }

  @Override
  public boolean dispatchTouchEvent(MotionEvent e) {
    lastTouch = SystemClock.elapsedRealtime();
    return super.dispatchTouchEvent(e);
  }

  @Override
  protected void onDestroy() {
    main.removeCallbacks(poll);
    io.shutdownNow();
    super.onDestroy();
  }

  int dp(float n) {
    return ui.dp(n);
  }

  LinearLayout panel() {
    return ui.card(body);
  }

  void note(LinearLayout p, String s) {
    ui.hint(p, s);
  }

  void title(LinearLayout p, String s) {
    p.addView(ui.heading(s, 17));
  }

  void button(LinearLayout p, String s, Runnable run) {
    ui.button(p, s, false, run);
  }

  String safeError(Exception e) {
    return GuardStore.cleanMessage(e.getMessage() == null ? "操作未完成，请重试" : e.getMessage());
  }

  JSONObject cardFor(int s) {
    if (status == null) return null;
    JSONArray cards = status.optJSONArray("cards");
    if (cards != null)
      for (int i = 0; i < cards.length(); i++) {
        JSONObject c = cards.optJSONObject(i);
        if (c != null && c.optInt("slot") == s) return c;
      }
    return null;
  }

  JSONObject selected() throws Exception {
    JSONObject c = cardFor(slot);
    if (c == null) throw new IllegalStateException("该卡槽没有可用 SIM");
    return c;
  }

  JSONObject config() throws Exception {
    return status.getJSONObject("config");
  }

  String provider(JSONObject c) {
    return c == null ? "未插卡" : c.optString("carrierLabel", c.optString("carrier", "识别中"));
  }

  String code(JSONObject c) {
    return c == null ? "unknown" : c.optString("carrierCode", "unknown");
  }

  String carrierName(String c) {
    return switch (c) {
      case "mobile" -> "中国移动";
      case "unicom" -> "中国联通";
      case "telecom" -> "中国电信";
      case "broadnet" -> "中国广电";
      default -> "运营商";
    };
  }

  int carrierColor(String c) {
    return switch (c) {
      case "mobile" -> ui.dark ? 0xff81bafa : 0xff327bc5;
      case "unicom" -> ui.dark ? 0xffffb38e : 0xffcb6036;
      case "telecom" -> ui.dark ? 0xffb2abff : 0xff7161c9;
      default -> ui.muted;
    };
  }

  /** A slot-number name is only the default; it must follow the SIM to its current slot. */
  String alias(JSONObject c) {
    if (c == null) return "未插卡";
    String slotName = "卡 " + c.optInt("slot");
    JSONObject config = c.optJSONObject("config");
    String name = config == null ? "" : config.optString("alias");
    return name.isBlank() || name.matches("卡 [0-9]+") ? slotName : name;
  }

  void selectView(int s) {
    slot = s;
    getPreferences(0).edit().putInt("slot", slot).apply();
    render();
  }

  void render() {
    base = ui.column();
    base.setPadding(dp(18), dp(8), dp(18), 0);
    base.setBackgroundColor(ui.bg);
    setContentView(base);
    base.setOnApplyWindowInsetsListener((v, insets) -> {
      android.graphics.Insets safe =
          insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
      v.setPadding(dp(18) + safe.left, dp(6) + safe.top, dp(18) + safe.right, safe.bottom);
      return insets;
    });
    base.requestApplyInsets();
    boolean live = !GuardApplication.off(this) && status != null;
    LinearLayout header = new LinearLayout(this);
    header.setGravity(Gravity.CENTER_VERTICAL);
    header.setMinimumHeight(dp(52));
    LinearLayout words = ui.column();
    words.addView(ui.heading("流量守卫", 22));
    statusLine = ui.text(headerStatus(), 12, ui.muted);
    statusLine.setSingleLine(true);
    statusLine.setEllipsize(TextUtils.TruncateAt.END);
    words.addView(statusLine);
    header.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
    if (live && (cardFor(1) != null || cardFor(2) != null)) header.addView(switchBar());
    base.addView(header);
    if (live && tab != 4) {
      ui.space(base, 10);
      LinearLayout sims = new LinearLayout(this);
      for (int s = 1; s <= 2; s++) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        if (s == 2) lp.leftMargin = dp(10);
        sims.addView(simTile(s), lp);
      }
      base.addView(sims);
    }
    ui.space(base, 6);
    scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.setClipToPadding(false);
    body = ui.column();
    body.setPadding(0, 0, 0, dp(12));
    scroll.addView(body);
    base.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    for (int s = 1; s <= 2; s++) {
      final int target = s;
      JSONObject c = cardFor(s);
      if (c != null && c.optJSONObject("config") != null
          && c.optJSONObject("config").optBoolean("newSim")) {
        LinearLayout p = panel();
        ui.row(p, "sim", "检测到新 SIM · 卡 " + s, "确认后才能作为数据卡", () -> cardAccessSheet(target));
      }
    }
    if (GuardApplication.off(this)) {
      LinearLayout p = panel();
      title(p, "模块已关闭");
      note(p, "移动数据由系统管理，开机也不再启动。设置、流量包和累计记录都保留。");
      button(p, "重新开启模块", this::enableModule);
    } else if (status == null) {
      LinearLayout p = panel();
      title(p, "连接手机监控");
      note(p, "授予 Root 权限后读取 SIM 与真实蜂窝统计。");
      button(p, "重新连接", () -> load(true));
    } else if (cardFor(slot) == null) {
      LinearLayout p = panel();
      boolean other = cardFor(3 - slot) != null;
      title(p, other ? "此卡槽暂无可用 SIM" : "未检测到可用 SIM");
      note(p, other ? "请切换到另一张卡查看。" : "插入或启用 SIM 后自动识别运营商。");
    } else
      try {
        switch (tab) {
          case 0:
            overview();
            break;
          case 1:
            packages();
            break;
          case 2:
            official();
            break;
          case 3:
            protection();
            break;
          case 4:
            logs();
            break;
        }
      } catch (Exception e) {
        note(panel(), "页面暂不可用，请刷新重试。");
      }
    LinearLayout nav = new LinearLayout(this);
    nav.setPadding(dp(4), dp(5), dp(4), dp(5));
    nav.setBackground(ui.outlined(ui.surface, 22, ui.line));
    String[] labels = {"总览", "流量包", "校准", "日志", "设置"},
        icons = {"overview", "packages", "refresh", "clock", "settings"};
    int[] pages = {0, 1, 2, 4, 3};
    for (int i = 0; i < 5; i++) {
      final int t = pages[i];
      boolean current = t == tab;
      LinearLayout item = ui.column();
      item.setGravity(Gravity.CENTER);
      item.setPadding(0, dp(5), 0, dp(5));
      item.setBackground(ui.ripple(current ? ui.soft : ui.surface, 16));
      item.addView(
          new GuardUi.Icon(this, icons[i], current ? ui.accent : ui.muted),
          new LinearLayout.LayoutParams(dp(23), dp(23)));
      ui.space(item, 3);
      TextView caption = ui.text(labels[i], 11, current ? ui.accent : ui.muted);
      caption.setGravity(Gravity.CENTER);
      item.addView(caption);
      nav.addView(item, new LinearLayout.LayoutParams(0, dp(57), 1));
      item.setFocusable(true);
      item.setContentDescription(labels[i] + (current ? "，已选中" : ""));
      item.setSelected(current);
      item.setOnClickListener(v -> {
        tab = t;
        render();
      });
    }
    base.addView(nav);
    ui.space(base, 5);
  }

  String headerStatus() {
    if (GuardApplication.off(this)) return "模块已关闭 · 移动数据由系统管理";
    if (status == null) return "正在连接安全监控…";
    String data =
        !status.optBoolean("dataOn")
            ? "移动数据已关闭"
            : status.optInt("activeSlot") > 0 ? "卡 " + status.optInt("activeSlot") + " 上网中" : "移动数据已开启";
    long at = status.optLong("sampleAt");
    return at <= 0
        ? data
        : data + " · " + new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date(at)) + " 更新";
  }

  /** Always in reach: which card carries data now, and a one-tap move to the other. */
  LinearLayout switchBar() {
    LinearLayout bar = new LinearLayout(this);
    bar.setGravity(Gravity.CENTER_VERTICAL);
    bar.setPadding(dp(3), dp(3), dp(3), dp(3));
    bar.setBackground(ui.shape(ui.surface, 16));
    boolean on = status.optBoolean("dataOn");
    int active = status.optInt("activeSlot");
    for (int s = 1; s <= 2; s++) {
      final int target = s;
      JSONObject c = cardFor(s);
      boolean current = on && active == s, pending = switching == s;
      TextView chip =
          ui.text(
              pending ? "切换中" : (current ? "● " : "") + "卡 " + s,
              14,
              current ? (ui.dark ? 0xff1b2746 : Color.WHITE) : c == null ? ui.muted : ui.accent);
      chip.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
      chip.setGravity(Gravity.CENTER);
      chip.setMinWidth(dp(64));
      chip.setPadding(dp(14), 0, dp(14), 0);
      chip.setBackground(ui.ripple(current ? ui.accent : ui.surface, 13));
      if (c == null) chip.setAlpha(.4f);
      else if (cardLocked(c) && !current) chip.setAlpha(.6f);
      chip.setContentDescription(
          c == null ? "卡 " + s + " 无可用 SIM" : (current ? "正在使用卡 " : "切换到卡 ") + s + " 上网");
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(40));
      if (s == 2) lp.leftMargin = dp(2);
      bar.addView(chip, lp);
      chip.setOnClickListener(v -> {
        if (c == null) toast("卡 " + target + " 无可用 SIM");
        else quickSwitch(target);
      });
      chip.setOnLongClickListener(v -> {
        dataCardSheet();
        return true;
      });
    }
    return bar;
  }

  /** Tiles only choose which card the page shows; the header switch moves the data. */
  LinearLayout simTile(int s) {
    JSONObject c = cardFor(s);
    boolean chosen = s == slot;
    LinearLayout tile = new LinearLayout(this);
    tile.setGravity(Gravity.CENTER_VERTICAL);
    tile.setPadding(dp(12), dp(10), dp(10), dp(10));
    tile.setBackground(ui.outlined(chosen ? ui.soft : ui.surface, 18, chosen ? ui.accent : ui.surface));
    tile.addView(carrierIcon(code(c)), new LinearLayout.LayoutParams(dp(28), dp(28)));
    LinearLayout words = ui.column();
    LinearLayout line = new LinearLayout(this);
    line.setGravity(Gravity.CENTER_VERTICAL);
    SpannableStringBuilder label = new SpannableStringBuilder(provider(c));
    String province = c == null ? "" : c.optString("province");
    if (showRegion() && !province.isEmpty()) {
      int start = label.length();
      label.append("  ").append(province);
      label.setSpan(new android.text.style.AbsoluteSizeSpan(11, true), start, label.length(), 0);
      label.setSpan(
          new android.text.style.ForegroundColorSpan(chosen ? ui.accent : ui.muted), start, label.length(), 0);
    }
    TextView name = ui.heading("", 13);
    name.setText(label);
    name.setSingleLine(true);
    name.setEllipsize(TextUtils.TruncateAt.END);
    line.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
    boolean carrying = c != null && status.optBoolean("dataOn") && status.optInt("activeSlot") == s;
    if (carrying || cardLocked(c)) {
      TextView state = ui.heading(carrying ? "上网" : "禁用", 11);
      state.setTextColor(carrying ? ui.accent : ui.warn);
      LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-2, -2);
      sl.leftMargin = dp(4);
      line.addView(state, sl);
    }
    words.addView(line);
    TextView number =
        ui.text(c == null ? "卡 " + s + " · 无 SIM" : "卡 " + s + " · " + numberLabel(c), 11, chosen ? ui.accent : ui.muted);
    number.setSingleLine(true);
    number.setEllipsize(TextUtils.TruncateAt.END);
    words.addView(number);
    LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(0, -2, 1);
    wp.leftMargin = dp(8);
    tile.addView(words, wp);
    tile.setFocusable(true);
    tile.setContentDescription("查看卡 " + s + " " + provider(c) + "，不切换移动数据");
    tile.setOnClickListener(v -> {
      if (c != null) selectView(s);
      else showMessage("卡槽暂不可用", "插入或启用 SIM 后自动识别。此操作不会改变当前数据卡。");
    });
    return tile;
  }

  /** One slim line for the limit state; the details live in Settings, not on the overview. */
  LinearLayout limitBar() throws Exception {
    LinearLayout bar = new LinearLayout(this);
    bar.setGravity(Gravity.CENTER_VERTICAL);
    bar.setPadding(dp(13), dp(5), dp(9), dp(5));
    bar.setMinimumHeight(dp(46));
    bar.setBackground(ui.ripple(ui.surface, 16));
    LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(dp(18), dp(18));
    il.rightMargin = dp(9);
    bar.addView(new GuardUi.Icon(this, "shield", ui.accent), il);
    TextView label =
        ui.text(
            "限制 · 通用"
                + (config().optBoolean("enabled") ? "开" : "关")
                + " · 累计"
                + (config().optBoolean("budgetEnabled") ? "开" : "关"),
            13,
            ui.ink);
    label.setSingleLine(true);
    label.setEllipsize(TextUtils.TruncateAt.END);
    bar.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
    if (wifiPaused()) {
      TextView chip = ui.chip("Wi-Fi 暂停中", ui.accent, ui.soft);
      chip.setContentDescription("Wi-Fi 联网中，限制已暂停，查看说明");
      chip.setOnClickListener(v -> wifiSettingsSheet());
      LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, -2);
      cl.rightMargin = dp(6);
      bar.addView(chip, cl);
    }
    bar.addView(
        new GuardUi.Icon(this, "chevron", ui.muted), new LinearLayout.LayoutParams(dp(16), dp(16)));
    bar.setContentDescription("限制与用卡设置");
    bar.setOnClickListener(v -> {
      tab = 3;
      render();
    });
    return bar;
  }

  void quickSwitch(int target) {
    if (status.optBoolean("dataOn") && status.optInt("activeSlot") == target) {
      toast("正在使用卡 " + target + " 上网");
      return;
    }
    JSONObject c = cardFor(target);
    if (cardLocked(c) && !wifiPaused()) {
      GuardUi.Sheet s = ui.sheet("卡 " + target + " 已禁止使用数据", provider(c) + " · " + numberLabel(c));
      note(s.content, "这个开关与流量阈值互相独立。允许后才能用此卡上网，之后可在用卡与数据中重新禁止。");
      s.primary("允许并切换到卡 " + target, () -> switchTo(s, target, true))
          .secondary("取消", s::dismiss)
          .show();
    } else switchTo(null, target, false);
  }

  void switchTo(GuardUi.Sheet sheet, int target, boolean allow) {
    if (busy) {
      toast("正在处理，请稍后");
      return;
    }
    busy = true;
    switching = target;
    if (sheet != null) sheet.dismiss();
    render();
    io.execute(() -> {
      try {
        JSONObject result =
            RootClient.request(
                new JSONObject()
                    .put("action", "quickSwitch")
                    .put("slot", target)
                    .put("allow", allow));
        GuardApplication.syncLock(result);
        main.post(() -> {
          busy = false;
          switching = 0;
          if (isDestroyed()) return;
          status = result;
          slot = target;
          render();
          toast("已切换到卡 " + target + " 上网");
        });
      } catch (Exception e) {
        main.post(() -> {
          busy = false;
          switching = 0;
          render();
          load(false, true);
          showMessage("未能切换到卡 " + target, safeError(e));
        });
      }
    });
  }

  void metric(LinearLayout p, String label, String value) {
    LinearLayout r = new LinearLayout(this);
    r.setGravity(Gravity.CENTER_VERTICAL);
    r.addView(ui.text(label, 13, ui.muted), new LinearLayout.LayoutParams(0, -2, 1));
    r.addView(ui.heading(value, 14));
    p.addView(r);
    ui.space(p, 8);
  }

  void progress(LinearLayout p, long used, long total) {
    ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
    bar.setMax(1000);
    bar.setProgress(
        total > 0 ? (int) Math.min(1000, Math.max(0, (double) used / total * 1000)) : 0);
    bar.setProgressTintList(android.content.res.ColorStateList.valueOf(ui.accent));
    bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(ui.line));
    p.addView(bar, new LinearLayout.LayoutParams(-1, dp(5)));
  }

  void overview() throws Exception {
    JSONObject card = selected(),
        cc = card.getJSONObject("config"),
        u = card.getJSONObject("usage");
    JSONObject official = currentOfficial();
    LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(-1, -2);
    barLp.setMargins(0, dp(2), 0, dp(4));
    body.addView(limitBar(), barLp);
    balanceHeader(card, official);
    LinearLayout p = panel();
    ui.section(
        p,
        "定向流量包",
        "管理",
        () -> {
          tab = 1;
          render();
        });
    int shown = 0, hidden = 0;
    JSONArray rows = official == null ? null : official.optJSONArray("packages");
    Set<String> seen = new HashSet<>();
    if (rows != null)
      for (int i = 0; i < rows.length(); i++) {
        JSONObject row = rows.optJSONObject(i);
        if (row == null
            || !"2".equals(row.optString("type"))
            || officialExpired(row)
            || !seen.add(row.optString("id", "row-" + i))) continue;
        if (shown >= 4) {
          hidden++;
          continue;
        }
        shown++;
        ui.row(
            p,
            "packages",
            row.optString("name"),
            "剩余 "
                + officialAmount(row.optLong("remaining", -1))
                + " · 总量 "
                + officialAmount(row.optLong("total", -1)),
            () -> openOfficialPack(row.optString("id")));
      }
    JSONArray packs = cc.getJSONArray("packages");
    for (int i = 0; i < packs.length(); i++) {
      final int index = i;
      JSONObject pack = packs.getJSONObject(i);
      if (pack.has("officialPackageId") && seen.contains(pack.optString("officialPackageId")))
        continue;
      if (shown >= 4) {
        hidden++;
        continue;
      }
      shown++;
      ui.row(p, "packages", pack.optString("name"), packEstimate(pack, u), () -> packDetails(index));
    }
    if (shown == 0)
      ui.row(p, "add", "添加定向流量包", "勾选本卡官方套餐，或手动新增应用与规则", this::addPackageSheet);
    if (hidden > 0) note(p, "另有 " + hidden + " 个，在流量包页面查看全部。");
    if (official != null)
      ui.row(p, "chevron", "查看官方套餐明细", "逐项查看通用、定向与套外", () -> officialDetails(official));
    p = panel();
    ui.section(
        p,
        "应用蜂窝用量",
        "说明",
        () -> showMessage("用量如何计算", "系统蜂窝总量为唯一总账。应用用量只作为分类参考，共享 UID 无法拆开；未核实的定向量不会扣减通用保护预算。"));
    ui.space(p, 4);
    JSONObject uids = u.optJSONObject("uids");
    List<String> ids = new ArrayList<>();
    if (uids != null) uids.keys().forEachRemaining(ids::add);
    ids.sort((a, b) -> Long.compare(uids.optLong(b), uids.optLong(a)));
    for (String id : ids.subList(0, Math.min(5, ids.size()))) {
      String[] pkgs = getPackageManager().getPackagesForUid(Integer.parseInt(id));
      metric(p, pkgs == null ? "系统 UID " + id : appLabel(pkgs[0]), bytes(uids.optLong(id)));
    }
    if (ids.isEmpty()) note(p, "还没有可展示的应用用量。");
  }

  View carrierIcon(String carrier) {
    int resource =
        switch (carrier) {
          case "mobile" -> R.drawable.ic_carrier_mobile;
          case "unicom" -> R.drawable.ic_carrier_unicom;
          default -> 0;
        };
    ImageView icon = new ImageView(this);
    icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
    icon.setContentDescription(carrierName(carrier) + "官方 App 图标");
    if (resource != 0) {
      icon.setImageResource(resource);
      return icon;
    }
    if (carrier.equals("telecom"))
      try {
        icon.setImageDrawable(getPackageManager().getApplicationIcon("com.ct.client"));
        return icon;
      } catch (PackageManager.NameNotFoundException ignored) {
      }
    TextView fallback = ui.text(carrier.equals("telecom") ? "电" : "SIM", 11, carrierColor(carrier));
    fallback.setGravity(Gravity.CENTER);
    fallback.setBackground(ui.shape(ui.bg, 9));
    return fallback;
  }

  String packEstimate(JSONObject pack, JSONObject u) throws Exception {
    JSONObject balance = directedBalance(pack);
    if (balance != null)
      return (balance.optBoolean("official") ? "官方" : "本地估算")
          + " · 已用 "
          + carrierBytes(balance.optLong("used", -1))
          + " · 剩余 "
          + carrierBytes(balance.optLong("remaining", -1));
    if (pack.optBoolean("requireBoth")) return "应用与规则同时匹配 · 独立观察";
    JSONArray all = selected().getJSONObject("config").getJSONArray("packages");
    List<JSONObject> ordered = new ArrayList<>();
    for (int i = 0; i < all.length(); i++) ordered.add(all.getJSONObject(i));
    ordered.sort(Comparator.comparingInt(p -> p.optInt("priority", 100)));
    Set<Integer> seen = new HashSet<>();
    JSONObject uid = u.optJSONObject("uids");
    long sum = 0;
    for (JSONObject item : ordered) {
      if (item.optBoolean("requireBoth")) continue;
      long value = 0;
      JSONArray apps = item.getJSONArray("apps");
      for (int j = 0; j < apps.length(); j++)
        try {
          int id = getPackageManager().getApplicationInfo(apps.getString(j), 0).uid;
          if (seen.add(id) && uid != null) value += uid.optLong("" + id);
        } catch (PackageManager.NameNotFoundException ignored) {
        }
      if (item.optString("id").equals(pack.optString("id"))) {
        sum = value;
        break;
      }
    }
    return "应用参考 " + bytes(sum) + " · 待官方验证";
  }

  JSONObject directedBalance(JSONObject pack) throws Exception {
    JSONObject all = status.optJSONObject("directedBalances");
    JSONArray values = all == null ? null : all.optJSONArray(selected().getString("identity"));
    if (values != null)
      for (int i = 0; i < values.length(); i++)
        if (pack.optString("id").equals(values.getJSONObject(i).optString("id")))
          return values.getJSONObject(i);
    return null;
  }

  String packageProtection(JSONObject pack) {
    return "用尽提醒"
        + (pack.optBoolean("notifyWhenEmpty", true) ? "开启" : "关闭")
        + " · 用尽保护"
        + (pack.optBoolean("stopWhenEmpty")
            ? (pack.optBoolean("confirmWhenEmpty") ? "断开确认" : "连续提醒")
            : "关闭");
  }

  void packages() throws Exception {
    JSONObject cc = selected().getJSONObject("config");
    JSONObject official = currentOfficial();
    LinearLayout p = panel();
    ui.section(p, "本卡定向余额", official == null ? null : "明细", () -> officialDetails(official));
    if (official != null) {
      directedSummary(p, official);
      note(p, "数据来源 · " + sourceLabel(official));
    } else note(p, "在总览更新本卡官方余量后，这里显示官方定向总量、已用与剩余。");
    ui.row(p, "shield", "定向额度与用尽设置", "逐包设置提醒、用尽保护和断开确认", this::packageLimitsSheet);
    LinearLayout heading = ui.column();
    heading.setPadding(dp(4), dp(6), 0, 0);
    ui.section(heading, "我的流量包", "＋ 添加", this::addPackageSheet);
    body.addView(heading);
    JSONArray packs = cc.getJSONArray("packages"), catalog = cc.optJSONArray("officialCatalog");
    LinearLayout pending = null;
    for (int i = 0; catalog != null && i < catalog.length(); i++) {
      JSONObject item = catalog.getJSONObject(i);
      String id = item.optString("officialPackageId");
      if (packIndex(id) >= 0 || officialExpired(item)) continue;
      if (pending == null) {
        pending = panel();
        pending.addView(ui.text("本卡官方包 · 尚未配置应用和规则", 12, ui.muted));
      }
      JSONObject balance = item.optJSONObject("officialBalance");
      ui.row(
          pending,
          "add",
          item.optString("name"),
          balance == null
              ? "点击添加应用和规则"
              : "剩余 "
                  + officialAmount(balance.optLong("remaining", -1))
                  + " · 总量 "
                  + officialAmount(balance.optLong("total", -1)),
          () -> openOfficialPack(id));
    }
    if (packs.length() == 0 && pending == null) {
      p = panel();
      title(p, "让每个流量包有自己的账本");
      note(p, "可选择多个应用，或添加多个域名规则。");
      button(p, "选择或新增流量包", this::addPackageSheet);
    }
    for (int i = 0; i < packs.length(); i++) {
      final int index = i;
      JSONObject pack = packs.getJSONObject(i);
      p = panel();
      p.setPadding(dp(16), dp(6), dp(16), dp(10));
      ui.row(
          p,
          "packages",
          pack.optString("name"),
          pack.optJSONArray("apps").length()
              + " 个应用 · "
              + pack.optJSONArray("rules").length()
              + " 条规则",
          () -> packDetails(index));
      JSONObject balance = directedBalance(pack);
      LinearLayout stats = new LinearLayout(this);
      if (balance == null) {
        stat(stats, "总量", packQuota(pack), 15);
        p.addView(stats);
        note(p, packEstimate(pack, selected().getJSONObject("usage")));
      } else {
        stat(stats, "剩余", officialAmount(balance.optLong("remaining", -1)), 15);
        stat(stats, "已用", officialAmount(balance.optLong("used", -1)), 15);
        stat(stats, "总量", packQuota(pack), 15);
        p.addView(stats);
        if (balance.optBoolean("manual")) note(p, "手动已用基准 · " + time(balance.optLong("manualAt")));
        else if (!balance.optBoolean("official")) note(p, "本地估算");
      }
      if (balance != null && balance.optBoolean("exhausted")) note(p, "该定向包已用尽，继续使用可能消耗通用流量。");
      ui.divider(p);
      ui.row(p, "shield", "已用量与用尽设置", packageProtection(pack), () -> packProtection(index));
    }
    p = panel();
    ui.row(p, "download", "导入流量包配置", "支持本地配置文件", this::importFile);
    ui.row(p, "apps", "启用应用规则观察", "为绑定应用及规则使用应用启用观察", this::requestAppScopes);
  }

  String officialSource() {
    return code(cardFor(slot));
  }

  String numberLabel(JSONObject c) {
    if (c == null) return "无可用号码";
    String n = c.optString("numberMasked");
    return n.isEmpty() ? "点击完善本卡号码" : n;
  }

  boolean officialExpired(JSONObject pack) {
    return pack.optBoolean("expired")
        || OfficialTotals.expired(pack.optString("expires"), System.currentTimeMillis());
  }

  JSONObject currentOfficial() {
    try {
      JSONObject all = status.optJSONObject("officialByCard"),
          card = selected(),
          result = all == null ? null : all.optJSONObject(card.optString("identity"));
      return result != null && card.optString("identity").equals(result.optString("boundIdentity"))
          ? result
          : null;
    } catch (Exception e) {
      return null;
    }
  }

  String officialAmount(long value) {
    return value < 0 ? "未提供／不限量" : carrierBytes(value);
  }

  LinearLayout balanceHeader(JSONObject card, JSONObject official) {
    JSONObject groups = official == null ? null : official.optJSONObject("aggregates");
    JSONObject general = groups == null ? null : groups.optJSONObject("1");
    OfficialTotals.Summary directed = directedTotals(official);
    LinearLayout p = panel();
    p.setBackground(ui.outlined(ui.soft, 24, ui.soft));
    p.setPadding(dp(18), dp(16), dp(18), dp(16));
    boolean manual = manualBalance(card, official);
    JSONObject local = card.optJSONObject("localSince");
    // The official figure less what this phone used since; the next official read replaces it.
    boolean live =
        !manual
            && general != null
            && local != null
            && local.optLong("remaining", -1) >= 0
            && general.optLong("remaining", -1) >= 0
            && local.optLong("baseRemaining", -2) == general.optLong("remaining", -1);
    long localGeneral = live ? local.optLong("general") : 0;
    // The chip names the figure's own base: a later unchanged official read does not reset it.
    boolean deducted = live && local.optLong("total") > 0;
    LinearLayout top = new LinearLayout(this);
    top.setGravity(Gravity.CENTER_VERTICAL);
    top.addView(
        ui.text(manual ? "通用剩余 · 手动设置" : deducted ? "通用剩余 · 实时估算" : "通用剩余", 13, ui.muted),
        new LinearLayout.LayoutParams(0, -2, 1));
    top.addView(
        ui.chip(
            official == null
                ? alias(card) + " · 待更新"
                : deducted
                    ? alias(card)
                        + " · 官方 "
                        + new SimpleDateFormat("MM-dd HH:mm", Locale.ROOT)
                            .format(new Date(local.optLong("asOf")))
                        + " 起扣减"
                    : alias(card) + " · " + sourceLabel(official),
            ui.accent,
            ui.surface));
    p.addView(top);
    ui.space(p, 2);
    p.addView(
        ui.heading(
            manual
                ? carrierBytes(card.optLong("remaining", -1))
                : live
                    ? carrierBytes(local.optLong("remaining"))
                    : general == null ? "待获取" : officialAmount(general.optLong("remaining", -1)),
            34));
    if (general != null && !manual && general.optLong("total", -1) > 0) {
      long used = general.optLong("used", -1) < 0 ? -1 : general.optLong("used") + localGeneral;
      ui.space(p, 6);
      progress(p, Math.max(0, used), general.optLong("total"));
      ui.space(p, 6);
      p.addView(
          ui.text(
              "已用 "
                  + officialAmount(used)
                  + " · 总量 "
                  + officialAmount(general.optLong("total", -1)),
              12,
              ui.muted));
    } else if (manual) note(p, "此后定向应用和规则的流量从定向扣减，其余按通用扣减；官方查询成功后以官方为准。");
    if (live && local.optLong("total") > 0)
      note(
          p,
          "官方 "
              + new SimpleDateFormat("HH:mm", Locale.ROOT).format(new Date(local.optLong("asOf")))
              + " 之后本机已用 "
              + carrierBytes(local.optLong("total"))
              + "（通用 "
              + carrierBytes(local.optLong("general"))
              + " · 定向 "
              + carrierBytes(local.optLong("directed"))
              + "），已实时扣减"
              + (official.optLong("fetchedAt") - local.optLong("asOf") > 60000
                  ? "；"
                      + new SimpleDateFormat("HH:mm", Locale.ROOT)
                          .format(new Date(official.optLong("fetchedAt")))
                      + " 再次查询运营商数据未变化，继续按本机扣减"
                  : "")
              + "。下次官方数据更新后以官方为准。");
    thresholdRow(p, card);
    LinearLayout stats = new LinearLayout(this);
    stat(
        stats,
        directed.unknownRemaining() > 0 ? "定向剩余（已知）" : "定向剩余",
        official == null
            ? "待获取"
            : directed.count() == 0
                ? "暂无套餐"
                : officialAmount(
                    directed.unknownRemaining() == directed.count() ? -1 : directed.remaining()));
    long cellular = card.optLong("cellular", -1);
    if (cellular < 0) {
      JSONObject usage = card.optJSONObject("usage");
      cellular = usage == null ? -1 : usage.optLong("raw", -1);
    }
    stat(stats, "本期蜂窝用量", carrierBytes(cellular));
    p.addView(stats);
    if (directed.unknownRemaining() > 0)
      note(p, "另有 " + directed.unknownRemaining() + " 个定向包未提供余额，不计入合计。");
    if (official != null) {
      outsideNotice(p, official);
      if (System.currentTimeMillis() - official.optLong("fetchedAt") > 86400000L)
        note(p, "官方数据已超过 1 天未更新，可能需要重新登录。");
    }
    LinearLayout actions = new LinearLayout(this);
    boolean supported = Set.of("mobile", "unicom").contains(code(card));
    LinearLayout first = ui.column(), second = ui.column();
    if (supported) officialButton(first);
    else ui.button(first, "打开官方查询", true, () -> launchCarrier(code(card)));
    ui.button(second, "手动设置", false, this::manualGeneralSheet).setBackground(ui.ripple(ui.surface, 28));
    actions.addView(first, new LinearLayout.LayoutParams(0, -2, 2.2f));
    LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, -2, 1);
    sp.leftMargin = dp(8);
    actions.addView(second, sp);
    ui.space(p, 4);
    p.addView(actions);
    return p;
  }

  String sourceLabel(JSONObject official) {
    String mode = official.optString("sourceMode");
    long at = "sms".equals(mode) || "web".equals(mode) ? official.optLong("queryAt") : official.optLong("fetchedAt");
    String when = at <= 0 ? "" : new SimpleDateFormat("MM-dd HH:mm", Locale.ROOT).format(new Date(at));
    return ("sms".equals(mode) ? "短信 " : "web".equals(mode) ? "营业厅 " : "官方 ") + when;
  }

  void stat(LinearLayout row, String label, String value) {
    stat(row, label, value, 18);
  }

  void stat(LinearLayout row, String label, String value, int size) {
    LinearLayout c = ui.column();
    c.setPadding(0, dp(10), dp(8), 0);
    c.addView(ui.text(label, 12, ui.muted));
    TextView v = ui.heading(value, size);
    v.setMaxLines(2);
    v.setEllipsize(TextUtils.TruncateAt.END);
    c.addView(v);
    row.addView(c, new LinearLayout.LayoutParams(0, -2, 1));
  }

  /** The most recent figure wins: a value typed after the last official query is the current one. */
  void thresholdRow(LinearLayout p, JSONObject card) {
    JSONObject config = card.optJSONObject("config");
    long reserve = config == null ? 500000000 : config.optLong("reserve", 500000000);
    long left = card.optLong("remaining", -1);
    boolean enabled = status.optJSONObject("config").optBoolean("enabled");
    ui.row(
        p,
        "shield",
        "剩余阈值 · " + bytes(reserve),
        !enabled
            ? "通用限制未开启，不按阈值停数据"
            : left < 0
                ? "余量未知，暂不按阈值限制"
                : left <= reserve ? "已达到阈值，不能用此卡上网" : "距离阈值还有 " + bytes(left - reserve),
        this::editCard);
  }

  boolean manualBalance(JSONObject card, JSONObject official) {
    JSONObject config = card.optJSONObject("config");
    JSONObject baseline = config == null ? null : config.optJSONObject("calibration");
    return baseline != null
        && "手动设置余量".equals(baseline.optString("source"))
        && card.optLong("remaining", -1) >= 0
        && (official == null || baseline.optLong("asOf") > official.optLong("fetchedAt"));
  }

  void manualGeneralSheet() {
    try {
      JSONObject card = selected();
      int target = slot;
      GuardUi.Sheet s = ui.sheet("手动设置通用流量", "卡 " + target + " · " + provider(card) + " · " + numberLabel(card));
      EditText value = ui.field(s.content, "通用流量剩余", "", true, "例如 30");
      Switch gib = ui.toggle(s.content, "单位用 GB（关闭为 MB）", true);
      note(s.content, "此后的蜂窝用量会从这里扣减，官方查询成功后自动以官方为准。保存不会自动开启移动数据。");
      s.primary(
              "保存流量",
              () -> {
                try {
                  long left = amount(value, gib.isChecked() ? 1073741824L : 1048576L, false);
                  submit(
                      s,
                      () ->
                          RootClient.request(
                              new JSONObject()
                                  .put("action", "manualGeneral")
                                  .put("slot", target)
                                  .put("identity", card.getString("identity"))
                                  .put("remaining", left)),
                      "本卡流量已设置");
                } catch (Exception e) {
                  s.error(safeError(e));
                }
              })
          .secondary("取消", s::dismiss)
          .show();
    } catch (Exception e) {
      showMessage("暂时无法设置", safeError(e));
    }
  }

  /** A failed update always offers the next step instead of a dead end. */
  void officialFailed(String message) {
    GuardUi.Sheet s = ui.sheet("未能更新本卡余量", message);
    ui.row(
        s.content,
        "edit",
        "手动设置流量",
        "填写官方 App 或短信里的剩余流量",
        () -> {
          s.dismiss();
          manualGeneralSheet();
        });
    if (officialSource().equals("mobile"))
      ui.row(
          s.content,
          "shield",
          "登录网上营业厅",
          "用短信验证码登录，之后不需要官方 App",
          () -> {
            s.dismiss();
            try {
              openWebLogin(selected());
            } catch (Exception ignored) {
            }
          });
    if (jiangsuApp(cardFor(slot)))
      ui.row(
          s.content,
          "apps",
          "打开中国移动江苏 App",
          "手动进入余量查询，页面加载后自动读取",
          () -> {
            s.dismiss();
            openJiangsuApp();
          });
    if (officialSource().equals("mobile"))
      ui.row(
          s.content,
          "refresh",
          "发短信查询",
          "不需要 App 登录",
          () -> {
            s.dismiss();
            smsQuery();
          });
    ui.row(
        s.content,
        "shield",
        "查看连接方法",
        "模块如何取得官方余量",
        () -> {
          s.dismiss();
          connectionHelp();
        });
    s.primary("知道了", s::dismiss).show();
  }

  void connectionHelp() {
    String carrier = officialSource();
    GuardUi.Sheet s = ui.sheet("官方余量连接方法", "不需要手动获取或填写令牌，登录状态由官方 App 提供。");
    if (carrier.equals("unicom")) {
      title(s.content, "中国联通");
      note(s.content, "1. 在 LSPosed 为流量守卫勾选“中国联通”。");
      note(s.content, "2. 强行停止联通 App 后重新打开，让模块生效。");
      note(s.content, "3. 用本卡号码登录，打开一次首页或余量页面。");
      note(s.content, "约 10 秒后自动取得连接，之后可后台更新。登录号码必须与本卡一致，否则不会保存。另一张联通卡需要在分身里登录。");
    } else if (carrier.equals("mobile")) {
      JSONObject card = cardFor(slot);
      title(s.content, "中国移动 · " + regionLabel(card));
      note(s.content, "网上营业厅：全国移动号码可用。用本卡号码和短信验证码登录后，一键更新和定时查询都在模块里完成，不需要官方 App；登录状态只保存在本机，失效时会提示重新登录。");
      note(s.content, "短信查询：向 10086 发送 CXLL，回复里有通用、定向和套外用量，不需要登录。");
      if (jiangsuApp(card)) {
        title(s.content, "中国移动江苏 App");
        note(s.content, "1. 在 LSPosed 为流量守卫勾选“中国移动江苏”。分身里的 App 要在分身用户下单独勾选。");
        note(s.content, "2. 强行停止江苏移动 App 后重新打开。");
        note(s.content, "3. 用本卡号码登录，点击流量进入“余量查询”一次。");
        note(s.content, "之后一键更新和定时都在后台读取，不影响屏幕；也可以随时在校准页手动打开 App 获取。");
      } else note(s.content, "中国移动江苏 App 只支持江苏号码。归属地按 SIM 号码识别，SIM 不提供号码时按卡片编号识别。");
    } else note(s.content, "此运营商暂未接入官方查询，请手动设置流量。");
    s.primary("知道了", s::dismiss).show();
  }

  void enableModule() {
    GuardApplication.setOff(this, false);
    startForegroundService(new Intent(this, GuardService.class));
    render();
    load(true);
  }

  void shutdownSheet() {
    GuardUi.Sheet s = ui.sheet("关闭模块并恢复系统？", "停止全部限制和后台监控，把移动数据交还系统。");
    note(s.content, "会打开当前默认卡的移动数据，开机不再启动。设置、流量包和累计记录都保留，之后可以重新开启。");
    note(s.content, "LSPosed 里的作用域不受影响；重启后电话保护才会完全退出，不影响上网。");
    s.primary(
            "关闭模块",
            () -> {
              GuardApplication.setOff(this, true);
              s.dismiss();
              io.execute(() -> {
                try {
                  JSONObject result = RootClient.action("shutdown");
                  GuardApplication.syncLock(result);
                  main.post(() -> {
                    status = null;
                    render();
                    toast("模块已关闭，移动数据已交还系统");
                  });
                } catch (Exception e) {
                  GuardApplication.setOff(this, false);
                  main.post(() -> showMessage("未能关闭模块", safeError(e)));
                }
              });
            })
        .secondary("取消", s::dismiss)
        .show();
  }

  /** Paid usage sits outside the general total, which is why the carrier's used figure is higher. */
  void outsideNotice(LinearLayout p, JSONObject official) {
    JSONArray rows = official.optJSONArray("outside");
    if (rows == null || rows.length() == 0) return;
    long used = 0, fen = 0;
    boolean usedKnown = true, fenKnown = true;
    for (int i = 0; i < rows.length(); i++) {
      JSONObject row = rows.optJSONObject(i);
      if (row == null) continue;
      usedKnown &= row.optLong("used", -1) >= 0;
      fenKnown &= row.optLong("fen", -1) >= 0;
      used += Math.max(0, row.optLong("used", -1));
      fen += Math.max(0, row.optLong("fen", -1));
    }
    ui.space(p, 10);
    p.addView(
        ui.chip(
            "已产生套外流量", ui.dark ? 0xff3a2a22 : 0xffffffff, ui.dark ? 0xffffb38e : 0xffcb6036));
    ui.space(p, 8);
    metric(p, "套外已用", usedKnown ? carrierBytes(used) : "未提供");
    metric(p, "套外费用", com.dalong.dataguard.core.OutsidePlan.yuan(fenKnown ? fen : -1));
    JSONObject groups = official.optJSONObject("aggregates");
    JSONObject general = groups == null ? null : groups.optJSONObject("1");
    if (general != null && usedKnown && general.optLong("used", -1) >= 0)
      note(
          p,
          "官方 App 的已用含套外："
              + carrierBytes(general.optLong("used"))
              + " + "
              + carrierBytes(used)
              + " = "
              + carrierBytes(general.optLong("used") + used)
              + "。费用以运营商账单为准。");
  }

  OfficialTotals.Summary directedTotals(JSONObject result) {
    List<OfficialTotals.Package> values = new ArrayList<>();
    JSONArray rows = result == null ? null : result.optJSONArray("packages");
    if (rows != null)
      for (int i = 0; i < rows.length(); i++) {
        JSONObject row = rows.optJSONObject(i);
        if (row == null || !"2".equals(row.optString("type"))) continue;
        values.add(
            new OfficialTotals.Package(
                row.optString("id", "row-" + i),
                row.optLong("total", -1),
                row.optLong("used", -1),
                row.optLong("remaining", -1),
                officialExpired(row)));
      }
    return OfficialTotals.sum(values);
  }

  void directedSummary(LinearLayout p, JSONObject result) {
    OfficialTotals.Summary s = directedTotals(result);
    if (s.count() == 0) {
      note(p, "暂无有效的官方定向套餐。");
      return;
    }
    metric(
        p,
        s.unknownTotal() > 0 ? "定向总量（已知）" : "定向总量",
        officialAmount(s.unknownTotal() == s.count() ? -1 : s.total()));
    metric(
        p,
        s.unknownUsed() > 0 ? "定向已用（已知）" : "定向已用",
        officialAmount(s.unknownUsed() == s.count() ? -1 : s.used()));
    metric(
        p,
        s.unknownRemaining() > 0 ? "定向剩余（已知）" : "定向剩余",
        officialAmount(s.unknownRemaining() == s.count() ? -1 : s.remaining()));
    if (s.unknownTotal() > 0 || s.unknownRemaining() > 0)
      note(p, "共 " + s.count() + " 个定向包；未提供的总量或余量不计入合计，是否不限量以套餐说明为准。");
    note(p, "不同包不可互抵，以各包余额为准。");
  }

  void packAmounts(LinearLayout p, JSONObject pack) throws Exception {
    metric(p, "总量", packQuota(pack));
    JSONObject balance = directedBalance(pack);
    if (balance == null) {
      note(p, packEstimate(pack, selected().getJSONObject("usage")));
      return;
    }
    metric(p, "已用", officialAmount(balance.optLong("used", -1)));
    metric(p, "剩余", officialAmount(balance.optLong("remaining", -1)));
    note(
        p,
        balance.optBoolean("manual")
            ? "手动已用基准 · " + time(balance.optLong("manualAt")) + " · 后续追加识别增量"
            : balance.optBoolean("official")
                ? "官方余量 · " + time(balance.optLong("officialAt"))
                : "本地估算"
                    + (balance.optLong("officialAt") > 0
                        ? " · 最近官方查询 " + time(balance.optLong("officialAt"))
                        : ""));
  }

  void official() throws Exception {
    JSONObject card = selected(), result = currentOfficial();
    String source = code(card);
    LinearLayout p = panel();
    ui.section(p, provider(card) + " · 官方查询", "连接方法", this::connectionHelp);
    JSONObject queries = status.optJSONObject("officialQueries");
    JSONObject query = queries == null ? null : queries.optJSONObject(card.getString("identity"));
    if (query != null)
      p.addView(
          ui.text(
              "最近一次 · " + time(query.optLong("at")) + " · " + GuardStore.cleanMessage(query.optString("message")),
              12,
              "error".equals(query.optString("state")) ? ui.warn : ui.muted));
    else if (result == null)
      note(
          p,
          card.optString("numberKey").isEmpty()
              ? "完善本卡号码后，可核对并显示本卡官方余量。"
              : "暂无与本卡号码一致的官方余量。请在官方 App 使用该号码登录。");
    if (source.equals("mobile")) {
      boolean app = jiangsuApp(card), web = MobileWeb.usable(this, card.getString("identity"));
      officialButton(p);
      note(
          p,
          web
              ? "优先走网上营业厅，不需要官方 App，熄屏也能定时更新；登录还在但这次没读到，会立刻再读一次。登录失效后"
                  + (app ? "先发短信，仍不行再用江苏 App。" : "改发短信。")
              : app
                  ? "江苏移动号码：在后台读取江苏 App 余量页，不影响屏幕。首次需在 App 点击流量进入余量查询一次。"
                  : regionNote(card));
      if (app) {
        var framework = GuardApplication.framework;
        if (framework != null && !framework.getScope().contains(Platform.MOBILE_APP))
          ui.row(p, "shield", "授权读取中国移动江苏 App", "向 LSPosed 申请作用域，批准后才能读取余量", this::requestAppScopes);
        ui.row(p, "apps", "打开中国移动江苏 App", "手动进入余量查询，页面加载后自动读取", this::openJiangsuApp);
      }
      String webCard = MobileWeb.identity(this);
      boolean webHere = webCard.equals(card.getString("identity"));
      ui.row(
          p,
          "shield",
          "网上营业厅",
          !webHere
              ? "登录后一键更新和定时查询都不需要官方 App，全国移动号码可用"
              : MobileWeb.prefs(this).getBoolean("expired", false)
                  ? "登录已失效，点此重新登录"
                  : "已登录 " + MobileWeb.prefs(this).getString("telephone", "") + " · 优先使用",
          () -> webSheet(card));
      scheduleRow(p);
      ui.row(p, "refresh", "发短信查询", "向 10086 发送 CXLL，不需要 App 登录", this::smsQuery);
      Switch auto =
          ui.toggle(p, "后台更新失败时发短信", card.getJSONObject("config").optBoolean("smsSync"));
      auto.setOnCheckedChangeListener((button, on) -> {
        if (busy) {
          render();
          return;
        }
        try {
          JSONObject next = copyConfig();
          mutableCard(next).put("smsSync", on);
          submit(null, configRequest(next), on ? "已开启短信备用查询" : "已关闭短信备用查询");
        } catch (Exception e) {
          render();
          showMessage("设置未保存", safeError(e));
        }
      });
      note(p, "回复会出现在短信收件箱，模块不会删除。查询短信一般免费，是否收费以套餐为准。");
    } else if (source.equals("unicom")) {
      officialButton(p);
      note(p, "在中国联通 App 用本卡号码登录并进入余量查询后，模块自动取得连接，之后后台定时更新。登录过期时一键更新会打开 App。");
      ui.row(p, "apps", "打开本卡官方查询", "使用本卡号码登录中国联通", () -> launchCarrier("unicom"));
      scheduleRow(p);
    } else if (source.equals("telecom")) {
      ui.button(p, "打开电信官方查询", true, () -> launchCarrier("telecom"));
      note(p, "电信官方自动读取尚未接入，可按官方结果手动校准。");
    } else note(p, "此运营商暂无官方查询，可先使用本地统计与手动校准。");
    p = panel();
    ui.section(p, "官方与本地对照", result == null ? null : "明细", () -> officialDetails(result));
    JSONObject groups = result == null ? null : result.optJSONObject("aggregates");
    JSONObject general = groups == null ? null : groups.optJSONObject("1");
    LinearLayout stats = new LinearLayout(this);
    stat(
        stats,
        "官方通用剩余",
        general == null ? "待获取" : officialAmount(general.optLong("remaining", -1)),
        16);
    stat(stats, "本地通用估算", carrierBytes(card.optLong("remaining", -1)), 16);
    p.addView(stats);
    if (result != null) {
      outsideNotice(p, result);
      note(p, sourceLabel(result) + " · 仅展示与本卡号码一致的结果");
    }
    generalAccountingNote(p, card.getJSONObject("config"));
    ui.divider(p);
    ui.row(p, "edit", "手动设置流量", "直接填写通用剩余", this::manualGeneralSheet);
    JSONObject snap = card.getJSONObject("config").optJSONObject("calibration");
    ui.row(
        p,
        "edit",
        "手动校准通用用量",
        snap == null
            ? "仅填写官方通用已用，不合并定向已用"
            : "当前基准：已用 " + carrierBytes(snap.optLong("used")) + " · " + time(snap.optLong("asOf")),
        this::manualCorrection);
    p = panel();
    ui.row(p, "sim", "本卡号码", numberLabel(card) + " · " + regionLabel(card), this::bindNumberSheet);
    ui.row(
        p,
        "sim",
        "SIM 识别详情",
        provider(card) + " · " + card.optString("carrierSource", "等待识别"),
        this::simDetails);
    note(p, "本地蜂窝用量按 SIM 单独记账；运营商登录账号不匹配时不混入本卡统计。");
  }

  boolean showRegion() {
    return getPreferences(0).getBoolean("showRegion", true);
  }

  boolean jiangsuApp(JSONObject card) {
    return card != null && card.optBoolean("jiangsuApp");
  }

  String regionLabel(JSONObject card) {
    String province = card == null ? "" : card.optString("province");
    return province.isEmpty() ? "归属地未识别" : "归属地 " + province;
  }

  String regionNote(JSONObject card) {
    String province = card.optString("province");
    return province.isEmpty()
        ? "未识别本卡号码归属地。可登录网上营业厅或发短信查询；江苏号码在“本卡号码”填写号码后可使用江苏 App。"
        : province + "移动号码：中国移动江苏 App 不适用，请登录网上营业厅或发短信查询。";
  }

  void openJiangsuApp() {
    try {
      JSONObject card = selected();
      getPreferences(0)
          .edit()
          .putString("officialPending", card.getString("identity"))
          .putLong("officialPendingAt", System.currentTimeMillis())
          .apply();
      submit(
          null,
          () -> {
            try {
              return RootClient.request(
                  new JSONObject()
                      .put("action", "openJiangsuApp")
                      .put("slot", card.getInt("slot"))
                      .put("identity", card.getString("identity")));
            } catch (Exception e) {
              getPreferences(0).edit().remove("officialPending").apply();
              throw e;
            }
          },
          "进入余量查询后自动读取，返回本页查看结果");
    } catch (Exception e) {
      showMessage("暂时无法打开", safeError(e));
    }
  }

  void smsQuery() {
    try {
      JSONObject card = selected();
      getPreferences(0)
          .edit()
          .putString("officialPending", card.getString("identity"))
          .putLong("officialPendingAt", System.currentTimeMillis())
          .apply();
      submit(
          null,
          () ->
              RootClient.request(
                  new JSONObject()
                      .put("action", "smsQuery")
                      .put("slot", card.getInt("slot"))
                      .put("identity", card.getString("identity"))),
          "已发送查询短信，等待 10086 回复");
    } catch (Exception e) {
      showMessage("暂时无法查询", safeError(e));
    }
  }

  void scheduleRow(LinearLayout p) throws Exception {
    ui.row(
        p,
        "clock",
        "自动更新频率",
        "官方"
            + GuardDaemon.minutesLabel(config().optInt("scheduleMinutes", 10))
            + " · 使用中约 10 秒",
        this::editSchedule);
  }

  /** Tapping a preset fills the field; any other typed value is the custom choice. */
  void presets(LinearLayout p, EditText field, int... minutes) {
    LinearLayout row = new LinearLayout(this);
    row.setPadding(0, dp(8), 0, 0);
    List<TextView> chips = new ArrayList<>();
    Runnable mark =
        () -> {
          String typed = field.getText().toString().trim();
          for (int i = 0; i < chips.size(); i++) {
            boolean on = typed.equals("" + minutes[i]);
            chips.get(i).setTextColor(on ? (ui.dark ? 0xff1b2746 : Color.WHITE) : ui.muted);
            chips.get(i).setBackground(ui.ripple(on ? ui.accent : ui.surface, 13));
          }
        };
    for (int i = 0; i < minutes.length; i++) {
      int value = minutes[i];
      TextView t =
          ui.text(value % 60 == 0 ? value / 60 + " 小时" : value + " 分钟", 13, ui.muted);
      t.setGravity(Gravity.CENTER);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(36), 1);
      if (i > 0) lp.leftMargin = dp(6);
      row.addView(t, lp);
      chips.add(t);
      t.setOnClickListener(
          v -> {
            field.setText("" + value);
            field.setSelection(field.length());
          });
    }
    field.addTextChangedListener(
        new android.text.TextWatcher() {
          @Override
          public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

          @Override
          public void onTextChanged(CharSequence s, int start, int before, int count) {}

          @Override
          public void afterTextChanged(android.text.Editable s) {
            mark.run();
          }
        });
    mark.run();
    p.addView(row);
  }

  void updateCurrent(boolean official) {
    try {
      JSONObject card = selected();
      int target = slot;
      String identity = card.getString("identity"), carrier = code(card);
      if (official && card.optString("numberKey").isEmpty()) {
        bindNumberSheet();
        return;
      }
      if (busy) {
        toast("正在更新，请稍后");
        return;
      }
      busy = true;
      if (official)
        getPreferences(0)
            .edit()
            .putString("officialPending", identity)
            .putLong("officialPendingAt", System.currentTimeMillis())
            .apply();
      if (official && carrier.equals("mobile") && MobileWeb.usable(this, identity))
        webRefresh(target, identity);
      else rootRefresh(target, identity, official, false);
    } catch (Exception e) {
      showMessage("暂时无法更新", safeError(e));
    }
  }

  /** Web hall first. A miss while the login remains is read once more; only a real logout uses SMS, then the app. */
  void webRefresh(int target, String identity) {
    webReading = true;
    render();
    toast("正在通过网上营业厅查询");
    readWeb(target, identity, true);
  }

  void readWeb(int target, String identity, boolean allowRetry) {
    MobileWeb.read(
        this,
        (page, error) -> {
          if (page == null && allowRetry && !"login".equals(error)) {
            toast("网上营业厅没有读到，再读一次");
            readWeb(target, identity, false);
            return;
          }
          if (page == null) {
            webReading = false;
            if (!"login".equals(error)) {
              busy = false;
              getPreferences(0).edit().remove("officialPending").apply();
              toast("网上营业厅连读两次都没有响应，登录仍在");
              render();
              return;
            }
            MobileWeb.expire(MainActivity.this);
            MobileWeb.lost(identity);
            JSONObject failed = cardFor(target);
            JSONObject cfg = failed == null ? null : failed.optJSONObject("config");
            toast(
                cfg != null && cfg.optBoolean("smsSync")
                    ? "网上营业厅登录已失效，改为发送查询短信"
                    : "网上营业厅登录已失效，改为读取江苏 App");
            rootRefresh(target, identity, true, true);
            return;
          }
          io.execute(() -> {
            try {
              MobileWeb.submit(identity, page);
              MobileWeb.remember(this, identity, page.optString("telephone"));
              main.post(() -> {
                webReading = false;
                busy = false;
                if (!isDestroyed()) load(false, true);
              });
            } catch (Exception e) {
              main.post(() -> {
                webReading = false;
                busy = false;
                getPreferences(0).edit().remove("officialPending").apply();
                if (!isDestroyed()) officialFailed(safeError(e));
              });
            }
          });
        });
  }

  void rootRefresh(int target, String identity, boolean official, boolean skipWeb) {
    try {
      io.execute(() -> {
        try {
          JSONObject result =
              RootClient.request(
                  new JSONObject()
                      .put("action", "refreshCurrent")
                      .put("slot", target)
                      .put("identity", identity)
                      .put("official", official)
                      .put("skipWeb", skipWeb));
          main.post(() -> {
            busy = false;
            if (isDestroyed()) return;
            status = result;
            render();
            toast(
                !official
                    ? "本卡蜂窝用量已更新"
                    : "正在查询本卡官方余量");
          });
        } catch (Exception e) {
          main.post(() -> {
            busy = false;
            if (official) {
              getPreferences(0).edit().remove("officialPending").apply();
              officialFailed(safeError(e));
            } else showMessage("未能更新本卡余量", safeError(e));
          });
        }
      });
    } catch (Exception e) {
      showMessage("暂时无法更新", safeError(e));
    }
  }

  void webSheet(JSONObject card) {
    try {
      String identity = card.getString("identity");
      if (!MobileWeb.identity(this).equals(identity)) {
        openWebLogin(card);
        return;
      }
      GuardUi.Sheet s =
          ui.sheet("网上营业厅", "卡 " + card.getInt("slot") + " · " + MobileWeb.prefs(this).getString("telephone", ""));
      ui.row(
          s.content,
          "shield",
          "打开网上营业厅",
          "登录失效时在这里重新登录",
          () -> {
            s.dismiss();
            openWebLogin(card);
          });
      ui.row(
          s.content,
          "stop",
          "退出登录",
          "清除本机保存的登录状态。之后先发短信，仍不行再用江苏 App",
          () -> {
            s.dismiss();
            android.webkit.CookieManager.getInstance().removeAllCookies(null);
            android.webkit.CookieManager.getInstance().flush();
            android.webkit.WebStorage.getInstance().deleteAllData();
            MobileWeb.prefs(this).edit().clear().apply();
            MobileWeb.lost(identity);
            toast("已退出网上营业厅");
            render();
          });
      s.primary("关闭", s::dismiss).show();
    } catch (Exception e) {
      showMessage("暂时无法打开", safeError(e));
    }
  }

  void openWebLogin(JSONObject card) {
    try {
      startActivity(
          new Intent(this, WebLoginActivity.class)
              .putExtra("identity", card.getString("identity"))
              .putExtra("slot", card.getInt("slot")));
    } catch (Exception e) {
      showMessage("暂时无法打开", safeError(e));
    }
  }

  /** While a read runs the button says so, instead of looking as if nothing happened. */
  void officialButton(LinearLayout p) {
    boolean reading = officialReading();
    ui.button(
        p,
        reading ? "正在读取官方余量…" : "一键更新官方余量",
        true,
        () -> {
          if (officialReading()) toast("正在读取本卡官方余量，完成后会提示");
          else updateCurrent(true);
        });
  }

  boolean officialReading() {
    if (webReading) return true;
    try {
      String id = selected().getString("identity");
      JSONObject queries = status == null ? null : status.optJSONObject("officialQueries");
      JSONObject query = queries == null ? null : queries.optJSONObject(id);
      return id.equals(getPreferences(0).getString("officialPending", ""))
          && query != null
          && "waiting".equals(query.optString("state"))
          && System.currentTimeMillis() - query.optLong("at") < 120000;
    } catch (Exception e) {
      return false;
    }
  }

  /** The result of the update this screen started, once the user can actually see it. */
  JSONObject officialOutcome(JSONObject result) {
    if (!foreground) return null;
    String id = getPreferences(0).getString("officialPending", "");
    JSONObject queries = result.optJSONObject("officialQueries");
    JSONObject query = id.isEmpty() || queries == null ? null : queries.optJSONObject(id);
    return query != null
            && Set.of("updated", "error").contains(query.optString("state"))
            && query.optLong("at") >= getPreferences(0).getLong("officialPendingAt", 0)
        ? query
        : null;
  }

  void announceOfficial(JSONObject query) {
    getPreferences(0).edit().remove("officialPending").apply();
    String message = GuardStore.cleanMessage(query.optString("message"));
    if ("updated".equals(query.optString("state")))
      Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    else officialFailed(message);
  }

  void bindNumberSheet() {
    try {
      JSONObject card = selected();
      int target = slot;
      String identity = card.getString("identity");
      GuardUi.Sheet s = ui.sheet("关联本卡号码", "卡 " + target + " · " + provider(card));
      if (!card.optString("numberSource").isEmpty()
          && card.optString("numberSource").equals("sim")) {
        note(s.content, "号码已从 SIM 自动读取：" + numberLabel(card));
        s.primary("完成", s::dismiss).show();
        return;
      }
      EditText number = ui.field(s.content, "本卡手机号码", "", false, "请输入完整的 11 位号码");
      number.setInputType(android.text.InputType.TYPE_CLASS_PHONE);
      note(s.content, "部分 SIM 不提供号码。请填写当前卡槽对应的真实号码；本地仅保存脱敏显示和关联摘要，完整号码不写入日志。");
      s.primary(
              "关联到本卡",
              () -> {
                try {
                  String n =
                      com.dalong.dataguard.core.PhoneIdentity.normalize(
                          number.getText().toString());
                  if (n.isEmpty()) {
                    number.setError("请输入完整的 11 位本卡号码");
                    throw new IllegalArgumentException("请检查本卡号码");
                  }
                  submit(
                      s,
                      () ->
                          RootClient.request(
                              new JSONObject()
                                  .put("action", "bindNumber")
                                  .put("slot", target)
                                  .put("identity", identity)
                                  .put("number", n)),
                      "本卡号码已关联");
                } catch (Exception e) {
                  s.error(safeError(e));
                }
              })
          .show();
    } catch (Exception e) {
      showMessage("暂时无法关联", safeError(e));
    }
  }

  void officialDetails(JSONObject result) {
    GuardUi.Sheet s = ui.sheet("官方套餐详情", "本卡官方套餐明细，计费以运营商账单为准。");
    directedSummary(s.content, result);
    JSONArray packs = result.optJSONArray("packages");
    if (packs != null)
      for (String type : new String[] {"2", "1", "other"}) {
        boolean heading = false;
        for (int i = 0; i < packs.length(); i++) {
          JSONObject p = packs.optJSONObject(i);
          if (p == null) continue;
          String actual = p.optString("type");
          if (!(type.equals(actual)
              || (type.equals("other") && !Set.of("1", "2").contains(actual)))) continue;
          if (!heading) {
            ui.space(s.content, 18);
            title(s.content, type.equals("2") ? "定向流量" : type.equals("1") ? "通用流量" : "其他流量");
            heading = true;
          }
          ui.row(
              s.content,
              "packages",
              p.optString("name"),
              officialExpired(p)
                  ? "已失效 · 不计入有效合计"
                  : p.optLong("remaining", -1) == 0 ? "已用尽" : "本卡官方套餐",
              actual.equals("2")
                  ? () -> {
                    s.dismiss();
                    openOfficialPack(p.optString("id"));
                  }
                  : null);
          metric(s.content, "总量", officialAmount(p.optLong("total", -1)));
          metric(s.content, "已用", officialAmount(p.optLong("used", -1)));
          metric(s.content, "剩余", officialAmount(p.optLong("remaining", -1)));
          if (!p.optString("expires").isEmpty()) note(s.content, "有效期至 " + p.optString("expires"));
          if (p.optBoolean("shared")) note(s.content, "共享流量包");
        }
      }
    JSONArray outside = result.optJSONArray("outside");
    if (outside != null && outside.length() > 0) {
      ui.space(s.content, 18);
      title(s.content, "套外流量");
      for (int i = 0; i < outside.length(); i++) {
        JSONObject row = outside.optJSONObject(i);
        if (row == null) continue;
        ui.row(s.content, "packages", row.optString("name"), "超出套餐部分", null);
        metric(s.content, "已用", officialAmount(row.optLong("used", -1)));
        metric(
            s.content,
            "费用",
            com.dalong.dataguard.core.OutsidePlan.yuan(row.optLong("fen", -1)));
      }
    }
    note(s.content, "获取时间 · " + time(result.optLong("fetchedAt")));
    note(s.content, "已核对本卡号码。计费统计截至时间仍需以运营商说明为准。");
    s.primary("知道了", s::dismiss).show();
  }

  void protection() throws Exception {
    if (!getSystemService(NotificationManager.class).areNotificationsEnabled()) {
      LinearLayout alert = panel();
      ui.row(
          alert,
          "shield",
          "通知未开启 · 提醒不会弹出",
          "定向用尽和套外流量提醒需要通知权限，点击去开启",
          () ->
              startActivity(
                  new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                      .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName())));
    }
    JSONObject cc = selected().getJSONObject("config");
    LinearLayout p = panel();
    ui.section(p, "限制", "说明", this::limitsHelp);
    if (wifiPaused()) p.addView(ui.text("Wi-Fi 联网中，额度限制临时暂停；断开后自动恢复。", 12, ui.accent));
    boolean enabled = config().optBoolean("enabled");
    Switch generalLimit = ui.toggle(p, "通用流量限制 · 两张卡共用", enabled);
    generalLimit.setOnCheckedChangeListener((button, checked) -> {
      if (busy) {
        render();
        return;
      }
      try {
        JSONObject next = copyConfig().put("enabled", checked);
        generalLimit.setEnabled(false);
        submit(null, configRequest(next), checked ? "通用流量限制已开启" : "通用流量限制已关闭");
      } catch (Exception e) {
        render();
        showMessage("设置未保存", safeError(e));
      }
    });
    ui.row(
        p,
        "edit",
        "卡 " + slot + " 剩余阈值 · " + bytes(cc.optLong("reserve", 500000000)),
        "本地通用估算 " + carrierBytes(selected().optLong("remaining", -1)) + " · 不高于阈值时停数据",
        this::editCard);
    JSONArray packs = cc.getJSONArray("packages");
    int stopping = 0;
    for (int i = 0; i < packs.length(); i++)
      if (packs.getJSONObject(i).optBoolean("stopWhenEmpty")) stopping++;
    ui.row(
        p,
        "packages",
        "定向包用尽设置",
        "卡 " + slot + " · " + stopping + " / " + packs.length() + " 个包开启用尽保护",
        this::packageLimitsSheet);
    long used = status.optLong("budgetUsed"), limit = config().optLong("budgetLimit");
    ui.row(
        p,
        "shield",
        "本机累计限制 · " + (config().optBoolean("budgetEnabled") ? "开启" : "关闭"),
        "已用 "
            + bytes(used)
            + (config().optBoolean("budgetEnabled") ? " / 上限 " + bytes(limit) : " · 默认关闭，关闭不清零"),
        this::editBudget);
    if (used >= cumulativeStopLine(config())) note(p, "当前已达到本机累计上限，暂时无法恢复移动数据。");
    ui.row(
        p,
        "shield",
        "Wi-Fi 暂停限制 · " + (config().optBoolean("pauseOnWifi", true) ? "开启" : "关闭"),
        wifiPaused() ? "当前已暂停 · 断开后自动恢复" : "Wi-Fi 联网时可手动切卡、开启数据",
        this::wifiSettingsSheet);
    p = panel();
    ui.section(p, "用卡与数据", null, null);
    ui.row(
        p,
        status.optBoolean("dataOn") ? "play" : "stop",
        "当前数据卡 · " + dataCardName(),
        (status.optBoolean("dataOn") ? "移动数据已开启" : "移动数据已关闭") + " · 右上角可一键切卡",
        this::dataCardSheet);
    ui.row(
        p,
        "swap",
        "用卡策略",
        wifiPaused() ? "临时暂停 · 允许手动切卡" : modeLabel(config().optString("mode", "fixed")) + lockedSummary(),
        this::editPolicy);
    ui.row(
        p,
        "sim",
        "卡 " + slot + " 用卡权限",
        cardLocked(selected()) ? "已禁止使用移动数据 · 点击解除" : "允许使用 · 点击可禁止",
        () -> cardAccessSheet(slot));
    if (!status.optBoolean("dataOn"))
      note(p, "最近执行结果：" + GuardStore.cleanMessage(status.optString("receipt", "等待核验")));
    ui.button(
        p,
        status.optBoolean("dataOn") ? "停止移动数据" : "核验后恢复" + resumeName() + "数据",
        !status.optBoolean("dataOn"),
        () -> {
          if (status.optBoolean("dataOn")) action("stop", "移动数据已关闭");
          else resumeSheet();
        });
    p = panel();
    ui.section(p, "显示", null, null);
    Switch region = ui.toggle(p, "顶部 SIM 卡片显示号码归属地", showRegion());
    region.setOnCheckedChangeListener((button, on) -> {
      getPreferences(0).edit().putBoolean("showRegion", on).apply();
      int y = scroll.getScrollY();
      render();
      scroll.post(() -> scroll.scrollTo(0, y));
    });
    note(p, "归属地按本卡号码识别，只在本机显示；校准页的本卡号码始终显示归属地。");
    p = panel();
    ui.section(p, "模块", null, null);
    String fw = getSharedPreferences("module", 0).getString("framework", "");
    LinearLayout health = new LinearLayout(this);
    stat(
        health,
        "Root 监控",
        System.currentTimeMillis() - status.optLong("sampleAt") < 10000 ? "运行中" : "连接过期",
        14);
    stat(health, "电话保护", status.optBoolean("hookCurrentBoot") ? "已就绪" : "待启用", 14);
    stat(health, "LSPosed", fw.isEmpty() ? "待连接" : fw, 14);
    p.addView(health);
    if (!status.optBoolean("hookCurrentBoot"))
      note(p, "电话保护未就绪时不影响上网；已禁止使用数据的卡仍由后台监控关闭。");
    if (!status.optString("fault").isEmpty()) note(p, status.optString("fault"));
    ui.divider(p);
    ui.row(p, "download", "备份与导入设置", "保存或恢复限制、流量包和规则", this::backupSheet);
    ui.row(p, "shield", "LSPosed 模块设置", "管理作用域和框架状态", () -> launch("org.lsposed.manager"));
    ui.row(p, "stop", "关闭模块并恢复系统", "停止全部限制，移动数据交还系统", this::shutdownSheet);
    note(p, "版本 " + BuildConfig.VERSION_NAME);
  }

  void limitsHelp() {
    GuardUi.Sheet s = ui.sheet("限制如何生效", "各开关分别生效，保存不会自动开启数据。");
    title(s.content, "Wi-Fi 暂停限制");
    note(
        s.content,
        "默认开启。当前默认网络为已联网的 Wi-Fi"
            + " 时，临时暂停通用阈值、定向用尽提醒及断开确认、用户累计上限和自动用卡策略。允许手动切卡、开数据；断开后恢复原规则。计数不中断，SIM 与监控异常仍生效。仅打开"
            + " Wi-Fi 开关或网络需登录时不暂停。");
    title(s.content, "本机累计上限");
    note(
        s.content,
        "默认关闭。开启后，累计本机蜂窝收发用量，包含通用、定向及后台流量，达到设置上限即停数据。关闭仍保留计数，不按月清零。");
    title(s.content, "通用流量限制");
    note(s.content, "只需设置剩余停止阈值，默认 500 MB，支持 MB／GB。开启后当前数据卡的通用可用估算不高于阈值时停数据；余量未知时不限制；总量自动跟随官方，无官方查询的卡手动填写，定向包单独判断。");
    title(s.content, "定向包用尽设置");
    note(s.content, "每个包分别设置。用尽提醒只提示；开启用尽保护后，通用仍有可用余量默认可继续。若开启先断开再确认，会先停数据再询问是否继续使用通用流量。");
    title(s.content, "用卡策略与卡槽限制");
    note(s.content, "可选择停数据、自动切卡或固定用卡。每张卡可在用卡与数据中单独禁止使用移动数据，被禁止的卡不会作为备用目标；Wi-Fi 暂停期间可手动切换。保存不会自动恢复联网。");
    s.primary("知道了", s::dismiss).show();
  }

  boolean wifiPaused() {
    return status != null && status.optBoolean("wifiPaused");
  }

  long cumulativeStopLine(JSONObject cfg) {
    return PolicyEngine.cumulativeLimit(
        cfg != null && cfg.optBoolean("budgetEnabled"),
        cfg == null ? 0 : cfg.optLong("budgetLimit"),
        wifiPaused());
  }

  boolean cardLocked(JSONObject c) {
    JSONObject cfg = c == null ? null : c.optJSONObject("config");
    return cfg != null && (cfg.optBoolean("dataForbidden") || cfg.optBoolean("newSim"));
  }

  String dataCardName() {
    int s = status.optInt("activeSlot");
    return s > 0 ? "卡 " + s : "暂无数据卡";
  }

  String resumeName() {
    int s = status.optInt("defaultSlot");
    return s > 0 ? "卡 " + s + " " : "移动";
  }

  String lockedSummary() {
    StringBuilder text = new StringBuilder();
    for (int s = 1; s <= 2; s++)
      if (cardLocked(cardFor(s))) text.append(" · 卡 ").append(s).append(" 禁止联网");
    return text.toString();
  }

  void wifiSettingsSheet() {
    try {
      JSONObject next = copyConfig();
      GuardUi.Sheet s = ui.sheet("Wi-Fi 暂停限制", "默认开启 · 保留原开关和阈值");
      Switch enabled =
          ui.toggle(s.content, "Wi-Fi 联网时暂停额度限制", next.optBoolean("pauseOnWifi", true));
      metric(s.content, "当前状态", wifiPaused() ? "额度限制已暂停" : "额度限制按原设置生效");
      note(s.content, "Wi-Fi 正在承担联网时，暂停通用阈值、用户累计上限、定向用尽提醒与断开确认，可手动切换数据卡、开启移动数据。不会自动开启数据或切卡。");
      note(
          s.content,
          "断开、网络需登录或默认联网转为蜂窝后恢复原规则。已达阈值时会停数据；已禁止使用数据的卡也会恢复禁止。仅打开 Wi-Fi"
              + " 开关不算联网。蜂窝计数持续累计，SIM 与监控异常保护仍保留。");
      s.primary(
              "保存设置",
              () -> {
                try {
                  next.put("pauseOnWifi", enabled.isChecked());
                  submit(s, configRequest(next), "Wi-Fi 暂停设置已保存");
                } catch (Exception e) {
                  s.error(safeError(e));
                }
              })
          .secondary("取消", s::dismiss)
          .show();
    } catch (Exception e) {
      showMessage("暂时无法设置", safeError(e));
    }
  }

  void editBudget() {
    try {
      JSONObject next = copyConfig();
      long used = status.optLong("budgetUsed"), limit = next.getLong("budgetLimit");
      GuardUi.Sheet s = ui.sheet("本机累计限制", "默认关闭 · 独立于运营商通用与定向套餐");
      Switch enabled = ui.toggle(s.content, "启用本机累计限制", next.optBoolean("budgetEnabled"));
      metric(s.content, "累计已用 · 含通用与定向", bytes(used));
      long maximum = 1_000_000_000_000L;
      String range = "0.001–1000";
      EditText value =
          ui.field(
              s.content,
              "累计停止上限 · GB",
              BigDecimal.valueOf(limit, 9).stripTrailingZeros().toPlainString(),
              true,
              range);
      note(
          s.content,
          "1 GB = 1,000 MB。开启时可设置 "
              + range
              + " GB，达到设置上限即停数据。关闭后不按累计用量限制。");
      note(s.content, "关闭不清零计数。开启且上限不高于已用量时，会停数据并拒绝恢复；提高上限或关闭开关均不会自动开启数据。");
      value.setEnabled(enabled.isChecked());
      enabled.setOnCheckedChangeListener((button, checked) -> value.setEnabled(checked));
      note(s.content, "通用阈值和定向包保护按各自开关独立生效。");
      if (wifiPaused()) note(s.content, "当前 Wi-Fi 联网，用户累计上限临时暂停；新上限会在暂停结束后恢复生效。");
      s.primary(
              "保存累计限制",
              () -> {
                try {
                  long n = enabled.isChecked() ? amount(value, 1000000000L, false) : limit;
                  if (n < 1000000 || n > maximum) {
                    value.setError("请输入 " + range + " GB");
                    throw new IllegalArgumentException("累计上限必须在 " + range + " GB 内");
                  }
                  next.put("budgetLimit", n).put("budgetEnabled", enabled.isChecked());
                  if (enabled.isChecked() && !wifiPaused() && n <= status.optLong("budgetUsed")) {
                    s.dismiss();
                    GuardUi.Sheet confirm = ui.sheet("新上限已触线", "新上限不高于累计已用量。保存后将停止移动数据，恢复请求也会被拒绝。");
                    metric(confirm.content, "累计已用", bytes(status.optLong("budgetUsed")));
                    metric(confirm.content, "新停止上限", bytes(n));
                    confirm
                        .primary(
                            "保存并停止移动数据",
                            () -> submit(confirm, configRequest(next), "累计上限已保存，移动数据保持关闭"))
                        .secondary(
                            "返回修改",
                            () -> {
                              confirm.dismiss();
                              editBudget();
                            })
                        .show();
                  } else
                    submit(
                        s,
                        configRequest(next),
                        enabled.isChecked() ? "累计限制已开启，已用记录保持不变" : "累计限制已关闭，计数保留");
                } catch (Exception e) {
                  s.error(safeError(e));
                }
              })
          .secondary("取消", s::dismiss)
          .show();
    } catch (Exception e) {
      showMessage("暂时无法设置累计上限", safeError(e));
    }
  }

  void generalAccountingNote(LinearLayout p, JSONObject cc) {
    JSONObject baseline = cc.optJSONObject("calibration");
    if (baseline != null
        && baseline.optLong("cycleStart", -1)
            == UsageMath.carrierCycleStart(System.currentTimeMillis(), cc.optInt("cycleDay", 1)))
      note(
          p,
          "通用估算以 "
              + time(baseline.optLong("asOf"))
              + " 的官方通用已用为基准，追加此后的蜂窝增量：定向包绑定应用和命中规则的流量从定向扣减（不超过该包当时的剩余），来源不明的按通用扣减。正在消耗时约 10 秒内更新，官方查询到新用量后直接覆盖。");
    else note(p, "尚无本账期通用校准，蜂窝总用量暂按通用保守计入。请更新本卡官方余量或手动校准通用已用。");
  }

  void packageLimitsSheet() {
    try {
      JSONArray packs = selected().getJSONObject("config").getJSONArray("packages");
      GuardUi.Sheet s = ui.sheet("定向额度与用尽设置", "卡 " + slot + " · 每个包单独设置，独立于通用流量限制。");
      if (packs.length() == 0) note(s.content, "尚未添加定向包，请先在流量包中选择或新增。");
      for (int i = 0; i < packs.length(); i++) {
        int index = i;
        JSONObject pack = packs.getJSONObject(i);
        ui.row(
            s.content,
            "packages",
            pack.optString("name"),
            packageProtection(pack),
            () -> {
              s.dismiss();
              packProtection(index);
            });
      }
      note(s.content, "使用已用尽的定向包对应应用或命中规则时，默认连续提醒 3 次；通用保护余量充足则继续联网。可单独开启先断开再确认，通用保护余量不足或未知时保持关闭。");
      s.primary("完成", s::dismiss).show();
    } catch (Exception e) {
      showMessage("暂时无法设置", safeError(e));
    }
  }

  String modeLabel(String mode) {
    return switch (mode) {
      case "auto" -> "自动切卡";
      case "fixed" -> "固定使用卡 " + status.optJSONObject("config").optInt("fixedSlot");
      default -> "额度不足停数据";
    };
  }

  void simDetails() {
    try {
      JSONObject c = selected();
      GuardUi.Sheet s = ui.sheet("SIM 识别详情", "识别来自实体 SIM 的系统标识，与官方 App 登录账号分别管理。");
      metric(s.content, "卡槽", "卡 " + slot);
      metric(s.content, "运营商", provider(c));
      metric(s.content, "识别依据", c.optString("carrierSource", "等待更新"));
      metric(
          s.content,
          "号码归属地",
          c.optString("province").isEmpty()
              ? "未识别"
              : c.optString("province")
                  + ("iccid".equals(c.optString("provinceSource")) ? " · 按卡片编号" : " · 按号码"));
      metric(s.content, "SIM MCC/MNC", c.optString("mccMnc", "—"));
      metric(s.content, "Carrier ID", "" + c.optInt("carrierId", -1));
      note(s.content, "运营商按 SIM 标识识别，系统无法确认时显示未知，不按号段或漫游网络猜测。归属地按本卡号码查询系统号码库，SIM 不提供号码时按中国移动卡片编号识别。");
      s.primary("知道了", s::dismiss).show();
    } catch (Exception e) {
      showMessage("识别暂不可用", safeError(e));
    }
  }

  void dataCardSheet() {
    if (status == null) {
      showMessage("正在连接", "监控连接后即可查看实际数据卡。");
      return;
    }
    GuardUi.Sheet s = ui.sheet("用卡与数据", "上方 SIM 卡片用于查看；此处管理真正的移动数据卡。");
    int active = status.optInt("activeSlot");
    boolean any = false;
    for (int i = 1; i <= 2; i++) {
      final int target = i;
      JSONObject c = cardFor(i);
      if (c == null) continue;
      any = true;
      JSONObject cfg = c.optJSONObject("config");
      boolean fresh = cfg != null && cfg.optBoolean("newSim");
      boolean reachable = !fresh && (!cardLocked(c) || wifiPaused());
      Runnable open =
          () -> {
            s.dismiss();
            if (reachable) switchSheet(target);
            else cardAccessSheet(target);
          };
      LinearLayout r =
          ui.row(
              s.content,
              "sim",
              "卡 " + i + " · " + provider(c),
              active == i
                  ? "当前数据卡"
                  : fresh
                      ? "新 SIM · 等待确认"
                      : cardLocked(c)
                          ? wifiPaused() ? "已禁用 · Wi-Fi 暂停中可手动切换" : "已禁止使用移动数据 · 点击解除"
                          : "切换后保持数据关闭",
              open);
      if (!reachable) r.setAlpha(.7f);
      if (reachable)
        ui.row(
            s.content,
            "shield",
            "卡 " + i + " 用卡权限",
            "允许使用 · 点击可禁止",
            () -> {
              s.dismiss();
              cardAccessSheet(target);
            });
    }
    if (!any) note(s.content, "未检测到可用 SIM。插入或启用 SIM 后自动识别。");
    note(s.content, "右上角的卡 1／卡 2 按钮可以一键切换并开启数据，长按打开此面板。");
    ui.divider(s.content);
    metric(s.content, "当前移动数据", status.optBoolean("dataOn") ? "开启" : "关闭");
    note(
        s.content,
        wifiPaused()
            ? "Wi-Fi 联网，额度和自动用卡策略临时暂停。可手动选择数据卡；断开后恢复原限制。切换不会自动开启数据。"
            : "当前设置："
                + modeLabel(status.optJSONObject("config").optString("mode"))
                + "。已禁止使用数据的卡不参与切换。");
    if (status.optBoolean("dataOn"))
      s.primary("关闭移动数据", () -> submit(s, () -> RootClient.action("stop"), "移动数据已关闭"));
    else
      s.primary(
          "核验后恢复" + resumeName() + "数据",
          () -> {
            s.dismiss();
            resumeSheet();
          });
    s.secondary(
            "调整用卡策略",
            () -> {
              s.dismiss();
              editPolicy();
            })
        .show();
  }

  void cardAccessSheet(int target) {
    JSONObject c = cardFor(target), cfg = c == null ? null : c.optJSONObject("config");
    if (cfg == null) {
      showMessage("卡槽暂不可用", "插入或启用 SIM 后自动识别。");
      return;
    }
    boolean fresh = cfg.optBoolean("newSim"), forbidden = cfg.optBoolean("dataForbidden");
    GuardUi.Sheet s =
        ui.sheet(
            fresh ? "确认新 SIM" : "卡 " + target + " 用卡权限",
            "卡 " + target + " · " + provider(c) + " · " + numberLabel(c));
    note(
        s.content,
        fresh
            ? "这张 SIM 尚未在本机使用过，确认前不能作为数据卡。"
            : forbidden
                ? "此卡当前禁止使用移动数据，也不会作为备用卡；Wi-Fi 暂停期间可手动使用。"
                : "禁止后此卡不能作为数据卡或备用卡，适合没有流量的卡；Wi-Fi 暂停期间可手动使用。");
    note(s.content, "权限跟随这张 SIM，换卡槽后仍然有效。保存不会自动开启移动数据。");
    Runnable allow = accessRequest(s, target, c, false), deny = accessRequest(s, target, c, true);
    if (fresh) s.primary("允许此卡使用移动数据", allow).secondary("禁止此卡使用移动数据", deny);
    else if (forbidden) s.primary("允许此卡使用移动数据", allow).secondary("取消", s::dismiss);
    else s.primary("禁止此卡使用移动数据", deny).secondary("取消", s::dismiss);
    s.show();
  }

  Runnable accessRequest(GuardUi.Sheet s, int target, JSONObject card, boolean forbidden) {
    return () ->
        submit(
            s,
            () ->
                RootClient.request(
                    new JSONObject()
                        .put("action", "cardAccess")
                        .put("slot", target)
                        .put("identity", card.getString("identity"))
                        .put("forbidden", forbidden)),
            forbidden ? "已禁止此卡使用移动数据" : "已允许此卡使用移动数据");
  }

  void switchSheet(int target) {
    GuardUi.Sheet s = ui.sheet("切换移动数据卡", "目标为卡 " + target + " · " + provider(cardFor(target)));
    note(s.content, "当前实际数据卡：卡 " + status.optInt("activeSlot") + "。切卡前先停数据，核验成功后保持关闭；恢复联网需要单独操作。");
    if (wifiPaused() && cardLocked(cardFor(target)))
      note(s.content, "当前为 Wi-Fi 临时放行，提交时会重新核验联网状态。Wi-Fi 暂停结束后，此卡不能保持数据开启。");
    if (status.optInt("activeSlot") == target && status.optInt("defaultSlot") == target) {
      note(s.content, "当前已使用卡 " + target + "，无需重复切换。");
      s.primary("完成", s::dismiss);
    } else
      s.primary(
          "切到卡 " + target + " 并保持数据关闭",
          () ->
              submit(
                  s,
                  () ->
                      RootClient.request(
                          new JSONObject().put("action", "switchCard").put("slot", target)),
                  "已切换并保持数据关闭"));
    s.show();
  }

  void resumeSheet() {
    JSONObject pending = status.optJSONObject("directedPending");
    if (pending != null && !wifiPaused()) {
      startActivity(
          new Intent(this, DirectedPromptActivity.class)
              .putExtra("episode", pending.optString("id")));
      return;
    }
    int targetSlot = status.optInt("defaultSlot");
    GuardUi.Sheet s =
        ui.sheet(
            "恢复" + resumeName() + "的移动数据",
            wifiPaused() ? "Wi-Fi 联网中 · 额度限制临时暂停" : "恢复前重新核验当前数据卡。");
    note(s.content, "将核验 SIM 身份和你设置的限制，触线时保持关闭。");
    if (wifiPaused()) note(s.content, "本次不按通用阈值、定向用尽或用户累计上限拦截；断开 Wi-Fi 后自动恢复原规则，已达停止条件会关闭数据。");
    JSONObject target = cardFor(targetSlot), cfg = status.optJSONObject("config");
    long limit = cumulativeStopLine(cfg);
    metric(
        s.content,
        "通用可用余量 · 本地估算",
        carrierBytes(target == null ? -1 : target.optLong("remaining", -1)));
    metric(
        s.content,
        limit == PolicyEngine.NO_LIMIT ? "本机累计用量" : "本机累计用量 / 当前停止线",
        bytes(status.optLong("budgetUsed"))
            + (limit == PolicyEngine.NO_LIMIT ? "" : " / " + bytes(limit)));
    if (status.optLong("budgetUsed") >= limit)
      note(s.content, "本机累计已达当前停止线，通用与定向余额充足也不会恢复；请核对累计开关。");
    ui.row(
        s.content,
        "edit",
        "设置本机累计上限",
        "不清零已用量，保存后仍需核验恢复",
        () -> {
          s.dismiss();
          editBudget();
        });
    s.primary(
            "核验并恢复" + resumeName() + "数据",
            () -> submit(s, () -> RootClient.action("resume"), resumeName() + "数据已恢复"))
        .secondary("保持关闭", s::dismiss)
        .show();
  }

  void load(boolean start) {
    load(start, false);
  }

  void load(boolean start, boolean keep) {
    if (busy || isDestroyed() || GuardApplication.off(this)) return;
    busy = true;
    io.execute(() -> {
      try {
        if (start) RootClient.start(this);
        JSONObject result = RootClient.action("status");
        main.post(() -> {
          busy = false;
          if (isDestroyed() || isFinishing()) return;
          JSONObject outcome = officialOutcome(result);
          if (keep
              && outcome == null
              && (!foreground
                  || !hasWindowFocus()
                  || SystemClock.elapsedRealtime() - lastTouch <= 2000)) return;
          int y = keep && scroll != null ? scroll.getScrollY() : 0;
          status = result;
          if (initialCard || cardFor(slot) == null) {
            int active = result.optInt("activeSlot");
            if (cardFor(active) != null) slot = active;
            else if (cardFor(3 - slot) != null) slot = 3 - slot;
            initialCard = false;
          }
          render();
          if (keep) scroll.post(() -> scroll.scrollTo(0, y));
          if (outcome != null) announceOfficial(outcome);
        });
      } catch (Exception e) {
        main.post(() -> {
          busy = false;
          if (statusLine != null) statusLine.setText("连接未完成 · 请检查 Root 授权");
        });
      }
    });
  }

  interface Request {
    JSONObject run() throws Exception;
  }

  void submit(GuardUi.Sheet sheet, Request request, String success) {
    if (busy) {
      if (sheet != null) sheet.error("正在更新，请稍后再试");
      return;
    }
    busy = true;
    if (sheet != null) sheet.working(true);
    io.execute(() -> {
      try {
        JSONObject result = request.run();
        GuardApplication.syncLock(result);
        main.post(() -> {
          busy = false;
          if (isDestroyed()) return;
          status = result;
          if (sheet != null) {
            sheet.working(false);
            sheet.dismiss();
          }
          render();
          toast(success);
        });
      } catch (Exception e) {
        main.post(() -> {
          busy = false;
          if (isDestroyed()) return;
          if (sheet != null) {
            sheet.working(false);
            sheet.error(safeError(e));
          } else {
            render();
            showMessage("操作未完成", safeError(e));
          }
        });
      }
    });
  }

  void action(String action, String success) {
    submit(null, () -> RootClient.action(action), success);
  }

  JSONObject copyConfig() throws Exception {
    return new JSONObject(config().toString());
  }

  JSONObject mutableCard(JSONObject config) throws Exception {
    return config.getJSONObject("cards").getJSONObject(selected().getString("identity"));
  }

  Request configRequest(JSONObject next) {
    return () -> RootClient.request(new JSONObject().put("action", "config").put("config", next));
  }

  long decimal(String s, long unit, boolean emptyAllowed) {
    String t = s.trim();
    if (t.isEmpty() && emptyAllowed) return -1;
    try {
      BigDecimal v = new BigDecimal(t);
      if (v.signum() < 0) throw new NumberFormatException();
      return v.multiply(BigDecimal.valueOf(unit))
          .setScale(0, RoundingMode.HALF_UP)
          .longValueExact();
    } catch (Exception e) {
      throw new IllegalArgumentException("请输入有效的非负数值");
    }
  }

  int integer(EditText e, int min, int max) {
    try {
      int n = Integer.parseInt(e.getText().toString().trim());
      if (n < min || n > max) throw new NumberFormatException();
      return n;
    } catch (Exception x) {
      e.setError("请输入 " + min + "–" + max + " 的整数");
      e.requestFocus();
      throw new IllegalArgumentException("请检查标注的输入项");
    }
  }

  long amount(EditText e, long unit, boolean blank) {
    try {
      return decimal(e.getText().toString(), unit, blank);
    } catch (Exception x) {
      e.setError(x.getMessage());
      e.requestFocus();
      throw x;
    }
  }

  String quotaText(long n) {
    return n < 0
        ? ""
        : BigDecimal.valueOf(n)
            .divide(BigDecimal.valueOf(1073741824L), 9, RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString();
  }

  void editCard() {
    try {
      JSONObject next = copyConfig(), c = mutableCard(next);
      GuardUi.Sheet s = ui.sheet("卡 " + slot + " · 通用停止阈值", "只需设置剩余多少 MB／GB 时停数据");
      CheckBox enabled = ui.check(s.content, "启用通用流量限制 · 两张卡共用开关", next.optBoolean("enabled"));
      metric(s.content, "当前通用可用估算", carrierBytes(selected().optLong("remaining", -1)));
      long initial = c.optLong("reserve", 500000000);
      boolean useGb = initial >= 1000000000L;
      EditText threshold =
          ui.field(
              s.content,
              "剩余停止阈值",
              BigDecimal.valueOf(initial, useGb ? 9 : 6).stripTrailingZeros().toPlainString(),
              true,
              "例如 500 MB 或 1 GB");
      JSONObject official = c.optJSONObject("officialGeneral");
      boolean hasOfficial = official != null && official.optLong("total", -1) >= 0;
      EditText total =
          hasOfficial
              ? null
              : ui.field(
                  s.content, "通用总量 · GiB", quotaText(c.optLong("quota", -1)), true, "本卡暂无官方总量时填写");
      Switch gb = ui.toggle(s.content, "使用 GB 输入（关闭为 MB）", useGb);
      if (cardLocked(selected()))
        note(s.content, "这张卡当前禁止使用数据，改阈值不会解除。切换时点允许，或在用卡与数据中解除。");
      gb.setOnCheckedChangeListener((button, checked) -> {
        try {
          BigDecimal n = new BigDecimal(threshold.getText().toString().trim());
          threshold.setText(
              (checked ? n.movePointLeft(3) : n.movePointRight(3))
                  .stripTrailingZeros()
                  .toPlainString());
        } catch (NumberFormatException ignored) {
        }
      });
      note(
          s.content,
          "默认 500 MB；1 GB = 1,000"
              + " MB。通用可用估算不高于阈值时停数据；余量未知时不限制。"
              + (hasOfficial ? "总量随官方套餐，不需要另填。" : "请填写套餐通用总量，再到校准页填写已用，否则余量无法核实。")
              + "定向已用量不会直接从通用余额抵扣。");
      EditText alias = ui.field(s.content, "卡片备注", alias(selected()), false, "例如 工作卡"),
          day = ui.field(s.content, "每月账期开始日", c.optInt("cycleDay", 1) + "", true, "1–31");
      s.primary(
              "保存通用阈值",
              () -> {
                try {
                  long r = amount(threshold, gb.isChecked() ? 1000000000L : 1000000L, false);
                  c.put("alias", alias.getText().toString().trim())
                      .put("reserve", r)
                      .put("cycleDay", integer(day, 1, 31));
                  if (hasOfficial)
                    c.put("quota", official.getLong("total")).put("officialQuotaManaged", true);
                  else {
                    long manual = amount(total, 1073741824L, true);
                    c.put("quota", manual).put("officialQuotaManaged", manual < 0);
                  }
                  next.put("enabled", enabled.isChecked());
                  submit(s, configRequest(next), "通用停止阈值已保存");
                } catch (Exception e) {
                  s.error(safeError(e));
                }
              })
          .show();
    } catch (Exception e) {
      showMessage("暂时无法编辑", safeError(e));
    }
  }

  void packDetails(int index) {
    try {
      JSONObject cc = selected().getJSONObject("config"),
          pack = cc.getJSONArray("packages").getJSONObject(index);
      GuardUi.Sheet s = ui.sheet(pack.optString("name"), "卡 " + slot + " · 定向流量包");
      packAmounts(s.content, pack);
      metric(s.content, "匹配方式", pack.optBoolean("requireBoth") ? "应用与规则同时命中" : "应用或规则任一命中");
      metric(s.content, "优先级", pack.optInt("priority", 100) + "");
      ui.row(
          s.content,
          "shield",
          "用尽保护",
          packageProtection(pack),
          () -> {
            s.dismiss();
            packProtection(index);
          });
      ui.row(
          s.content,
          "edit",
          "手动设置已用量",
          "本账期有效，可恢复官方口径",
          () -> {
            s.dismiss();
            editDirectedUsed(index);
          });
      JSONArray apps = pack.getJSONArray("apps");
      if (apps.length() > 0) title(s.content, "应用");
      for (int i = 0; i < apps.length(); i++) appRow(s.content, apps.getString(i), null);
      JSONArray rules = pack.getJSONArray("rules");
      if (rules.length() > 0) note(s.content, "规则\n" + join(rules));
      if (apps.length() == 0 && rules.length() == 0)
        note(s.content, "还没有配置应用和规则，暂时无法识别这个包的用量。");
      JSONObject obs = cc.optJSONObject("observations"),
          counts = obs == null ? null : obs.optJSONObject("packages"),
          count = counts == null ? null : counts.optJSONObject(pack.optString("id"));
      long cycle = selected().getJSONObject("usage").optLong("cycleStart");
      metric(
          s.content,
          "独立观察负载",
          bytes(
              count == null || obs.optLong("cycleStart") != cycle
                  ? 0
                  : count.optLong("rx") + count.optLong("tx")));
      note(s.content, "观察负载与应用参考分别展示，不相加。定向扣费待运营商验证，不降低通用保护预算。");
      if (pack.optBoolean("shared")) note(s.content, "共享池：" + pack.optString("poolKey"));
      s.primary(
              "编辑应用和规则",
              () -> {
                s.dismiss();
                editPack(index);
              })
          .secondary(
              "移除此流量包",
              () -> {
                s.dismiss();
                removePack(index);
              })
          .show();
    } catch (Exception e) {
      showMessage("暂时无法查看", safeError(e));
    }
  }

  void removePack(int index) {
    try {
      JSONObject next = copyConfig();
      JSONArray packs = mutableCard(next).getJSONArray("packages");
      String name = packs.getJSONObject(index).optString("name");
      GuardUi.Sheet s = ui.sheet("移除流量包？", name);
      note(s.content, "仅移除该包的匹配配置。本卡原始蜂窝总账不受影响。");
      s.primary(
              "确认移除",
              () -> {
                packs.remove(index);
                submit(s, configRequest(next), "流量包已移除");
              })
          .secondary("保留流量包", s::dismiss)
          .show();
    } catch (Exception e) {
      showMessage("暂时无法移除", safeError(e));
    }
  }

  int packIndex(String officialId) throws Exception {
    JSONArray packs = selected().getJSONObject("config").getJSONArray("packages");
    for (int i = 0; i < packs.length(); i++)
      if (officialId.equals(packs.getJSONObject(i).optString("officialPackageId"))) return i;
    return -1;
  }

  /** A carrier's package is one tap from its apps and rules, added or not. */
  void openOfficialPack(String officialId) {
    try {
      int index = packIndex(officialId);
      if (index >= 0) {
        packDetails(index);
        return;
      }
      JSONArray catalog = selected().getJSONObject("config").optJSONArray("officialCatalog");
      for (int i = 0; catalog != null && i < catalog.length(); i++)
        if (officialId.equals(catalog.getJSONObject(i).optString("officialPackageId"))) {
          editPack(-1, new JSONObject(catalog.getJSONObject(i).toString()));
          return;
        }
      showMessage("暂时无法配置", "请先更新本卡官方余量，再添加应用和规则。");
    } catch (Exception e) {
      showMessage("暂时无法配置", safeError(e));
    }
  }

  View appIcon(String pkg) {
    try {
      ImageView icon = new ImageView(this);
      icon.setImageDrawable(getPackageManager().getApplicationIcon(pkg));
      return icon;
    } catch (PackageManager.NameNotFoundException notInstalled) {
      return new GuardUi.Icon(this, "apps", ui.muted);
    }
  }

  boolean installed(String pkg) {
    try {
      getPackageManager().getApplicationInfo(pkg, 0);
      return true;
    } catch (PackageManager.NameNotFoundException e) {
      return false;
    }
  }

  void appRow(LinearLayout parent, String pkg, Runnable remove) {
    LinearLayout row = new LinearLayout(this);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(0, dp(6), 0, dp(6));
    row.addView(appIcon(pkg), new LinearLayout.LayoutParams(dp(32), dp(32)));
    LinearLayout words = ui.column();
    TextView name = ui.heading(appLabel(pkg), 14);
    name.setMaxLines(1);
    name.setEllipsize(TextUtils.TruncateAt.END);
    words.addView(name);
    TextView id = ui.text(pkg + (installed(pkg) ? "" : " · 未安装"), 11, ui.muted);
    id.setMaxLines(1);
    id.setEllipsize(TextUtils.TruncateAt.END);
    words.addView(id);
    LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(0, -2, 1);
    wp.leftMargin = dp(10);
    row.addView(words, wp);
    if (remove != null) {
      TextView action = ui.text("移除", 13, ui.accent);
      action.setPadding(dp(12), dp(8), 0, dp(8));
      action.setContentDescription("移除 " + appLabel(pkg));
      action.setOnClickListener(v -> remove.run());
      row.addView(action);
    }
    parent.addView(row);
  }

  void renderPackApps(LinearLayout list, JSONObject pack) {
    list.removeAllViews();
    JSONArray apps = pack.optJSONArray("apps");
    if (apps == null || apps.length() == 0) {
      note(list, "还没有添加应用。");
      return;
    }
    for (int i = 0; i < apps.length(); i++) {
      int at = i;
      appRow(
          list,
          apps.optString(i),
          () -> {
            apps.remove(at);
            renderPackApps(list, pack);
          });
    }
  }

  void editPack(int index) {
    editPack(index, null);
  }

  void editPack(int index, JSONObject template) {
    try {
      JSONObject next = copyConfig();
      JSONArray packs = mutableCard(next).getJSONArray("packages");
      JSONObject pack =
          index >= 0
              ? new JSONObject(packs.getJSONObject(index).toString())
              : template != null
                  ? template
                  : new JSONObject()
                      .put("id", UUID.randomUUID().toString())
                      .put("apps", new JSONArray())
                      .put("rules", new JSONArray());
      GuardUi.Sheet s =
          ui.sheet(
              index < 0 && template == null ? "添加定向流量包" : "配置应用和规则",
              "卡 " + slot + " · 可添加多个应用和多条规则");
      EditText name = ui.field(s.content, "流量包名称", pack.optString("name"), false, "例如 移动云盘"),
          quota =
              ui.field(
                  s.content, "定向总额 · GiB", quotaText(pack.optLong("quota", -1)), true, "未知可留空");
      quota.setEnabled(!pack.has("officialPackageId"));
      if (pack.has("officialPackageId")) note(s.content, "本包额度与余额来自本卡官方套餐，更新官方余量即可同步。");
      ui.space(s.content, 12);
      title(s.content, "应用");
      LinearLayout apps = ui.column();
      s.content.addView(apps);
      renderPackApps(apps, pack);
      ui.row(
          s.content,
          "apps",
          "添加应用",
          "选择已安装应用，或手动输入包名",
          () -> chooseApps(pack, () -> renderPackApps(apps, pack)));
      EditText rules =
          ui.field(
              s.content,
              "匹配规则 · 每行一条",
              join(pack.getJSONArray("rules")),
              false,
              "cloud.example.com\nexample.com/download/");
      rules.setSingleLine(false);
      rules.setMinLines(3);
      rules.setMaxLines(5);
      rules.setGravity(Gravity.TOP);
      rules.setSelectAllOnFocus(false);
      note(s.content, "填域名，或域名加路径，可直接粘贴网址。规则识别需要在流量包页“启用应用规则观察”。");
      CheckBox both = ui.check(s.content, "应用与规则必须同时命中", pack.optBoolean("requireBoth"));
      note(s.content, "未勾选时，应用或规则任一命中即可归类。");
      Switch[] protection = packageSwitches(s, pack);
      LinearLayout advanced = ui.column();
      advanced.setVisibility(View.GONE);
      ui.row(
          s.content,
          "edit",
          "高级设置",
          "匹配优先级、共享额度池、账号备注",
          () -> advanced.setVisibility(advanced.getVisibility() == View.GONE ? View.VISIBLE : View.GONE));
      s.content.addView(advanced);
      EditText priority =
          ui.field(advanced, "匹配优先级 · 越小越优先", pack.optInt("priority", 100) + "", true, "例如 100");
      CheckBox shared = ui.check(advanced, "属于共享额度池", pack.optBoolean("shared"));
      EditText pool = ui.field(advanced, "共享池标识", pack.optString("poolKey"), false, "开启共享时必填"),
          account = ui.field(advanced, "账号备注", pack.optString("accountAlias"), false, "不填写完整手机号");
      pool.setEnabled(shared.isChecked());
      shared.setOnCheckedChangeListener((v, b) -> pool.setEnabled(b));
      note(s.content, "应用与规则用于分类参考，实际定向扣费以运营商为准。");
      s.primary(
              "保存流量包",
              () -> {
                try {
                  String n = name.getText().toString().trim();
                  if (n.isEmpty()) {
                    name.setError("请输入流量包名称");
                    name.requestFocus();
                    throw new IllegalArgumentException("请填写流量包名称");
                  }
                  JSONArray rr = new JSONArray();
                  for (String raw : rules.getText().toString().split("\\n")) {
                    String line = com.dalong.dataguard.core.AppPackage.ruleLine(raw);
                    if (line.isEmpty()) continue;
                    if (!line.matches("[a-zA-Z0-9.-]+(?::[0-9]+)?(/[^\\s]*)?")) {
                      rules.setError("请填写域名或域名/路径，不含 https://");
                      rules.requestFocus();
                      throw new IllegalArgumentException("请检查匹配规则");
                    }
                    rr.put(line);
                  }
                  if (both.isChecked()
                      && (rr.length() == 0 || pack.getJSONArray("apps").length() == 0))
                    throw new IllegalArgumentException("同时匹配时，需要至少一个应用和一条规则");
                  if (shared.isChecked() && pool.getText().toString().isBlank()) {
                    advanced.setVisibility(View.VISIBLE);
                    pool.setError("请填写共享池标识");
                    pool.requestFocus();
                    throw new IllegalArgumentException("共享池标识不能为空");
                  }
                  pack.put("userSelected", true)
                      .put("name", n)
                      .put(
                          "quota",
                          pack.has("officialPackageId")
                                  || quota
                                      .getText()
                                      .toString()
                                      .trim()
                                      .equals(quotaText(pack.optLong("quota", -1)))
                              ? pack.optLong("quota", -1)
                              : amount(quota, 1073741824L, true))
                      .put("rules", rr)
                      .put("requireBoth", both.isChecked())
                      .put("notifyWhenEmpty", protection[0].isChecked())
                      .put("stopWhenEmpty", protection[1].isChecked())
                      .put("confirmWhenEmpty", protection[2].isChecked())
                      .put("shared", shared.isChecked())
                      .put("poolKey", pool.getText().toString().trim())
                      .put("accountAlias", account.getText().toString().trim())
                      .put("priority", integer(priority, 0, 99999));
                  if (index < 0) {
                    if (!containsId(packs, pack.optString("id"))) packs.put(pack);
                  } else packs.put(index, pack);
                  submit(s, configRequest(next), "流量包已保存");
                } catch (Exception e) {
                  s.error(safeError(e));
                }
              })
          .show();
    } catch (Exception e) {
      showMessage("暂时无法编辑", safeError(e));
    }
  }

  Switch[] packageSwitches(GuardUi.Sheet s, JSONObject pack) {
    Switch notify = ui.toggle(s.content, "使用时连续提醒 3 次", pack.optBoolean("notifyWhenEmpty", true));
    Switch stop = ui.toggle(s.content, "启用定向流量用尽保护", pack.optBoolean("stopWhenEmpty"));
    Switch confirm = ui.toggle(s.content, "使用通用流量前先断开并确认", pack.optBoolean("confirmWhenEmpty"));
    confirm.setEnabled(stop.isChecked());
    stop.setOnCheckedChangeListener((button, checked) -> confirm.setEnabled(checked));
    note(
        s.content,
        "使用已用尽的定向包时连续提醒 3 次；停止使用至少 30"
            + " 秒后再次使用会重新提醒，持续使用不会反复弹出。通用保护余量充足时保持联网；开启先断开再确认后，会关闭数据并等待选择。用尽保护只针对对应应用或命中规则。");
    note(
        s.content,
        "开启断开确认后，会先关闭移动数据，再弹窗询问是否使用通用流量；选择继续后，本卡本账期这次用尽不再重复询问。提醒、用尽保护和断开确认分别设置，通用及累计用量保护始终按各自设置执行。");
    note(s.content, "应用流量和规则识别存在延迟及覆盖限制，余量优先参考本卡官方结果，其余按本地估算；未知定向余量不会按 0 处理。");
    if (!getSystemService(NotificationManager.class).areNotificationsEnabled())
      note(s.content, "系统通知未开启，请在系统设置允许通知以接收余量提醒。");
    return new Switch[] {notify, stop, confirm};
  }

  String packQuota(JSONObject pack) {
    long quota = pack.optLong("quota", -1);
    return pack.has("officialPackageId")
        ? officialAmount(quota)
        : quota < 0 ? "待核实" : carrierBytes(quota);
  }

  void packProtection(int index) {
    try {
      JSONObject next = copyConfig(),
          pack = mutableCard(next).getJSONArray("packages").getJSONObject(index);
      GuardUi.Sheet s = ui.sheet("定向额度与用尽设置", pack.optString("name"));
      packAmounts(s.content, pack);
      EditText quota =
          pack.has("officialPackageId")
              ? null
              : ui.field(
                  s.content, "定向总额 · GiB", quotaText(pack.optLong("quota", -1)), true, "未知可留空");
      note(
          s.content,
          pack.has("officialPackageId")
              ? "本包总量跟随官方。可手动设置本包已用量，手动值会明确标为本地估算，不修改官方余额；用尽行为按下方设置执行。"
              : "本地定向余额由总额减去已归类用量估算；未知额度不会按已用尽处理。修改定向额度不影响通用余额。");
      ui.row(
          s.content,
          "edit",
          "手动设置已用量",
          "可填写已用多少 GiB，仅影响本包本账期",
          () -> {
            s.dismiss();
            editDirectedUsed(index);
          });
      Switch[] switches = packageSwitches(s, pack);
      s.primary(
              "保存保护设置",
              () -> {
                try {
                  if (quota != null
                      && !quota
                          .getText()
                          .toString()
                          .trim()
                          .equals(quotaText(pack.optLong("quota", -1))))
                    pack.put("quota", amount(quota, 1073741824L, true));
                  pack.put("notifyWhenEmpty", switches[0].isChecked())
                      .put("stopWhenEmpty", switches[1].isChecked())
                      .put("confirmWhenEmpty", switches[2].isChecked());
                  submit(s, configRequest(next), "流量包保护设置已保存");
                } catch (Exception e) {
                  s.error(safeError(e));
                }
              })
          .show();
    } catch (Exception e) {
      showMessage("暂时无法设置", safeError(e));
    }
  }

  void editDirectedUsed(int index) {
    try {
      JSONObject card = selected(),
          pack = card.getJSONObject("config").getJSONArray("packages").getJSONObject(index);
      JSONObject balance = directedBalance(pack);
      GuardUi.Sheet s = ui.sheet("手动设置定向已用量", "卡 " + slot + " · " + pack.optString("name"));
      packAmounts(s.content, pack);
      long current = balance == null ? -1 : balance.optLong("used", -1);
      EditText used = ui.field(s.content, "本包当前已用 · GiB", quotaText(current), true, "例如 20 或 49.5");
      note(
          s.content,
          "1 GiB = 1024"
              + " MiB，与本包套餐显示一致。保存值作为此刻的已用基准，后续追加本包识别增量，剩余按总量减已用计算。本账期有效；官方更新不覆盖手动值，可随时恢复官方／自动估算口径。通用余额与本机累计不变。");
      JSONObject request =
          new JSONObject()
              .put("action", "calibrateDirected")
              .put("slot", slot)
              .put("identity", card.getString("identity"))
              .put("packageId", pack.getString("id"));
      s.primary(
          "保存已用量",
          () -> {
            try {
              long value = amount(used, 1073741824L, false);
              request.put("used", value);
              submit(s, () -> RootClient.request(request), "定向已用量已保存");
            } catch (Exception e) {
              s.error(safeError(e));
            }
          });
      if (pack.has("manualUsage"))
        s.secondary(
            "恢复官方／自动估算",
            () ->
                submit(
                    s,
                    () -> RootClient.request(new JSONObject(request.toString()).put("clear", true)),
                    "已恢复官方／自动估算口径"));
      else s.secondary("取消", s::dismiss);
      s.show();
    } catch (Exception e) {
      showMessage("暂时无法设置已用量", safeError(e));
    }
  }

  boolean containsId(JSONArray packs, String id) {
    for (int i = 0; i < packs.length(); i++)
      if (packs.optJSONObject(i).optString("id").equals(id)) return true;
    return false;
  }

  static final int PICKER_ROWS = 60;

  void pickerRow(
      LinearLayout list,
      android.graphics.drawable.Drawable drawable,
      String title,
      String subtitle,
      boolean checked,
      java.util.function.Consumer<Boolean> toggled) {
    LinearLayout row = new LinearLayout(this);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(0, dp(7), 0, dp(7));
    View icon;
    if (drawable == null) icon = new GuardUi.Icon(this, "apps", ui.muted);
    else {
      ImageView image = new ImageView(this);
      image.setImageDrawable(drawable);
      icon = image;
    }
    row.addView(icon, new LinearLayout.LayoutParams(dp(34), dp(34)));
    LinearLayout words = ui.column();
    TextView n = ui.heading(title, 14);
    n.setMaxLines(1);
    n.setEllipsize(TextUtils.TruncateAt.END);
    words.addView(n);
    TextView pkg = ui.text(subtitle, 11, ui.muted);
    pkg.setMaxLines(1);
    pkg.setEllipsize(TextUtils.TruncateAt.END);
    words.addView(pkg);
    LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(0, -2, 1);
    wp.leftMargin = dp(10);
    row.addView(words, wp);
    CheckBox check = new CheckBox(this);
    check.setButtonTintList(android.content.res.ColorStateList.valueOf(ui.accent));
    check.setChecked(checked);
    check.setContentDescription(title);
    check.setOnCheckedChangeListener((v, b) -> toggled.accept(b));
    row.addView(check);
    row.setOnClickListener(v -> check.setChecked(!check.isChecked()));
    list.addView(row);
  }

  void chooseApps(JSONObject pack, Runnable changed) {
    GuardUi.Sheet s = ui.sheet("选择应用", "搜索已安装应用，也可以直接输入包名添加。");
    Set<String> chosen = new LinkedHashSet<>();
    JSONArray existing = pack.optJSONArray("apps");
    if (existing != null)
      for (int i = 0; i < existing.length(); i++) chosen.add(existing.optString(i));
    EditText search = ui.field(s.content, "搜索应用或输入包名", "", false, "例如 微信 或 com.tencent.mm");
    TextView count = ui.text("已选 " + chosen.size() + " 个应用", 12, ui.accent);
    s.content.addView(count);
    LinearLayout list = ui.column();
    list.setMinimumHeight(dp(320));
    s.content.addView(list);
    note(list, "正在读取应用列表…");
    List<ApplicationInfo> all = new ArrayList<>();
    Map<String, ApplicationInfo> byPackage = new HashMap<>();
    Map<String, String> labels = new HashMap<>();
    Set<String> launchable = new HashSet<>();
    Map<String, android.graphics.drawable.Drawable> icons = new HashMap<>();
    java.util.function.Consumer<String> addRow =
        pkg -> {
          ApplicationInfo a = byPackage.get(pkg);
          if (a != null && !icons.containsKey(pkg))
            icons.put(pkg, a.loadIcon(getPackageManager()));
          pickerRow(
              list,
              icons.get(pkg),
              labels.getOrDefault(pkg, pkg),
              a == null ? pkg + " · 未安装" : pkg,
              chosen.contains(pkg),
              on -> {
                if (on) chosen.add(pkg);
                else chosen.remove(pkg);
                count.setText("已选 " + chosen.size() + " 个应用");
              });
        };
    Runnable filter =
        () -> {
          list.removeAllViews();
          String raw = search.getText().toString().trim(), q = raw.toLowerCase(Locale.ROOT);
          java.util.function.Predicate<String> matches =
              pkg ->
                  q.isEmpty()
                      || labels.getOrDefault(pkg, pkg).toLowerCase(Locale.ROOT).contains(q)
                      || pkg.toLowerCase(Locale.ROOT).contains(q);
          int shown = 0, more = 0;
          // Taken from a copy: ticking a row must not disturb the list being drawn.
          for (String pkg : new ArrayList<>(chosen))
            if (matches.test(pkg)) {
              addRow.accept(pkg);
              shown++;
            }
          if (com.dalong.dataguard.core.AppPackage.valid(raw)
              && !chosen.contains(raw)
              && !byPackage.containsKey(raw)) {
            shown++;
            pickerRow(
                list,
                null,
                "添加包名 " + raw,
                "手动添加，当前未安装",
                false,
                on -> {
                  if (on) chosen.add(raw);
                  else chosen.remove(raw);
                  count.setText("已选 " + chosen.size() + " 个应用");
                });
          }
          // Searching reaches system packages too; the idle list stays to everyday apps.
          for (ApplicationInfo a : all) {
            String pkg = a.packageName;
            if (chosen.contains(pkg) || !matches.test(pkg)) continue;
            if (q.isEmpty() && !launchable.contains(pkg)) continue;
            if (shown >= PICKER_ROWS) {
              more++;
              continue;
            }
            addRow.accept(pkg);
            shown++;
          }
          if (more > 0) note(list, "还有 " + more + " 个应用，继续输入可缩小范围。");
          if (shown == 0) note(list, "没有匹配的应用。输入完整包名可直接添加，例如 com.example.app");
        };
    search.addTextChangedListener(
        new TextWatcher() {
          public void beforeTextChanged(CharSequence s, int st, int c, int a) {}

          public void onTextChanged(CharSequence t, int st, int b, int c) {
            main.removeCallbacks(filter);
            if (!all.isEmpty()) main.postDelayed(filter, 150);
          }

          public void afterTextChanged(Editable e) {}
        });
    s.primary(
            "完成选择",
            () -> {
              try {
                main.removeCallbacks(filter);
                pack.put("apps", new JSONArray(chosen));
                changed.run();
                s.dismiss();
              } catch (Exception e) {
                s.error(safeError(e));
              }
            })
        .show();
    io.execute(() -> {
      try {
        PackageManager pm = getPackageManager();
        List<ApplicationInfo> installed = pm.getInstalledApplications(0);
        Map<String, String> names = new HashMap<>();
        Set<String> everyday = new HashSet<>();
        for (ApplicationInfo a : installed) {
          names.put(a.packageName, a.loadLabel(pm).toString());
          if ((a.flags & ApplicationInfo.FLAG_SYSTEM) == 0
              || pm.getLaunchIntentForPackage(a.packageName) != null) everyday.add(a.packageName);
        }
        java.text.Collator order = java.text.Collator.getInstance(Locale.CHINA);
        installed.sort((a, b) -> order.compare(names.get(a.packageName), names.get(b.packageName)));
        main.post(() -> {
          if (!s.dialog.isShowing()) return;
          all.addAll(installed);
          for (ApplicationInfo a : installed) byPackage.put(a.packageName, a);
          labels.putAll(names);
          launchable.addAll(everyday);
          filter.run();
          // HyperOS hides other apps until its "installed apps" permission is granted.
          if (installed.size() < 5) {
            note(s.content, "系统未允许读取应用列表，目前只能手动输入包名。");
            ui.row(
                s.content,
                "shield",
                "去允许读取应用列表",
                "在应用权限中开启后，重新打开此页",
                () ->
                    startActivity(
                        new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(android.net.Uri.parse("package:" + getPackageName()))));
          }
        });
      } catch (Exception e) {
        main.post(() -> s.error("读取应用失败，也可以手动输入包名"));
      }
    });
  }

  void editPolicy() {
    try {
      JSONObject next = copyConfig();
      GuardUi.Sheet s = ui.sheet("用卡策略", "保存后不会自动开启数据。");
      String[] keys = {"stop", "auto", "fixed"},
          names = {"额度不足停数据", "自动切到可用卡", "固定用卡"},
          details = {"剩余达到停止阈值就关闭移动数据", "切到未禁用且余量高于阈值的卡；没有就停数据", "阻止系统改用其他卡"};
      RadioGroup modes = new RadioGroup(this);
      modes.setOrientation(LinearLayout.VERTICAL);
      int[] ids = {View.generateViewId(), View.generateViewId(), View.generateViewId()};
      for (int i = 0; i < 3; i++) {
        RadioButton r = new RadioButton(this);
        r.setId(ids[i]);
        r.setText(names[i] + "\n" + details[i]);
        r.setTextSize(14);
        r.setTextColor(ui.ink);
        r.setButtonTintList(android.content.res.ColorStateList.valueOf(ui.accent));
        r.setMinHeight(dp(62));
        modes.addView(r);
        if (keys[i].equals(next.optString("mode", "fixed"))) modes.check(ids[i]);
      }
      s.content.addView(modes);
      RadioGroup fixedCards = new RadioGroup(this);
      fixedCards.setOrientation(LinearLayout.VERTICAL);
      int[] fixedIds = {View.generateViewId(), View.generateViewId()};
      int chosen = next.optInt("fixedSlot");
      if (cardFor(chosen) == null || cardLocked(cardFor(chosen))) chosen = status.optInt("activeSlot");
      for (int i = 1; i <= 2; i++) {
        JSONObject c = cardFor(i);
        if (c == null) continue;
        RadioButton r = new RadioButton(this);
        r.setId(fixedIds[i - 1]);
        r.setText("卡 " + i + " · " + provider(c) + (cardLocked(c) ? " · 已禁止" : ""));
        r.setEnabled(!cardLocked(c));
        r.setTextColor(ui.ink);
        r.setButtonTintList(android.content.res.ColorStateList.valueOf(ui.accent));
        fixedCards.addView(r);
        if (i == chosen && !cardLocked(c)) fixedCards.check(fixedIds[i - 1]);
      }
      s.content.addView(fixedCards);
      fixedCards.setVisibility(modes.getCheckedRadioButtonId() == ids[2] ? View.VISIBLE : View.GONE);
      modes.setOnCheckedChangeListener(
          (group, id) -> fixedCards.setVisibility(id == ids[2] ? View.VISIBLE : View.GONE));
      CheckBox enabled = ui.check(s.content, "启用通用流量限制", next.optBoolean("enabled"));
      note(s.content, "自动切卡需要开启通用流量限制，切换后自动恢复移动数据；开启前请更新官方余量，并设置通用剩余停止阈值。");
      s.primary(
              "保存用卡策略",
              () -> {
                try {
                  int pos = -1;
                  for (int j = 0; j < ids.length; j++)
                    if (ids[j] == modes.getCheckedRadioButtonId()) pos = j;
                  if (pos < 0) throw new IllegalArgumentException("请选择用卡策略");
                  int fixedSlot = 0;
                  for (int j = 0; j < 2; j++)
                    if (fixedIds[j] == fixedCards.getCheckedRadioButtonId()) fixedSlot = j + 1;
                  if (keys[pos].equals("fixed") && fixedSlot == 0)
                    throw new IllegalArgumentException("请选择固定使用的卡");
                  next.put("mode", keys[pos]).put("enabled", enabled.isChecked());
                  if (fixedSlot > 0) next.put("fixedSlot", fixedSlot);
                  submit(s, configRequest(next), "用卡策略已保存");
                } catch (Exception e) {
                  s.error(safeError(e));
                }
              })
          .show();
    } catch (Exception e) {
      showMessage("暂时无法编辑", safeError(e));
    }
  }

  void editSchedule() {
    try {
      JSONObject next = copyConfig();
      GuardUi.Sheet s = ui.sheet("自动更新频率", "在后台定时更新，不影响屏幕，熄屏也能运行。");
      EditText official =
          ui.field(
              s.content,
              "官方余量查询 · 分钟",
              next.optInt("scheduleMinutes", 10) + "",
              true,
              "可直接输入，最少 1 分钟");
      presets(s.content, official, 1, 5, 10, 30, 60);
      note(
          s.content,
          "移动网上营业厅每次约 1 MB，联通更少；1 分钟一次每天约 1.4 GB，连接 Wi-Fi 时不耗流量。短信查询自动发送最多每小时一次。");
      EditText local =
          ui.field(
              s.content,
              "本机流量统计同步 · 分钟",
              next.optInt("localSyncMinutes", 1) + "",
              true,
              "1–60");
      presets(s.content, local, 1, 2, 5, 10);
      note(
          s.content,
          "正在使用移动数据时，约 10 秒内从系统统计区分通用和定向，不等这个间隔。没有明显新流量时不读取统计，避免空耗电。官方查询到新的用量后，以官方数据覆盖这次估算。");
      s.primary(
              "保存",
              () -> {
                try {
                  next.put("scheduleMinutes", integer(official, 1, 10080))
                      .put("localSyncMinutes", integer(local, 1, 60));
                  next.remove("scheduleHours");
                  submit(s, configRequest(next), "更新频率已保存");
                } catch (Exception e) {
                  s.error(safeError(e));
                }
              })
          .show();
    } catch (Exception e) {
      showMessage("暂时无法编辑", safeError(e));
    }
  }

  void manualCorrection() {
    int target = slot;
    JSONObject card = cardFor(target);
    if (card == null) {
      showMessage("无法校准", "当前卡槽没有可用 SIM");
      return;
    }
    String identity = card.optString("identity");
    GuardUi.Sheet s = ui.sheet("校准卡 " + target + " 的用量", "输入官方已用和官方统计截至时间。");
    EditText used = ui.field(s.content, "官方通用已用 · GiB", "", true, "不含定向已用，例如 12.5");
    Calendar cutoff = Calendar.getInstance();
    cutoff.set(Calendar.SECOND, 0);
    cutoff.set(Calendar.MILLISECOND, 0);
    TextView value = ui.text(cutoffText(cutoff), 14, ui.ink);
    ui.space(s.content, 14);
    s.content.addView(value);
    ui.row(s.content, "calendar", "选择日期", null, () -> chooseDate(cutoff, value));
    ui.row(s.content, "clock", "选择时间", null, () -> chooseTime(cutoff, value));
    note(
        s.content,
        "时区："
            + TimeZone.getDefault().getDisplayName()
            + "。请核对官方统计时点，不把查询时间当作统计时间。这里只填写通用已用，定向已用不要合并填写；保留蜂窝总账，仅追加校准时点后的增量。");
    s.primary(
            "保存校准快照",
            () -> {
              try {
                long n = amount(used, 1073741824L, false);
                submit(
                    s,
                    () ->
                        RootClient.request(
                            new JSONObject()
                                .put("action", "calibrate")
                                .put("slot", target)
                                .put("identity", identity)
                                .put("used", n)
                                .put("asOf", cutoff.getTimeInMillis())),
                    "校准快照已保存");
              } catch (Exception e) {
                s.error(safeError(e));
              }
            })
        .show();
  }

  String cutoffText(Calendar c) {
    return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(c.getTime());
  }

  void chooseDate(Calendar value, TextView label) {
    GuardUi.Sheet s = ui.sheet("选择统计日期", null);
    DatePicker picker =
        new DatePicker(
            new ContextThemeWrapper(this, ui.dark ? R.style.AppThemeDark : R.style.AppTheme));
    picker.setMaxDate(System.currentTimeMillis());
    picker.init(
        value.get(Calendar.YEAR),
        value.get(Calendar.MONTH),
        value.get(Calendar.DAY_OF_MONTH),
        null);
    s.content.addView(picker, new LinearLayout.LayoutParams(-1, -2));
    s.primary(
            "使用此日期",
            () -> {
              value.set(picker.getYear(), picker.getMonth(), picker.getDayOfMonth());
              label.setText(cutoffText(value));
              s.dismiss();
            })
        .show();
  }

  void chooseTime(Calendar value, TextView label) {
    GuardUi.Sheet s = ui.sheet("选择统计时间", null);
    TimePicker picker =
        new TimePicker(
            new ContextThemeWrapper(this, ui.dark ? R.style.AppThemeDark : R.style.AppTheme));
    picker.setIs24HourView(true);
    picker.setHour(value.get(Calendar.HOUR_OF_DAY));
    picker.setMinute(value.get(Calendar.MINUTE));
    s.content.addView(picker, new LinearLayout.LayoutParams(-1, -2));
    s.primary(
            "使用此时间",
            () -> {
              value.set(Calendar.HOUR_OF_DAY, picker.getHour());
              value.set(Calendar.MINUTE, picker.getMinute());
              label.setText(cutoffText(value));
              s.dismiss();
            })
        .show();
  }

  void addPackageSheet() {
    GuardUi.Sheet s = ui.sheet("添加定向流量包", "仅添加用户选择的套餐，不默认启用任何定向包。");
    ui.row(
        s.content,
        "packages",
        "从套餐清单勾选",
        "本卡官方套餐与候选规则，可多选添加",
        () -> {
          s.dismiss();
          selectPackages();
        });
    ui.row(
        s.content,
        "add",
        "手动新增流量包",
        "自定义名称、应用和规则",
        () -> {
          s.dismiss();
          editPack(-1);
        });
    s.primary("取消", s::dismiss).show();
  }

  void selectPackages() {
    try {
      JSONObject next = copyConfig(), cc = mutableCard(next);
      JSONArray active = cc.getJSONArray("packages"), catalog = cc.optJSONArray("officialCatalog");
      List<JSONObject> available = new ArrayList<>();
      Set<String> names = new HashSet<>();
      for (int i = 0; i < active.length(); i++)
        names.add(active.getJSONObject(i).optString("name"));
      if (catalog != null)
        for (int i = 0; i < catalog.length(); i++) {
          JSONObject p = catalog.getJSONObject(i);
          if (names.add(p.optString("name"))) available.add(new JSONObject(p.toString()));
        }
      try (var in = getAssets().open("directed-templates.json")) {
        JSONArray bundled =
            new JSONArray(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        for (int i = 0; i < bundled.length(); i++) {
          JSONObject t = bundled.getJSONObject(i);
          if (names.add(t.getString("name")))
            available.add(
                t.put("id", UUID.randomUUID().toString())
                    .put(
                        "sourceKey",
                        Platform.hash(t.getString("name") + t.getJSONArray("rules").toString()))
                    .put("quota", -1)
                    .put("priority", 100)
                    .put("requireBoth", false)
                    .put("shared", false));
        }
      }
      String source = code(selected());
      String[] defaults =
          catalog != null && catalog.length() > 0
              ? new String[0]
              : source.equals("mobile")
                  ? new String[] {"移动云盘"}
                  : source.equals("unicom")
                      ? new String[] {"王卡专属流量"}
                      : source.equals("telecom") ? new String[] {"星卡定向流量"} : new String[0];
      for (String name : defaults)
        if (names.add(name)) {
          JSONArray apps = new JSONArray();
          if (name.equals("移动云盘")) apps.put("com.chinamobile.mcloud");
          available.add(
              new JSONObject()
                  .put("id", UUID.randomUUID().toString())
                  .put("name", name)
                  .put("quota", -1)
                  .put("apps", apps)
                  .put("rules", new JSONArray())
                  .put("priority", 100)
                  .put("requireBoth", false)
                  .put("shared", false));
        }
      GuardUi.Sheet s = ui.sheet("选择本卡定向流量包", provider(selected()) + " · 默认不勾选");
      List<CheckBox> checks = new ArrayList<>();
      for (JSONObject item : available)
        checks.add(
            ui.check(
                s.content,
                item.optString("name")
                    + (item.has("officialBalance")
                        ? "\n本卡官方 · 总额 "
                            + carrierBytes(item.optLong("quota", -1))
                            + " · 剩余 "
                            + carrierBytes(
                                item.getJSONObject("officialBalance").optLong("remaining", -1))
                        : item.has("sourceKey") ? "\n候选规则 · 适用范围待核实" : ""),
                false));
      if (available.isEmpty()) note(s.content, "已添加所有清单项目，也可以手动新增。");
      note(s.content, "本卡官方套餐自动填写额度和余量，勾选后才添加；候选规则的额度需自行核实。添加后可编辑应用、规则和用尽保护。");
      s.primary(
              "添加所选流量包",
              () -> {
                try {
                  int count = 0;
                  for (int i = 0; i < checks.size(); i++)
                    if (checks.get(i).isChecked()) {
                      JSONObject p = available.get(i);
                      if (!containsId(active, p.optString("id"))) {
                        p.put("userSelected", true);
                        active.put(p);
                        count++;
                      }
                    }
                  if (count == 0) {
                    s.error("请至少选择一个套餐");
                    return;
                  }
                  submit(s, configRequest(next), "所选流量包已添加");
                } catch (Exception e) {
                  s.error(safeError(e));
                }
              })
          .secondary(
              "手动新增",
              () -> {
                s.dismiss();
                editPack(-1);
              })
          .show();
    } catch (Exception e) {
      showMessage("清单暂不可用", safeError(e));
    }
  }

  void logs() {
    LinearLayout head = ui.column();
    head.setPadding(dp(4), 0, 0, 0);
    ui.section(head, "操作日志", "清空", this::clearLogsSheet);
    body.addView(head);
    LinearLayout filters = new LinearLayout(this);
    filters.setPadding(dp(3), dp(3), dp(3), dp(3));
    filters.setBackground(ui.shape(ui.surface, 16));
    String[] keys = {"all", "protection", "query", "settings"}, labels = {"全部", "保护", "查询", "设置"};
    for (int i = 0; i < keys.length; i++) {
      String key = keys[i];
      boolean on = key.equals(logFilter);
      TextView t = ui.text(labels[i], 13, on ? (ui.dark ? 0xff1b2746 : Color.WHITE) : ui.muted);
      t.setGravity(Gravity.CENTER);
      t.setBackground(ui.ripple(on ? ui.accent : ui.surface, 13));
      filters.addView(t, new LinearLayout.LayoutParams(0, dp(38), 1));
      t.setOnClickListener(v -> {
        logFilter = key;
        render();
      });
    }
    body.addView(filters);
    ui.space(body, 4);
    JSONArray events = status.optJSONArray("events");
    int shown = 0;
    LinearLayout p = null;
    if (events != null)
      for (int i = events.length() - 1; i >= 0; i--) {
        JSONObject e = events.optJSONObject(i);
        if (e == null
            || (!logFilter.equals("all")
                && !e.optString("category", "protection").equals(logFilter))) continue;
        if (p == null) p = panel();
        else ui.divider(p);
        shown++;
        String category =
            switch (e.optString("category", "protection")) {
              case "query" -> "查询";
              case "settings" -> "设置";
              default -> "保护";
            };
        boolean warning = "warning".equals(e.optString("level"));
        p.addView(
            ui.chip(
                category + (warning ? " · 需处理" : "") + " · " + time(e.optLong("at")),
                warning ? ui.warn : ui.accent,
                ui.soft),
            new LinearLayout.LayoutParams(-2, -2));
        if (!e.optString("title").isEmpty())
          title(p, GuardStore.cleanMessage(e.optString("title")));
        note(p, GuardStore.cleanMessage(e.optString("message")));
        String destination = e.optString("destination");
        if (!destination.isEmpty()) {
          String label =
              switch (destination.split(":")[0]) {
                case "budget" -> "设置本机累计上限";
                case "general" -> "设置通用停止阈值";
                case "directed" -> "设置定向包用尽行为";
                case "wifi" -> "设置 Wi-Fi 暂停限制";
                default -> "查看限制与保护状态";
              };
          ui.row(p, "edit", label, null, () -> openLogSetting(destination));
        }
      }
    if (shown == 0) note(panel(), "暂无此类日志。");
  }

  void openLogSetting(String destination) {
    String[] parts = destination.split(":");
    if (parts.length == 2 && cardFor(parts[1].equals("1") ? 1 : 2) != null)
      slot = parts[1].equals("1") ? 1 : 2;
    tab = 3;
    render();
    switch (parts[0]) {
      case "budget" -> editBudget();
      case "general" -> editCard();
      case "directed" -> packageLimitsSheet();
      case "wifi" -> wifiSettingsSheet();
      default -> {
        tab = 3;
        render();
      }
    }
  }

  void clearLogsSheet() {
    GuardUi.Sheet s = ui.sheet("清空全部操作日志？", "全部分类的日志将一并清空。");
    note(s.content, "仅清空日志列表，不会清零通用、定向用量、本机累计用量或修改任何保护设置。");
    s.primary("确认清空日志", () -> submit(s, () -> RootClient.action("clearLogs"), "操作日志已清空"))
        .secondary("保留日志", s::dismiss)
        .show();
  }

  void requestAppScopes() {
    try {
      var framework = GuardApplication.framework;
      if (framework == null) {
        showMessage("需要启用模块", "请在 LSPosed 中启用流量守卫，再为绑定应用授权作用域。");
        return;
      }
      Set<String> apps = new LinkedHashSet<>();
      boolean hasRules = false;
      JSONArray cards = status.getJSONArray("cards");
      for (int i = 0; i < cards.length(); i++) {
        JSONArray packs = cards.getJSONObject(i).getJSONObject("config").getJSONArray("packages");
        for (int j = 0; j < packs.length(); j++) {
          hasRules |= packs.getJSONObject(j).getJSONArray("rules").length() > 0;
          JSONArray a = packs.getJSONObject(j).getJSONArray("apps");
          for (int k = 0; k < a.length(); k++)
            try {
              getPackageManager().getApplicationInfo(a.getString(k), 0);
              apps.add(a.getString(k));
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
      }
      // Reading the official balance needs the carrier's app in scope too.
      for (int i = 0; i < cards.length(); i++)
        if (cards.getJSONObject(i).optBoolean("jiangsuApp") && installed(Platform.MOBILE_APP))
          apps.add(Platform.MOBILE_APP);
      // A rule executor is observed without assigning all of its traffic to a package.
      if (hasRules)
        try {
          getPackageManager().getApplicationInfo("com.paoman.lema", 0);
          apps.add("com.paoman.lema");
        } catch (PackageManager.NameNotFoundException ignored) {
        }
      apps.removeAll(framework.getScope());
      if (apps.isEmpty()) {
        showMessage("作用域已准备好", "已安装的绑定应用均已启用；目标应用重启后开始观察。");
        return;
      }
      GuardUi.Sheet s = ui.sheet("启用应用作用域", "将通过 LSPosed 请求以下应用作用域。");
      for (String pkg : apps) ui.row(s.content, "apps", appLabel(pkg), null, null);
      s.primary(
              "向 LSPosed 申请",
              () -> {
                s.dismiss();
                framework.requestScope(
                    new ArrayList<>(apps),
                    new io.github.libxposed.service.XposedService.OnScopeEventListener() {
                      public void onScopeRequestApproved(List<String> scope) {
                        main.post(() -> showMessage("作用域已更新", "重新打开目标应用后生效。"));
                      }

                      public void onScopeRequestFailed(String message) {
                        main.post(() -> showMessage("作用域未更新", "可打开 LSPosed 手动勾选绑定应用。"));
                      }
                    });
              })
          .show();
    } catch (Exception e) {
      showMessage("作用域暂不可用", "请在 LSPosed 中设置绑定应用。 ");
    }
  }

  void backupSheet() {
    GuardUi.Sheet s = ui.sheet("备份与导入设置", "备份限制、用卡策略、流量包、应用和规则。");
    ui.row(
        s.content,
        "download",
        "备份到文件",
        "选择保存位置",
        () -> {
          s.dismiss();
          startActivityForResult(
              new Intent(Intent.ACTION_CREATE_DOCUMENT)
                  .addCategory(Intent.CATEGORY_OPENABLE)
                  .setType("application/json")
                  .putExtra(
                      Intent.EXTRA_TITLE,
                      "流量守卫设置-"
                          + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(new Date())
                          + ".json"),
              32);
        });
    ui.row(
        s.content,
        "apps",
        "从文件导入",
        "导入前先预览内容",
        () -> {
          s.dismiss();
          startActivityForResult(
              new Intent(Intent.ACTION_OPEN_DOCUMENT)
                  .addCategory(Intent.CATEGORY_OPENABLE)
                  .setType("*/*"),
              33);
        });
    note(s.content, "备份不含登录信息、用量记录和官方余额。备份按 SIM 匹配，换手机后同一张卡的设置可以恢复。");
    s.primary("关闭", s::dismiss).show();
  }

  void writeBackup(android.net.Uri target) {
    io.execute(() -> {
      try {
        byte[] bytes =
            RootClient.action("exportSettings")
                .toString(2)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try (var out = getContentResolver().openOutputStream(target, "wt")) {
          out.write(bytes);
        }
        main.post(() -> toast("设置已备份"));
      } catch (Exception e) {
        main.post(() -> showMessage("备份未完成", safeError(e)));
      }
    });
  }

  JSONObject readJson(android.net.Uri source) throws Exception {
    try (var in = getContentResolver().openInputStream(source)) {
      java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
      byte[] chunk = new byte[8192];
      int n;
      while ((n = in.read(chunk)) != -1) {
        if (buffer.size() + n > 512000) throw new IllegalArgumentException("文件超过 500 KB");
        buffer.write(chunk, 0, n);
      }
      return new JSONObject(
          new String(buffer.toByteArray(), java.nio.charset.StandardCharsets.UTF_8));
    }
  }

  void previewBackup(android.net.Uri source) {
    io.execute(() -> {
      try {
        JSONObject backup = readJson(source);
        JSONObject preview =
            RootClient.request(
                new JSONObject().put("action", "previewSettings").put("backup", backup));
        main.post(() -> importSheet(backup, preview));
      } catch (org.json.JSONException e) {
        main.post(() -> showMessage("无法导入", "文件不是有效的设置备份。"));
      } catch (Exception e) {
        main.post(() -> showMessage("无法导入", safeError(e)));
      }
    });
  }

  void importSheet(JSONObject backup, JSONObject preview) {
    GuardUi.Sheet s = ui.sheet("导入设置？", "将覆盖本机对应卡的限制、流量包和规则。");
    metric(s.content, "备份时间", time(preview.optLong("exportedAt")));
    metric(s.content, "备份版本", preview.optString("appVersion"));
    metric(s.content, "卡片设置", preview.optInt("cards") + " 张，本机匹配 " + preview.optInt("here") + " 张");
    metric(s.content, "流量包", preview.optInt("packs") + " 个");
    note(s.content, "未匹配的卡会保留设置，插入后生效。用量记录和官方余额不变，导入不会自动开启移动数据。禁止联网的卡不会因导入解除。");
    s.primary(
            "确认导入",
            () ->
                submit(
                    s,
                    () ->
                        RootClient.request(
                            new JSONObject().put("action", "importSettings").put("backup", backup)),
                    "设置已导入"))
        .secondary("取消", s::dismiss)
        .show();
  }

  void importFile() {
    importSlot = slot;
    Intent i =
        new Intent(Intent.ACTION_OPEN_DOCUMENT)
            .setType("*/*")
            .addCategory(Intent.CATEGORY_OPENABLE);
    startActivityForResult(i, 31);
  }

  @Override
  protected void onActivityResult(int req, int result, Intent data) {
    super.onActivityResult(req, result, data);
    if (result == RESULT_OK && data != null && req == 32) writeBackup(data.getData());
    if (result == RESULT_OK && data != null && req == 33) previewBackup(data.getData());
    if (req != 31 || result != RESULT_OK || data == null) return;
    final int target = importSlot;
    if (busy) {
      toast("正在更新，请稍后再导入");
      return;
    }
    busy = true;
    io.execute(() -> {
      try {
        JSONObject input;
        try (var in = getContentResolver().openInputStream(data.getData())) {
          java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
          byte[] chunk = new byte[8192];
          int n;
          while ((n = in.read(chunk)) != -1) {
            if (buffer.size() + n > 512000) throw new IllegalArgumentException("文件超过 500 KB");
            buffer.write(chunk, 0, n);
          }
          input =
              new JSONObject(
                  new String(buffer.toByteArray(), java.nio.charset.StandardCharsets.UTF_8));
        }
        if (!"HBCS_SPEED_TEST_CONFIG".equals(input.optString("format")))
          throw new IllegalArgumentException("请选择支持的定向规则配置文件");
        main.post(() -> {
          busy = false;
          if (!isDestroyed()) previewImport(input, target);
        });
      } catch (Exception e) {
        main.post(() -> {
          busy = false;
          showMessage("无法导入规则", safeError(e));
        });
      }
    });
  }

  void previewImport(JSONObject input, int target) {
    try {
      JSONArray profiles = input.getJSONArray("profiles");
      GuardUi.Sheet s = ui.sheet("选择要导入的规则", "添加到卡 " + target + "；默认不勾选，不会发起下载。");
      List<CheckBox> checks = new ArrayList<>();
      for (int i = 0; i < profiles.length(); i++)
        checks.add(
            ui.check(s.content, profiles.getJSONObject(i).optString("name", "未命名规则"), false));
      note(s.content, "配置中的下载地址只提取域名与路径作为规则，适用范围需与本卡套餐核实。");
      s.primary(
              "导入所选规则",
              () -> {
                try {
                  JSONArray chosen = new JSONArray();
                  for (int i = 0; i < checks.size(); i++)
                    if (checks.get(i).isChecked()) chosen.put(profiles.getJSONObject(i));
                  if (chosen.length() == 0) {
                    s.error("请至少选择一组规则");
                    return;
                  }
                  JSONObject subset = new JSONObject(input.toString()).put("profiles", chosen);
                  submit(
                      s,
                      () -> Importer.importJson(subset, RootClient.action("status"), target),
                      "所选规则已添加");
                } catch (Exception e) {
                  s.error(safeError(e));
                }
              })
          .show();
    } catch (Exception e) {
      showMessage("无法读取规则清单", safeError(e));
    }
  }

  void launchCarrier(String provider) {
    String[] choices =
        switch (provider) {
          case "mobile" -> new String[] {Platform.MOBILE_APP, "com.greenpoint.android.mc10086.activity"};
          case "unicom" -> new String[] {"com.sinovatech.unicom.ui"};
          case "telecom" -> new String[] {"com.ct.client"};
          default -> new String[0];
        };
    if (choices.length == 0) {
      showMessage("暂无官方查询", "此运营商尚未接入官方查询，可在校准页手动填写已用量。");
      return;
    }
    for (String pkg : choices)
      try {
        getPackageManager().getApplicationInfo(pkg, 0);
        launch(pkg);
        return;
      } catch (PackageManager.NameNotFoundException ignored) {
      }
    showMessage("尚未安装" + carrierName(provider), "安装官方 App 后即可从此处打开。模块已识别 SIM，不代表已登录或已接通官方查询。");
  }

  void launch(String pkg) {
    try {
      Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
      if (i == null && pkg.equals(Platform.MOBILE_APP))
        i =
            new Intent()
                .setComponent(new ComponentName(pkg, "com.mc10086.cmcc.base.StartPageActivity"));
      if (i == null) {
        showMessage("未找到应用入口", "请从手机桌面打开相应应用。");
        return;
      }
      startActivity(i);
    } catch (ActivityNotFoundException | SecurityException e) {
      showMessage("无法打开应用", "请从桌面进入，或检查应用是否启用。");
    }
  }

  void showMessage(String title, String message) {
    if (isDestroyed()) return;
    GuardUi.Sheet s = ui.sheet(title, null);
    note(s.content, message);
    s.primary("知道了", s::dismiss).show();
  }

  String appLabel(String pkg) {
    try {
      return getPackageManager()
          .getApplicationInfo(pkg, 0)
          .loadLabel(getPackageManager())
          .toString();
    } catch (Exception e) {
      return pkg;
    }
  }

  static String join(JSONArray a) throws Exception {
    List<String> s = new ArrayList<>();
    for (int i = 0; i < a.length(); i++) s.add(a.getString(i));
    return String.join("\n", s);
  }

  static String bytes(long n) {
    if (n < 0) return "待核实";
    if (n >= 1000000000) return String.format(Locale.ROOT, "%.2f GB", n / 1e9);
    if (n >= 1000000) return String.format(Locale.ROOT, "%.1f MB", n / 1e6);
    return String.format(Locale.ROOT, "%.1f KB", n / 1e3);
  }

  static String carrierBytes(long n) {
    if (n < 0) return "未知／不限量";
    if (n >= 1073741824L) return String.format(Locale.ROOT, "%.2f GiB", n / 1073741824d);
    return String.format(Locale.ROOT, "%.2f MiB", n / 1048576d);
  }

  String time(long at) {
    return at <= 0
        ? "等待采样"
        : new SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT).format(new Date(at));
  }

  void toast(String s) {
    Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
  }
}
