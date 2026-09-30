package com.statcolor.ui;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.statcolor.R;
import com.statcolor.app.Config;
import com.statcolor.app.Hook;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;

/**
 * 配置界面。纯平台 API，无第三方依赖，方便手工编译。
 *
 * 说明：本文件刻意不使用 lambda / 方法引用，一律用匿名内部类。
 * android.jar 的 java.lang.invoke 是旧版 stub，javac 在 -bootclasspath
 * 指向它时无法为 invokedynamic 生成引导方法；用匿名内部类可完全绕开。
 */
public class MainActivity extends Activity {

    /** 常用颜色，点一下就填进去。 */
    private static final String[] PALETTE = {
            "#FFFFFFFF", "#FF000000", "#FFFF3B30", "#FFFF9500", "#FFFFCC00",
            "#FF34C759", "#FF00C7BE", "#FF0A84FF", "#FF5E5CE6", "#FFFF2D55",
            "#FF8E8E93", "#FF64D2FF",
    };

    private Switch swEnable;
    private EditText etDark, etLight;
    private View swDark, swLight;
    private TextView tvLog;
    private android.widget.ImageView icDarkEdit, icLightEdit;
    private TextView tvDarkPreview, tvLightPreview;
    private final Handler ticker = new Handler(Looper.getMainLooper());
    private final java.text.SimpleDateFormat fmt =
            new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            String t = fmt.format(new java.util.Date());
            if (tvDarkPreview != null) tvDarkPreview.setText(t);
            if (tvLightPreview != null) tvLightPreview.setText(t);
            ticker.postDelayed(this, 1000);
        }
    };
    private RadioButton rbAuto, rbDark, rbLight;

    /** 载入配置期间抑制回调，避免半初始化状态触发预览。 */
    private boolean binding = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        swEnable     = (Switch) findViewById(R.id.sw_enable);
        etDark       = (EditText) findViewById(R.id.et_dark_hex);
        etLight      = (EditText) findViewById(R.id.et_light_hex);
        tvDarkPreview  = (TextView) findViewById(R.id.tv_dark_preview);
        tvLightPreview = (TextView) findViewById(R.id.tv_light_preview);
        swDark       = findViewById(R.id.tv_dark_swatch);
        icDarkEdit   = (android.widget.ImageView) findViewById(R.id.iv_dark_edit);
        icLightEdit  = (android.widget.ImageView) findViewById(R.id.iv_light_edit);
        swLight      = findViewById(R.id.tv_light_swatch);
        tvLog        = (TextView) findViewById(R.id.tv_log);
        rbAuto       = (RadioButton) findViewById(R.id.rb_auto);
        rbDark       = (RadioButton) findViewById(R.id.rb_dark);
        rbLight      = (RadioButton) findViewById(R.id.rb_light);

        buildPalette((LinearLayout) findViewById(R.id.ll_dark_palette), true);
        buildPalette((LinearLayout) findViewById(R.id.ll_light_palette), false);

        // 先挂监听再载入：否则 loadIntoUi() 里 setText 不会触发色块更新，
        // 左侧色块会停留在布局默认值（白），表现就是「每次启动都恢复成默认」。
        etDark.addTextChangedListener(watcher(true));
        etLight.addTextChangedListener(watcher(false));
        applyEdgeToEdge();
        loadIntoUi();
        syncSwatches();
        roundSurfaces();
        roundButtons();
        shrinkButtons();

        swEnable.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean v) {
                if (!binding) preview();
            }
        });

        ((Button) findViewById(R.id.btn_dark_default)).setOnClickListener(
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        etDark.setText(Config.DEFAULT_DARK);
                        syncSwatches();
                    }
                });
        ((Button) findViewById(R.id.btn_light_default)).setOnClickListener(
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        etLight.setText(Config.DEFAULT_LIGHT);
                        syncSwatches();
                    }
                });

        // 点色块直接开取色面板（原来的「应用」按钮已删除）
        swDark.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openPicker(true); }
        });
        swLight.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openPicker(false); }
        });

        View.OnClickListener modeListener = new View.OnClickListener() {
            @Override public void onClick(View v) { preview(); }
        };
        rbAuto.setOnClickListener(modeListener);
        rbDark.setOnClickListener(modeListener);
        rbLight.setOnClickListener(modeListener);

        ((Button) findViewById(R.id.btn_save)).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { save(); }
        });

        ((Button) findViewById(R.id.btn_diag)).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showDiag(); }
        });

        tvLog.setText("模块版本 " + Hook.VERSION + "\n作用域：com.android.systemui\n"
                + "配置项：启用 / 两组颜色 / 透明度 / 明暗模式\n"
                + "保存后需重启 SystemUI 生效。");
    }

    // ─────────────────────────────────────────────────────── 调色板

    private void buildPalette(LinearLayout row, final boolean isDarkGroup) {
        row.removeAllViews();
        int size = dp(40);
        int gap = dp(10);
        for (int i = 0; i < PALETTE.length; i++) {
            final String hex = PALETTE[i];
            final int c = Config.parseColor(hex, 0xFF888888);

            // 圆点 + 覆盖在上面的对勾（FrameLayout 叠两层）
            android.widget.FrameLayout cell = new android.widget.FrameLayout(this);
            LinearLayout.LayoutParams lp =
                    new LinearLayout.LayoutParams(size, size);
            lp.rightMargin = gap;
            cell.setLayoutParams(lp);
            cell.setTag(hex);                       // preview() 用它判断选中
            cell.setContentDescription(hex);

            View dot = new View(this);
            dot.setLayoutParams(new android.widget.FrameLayout.LayoutParams(size, size));
            dot.setBackground(circle(hex));
            cell.addView(dot);

            TextView check = new TextView(this);
            check.setId(R.id.tv_palette_check);
            android.widget.FrameLayout.LayoutParams clp =
                    new android.widget.FrameLayout.LayoutParams(size, size);
            check.setLayoutParams(clp);
            check.setText("✓");
            check.setTextSize(18);
            check.setGravity(android.view.Gravity.CENTER);
            // 浅色块上用黑勾、深色块上用白勾
            check.setTextColor(luminance(c) > 0.5 ? 0xFF000000 : 0xFFFFFFFF);
            check.setVisibility(View.INVISIBLE);
            cell.addView(check);

            cell.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    EditText target = isDarkGroup ? etDark : etLight;
                    target.setText(hex);
                    preview();
                }
            });
            row.addView(cell);
        }
    }


    // ─────────────────────────────────────────────────────── 取色面板

    /**
     * 平台没有内置取色器，这里自己搭一个：
     * 预览块 + A/R/G/B 四条滑杆，实时反映到预览与色块。
     */
    private void openPicker(final boolean isDarkGroup) {
        final EditText et = isDarkGroup ? etDark : etLight;
        final View swatch = isDarkGroup ? swDark : swLight;
        final int fallback = isDarkGroup ? 0xFFFFFFFF : 0xFF000000;

        int cur = Config.parseColor(et.getText().toString(), fallback);
        final int[] rgba = { (cur >>> 24) & 0xFF, (cur >>> 16) & 0xFF,
                             (cur >>> 8) & 0xFF, cur & 0xFF };

        float d = getResources().getDisplayMetrics().density;
        int pad = (int) (d * 18);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);

        final View previewBox = new View(this);
        previewBox.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int) (d * 56)));
        box.addView(previewBox);

        final TextView hexLabel = new TextView(this);
        hexLabel.setTextSize(14);
        hexLabel.setPadding(0, (int) (d * 10), 0, (int) (d * 4));
        box.addView(hexLabel);

        final boolean[] syncing = { false };
        final String[] names = { "不透明度 A", "红 R", "绿 G", "蓝 B" };
        final SeekBar[] bars = new SeekBar[4];
        final EditText[] nums = new EditText[4];

        for (int i = 0; i < 4; i++) {
            final int idx = i;

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);

            TextView tv = new TextView(this);
            tv.setText(names[i]);
            tv.setTextSize(12);
            tv.setLayoutParams(new LinearLayout.LayoutParams(
                    (int) (d * 76), LinearLayout.LayoutParams.WRAP_CONTENT));
            row.addView(tv);

            SeekBar sb = new SeekBar(this);
            sb.setMax(255);
            sb.setProgress(rgba[i]);
            sb.setLayoutParams(new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(sb);

            // 滑杆右侧可直接输入 0-255 的十进制数值
            EditText num = new EditText(this);
            num.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            num.setText(String.valueOf(rgba[i]));
            num.setTextSize(13);
            num.setGravity(android.view.Gravity.CENTER);
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                    (int) (d * 58), LinearLayout.LayoutParams.WRAP_CONTENT);
            nlp.leftMargin = (int) (d * 6);
            num.setLayoutParams(nlp);
            row.addView(num);

            sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                    if (syncing[0]) return;
                    syncing[0] = true;
                    rgba[idx] = value;
                    nums[idx].setText(String.valueOf(value));
                    int c = Color.argb(rgba[0], rgba[1], rgba[2], rgba[3]);
                    previewBox.setBackground(rounded(c));
                    hexLabel.setText(Config.toHex(c));
                    syncing[0] = false;
                }
                @Override public void onStartTrackingTouch(SeekBar bar) {}
                @Override public void onStopTrackingTouch(SeekBar bar) {}
            });

            num.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence c, int a, int b, int cc) {}
                @Override public void onTextChanged(CharSequence c, int a, int b, int cc) {}
                @Override public void afterTextChanged(Editable e) {
                    if (syncing[0]) return;
                    int v;
                    try { v = Integer.parseInt(e.toString().trim()); }
                    catch (Throwable t) { return; }
                    if (v < 0 || v > 255) return;
                    syncing[0] = true;
                    rgba[idx] = v;
                    bars[idx].setProgress(v);
                    int c = Color.argb(rgba[0], rgba[1], rgba[2], rgba[3]);
                    previewBox.setBackground(rounded(c));
                    hexLabel.setText(Config.toHex(c));
                    syncing[0] = false;
                }
            });

            bars[i] = sb;
            nums[i] = num;
            box.addView(row);
        }

        int start = Color.argb(rgba[0], rgba[1], rgba[2], rgba[3]);
        previewBox.setBackground(rounded(start));
        hexLabel.setText(Config.toHex(start));

        new android.app.AlertDialog.Builder(this)
                .setTitle(isDarkGroup ? "深色背景时的颜色" : "浅色背景时的颜色")
                .setView(box)
                .setPositiveButton("确定", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d0, int w) {
                        int c = Color.argb(rgba[0], rgba[1], rgba[2], rgba[3]);
                        et.setText(Config.toHex(c));
                        applySwatch(isDarkGroup, c);
                        preview();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }


    private static double luminance(int c) {
        double r = android.graphics.Color.red(c) / 255.0;
        double g = android.graphics.Color.green(c) / 255.0;
        double b = android.graphics.Color.blue(c) / 255.0;
        return 0.299 * r + 0.587 * g + 0.114 * b;
    }

    @Override protected void onResume() {
        super.onResume();
        ticker.removeCallbacks(tick);
        ticker.post(tick);          // 预览里的时间每秒走一格
    }

    @Override protected void onPause() {
        super.onPause();
        ticker.removeCallbacks(tick);
    }

    private GradientDrawable circle(String hex) {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Config.parseColor(hex, 0xFF888888));
        // 描边色随界面深浅走：浅色界面用深色细边（否则白底上白块看不见边界），
        // 深色界面沿用原来的浅色边。色值在 values / values-night 里。
        bg.setStroke(dp(1), getResources().getColor(R.color.sc_swatch_stroke));
        return bg;
    }

    /** 圆角倍数：在各自「原半径」基础上的缩放系数。
     *  1.0 = 原样；1.5 = 放大 50%；0.75 = 取上一次设置的一半。 */
    private static final float RADIUS_SCALE = 0.75f;

    /** 色块单独一档：在当前基础上再增大 1 倍（= 基准的 1.5 倍）。 */
    private static final float SWATCH_SCALE = RADIUS_SCALE * 2f;

    private int scaled(int base) {
        return Math.round(base * RADIUS_SCALE);
    }

    private int scaledSwatch(int base) {
        return Math.round(base * SWATCH_SCALE);
    }

    /**
     * 色块 / 取色器里的色块。
     * 半径恢复成原来那个基准 dp(4)，再按倍数放大 —— 不再跟着按钮半径走
     * （按钮半径是主题给的，两者基准本来就不同）。
     */
    private GradientDrawable rounded(int color) {
        GradientDrawable g = roundedSurface(color, scaledSwatch(dp(4)));
        // 与预设色卡同样的描边：浅色界面深色细边、深色界面浅色边。
        // 覆盖：输入框左边的色块、取色面板顶部的预览块。
        // 注意只在这里加 —— roundedSurface() 还被卡片和按钮复用，它们不该有边。
        g.setStroke(dp(1), getResources().getColor(R.color.sc_swatch_stroke));
        return g;
    }




    // ─────────────────────────────────────────────────────── 全面屏

    /**
     * 内容延伸到状态栏 / 导航栏之后，系统栏背景透明，
     * 于是应用自己的底色（@color/sc_bg）就自然铺满状态栏区域 ——
     * 状态栏和页面看起来是一整块。
     *
     * 代价是内容会被系统栏盖住，所以要按 insets 补内边距。
     * 图标明暗由主题里的 windowLightStatusBar 跟随系统深浅，无需运行期干预。
     */
    private void applyEdgeToEdge() {
        Window w = getWindow();
        try {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                w.setDecorFitsSystemWindows(false);
            } else {
                w.getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
            }
        } catch (Throwable ignored) {}

        View root = findViewById(R.id.root_scroll);
        if (root == null) return;
        int top = systemBarHeight("status_bar_height", 28);
        int bottom = systemBarHeight("navigation_bar_height", 24);
        root.setPadding(root.getPaddingLeft(), top,
                        root.getPaddingRight(), bottom);
    }

    private int systemBarHeight(String name, int fallbackDp) {
        try {
            int id = getResources().getIdentifier(name, "dimen", "android");
            if (id > 0) {
                int px = getResources().getDimensionPixelSize(id);
                if (px > 0) return px;
            }
        } catch (Throwable ignored) {}
        return dp(fallbackDp);
    }

    // ─────────────────────────────────────────────────────── 圆角统一

    /**
     * 预览框、卡片、日志框的圆角统一取「按钮的圆角」。
     * 不去猜具体数值，而是问按钮自己的背景要 Outline：
     * 拿到多少就用多少，取不到再退回 10dp。
     */
    private int buttonCornerRadius() {
        try {
            Button b = (Button) findViewById(R.id.btn_save);
            if (b != null && b.getBackground() != null) {
                android.graphics.Outline o = new android.graphics.Outline();
                b.getBackground().getOutline(o);        // 填充 o，无返回值
                if (!o.isEmpty() && o.getRadius() > 0) {
                    return (int) o.getRadius();
                }
            }
        } catch (Throwable ignored) {}
        return dp(10);
    }

    private GradientDrawable roundedSurface(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    /**
     * 按钮圆角同样翻倍。
     * 直接换背景会丢掉按压反馈，所以外面套一层 RippleDrawable：
     * 内容是我们自绘的圆角矩形，水波纹仍然照常。
     * 底色取主题的 colorButtonNormal，文字颜色不受影响。
     */
    private void roundButtons() {
        int r = scaled(buttonCornerRadius());   // 按钮原半径（主题给的值）× 1.5
        int normal = resolveThemeColor(android.R.attr.colorButtonNormal, 0xFFE6E6E6);
        int[] ids = { R.id.btn_save, R.id.btn_diag,
                      R.id.btn_dark_default, R.id.btn_light_default };
        for (int id : ids) {
            View v = findViewById(id);
            if (v == null) continue;
            GradientDrawable shape = roundedSurface(normal, r);
            v.setBackground(new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(0x33000000),
                    shape, null));
        }
    }

    private int resolveThemeColor(int attr, int fallback) {
        try {
            android.util.TypedValue tv = new android.util.TypedValue();
            if (getTheme().resolveAttribute(attr, tv, true)) {
                if (tv.resourceId != 0) return getResources().getColor(tv.resourceId);
                if (tv.data != 0) return tv.data;
            }
        } catch (Throwable ignored) {}
        return fallback;
    }

    /** 按钮整体收小：高度、内边距、字号各减一档。 */
    private void shrinkButtons() {
        int h = dp(38);
        int[] ids = { R.id.btn_save, R.id.btn_diag,
                      R.id.btn_dark_default, R.id.btn_light_default };
        for (int id : ids) {
            View v = findViewById(id);
            if (!(v instanceof Button)) continue;
            Button b = (Button) v;
            b.setMinHeight(h);
            b.setMinimumHeight(h);
            b.setPadding(dp(14), 0, dp(14), 0);
            b.setTextSize(13);
        }
    }

    private void roundSurfaces() {
        int r = scaled(buttonCornerRadius());   // 与按钮保持一致
        int card = getResources().getColor(R.color.sc_card);

        int[] cardIds = { R.id.card_switch, R.id.card_dark, R.id.card_light,
                          R.id.card_mode, R.id.tv_log };
        for (int id : cardIds) {
            View v = findViewById(id);
            if (v != null) v.setBackground(roundedSurface(card, r));
        }
        // 预览框底色固定：深色组纯黑、浅色组纯白
        if (tvDarkPreview != null) {
            tvDarkPreview.setBackground(roundedSurface(0xFF000000, r));
        }
        if (tvLightPreview != null) {
            tvLightPreview.setBackground(roundedSurface(0xFFFFFFFF, r));
        }
    }


    /**
     * 色块 = 底色 + 中间那个编辑图标。
     * 图标颜色按底色亮度自动取反（白底黑笔、黑底白笔），
     * 与预设色卡的对勾用的是同一套判断。
     */
    private void applySwatch(boolean isDarkGroup, int c) {
        View sw = isDarkGroup ? swDark : swLight;
        android.widget.ImageView ic = isDarkGroup ? icDarkEdit : icLightEdit;
        if (sw != null) sw.setBackground(rounded(c));
        if (ic != null) {
            ic.setImageTintList(android.content.res.ColorStateList.valueOf(
                    luminance(c) > 0.5 ? 0xAA000000 : 0xEEFFFFFF));
        }
    }

    /** 按输入框当前内容刷新两个色块（启动时、恢复默认后都用它兜底）。 */
    private void syncSwatches() {
        applySwatch(true, Config.parseColor(etDark.getText().toString(), 0xFFFFFFFF));
        applySwatch(false, Config.parseColor(etLight.getText().toString(), 0xFF000000));
        preview();
    }

    private TextWatcher watcher(final boolean isDarkGroup) {
        return new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                int def = isDarkGroup ? 0xFFFFFFFF : 0xFF000000;
                int c = Config.parseColor(s == null ? null : s.toString(), def);
                applySwatch(isDarkGroup, c);
                preview();
            }
        };
    }

    /** 用当前控件值刷新预览。 */
    /**
     * 两组各自预览：深色组画在纯黑底上、浅色组画在纯白底上。
     * 颜色与 alpha 都原样画出来，所见即状态栏所得。
     */
    private void preview() {
        boolean enabled = swEnable.isChecked();
        int darkC  = Config.parseColor(etDark.getText().toString(), 0xFFFFFFFF);
        int lightC = Config.parseColor(etLight.getText().toString(), 0xFF000000);

        if (tvDarkPreview != null) {
            tvDarkPreview.setTextColor(enabled ? darkC : 0xFFFFFFFF);
        }
        if (tvLightPreview != null) {
            tvLightPreview.setTextColor(enabled ? lightC : 0xFF000000);
        }
        String t = fmt.format(new java.util.Date());
        if (tvDarkPreview != null) tvDarkPreview.setText(t);
        if (tvLightPreview != null) tvLightPreview.setText(t);

        updatePaletteChecks(true);
        updatePaletteChecks(false);
    }

    /** 预设色里与当前 hex 相同的那个打勾。 */
    private void updatePaletteChecks(boolean isDarkGroup) {
        LinearLayout row = (LinearLayout) findViewById(
                isDarkGroup ? R.id.ll_dark_palette : R.id.ll_light_palette);
        if (row == null) return;
        String cur = Config.toHex(Config.parseColor(
                (isDarkGroup ? etDark : etLight).getText().toString(),
                isDarkGroup ? 0xFFFFFFFF : 0xFF000000));
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            Object tag = child.getTag();
            if (!(tag instanceof String)) continue;
            View check = child.findViewById(R.id.tv_palette_check);
            if (check == null) continue;
            check.setVisibility(((String) tag).equalsIgnoreCase(cur)
                    ? View.VISIBLE : View.INVISIBLE);
        }
    }

    // ─────────────────────────────────────────────────────── 存取

    private void loadIntoUi() {
        binding = true;
        SharedPreferences sp = Config.prefs(this);
        swEnable.setChecked(sp.getBoolean(Config.KEY_ENABLE, false));
        etDark.setText(sp.getString(Config.KEY_DARK, Config.DEFAULT_DARK));
        etLight.setText(sp.getString(Config.KEY_LIGHT, Config.DEFAULT_LIGHT));

        String mode = sp.getString(Config.KEY_MODE, Config.MODE_AUTO);
        if (Config.MODE_DARK.equals(mode)) rbDark.setChecked(true);
        else if (Config.MODE_LIGHT.equals(mode)) rbLight.setChecked(true);
        else rbAuto.setChecked(true);
        binding = false;
        preview();
    }

    private void save() {
        final String mode = rbDark.isChecked() ? Config.MODE_DARK
                : rbLight.isChecked() ? Config.MODE_LIGHT
                : Config.MODE_AUTO;

        SharedPreferences.Editor e = Config.prefs(this).edit();
        e.putBoolean(Config.KEY_ENABLE, swEnable.isChecked());
        e.putString(Config.KEY_DARK, Config.toHex(
                Config.parseColor(etDark.getText().toString(), 0xFFFFFFFF)));
        e.putString(Config.KEY_LIGHT, Config.toHex(
                Config.parseColor(etLight.getText().toString(), 0xFF000000)));
        e.putInt(Config.KEY_ALPHA, 100);   // 透明度过时字段，恒为 100
        e.putString(Config.KEY_MODE, mode);
        e.commit();

        appendLog("已保存：" + mode);
        Toast.makeText(this, "已保存，正在同步并重启 SystemUI…", Toast.LENGTH_SHORT).show();
        restartSystemUi();
    }

    /**
     * 把当前配置写成 "key=value" 文本，镜像到 /data/local/tmp/statcolor.conf（0644）。
     *
     * 这是 Hook 侧最可靠的配置来源：不依赖 XSharedPreferences 的可见性，
     * 也不受 getSharedPreferences 在 CE/DE 存储间分叉的影响。
     * 需要 root；失败不致命，Hook 会退回另外两条路。
     *
     * @return null 表示成功，否则返回错误说明
     */
    private String syncConfFile() {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append(Config.KEY_ENABLE).append('=').append(swEnable.isChecked()).append('\n');
            sb.append(Config.KEY_DARK).append('=')
              .append(Config.toHex(Config.parseColor(etDark.getText().toString(), 0xFFFFFFFF)))
              .append('\n');
            sb.append(Config.KEY_LIGHT).append('=')
              .append(Config.toHex(Config.parseColor(etLight.getText().toString(), 0xFF000000)))
              .append('\n');
            sb.append(Config.KEY_MODE).append('=').append(
                    rbDark.isChecked() ? Config.MODE_DARK
                            : rbLight.isChecked() ? Config.MODE_LIGHT
                            : Config.MODE_AUTO).append('\n');

            // 先写应用私有目录（必定可写）
            File tmp = new File(getFilesDir(), "statcolor.conf");
            FileOutputStream fos = new FileOutputStream(tmp);
            try {
                fos.write(sb.toString().getBytes("UTF-8"));
            } finally {
                fos.close();
            }

            // 再用 root 拷到全局可读位置
            String out = exec("su", "-c",
                    "cp " + tmp.getAbsolutePath() + " " + Hook.CONF_FILE
                            + " && chmod 644 " + Hook.CONF_FILE);
            String verify = exec("su", "-c", "ls -l " + Hook.CONF_FILE);
            if (verify == null || !verify.contains("statcolor.conf")) {
                return "su 写入失败，输出=" + out;
            }
            return null;
        } catch (Throwable t) {
            return "异常：" + t;
        }
    }

    /** 重启 SystemUI 让配置生效。先同步配置文件，再 kill。 */
    private void restartSystemUi() {
        new Thread(new Runnable() {
            @Override public void run() {
                final String syncErr = syncConfFile();
                String out = exec("su", "-c", "killall com.android.systemui");
                final String r = out;
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override public void run() {
                        if (syncErr != null) {
                            appendLog("配置镜像同步失败 —— " + syncErr);
                        } else {
                            appendLog("配置已同步到 " + Hook.CONF_FILE);
                        }
                        if (r == null || r.startsWith("__ERR__")) {
                            appendLog("自动重启失败，请手动重启手机。");
                            Toast.makeText(MainActivity.this,
                                    "请手动重启手机使配置生效", Toast.LENGTH_LONG).show();
                        } else {
                            appendLog("已请求重启 SystemUI");
                        }
                    }
                });
            }
        }).start();
    }

    /** 读回配置文件内容，用于自诊断。 */
    private void showDiag() {
        appendLog("──── 自诊断 ────");
        appendLog("模块版本 " + Hook.VERSION + " · 配置文件 " + Hook.CONF_FILE);

        String r = exec("su", "-c", "ls -l " + Hook.CONF_FILE);
        if (r == null || r.startsWith("__ERR__") || r.indexOf("statcolor.conf") < 0) {
            appendLog("✗ 配置文件不存在（Hook 将退回 SharedPreferences）");
        } else {
            appendLog("✓ " + r.trim());
            String body = exec("su", "-c", "cat " + Hook.CONF_FILE);
            appendLog("内容：\n" + (body == null ? "(读不到)" : body.trim()));
        }

        SharedPreferences sp = Config.prefs(this);
        appendLog("SharedPreferences: enable="
                + sp.getBoolean(Config.KEY_ENABLE, false)
                + " mode=" + sp.getString(Config.KEY_MODE, "auto"));
    }

    private String exec(String... cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            StringBuilder sb = new StringBuilder();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            try {
                String line;
                while ((line = br.readLine()) != null) sb.append(line).append('\n');
            } finally {
                try { br.close(); } catch (Throwable ignored) {}
            }
            p.waitFor();
            return sb.toString();
        } catch (Throwable t) {
            return "__ERR__" + t;
        }
    }

    private void appendLog(String s) {
        if (tvLog == null) return;
        tvLog.setText(tvLog.getText() + "\n" + s);
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}
