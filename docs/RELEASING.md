# Releasing

Releases are published by one script, `tools/release.ps1`, run from the project's private working copy on the
build machine (Windows PowerShell, with the GitHub CLI logged in as the owner). Nothing is published by hand.

```
.\tools\release.ps1 -Version x.y.z
```

Before running it: set `ddf.versionName=x.y.z` in `gradle.properties`, write `release-notes/vx.y.z.md`, commit,
and push to the private origin.

## What the script does

It stops at the first thing that is wrong, before anything is published:

1. The working tree is clean, HEAD is the private origin's `main`, the GitHub CLI is logged in as the owner, and
   `local.properties` turns the release gates on (`ddf.privacy.gate=true`).
2. `-Version` is the version in `gradle.properties`, and `release-notes/vx.y.z.md` exists without an em dash.
3. Tag `vx.y.z` exists neither on GitHub nor on the private origin.
4. A clean `assembleRelease` with every gate on: release signing, `tools/privacy_check.py` on the tree, the public
   set and the APK, and the license check against `THIRD-PARTY-NOTICES.md`.
5. `apksigner` confirms the APK is signed with the app's release certificate (see `docs/SIGNING.md`) and carries
   the right versionName and versionCode.
6. The two release files, `DeadDropFinance.apk` and `DeadDropFinance.apk.sha256` (`<sha256> DeadDropFinance.apk`),
   are staged in a temporary folder outside the repo.
7. The public source repo gets exactly the public file set of this commit (`git archive HEAD`, which leaves out
   everything marked `export-ignore`), with LF line endings and file modes kept. It is committed as
   "Release vx.y.z" under the owner's GitHub noreply address with no trailers, privacy checked, and pushed. If the
   source didn't change, there is no commit. The private history never goes public.
8. A draft release `vx.y.z` is created on that commit, with exactly the two files.
9. The draft is checked: exactly those two names, GitHub's digests equal the local files, and a downloaded copy of
   the APK matches the `.sha256` file.
10. The draft is published as the latest release, not a pre-release.
11. The private commit is tagged `vx.y.z`, and the tag goes to the private origin only.
12. The temporary folders are deleted.

A published release, its files, notes and tag are never edited or deleted. A mistake is fixed with the next
version.

## The update check

The app asks GitHub for the latest release and offers it only if it is newer than the installed version, is not a
draft or a pre-release, and carries both release files. The download link is always
`releases/latest/download/DeadDropFinance.apk`, so the two file names never change.
