# StatColor · 状态栏着色

一个 LSPosed 模块，用来**统一修改 ColorOS / OxygenOS 状态栏上所有元素的颜色与透明度**（通知图标除外）。

针对 **OnePlus 13（PJZ110）/ ColorOS 16 / Android 16** 实测开发并验证，作用域 `com.android.systemui`。

---

## 效果

| 元素 | 是否受控 |
|---|---|
| 时钟、功耗/温度文字、网速文字 | ✅ |
| WiFi、移动信号（5G/4G）、VPN、NFC、蓝牙图标 | ✅ |
| 电量图标（圆形 / 横向 / 竖向三种样式） | ✅ |
| 下拉控制中心、锁屏、通知栏 | ❌ 一律不碰 |
| 左侧通知图标 | ❌ 不碰 |

电量图标另有一套更细的规则（见下文「电量图标的着色边界」）。

---

## 原理

这个模块最核心的设计是：**不去猜"谁在给这个元素上色"，而是直接判断"这块东西属于哪个窗口 / 哪棵视图树"**。前面若干个版本失败，全部是因为用了前者。

### 三条着色通道

系统栏元素的上色方式并不统一，实测有三条互不相干的路径：

| 通道 | 覆盖对象 | 判定边界 |
|---|---|---|
| `Paint#setColor`（绘制期） | 时钟、功耗、网速等**文字** | 只在状态栏窗口绘制期间生效，且**只改中性色**（R=G=B） |
| 状态栏视图树扫描 | WiFi、信号、VPN、NFC、蓝牙等**图标** | 从 `StatusBarWindowView` 出发遍历，只处理右半屏、排除 `Notification*` 子树 |
| `Drawable#setColor` / tint | 电量图标的各个部件 | 按 drawable 实例 + 充电状态判定 |

三条通道都遵守同一条安全规则：**饱和色（充电绿、低电量红）一律不改**，只改中性白/灰。否则会把系统状态色一起染掉。

### 为什么图标用「扫描」而不是拦截 setter

实测日志显示，WiFi/信号图标的 tint 是在**子线程**里设置的，而且那时 View 还没挂到窗口：

```
10237: 11706: 11706  [icon-tint] pre-attach ImageView #ccffffff -> #ff00c7ff
```

主线程是 5807，一次都没出现过。后果有两个，早期版本全栽在这里：

1. 设置时 `isAttachedToWindow()` 恒为 false，判断不了窗口归属；
2. `View.post()` 在子线程上只是把 Runnable 塞进那个线程的 RunQueue，**永远不会被执行** —— "延迟补色"和"挂载后复核"两版代码日志里一条都没打出来。

所以改成反过来做：**周期性（80ms）从状态栏窗口根视图遍历真实视图树**，把 ImageView 的 tint 改成目标色。边界天然正确，新增图标（VPN/NFC/蓝牙）不需要加白名单。

另外维护一个 `SB_ICON_VIEWS` 集合记录"已确认是状态栏图标"的 View —— 布局变化（网速文字变长把左边图标挤动）时框架会重新下发白色 tint，有了它就能**当帧**改掉，不会闪白。

### 电量图标的着色边界

反编译 `CircleBatteryContentDrawable#setColors(III)` 得到的真实语义：

```
p1 → mCircleFrontPaint      随电量变化（充电时系统会染成绿色 #ff24b232）
p2 → mCircleBackPaint       背景底环
p3 → mCircleChargingPaint + circleFramePaint(30%) + 闪电
```

据此定义的规则：

| 部分 | 未充电 | 充电 |
|---|---|---|
| 背景 / 边框 / 闪电 | 自定义 RGBA | 自定义 RGBA |
| 随电量变化的部分 | 自定义 RGBA | **只取 alpha**，RGB 用系统默认（绿） |

"随电量变化的那一份"不靠下标猜，而是靠颜色特征识别：充电时它是唯一被系统染成高饱和状态色的参数。这样圆形、横向、竖向三种样式自动都成立。

充电状态的判定有三层兜底（横向/竖向电量继承的是 `BatteryBarDrawableBase`，**没有** `getChargeIconId()`）：

1. 实例状态方法 `getChargeIconId()` / `getSpecialColor()`
2. 公开字段 `chargingBgIconId` / `chargeIconId`
3. 颜色特征：任一参数是高饱和状态色

**alpha 按各参数原有的相对值缩放**（`原alpha × 配置alpha / 255`），不能统一成配置的绝对值 —— 横向/竖向电量的电量信息就编码在 p1 的 alpha 里（实测 `#f0ffffff` → `#b5ffffff` 随电量变），拉平就会"图标始终填满"。

闪电有两条不同的实现，都要处理：

- 圆形：`chargingDrawable.setTintList(...)`，但 **tintMode 是 `SRC_ATOP`** —— 该模式输出 alpha 取目标 alpha，把透明度塞进 tint 颜色里在数学上无效，必须另外调 `Drawable#setAlpha`
- 横向/竖向：闪电是 **Bitmap**（字段 `chargingBitmap`），用 `chargePaint` 画出，没有 tint 可设 —— 给 `chargePaint` 挂 `PorterDuffColorFilter(SRC_IN)`

---

## 构建

### 依赖

- JDK（实测 17 / 21 均可）
- Android SDK：`platforms/android-34/android.jar`、`build-tools/34.0.0/lib/d8.jar`
- `aapt2`（本仓库使用 Debian 打包的原生 arm64 版本：`/usr/lib/android-sdk/build-tools/debian/aapt2`）
- `apksigner`、`zipalign`、`keytool`

### 命令

```bash
./build.sh
```

产物为 `StatColor-<versionName>.apk`（版本号从 `StatColor/AndroidManifest.xml` 读取，避免手改两处不一致）。

脚本是 `set -e` + `set -o pipefail`，并且会断言 `R.java`、`classes.dex` 存在 —— 早期版本出现过"javac 失败但仍产出签名 APK"的假成功，这个断言就是为它加的。

首次构建若没有 `statcolor.keystore` 会自动生成一个自签名密钥。**换密钥会导致签名变化，设备上必须先卸载旧版才能安装。**

### 构建流程

```
aapt2 compile → aapt2 link → javac → 分包打 jar → d8 → zipalign → apksigner
```

其中"分包打 jar"是关键：Xposed API 的 stub（`de.robv.android.xposed.*`）只能用于**编译期解析**，绝不能进 dex，否则会和 LSPosed 注入的真实 `XposedBridge` 冲突。所以 `com.*` 和 `de.*` 分开打包，stub 只通过 `d8 --classpath` 参与。

---

## 配置存储

模块界面写出的配置有三条读取路径，按顺序回退：

1. **文件镜像** `/data/local/tmp/statcolor.conf`（0644，由界面向 `su` 写入）
2. `XSharedPreferences`
3. `SharedPreferences`

第一条是主力。原因是 Android 7.0 起 `MODE_WORLD_READABLE` 会抛 `SecurityException`，模块进程读不到应用的私有 pref 文件。

注入侧对配置做了 1 秒 TTL 的缓存 —— 否则每次 `recolor()` 都要读一次文件，视图树扫描根本跑不起来。

---

## 目录结构

```
StatColor/
  src/com/statcolor/app/Hook.java        注入逻辑主体
  src/com/statcolor/app/Config.java      颜色解析 / 透明度 / pref 读写
  src/com/statcolor/app/Module.java      IXposedHookLoadPackage 入口
  src/com/statcolor/ui/MainActivity.java 设置界面
  res/                                   布局与资源（values / values-night 两套）
  assets/xposed_init                     模块入口类名
  AndroidManifest.xml                    作用域、版本、主题
stub/de/robv/android/xposed/             编译期 Xposed API 桩（不入 dex）
build.sh                                 一键构建
dexlist.py                               纯 Python 的 dex 类/方法列表工具
DumpSmali.java / dumpsmali.sh            从 dex 抽出指定类的 smali
FindRefs.java / findrefs.sh              扫描"谁引用了某个方法/字段"
```

---

## 逆向工具链

适配新 ROM / 新版本时，靠猜是没用的，需要直接读 SystemUI 的机器码。这套工具就是为此写的：

```bash
# 依赖
apt install libsmali-java

# 取出 SystemUI 的 dex
adb shell su -c 'cp /system_ext/priv-app/SystemUI/SystemUI.apk /sdcard/'
unzip -o /sdcard/SystemUI.apk 'classes*.dex' -d sysui/

# 列出某个类声明了哪些方法（纯 Python，无需 Java）
python3 dexlist.py "battery/ui/drawable/"

# 抽某个类的 smali
./dumpsmali.sh sysui/classes6.dex "battery/ui/drawable/CircleBatteryContentDrawable;"

# 谁调用了 Drawable#setTint
./findrefs.sh sysui/classes4.dex "drawable/Drawable;->setTint("
```

`FindRefs` 是这套工具里最有用的一个 —— WiFi/信号图标的上色路径就是靠它定位到
`WifiViewBinder$bind$1$1$3$1.emit()` → `$iconView.setImageTintList(...)` 的。

> 注意：`dexlist.py` 解析 `class_data_item` 时，**direct methods 与 virtual methods 的 `method_idx_diff` 各自从 0 累加**，不是连续的。不按这个规则解析会一路错位。

---

## 已知限制

- **只针对 ColorOS / OxygenOS 的 SystemUI 类名适配**。换 ROM 大概率要在 `Hook.java` 顶部的类名数组里补路径。
- 电量样式的字段名（`chargingDrawable`、`chargingBgIconId`、`chargePaint`）是反编译得到的，ROM 更新后可能变化，此时对应功能会静默失效（日志里会有 `no chargingDrawable field` 之类的提示）。
- 修改后需重启 SystemUI 生效（保存按钮会尝试自动重启）。若颜色误设为全透明导致看不清，在 LSPosed 里停用模块并重启 SystemUI 即可恢复。
- 应用界面**不渲染真实状态栏**：把真实状态栏画进别的 App 需要 `MediaProjection` 或 root 截图，都不适合设置界面。界面里是纯文本预览（当前系统时间），颜色与透明度与真机一致。

---

## 排错

模块日志在 `/data/adb/lspd/log/modules_*.log`，关键标记：

| 标记 | 含义 |
|---|---|
| `=== vX.Y enter com.android.systemui` | 模块已加载（**没有这行说明模块根本没跑，后面所有现象都不必分析**） |
| `[sweep] ...` / `[sweep-skip] ...` | 视图树扫描改色 / 因位置被跳过 |
| `[icon-tint] known-icon ...` | 快路径命中，框架重设 tint 被当帧拦下 |
| `[batt-draw] ... charging=true/false` | 电量图标的充电判定结果 |
| `[batt-bolt] bolt ...` | 闪电上色 / 透明度 |
| `tint err ...` | 某个钩子抛异常（早期 WiFi 全白就是这里的 `NoSuchMethodException`） |

配置是否正确写出可以用界面上的「自诊断」按钮检查。
