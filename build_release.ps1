# Lyricon v3 正式版打包脚本
# 版本号由根 build.gradle.kts 的 bumpVersion 在构建时自动 +1，本脚本不手动改版本号
# 用法: powershell -ExecutionPolicy Bypass -File build_release.ps1
$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
Set-Location $root

$env:JAVA_HOME = "C:\Users\Administrator\.jdks\jbr-17.0.14"
$env:ANDROID_HOME = "D:\Android\Sdk"
$env:PATH = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:PATH"

Write-Host "开始构建正式版（:app:bundleLibCompileToJarStandardRelease + :lyricon:assembleStandardRelease）..." -ForegroundColor Cyan
& .\gradlew.bat :app:bundleLibCompileToJarStandardRelease :lyricon:assembleStandardRelease
if ($LASTEXITCODE -ne 0) { Write-Error "构建失败"; exit 1 }

# 构建完成后从根 build.gradle.kts 读取最新版本号（bumpVersion 已在构建时 +1）
$gradleFile = Join-Path $root "build.gradle.kts"
$content = Get-Content $gradleFile -Raw -Encoding UTF8
if ($content -match 'extra\["appVersionName"\] = "([\d.]+)"') {
    $versionName = $Matches[1]
} else {
    Write-Error "找不到版本号"; exit 1
}

# 从 strings.xml 读取应用名称
$stringsFile = Join-Path $root "app\src\main\res\values\strings.xml"
$stringsContent = Get-Content $stringsFile -Raw -Encoding UTF8
if ($stringsContent -match '<string name="app_name">([^<]+)</string>') {
    $appName = $Matches[1]
} else {
    Write-Error "找不到应用名称"; exit 1
}

$apk = Get-ChildItem (Join-Path $root "build\all-apks\release\*.apk") | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $apk) { Write-Error "找不到 APK"; exit 1 }
$outPath = Join-Path $apk.DirectoryName "$appName-$versionName-正式版.apk"
Copy-Item $apk.FullName $outPath -Force

Write-Host ""
Write-Host "完成: $outPath" -ForegroundColor Green
explorer (Split-Path $outPath)
Write-Host "大小: $([math]::Round($apk.Length/1MB,1)) MB" -ForegroundColor Green

$install = Read-Host "是否安装到手机？(是/否)"
if ($install -eq "是") { & adb install -r $outPath }
