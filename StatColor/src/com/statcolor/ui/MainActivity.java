package com.statcolor.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.ScrollView;
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
 * 设置界面。
 *
 * <p>整屏用框架控件在 Java 里搭出来，挂在 {@code Theme.DeviceDefault.DayNight} 上 ——
 * 与 NativePowerMenu 用同一套 AOSP 设计语言：不打包 Material Components、不写 XML 布局，
 * 颜色一律从框架主题属性取（{@code colorBackgroundFloating} / {@code textColorPrimary} /
 * {@code textColorSecondary} / {@code colorAccent}），因此深浅色完全跟随系统。
 *
 * <p>版式沿用参考项目：18dp 圆角的卡片、13sp 全大写强调色小标题、行间 1px 分隔线、
 * 胶囊形主按钮。
 */
public class MainActivity extends Activity {

    // ─────────────────────────────────────────────────────── 设计尺寸

    /** 卡片圆角。 */
    private static final int CARD_RADIUS_DP = 18;
    /** 卡片内部的表面（预览条、色块）圆角。 */
    private static final int INNER_RADIUS_DP = 14;
    /** 行内左右内边距。 */
    private static final int ROW_PAD_H_DP = 16;
    /** 行内上下内边距。 */
    private static final int ROW_PAD_V_DP = 14;
    /** 主按钮高度（胶囊半径 = 高度 / 2）。 */
    private static final int PILL_HEIGHT_DP = 52;
    /** 色块尺寸。 */
    private static final int SWATCH_W_DP = 56;
    private static final int SWATCH_H_DP = 44;

    private static final String[] PALETTE = {
            "#FFFFFFFF", "#FF000000", "#FFFF3B30", "#FFFF9500", "#FFFFCC00",
            "#FF34C759", "#FF00C7BE", "#FF0A84FF", "#FF5E5CE6", "#FFFF2D55",
            "#FF8E8E93", "#FF64D2FF",
    };

    // ─────────────────────────────────────────────────────── 控件

    private ScrollView mScrollView;
    private LinearLayout mContent;

    private Switch swEnable;
    private EditText etDark, etLight;
    private View swDark, swLight;
    private ImageView icDarkEdit, icLightEdit;
    private TextView tvDarkPreview, tvLightPreview;
    private LinearLayout rowDarkPalette, rowLightPalette;
    private TextView tvLog;

    private boolean binding = false;

    /** 日志正文。用 StringBuilder 记账，避免从 TextView 回读时带上多余换行。 */
    private final StringBuilder mLog = new StringBuilder();
    private final java.text.SimpleDateFormat logFmt =
            new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault());
    private boolean mInsetsApplied = false;

    /** 预览条上的时间每秒走一格。 */
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

    // ─────────────────────────────────────────────────────── 生命周期

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 主题是 DayNight（不是 NoActionBar 变体，为的是让系统深浅色直接生效），
        // 所以标题栏要显式藏掉，而不是留一条空栏。与 NativePowerMenu 做法一致。
        android.app.ActionBar actionBar = getActionBar();
        if (actionBar != null) actionBar.hide();

        mScrollView = new ScrollView(this);
        mScrollView.setFillViewport(true);
        mContent = new LinearLayout(this);
        mContent.setOrientation(LinearLayout.VERTICAL);
        mContent.setPadding(dp(20), dp(20), dp(20), dp(32));
        mScrollView.addView(mContent, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        applyEdgeToEdge();

        buildContentView();
        setContentView(mScrollView);

        // 先挂监听再载入：否则 setText 不会触发色块更新，
        // 左侧色块会停在初始值，看起来像"每次启动都恢复成默认"。
        etDark.addTextChangedListener(watcher(true));
        etLight.addTextChangedListener(watcher(false));
        loadIntoUi();
        syncSwatches();
        applySystemBarInsets();
        reportModuleState();
    }

    @Override protected void onResume() {
        super.onResume();
        ticker.removeCallbacks(tick);
        ticker.post(tick);
    }

    @Override protected void onPause() {
        super.onPause();
        ticker.removeCallbacks(tick);
    }

    // ─────────────────────────────────────────────────────── 界面搭建

    private void buildContentView() {
        mContent.addView(buildMasterCard(), margins(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                0, 0, 0, dp(24)));

        addSectionHeader(R.string.settings_dark_header);
        mContent.addView(buildColorCard(true), margins(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                0, 0, 0, dp(24)));

        addSectionHeader(R.string.settings_light_header);
        mContent.addView(buildColorCard(false), margins(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                0, 0, 0, dp(24)));

        Button save = new Button(this);
        save.setText(R.string.settings_save);
        save.setAllCaps(false);
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { save(); }
        });
        stylePillButton(save);
        mContent.addView(save, margins(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                0, 0, 0, dp(24)));

        addSectionHeader(R.string.settings_log_header);
        mContent.addView(buildLogCard(), margins(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                0, 0, 0, 0));

    }

    /** 总开关卡片：标题 + 说明 + 右侧开关，整行可点。 */
    private View buildMasterCard() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(cardBackground());
        row.setPadding(dp(ROW_PAD_H_DP), dp(ROW_PAD_V_DP),
                dp(ROW_PAD_H_DP), dp(ROW_PAD_V_DP));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);

        TextView label = new TextView(this);
        label.setText(R.string.settings_enable);
        label.setTextSize(16);
        label.setTextColor(themeColorList(android.R.attr.textColorPrimary));
        texts.addView(label);

        TextView desc = new TextView(this);
        desc.setText(R.string.settings_enable_desc);
        desc.setTextSize(12);
        desc.setTextColor(themeColorList(android.R.attr.textColorSecondary));
        texts.addView(desc);

        row.addView(texts, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        swEnable = new Switch(this);
        row.addView(swEnable);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { swEnable.toggle(); }
        });
        swEnable.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean v) {
                preview();
            }
        });
        return row;
    }

    /** 一组颜色：预览条 + 色块行 + 预设色卡。 */
    private View buildColorCard(final boolean isDarkGroup) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(cardBackground());

        // ① 预览条：底色固定纯黑 / 纯白，文字就是当前配置色（含透明度）
        TextView previewView = new TextView(this);
        previewView.setTextSize(16);
        previewView.setPadding(dp(16), dp(14), dp(16), dp(14));
        // 底色固定纯黑 / 纯白：这一条就是用来对照状态的，必须自己带背景
        previewView.setBackground(innerSurface(isDarkGroup ? 0xFF000000 : 0xFFFFFFFF));
        if (isDarkGroup) tvDarkPreview = previewView; else tvLightPreview = previewView;
        card.addView(previewView, margins(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(12), dp(12), dp(12), dp(4)));

        // ② 色块 + 十六进制 + 恢复默认
        LinearLayout colorRow = new LinearLayout(this);
        colorRow.setOrientation(LinearLayout.HORIZONTAL);
        colorRow.setGravity(Gravity.CENTER_VERTICAL);
        colorRow.setPadding(dp(ROW_PAD_H_DP), dp(6), dp(8), dp(6));

        FrameLayout swatchBox = new FrameLayout(this);
        View swatch = new View(this);
        swatch.setLayoutParams(new FrameLayout.LayoutParams(
                dp(SWATCH_W_DP), dp(SWATCH_H_DP)));
        swatch.setClickable(true);
        swatch.setFocusable(true);
        swatchBox.addView(swatch);

        ImageView edit = new ImageView(this);
        FrameLayout.LayoutParams elp = new FrameLayout.LayoutParams(dp(18), dp(18));
        elp.gravity = Gravity.CENTER;
        edit.setLayoutParams(elp);
        edit.setImageResource(R.drawable.ic_edit);
        edit.setClickable(false);
        edit.setFocusable(false);
        swatchBox.addView(edit);

        if (isDarkGroup) { swDark = swatch; icDarkEdit = edit; }
        else { swLight = swatch; icLightEdit = edit; }

        swatch.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openPicker(isDarkGroup); }
        });

        colorRow.addView(swatchBox);

        EditText hex = new EditText(this);
        hex.setTextSize(15);
        hex.setSingleLine(true);
        hex.setHint(isDarkGroup
                ? getString(R.string.settings_hex_hint_dark)
                : getString(R.string.settings_hex_hint_light));
        hex.setTextColor(themeColorList(android.R.attr.textColorPrimary));
        hex.setHintTextColor(themeColorList(android.R.attr.textColorSecondary));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        hlp.leftMargin = dp(12);
        colorRow.addView(hex, hlp);
        if (isDarkGroup) etDark = hex; else etLight = hex;

        Button reset = new Button(this);
        reset.setText(R.string.settings_reset);
        reset.setAllCaps(false);
        reset.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                EditText target = isDarkGroup ? etDark : etLight;
                target.setText(isDarkGroup ? Config.DEFAULT_DARK : Config.DEFAULT_LIGHT);
                syncSwatches();
            }
        });
        styleTextButton(reset);
        colorRow.addView(reset);

        card.addView(colorRow, margins(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                0, 0, 0, 0));
        card.addView(divider(), margins(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2),
                dp(ROW_PAD_H_DP), 0, dp(ROW_PAD_H_DP), 0));

        // ③ 预设色卡（可横向滑动，选中打勾）
        LinearLayout palette = new LinearLayout(this);
        palette.setOrientation(LinearLayout.HORIZONTAL);
        if (isDarkGroup) rowDarkPalette = palette; else rowLightPalette = palette;
        buildPalette(palette, isDarkGroup);

        HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setClipToPadding(false);
        scroller.addView(palette, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        scroller.setPadding(dp(ROW_PAD_H_DP), dp(14), dp(ROW_PAD_H_DP), dp(14));
        card.addView(scroller, margins(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                0, 0, 0, 0));

        return card;
    }

    private View buildLogCard() {
        tvLog = new TextView(this);
        tvLog.setTextSize(12);
        tvLog.setTypeface(Typeface.MONOSPACE);
        tvLog.setTextColor(themeColorList(android.R.attr.textColorSecondary));
        tvLog.setTextIsSelectable(true);
        tvLog.setPadding(dp(ROW_PAD_H_DP), dp(ROW_PAD_V_DP),
                dp(ROW_PAD_H_DP), dp(ROW_PAD_V_DP));
        tvLog.setBackground(cardBackground());
        tvLog.setText("");
        return tvLog;
    }

    private void addSectionHeader(int textRes) {
        TextView header = new TextView(this);
        header.setText(textRes);
        header.setTextSize(13);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setAllCaps(true);
        header.setTextColor(themeColorList(android.R.attr.colorAccent));
        header.setLetterSpacing(0.06f);
        mContent.addView(header, margins(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(4), 0, 0, dp(8)));
    }

    /** 预设色卡：圆点 + 选中时的对勾（勾色按底色亮度自动取反）。 */
    private void buildPalette(LinearLayout row, final boolean isDarkGroup) {
        row.removeAllViews();
        int size = dp(40);
        for (int i = 0; i < PALETTE.length; i++) {
            final String hex = PALETTE[i];
            final int c = Config.parseColor(hex, 0xFF888888);

            FrameLayout cell = new FrameLayout(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.rightMargin = dp(10);
            cell.setLayoutParams(lp);
            cell.setTag(hex);
            cell.setContentDescription(hex);

            View dot = new View(this);
            dot.setLayoutParams(new FrameLayout.LayoutParams(size, size));
            dot.setBackground(circle(hex));
            cell.addView(dot);

            TextView check = new TextView(this);
            check.setLayoutParams(new FrameLayout.LayoutParams(size, size));
            check.setText("✓");
            check.setTextSize(18);
            check.setGravity(Gravity.CENTER);
            check.setTextColor(luminance(c) > 0.5 ? 0xFF000000 : 0xFFFFFFFF);
            check.setVisibility(View.INVISIBLE);
            cell.addView(check);

            cell.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    EditText target = isDarkGroup ? etDark : etLight;
                    target.setText(hex);
                    syncSwatches();
                }
            });
            row.addView(cell);
        }
    }

    // ─────────────────────────────────────────────────────── 取色面板

    /** 平台没有内置取色器，这里自己搭一个：预览块 + A/R/G/B 滑杆 + 十进制数值框。 */
    private void openPicker(final boolean isDarkGroup) {
        final EditText et = isDarkGroup ? etDark : etLight;
        final int fallback = isDarkGroup ? 0xFFFFFFFF : 0xFF000000;

        int cur = Config.parseColor(et.getText().toString(), fallback);
        final int[] rgba = { (cur >>> 24) & 0xFF, (cur >>> 16) & 0xFF,
                             (cur >>> 8) & 0xFF, cur & 0xFF };

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);

        final View previewBox = new View(this);
        previewBox.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56)));
        previewBox.setBackground(innerSurface(Color.argb(rgba[0], rgba[1], rgba[2], rgba[3])));
        box.addView(previewBox);

        final TextView hexLabel = new TextView(this);
        hexLabel.setTextSize(14);
        hexLabel.setTextColor(themeColorList(android.R.attr.textColorPrimary));
        hexLabel.setPadding(0, dp(10), 0, dp(4));
        box.addView(hexLabel);

        final boolean[] syncing = { false };
        final int[] names = { R.string.settings_channel_alpha, R.string.settings_channel_red,
                              R.string.settings_channel_green, R.string.settings_channel_blue };
        final SeekBar[] bars = new SeekBar[4];
        final EditText[] nums = new EditText[4];

        for (int i = 0; i < 4; i++) {
            final int idx = i;

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            TextView label = new TextView(this);
            label.setText(names[i]);
            label.setTextSize(12);
            label.setTextColor(themeColorList(android.R.attr.textColorSecondary));
            label.setLayoutParams(new LinearLayout.LayoutParams(
                    dp(84), LinearLayout.LayoutParams.WRAP_CONTENT));
            row.addView(label);

            SeekBar sb = new SeekBar(this);
            sb.setMax(255);
            sb.setProgress(rgba[i]);
            sb.setLayoutParams(new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(sb);

            EditText num = new EditText(this);
            num.setInputType(InputType.TYPE_CLASS_NUMBER);
            num.setText(String.valueOf(rgba[i]));
            num.setTextSize(13);
            num.setGravity(Gravity.CENTER);
            num.setTextColor(themeColorList(android.R.attr.textColorPrimary));
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                    dp(58), LinearLayout.LayoutParams.WRAP_CONTENT);
            nlp.leftMargin = dp(6);
            num.setLayoutParams(nlp);
            row.addView(num);

            sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                    if (syncing[0]) return;
                    syncing[0] = true;
                    rgba[idx] = value;
                    nums[idx].setText(String.valueOf(value));
                    refreshPicker(previewBox, hexLabel, rgba);
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
                    refreshPicker(previewBox, hexLabel, rgba);
                    syncing[0] = false;
                }
            });

            bars[i] = sb;
            nums[i] = num;
            box.addView(row);
        }
        hexLabel.setText(Config.toHex(Color.argb(rgba[0], rgba[1], rgba[2], rgba[3])));

        new AlertDialog.Builder(this)
                .setTitle(isDarkGroup ? R.string.settings_pick_dark_title
                                      : R.string.settings_pick_light_title)
                .setView(box)
                .setPositiveButton(R.string.settings_ok, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        et.setText(Config.toHex(
                                Color.argb(rgba[0], rgba[1], rgba[2], rgba[3])));
                        syncSwatches();
                    }
                })
                .setNegativeButton(R.string.settings_cancel, null)
                .show();
    }

    private void refreshPicker(View previewBox, TextView hexLabel, int[] rgba) {
        int c = Color.argb(rgba[0], rgba[1], rgba[2], rgba[3]);
        previewBox.setBackground(innerSurface(c));
        hexLabel.setText(Config.toHex(c));
    }

    // ─────────────────────────────────────────────────────── 预览与色块

    private void applySwatch(boolean isDarkGroup, int c) {
        View sw = isDarkGroup ? swDark : swLight;
        ImageView ic = isDarkGroup ? icDarkEdit : icLightEdit;
        if (sw != null) sw.setBackground(innerSurface(c));
        if (ic != null) {
            ic.setImageTintList(ColorStateList.valueOf(
                    luminance(c) > 0.5 ? 0xAA000000 : 0xEEFFFFFF));
        }
    }

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
                applySwatch(isDarkGroup, Config.parseColor(s == null ? null : s.toString(), def));
                preview();
            }
        };
    }

    /** 两组各自预览：深色组画在纯黑底上、浅色组画在纯白底上。 */
    private void preview() {
        boolean enabled = swEnable.isChecked();
        int darkC  = Config.parseColor(etDark.getText().toString(), 0xFFFFFFFF);
        int lightC = Config.parseColor(etLight.getText().toString(), 0xFF000000);
        if (tvDarkPreview != null) tvDarkPreview.setTextColor(enabled ? darkC : 0xFFFFFFFF);
        if (tvLightPreview != null) tvLightPreview.setTextColor(enabled ? lightC : 0xFF000000);

        String t = fmt.format(new java.util.Date());
        if (tvDarkPreview != null) tvDarkPreview.setText(t);
        if (tvLightPreview != null) tvLightPreview.setText(t);

        updatePaletteChecks(true);
        updatePaletteChecks(false);
    }

    private void updatePaletteChecks(boolean isDarkGroup) {
        LinearLayout row = isDarkGroup ? rowDarkPalette : rowLightPalette;
        if (row == null) return;
        String cur = Config.toHex(Config.parseColor(
                (isDarkGroup ? etDark : etLight).getText().toString(),
                isDarkGroup ? 0xFFFFFFFF : 0xFF000000));
        for (int i = 0; i < row.getChildCount(); i++) {
            View cell = row.getChildAt(i);
            Object tag = cell.getTag();
            if (!(tag instanceof String) || !(cell instanceof ViewGroup)) continue;
            ViewGroup group = (ViewGroup) cell;
            if (group.getChildCount() < 2) continue;
            group.getChildAt(1).setVisibility(
                    ((String) tag).equalsIgnoreCase(cur) ? View.VISIBLE : View.INVISIBLE);
        }
    }

    // ─────────────────────────────────────────────────────── 存取

    private void loadIntoUi() {
        binding = true;
        SharedPreferences sp = Config.prefs(this);
        swEnable.setChecked(sp.getBoolean(Config.KEY_ENABLE, false));
        etDark.setText(sp.getString(Config.KEY_DARK, Config.DEFAULT_DARK));
        etLight.setText(sp.getString(Config.KEY_LIGHT, Config.DEFAULT_LIGHT));

        binding = false;
        preview();
    }

    private void save() {
        SharedPreferences.Editor e = Config.prefs(this).edit();
        e.putBoolean(Config.KEY_ENABLE, swEnable.isChecked());
        e.putString(Config.KEY_DARK, Config.toHex(
                Config.parseColor(etDark.getText().toString(), 0xFFFFFFFF)));
        e.putString(Config.KEY_LIGHT, Config.toHex(
                Config.parseColor(etLight.getText().toString(), 0xFF000000)));
        e.putInt(Config.KEY_ALPHA, 100);   // 透明度过时字段，恒为 100
        e.commit();

        appendLog("已保存");
        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show();
        restartSystemUi();
    }

    /**
     * 把配置写成 "key=value" 文本，镜像到 /data/local/tmp/statcolor.conf（0644）。
     *
     * <p>这是 Hook 侧最可靠的配置来源：不依赖 XSharedPreferences 的可见性，
     * 也不受 getSharedPreferences 在 CE/DE 存储间分叉的影响。需要 root；
     * 失败不致命，Hook 会退回另外两条路。
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

            File tmp = new File(getFilesDir(), "statcolor.conf");
            FileOutputStream fos = new FileOutputStream(tmp);
            try {
                fos.write(sb.toString().getBytes("UTF-8"));
            } finally {
                fos.close();
            }

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
                // 每次应用后把配置文件读回来打出来，取代原来的「自诊断」按钮
                final String dump = syncErr == null
                        ? exec("su", "-c", "cat " + Hook.CONF_FILE) : null;
                final String r = exec("su", "-c", "killall com.android.systemui");
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override public void run() {
                        if (syncErr != null) {
                            appendLog("配置同步失败 —— " + syncErr);
                        } else {
                            appendLog("配置已写入 " + Hook.CONF_FILE);
                            appendLog(dump == null || dump.trim().isEmpty()
                                    ? "(读不到内容)" : dump.trim());
                        }

                        if (r == null || r.startsWith("__ERR__")) {
                            appendLog("自动重启失败，请手动重启手机。");
                            Toast.makeText(MainActivity.this,
                                    R.string.settings_restart_failed, Toast.LENGTH_LONG).show();
                        } else {
                            appendLog("已请求重启 SystemUI");
                        }
                    }
                });
            }
        }).start();
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

    /**
     * 追加一行日志。
     *
     * <p>不再用 {@code tvLog.getText() + "\n" + s} 拼接 —— 那样第一条前面会多出一个
     * 换行，日志首行是空的。改用 StringBuilder 记账，并且给**每一行**都加上时间戳
     * （配置文件内容是多行的，逐行加才读得清）。
     */
    private void appendLog(String s) {
        if (tvLog == null || s == null) return;
        String[] lines = s.split("\n", -1);
        for (String line : lines) {
            if (mLog.length() > 0) mLog.append('\n');
            mLog.append('[').append(logFmt.format(new java.util.Date())).append("] ").append(line);
        }
        tvLog.setText(mLog);
    }

    /**
     * 启动时报告模块激活状态。
     *
     * <p>App 进程看不到 SystemUI 里有没有加载本模块，但 LSPosed 的日志里有 ——
     * 每次注入都会留下 {@code StatColor: === vX.Y enter com.android.systemui}。
     * 直接去捞最后一条，比让用户自己翻日志快得多。
     */
    private void reportModuleState() {
        appendLog("模块版本 " + Hook.VERSION);
        new Thread(new Runnable() {
            @Override public void run() {
                final String out = exec("su", "-c",
                        "grep -a 'StatColor: === v' /data/adb/lspd/log/modules_*.log | tail -n 1");
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override public void run() {
                        if (out == null || out.startsWith("__ERR__")) {
                            appendLog("无法读取 LSPosed 日志（" + out + "）");
                            return;
                        }
                        String line = out.trim();
                        if (line.isEmpty()) {
                            appendLog("未找到激活记录 —— 请确认模块已启用、作用域勾选了"
                                    + "「系统界面」，然后重启 SystemUI");
                            return;
                        }
                        int i = line.indexOf("StatColor:");
                        appendLog("模块已激活：" + (i >= 0 ? line.substring(i) : line));
                    }
                });
            }
        }).start();
    }

    // ─────────────────────────────────────────────────────── 主题与绘制

    private ColorStateList themeColorList(int attribute) {
        TypedArray array = obtainStyledAttributes(new int[]{attribute});
        try {
            ColorStateList list = array.getColorStateList(0);
            if (list != null) return list;
        } catch (Throwable ignored) {
            // 落到下面的兜底值
        } finally {
            array.recycle();
        }
        return ColorStateList.valueOf(Color.GRAY);
    }

    private int themeColor(int attribute) {
        return themeColorList(attribute).getDefaultColor();
    }

    /** 卡片：18dp 圆角 + 半像素描边。 */
    private Drawable cardBackground() {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setColor(themeColor(android.R.attr.colorBackgroundFloating));
        bg.setCornerRadius(dp(CARD_RADIUS_DP));
        int stroke = themeColor(android.R.attr.textColorSecondary);
        bg.setStroke(hairline(), (stroke & 0x00FFFFFF) | 0x33000000);
        return bg;
    }

    /** 卡片内部的表面：预览条、色块、取色面板预览块。 */
    private Drawable innerSurface(int color) {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setColor(color);
        bg.setCornerRadius(dp(INNER_RADIUS_DP));
        return bg;
    }

    private Drawable circle(String hex) {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Config.parseColor(hex, 0xFF888888));
        int stroke = themeColor(android.R.attr.textColorSecondary);
        bg.setStroke(hairline(), (stroke & 0x00FFFFFF) | 0x33000000);
        return bg;
    }

    private View divider() {
        View v = new View(this);
        int base = themeColor(android.R.attr.textColorSecondary);
        v.setBackgroundColor((base & 0x00FFFFFF) | 0x1F000000);
        return v;
    }

    /** 主操作按钮：胶囊形，强调色填充，文字色按强调色亮度取反。 */
    private void stylePillButton(Button button) {
        int accent = themeColor(android.R.attr.colorAccent);
        int height = dp(PILL_HEIGHT_DP);

        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.RECTANGLE);
        shape.setColor(accent);
        shape.setCornerRadius(height / 2f);
        button.setBackground(new RippleDrawable(
                ColorStateList.valueOf(0x33FFFFFF), shape, null));

        button.setTextSize(16);
        button.setTextColor(luminance(accent) > 0.6 ? Color.BLACK : Color.WHITE);
        button.setPadding(dp(24), 0, dp(24), 0);
        button.setMinHeight(height);
        button.setMinimumHeight(height);
    }

    /** 次要操作：无底色的文字按钮。 */
    private void styleTextButton(Button button) {
        button.setTextSize(14);
        button.setTextColor(themeColorList(android.R.attr.colorAccent));
        button.setBackground(null);
        button.setPadding(dp(12), dp(8), dp(12), dp(8));
        button.setMinHeight(0);
        button.setMinimumHeight(0);
    }

    private int hairline() {
        return Math.max(1, Math.round(getResources().getDisplayMetrics().density / 2f));
    }

    private static double luminance(int c) {
        return (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255d;
    }

    private LinearLayout.LayoutParams margins(int width, int height,
                                              int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
        params.setMargins(left, top, right, bottom);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // ─────────────────────────────────────────────────────── 全面屏

    /**
     * 内容延伸到系统栏之后，应用底色自然铺满状态栏区域。
     *
     * <p>Insets 在首次 traversal 时到达、早于任何绘制，所以第一帧就是对的。
     * 少数 OEM 会在 decor 内部消费掉 insets，那样监听器收不到，用框架的
     * status_bar_height 兜底。
     */
    /**
     * 内容铺到系统栏之后，系统栏底色设为透明，于是状态栏那一条显示的就是
     * 应用自己的窗口底色 —— 与页面连成一片。
     *
     * <p>之前不一致的原因：targetSdk 34 不会被强制全面屏，系统栏仍按主题里
     * 的 statusBarColor 画一条不透明色带，而页面用的是 colorBackground，两者不同。
     */
    private void applyEdgeToEdge() {
        try {
            android.view.Window w = getWindow();
            w.setDecorFitsSystemWindows(false);        // minSdk 31，必然可用
            w.setStatusBarColor(Color.TRANSPARENT);    // targetSdk 34 下仍然有效
            w.setNavigationBarColor(Color.TRANSPARENT);
        } catch (Throwable ignored) {}
        mScrollView.setBackgroundColor(themeColor(android.R.attr.colorBackground));
    }

    private void applySystemBarInsets() {
        final int left = mContent.getPaddingLeft();
        final int top = mContent.getPaddingTop();
        final int right = mContent.getPaddingRight();
        final int bottom = mContent.getPaddingBottom();

        mScrollView.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override public WindowInsets onApplyWindowInsets(View view, WindowInsets insets) {
                Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                if (bars.left != 0 || bars.top != 0 || bars.right != 0 || bars.bottom != 0) {
                    mInsetsApplied = true;
                    mContent.setPadding(left + bars.left, top + bars.top,
                            right + bars.right, bottom + bars.bottom);
                }
                return insets;
            }
        });

        mScrollView.post(new Runnable() {
            @Override public void run() {
                if (mInsetsApplied) return;
                int[] location = new int[2];
                mScrollView.getLocationOnScreen(location);
                if (location[1] > 0) return;      // 没有顶到 0 说明系统已经避让过了
                int statusBar = systemBarHeight("status_bar_height", 28);
                if (statusBar > 0) mContent.setPadding(left, top + statusBar, right, bottom);
            }
        });
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
}
