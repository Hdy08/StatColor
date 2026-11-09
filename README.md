# StatColor · 状态栏着色

一个 LSPosed 模块，用来**统一修改 ColorOS / OxygenOS 与 HyperOS 状态栏上所有元素的颜色与透明度**（通知图标除外）。

v11.5.2 = 上游 v11.4.3（ColorOS 通道）+ HyperOS 适配：注入时先判断当前设备跑的是哪套 SystemUI，
再选择对应的着色通道 —— 同一份 APK 在两类 ROM 上都能用。

> v11.5.2 修复：两类 ROM 的电量图标在「浅色状态栏」下都会误用「深色背景」那组配置色 ——
> HyperOS 是未充电时的电池外框，ColorOS 是**充电时**的闪电（详见下文「颜色组：跟随状态栏明暗」）。

实测环境：

| ROM | 机型 | 版本 |
|---|---|---|
| ColorOS 16 / Android 16 | OnePlus 13（PJZ110） | 上游 v1.3 ~ v11.4.3；v11.5.2 充电闪电验证 |
| HyperOS 3 / Android 15 | Xiaomi MIX Fold 2（zizhan） | v11.5.2 开发验证（OS3.0.8.0.VLRCNXM） |

作用域固定为 `com.android.systemui`（两类 ROM 的 SystemUI 都是这个包名）。

---

## 效果

| 元素 | 是否受控 |
|---|---|
| 时钟、功耗/温度文字、网速文字 | ✅ |
| WiFi、移动信号（5G/4G）、VPN、NFC、蓝牙图标 | ✅ |
| 电量图标（充电 / 低电量 / 省电 / 高性能 / 百分比样式） | ✅ |
| 下拉控制中心、锁屏、通知栏、AOD | ❌ 一律不碰 |
| 左侧通知图标 | ❌ 不碰 |
| HyperOS 灵动岛、充电气泡 | ❌ 不碰（独立窗口 / 按类名排除） |

电量图标另有一套更细的规则（见下文「电量图标的着色边界」）。

---

## 设备自适应（v11.5 新增）

`Rom.java` 负责判断当前 ROM，判定顺序是：

1. **类探测**（仅注入进程，最可靠）：SystemUI 的 ClassLoader 里能不能找到特征类
   - `com.android.systemui.statusbar.views.MiuiBatteryMeterView` → HyperOS / MIUI
   - `com.oplus.systemui.statusbar.pipeline.battery.ui.drawable.BatteryContentDrawableBase` → ColorOS / OxygenOS
2. **系统属性**：`ro.mi.os.version.incremental` / `ro.miui.ui.version.name`；`ro.build.version.oplusrom` / `ro.build.version.opporom`
3. **Build 字段兜底**：厂商名（Xiaomi/Redmi/POCO ↔ OPPO/OnePlus/realme），只用于模块界面展示

判定结果写进日志，一眼就能确认模块走的是哪条通道：

```
StatColor: === v11.5.2 enter com.android.systemui rom=HyperOS(OS3.0.8.0.VLRCNXM) display=AQ3A.250226.002
StatColor: hyperos: com.android.systemui.statusbar.views.MiuiBatteryMeterView hooks=2
```

> 为什么不用版本号判定：ColorOS/OxygenOS 共用 `com.oplus.*`，HyperOS/MIUI 共用
> `com.android.systemui.*`，而版本号字符串在不同 OEM 之间没有任何统一格式。
> 真正决定「该挂哪些钩子」的只有「目标类在不在这个进程里」。

两套通道的差异只集中在 ROM 私有实现上，公共逻辑（按窗口划界、文字、透明度、配置）完全共用：

| 环节 | ColorOS / OxygenOS | HyperOS |
|---|---|---|
| 状态栏窗口根视图 | `*StatusBarWindow*` | `statusbar.window.StatusBarWindowView` |
| 文字颜色 | `Paint#setColor`（绘制期）+ `setTextColor` 源头拦截 | 同左 |
| WiFi/信号图标 | `ImageView`/`Drawable` tint + 视图树扫描 | `ImageView` tint + 视图树扫描 |
| 电量图标 | `com.oplus...drawable.*#setColors(III)` + `draw(Canvas)` | `MiuiBatteryMeterIconView#onDarkChangeInternal` 后改写 tint |
| 通知图标区 | 类名含 `Notification` 的子树 | 按资源 id 精确排除（**不能按类名**，见下） |

---

## 原理

这个模块最核心的设计是：**不去猜"谁在给这个元素上色"，而是直接判断"这块东西属于哪个窗口 / 哪棵视图树"**。前面若干个版本失败，全部是因为用了前者。

### 三条着色通道

系统栏元素的上色方式并不统一，实测有三条互不相干的路径：

| 通道 | 覆盖对象 | 判定边界 |
|---|---|---|
| `Paint#setColor`（绘制期） | 时钟、功耗、网速等**文字** | 只在状态栏窗口绘制期间生效，且**只改中性色**（R=G=B） |
| 状态栏视图树扫描 | WiFi、信号、VPN、NFC、蓝牙、文字等 | 从 `StatusBarWindowView` 出发遍历，排除通知图标区 |
| `Drawable#setColor` / tint | 电量图标的各个部件 | 按 drawable 实例 + 充电状态判定 |

三条通道都遵守同一条安全规则：**饱和色（充电绿、低电量红）不换色相**。v11.4 起改为只缩 alpha
（`tintArg()` 统一处理），否则用户把透明度调低时，低电量红/充电绿那几块会停在系统 alpha 上。

### 为什么图标用「扫描」而不是拦截 setter

实测日志显示，WiFi/信号图标的 tint 是在**子线程**里设置的，而且那时 View 还没挂到窗口：

```
10237: 11706: 11706  [icon-tint] pre-attach ImageView #ccffffff -> #ff00c7ff
```

主线程是 5807，一次都没出现过。后果有两个，早期版本全栽在这里：

1. 设置时 `isAttachedToWindow()` 恒为 false，判断不了窗口归属；
2. `View.post()` 在子线程上只是把 Runnable 塞进那个线程的 RunQueue，**永远不会被执行** —— "延迟补色"和"挂载后复核"两版代码日志里一条都没打出来。

所以改成反过来做：**周期性（80ms）从状态栏窗口根视图遍历真实视图树**，把 ImageView 的 tint / TextView 的颜色改成目标色。边界天然正确，新增图标（VPN/NFC/蓝牙）不需要加白名单。

另外维护一个 `SB_ICON_VIEWS` 集合记录"已确认是状态栏图标"的 View —— 布局变化（网速文字变长把左边图标挤动）时框架会重新下发白色 tint，有了它就能**当帧**改掉，不会闪白。

### 文字：为什么必须拦 `setTextColor`（v11.5 修复闪烁）

HyperOS 的状态栏文字（时钟、功耗/温度、网速）并不是每帧重设 Paint，而是在
`onDarkChanged` 之类的时机统一 `setTextColor(系统色)`。视图树扫描最快也要等
下一次 80ms 窗口才能把颜色刷回来 —— 表现就是**「着色 ↔ 未着色」之间来回闪**，
实测「功耗/温度指示器」每秒更新一次数值时最明显。

v11.5 直接在源头解决：挂 `TextView#setTextColor(int)` 与 `setTextColor(ColorStateList)`，
只对「已确认属于状态栏」的 TextView 改写入参（扫描登记过的，或当前挂在
`StatusBarWindowView` 下的）。颜色根本没有机会变回原色，闪烁随之消失；
同一套判据也顺带覆盖了浅色背景下的深色文字。

`setTextColor` 钩子里还带一个 `[flicker-guard]` 计数：每当系统想把已经是自定义色的
文字打回中性色时 +1，用于确认这条路径是否仍在工作。

### 通知图标区：HyperOS 的坑（v11.5）

ColorOS 上「类名含 `Notification` 的子树 = 通知图标」这条规则在 HyperOS 上会**误杀整个系统图标区**：

```
MiuiNotificationStatusContainer   ← 名字带 Notification，装的却是 system_icon_area
  ├─ AlphaOptimizedFrameLayout    ← fullscreen_notification_icon_area（真正的通知图标）
  ├─ StatusBarUserSwitcherContainer
  └─ MiuiStatusBatteryContainer   ← 系统图标 + 电量
```

按类名排除会把 WiFi/信号/电量一起跳过（v10.3 在 HyperOS 上一条 `[sweep]` 都没有，就是这个原因）。

v11.5 改为按**资源 id** 精确排除（运行期 `getIdentifier` 解析，不硬编码数字）：

- `R.id.notification_icon_area`
- `R.id.fullscreen_notification_icon_area`

再加上 `FocusedNotifPrompt*`（左侧通知提示）与 `*Island*`（灵动岛）两个类名排除，
其余状态栏元素全部参与着色。

### 电量图标的着色边界

#### ColorOS：`setColors` 的真实语义

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

#### HyperOS：自绘控件 + Drawable tint

HyperOS 3 的电量图标与 ColorOS 完全无关：

```
MiuiBatteryMeterView (LinearLayout)
 ├─ mBatteryChargingView (ImageView)      ← 充电闪电
 ├─ mBatteryPercentView / MarkView        ← 百分比文字
 └─ MiuiBatteryMeterIconView (自绘 ImageView)
      ├─ onDarkChangeInternal()           ← 颜色的唯一计算点
      │    mTintBgDrawable.setTintList(...)        // 外框 + 闪电
      │    mProgressClipDrawable.setTintList(...)  // 电量填充
      │    updateProgressBackgroundBitmap()        // 把两者烘焙成位图
      └─ onDraw(Canvas)                   ← 只画烘焙好的位图
```

所以适配点不是「拦截绘制参数」，而是「在它算完颜色之后改写结果」：

1. `after MiuiBatteryMeterIconView#onDarkChangeInternal()` → 给两个 Drawable 重设 tint，**再调一次 `updateProgressBackgroundBitmap()` 重建位图**（否则要等下次布局才生效）；
2. `after MiuiBatteryMeterView#onDarkChanged(...)` / `onDarkChangedInternal()` → 改闪电与百分比文字；
3. 视图树扫描兜底：挂窗口较晚、或系统在别处重新下发颜色时，80ms 内纠正。

规则与 ColorOS 保持一致：

| 部分 | 未充电 | 充电 / 低电量 / 省电 / 高性能 |
|---|---|---|
| 外框 + 闪电（`mTintBgDrawable`） | 自定义 RGBA | 自定义 RGBA |
| 电量填充（`mProgressClipDrawable`） | 自定义 RGBA | **保留系统色相**（绿/红/黄），只按配置 alpha 缩放 |

空心电量样式（`MiuiHollowBatteryMeterIconView`）走同样的规则：改写
`firstBackground` / `secondBackground` / `thirdBackground` 的 tint 与 `textPaint` 颜色，
再调 `updateFirstBitmap()` / `updateSecondBitmap()` 重建。

#### 颜色组：跟随状态栏明暗（v11.5.2 修复）

配置里有两组颜色：`color_dark`（深色背景 → 浅色图标）与 `color_light`（浅色背景 → 深色图标）。
选哪一组由 `recolor()` 按**输入色的亮度**决定 —— 文字、WiFi、信号拿到的都是系统下发的
真实前景色（深色背景下发白，浅色背景下发黑），所以天然选对。

但两类 ROM 的电量图标都要额外给一个"种子"（我们拿不到它的前景色）：

| 通道 | 错误（v11.5.1 及以前） | 现在 |
|---|---|---|
| HyperOS | `recolor(0xFFFFFFFF)` 写死白色 → 永远是「深色背景」组 | 取 `MiuiBatteryMeterView#onDarkChanged(...)` 第 3 个参数（系统算好的图标前景色：深色背景 `#E6FFFFFF` / 浅色背景 `#BF000000`） |
| ColorOS | 闪电 `tintChargeBolt()` 同样写死白色 → **充电时浅色状态栏的闪电变成深色组的颜色** | 用同一支 drawable 的系统原始色推断（底环 / 边框仍是中性色：白系 = 深色背景，黑系 = 浅色背景） |

极性记在 `Hook.sIconPolarity`，`barSeedShared()` 返回白/黑种子；两者都拿不到时退回
系统深色模式设置，再不行按「深色背景」处理（与旧行为一致）。

两个坑，都是实测踩出来的：

- **不能拿状态栏文字反推**：MIUI 会把锁屏 / 被遮挡的时钟一起刷，两个实例颜色相反时
  极性会 1Hz 来回跳；`NetworkSpeedView` 更是每秒用 `#CCFFFFFF` 和 `#BF000000` 交替设色。
  表现就是电池在两组颜色之间闪（v11.5.1 调试实测）。
- **只能认状态栏那个实例**：锁屏 / AOD 用的是同名电量控件的另一个实例，
  `onDarkChanged` 的参数可能整好相反，必须先用 `inStatusBarWindow()` 判窗口。

极性每次变化都会打一行日志：

```
StatColor: [bar] #ff000000 from onDarkChanged 深色图标（浅色背景）→ 用「浅色背景」组
StatColor: [bar] #ffffffff from batt-orig 浅色图标（深色背景）→ 用「深色背景」组
```

---

## 构建

### 依赖

- JDK（实测 17 / 21 均可）
- Android SDK：`platforms/android-3x/android.jar`、`build-tools/<ver>/`（`aapt2`、`d8`、`zipalign`、`apksigner`）
- Linux 下额外需要 `zip`

### Linux / macOS（build.sh）

工具链不在仓库里，路径通过环境变量覆盖，与 NativePowerMenu 的做法一致：

```bash
SDK_DIR=/path/to/sdk ./build.sh          # 一次性指定 SDK 根目录
ANDROID_JAR=... D8_JAR=... AAPT2=... ./build.sh   # 或逐个覆盖
```

脚本用自身所在目录作为仓库根，**换位置不需要改脚本**。依赖缺失时直接报
`FATAL: 缺少构建依赖 …` 并退出，不会产出一个半成品 APK。

### Windows（build.ps1）

```powershell
pwsh -File .\build.ps1
```

自动探测 `%ANDROID_SDK_ROOT%`（或 `C:\Android\Sdk`）下版本最高的 build-tools 与
platform，以及 `C:\Program Files\Eclipse Adoptium` 下的 JDK。也可显式指定：

```powershell
pwsh -File .\build.ps1 -AndroidJar C:\Android\Sdk\platforms\android-35\android.jar `
                       -BuildTools C:\Android\Sdk\build-tools\36.0.0 `
                       -JavaHome   'C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot'
```

minSdk / targetSdk 同样从 `AndroidManifest.xml` 读取（v11.4 起 minSdk 提到 **31**，
`Theme.DeviceDefault.DayNight` 需要它）。

### 在手机存储上使用

仓库放在 `/sdcard/Code/StatColor`（与 NativePowerMenu 同目录）时有两处与普通文件系统不同，
本仓库已经处理好：

- `sdcardfs` 不保存 Unix 权限位，`./dumpsmali.sh` 这类脚本的可执行位会丢失。
  仓库内已设 `core.fileMode=false`，git 不再把权限变化报成改动；
  脚本用 `bash dumpsmali.sh` 调用即可。
- 文件属主是手机用户（非容器内 root），git 会报 *dubious ownership*。需要：

  ```bash
  git config --global --add safe.directory /sdcard/Code/StatColor
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

1. **文件镜像**（主力，需要 root 写）
   - `/data/local/tmp/statcolor.conf` —— ColorOS / OxygenOS 主路径
   - `/data/system/statcolor.conf` —— **HyperOS 主路径**
2. `XSharedPreferences`
3. `SharedPreferences`

### 为什么 HyperOS 必须换位置

SystemUI 跑在 `system_app` 域，而 `/data/local/tmp` 属于 `shell_data_file`。
实测 HyperOS 3 上 `system_app` 连目录 `search` 都被拒绝：

```
avc: denied { search } for comm="ndroid.systemui" name="tmp" ...
  scontext=u:r:system_app:s0 tcontext=u:object_r:shell_data_file:s0 tclass=dir
```

ColorOS 的策略放过了这条路径，所以旧路径保留。`/data/system` 属于
`system_data_file`，`system_app` 可读，因此 HyperOS 改用它。

界面保存时会**两条路径都写**（都失败才报错）；注入侧按 ROM 决定尝试顺序，
并记住上次成功的那条，避免每秒都去碰被拒绝的路径。注入侧对配置做了
1 秒 TTL 的缓存 —— 否则每次 `recolor()` 都要读一次文件，视图树扫描根本跑不起来。

> 写配置需要 root：请在 KernelSU / Magisk 里给「状态栏着色」授予超级用户权限。
> 未授权时界面日志会明确提示「配置同步失败」，此时颜色不会生效。

---

## 目录结构

```
StatColor/
  src/com/statcolor/app/Hook.java        注入逻辑主体：窗口划界 / 扫描 / 文字 / Paint / 配置
  src/com/statcolor/app/HyperOsHooks.java HyperOS 适配层：MiuiBatteryMeter* 电量
  src/com/statcolor/app/Rom.java         ROM 识别（类探测 → 属性 → Build）
  src/com/statcolor/app/Config.java      颜色解析 / 透明度 / pref 读写
  src/com/statcolor/app/Module.java      IXposedHookLoadPackage 入口
  src/com/statcolor/ui/MainActivity.java 设置界面（纯 Java 构建，无 XML 布局）
  res/values/strings.xml                 界面文案
  res/drawable/ic_edit.xml               色块上的铅笔图标
  assets/xposed_init                     模块入口类名
  AndroidManifest.xml                    作用域、版本、主题
stub/de/robv/android/xposed/             编译期 Xposed API 桩（不入 dex）
build.sh                                 一键构建（Linux / macOS）
build.ps1                                一键构建（Windows，自动探测 SDK/JDK）
dexlist.py                               纯 Python 的 dex 类/方法列表工具
DumpSmali.java / dumpsmali.sh            从 dex 抽出指定类的 smali
FindRefs.java / findrefs.sh              扫描"谁引用了某个方法/字段"
```

---

## 界面

设置界面**没有任何 XML 布局**，整屏在 `MainActivity` 里用框架控件搭出来，挂在
`@android:style/Theme.DeviceDefault.DayNight` 上。这是与参考项目 **NativePowerMenu** 统一的 AOSP 设计语言：

- 颜色一律从**框架主题属性**取，不硬编码、不打包 Material Components：
  `colorBackgroundFloating`（卡片底）、`textColorPrimary` / `textColorSecondary`（文字）、`colorAccent`（强调色）
- 卡片 18dp 圆角 + 半像素描边；行间 1px 分隔线（`textColorSecondary` at 0x1F）
- 小标题 13sp、加粗、全大写、`letterSpacing 0.06`、强调色
- 主按钮胶囊形（半径 = 高度/2，高 52dp），文字色按强调色亮度取反；次要操作是无底色文字按钮
- 深浅色完全跟随系统，不需要 `values-night`
- 标题栏在 `onCreate` 里显式隐藏（主题是 DayNight 而非 NoActionBar 变体）

因此 minSdk 提到 **31**（`Theme.DeviceDefault.DayNight` 自 API 29 起才存在），targetSdk 34。

v11.5 起，日志区首行会显示 `模块版本 11.5.2 · 当前系统 HyperOS(OS3.0.8.0.VLRCNXM)`，
保存后会把**两条配置路径**的写入结果与内容都打到日志里。

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

Windows 下没有 smali/dexlib 时，可以只用 `build-tools` 里的 `aapt2 dump xmltree/resources`
看布局与资源名，用 `dexdump -d` 反汇编，再配合任意 dex→java 工具（如 jadx）读逻辑。

---

## 已知限制

- 本版是**上游 v11.4.3 + HyperOS 适配**的合并结果：ColorOS 通道保持 v11.4.3 的实现，
  但本次合并没有 ColorOS 设备做回归；如遇异常，先看日志里 `rom=ColorOS` 与
  `[sweep]` / `[text-color]` 标记。
- 电量样式的字段名（ColorOS：`chargingDrawable`、`chargingBgIconId`、`chargePaint`；
  HyperOS：`mTintBgDrawable`、`mProgressClipDrawable`、`chargingBgIconId` 等）来自反编译，
  ROM 更新后可能变化，此时对应功能会静默失效（日志里有 `MISS` / `no chargingDrawable field` 提示）。
- HyperOS 的充电气泡、灵动岛、隐私指示器不在着色范围（按窗口/类名排除）。
- 修改后需重启 SystemUI 生效（保存按钮会尝试自动重启）。若颜色误设为全透明导致看不清，
  在 LSPosed 里停用模块并重启 SystemUI 即可恢复。
- 应用界面**不渲染真实状态栏**：把真实状态栏画进别的 App 需要 `MediaProjection` 或 root 截图，
  都不适合设置界面。界面里是纯文本预览（当前系统时间），颜色与透明度与真机一致。
- 改配置后颜色通常要在下一次 SystemUI 重启后完全统一；视图树扫描/文字钩子会在运行中
  逐步把旧颜色改成新颜色，但已下发的图标 tint 不保证立即全部刷新。

---

## 排错

模块日志在 `/data/adb/lspd/log/modules_*.log`，关键标记：

| 标记 | 含义 |
|---|---|
| `=== v11.5.2 enter com.android.systemui rom=...` | 模块已加载并识别到 ROM（**没有这行说明模块根本没跑，后面所有现象都不必分析**） |
| `hyperos: ... hooks=N` | HyperOS 电量相关钩子挂载数量（`MISS` 表示该类不存在，ROM 可能变了） |
| `conf file = ...` / `conf source = ...` | 配置实际用的哪条路径（HyperOS 应为 `/data/system/statcolor.conf`） |
| `[sweep] ...` / `[sweep-skip] ...` / `[sweep-text] ...` | 视图树扫描改色 / 因位置被跳过 / 文字改色 |
| `[icon-tint] known-icon ...` | 快路径命中，框架重设 tint 被当帧拦下 |
| `[text-color] ...` / `[flicker-guard] #N ...` | 文字颜色在源头被改写 / 系统试图把已改色文字打回原色（防闪烁计数） |
| `[hyperos-batt] ... charging=... low=...` | HyperOS 电量图标的判定与目标色（`seed=` 为选组依据） |
| `[bar] #... from ...` | 状态栏明暗极性变化，决定电量用「深色背景」还是「浅色背景」那组颜色 |
| `[hyperos-hollow] ...` | 空心电量样式的处理 |
| `[batt-draw] ... charging=true/false` | ColorOS 电量图标的充电判定结果 |
| `[batt-bolt] bolt ...` | ColorOS 闪电上色 / 透明度 |
| `tint err ...` | 某个钩子抛异常（早期 WiFi 全白就是这里的 `NoSuchMethodException`） |

保存配置后，界面日志会输出两条路径的写入结果与实际内容，可直接核对。
