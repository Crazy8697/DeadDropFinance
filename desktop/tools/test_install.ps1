# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 DarkRelay
# Install test of the desktop program, isolated: the TEST installer (desktop\build.ps1 -Setup -TestBuild: its own
# AppId, name and folder) installed for the current user only into a temp folder (no UAC prompt), with a throwaway
# %APPDATA%. Never touches a real installation, its Apps entry or the user's settings.
#   .\desktop\tools\test_install.ps1 -Setup <DeadDropFinance-Test-Setup.exe> [-UpgradeFrom <older test Setup>] [-Live] [-FakeApi <url>] [-Keep]
# -UpgradeFrom: first install the older TEST build there, let it make its budget (0.6.0's is plain) and run its update
# probe, then install -Setup over it as the updater would (same test AppId): the newer program must move a plain budget
# into an encrypted one. -Live: the probes ask the real GitHub, and the older build must be offered this version while
# this version is offered nothing.
# Checks, stopping at the first failure:
#   0. (-UpgradeFrom) the older build installed, its smoke test, its plain budget, its update probe
#   1. silent install: the program, its runtime, LICENSE.txt, NOTICE.txt, THIRD-PARTY-NOTICES.md and licenses\*.txt
#      in the folder; one Apps entry (HKCU) for the test AppId; a Start menu shortcut; no desktop shortcut
#   2. the installed program's smoke test (off screen, never focused, settings and the encrypted budget in the
#      throwaway %APPDATA% only); after -UpgradeFrom, the plain budget moved into an encrypted one with the same rows
#   3. the installed program's update probe: against -FakeApi when given (DDF_DESKTOP_UPDATE_API), else real GitHub
#   4. the same Setup again (/VERYSILENT, so no progress window shows): an in-place upgrade, still one Apps entry
#   5. a settings file in the throwaway %APPDATA%, then silent uninstall: the folder, the Apps entry and the shortcut
#      are gone, and the settings file, the budget and its key are still there
param(
    [Parameter(Mandatory = $true)][string]$Setup,
    [string]$UpgradeFrom = "",
    [switch]$Live,
    [string]$FakeApi = "",
    [switch]$Keep
)
$ErrorActionPreference = "Stop"
$AppGuid = "{6C662EDE-9A0B-405E-9F6D-71BFB2DD96A5}"
$UninstKey = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\${AppGuid}_is1"
$Shortcut = Join-Path ([Environment]::GetFolderPath("Programs")) "Dead Drop Finance Test.lnk"
$DesktopLnk = Join-Path ([Environment]::GetFolderPath("Desktop")) "Dead Drop Finance Test.lnk"

function Fail([string]$why) { throw "test install: $why" }
function Ok([string]$what) { Write-Host "ok: $what" }

if (Test-Path $UninstKey) { Fail "a test install is already registered; uninstall it first" }
$tmp = Join-Path $env:TEMP ("ddf-desktop-install-" + [guid]::NewGuid().ToString("N").Substring(0, 8))
$dir = Join-Path $tmp "DeadDropFinance Test"
$appData = Join-Path $tmp "AppData"
$data = Join-Path $appData "DeadDropFinance"
New-Item -ItemType Directory -Force $appData | Out-Null
$oldAppData = $env:APPDATA
$oldApi = $env:DDF_DESKTOP_UPDATE_API

function Install([string[]]$extra, [string]$exe = $Setup) {
    $log = Join-Path $tmp ("setup-" + [guid]::NewGuid().ToString("N").Substring(0, 6) + ".log")
    $a = @("/CURRENTUSER", "/DIR=`"$dir`"", "/MERGETASKS=`"!desktopicon`"", "/NORESTART", "/SUPPRESSMSGBOXES", "/SP-", "/LOG=`"$log`"") + $extra
    $p = Start-Process -FilePath $exe -ArgumentList $a -PassThru -Wait
    if ($p.ExitCode -ne 0) { Fail "Setup exited with $($p.ExitCode) (log $log)" }
}

function RunProgram([string[]]$argv, [string]$out) {
    $env:APPDATA = $appData
    try {
        $p = Start-Process -FilePath (Join-Path $dir "DeadDropFinance.exe") -ArgumentList $argv -PassThru
        if (-not $p.WaitForExit(60000)) { $p.Kill(); Fail "the program didn't finish within 60 s" }
    } finally { $env:APPDATA = $oldAppData }
    if (-not (Test-Path $out)) { Fail "the program wrote no result ($($argv[0]))" }
    $r = @{}
    foreach ($l in Get-Content $out) { $k, $v = $l -split '=', 2; $r[$k] = $v }
    return $r
}

function Probe([string]$name) {
    if ($FakeApi) { $env:DDF_DESKTOP_UPDATE_API = $FakeApi }
    try {
        $out = Join-Path $tmp ("probe-" + [guid]::NewGuid().ToString("N").Substring(0, 6) + ".txt")
        $r = RunProgram @("--update-probe", "`"$out`"") $out
    } finally { $env:DDF_DESKTOP_UPDATE_API = $oldApi }
    Write-Host ("update probe, $name (" + $(if ($FakeApi) { "fake GitHub" } else { "GitHub" }) + "): " + ((Get-Content $out) -join "; "))
    return $r
}

function IsPlain([string]$file) {
    $bytes = [IO.File]::ReadAllBytes($file)
    return $bytes.Length -ge 15 -and [Text.Encoding]::ASCII.GetString($bytes, 0, 15) -eq "SQLite format 3"
}

# the version being tested: ddf.desktop.versionName in gradle.properties
$Version = ((Get-Content (Join-Path $PSScriptRoot "..\..\gradle.properties") | Where-Object { $_ -match '^ddf\.desktop\.versionName=' }) -replace '^ddf\.desktop\.versionName=', '').Trim()
$ok = $false
$old = $null
try {
    # ---- 0: the older build first ----
    if ($UpgradeFrom) {
        Install @("/VERYSILENT") $UpgradeFrom
        $out = Join-Path $tmp "smoke-old.txt"
        $old = RunProgram @("--smoke-test", "`"$out`"") $out
        if ($old["db"] -ne "ok") { Fail "the older build's smoke test: $((Get-Content $out) -join '; ')" }
        $plain = IsPlain (Join-Path $data "budget.db")
        Ok "older build $($old["version"]) installed; its budget made ($(if ($plain) { 'plain SQLite' } else { 'encrypted' }), schema $($old["dbSchema"]), $($old["envelopes"]) envelopes, $($old["categories"]) categories)"
        $p = Probe "older build $($old["version"])"
        if ($Live -and ($p["status"] -ne "newer" -or $p["version"] -ne $Version)) { Fail "the older build isn't offered $Version" }
    }

    # ---- 1 ----
    Install @("/VERYSILENT")
    foreach ($f in @("DeadDropFinance.exe", "app\DeadDropFinance.cfg", "runtime\bin\java.dll", "runtime\legal\java.base\LICENSE",
                     "LICENSE.txt", "NOTICE.txt", "THIRD-PARTY-NOTICES.md", "licenses\Apache-2.0.txt", "licenses\libpng-2.0.txt",
                     "licenses\GPL-2.0-with-Classpath-Exception-OpenJDK.txt", "licenses\BSD-3-Clause-Skia.txt",
                     "licenses\Unicode-3.0-ICU.txt", "licenses\SQLite-Public-Domain.txt", "licenses\MIT-SQLite3MultipleCiphers.txt", "unins000.exe")) {
        if (-not (Test-Path (Join-Path $dir $f))) { Fail "missing after install: $f" }
    }
    $lic = @(Get-ChildItem (Join-Path $dir "licenses") -Filter *.txt).Count
    $entry = Get-ItemProperty $UninstKey
    if ($entry.DisplayName -ne "Dead Drop Finance Test" -or $entry.DisplayVersion -ne $Version) { Fail "the Apps entry is '$($entry.DisplayName)' $($entry.DisplayVersion)" }
    if (-not (Test-Path $Shortcut)) { Fail "no Start menu shortcut" }
    if (Test-Path $DesktopLnk) { Fail "a desktop shortcut was made although the task was off" }
    Ok "installed $(if ($old) { 'over the older build' } else { 'to a temp folder' }): program, runtime, notices, $lic license texts, Apps entry $($entry.DisplayName) $($entry.DisplayVersion), Start menu shortcut, no desktop shortcut"

    # ---- 2 ----
    $out = Join-Path $tmp "smoke.txt"
    $r = RunProgram @("--smoke-test", "`"$out`"") $out
    if ($r["version"] -ne $Version -or $r["showing"] -ne "true" -or $r["focused"] -ne "false" -or
        $r["settingsDir"] -ne $data -or $r["db"] -ne "ok" -or $r["dbSchema"] -ne "12" -or
        $r["dbFile"] -ne (Join-Path $data "budget.db") -or $r["dbEncrypted"] -ne "true") { Fail "smoke test: $((Get-Content $out) -join '; ')" }
    if (IsPlain (Join-Path $data "budget.db")) { Fail "budget.db is still plain SQLite" }
    if (-not (Test-Path (Join-Path $data "budget.key"))) { Fail "no budget.key beside the budget" }
    $written = @(Get-ChildItem -Recurse -File $dir | Where-Object { $_.LastWriteTime -gt (Get-Item (Join-Path $dir "unins000.exe")).LastWriteTime.AddSeconds(5) })
    if ($written.Count) { Fail "the program wrote into its folder: $($written.Name -join ', ')" }
    Ok "installed program: name, version $Version, shown off screen, not focused, settings and encrypted budget (schema 12, $($r["envelopes"]) envelopes, $($r["categories"]) categories) in the throwaway APPDATA, nothing written in its folder"
    if ($old) {
        if ($plain -and $r["dbMigrated"] -ne "true") { Fail "the plain budget wasn't moved into an encrypted one: $((Get-Content $out) -join '; ')" }
        if ($r["envelopes"] -ne $old["envelopes"] -or $r["categories"] -ne $old["categories"]) { Fail "the encrypted budget doesn't hold what the older one did" }
        foreach ($left in @("budget.db.plain", "budget.db.encrypting")) { if (Test-Path (Join-Path $data $left)) { Fail "$left was left behind" } }
        if ($plain) { Ok "upgrade: the older build's plain budget is now encrypted (SQLCipher 4 format, key under DPAPI), same rows, the plain file gone" }
        else { Ok "upgrade: the older build's encrypted budget is kept, same key, same rows" }
    }

    # ---- 3 ----
    $p = Probe "this build $Version"
    if ($Live -and ($p["status"] -ne "current" -or $p["newest"] -ne $Version)) { Fail "$Version is offered again, or isn't the newest" }

    # ---- 4 ----
    Install @("/VERYSILENT")
    $n = @(Get-ChildItem "HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall" | Where-Object { $_.PSChildName -like "*6C662EDE*" }).Count
    if ($n -ne 1) { Fail "$n Apps entries after the upgrade" }
    if (-not (Test-Path (Join-Path $dir "DeadDropFinance.exe"))) { Fail "no program after the upgrade" }
    Ok "same Setup again: upgraded in place, still one Apps entry"

    # ---- 5 ----
    $settings = Join-Path $data "settings.properties"
    New-Item -ItemType Directory -Force $data | Out-Null
    Set-Content -Path $settings -Value "checkUpdatesAtStartup=false" -Encoding ascii
    $p = Start-Process -FilePath (Join-Path $dir "unins000.exe") -ArgumentList @("/VERYSILENT", "/SUPPRESSMSGBOXES", "/NORESTART") -PassThru -Wait
    # the uninstaller hands over to a copy of itself in %TEMP% and returns; wait for the folder to go
    $deadline = (Get-Date).AddSeconds(60)
    while ((Test-Path (Join-Path $dir "DeadDropFinance.exe")) -and (Get-Date) -lt $deadline) { Start-Sleep -Milliseconds 500 }
    while ((Test-Path $UninstKey) -and (Get-Date) -lt $deadline) { Start-Sleep -Milliseconds 500 }
    if (Test-Path (Join-Path $dir "DeadDropFinance.exe")) { Fail "the program is still there after uninstall" }
    if (Test-Path $UninstKey) { Fail "the Apps entry is still there after uninstall" }
    if (Test-Path $Shortcut) { Fail "the Start menu shortcut is still there after uninstall" }
    if (-not (Test-Path $settings)) { Fail "uninstall removed the user's settings" }
    foreach ($kept in @("budget.db", "budget.key")) { if (-not (Test-Path (Join-Path $data $kept))) { Fail "uninstall removed $kept" } }
    Ok "uninstalled: program, Apps entry and shortcut gone; the user's settings, budget and key kept"
    $ok = $true
} finally {
    $env:APPDATA = $oldAppData
    $env:DDF_DESKTOP_UPDATE_API = $oldApi
    if (-not $ok -and (Test-Path (Join-Path $dir "unins000.exe"))) {
        Start-Process -FilePath (Join-Path $dir "unins000.exe") -ArgumentList @("/VERYSILENT", "/SUPPRESSMSGBOXES", "/NORESTART") -Wait
        Start-Sleep -Seconds 3
    }
    if (-not $Keep) { Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue }
}
Write-Host "Install test passed."
