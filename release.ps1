# 推送仓库并发布 GitHub Release（v0.1.0）
#
# 用法（在本目录下执行 PowerShell）：
#   .\release.ps1                     只推代码和 tag
#   .\release.ps1 -Release            顺带用 GitHub API 建 Release 并上传两个 jar
#
# 建 Release 需要 token（classic 勾 repo，或 fine-grained 给 Contents: Read and write）：
#   $env:GITHUB_TOKEN = "ghp_xxx"
#   .\release.ps1 -Release
#
# 只用 git 推送时，Git Credential Manager 会自己弹登录窗口，不需要 token。

param(
    [string]$Tag = "v0.1.0",
    [switch]$Release,
    [string]$Token = $env:GITHUB_TOKEN,
    [string]$Proxy = "http://127.0.0.1:7890"
)

$ErrorActionPreference = "Stop"
$repo = "wuliaoppx/Create-Cchematics-Internet"
$root = $PSScriptRoot

Write-Host "==> 1/4 代理与远程" -ForegroundColor Cyan
if ($Proxy) {
    git config http.proxy $Proxy
    git config https.proxy $Proxy
}
git remote get-url origin *> $null
if ($LASTEXITCODE -ne 0) {
    git remote add origin "https://github.com/$repo.git"
}
git remote -v

Write-Host "==> 2/4 推送 main" -ForegroundColor Cyan
git push -u origin main
if ($LASTEXITCODE -ne 0) { throw "推送失败：检查网络/凭据。可在浏览器用 Git Credential Manager 登录后重试。" }

Write-Host "==> 3/4 tag $Tag" -ForegroundColor Cyan
$exists = git tag -l $Tag
if (-not $exists) {
    git tag -a $Tag -m "schematicsinternet 0.1.0"
}
git push origin $Tag
if ($LASTEXITCODE -ne 0) { throw "tag 推送失败" }

if (-not $Release) {
    Write-Host ""
    Write-Host "代码与 tag 已推。" -ForegroundColor Green
    Write-Host "要建 Release：设好 `$env:GITHUB_TOKEN 后重跑  .\release.ps1 -Release" -ForegroundColor Yellow
    Write-Host "（或在网页 https://github.com/$repo/releases/new 手动上传 dist\ 里的两个 jar）"
    return
}

Write-Host "==> 4/4 建 Release 并上传 jar" -ForegroundColor Cyan
if (-not $Token) { throw "缺少 token：`$env:GITHUB_TOKEN = 'ghp_xxx'" }
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$headers = @{
    Authorization          = "Bearer $Token"
    "Accept"               = "application/vnd.github+json"
    "User-Agent"           = "schematicsinternet-release-script"
    "X-GitHub-Api-Version" = "2022-11-28"
}
$notesFile = Join-Path $root "RELEASE-NOTES-v0.1.0.md"
$body = @{
    tag_name         = $Tag
    target_commitish = "main"
    name             = "schematicsinternet 0.1.0"
    body             = (Get-Content $notesFile -Raw -Encoding UTF8)
    draft            = $false
    prerelease       = $false
} | ConvertTo-Json -Depth 4

try {
    $rel = Invoke-RestMethod -Method Post -Uri "https://api.github.com/repos/$repo/releases" -Headers $headers -Body $body -ContentType "application/json"
} catch {
    # 已存在同名 release（409）：改成按 tag 查出来复用
    $rel = Invoke-RestMethod -Uri "https://api.github.com/repos/$repo/releases/tags/$Tag" -Headers $headers
    Write-Host "已有 Release，复用：$($rel.html_url)" -ForegroundColor Yellow
}
Write-Host "Release: $($rel.html_url)" -ForegroundColor Green

$uploadBase = ($rel.upload_url -replace "\{\?name,label\}", "")
foreach ($jar in Get-ChildItem (Join-Path $root "dist") -Filter "schematicsinternet-*.jar") {
    $uri = "$uploadBase?name=$([uri]::EscapeDataString($jar.Name))"
    Write-Host "  上传 $($jar.Name)（$([math]::Round($jar.Length/1KB,1)) KB）"
    $h = $headers.Clone()
    $h["Content-Type"] = "application/java-archive"
    $r = Invoke-RestMethod -Method Post -Uri $uri -Headers $h -InFile $jar.FullName
    Write-Host "    OK $($r.browser_download_url)"
}

Write-Host ""
Write-Host "完成：$($rel.html_url)" -ForegroundColor Green
