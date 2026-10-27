# cms-blueprints 构建脚本（无 Gradle：javac + jar）
# 关键点：本工程路径含中文，JDK 21 在 Windows 上按 ANSI 解码 javac/jar 的命令行与 @argfile，
#         所以所有传给它们的路径一律用「相对路径 + 当前目录切到工程根」。
# 用法：
#   .\build.ps1              构建并安装到实例 mods\
#   .\build.ps1 -NoInstall   只构建，不安装
#   .\build.ps1 -Smoke       构建后跑一次离线冒烟测试（不开游戏，直接撞站点）
param(
    [string]$McDir = "D:\mc\.minecraft",
    [string]$Instance = "1.21.1-NeoForge_21.1.265",
    [switch]$NoInstall,
    [switch]$Smoke,
    [switch]$CoverTest,
    [switch]$ConvertTest,
    [switch]$McsTest,
    [switch]$OrientCheck
)

$ErrorActionPreference = "Stop"
$proj = $PSScriptRoot
$versionDir = Join-Path $McDir "versions\$Instance"
$versionName = "0.1.0"
$jarName = "blueprintnet-$versionName.jar"

function Fail($msg) { Write-Host "构建失败：$msg" -ForegroundColor Red; exit 1 }

# ---------------------------------------------------------------- 1. 版本元数据
$jsonFile = Get-ChildItem $versionDir -Filter "*.json" | Select-Object -First 1
if (-not $jsonFile) { Fail "找不到版本 json：$versionDir" }
$v = Get-Content $jsonFile.FullName -Raw -Encoding UTF8 | ConvertFrom-Json
Write-Host "版本 json：$($jsonFile.Name)（$($v.libraries.Count) 个库）"

# ---------------------------------------------------------------- 2. 拼 classpath
$cp = New-Object System.Collections.Generic.List[string]
$natives = 0
$missing = 0
foreach ($l in $v.libraries) {
    $name = $l.name
    if (-not $name) { continue }
    if ($name -match ":natives-") { $natives++; continue }
    $parts = $name.Split(":")
    if ($parts.Count -lt 3) { continue }
    # Maven 布局：libraries\<group>\<artifact>\<version>\<artifact>-<version>[-<classifier>].jar
    $group = $parts[0] -replace "\.", "\"
    $art = $parts[1]
    $ver = $parts[2]
    $file = "$art-$ver"
    if ($parts.Count -ge 4) { $file = "$file-$($parts[3])" }
    $jar = Join-Path $McDir ("libraries\$group\$art\$ver\$file.jar")
    if (Test-Path $jar) { $cp.Add($jar) } else { $missing++ }
}

# MC 官方映射类（javac 需要它才有 Screen / GuiGraphics 等）
$srg = Get-ChildItem (Join-Path $McDir "libraries\net\minecraft\client") -Recurse -Filter "*-srg.jar" |
    Sort-Object Name -Descending | Select-Object -First 1
if (-not $srg) { Fail "找不到 client-*-srg.jar（MC 官方映射类）" }
$cp.Add($srg.FullName)

# Create（编译期需要 SchematicTableScreen / ClientSchematicLoader / CreatePaths）
$create = Get-ChildItem (Join-Path $versionDir "mods") -Filter "create-*.jar" |
    Where-Object { $_.Name -notmatch "cyber|goggles" } | Sort-Object Name -Descending | Select-Object -First 1
if (-not $create) { Fail "找不到 create-*.jar" }
$cp.Add($create.FullName)

# catnip（net.createmod.catnip.*，提供 AllGuiTextures/IconButton 的父类与 ScreenElement）
# 它没有独立 jar，藏在 create jar 的 META-INF/jarjar/ponder-*.jar 里，构建时解出来。
$ponderRel = "build/lib/ponder.jar"
$ponderOut = Join-Path $proj $ponderRel
New-Item -ItemType Directory -Path (Split-Path $ponderOut) -Force | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
$czip = [System.IO.Compression.ZipFile]::OpenRead($create.FullName)
try {
    $pe = $czip.Entries | Where-Object { $_.FullName -match "^META-INF/jarjar/ponder-.*\.jar$" } | Select-Object -First 1
    if (-not $pe) { Fail "create jar 里没有 META-INF/jarjar/ponder-*.jar（找不到 catnip）" }
    [System.IO.Compression.ZipFileExtensions]::ExtractToFile($pe, $ponderOut, $true)
} finally {
    $czip.Dispose()
}
Write-Host "  catnip ：从 $($create.Name) 解出 $(Split-Path $ponderOut -Leaf)（$([math]::Round((Get-Item $ponderOut).Length / 1KB, 1)) KB）"

# WebP 解码器（twelvemonkeys 纯 Java）：编译期进 classpath，打包期合并进 mod jar
$webpJars = Get-ChildItem (Join-Path $proj "lib\webp") -Filter *.jar -ErrorAction SilentlyContinue | Sort-Object Name
foreach ($w in $webpJars) { $cp.Add($w.FullName) }
if ($webpJars.Count -eq 0) { Write-Host "警告：lib\webp 里没有解码器 jar，站点封面图将无法显示" -ForegroundColor Yellow }

# net.neoforged.api.distmarker.Dist 在 mergetool-*-api.jar 里
$dist = Get-ChildItem (Join-Path $McDir "libraries\net\neoforged\mergetool") -Recurse -Filter "*-api.jar" -ErrorAction SilentlyContinue |
    Sort-Object Name -Descending | Select-Object -First 1
if ($dist) { $cp.Add($dist.FullName) } else { Write-Host "警告：没找到 mergetool-*-api.jar（可能缺 Dist 类）" -ForegroundColor Yellow }

# NeoForge 本体（ScreenEvent / SubscribeEvent / Mod 等 API 在 universal jar 里，不在版本 json 的 libraries 中）
$neoVer = $Instance.Split("_")[-1]
$uni = Join-Path $McDir "libraries\net\neoforged\neoforge\$neoVer\neoforge-$neoVer-universal.jar"
if (-not (Test-Path $uni)) {
    $uni = Get-ChildItem (Join-Path $McDir "libraries\net\neoforged\neoforge") -Recurse -Filter "*-universal.jar" |
        Where-Object { $_.Name -match "^neoforge-21\.1\." } | Sort-Object Name -Descending | Select-Object -First 1
    if (-not $uni) { Fail "找不到 neoforge-*-universal.jar" }
    $uni = $uni.FullName
}
$cp.Add($uni)

Write-Host "classpath：$($cp.Count) 个 jar（natives $natives 个跳过，缺 $missing 个）"
Write-Host "  MC 映射：$($srg.Name)"
Write-Host "  Create ：$($create.Name)"
if ($dist) { Write-Host "  Dist   ：$($dist.Name)" }
Write-Host "  NeoForge：$(Split-Path $uni -Leaf)"

# ---------------------------------------------------------------- 3. 编译（相对路径 + CWD=工程根）
Push-Location $proj
try {
    Remove-Item "build\classes" -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Path "build\classes" -Force | Out-Null

    $sources = Get-ChildItem "src\main\java" -Recurse -Filter *.java |
        ForEach-Object { $_.FullName.Substring($proj.Length + 1).Replace("\", "/") }
    if (-not $sources) { Fail "src\main\java 下没有 .java" }
    $sources | Set-Content "build\sources.txt" -Encoding ASCII

    Write-Host ""
    Write-Host "javac $(& javac -version 2>&1)，源文件 $($sources.Count) 个"
    $cpAll = ($cp -join ";") + ";" + $ponderRel
    & javac -encoding UTF-8 --release 21 -nowarn -proc:none -classpath $cpAll -d "build/classes" "@build/sources.txt"
    if ($LASTEXITCODE -ne 0) { Fail "javac 返回 $LASTEXITCODE" }

    if (Test-Path "src\main\resources") {
        Copy-Item "src\main\resources\*" "build\classes" -Recurse -Force
    }

    # 合并 WebP 解码器类（twelvemonkeys，BSD）进 mod jar；
    # imageio-webp 最后解压，保证它的 META-INF/services 不被别的 jar 覆盖
    foreach ($w in ($webpJars | Sort-Object Name -Descending)) {
        $tmpZip = Join-Path $env:TEMP ($w.BaseName + ".zip")
        Copy-Item $w.FullName $tmpZip -Force
        Expand-Archive $tmpZip "build\classes" -Force
        Remove-Item $tmpZip -Force
    }
    Write-Host "  已合并 WebP 解码器（$($webpJars.Count) 个 jar）"

    Remove-Item "build\$jarName" -Force -ErrorAction SilentlyContinue
    & jar --create --file "build/$jarName" -C "build/classes" .
    if ($LASTEXITCODE -ne 0) { Fail "jar 打包失败" }

    $classCount = (Get-ChildItem "build\classes" -Recurse -Filter *.class).Count
    $size = [math]::Round((Get-Item "build\$jarName").Length / 1KB, 1)
    Write-Host ""
    Write-Host "打包完成：build\$jarName（$classCount 个 class，$size KB）" -ForegroundColor Green

    # ------------------------------------------------------------ 3.5 离线冒烟（可选）
    if ($Smoke) {
        Write-Host ""
        Write-Host "编译并运行离线冒烟测试（不开游戏，直接请求站点）…" -ForegroundColor Cyan
        & javac -encoding UTF-8 --release 21 -nowarn -proc:none -classpath "build/classes;$cpAll" -d "build/smoke" "tools/SmokeTest.java"
        if ($LASTEXITCODE -ne 0) { Fail "冒烟测试编译失败" }
        $jcp = "build/classes;build/smoke;$cpAll"
        & java "-Dfile.encoding=UTF-8" "-Dstdout.encoding=UTF-8" -classpath $jcp SmokeTest
        if ($LASTEXITCODE -ne 0) { Fail "冒烟测试返回 $LASTEXITCODE" }
    }

    # ------------------------------------------------------------ 3.6 封面图解码测试（可选）
    if ($CoverTest) {
        Write-Host ""
        Write-Host "编译并运行封面图解码测试…" -ForegroundColor Cyan
        & javac -encoding UTF-8 --release 21 -nowarn -proc:none -classpath "build/classes;$cpAll" -d "build/smoke" "tools/CoverTest.java"
        if ($LASTEXITCODE -ne 0) { Fail "封面测试编译失败" }
        $jcp = "build/classes;build/smoke;$cpAll"
        & java "-Dfile.encoding=UTF-8" "-Dstdout.encoding=UTF-8" -classpath $jcp CoverTest
    }

    # ------------------------------------------------------------ 3.7 蓝图转换测试（可选）
    if ($ConvertTest) {
        Write-Host ""
        Write-Host "编译并运行蓝图转换/分割测试…" -ForegroundColor Cyan
        & javac -encoding UTF-8 --release 21 -nowarn -proc:none -classpath "build/classes;$cpAll" -d "build/smoke" "tools/ConvertTest.java"
        if ($LASTEXITCODE -ne 0) { Fail "转换测试编译失败" }
        $jcp = "build/classes;build/smoke;$cpAll"
        & java "-Dfile.encoding=UTF-8" "-Dstdout.encoding=UTF-8" -classpath $jcp ConvertTest
        if ($LASTEXITCODE -ne 0) { Fail "转换测试返回 $LASTEXITCODE" }
    }

    # ------------------------------------------------------------ 3.8 MCS 联网自测（可选）
    if ($McsTest) {
        Write-Host ""
        Write-Host "编译并运行 MCS 联网自测（搜索→详情→下载→转 nbt）…" -ForegroundColor Cyan
        & javac -encoding UTF-8 --release 21 -nowarn -proc:none -classpath "build/classes;$cpAll" -d "build/smoke" "tools/McsTest.java"
        if ($LASTEXITCODE -ne 0) { Fail "MCS 自测编译失败" }
        $jcp = "build/classes;build/smoke;$cpAll"
        & java "-Dfile.encoding=UTF-8" "-Dstdout.encoding=UTF-8" -classpath $jcp McsTest
        if ($LASTEXITCODE -ne 0) { Fail "MCS 自测返回 $LASTEXITCODE" }
    }
    # ------------------------------------------------------------ 3.9 朝向校验（可选）
    if ($OrientCheck) {
        Write-Host ""
        Write-Host "编译并运行朝向校验（转 nbt 后逐格比对 + 镜像对位）…" -ForegroundColor Cyan
        & javac -encoding UTF-8 --release 21 -nowarn -proc:none -classpath "build/classes;$cpAll" -d "build/smoke" "tools/OrientCheck.java"
        if ($LASTEXITCODE -ne 0) { Fail "朝向校验编译失败" }
        $jcp = "build/classes;build/smoke;$cpAll"
        & java "-Dfile.encoding=UTF-8" "-Dstdout.encoding=UTF-8" -classpath $jcp OrientCheck
        if ($LASTEXITCODE -ne 0) { Fail "朝向校验返回 $LASTEXITCODE" }
    }
} finally {
    Pop-Location
}

# ---------------------------------------------------------------- 4. 安装
if (-not $NoInstall) {
    $modsDir = Join-Path $versionDir "mods"
    if (-not (Test-Path $modsDir)) { New-Item -ItemType Directory -Path $modsDir -Force | Out-Null }
    Get-ChildItem $modsDir -File | Where-Object { $_.Name -match "^(cmsblueprints|blueprintnet)-.*\.jar$" } | Remove-Item -Force
    Copy-Item (Join-Path $proj "build\$jarName") $modsDir -Force
    Write-Host "已安装到：$modsDir" -ForegroundColor Green
}
