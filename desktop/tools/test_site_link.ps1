# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 DarkRelay
# Proves desktop\tools\site_link.ps1 (the darkrelay.net step of build.ps1 -Release) on a throwaway repo: never the
# real site. A bare "origin" and a clone with core.autocrlf=true and LF line endings, so git writes its "LF will be
# replaced by CRLF" warning to stderr, as on the build machine.
#   .\desktop\tools\test_site_link.ps1
# Checks, stopping at the first failure:
#   1. git really writes the warning (and exits 0), and the old way (git under ErrorActionPreference Stop, as in
#      0.12.0) fails on that warning alone
#   2. Update-SiteLink still moves the link, commits and pushes it
#   3. a real failure (origin gone) still stops the step, and leaves the site as it was
# Everything is deleted afterwards.
$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "site_link.ps1")

$root = Join-Path ([IO.Path]::GetTempPath()) ("ddf-site-link-test-" + [Guid]::NewGuid().ToString("N").Substring(0, 8))
$origin = Join-Path $root "origin.git"
$site = Join-Path $root "site"
$re = 'href="' + [regex]::Escape("https://github.com/Crazy8697/DeadDropFinance/releases/download/desktop-v") + '\d+\.\d+\.\d+/' + [regex]::Escape("DeadDropFinance-Setup.exe") + '"'
function Href([string]$v) { "https://github.com/Crazy8697/DeadDropFinance/releases/download/desktop-v$v/DeadDropFinance-Setup.exe" }
function Check([bool]$ok, [string]$what) { if (-not $ok) { throw "FAILED: $what" }; Write-Host "ok: $what" }

try {
    New-Item -ItemType Directory -Force $root | Out-Null
    Invoke-SiteGitOk $root @("init", "-q", "--bare", "-b", "main", $origin) | Out-Null
    Invoke-SiteGitOk $root @("clone", "-q", $origin, $site) | Out-Null
    foreach ($kv in @(@("core.autocrlf", "true"), @("user.name", "site link test"), @("user.email", ("site-test" + [char]64 + "example.invalid")), @("commit.gpgsign", "false"))) {
        Invoke-SiteGitOk $site @("config", $kv[0], $kv[1]) | Out-Null
    }
    $index = Join-Path $site "index.html"
    $utf8 = New-Object Text.UTF8Encoding($false)
    $page = "<!doctype html>`n<p>Sample page</p>`n<a class=`"dl`" href=`"$(Href '0.12.0')`">Download</a>`n"
    [IO.File]::WriteAllText($index, $page, $utf8)
    Invoke-SiteGitOk $site @("add", "index.html") | Out-Null
    Invoke-SiteGitOk $site @("commit", "-q", "-m", "sample") | Out-Null
    Invoke-SiteGitOk $site @("push", "-q", "-u", "origin", "main") | Out-Null

    # 1. the 0.12.0 way: git's warning on stderr counts as an error under Stop
    [IO.File]::WriteAllText($index, $page.Replace("0.12.0", "0.12.9"), $utf8)
    $warn = Invoke-SiteGit $site @("diff", "--numstat")
    $oldWayFailed = $false
    try { $null = @(git -C $site diff --numstat) } catch { $oldWayFailed = $true }
    [IO.File]::WriteAllText($index, $page, $utf8)
    Check ($warn.Code -eq 0 -and (($warn.Err -join " ") -match "LF will be replaced by CRLF")) "git writes the line-ending warning to stderr and still exits 0"
    Check $oldWayFailed "the 0.12.0 way (git under ErrorActionPreference Stop) fails on that warning alone"

    # 2. the step now: the same warning, judged by the exit code, and the link moves, commits and pushes
    $did = Update-SiteLink $site $re (Href "0.12.1") "desktop-v0.12.1"
    $pushed = (Invoke-SiteGitOk $origin @("show", "HEAD:index.html")) -join "`n"
    Check ($pushed.Contains((Href "0.12.1")) -and -not $pushed.Contains((Href "0.12.0"))) "Update-SiteLink moved the link, committed and pushed it ($did)"
    Check (@(Invoke-SiteGitOk $site @("status", "--porcelain")).Count -eq 0) "the site is clean afterwards"

    # 3. a real failure still stops the step: origin is gone
    Invoke-SiteGitOk $site @("remote", "set-url", "origin", (Join-Path $root "no-such-origin.git")) | Out-Null
    $stopped = $null
    try { Update-SiteLink $site $re (Href "0.12.2") "desktop-v0.12.2" | Out-Null } catch { $stopped = $_.Exception.Message }
    Check ($null -ne $stopped -and $stopped -match "git \w+ failed") "a real git failure stops the step: $stopped"
    $now = [IO.File]::ReadAllText($index)
    Check ($now.Contains((Href "0.12.1")) -and @(Invoke-SiteGitOk $site @("status", "--porcelain")).Count -eq 0) "and the site is left as it was"

    Write-Host "Site link test passed."
} finally {
    if (Test-Path $root) { Remove-Item -Recurse -Force $root -ErrorAction SilentlyContinue }
}
