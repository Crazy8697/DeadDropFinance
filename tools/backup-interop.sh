#!/bin/bash
# Backups between the phone and the program, both directions, with receipt photos and without (1.8.1 / 0.14.0).
# The phone's backup code runs under Robolectric and the program's on the desktop, so the halves hand files over in a
# temporary folder: the phone writes, the program restores them and writes its own, the phone restores those.
# Invented data and a test recovery key made for the run; the folder is removed at the end.
cd "$(dirname "$0")/.."
export DDF_INTEROP_DIR=$(mktemp -d)
trap 'rm -rf "$DDF_INTEROP_DIR"' EXIT
DDF_INTEROP_STAGE=phone-write ./gradlew --console=plain -q :app:testDebugUnitTest --tests "*backup.BackupInteropTest*" --rerun || exit 1
DDF_INTEROP_STAGE=program ./gradlew --console=plain -q :desktop:test --tests "*BackupInteropTest*" --rerun || exit 1
DDF_INTEROP_STAGE=phone-read ./gradlew --console=plain -q :app:testDebugUnitTest --tests "*backup.BackupInteropTest*" --rerun || exit 1
echo "backup interop: phone to program and program to phone, with photos and without: passed"
