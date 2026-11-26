# Lyricon v3 测试版打包脚本
# 版本号由根 build.gradle.kts 的 bumpVersion 在构建时自动 +1，本脚本不手动改版本号
$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
Set-Location $root

$env:JAVA_HOME = "C:\Users\Administrator\.jdks\jbr-17.0.14"
$env:ANDROID_HOME = "D:\Android\Sdk"
$env:PATH = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:PATH"

Write-Host "开始编译测试版（:lyricon:assembleDebug）..." -ForegroundColor Cyan
& .\gradlew.bat :lyricon:assembleDebug
if ($LASTEXITCODE -ne 0) { Write-Error "编译失败"; exit 1 }

# 构建完成后从产物 APK 文件名读取版本号（appVersionName 只是本次构建的内存值，不落盘）
$apkDir = Join-Path $root "build\all-apks\debug"
$versionName = $null
if (Test-Path -LiteralPath $apkDir) {
    $latest = Get-ChildItem -LiteralPath $apkDir -Filter "*.apk" | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($latest -and $latest.Name -match '词岛-([\d.]+)-debug') { $versionName = $Matches[1] }
}
if (-not $versionName) { Write-Error "找不到版本号"; exit 1 }

# 从 strings.xml 读取应用名称
$stringsFile = Join-Path $root "app\src\main\res\values\strings.xml"
$stringsContent = Get-Content $stringsFile -Raw -Encoding UTF8
if ($stringsContent -match '<string name="app_name">([^<]+)</string>') {
    $appName = $Matches[1]
} else {
    Write-Error "找不到应用名称"; exit 1
}

$apk = Get-ChildItem (Join-Path $root "build\all-apks\debug\*.apk") | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $apk) { Write-Error "找不到 APK"; exit 1 }
$outPath = Join-Path $apk.DirectoryName "$appName-$versionName-测试版.apk"
Copy-Item $apk.FullName $outPath -Force

Write-Host ""
Write-Host "完成: $outPath" -ForegroundColor Green
explorer (Split-Path $outPath)
Write-Host "大小: $([math]::Round($apk.Length/1MB,1)) MB" -ForegroundColor Green

$install = Read-Host "是否安装到手机？(是/否)"
if ($install -eq "是") { & adb install -r $outPath }
