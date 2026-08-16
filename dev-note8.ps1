<#
.SYNOPSIS
    Build, install and collect logs for the side-by-side diagnostics build.

.DESCRIPTION
    The debug variant installs as com.andrerinas.headunitrevived.dev and leaves the store build
    (com.andrerinas.headunitrevived) untouched, so a fault can be chased on the same device that
    still has a working app on it.

    This script only wires up the toolchain paths and wraps adb/gradle. It makes no decisions.

.EXAMPLE
    .\dev-note8.ps1 build       # assemble the debug APK
    .\dev-note8.ps1 install     # assemble + install alongside the store build
    .\dev-note8.ps1 logs        # pull captured AppLog files off the device
    .\dev-note8.ps1 logcat      # live logcat, filtered to this app
    .\dev-note8.ps1 status      # what is installed, and is capture actually writing
#>
param(
    [Parameter(Position = 0)]
    [ValidateSet('build', 'install', 'logs', 'logcat', 'status')]
    [string]$Command = 'status'
)

$ErrorActionPreference = 'Stop'

# Toolchain installed out-of-tree so the repo stays clean; override if yours lives elsewhere.
$env:JAVA_HOME = 'D:\android-dev\jdk\jdk-17.0.20+8'
$env:ANDROID_HOME = 'D:\android-dev\sdk'
$adb = 'C:\adb\adb.exe'

$devPkg = 'com.andrerinas.headunitrevived.dev'
$storePkg = 'com.andrerinas.headunitrevived'
$apk = Join-Path $PSScriptRoot 'app\build\outputs\apk\github\debug\com.andrerinas.headunitrevived.dev_3.2.5_debug.apk'

function Invoke-Gradle {
    param([string[]]$GradleArgs)
    Push-Location $PSScriptRoot
    try {
        & (Join-Path $PSScriptRoot 'gradlew.bat') @GradleArgs
        if ($LASTEXITCODE -ne 0) { throw "gradle failed ($LASTEXITCODE)" }
    } finally {
        Pop-Location
    }
}

switch ($Command) {
    'build' {
        Invoke-Gradle @(':app:assembleGithubDebug')
        Get-Item $apk | Select-Object Name, @{n = 'MB'; e = { [math]::Round($_.Length / 1MB, 1) } }, LastWriteTime
    }

    'install' {
        Invoke-Gradle @(':app:assembleGithubDebug')
        # -r reinstalls in place and keeps captured logs and settings from the previous run.
        & $adb install -r $apk
        & $adb shell "pm list packages | grep headunitrevived"
    }

    'logs' {
        $dest = Join-Path $PSScriptRoot 'device-logs'
        New-Item -ItemType Directory -Force -Path $dest | Out-Null
        # getExternalFilesDir(null) — readable over adb without root because it is the app's own
        # external directory.
        & $adb pull "/sdcard/Android/data/$devPkg/files/" $dest 2>&1 | Select-Object -Last 5
        Get-ChildItem -Path $dest -Recurse -Filter 'HUR_Log_*' -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime |
            Select-Object LastWriteTime, @{n = 'KB'; e = { [math]::Round($_.Length / 1KB, 1) } }, FullName
    }

    'logcat' {
        # OPENHU is AppLog.TAG. The extra tags are the system's own account of the app being
        # killed or its broadcasts refused, which is the half AppLog cannot see.
        & $adb logcat -v time | Select-String -Pattern 'OPENHU|headunitrevived|ActivityManager.*andrerinas|BroadcastQueue.*andrerinas'
    }

    'status' {
        Write-Host '--- installed ---'
        & $adb shell "pm list packages | grep headunitrevived"
        Write-Host "`n--- versions ---"
        foreach ($p in @($storePkg, $devPkg)) {
            $v = & $adb shell "dumpsys package $p | grep versionName" 2>$null
            if ($v) { Write-Host "$p : $($v.Trim())" }
        }
        Write-Host "`n--- capture files on device ---"
        & $adb shell "ls -la /sdcard/Android/data/$devPkg/files/ 2>/dev/null"
    }
}
