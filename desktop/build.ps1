# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 DarkRelay
# Build Dead Drop Finance for Windows (desktop/) and its installer. Run from Windows PowerShell on the build machine.
#   .\desktop\build.ps1                  clean, test and build the app image (desktop\build\compose\binaries\main\app\
#                                        DeadDropFinance: DeadDropFinance.exe, its jars and the bundled Java runtime);
#                                        the build's license gate refuses a component missing from THIRD-PARTY-NOTICES.md
#   .\desktop\build.ps1 -Setup           build, then package it with Inno Setup 6 (ISCC; a build tool only) as
#                                        DeadDropFinance-Setup.exe plus DeadDropFinance-Setup.exe.sha256
#                                        ("<sha256> DeadDropFinance-Setup.exe") in -SetupDir
#   .\desktop\build.ps1 -Setup -NoBuild  package the existing app image without rebuilding
#   .\desktop\build.ps1 -Setup -TestBuild
#                                        a TEST installer (DeadDropFinance-Test-Setup.exe, its own AppId, name and folder)
#   .\desktop\build.ps1 -Smoke           start the built program once with a throwaway %APPDATA%, off screen and unable
#                                        to take focus (--smoke-test), and check what it showed; nothing of the user's
#                                        settings is read or written
#   .\desktop\build.ps1 -PrivacyCheck    tools\privacy_check.py on the tree and the public set, and on the app image if built
#   .\desktop\build.ps1 -Release -Version x.y.z
#                                        publish desktop-vx.y.z. Refuses unless the tree is clean, HEAD is origin/main, gh
#                                        is logged in as the owner, x.y.z is ddf.desktop.versionName, the release notes
#                                        desktop\release-notes\desktop-vx.y.z.md exist without an em dash, and the tag is
#                                        new everywhere. Then, stopping at the first failure:
#                                          1. clean build and tests, the Setup and its .sha256 (outside the repo)
#                                          2. the privacy check on the tree, the public set and the app image
#                                          3. the public source (tools\release.ps1's way): exactly this commit's public
#                                             file set (git archive HEAD), one commit "Release desktop-vx.y.z" by the
#                                             GitHub noreply identity with no trailers, privacy checked, pushed by URL
#                                             (no commit when the tree is unchanged)
#                                          4. a DRAFT release desktop-vx.y.z on that commit, with exactly the two files,
#                                             checked: names, GitHub's digests, a downloaded copy against the .sha256
#                                          5. published NOT as latest (--latest=false), and releases/latest is checked to
#                                             still be a phone release, so the phone's update check never sees this one
#                                          6. the private commit is tagged desktop-vx.y.z, pushed to origin only
#                                          7. the staging and temp folders are deleted
#                                          8. only after all of that succeeded: the program's download link on the
#                                             front page of the darkrelay-site repo (next to this one) is moved to
#                                             releases/download/desktop-vx.y.z/DeadDropFinance-Setup.exe, committed
#                                             ("Dead Drop Finance program download: desktop-vx.y.z") and pushed to
#                                             the site's origin only, with any site commits made beforehand that
#                                             weren't pushed yet (a new card, for example); refused if the site has
#                                             uncommitted changes, is behind its upstream, or the link isn't there
#                                             exactly once (step 0 checks this too, so it stops the release first)
#                                          9. live check: https://darkrelay.net/ is polled (cache busted) for up to
#                                             10 minutes until it shows the new link, then the link must reach a 200.
#                                             A failure in 8 or 9 says so; the GitHub release itself stays done
# Nothing here prints a token: gh uses its own login.
param(
    [switch]$Setup,
    [switch]$NoBuild,
    [switch]$TestBuild,
    [switch]$Smoke,
    [switch]$PrivacyCheck,
    [switch]$Release,
    [string]$Version = "",
    [string]$SetupDir = (Join-Path $PSScriptRoot "build\installer")
)
$ErrorActionPreference = "Stop"

$Repo = "Crazy8697/DeadDropFinance"
$PublicPushUrl = "git@github.com:$Repo.git"
$PublicCloneUrl = "https://github.com/$Repo.git"
$Owner = "Crazy8697"
$NoreplyName = "Crazy8697"
$NoreplyEmail = "261646346+Crazy8697@users.noreply.github.com"
$SetupName = "DeadDropFinance-Setup.exe"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Desktop = $PSScriptRoot
$AppImage = Join-Path $Desktop "build\compose\binaries\main\app\DeadDropFinance"
$Iss = Join-Path $Desktop "installer\deaddropfinance.iss"
$EmDash = [string][char]0x2014
# The public site (darkrelay.net) repo, next to this one; its origin is the only remote it is pushed to.
$Site = Join-Path (Split-Path $Root -Parent) "darkrelay-site"
$SiteHrefRe = 'href="' + [regex]::Escape("https://github.com/$Repo/releases/download/desktop-v") + '\d+\.\d+\.\d+/' +
              [regex]::Escape($SetupName) + '"'

function Fail([string]$why) { throw "desktop build: $why. Nothing more was done." }
function Step([string]$what) { Write-Host "== $what" }
function Run([string]$exe, [string[]]$argv) {
    # (to the host: a function's own output stays its return value)
    & $exe @argv | Out-Host
    if ($LASTEXITCODE -ne 0) { Fail "$exe $($argv[0]) failed ($LASTEXITCODE)" }
}

function Get-DesktopVersion {
    $line = Get-Content (Join-Path $Root "gradle.properties") | Where-Object { $_ -match '^ddf\.desktop\.versionName=' }
    $v = ($line -replace '^ddf\.desktop\.versionName=', '').Trim()
    if ($v -notmatch '^\d+\.\d+\.\d+$') { Fail "ddf.desktop.versionName in gradle.properties isn't x.y.z ('$v')" }
    return $v
}

function Find-Iscc {
    foreach ($c in @($env:ISCC, (Join-Path ${env:ProgramFiles(x86)} "Inno Setup 6\ISCC.exe"),
                     (Join-Path $env:ProgramFiles "Inno Setup 6\ISCC.exe"))) {
        if ($c -and (Test-Path $c)) { return $c }
    }
    Fail "Inno Setup 6 (ISCC.exe) isn't installed"
}

function Invoke-Build {
    Step "clean, test and build the app image (license gate on)"
    Push-Location $Root
    try {
        Run (Join-Path $Root "gradlew.bat") @("--no-daemon", "--console=plain", ":desktop:clean", ":shared:jvmTest", ":desktop:test", ":desktop:createDistributable")
    } finally { Pop-Location }
    if (-not (Test-Path (Join-Path $AppImage "DeadDropFinance.exe"))) { Fail "no DeadDropFinance.exe after the build" }
}

function Invoke-Setup([string]$outDir, [switch]$Test) {
    $ver = Get-DesktopVersion
    if (-not (Test-Path (Join-Path $AppImage "DeadDropFinance.exe"))) { Fail "no app image to package (build first)" }
    New-Item -ItemType Directory -Force $outDir | Out-Null
    $base = if ($Test) { "DeadDropFinance-Test-Setup" } else { "DeadDropFinance-Setup" }
    $exe = Join-Path $outDir "$base.exe"
    foreach ($f in @($exe, "$exe.sha256")) { if (Test-Path $f) { Remove-Item -Force $f } }
    Step "ISCC $base.exe $ver"
    $isccArgs = @("/Q", "/DAppVersion=$ver", "/DDistDir=$AppImage", "/O$outDir")
    if ($Test) { $isccArgs += "/DTestBuild=1" }
    Run (Find-Iscc) ($isccArgs + @($Iss))
    if (-not (Test-Path $exe)) { Fail "ISCC made no $base.exe" }
    $hash = (Get-FileHash $exe -Algorithm SHA256).Hash.ToLower()
    [IO.File]::WriteAllText("$exe.sha256", "$hash $base.exe`n", (New-Object Text.ASCIIEncoding))
    Write-Host "Setup: $exe ($([math]::Round((Get-Item $exe).Length / 1MB, 1)) MB), sha256 $hash"
    return $exe
}

function Invoke-Privacy([string[]]$modes) {
    Push-Location $Root
    try {
        & python -B tools\privacy_check.py @modes | Out-Host
        return $LASTEXITCODE
    } finally { Pop-Location }
}

function Invoke-Smoke {
    $exe = Join-Path $AppImage "DeadDropFinance.exe"
    if (-not (Test-Path $exe)) { Fail "no app image to smoke test (build first)" }
    $tmp = Join-Path $env:TEMP ("ddf-desktop-smoke-" + [guid]::NewGuid().ToString("N").Substring(0, 8))
    $appData = Join-Path $tmp "AppData"
    New-Item -ItemType Directory -Force $appData | Out-Null
    $out = Join-Path $tmp "smoke.txt"
    $old = $env:APPDATA
    try {
        Step "smoke test (throwaway APPDATA, off screen, never focused)"
        $env:APPDATA = $appData
        $p = Start-Process -FilePath $exe -ArgumentList @("--smoke-test", "`"$out`"") -PassThru
        if (-not $p.WaitForExit(60000)) { $p.Kill(); Fail "the smoke test didn't finish within 60 s" }
        if (-not (Test-Path $out)) { Fail "the smoke test wrote no result" }
        $r = @{}
        foreach ($l in Get-Content $out) { $k, $v = $l -split '=', 2; $r[$k] = $v }
        $ver = Get-DesktopVersion
        $ok = $r["name"] -eq "Dead Drop Finance" -and $r["version"] -eq $ver -and $r["title"] -eq "Dead Drop Finance" -and
              $r["showing"] -eq "true" -and $r["focused"] -eq "false" -and $r["settingsDir"] -eq (Join-Path $appData "DeadDropFinance") -and
              $r["db"] -eq "ok" -and $r["dbSchema"] -eq "12" -and $r["dbFile"] -eq (Join-Path $appData "DeadDropFinance\budget.db") -and
              $r["dbEncrypted"] -eq "true"
        Get-Content $out | ForEach-Object { Write-Host "  $_" }
        $leftovers = @(Get-ChildItem -Recurse -File $AppImage | Where-Object { $_.LastWriteTime -gt $p.StartTime })
        if ($leftovers.Count) { Fail "the program wrote next to the exe: $($leftovers.FullName -join ', ')" }
        if (-not $ok) { Fail "the smoke test result isn't what the program should show" }
        Write-Host "Smoke test passed (exit $($p.ExitCode)); nothing written in the program folder."
    } finally {
        $env:APPDATA = $old
        Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
    }
}

# the site step's git work (0.12.1): desktop\tools\site_link.ps1, so git's stderr notes never count as failures
. (Join-Path $Desktop "tools\site_link.ps1")

function Assert-SiteOk {
    try { Assert-SiteReady $Site $SiteHrefRe } catch { Fail $_.Exception.Message }
}

if ($PrivacyCheck) {
    $modes = @("--tree", "--public")
    if (Test-Path $AppImage) { $modes += @("--desktop", $AppImage) }
    exit (Invoke-Privacy $modes)
}

if (-not $Release) {
    if (-not $NoBuild) { Invoke-Build }
    if ($Smoke) { Invoke-Smoke }
    if ($Setup) { Invoke-Setup $SetupDir -Test:$TestBuild | Out-Null }
    exit 0
}

# ---------------- release ----------------
$tag = "desktop-v$Version"
$staging = Join-Path $env:TEMP ("ddf-desktop-release-$Version-" + [guid]::NewGuid().ToString("N").Substring(0, 8))
$public = Join-Path $env:TEMP ("ddf-desktop-public-$Version-" + [guid]::NewGuid().ToString("N").Substring(0, 8))
Push-Location $Root
try {
    Step "0. working tree, origin, gh login, version, notes, tag"
    if ((git status --porcelain) -ne $null) { Fail "the working tree isn't clean" }
    Run git @("fetch", "-q", "origin")
    $head = (git rev-parse HEAD).Trim()
    if ($head -ne (git rev-parse origin/main).Trim()) { Fail "HEAD isn't origin/main (push to origin first)" }
    $login = (cmd /c "gh api user --jq .login 2>nul")
    if ($LASTEXITCODE -ne 0 -or "$login".Trim() -ne $Owner) { Fail "gh isn't logged in as $Owner" }
    if ($Version -notmatch '^\d+\.\d+\.\d+$') { Fail "-Version must look like 1.2.3" }
    $ver = Get-DesktopVersion
    if ($ver -ne $Version) { Fail "-Version $Version isn't ddf.desktop.versionName ($ver) in gradle.properties" }
    $notes = Join-Path $Desktop "release-notes\$tag.md"
    if (-not (Test-Path $notes)) { Fail "no release notes at desktop\release-notes\$tag.md" }
    if ([IO.File]::ReadAllText($notes).Contains($EmDash)) { Fail "the release notes contain an em dash" }
    cmd /c "gh api repos/$Repo/git/ref/tags/$tag >nul 2>&1"
    if ($LASTEXITCODE -eq 0) { Fail "tag $tag already exists on GitHub" }
    cmd /c "gh release view $tag --repo $Repo >nul 2>&1"
    if ($LASTEXITCODE -eq 0) { Fail "release $tag already exists on GitHub" }
    if (git ls-remote --tags origin "refs/tags/$tag") { Fail "tag $tag already exists on origin" }
    if (git tag --list $tag) { Fail "tag $tag already exists locally" }
    $latestBefore = ((cmd /c "gh api repos/$Repo/releases/latest --jq .tag_name 2>nul") -join "").Trim()
    if ($latestBefore -notmatch '^v\d+\.\d+\.\d+$') { Fail "releases/latest isn't a phone release now ('$latestBefore')" }
    Assert-SiteOk

    Step "1. build, tests, Setup"
    Invoke-Build
    $setupExe = Invoke-Setup $staging
    $assets = @($setupExe, "$setupExe.sha256")

    Step "2. privacy check: tree, public set, app image"
    if ((Invoke-Privacy @("--tree", "--public", "--desktop", $AppImage)) -ne 0) { Fail "the privacy check found something" }

    Step "3. public source sync"
    Run git @("-c", "core.autocrlf=false", "clone", "-q", $PublicCloneUrl, $public)
    Run git @("-C", $public, "config", "core.autocrlf", "false")
    $tar = Join-Path $staging "public.tar"
    Run git @("-c", "core.autocrlf=false", "archive", "--format=tar", "-o", $tar, "HEAD")
    Run git @("-C", $public, "rm", "-r", "-q", "-f", "--ignore-unmatch", "--", ".")
    Run tar @("-xf", $tar, "-C", $public)
    Remove-Item $tar
    Run git @("-C", $public, "add", "-A")
    foreach ($line in (git ls-tree -r HEAD)) {
        if ($line -match '^100755 blob [0-9a-f]+\t(.+)$') {
            $p = $Matches[1]
            if (Test-Path (Join-Path $public $p)) { Run git @("-C", $public, "update-index", "--chmod=+x", "--", $p) }
        }
    }
    $msg = "Release $tag"
    git -C $public diff --cached --quiet
    if ($LASTEXITCODE -eq 0) {
        Write-Host "Public source unchanged; no commit."
    } else {
        $local = Join-Path $Root "tools\privacy-local.txt"
        if (Test-Path $local) { Copy-Item $local (Join-Path $public "tools\privacy-local.txt") }
        try {
            Push-Location $public
            & python -B tools\privacy_check.py --tree
            $pc = $LASTEXITCODE
        } finally {
            Pop-Location
            Remove-Item -Force -ErrorAction SilentlyContinue (Join-Path $public "tools\privacy-local.txt")
        }
        if ($pc -ne 0) { Fail "the privacy check found something in the public source" }
        Run git @("-C", $public, "-c", "user.name=$NoreplyName", "-c", "user.email=$NoreplyEmail", "commit", "-q", "-m", $msg)
        $body = (git -C $public log -1 --format=%B) -join "`n"
        if ($body -match 'Co-Authored-By|Claude|Anthropic') { Fail "the public commit carries a trailer" }
        Run git @("-C", $public, "push", "-q", $PublicPushUrl, "HEAD:refs/heads/main")
    }
    $commit = (git -C $public rev-parse HEAD).Trim()
    Write-Host "Public source commit: $commit"

    Step "4. draft release $tag, checked"
    Run gh @("release", "create", $tag, $assets[0], "--repo", $Repo, "--target", $commit,
             "--title", "Dead Drop Finance for Windows $ver", "--notes-file", $notes, "--draft", "--latest=false")
    Run gh @("release", "upload", $tag, $assets[1], "--repo", $Repo)
    $want = @{}
    foreach ($a in $assets) {
        $want[(Split-Path $a -Leaf)] = "$((Get-Item $a).Length) sha256:$((Get-FileHash $a -Algorithm SHA256).Hash.ToLower())"
    }
    $draft = ((cmd /c "gh api repos/$Repo/releases?per_page=30 2>nul") -join "`n" | ConvertFrom-Json) |
             Where-Object { $_.draft -and $_.tag_name -eq $tag } | Select-Object -First 1
    if (-not $draft) { Fail "the draft $tag isn't on GitHub" }
    $seen = @{}
    foreach ($x in $draft.assets) { $seen[$x.name] = "$($x.size) $($x.digest)" }
    if ((($seen.Keys | Sort-Object) -join ",") -ne (($want.Keys | Sort-Object) -join ",")) {
        Fail "the draft's assets are '$(($seen.Keys | Sort-Object) -join ", ")', not exactly the two planned; it stays a draft"
    }
    foreach ($n in $want.Keys) {
        if ($seen[$n] -ne $want[$n]) { Fail "$n on GitHub doesn't match the local file; it stays a draft" }
    }
    $dl = Join-Path $staging "download"
    Run gh @("release", "download", $tag, "--repo", $Repo, "--pattern", $SetupName, "--dir", $dl)
    $got = (Get-FileHash (Join-Path $dl $SetupName) -Algorithm SHA256).Hash.ToLower()
    $listed = ([IO.File]::ReadAllText("$setupExe.sha256").Split(" ")[0]).Trim()
    if ($got -ne $listed) { Fail "the downloaded Setup doesn't match $SetupName.sha256; it stays a draft" }

    Step "5. publish $tag, NOT as latest"
    Run gh @("release", "edit", $tag, "--repo", $Repo, "--draft=false", "--prerelease=false", "--latest=false")
    $latestAfter = ((cmd /c "gh api repos/$Repo/releases/latest --jq .tag_name 2>nul") -join "").Trim()
    if ($latestAfter -ne $latestBefore) {
        Write-Warning "releases/latest is now '$latestAfter', was '$latestBefore'. Fix it by hand: gh release edit $latestBefore --repo $Repo --latest"
        Fail "releases/latest moved off the phone release"
    }
    Write-Host "Released: https://github.com/$Repo/releases/tag/$tag (releases/latest is still $latestAfter)"

    Step "6. tag the private commit $tag (origin only)"
    Run git @("tag", $tag, $head)
    git push -q origin "refs/tags/$tag"
    if ($LASTEXITCODE -ne 0) { Write-Warning "Pushing tag $tag to origin failed; the GitHub release is out. Push the tag by hand." }
} finally {
    Pop-Location
    foreach ($d in @($staging, $public)) {
        if (Test-Path $d) { Remove-Item -Recurse -Force $d -ErrorAction SilentlyContinue }
    }
    Write-Host "Staging and temp folders removed: $(-not (Test-Path $staging) -and -not (Test-Path $public))"
}

# ---------------- site link ----------------
# Only reached when every release step above succeeded (Fail throws). From here on the release stays done
# whatever happens; a failure only means the site still links the previous program version.
$siteHref = "https://github.com/$Repo/releases/download/$tag/$SetupName"
try {
    Step "8. darkrelay.net: the program's download link to $tag (site origin only)"
    $did = try { Update-SiteLink $Site $SiteHrefRe $siteHref $tag } catch { Fail $_.Exception.Message }
    Write-Host "darkrelay-site: $did."

    Step "9. live check: darkrelay.net shows the $tag link, and the link resolves"
    $deadline = (Get-Date).AddMinutes(10)
    $live = $false
    while ((Get-Date) -lt $deadline) {
        $page = (& curl.exe -s -H "Cache-Control: no-cache" "https://darkrelay.net/?ddf=$([DateTime]::UtcNow.Ticks)") -join "`n"
        if ($page.Contains("href=`"$siteHref`"")) { $live = $true; break }
        Start-Sleep -Seconds 15
    }
    if (-not $live) { Fail "darkrelay.net still doesn't show the $tag link after 10 minutes (the site commit is pushed; Cloudflare Pages may still be building). Check https://darkrelay.net/ by hand" }
    $code = "$(& curl.exe -s -I -L -o NUL -w '%{http_code}' $siteHref)".Trim()
    if ($code -ne "200") { Fail "darkrelay.net shows the $tag link, but $siteHref answers $code, not 200" }
    Write-Host "darkrelay.net links $tag and the link answers 200: $siteHref"
} catch {
    throw "The release $tag is out and stays done; only the site link step failed. $($_.Exception.Message)"
}
