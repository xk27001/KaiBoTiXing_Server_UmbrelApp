# 抖音主播开播监控 - Windows exe 打包脚本（服务端 + 客户端）
# 用法:
#   powershell -ExecutionPolicy Bypass -File package-exe.ps1             # 打包服务端与客户端
#   powershell -ExecutionPolicy Bypass -File package-exe.ps1 -App server # 仅打包服务端
#   powershell -ExecutionPolicy Bypass -File package-exe.ps1 -App client # 仅打包客户端

param(
    [ValidateSet("all", "server", "client")]
    [string]$App = "all"
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $projectRoot

$fxBase = Join-Path $env:USERPROFILE ".m2\repository\org\openjfx"
$fxVer = "23.0.2"
$appVersion = "1.0.0"
$fatJar = "target\KaiBoTiXing-1.0.0-fat.jar"

# 打包目标：服务端（抖音主播开播监控）与客户端（主播开播提醒）
# 两个程序共用同一个 fat jar，仅入口类与产物名称不同
$allTargets = @(
    @{ Key = "server"; Name = "KaiBoTiXing"; MainClass = "com.kaibotixing.Launcher"; Label = "服务端（抖音主播开播监控）" },
    @{ Key = "client"; Name = "Reminder"; MainClass = "com.kaibotixing.reminder.ReminderLauncher"; Label = "客户端（主播开播提醒）" }
)
$targets = @($allTargets | Where-Object { $App -eq "all" -or $_.Key -eq $App })

Write-Host "1. 编译打包 fat jar ..."
mvn -B -q package -DskipTests

if (-not (Test-Path $fatJar)) {
    Write-Error "未找到 fat jar，打包失败"
    exit 1
}

Write-Host "2. 生成 jlink 运行时镜像 ..."
$modPath = @(
    "$fxBase\javafx-base\$fxVer\javafx-base-$fxVer-win.jar",
    "$fxBase\javafx-base\$fxVer\javafx-base-$fxVer.jar",
    "$fxBase\javafx-graphics\$fxVer\javafx-graphics-$fxVer-win.jar",
    "$fxBase\javafx-graphics\$fxVer\javafx-graphics-$fxVer.jar",
    "$fxBase\javafx-controls\$fxVer\javafx-controls-$fxVer-win.jar",
    "$fxBase\javafx-controls\$fxVer\javafx-controls-$fxVer.jar",
    "$fxBase\javafx-fxml\$fxVer\javafx-fxml-$fxVer-win.jar",
    "$fxBase\javafx-fxml\$fxVer\javafx-fxml-$fxVer.jar",
    "$fxBase\javafx-media\$fxVer\javafx-media-$fxVer-win.jar",
    "$fxBase\javafx-media\$fxVer\javafx-media-$fxVer.jar"
) -join ";"

if (Test-Path "target\runtime") { Remove-Item -Recurse -Force "target\runtime" }

jlink --module-path $modPath `
    --add-modules javafx.base,javafx.graphics,javafx.controls,javafx.fxml,javafx.media,java.naming,java.sql,java.management,java.instrument,java.net.http,java.security.jgss,java.security.sasl,jdk.crypto.ec,jdk.unsupported,java.desktop,java.logging,java.xml `
    --output "target\runtime" --strip-debug --no-header-files --no-man-pages

Write-Host "3. 准备 jar ..."
if (Test-Path "dist\lib") { Remove-Item -Recurse -Force "dist\lib" }
New-Item -ItemType Directory -Path "dist\lib" -Force | Out-Null
Copy-Item $fatJar "dist\lib\KaiBoTiXing.jar"

foreach ($t in $targets) {
    $outDir = "dist\" + $t.Name
    Write-Host ""
    Write-Host ("4. 用 jpackage 生成 {0} ..." -f $t.Label)
    if (Test-Path $outDir) { Remove-Item -Recurse -Force $outDir }

    jpackage --type app-image --name $t.Name `
        --input dist\lib --main-jar KaiBoTiXing.jar `
        --main-class $t.MainClass `
        --runtime-image target\runtime `
        --dest dist --app-version $appVersion

    Write-Host ("   生成: {0}\{1}.exe" -f $outDir, $t.Name)
}

Write-Host ""
Write-Host "打包完成！免安装绿色版（内置 JRE，目标机器无需预装 Java）："
foreach ($t in $targets) {
    Write-Host ("  {0}: dist\{1}\{1}.exe" -f $t.Label, $t.Name)
}
Write-Host "整个 dist\<名称> 目录可直接分发运行。"
