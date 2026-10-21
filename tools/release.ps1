# Publish a Dead Drop Finance release (see docs/RELEASING.md).
#   .\tools\release.ps1 -Version x.y.z
# In order, stopping at the first failure:
#   1. the working tree is clean, HEAD equals origin/main, gh is logged in as the owner, and
#      local.properties turns the release gates on (ddf.privacy.gate=true)
#   2. -Version equals ddf.versionName in gradle.properties; release-notes/vx.y.z.md exists, no em dash
#   3. tag vx.y.z exists neither on GitHub nor on origin
#   4. clean assembleRelease with every gate on (signing, privacy on tree / public set / APK, licenses)
#   5. apksigner: the release certificate is the app's own, versionName and versionCode match
#   6. DeadDropFinance.apk and DeadDropFinance.apk.sha256 are staged in a temp folder (never in the repo)
#   7. the public source repo gets exactly this commit's public file set (git archive HEAD), one commit
#      "Release vx.y.z" by the GitHub noreply identity with no trailers, privacy checked, pushed by URL
#      (skipped when the tree is unchanged); the private repo's github-public push URL stays disabled
#   8. a DRAFT release vx.y.z on that commit, with exactly the two assets
#   9. the draft is checked: exactly those two names, GitHub's digests and a downloaded copy match
#  10. published as latest, not a pre-release
#  11. the private commit is tagged vx.y.z, and the tag is pushed to origin only
#  12. the staging and temp folders are deleted
# Nothing here prints a token: gh uses its own login.
param(
    [Parameter(Mandatory = $true)][string]$Version
)
$ErrorActionPreference = "Stop"

$Repo = "Crazy8697/DeadDropFinance"
$PublicPushUrl = "git@github.com:$Repo.git"
$PublicCloneUrl = "https://github.com/$Repo.git"
$Owner = "Crazy8697"
$NoreplyName = "Crazy8697"
$NoreplyEmail = "261646346+Crazy8697@users.noreply.github.com"
$CertSha256 = "a93e9aa12b807633335bf28800b4d987fd1ad47c1a078ef095c2ac7feaf45ce0"
$ApkName = "DeadDropFinance.apk"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$EmDash = [string][char]0x2014

function Fail([string]$why) { throw "release: $why. Nothing more was done." }
function Step([string]$what) { Write-Host "== $what" }
function Run([string]$exe, [string[]]$argv) {
    & $exe @argv
    if ($LASTEXITCODE -ne 0) { Fail "$exe $($argv[0]) failed ($LASTEXITCODE)" }
}

$staging = Join-Path $env:TEMP ("ddf-release-$Version-" + [guid]::NewGuid().ToString("N").Substring(0, 8))
$public = Join-Path $env:TEMP ("ddf-public-$Version-" + [guid]::NewGuid().ToString("N").Substring(0, 8))
Push-Location $Root
try {
    # ---- 1. state ------------------------------------------------------------------------------------------
    Step "1. working tree, origin, gh login, gates"
    if ((git status --porcelain) -ne $null) { Fail "the working tree isn't clean" }
    Run git @("fetch", "-q", "origin")
    $head = (git rev-parse HEAD).Trim()
    if ($head -ne (git rev-parse origin/main).Trim()) { Fail "HEAD isn't origin/main (push to origin first)" }
    $login = (cmd /c "gh api user --jq .login 2>nul")
    if ($LASTEXITCODE -ne 0 -or "$login".Trim() -ne $Owner) { Fail "gh isn't logged in as $Owner" }
    $lp = Join-Path $Root "local.properties"
    if (-not (Test-Path $lp) -or -not (Select-String -Path $lp -Pattern '^\s*ddf\.privacy\.gate\s*=\s*true\s*$' -Quiet)) {
        Fail "local.properties doesn't have ddf.privacy.gate=true (the release gates would be off)"
    }

    # ---- 2. version and notes ------------------------------------------------------------------------------
    Step "2. version and release notes"
    if ($Version -notmatch '^\d+\.\d+\.\d+$') { Fail "-Version must look like 1.2.3" }
    $gp = Get-Content (Join-Path $Root "gradle.properties") | Where-Object { $_ -match '^ddf\.versionName=' }
    $ver = ($gp -replace '^ddf\.versionName=', '').Trim()
    if ($ver -ne $Version) { Fail "-Version $Version isn't ddf.versionName ($ver) in gradle.properties" }
    $parts = $ver.Split(".") | ForEach-Object { [int]$_ }
    $code = $parts[0] * 10000 + $parts[1] * 100 + $parts[2]
    $notes = Join-Path $Root "release-notes\v$ver.md"
    if (-not (Test-Path $notes)) { Fail "no release notes at release-notes\v$ver.md" }
    if ([IO.File]::ReadAllText($notes).Contains($EmDash)) { Fail "the release notes contain an em dash" }

    # ---- 3. the tag is new ---------------------------------------------------------------------------------
    Step "3. tag v$ver is new"
    cmd /c "gh api repos/$Repo/git/ref/tags/v$ver >nul 2>&1"
    if ($LASTEXITCODE -eq 0) { Fail "tag v$ver already exists on GitHub" }
    cmd /c "gh release view v$ver --repo $Repo >nul 2>&1"
    if ($LASTEXITCODE -eq 0) { Fail "release v$ver already exists on GitHub" }
    if (git ls-remote --tags origin "refs/tags/v$ver") { Fail "tag v$ver already exists on origin" }
    if (git tag --list "v$ver") { Fail "tag v$ver already exists locally" }

    # ---- 4. build with every gate on -----------------------------------------------------------------------
    Step "4. clean assembleRelease (signing, privacy and license gates)"
    Run (Join-Path $Root "gradlew.bat") @("--no-daemon", "--console=plain", "clean", "assembleRelease")
    $apk = Join-Path $Root "app\build\outputs\apk\release\app-release.apk"
    if (-not (Test-Path $apk)) { Fail "no release APK after the build" }

    # ---- 5. certificate and version ------------------------------------------------------------------------
    Step "5. certificate and version"
    $sdk = ((Get-Content $lp | Where-Object { $_ -match '^sdk\.dir=' }) -replace '^sdk\.dir=', '') -replace '\\:', ':' -replace '\\\\', '\'
    $bt = Get-ChildItem (Join-Path $sdk "build-tools") -Directory | Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
    $certs = (& (Join-Path $bt.FullName "apksigner.bat") verify --print-certs $apk) -join "`n"
    if ($LASTEXITCODE -ne 0) { Fail "apksigner verify failed" }
    # @(): with one match PowerShell would hand back a bare string, and [0] would be its first character
    $digests = @([regex]::Matches($certs, 'certificate SHA-256 digest: ([0-9a-f]{64})') | ForEach-Object { $_.Groups[1].Value } |
                 Select-Object -Unique)
    if ($digests.Count -ne 1 -or $digests[0] -ne $CertSha256) { Fail "the APK isn't signed with the app's release certificate" }
    $badging = (& (Join-Path $bt.FullName "aapt2.exe") dump badging $apk) -join "`n"
    if ($badging -notmatch "versionCode='$code' versionName='$([regex]::Escape($ver))'") {
        Fail "the APK isn't versionName $ver / versionCode $code"
    }

    # ---- 6. stage the two assets outside the repo ----------------------------------------------------------
    Step "6. stage the assets"
    New-Item -ItemType Directory -Force $staging | Out-Null
    $stApk = Join-Path $staging $ApkName
    Copy-Item $apk $stApk
    $hash = (Get-FileHash $stApk -Algorithm SHA256).Hash.ToLower()
    [IO.File]::WriteAllText("$stApk.sha256", "$hash $ApkName`n", (New-Object Text.ASCIIEncoding))
    $assets = @($stApk, "$stApk.sha256")

    # ---- 7. public source ----------------------------------------------------------------------------------
    Step "7. public source sync"
    Run git @("-c", "core.autocrlf=false", "clone", "-q", $PublicCloneUrl, $public)
    Run git @("-C", $public, "config", "core.autocrlf", "false")
    $tar = Join-Path $staging "public.tar"
    Run git @("-c", "core.autocrlf=false", "archive", "--format=tar", "-o", $tar, "HEAD")
    Run git @("-C", $public, "rm", "-r", "-q", "-f", "--ignore-unmatch", "--", ".")
    Run tar @("-xf", $tar, "-C", $public)
    Remove-Item $tar
    Run git @("-C", $public, "add", "-A")
    # file modes as in the private commit (Windows checkouts don't carry the executable bit)
    foreach ($line in (git ls-tree -r HEAD)) {
        if ($line -match '^100755 blob [0-9a-f]+\t(.+)$') {
            $p = $Matches[1]
            if (Test-Path (Join-Path $public $p)) { Run git @("-C", $public, "update-index", "--chmod=+x", "--", $p) }
        }
    }
    $msg = "Release v$ver"
    if ($msg -match 'Co-Authored-By|Claude|Anthropic') { Fail "the public commit message carries a trailer" }
    git -C $public diff --cached --quiet
    if ($LASTEXITCODE -eq 0) {
        Write-Host "Public source unchanged; no commit."
    } else {
        # the privacy check on exactly what goes public, with this machine's local values (gitignored there too)
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

    # ---- 8. draft release ----------------------------------------------------------------------------------
    Step "8. draft release v$ver"
    Run gh @("release", "create", "v$ver", $assets[0], "--repo", $Repo, "--target", $commit,
             "--title", "Dead Drop Finance $ver", "--notes-file", $notes, "--draft")
    Run gh @("release", "upload", "v$ver", $assets[1], "--repo", $Repo)

    # ---- 9. check the draft --------------------------------------------------------------------------------
    Step "9. check the draft"
    $want = @{}
    foreach ($a in $assets) {
        $want[(Split-Path $a -Leaf)] = "$((Get-Item $a).Length) sha256:$((Get-FileHash $a -Algorithm SHA256).Hash.ToLower())"
    }
    $draft = ((cmd /c "gh api repos/$Repo/releases?per_page=20 2>nul") -join "`n" | ConvertFrom-Json) |
             Where-Object { $_.draft -and $_.tag_name -eq "v$ver" } | Select-Object -First 1
    if (-not $draft) { Fail "the draft v$ver isn't on GitHub" }
    $seen = @{}
    foreach ($x in $draft.assets) { $seen[$x.name] = "$($x.size) $($x.digest)" }
    if ((($seen.Keys | Sort-Object) -join ",") -ne (($want.Keys | Sort-Object) -join ",")) {
        Fail "the draft's assets are '$(($seen.Keys | Sort-Object) -join ", ")', not exactly the two planned; it stays a draft"
    }
    foreach ($n in $want.Keys) {
        if ($seen[$n] -ne $want[$n]) { Fail "$n on GitHub doesn't match the local file; it stays a draft" }
    }
    $dl = Join-Path $staging "download"
    Run gh @("release", "download", "v$ver", "--repo", $Repo, "--pattern", $ApkName, "--dir", $dl)
    $got = (Get-FileHash (Join-Path $dl $ApkName) -Algorithm SHA256).Hash.ToLower()
    $listed = ([IO.File]::ReadAllText("$stApk.sha256").Split(" ")[0]).Trim()
    if ($got -ne $listed) { Fail "the downloaded APK doesn't match DeadDropFinance.apk.sha256; it stays a draft" }

    # ---- 10. publish ---------------------------------------------------------------------------------------
    Step "10. publish v$ver as latest"
    Run gh @("release", "edit", "v$ver", "--repo", $Repo, "--draft=false", "--prerelease=false", "--latest")
    Write-Host "Released: https://github.com/$Repo/releases/tag/v$ver"

    # ---- 11. private tag -----------------------------------------------------------------------------------
    Step "11. tag the private commit v$ver (origin only)"
    Run git @("tag", "v$ver", $head)
    git push -q origin "refs/tags/v$ver"
    if ($LASTEXITCODE -ne 0) { Write-Warning "Pushing tag v$ver to origin failed; the GitHub release is out. Push the tag by hand." }
} finally {
    Pop-Location
    # ---- 12. clean up --------------------------------------------------------------------------------------
    foreach ($d in @($staging, $public)) {
        if (Test-Path $d) { Remove-Item -Recurse -Force $d -ErrorAction SilentlyContinue }
    }
    Write-Host "Staging and temp folders removed: $(-not (Test-Path $staging) -and -not (Test-Path $public))"
}
