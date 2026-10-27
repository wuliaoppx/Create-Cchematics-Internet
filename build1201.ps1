# 1.20.1 Forge 构建脚本（与 build.ps1 分开，避免两套 classpath 互相干扰）
# 用法： powershell -NoProfile -ExecutionPolicy Bypass -File .\build1201.ps1
#        powershell -NoProfile -ExecutionPolicy Bypass -File .\build1201.ps1 -NoInstall
param(
    [switch]$NoInstall,
    [switch]$CompileOnly
)

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$modsDir = "D:\mc\.minecraft\versions\1.20.1-Forge_47.4.28\mods"
$verJson = "D:\mc\.minecraft\versions\1.20.1-Forge_47.4.28\1.20.1-Forge_47.4.28.json"
$libsRoot = "D:\mc\.minecraft\libraries"
$m1201 = Join-Path $root "build\1201"
$srcDir = Join-Path $root "src1201\java"
$resDir = Join-Path $root "src1201\resources"
$classesDir = Join-Path $m1201 "classes"
$toolClasses = Join-Path $m1201 "tools-classes"
$jarName = "blueprintnet-1.20.1-0.1.0.jar"
$jarPath = Join-Path $m1201 $jarName

$javac = "javac"    # Zulu 21 / JDK 21 在 PATH 上，用 --release 17 产出 1.20.1 需要的字节码
$java = "java"

function Log($m) { Write-Host "[1201] $m" }

# ---------- 1. 组 classpath ----------
$cp = New-Object System.Collections.Generic.List[string]

# 1.1 版本 JSON 里的依赖库（跳过原版 client jar —— 用我们重映射过的可读名版本替代）
$json = Get-Content $verJson -Raw -Encoding UTF8 | ConvertFrom-Json
foreach ($lib in $json.libraries) {
    if ($null -eq $lib.downloads -or $null -eq $lib.downloads.artifact) { continue }
    $rel = $lib.downloads.artifact.path
    if ($rel -match 'com/mojang/minecraft-client') { continue }
    $full = Join-Path $libsRoot $rel
    if (Test-Path $full) { $cp.Add($full) }
}

# 1.2 重映射后的 MC 与 Forge（可读名）
$mcMojmap = Join-Path $m1201 "mc-1.20.1-mojmap.jar"
$forgeNamed = Join-Path $m1201 "forge-client-named.jar"
foreach ($p in @($mcMojmap, $forgeNamed)) {
    if (-not (Test-Path $p)) { throw "缺少 $p（先跑 tools1201 的映射生成）" }
    $cp.Add($p)
}

# 1.2b Forge 框架本体（这个整合包版本的 JSON 里没列，必须手动补，
#      否则 net.minecraftforge.fml.common / client.event 全部找不到）
$forgeVer = "1.20.1-47.4.28"
foreach ($a in @("forge/$forgeVer/forge-$forgeVer-universal.jar",
                 "fmlcore/$forgeVer/fmlcore-$forgeVer.jar",
                 "javafmllanguage/$forgeVer/javafmllanguage-$forgeVer.jar",
                 "mclanguage/$forgeVer/mclanguage-$forgeVer.jar",
                 "lowcodelanguage/$forgeVer/lowcodelanguage-$forgeVer.jar")) {
    $full = Join-Path $libsRoot ("net/minecraftforge/" + ($a -replace '/', '\'))
    if (-not (Test-Path $full)) { throw "缺少 Forge 框架 jar：$full" }
    $cp.Add($full)
}

# 1.3 目标模组
$createJar = Join-Path $modsDir "create-1.20.1-6.0.8.jar"
$gogglesJar = Join-Path $modsDir "CreateCyberGoggles-1.20.1-7.2.3-Forge.jar"
foreach ($p in @($createJar, $gogglesJar)) {
    if (-not (Test-Path $p)) { throw "缺少 $p" }
    $cp.Add($p)
}

# 1.4 内置 WebP 解码器（编译期需要 ImageIO API 已在 JDK 里，这里只是保持一致）
Get-ChildItem (Join-Path $root "lib\webp") -Filter *.jar | ForEach-Object { $cp.Add($_.FullName) }

# 1.5 catnip（1.20.1 里被 jarjar 进 create，编译期要单独一份解压出来的）
#     create jar 内 META-INF/jarjar/*.jar 里含 net/createmod/catnip/**
$catnipJar = Join-Path $m1201 "ponder-catnip.jar"
if (Test-Path $catnipJar) {
    $cp.Add($catnipJar)
} else {
    Log "警告：缺少 catnip 提取包 $catnipJar（SchematicPreviewPanel 会编译失败）"
}

$cpStr = ($cp -join ";")
Log ("classpath entries: " + $cp.Count)

# ---------- 2. 编译 ----------
if (Test-Path $classesDir) { Remove-Item $classesDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $classesDir | Out-Null
$sources = Get-ChildItem $srcDir -Recurse -Filter *.java | ForEach-Object { $_.FullName }
Log ("编译 " + $sources.Count + " 个源文件（--release 17）")

$argFile = Join-Path $m1201 "javac-args.txt"
$sb = New-Object System.Text.StringBuilder
[void]$sb.AppendLine("-encoding UTF-8")
[void]$sb.AppendLine("--release 17")
[void]$sb.AppendLine("-nowarn")
# javac 的 @argfile 会把反斜杠当转义符吃掉（C:Users...），统一换成正斜杠
[void]$sb.AppendLine("-classpath `"$($cpStr.Replace('\', '/'))`"")
[void]$sb.AppendLine("-d `"$($classesDir.Replace('\', '/'))`"")
foreach ($s in $sources) { [void]$sb.AppendLine('"' + $s.Replace('\', '/') + '"') }
[System.IO.File]::WriteAllText($argFile, $sb.ToString(), (New-Object System.Text.UTF8Encoding($false)))

& $javac "@$argFile"
if ($LASTEXITCODE -ne 0) { throw "javac 失败（exit $LASTEXITCODE）" }
$classCount = (Get-ChildItem $classesDir -Recurse -Filter *.class).Count
Log "编译通过：$classCount 个 class"

if ($CompileOnly) { Log "仅编译模式，结束"; return }

# ---------- 3. 重映射（可读名 → SRG 成员名，类名保持不变） ----------
$toSrg = Join-Path $m1201 "toSrg.tsrg"
if (-not (Test-Path $toSrg)) { throw "缺少 $toSrg" }
$aux = @($forgeNamed, $mcMojmap, $createJar, $gogglesJar, $catnipJar) -join ";"
$remapped = Join-Path $m1201 "remapped.jar"
if (Test-Path $remapped) { Remove-Item $remapped -Force }
Log "重映射（含继承链传播）"
# Reobf 用 ASM 实现，运行它自己也要 ASM 本体在 classpath 上
$asmJars = Get-ChildItem (Join-Path $libsRoot "org\ow2\asm") -Recurse -Filter *.jar |
    Where-Object { $_.FullName -match '9\.9\.1' } |
    ForEach-Object { $_.FullName }
if (-not $asmJars) { throw "找不到 ASM 9.9.1（Reobf 需要）" }
$reobfCp = (@($toolClasses) + $asmJars) -join ";"
& $java "-Dfile.encoding=UTF-8" "-cp" $reobfCp "Reobf" $toSrg $classesDir $aux $remapped
if ($LASTEXITCODE -ne 0) { throw "Reobf 失败（exit $LASTEXITCODE）" }

# ---------- 4. 打包 ----------
$stage = Join-Path $m1201 "stage"
if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
New-Item -ItemType Directory -Force -Path $stage | Out-Null

# 4.1 重映射后的类（Expand-Archive 不认 .jar；ExtractToDirectory 又不允许覆盖同名条目）
Add-Type -AssemblyName System.IO.Compression.FileSystem
function Extract-Jar($jar, $dest) {
    $zip = [System.IO.Compression.ZipFile]::OpenRead($jar)
    try {
        foreach ($e in $zip.Entries) {
            if ([string]::IsNullOrEmpty($e.Name)) { continue }
            $target = Join-Path $dest $e.FullName
            $dir = Split-Path $target -Parent
            if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
            [System.IO.Compression.ZipFileExtensions]::ExtractToFile($e, $target, $true)
        }
    } finally {
        $zip.Dispose()
    }
}
Extract-Jar $remapped $stage

# 4.2 资源（mods.toml 等）
Copy-Item (Join-Path $resDir "*") $stage -Recurse -Force

# 4.3 内置 WebP 解码器：按 Name 降序解压，保证 imageio-webp 的 services 最后写入
Get-ChildItem (Join-Path $root "lib\webp") -Filter *.jar |
    Sort-Object Name -Descending |
    ForEach-Object { Extract-Jar $_.FullName $stage }

# 4.4 去掉 jar 里不该有的签名/元信息
Get-ChildItem $stage -Recurse -Filter "*.SF" | Remove-Item -Force
Get-ChildItem $stage -Recurse -Filter "*.DSA" | Remove-Item -Force
Get-ChildItem $stage -Recurse -Filter "*.RSA" | Remove-Item -Force
if (Test-Path (Join-Path $stage "META-INF\maven")) { Remove-Item (Join-Path $stage "META-INF\maven") -Recurse -Force }

if (Test-Path $jarPath) { Remove-Item $jarPath -Force }
Add-Type -AssemblyName System.IO.Compression.FileSystem
# 手工写 zip：ZipFile.CreateFromDirectory 在 .NET Framework 上会把分隔符写成反斜杠，
# 生成的 jar 里路径变 dev\cmsbp\...，JVM 直接找不到类。
$zip = [System.IO.Compression.ZipFile]::Open($jarPath, [System.IO.Compression.ZipArchiveMode]::Create)
try {
    $stageTrim = $stage.TrimEnd('\').Length + 1
    Get-ChildItem $stage -Recurse -File | ForEach-Object {
        $rel = $_.FullName.Substring($stageTrim).Replace('\', '/')
        $entry = $zip.CreateEntry($rel, [System.IO.Compression.CompressionLevel]::Optimal)
        $es = $entry.Open()
        $fs = [System.IO.File]::OpenRead($_.FullName)
        try { $fs.CopyTo($es) } finally { $fs.Dispose(); $es.Dispose() }
    }
} finally {
    $zip.Dispose()
}

$size = "{0:N1} KB" -f ((Get-Item $jarPath).Length / 1KB)
$jarClasses = (& jar tf $jarPath | Select-String -Pattern '\.class$').Count
$hash = (Get-FileHash $jarPath -Algorithm SHA256).Hash
Log "产物 $jarName（$size，$jarClasses 个 class）SHA256=$hash"

# ---------- 5. 安装 ----------
if (-not $NoInstall) {
    if (-not (Test-Path $modsDir)) { throw "mods 目录不存在：$modsDir" }
    Get-ChildItem $modsDir -File | Where-Object { $_.Name -match "^(cmsblueprints|blueprintnet)-.*\.jar$" } | Remove-Item -Force
    Copy-Item $jarPath (Join-Path $modsDir $jarName) -Force
    $downloads = Join-Path $env:USERPROFILE "Downloads"
    if (Test-Path $downloads) { Copy-Item $jarPath (Join-Path $downloads $jarName) -Force }
    Log "已安装到 $modsDir"
    $installed = Get-FileHash (Join-Path $modsDir $jarName) -Algorithm SHA256
    Log ("实例内哈希 " + $installed.Hash + " 一致=" + ($installed.Hash -eq $hash))
}
