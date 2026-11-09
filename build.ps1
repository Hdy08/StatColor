<#
.SYNOPSIS
  构建 StatColor LSPosed 模块 APK（Windows 版；与 build.sh 等价）。

.DESCRIPTION
  流程：aapt2 compile → aapt2 link → javac → 分包打 jar → d8 → 组装 → zipalign → apksigner。
  任何一步失败立即抛错退出，绝不允许「编译失败但仍产出签名 APK」。

  工具来源（可用参数覆盖）：
    - Android SDK build-tools（aapt2 / d8 / zipalign / apksigner）
    - 任意 JDK 17+（javac / jar / keytool）

.EXAMPLE
  pwsh -File build.ps1
  pwsh -File build.ps1 -AndroidJar C:\Android\Sdk\platforms\android-36\android.jar
#>
param(
    [string]$AndroidJar  = "",
    [string]$BuildTools  = "",
    [string]$JavaHome    = "",
    [string]$OutDir      = "",
    [switch]$NoSign
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$Proj = $PSScriptRoot
$Module = Join-Path $Proj 'StatColor'
$Out  = if ($OutDir) { $OutDir } else { Join-Path $Proj 'build' }

function Fail([string]$msg) { Write-Host "FATAL: $msg" -ForegroundColor Red; exit 1 }

# ── 解析工具链 ────────────────────────────────────────────────────────────
$sdkRoot = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT }
           elseif ($env:ANDROID_HOME) { $env:ANDROID_HOME }
           else { 'C:\Android\Sdk' }

if (-not $BuildTools) {
    $btRoot = Join-Path $sdkRoot 'build-tools'
    if (-not (Test-Path $btRoot)) { Fail "找不到 build-tools：$btRoot（用 -BuildTools 指定）" }
    # 取版本号最大的一个
    $BuildTools = Get-ChildItem $btRoot -Directory |
        Sort-Object { [version]($_.Name -replace '[^0-9.].*$','') } -Descending |
        Select-Object -First 1 -ExpandProperty FullName
}
$aapt2     = Join-Path $BuildTools 'aapt2.exe'
$d8Jar     = Join-Path $BuildTools 'lib\d8.jar'
$zipalign  = Join-Path $BuildTools 'zipalign.exe'
$apksigner = Join-Path $BuildTools 'apksigner.bat'
foreach ($p in @($aapt2, $d8Jar, $zipalign, $apksigner)) {
    if (-not (Test-Path $p)) { Fail "缺少工具：$p" }
}

if (-not $JavaHome) {
    foreach ($cand in @('C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot',
                        'C:\Program Files\Eclipse Adoptium',
                        $env:JAVA_HOME)) {
        if (-not $cand) { continue }
        if (Test-Path (Join-Path $cand 'bin\javac.exe')) { $JavaHome = $cand; break }
        if (Test-Path $cand) {
            $jdk = Get-ChildItem $cand -Directory -ErrorAction SilentlyContinue |
                Where-Object { Test-Path (Join-Path $_.FullName 'bin\javac.exe') } |
                Select-Object -First 1
            if ($jdk) { $JavaHome = $jdk.FullName; break }
        }
    }
}
if (-not $JavaHome -or -not (Test-Path (Join-Path $JavaHome 'bin\javac.exe'))) {
    Fail "找不到 JDK（需要 bin\javac.exe）；用 -JavaHome 指定"
}
$javac    = Join-Path $JavaHome 'bin\javac.exe'
$jar      = Join-Path $JavaHome 'bin\jar.exe'
$keytool  = Join-Path $JavaHome 'bin\keytool.exe'
$java     = Join-Path $JavaHome 'bin\java.exe'

$platforms = Join-Path $sdkRoot 'platforms'
if (-not $AndroidJar) {
    $best = Get-ChildItem $platforms -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match '^android-(\d+)$' } |
        Sort-Object { [int]($_.Name -replace '^android-','') } -Descending |
        Select-Object -First 1
    if (-not $best) { Fail "找不到 platforms/android-*/android.jar（用 -AndroidJar 指定）" }
    $AndroidJar = Join-Path $best.FullName 'android.jar'
}
if (-not (Test-Path $AndroidJar)) { Fail "缺少 android.jar：$AndroidJar" }

# ── 读版本号与 SDK 区间（以 AndroidManifest.xml 为准）──────────────────────
$manifest = Join-Path $Module 'AndroidManifest.xml'
$mf = Get-Content $manifest -Raw
if ($mf -notmatch 'android:versionCode="(\d+)"') { Fail 'AndroidManifest.xml 缺 versionCode' }
$vc = $Matches[1]
if ($mf -notmatch 'android:versionName="([^"]+)"') { Fail 'AndroidManifest.xml 缺 versionName' }
$vn = $Matches[1]
$minSdk = if ($mf -match 'android:minSdkVersion="(\d+)"') { $Matches[1] } else { '24' }
$targetSdk = if ($mf -match 'android:targetSdkVersion="(\d+)"') { $Matches[1] } else { '34' }
$apk = Join-Path $Proj "StatColor-$vn.apk"
Write-Host "  目标: versionCode=$vc versionName=$vn -> $apk"
Write-Host "  SDK : min=$minSdk target=$targetSdk / $BuildTools"
Write-Host "  JDK : $JavaHome"
Write-Host "  Jar : $AndroidJar"

# ── 签名密钥（不入库；首次自动生成）────────────────────────────────────────
$ks = Join-Path $Proj 'statcolor.keystore'
$ksPass = 'statcolor'
if (-not $NoSign -and -not (Test-Path $ks)) {
    Write-Host '=== 未找到签名密钥，自动生成 statcolor.keystore ==='
    & $keytool -genkeypair -keystore $ks -alias statcolor `
        -storepass $ksPass -keypass $ksPass `
        -dname 'CN=StatColor, OU=Dev, O=Dev, L=City, ST=State, C=CN' `
        -keyalg RSA -keysize 2048 -validity 10000
    if ($LASTEXITCODE -ne 0) { Fail '生成签名密钥失败' }
}

# ── 工作目录 ──────────────────────────────────────────────────────────────
if (Test-Path $Out) { Remove-Item $Out -Recurse -Force }
foreach ($d in 'res_compiled','classes','dex','apk','gen') {
    New-Item -ItemType Directory -Force (Join-Path $Out $d) | Out-Null
}

# ── [1/6] aapt2 compile ───────────────────────────────────────────────────
Write-Host '=== [1/6] aapt2 compile 资源 ==='
& $aapt2 compile --dir (Join-Path $Module 'res') -o (Join-Path $Out 'res_compiled\res.zip')
if ($LASTEXITCODE -ne 0) { Fail 'aapt2 compile 失败' }

# ── [2/6] aapt2 link ──────────────────────────────────────────────────────
Write-Host '=== [2/6] aapt2 link 资源 ==='
& $aapt2 link `
    -o (Join-Path $Out 'apk\base.apk') `
    -I $AndroidJar `
    --manifest $manifest `
    --java (Join-Path $Out 'gen') `
    --min-sdk-version $minSdk `
    --target-sdk-version $targetSdk `
    --version-code $vc --version-name $vn `
    (Join-Path $Out 'res_compiled\res.zip')
if ($LASTEXITCODE -ne 0) { Fail 'aapt2 link 失败' }
if (-not (Test-Path (Join-Path $Out 'gen\com\statcolor\R.java'))) { Fail 'R.java 未生成' }

# ── [3/6] javac ───────────────────────────────────────────────────────────
Write-Host '=== [3/6] javac 编译 ==='
$sources = Get-ChildItem (Join-Path $Module 'src'), (Join-Path $Proj 'stub'), (Join-Path $Out 'gen') `
    -Recurse -Filter '*.java' | ForEach-Object { $_.FullName }
$srcList = Join-Path $Out 'sources.txt'
Set-Content -Path $srcList -Value $sources -Encoding utf8
Write-Host "  源文件数: $($sources.Count)"
& $javac -source 8 -target 8 -encoding UTF-8 `
    -bootclasspath $AndroidJar `
    -classpath $AndroidJar `
    -d (Join-Path $Out 'classes') `
    -nowarn `
    "@$srcList"
if ($LASTEXITCODE -ne 0) { Fail 'javac 失败' }
Write-Host '  javac OK'

# ── [4/6] 分包打 jar ──────────────────────────────────────────────────────
Write-Host '=== [4/6] 打包 jar（工程类与 stub 分开）==='
Push-Location (Join-Path $Out 'classes')
try {
    & $jar cf (Join-Path $Out 'classes.jar') com
    if ($LASTEXITCODE -ne 0) { Fail 'jar (classes) 失败' }
    & $jar cf (Join-Path $Out 'stubs.jar') de
    if ($LASTEXITCODE -ne 0) { Fail 'jar (stubs) 失败' }
} finally { Pop-Location }
Write-Host ("  classes.jar: {0:N0} 字节" -f (Get-Item (Join-Path $Out 'classes.jar')).Length)
Write-Host ("  stubs.jar  : {0:N0} 字节" -f (Get-Item (Join-Path $Out 'stubs.jar')).Length)

# ── [5/6] d8 ──────────────────────────────────────────────────────────────
Write-Host '=== [5/6] d8 转 dex ==='
& $java -cp $d8Jar com.android.tools.r8.D8 `
    --min-api $minSdk `
    --lib $AndroidJar `
    --classpath (Join-Path $Out 'stubs.jar') `
    --output (Join-Path $Out 'dex') `
    (Join-Path $Out 'classes.jar')
if ($LASTEXITCODE -ne 0) { Fail 'd8 失败' }
$dexFile = Join-Path $Out 'dex\classes.dex'
if (-not (Test-Path $dexFile)) { Fail 'classes.dex 未生成' }
Write-Host ("  dex 大小: {0:N0} 字节" -f (Get-Item $dexFile).Length)

# ── [6/6] 组装 + 签名 ─────────────────────────────────────────────────────
Write-Host '=== [6/6] 组装 + 签名 ==='
$final = Join-Path $Out 'apk\final.apk'
Copy-Item (Join-Path $Out 'apk\base.apk') $final -Force
Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open($final, [System.IO.Compression.ZipArchiveMode]::Update)
try {
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
        $zip, $dexFile, 'classes.dex', [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
    Get-ChildItem (Join-Path $Module 'assets') -Recurse -File | ForEach-Object {
        $rel = 'assets/' + $_.FullName.Substring((Join-Path $Module 'assets').Length + 1).Replace('\','/')
        if ($zip.GetEntry($rel)) { $zip.GetEntry($rel).Delete() }
        [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
            $zip, $_.FullName, $rel, [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
    }
} finally { $zip.Dispose() }

$aligned = Join-Path $Out 'apk\aligned.apk'
& $zipalign -f 4 $final $aligned
if ($LASTEXITCODE -ne 0) { Fail 'zipalign 失败' }

if ($NoSign) {
    Copy-Item $aligned $apk -Force
} else {
    & $apksigner sign --ks $ks --ks-pass "pass:$ksPass" --key-pass "pass:$ksPass" `
        --ks-key-alias statcolor --out $apk $aligned
    if ($LASTEXITCODE -ne 0) { Fail 'apksigner 失败' }
    Write-Host '=== 验证产物 ==='
    & $apksigner verify --print-certs $apk | Select-Object -First 3
}

Write-Host "--- 产物: $apk ($("{0:N0}" -f (Get-Item $apk).Length) 字节) ---"
