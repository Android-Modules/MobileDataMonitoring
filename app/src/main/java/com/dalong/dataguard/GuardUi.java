package com.dalong.dataguard;

import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.*;
import android.widget.*;

/** Shared native components. No accounting or privileged operations live here. */
final class GuardUi {
  final Activity activity;
  final boolean dark;
  final int bg, surface, ink, muted, accent, soft, line, danger, warn;

  GuardUi(Activity a) {
    activity = a;
    dark = (a.getResources().getConfiguration().uiMode & 48) == 32;
    bg = dark ? 0xff15151e : 0xfffaf8ff;
    surface = dark ? 0xff252530 : 0xffe5e4eb;
    ink = dark ? 0xffeeeaf4 : 0xff1c1c23;
    muted = dark ? 0xffb3afc0 : 0xff62616e;
    accent = dark ? 0xffb9c7ff : 0xff4b6094;
    soft = dark ? 0xff303b5a : 0xffdfe5ff;
    line = dark ? 0xff363541 : 0xffe5e4eb;
    danger = dark ? 0xffffa39c : 0xffb84037;
    warn = dark ? 0xffedc77d : 0xff946223;
  }

  int dp(float n) {
    return Math.round(n * activity.getResources().getDisplayMetrics().density);
  }

  GradientDrawable shape(int color, int radius) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(radius));
    return d;
  }

  GradientDrawable outlined(int color, int radius, int stroke) {
    GradientDrawable d = shape(color, radius);
    d.setStroke(dp(1), stroke);
    return d;
  }

  Drawable ripple(int color, int radius) {
    return new RippleDrawable(
        ColorStateList.valueOf(dark ? 0x24ffffff : 0x164b6094),
        shape(color, radius),
        shape(-1, radius));
  }

  LinearLayout column() {
    LinearLayout l = new LinearLayout(activity);
    l.setOrientation(LinearLayout.VERTICAL);
    return l;
  }

  TextView text(String value, int size, int color) {
    TextView t = new TextView(activity);
    t.setText(value);
    t.setTextSize(size);
    t.setTextColor(color);
    t.setLineSpacing(dp(2), 1);
    t.setFontFeatureSettings("tnum");
    return t;
  }

  TextView heading(String s, int size) {
    TextView t = text(s, size, ink);
    t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    return t;
  }

  void space(LinearLayout parent, int h) {
    parent.addView(new View(activity), new LinearLayout.LayoutParams(1, dp(h)));
  }

  TextView label(LinearLayout p, String s) {
    TextView t = text(s, 12, muted);
    p.addView(t);
    return t;
  }

  LinearLayout card(LinearLayout parent) {
    LinearLayout p = column();
    p.setPadding(dp(18), dp(18), dp(18), dp(18));
    p.setBackground(outlined(surface, 22, line));
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.setMargins(0, dp(6), 0, dp(8));
    parent.addView(p, lp);
    return p;
  }

  void section(LinearLayout p, String s, String tail, Runnable action) {
    LinearLayout row = new LinearLayout(activity);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.addView(heading(s, 17), new LinearLayout.LayoutParams(0, -2, 1));
    if (tail != null) {
      TextView t = text(tail, 13, accent);
      t.setGravity(Gravity.CENTER);
      t.setMinHeight(dp(44));
      t.setPadding(dp(8), 0, 0, 0);
      row.addView(t);
      t.setOnClickListener(v -> action.run());
    }
    p.addView(row);
  }

  void divider(LinearLayout p) {
    View d = new View(activity);
    d.setBackgroundColor(line);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(1));
    lp.setMargins(0, dp(12), 0, dp(12));
    p.addView(d, lp);
  }

  Button button(LinearLayout p, String s, boolean primary, Runnable action) {
    Button b = new Button(activity);
    b.setText(s);
    b.setTextSize(14);
    b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    b.setAllCaps(false);
    b.setTextColor(primary ? (dark ? 0xff1b2746 : Color.WHITE) : accent);
    b.setBackground(ripple(primary ? accent : soft, 28));
    b.setElevation(0);
    b.setStateListAnimator(null);
    b.setMinHeight(dp(48));
    b.setPadding(dp(12), dp(8), dp(12), dp(8));
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.setMargins(0, dp(10), 0, 0);
    p.addView(b, lp);
    b.setOnClickListener(v -> action.run());
    return b;
  }

  View iconButton(String icon, String description, Runnable action) {
    FrameLayout f = new FrameLayout(activity);
    f.setBackground(ripple(surface, 16));
    f.setContentDescription(description);
    f.setFocusable(true);
    Icon v = new Icon(activity, icon, accent);
    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER);
    f.addView(v, lp);
    f.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(44)));
    f.setOnClickListener(w -> action.run());
    return f;
  }

  TextView chip(String s, int color, int fill) {
    TextView t = text(s, 11, color);
    t.setPadding(dp(9), dp(4), dp(9), dp(4));
    t.setBackground(shape(fill, 9));
    return t;
  }

  LinearLayout row(LinearLayout p, String icon, String title, String subtitle, Runnable action) {
    LinearLayout r = new LinearLayout(activity);
    r.setGravity(Gravity.CENTER_VERTICAL);
    r.setPadding(0, dp(10), 0, dp(10));
    r.setMinimumHeight(dp(60));
    Icon iv = new Icon(activity, icon, accent);
    LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(dp(23), dp(23));
    il.setMargins(0, 0, dp(12), 0);
    r.addView(iv, il);
    LinearLayout words = column();
    words.addView(heading(title, 14));
    if (subtitle != null && !subtitle.isEmpty()) words.addView(text(subtitle, 12, muted));
    r.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
    if (action != null) {
      r.addView(
          new Icon(activity, "chevron", muted), new LinearLayout.LayoutParams(dp(18), dp(18)));
      r.setBackground(ripple(Color.TRANSPARENT, 12));
      r.setFocusable(true);
      r.setOnClickListener(v -> action.run());
    }
    p.addView(r);
    return r;
  }

  EditText field(LinearLayout p, String label, String value, boolean number, String hint) {
    space(p, 14);
    p.addView(heading(label, 13));
    space(p, 7);
    EditText e = new EditText(activity);
    e.setText(value);
    e.setTextSize(15);
    e.setTextColor(ink);
    e.setHintTextColor(muted);
    e.setHint(hint);
    e.setSingleLine(true);
    e.setSelectAllOnFocus(true);
    e.setPadding(dp(12), dp(12), dp(12), dp(12));
    e.setBackground(outlined(bg, 12, line));
    e.setInputType(
        number
            ? android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            : android.text.InputType.TYPE_CLASS_TEXT);
    e.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_NEXT);
    p.addView(e, new LinearLayout.LayoutParams(-1, -2));
    return e;
  }

  CheckBox check(LinearLayout p, String s, boolean checked) {
    CheckBox c = new CheckBox(activity);
    c.setText(s);
    c.setTextSize(14);
    c.setTextColor(ink);
    c.setButtonTintList(ColorStateList.valueOf(accent));
    c.setChecked(checked);
    c.setMinHeight(dp(48));
    p.addView(c);
    return c;
  }

  Switch toggle(LinearLayout p, String label, boolean checked) {
    Switch control = new Switch(activity);
    control.setText(label);
    control.setTextSize(14);
    control.setTextColor(ink);
    control.setChecked(checked);
    control.setMinHeight(dp(52));
    control.setSwitchPadding(dp(14));
    control.setThumbTintList(
        new ColorStateList(
            new int[][] {{android.R.attr.state_checked}, {}}, new int[] {accent, muted}));
    control.setTrackTintList(
        new ColorStateList(
            new int[][] {{android.R.attr.state_checked}, {}}, new int[] {soft, line}));
    p.addView(control, new LinearLayout.LayoutParams(-1, -2));
    return control;
  }

  void hint(LinearLayout p, String s) {
    space(p, 10);
    TextView t = text(s, 12, muted);
    p.addView(t);
  }

  /** One presentation for forms, choices, confirmations and errors. */
  final class Sheet {
    final Dialog dialog;
    final LinearLayout content, footer;
    final TextView error;
    Button primary;

    Sheet(String title, String subtitle) {
      dialog = new Dialog(activity);
      LinearLayout root = column();
      root.setPadding(dp(20), dp(10), dp(20), dp(18));
      root.setBackground(shape(bg, 26));
      View handle = new View(activity);
      handle.setBackground(shape(line, 3));
      LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(dp(36), dp(4));
      hp.gravity = Gravity.CENTER;
      hp.bottomMargin = dp(12);
      root.addView(handle, hp);
      LinearLayout header = new LinearLayout(activity);
      header.setGravity(Gravity.CENTER_VERTICAL);
      header.addView(heading(title, 21), new LinearLayout.LayoutParams(0, -2, 1));
      header.addView(iconButton("close", "关闭面板", this::dismiss));
      root.addView(header);
      if (subtitle != null && !subtitle.isEmpty()) {
        TextView desc = text(subtitle, 13, muted);
        desc.setPadding(0, 0, 0, dp(10));
        root.addView(desc);
      }
      ScrollView scroll =
          new ScrollView(activity) {
            @Override
            protected void onMeasure(int w, int h) {
              int max = (int) (activity.getResources().getDisplayMetrics().heightPixels * .60);
              int given = MeasureSpec.getSize(h);
              super.onMeasure(
                  w,
                  MeasureSpec.makeMeasureSpec(
                      given > 0 ? Math.min(given, max) : max, MeasureSpec.AT_MOST));
            }
          };
      scroll.setFillViewport(false);
      content = column();
      content.setPadding(0, 0, 0, dp(4));
      scroll.addView(content);
      root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
      error = text("", 13, danger);
      error.setVisibility(View.GONE);
      error.setPadding(0, dp(8), 0, 0);
      root.addView(error);
      footer = column();
      root.addView(footer);
      dialog.setContentView(root);
      Window window = dialog.getWindow();
      window.setBackgroundDrawableResource(android.R.color.transparent);
      window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
      window.setDimAmount(.38f);
      window.setGravity(Gravity.BOTTOM);
      window.setSoftInputMode(
          WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
              | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
      root.setOnApplyWindowInsetsListener((v, insets) -> {
        android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
        v.setPadding(dp(20) + bars.left, dp(10), dp(20) + bars.right, dp(18) + bars.bottom);
        return insets;
      });
    }

    Sheet primary(String text, Runnable run) {
      primary =
          button(
              footer,
              text,
              true,
              () -> {
                clearError();
                run.run();
              });
      return this;
    }

    Sheet secondary(String text, Runnable run) {
      button(footer, text, false, run);
      return this;
    }

    void show() {
      dialog.show();
      Window w = dialog.getWindow();
      w.setLayout(-1, -2);
      w.getDecorView().requestApplyInsets();
    }

    void dismiss() {
      dialog.dismiss();
    }

    void error(String message) {
      error.setText(message == null ? "请检查输入后重试" : message);
      error.setVisibility(View.VISIBLE);
    }

    void clearError() {
      error.setVisibility(View.GONE);
    }

    void working(boolean value) {
      if (primary != null) {
        primary.setEnabled(!value);
        primary.setAlpha(value ? .55f : 1f);
      }
      dialog.setCancelable(!value);
    }
  }

  Sheet sheet(String title, String subtitle) {
    return new Sheet(title, subtitle);
  }

  static final class Icon extends View {
    final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    final String kind;
    final int color;

    Icon(Context c, String k, int color) {
      super(c);
      kind = k;
      this.color = color;
      setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void path(Canvas c, float... xy) {
      Path q = new Path();
      q.moveTo(xy[0], xy[1]);
      for (int i = 2; i < xy.length; i += 2) q.lineTo(xy[i], xy[i + 1]);
      c.drawPath(q, p);
    }

    @Override
    protected void onDraw(Canvas canvas) {
      super.onDraw(canvas);
      canvas.save();
      canvas.scale(getWidth() / 24f, getHeight() / 24f);
      p.setColor(color);
      p.setStyle(Paint.Style.STROKE);
      p.setStrokeWidth(1.8f);
      p.setStrokeCap(Paint.Cap.ROUND);
      p.setStrokeJoin(Paint.Join.ROUND);
      switch (kind) {
        case "overview":
          for (int y : new int[] {4, 14})
            for (int x : new int[] {4, 14}) canvas.drawRoundRect(x, y, x + 6, y + 6, 1.5f, 1.5f, p);
          break;
        case "packages":
          path(canvas, 3, 7, 12, 3, 21, 7, 12, 11, 3, 7);
          path(canvas, 3, 12, 12, 16, 21, 12);
          path(canvas, 3, 17, 12, 21, 21, 17);
          break;
        case "refresh":
          canvas.drawArc(4, 4, 20, 20, 205, 145, false, p);
          canvas.drawArc(4, 4, 20, 20, 25, 145, false, p);
          path(canvas, 15, 4, 20, 4, 20, 9);
          path(canvas, 9, 20, 4, 20, 4, 15);
          break;
        case "settings":
          canvas.drawCircle(12, 12, 3, p);
          for (int a = 0; a < 360; a += 45) {
            float c = (float) Math.cos(Math.toRadians(a)), n = (float) Math.sin(Math.toRadians(a));
            canvas.drawLine(12 + 6.5f * c, 12 + 6.5f * n, 12 + 9.5f * c, 12 + 9.5f * n, p);
          }
          canvas.drawCircle(12, 12, 6.5f, p);
          break;
        case "shield":
          path(canvas, 12, 3, 20, 6, 19, 14, 16, 19, 12, 22, 8, 19, 5, 14, 4, 6, 12, 3);
          path(canvas, 8, 12, 11, 15, 16, 9);
          break;
        case "swap":
          path(canvas, 3, 7, 20, 7, 16, 3);
          path(canvas, 21, 17, 4, 17, 8, 21);
          break;
        case "close":
          path(canvas, 6, 6, 18, 18);
          path(canvas, 18, 6, 6, 18);
          break;
        case "add":
          path(canvas, 12, 4, 12, 20);
          path(canvas, 4, 12, 20, 12);
          break;
        case "check":
          path(canvas, 5, 12, 10, 17, 19, 7);
          break;
        case "chevron":
          path(canvas, 9, 5, 16, 12, 9, 19);
          break;
        case "download":
          path(canvas, 12, 3, 12, 15);
          path(canvas, 7, 10, 12, 15, 17, 10);
          path(canvas, 4, 16, 4, 21, 20, 21, 20, 16);
          break;
        case "search":
          canvas.drawCircle(10, 10, 6, p);
          path(canvas, 15, 15, 21, 21);
          break;
        case "sim":
          path(canvas, 8, 3, 19, 3, 19, 21, 5, 21, 5, 7, 8, 3);
          canvas.drawRoundRect(8, 10, 16, 17, 1, 1, p);
          break;
        case "edit":
          path(canvas, 4, 20, 5, 14, 17, 2, 22, 7, 10, 19, 4, 20);
          path(canvas, 14, 5, 19, 10);
          break;
        case "stop":
          canvas.drawCircle(12, 12, 9, p);
          canvas.drawRoundRect(8, 8, 16, 16, 1, 1, p);
          break;
        case "play":
          path(canvas, 8, 4, 20, 12, 8, 20, 8, 4);
          break;
        case "apps":
          for (int y : new int[] {4, 11, 18})
            for (int x : new int[] {4, 11, 18}) canvas.drawCircle(x, y, 1, p);
          break;
        case "clock":
          canvas.drawCircle(12, 12, 9, p);
          path(canvas, 12, 7, 12, 12, 16, 14);
          break;
        case "calendar":
          canvas.drawRoundRect(3, 5, 21, 21, 2, 2, p);
          path(canvas, 3, 10, 21, 10);
          path(canvas, 7, 2, 7, 7);
          path(canvas, 17, 2, 17, 7);
          break;
        case "trash":
          path(canvas, 4, 6, 20, 6);
          path(canvas, 8, 6, 8, 3, 16, 3, 16, 6);
          path(canvas, 6, 6, 7, 21, 17, 21, 18, 6);
          path(canvas, 10, 10, 10, 17);
          path(canvas, 14, 10, 14, 17);
          break;
        default:
          canvas.drawCircle(12, 12, 9, p);
          canvas.drawCircle(12, 7, 0.5f, p);
          path(canvas, 12, 11, 12, 17);
      }
      canvas.restore();
    }
  }
}
