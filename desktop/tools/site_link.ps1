# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 DarkRelay
# The darkrelay.net step of desktop\build.ps1 -Release (0.12.1): moves the program's download link on the site's front
# page to a new desktop release, commits it and pushes the site to its origin only. Dot-sourced by build.ps1, and by
# desktop\tools\test_site_link.ps1, which proves it on a throwaway repo.
#
# git writes notes to stderr that aren't failures (the line-ending warning "LF will be replaced by CRLF", progress).
# Windows PowerShell turns a native program's stderr into errors, which stopped the step in 0.12.0 with nothing wrong.
# So every git call here goes through Invoke-SiteGit: stderr is collected as text, never thrown, and success is
# judged by git's exit code alone. A real failure (a non-zero exit) still stops the step with git's own words.

function Invoke-SiteGit([string]$site, [string[]]$argv) {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $all = @(& git -C $site @argv 2>&1)
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $prev
    }
    # a blank stderr line comes through as the bare type name: dropped
    $err = @($all | Where-Object { $_ -is [System.Management.Automation.ErrorRecord] } | ForEach-Object { "$_" } |
        Where-Object { $_ -ne "System.Management.Automation.RemoteException" -and $_.Trim() -ne "" })
    $out = @($all | Where-Object { $_ -isnot [System.Management.Automation.ErrorRecord] } | ForEach-Object { "$_" })
    [pscustomobject]@{ Code = $code; Out = $out; Err = $err }
}

# Runs git and throws when it exits non-zero; returns its stdout lines.
function Invoke-SiteGitOk([string]$site, [string[]]$argv) {
    $r = Invoke-SiteGit $site $argv
    if ($r.Code -ne 0) { throw "git $($argv[0]) failed ($($r.Code)): $(($r.Err + $r.Out) -join ' ')" }
    return $r.Out
}

# The site repo must be clean, not behind its upstream (commits of its own still to push are fine: they go out with
# the link), and carry the program's download link exactly once.
function Assert-SiteReady([string]$site, [string]$hrefRe) {
    if (-not (Test-Path (Join-Path $site ".git"))) { throw "no darkrelay-site repo at $site" }
    if (@(Invoke-SiteGitOk $site @("status", "--porcelain")).Count -ne 0) { throw "darkrelay-site has uncommitted changes; commit or stash them first" }
    Invoke-SiteGitOk $site @("fetch", "-q", "origin") | Out-Null
    $r = Invoke-SiteGit $site @("merge-base", "--is-ancestor", "@{u}", "HEAD")
    if ($r.Code -ne 0) { throw "darkrelay-site is behind or apart from its upstream (pull it first)" }
    $n = [regex]::Matches([IO.File]::ReadAllText((Join-Path $site "index.html")), $hrefRe).Count
    if ($n -ne 1) { throw "darkrelay-site index.html has the program's download link $n times, not exactly once" }
}

# Moves the link to $newHref (index.html put back if anything else changed), commits it, and pushes whatever the site
# has that origin hasn't. Returns a line saying what it did.
function Update-SiteLink([string]$site, [string]$hrefRe, [string]$newHref, [string]$tag) {
    Assert-SiteReady $site $hrefRe
    $index = Join-Path $site "index.html"
    $utf8 = New-Object Text.UTF8Encoding($false)
    $before = [IO.File]::ReadAllText($index)
    $after = [regex]::Replace($before, $hrefRe, "href=`"$newHref`"")
    $did = @()
    if ($after -ceq $before) {
        $did += "index.html already links $tag; no commit"
    } else {
        [IO.File]::WriteAllText($index, $after, $utf8)
        $changed = @(Invoke-SiteGitOk $site @("status", "--porcelain"))
        $num = @(Invoke-SiteGitOk $site @("diff", "--numstat"))
        if ($changed.Count -ne 1 -or $changed[0] -ne " M index.html" -or $num.Count -ne 1 -or $num[0] -ne "1`t1`tindex.html") {
            [IO.File]::WriteAllText($index, $before, $utf8)
            throw "darkrelay-site changed more than the one link line ($($changed -join '; ')); index.html was put back"
        }
        try {
            Invoke-SiteGitOk $site @("add", "--", "index.html") | Out-Null
            Invoke-SiteGitOk $site @("commit", "-q", "-m", "Dead Drop Finance program download: $tag") | Out-Null
        } catch {
            # nothing half done: the file goes back as it was
            Invoke-SiteGit $site @("reset", "-q", "--", "index.html") | Out-Null
            [IO.File]::WriteAllText($index, $before, $utf8)
            throw
        }
        $did += "committed"
    }
    $ahead = "$(Invoke-SiteGitOk $site @('rev-list', '--count', '@{u}..HEAD'))".Trim()
    if ($ahead -ne "0") {
        Invoke-SiteGitOk $site @("push", "-q", "origin", "HEAD") | Out-Null
        $did += "$ahead commit(s) pushed to origin"
    }
    return ($did -join "; ")
}
