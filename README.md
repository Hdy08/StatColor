# StatColor · 状态栏着色

用于 ColorOS / OxygenOS 与 HyperOS 的 LSPosed 模块，统一设置状态栏文字、系统图标和电量的颜色与透明度。

当前版本：**12.1.1**。

## 功能与范围

- 深色、浅色背景分别配色，随系统的状态栏明暗判定切换，覆盖应用界面切换。
- HyperOS 的文字、图标、电池外框和填充跟随同一次系统颜色分发，保持同步。
- 提供颜色预设、ARGB 取色器和深浅背景预览。

| 范围 | 元素 |
|---|---|
| 参与着色 | 时钟、网速、功耗 / 温度文字、Wi-Fi、移动信号、VPN、NFC、蓝牙、电量与百分比 |
| 排除范围 | 通知图标、控制中心、通知面板、锁屏、AOD、灵动岛、充电气泡、隐私指示器 |

电池外框、闪电和普通填充使用配置色；充电绿、低电量红、省电黄等状态色保留系统 RGB，只调整透明度。ColorOS 电量保留各部件原有的透明度层次。

## 使用

需要 **Android 12 及以上、root 和 LSPosed**。具体兼容性取决于 ROM 的 SystemUI 实现。

1. 安装 StatColor APK，在 LSPosed 中启用模块。
2. 作用域勾选「系统界面」（`com.android.systemui`）。
3. 打开「状态栏着色」，授予 root 权限，打开「启用模块」并设置两组颜色。
4. 点击「保存并应用」。应用会同步配置并请求重启 SystemUI；若自动重启失败，按界面提示重启手机。

| 配色组 | 使用场景 | 默认颜色 |
|---|---|---|
| 深色背景 | 系统使用浅色状态栏元素时 | `#FFFFFFFF` |
| 浅色背景 | 系统使用深色状态栏元素时 | `#FF000000` |

颜色格式为 `#AARRGGBB`：`AA` 表示不透明度，`00` 为全透明，`FF` 为完全不透明。例如 `#80FFFFFF` 是约 50% 不透明度的白色。也可输入 `#RRGGBB`，或使用取色器调整各通道。

界面预览以黑、白底上的时间文字展示配色，实际效果以系统状态栏为准。

## 兼容性

| 系统 | 当前版本验证情况 |
|---|---|
| HyperOS 3 / Android 15 | MIX Fold 2（zizhan），OS3.0.8.0.VLRCNXM；已验证外屏、普通电量样式与充电填充同步 |
| ColorOS / OxygenOS | 已提供适配，当前版本未做实机回归 |

HyperOS 的内屏、分屏和空心电量样式尚未做实机回归。测试过程与逐帧结果见 [HyperOS 同步验证记录](docs/hyperos-sync-verification.md)。

## 实现概览

着色以状态栏窗口和视图树为边界，文字与图标在系统更新颜色时映射到配置色，视图树扫描补齐新挂载的元素。

- **HyperOS**：读取系统颜色分发器的明暗进度、作用区域和显示屏信息，统一计算各元素颜色。电量绘制前核对填充位图的颜色、尺寸和 Drawable；需要更新时重建，匹配时复用缓存。
- **ColorOS / OxygenOS**：适配系统电量 Drawable 的颜色、闪电与透明度，按各实例的原始颜色和状态选择配置。

主要代码：

| 文件 | 职责 |
|---|---|
| [Hook.java](StatColor/src/com/statcolor/app/Hook.java) | 窗口边界、文字 / 图标着色、视图树扫描与 ColorOS 电量适配 |
| [HyperOsTint.java](StatColor/src/com/statcolor/app/HyperOsTint.java) | HyperOS 颜色分发、区域判断与动画进度 |
| [HyperOsHooks.java](StatColor/src/com/statcolor/app/HyperOsHooks.java) | HyperOS 电量着色与位图同步 |
| [MainActivity.java](StatColor/src/com/statcolor/ui/MainActivity.java) | 设置、预览、配置同步与 SystemUI 重启 |

## 构建

使用 JDK 17+ 和 Android SDK（platforms、build-tools），无需 Gradle。Linux 还需要 Bash、GNU 工具和 `zip`。

### Windows

设置 `JAVA_HOME` 指向本机 JDK。SDK 默认读取 `ANDROID_SDK_ROOT`，未设置时使用 `C:\Android\Sdk`；脚本自动选择已安装的最高版本 platform 与 build-tools。

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-17'
pwsh -File .\build.ps1 -JavaHome $env:JAVA_HOME
```

也可通过 `-AndroidJar`、`-BuildTools`、`-JavaHome` 和 `-OutDir` 显式指定路径。

### Linux

按本机 SDK 安装位置调整以下路径：

```bash
export SDK_DIR=/path/to/android-sdk
export BUILD_TOOLS="$SDK_DIR/build-tools/37.0.0"
export PATH="$BUILD_TOOLS:$PATH"
AAPT2="$BUILD_TOOLS/aapt2" \
ANDROID_JAR="$SDK_DIR/platforms/android-37/android.jar" \
D8_JAR="$BUILD_TOOLS/lib/d8.jar" \
bash ./build.sh
```

版本号取自 [AndroidManifest.xml](StatColor/AndroidManifest.xml)，APK 输出到仓库根目录。首次构建会生成签名密钥 `statcolor.keystore`，密钥不入库。

## 排错

先查看应用内日志中的模块版本、ROM 与配置同步结果；LSPosed 模块日志位于 `/data/adb/lspd/log/modules_*.log`。

| 日志标记 | 检查内容 |
|---|---|
| `enter com.android.systemui rom=...` | 模块是否加载，ROM 是否识别正确 |
| `conf file` / `conf source` | 配置读取路径；HyperOS 优先使用 `/data/system/statcolor.conf` |
| `[hyperos-sync]` | HyperOS 的显示屏、明暗进度与作用区域 |
| `MISS` / `err` / `unavailable` | 适配接口是否缺失或异常 |

配置未生效时，确认 LSPosed 作用域与 root 授权，再保存并应用。ROM 更新可能改变 SystemUI 的类或字段，需要结合日志检查适配。若配色导致状态栏难以辨认，可在 LSPosed 中停用模块并重启 SystemUI。

## 许可

[GNU GPL v3](LICENSE)。
