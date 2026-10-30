# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 DarkRelay
# The only way on-device tests and checks run (1.7.1): the Android 17 emulator (AVD ddf-api37), through an adb server of
# its own, so no phone is ever reachable from a test run.
#   .\tools\emulator.ps1 -Start                       boot the emulator headless (no window) and its own adb server
#   .\tools\emulator.ps1 -Gradle "<gradle args>"      run gradlew with the locked-down settings (connected tests)
#   .\tools\emulator.ps1 -Adb "<adb args>"            run adb against the emulator only
#   .\tools\emulator.ps1 -Stop                        shut the emulator and its adb server down
# The lockdown, for this script and everything it starts:
#   - ADB_MDNS=0 and ADB_MDNS_AUTO_CONNECT=0: the adb server neither looks for nor joins wireless-debugging devices
#   - ANDROID_ADB_SERVER_PORT=5038: its own adb server, apart from any other on the usual 5037 (one a phone may use)
#   - ANDROID_SERIAL=emulator-5554: every adb and Gradle connected-test call goes to the emulator alone
#   - before anything runs, the device list must be exactly that emulator, or it stops
#   - Gradle's test runner starts an adb server of its own on 5037 whatever the port setting says: it inherits mDNS off
#     from here, a Gradle run refuses to start while another adb server already listens on 5037 (it could be one a phone
#     uses), and afterwards the one the run started is checked (the emulator alone) and stopped
# app/build.gradle.kts refuses connected tests unless these settings are in force, so a run outside this script fails.
param([switch]$Start, [switch]$Stop, [string]$Gradle, [string]$Adb)
$ErrorActionPreference = "Stop"

$Root = Split-Path $PSScriptRoot -Parent
$Avd = "ddf-api37"
$Serial = "emulator-5554"
$env:ADB_MDNS = "0"
$env:ADB_MDNS_AUTO_CONNECT = "0"
$env:ANDROID_ADB_SERVER_PORT = "5038"
$env:ANDROID_SERIAL = $Serial

function Sdk {
    $lp = Join-Path $Root "local.properties"
    $dir = if (Test-Path $lp) { (Get-Content $lp | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1) -replace '^sdk\.dir=', '' -replace '\\:', ':' -replace '\\\\', '\' } else { $env:ANDROID_HOME }
    if (-not $dir -or -not (Test-Path $dir)) { throw "no Android SDK (local.properties sdk.dir or ANDROID_HOME)" }
    return $dir
}
$AdbExe = Join-Path (Sdk) "platform-tools\adb.exe"
$EmuExe = Join-Path (Sdk) "emulator\emulator.exe"

# adb's own notes on stderr ("daemon started") aren't failures: judged by the exit code
function Invoke-Adb([string[]]$argv) {
    $prev = $ErrorActionPreference; $ErrorActionPreference = "Continue"
    try { $out = @(& $AdbExe @argv 2>&1 | ForEach-Object { "$_" }); $code = $LASTEXITCODE } finally { $ErrorActionPreference = $prev }
    [pscustomobject]@{ Code = $code; Out = $out }
}

# The device list must be the emulator and nothing else.
function Assert-OnlyEmulator {
    $lines = @((Invoke-Adb @("devices")).Out | Where-Object { $_ -match "`t" })
    $others = @($lines | Where-Object { -not $_.StartsWith("$Serial`t") })
    if ($others.Count -gt 0) {
        Invoke-Adb @("kill-server") | Out-Null
        throw "the test adb server sees a device other than the emulator ($($others -join '; ')); stopped and nothing ran"
    }
    if (-not ($lines | Where-Object { $_ -eq "$Serial`tdevice" })) { throw "the emulator ($Serial) isn't running: use -Start" }
}

if ($Start) {
    Invoke-Adb @("start-server") | Out-Null
    $booted = (Invoke-Adb @("-s", $Serial, "shell", "getprop", "sys.boot_completed")).Out -join ""
    if ($booted.Trim() -ne "1") {
        Start-Process -FilePath $EmuExe -ArgumentList @("-avd", $Avd, "-no-window", "-no-audio", "-no-boot-anim", "-no-snapshot", "-gpu", "swiftshader_indirect", "-memory", "3072") -WindowStyle Hidden | Out-Null
        $deadline = (Get-Date).AddMinutes(6)
        do {
            Start-Sleep -Seconds 5
            $booted = ((Invoke-Adb @("-s", $Serial, "shell", "getprop", "sys.boot_completed")).Out -join "").Trim()
        } while ($booted -ne "1" -and (Get-Date) -lt $deadline)
        if ($booted -ne "1") { throw "the emulator didn't finish booting in 6 minutes" }
    }
    Assert-OnlyEmulator
    Write-Host "emulator $Serial ready (adb server on port $env:ANDROID_ADB_SERVER_PORT, mDNS off)"
}

if ($Adb) {
    Assert-OnlyEmulator
    $r = Invoke-Adb (@("-s", $Serial) + ($Adb -split ' '))
    $r.Out | ForEach-Object { Write-Host $_ }
    if ($r.Code -ne 0) { throw "adb $Adb failed ($($r.Code))" }
}

function Test-DefaultAdb { $null -ne (Get-NetTCPConnection -LocalPort 5037 -State Listen -ErrorAction SilentlyContinue) }

# The adb server Gradle's test runner started on 5037: it may see the emulator and nothing else, then it is stopped.
function Close-DefaultAdb {
    $saved = $env:ANDROID_ADB_SERVER_PORT
    $env:ANDROID_ADB_SERVER_PORT = "5037"
    try {
        $lines = @((Invoke-Adb @("devices")).Out | Where-Object { $_ -match "`t" })
        Invoke-Adb @("kill-server") | Out-Null
        $others = @($lines | Where-Object { -not $_.StartsWith("$Serial`t") })
        if ($others.Count -gt 0) { throw "the adb server Gradle started saw a device other than the emulator ($($others -join '; ')); it was stopped" }
    } finally {
        $env:ANDROID_ADB_SERVER_PORT = $saved
    }
}

if ($Gradle) {
    if (Test-DefaultAdb) { throw "another adb server is listening on port 5037 (it could be one a phone uses); stop it first, then run this again" }
    Assert-OnlyEmulator
    $prev = $ErrorActionPreference; $ErrorActionPreference = "Continue"
    try { & (Join-Path $Root "gradlew.bat") @($Gradle -split ' ') 2>&1 | ForEach-Object { Write-Host "$_" }; $code = $LASTEXITCODE } finally { $ErrorActionPreference = $prev }
    if (Test-DefaultAdb) { Close-DefaultAdb }
    if ($code -ne 0) { throw "gradle $Gradle failed ($code)" }
}

if ($Stop) {
    Invoke-Adb @("-s", $Serial, "emu", "kill") | Out-Null
    Start-Sleep -Seconds 5
    Invoke-Adb @("kill-server") | Out-Null
    Write-Host "emulator and its adb server stopped"
}
