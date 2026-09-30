#!/bin/bash
# 构建 StatColor LSPosed 模块 APK（aapt2 + javac + d8 + apksigner）
# 注意：任何一步失败立即退出，绝不允许"编译失败但仍产出签名 APK"。
set -e
set -o pipefail

ROOT=/root/work/lsp
PROJ=$ROOT/StatColor
OUT=$ROOT/build
AAPT2=/usr/lib/android-sdk/build-tools/debian/aapt2
ANDROID_JAR=$ROOT/sdk/platforms/android-34/android.jar
D8_JAR=$ROOT/sdk/build-tools/34.0.0/lib/d8.jar
KS=$ROOT/statcolor.keystore
KSPASS=statcolor
VC=$(grep -oP 'android:versionCode="\K[0-9]+' "$PROJ/AndroidManifest.xml")
VN=$(grep -oP 'android:versionName="\K[^"]+' "$PROJ/AndroidManifest.xml")
APK=$ROOT/StatColor-$VN.apk
echo "  目标: versionCode=$VC versionName=$VN -> $APK"

# 签名密钥不入库；首次构建时自动生成一个自签名密钥。
# 换密钥会导致签名变化，设备上必须先卸载旧版才能安装。
if [ ! -f "$KS" ]; then
    echo "=== 未找到签名密钥，自动生成 $KS ==="
    keytool -genkeypair -keystore "$KS" -alias statcolor \
        -storepass "$KSPASS" -keypass "$KSPASS" \
        -dname "CN=StatColor, OU=Dev, O=Dev, L=City, ST=State, C=CN" \
        -keyalg RSA -keysize 2048 -validity 10000
fi

rm -rf "$OUT"
mkdir -p "$OUT"/{res_compiled,classes,dex,apk,gen}

echo "=== [1/6] aapt2 compile 资源 ==="
"$AAPT2" compile --dir "$PROJ/res" -o "$OUT/res_compiled/res.zip"

echo "=== [2/6] aapt2 link 资源 ==="
"$AAPT2" link \
    -o "$OUT/apk/base.apk" \
    -I "$ANDROID_JAR" \
    --manifest "$PROJ/AndroidManifest.xml" \
    --java "$OUT/gen" \
    --min-sdk-version 24 \
    --target-sdk-version 34 \
    --version-code "$VC" --version-name "$VN" \
    "$OUT/res_compiled/res.zip"

test -f "$OUT/gen/com/statcolor/R.java" || { echo "FATAL: R.java 未生成"; exit 1; }

echo "=== [3/6] javac 编译 ==="
find "$PROJ/src" "$ROOT/stub" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
echo "  源文件数: $(wc -l < "$OUT/sources.txt")"
javac -source 8 -target 8 -encoding UTF-8 \
    -bootclasspath "$ANDROID_JAR" \
    -classpath "$ANDROID_JAR" \
    -d "$OUT/classes" \
    -nowarn \
    @"$OUT/sources.txt"
echo "  javac OK"

echo "=== [4/6] 打包 jar（工程类与 stub 分开）==="
# 关键：Xposed API 的 stub 只能用于编译期解析，绝不能进 dex，
# 否则会和 LSPosed 注入的真实 XposedBridge 冲突。
(cd "$OUT/classes" && jar cf "$OUT/classes.jar" com)
(cd "$OUT/classes" && jar cf "$OUT/stubs.jar" de)
echo "  classes.jar: $(unzip -l "$OUT/classes.jar" | tail -1)"
echo "  stubs.jar  : $(unzip -l "$OUT/stubs.jar" | tail -1)"

echo "=== [5/6] d8 转 dex ==="
java -cp "$D8_JAR" com.android.tools.r8.D8 \
    --min-api 24 \
    --lib "$ANDROID_JAR" \
    --classpath "$OUT/stubs.jar" \
    --output "$OUT/dex" \
    "$OUT/classes.jar"
test -f "$OUT/dex/classes.dex" || { echo "FATAL: classes.dex 未生成"; exit 1; }
echo "  dex 大小: $(stat -c%s "$OUT/dex/classes.dex") 字节"

echo "=== [6/6] 组装 + 签名 ==="
cp "$OUT/apk/base.apk" "$OUT/apk/final.apk"
(cd "$OUT/dex" && zip -q -j "$OUT/apk/final.apk" classes.dex)
(cd "$PROJ" && zip -q -r "$OUT/apk/final.apk" assets)
zipalign -f 4 "$OUT/apk/final.apk" "$OUT/apk/aligned.apk"
apksigner sign \
    --ks "$KS" --ks-pass "pass:$KSPASS" --key-pass "pass:$KSPASS" \
    --ks-key-alias statcolor \
    --out "$APK" \
    "$OUT/apk/aligned.apk"

echo
echo "=== 验证产物 ==="
apksigner verify --print-certs "$APK" | head -5
echo "--- APK 内容 ---"
unzip -l "$APK"
echo "--- 大小 ---"
ls -l "$APK"
