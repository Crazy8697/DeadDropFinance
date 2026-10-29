# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 DarkRelay
"""Refuse to publish if Dead Drop Finance carries something private: a personal user path, a home-network or
tailnet address, a home-lab hostname, an e-mail address, a token or key, a filled-in secret or keystore line, a
secret matching a fingerprint, content copied out of the private docs/mockups folder, or an em dash. Ported from
the checker in DarkRelay's Windows programs, adapted for an Android project.

    python tools/privacy_check.py --tree          the working tree: tracked files plus new, not ignored files
                                                  (the default when no mode is given)
    python tools/privacy_check.py --public        the public set: exactly what `git archive HEAD` produces, so
                                                  export-ignore applies; any docs/mockups file in it is a finding
    python tools/privacy_check.py --apk <path>    an APK: unpacked to a temp folder (manifest, resources, assets,
                                                  the strings in classes*.dex), plus aapt2's decoded manifest and
                                                  resources; the temp folder is deleted afterwards
    python tools/privacy_check.py --all           --tree, --public and --apk on the release APK in
                                                  app/build/outputs/apk/release
    python tools/privacy_check.py --desktop <dir> the desktop program's built app image (desktop/build.ps1): every
                                                  file, every entry of every jar, the class files' strings, the
                                                  native DLLs and the bundled Java runtime
    python tools/privacy_check.py --root <dir>    check another copy of the tree (tests)

Exit 1 and a list of file:line (or file: for binaries) if anything is found.

The patterns are generic, so nothing private is written here. Values no generic pattern can catch go in
tools/privacy-local.txt, which is gitignored and never leaves this PC, one per line:
    plain text        matches anywhere, ignoring case
    case:Word         matches only as a whole word with the exact case
    sha256:<hex>      the SHA-256 of a secret (a password, a key file); any word or file with that hash is a
                      finding, so a leak is caught without the secret itself being stored here

docs/mockups is private by design (export-ignored). In --tree mode it is still scanned for secrets, but its
personal-term and other non-secret hits are listed as "private-only, export-ignored" info lines, not findings.
Binary files are skipped for text patterns (but still checked by fingerprint); license texts quote their
authors' addresses, so those are skipped too."""
import hashlib
import io
import os
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
LOCAL_NAME = "tools/privacy-local.txt"
PRIVATE_PREFIX = "docs/mockups/"
DESKTOP_PRIVATE_PREFIX = "desktop/docs/"

# public values that may appear: the release certificate's SHA-256 is printed in docs/SIGNING.md on purpose
PUBLIC_FINGERPRINTS = {"a93e9aa12b807633335bf28800b4d987fd1ad47c1a078ef095c2ac7feaf45ce0"}
# e-mail domains and addresses that may appear
EMAIL_ALLOW_DOMAINS = ["users.noreply.github.com", "example.com", "example.org"]
# (git@github.com is GitHub's SSH remote address, used by tools/release.ps1, not a mailbox; support@darkrelay.net is
# the app's published contact address, in PRIVACY.md, TERMS.md and the in-app copies, from 0.15.1)
EMAIL_ALLOW_ADDRESSES = ["git@github.com", "support@darkrelay.net"]

# build service accounts (CI runners, and JitPack's, whose paths Tesseract4Android's native library keeps) aren't people
USER_PATH = ("a personal user path",
             re.compile(r"[A-Za-z]:[\\/]+Users[\\/]+(?!Public\b|Default\b|<|%|\$|\{)[A-Za-z0-9_.-]+"
                        r"|/(?:home|Users)/(?!runner\b|builder\b|jitpack\b|<|\$|\{)[a-z][a-z0-9_.-]+", re.I))
PRIVATE_IP = ("a private network address",
              re.compile(r"(?<!Version: )(?<![\d.])(?:10\.\d{1,3}|192\.168|172\.(?:1[6-9]|2\d|3[01]))"
                         r"\.\d{1,3}\.\d{1,3}(?![\d.])"))
TAILNET = ("a tailnet name or address",
           re.compile(r"\btailscale\b|\.ts\.net\b|(?<![\d.])100\.(?:6[4-9]|[7-9]\d|1[01]\d|12[0-7])"
                      r"\.\d{1,3}\.\d{1,3}(?![\d.])", re.I))
# any host under the project's domain is private infrastructure (the public site is the bare domain); the names of
# private machines themselves live in the gitignored privacy-local.txt, not here
HOMELAB = ("a home-lab hostname", re.compile(r"[a-z0-9-]+\.darkrelay\.net", re.I))
TOKEN = ("a token", re.compile(r"\bgh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}"
                               r"|\beyJ[A-Za-z0-9_-]{20,}\.eyJ|\bAKIA[0-9A-Z]{16}\b|\bAIza[0-9A-Za-z_-]{35}\b"
                               # Teller access tokens (live and sandbox)
                               r"|\b(?:test_)?token_[A-Za-z0-9]{16,}\b"))
# a web address with a login in it, like a SimpleFIN access URL (template variables like $user are code, not values)
CRED_URL = ("a web address with a login in it",
            re.compile(r"\bhttps?://(?![$<{%])[^\s/:@\"']+:(?![$<{%])[^\s/@\"']+@[A-Za-z0-9.-]+"))
KEY = ("a private key", re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----"))
EMAIL = ("an e-mail address",
         re.compile(r"\b(?!(?:" + "|".join(re.escape(a) for a in EMAIL_ALLOW_ADDRESSES or ["\0"]) + r")\b)"
                    r"[A-Za-z0-9._%+-]+@(?!(?:" + "|".join(re.escape(d) for d in EMAIL_ALLOW_DOMAINS) + r")\b)"
                    r"[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}\b"))
# key = value with a real-looking value (not empty, not a placeholder, not a variable reference, not code that
# reads the value from somewhere else, like props.getProperty("storePassword"))
SECRET = ("a filled-in secret",
          re.compile(r"(?i)\b(?:password|passwd|secret|api[_-]?key|token)\b"
                     r"\s*[=:]\s*(?![\"']?\s*(?:$|<|\$|\{|%|changeme\b|xxx|\*\*\*|null\b|none\b|\"\"|''))"
                     r"(?![A-Za-z_][\w.]*\()"
                     r"[\"']?[^\s\"'<>]{6,}"))
# a keystore.properties line with a value: storePassword, keyPassword, storeFile or keyAlias set to something
KEYSTORE = ("a keystore.properties line with a value",
            re.compile(r"\b(?:storePassword|keyPassword|storeFile|keyAlias)\s*[=:]\s*"
                       r"(?![A-Za-z_][\w.]*\()(?![\"']?\s*(?:$|<|\$|\{))[\"']?[^\s\"'<>]+"))
ADB = ("an adb device address or serial",
       re.compile(r"\badb(?:\.exe)?\s+(?:-s|connect)\s+(?!<|\$|\{)\S+|(?<![\d.])\d{1,3}(?:\.\d{1,3}){3}:\d{4,5}\b"))
FINGERPRINT = ("a SHA-256 fingerprint value",
               re.compile(r"(?<![0-9A-Fa-f:])(?:[0-9A-Fa-f]{64}|(?:[0-9A-Fa-f]{2}:){31}[0-9A-Fa-f]{2})(?![0-9A-Fa-f:])"))
EMDASH = ("an em dash", re.compile(chr(0x2014)))
# filled lines in an .env file (any NAME=value that isn't empty or a placeholder)
ENV_LINE = ("a filled-in .env line",
            re.compile(r"^\s*(?:export\s+)?[A-Za-z_][A-Za-z0-9_]*\s*=\s*(?![\"']?\s*(?:$|<|\$\{|changeme\b))\S"))

TREE_PATTERNS = [USER_PATH, PRIVATE_IP, TAILNET, HOMELAB, TOKEN, CRED_URL, KEY, EMAIL, SECRET, KEYSTORE, ADB, FINGERPRINT,
                 EMDASH]
# the APK also carries AndroidX, Compose and the Kotlin standard library; their strings are not ours, so only the
# patterns that could only be ours are used there: no key=value or fingerprint-shape rules, and no em dash (the
# sources are checked for those; library translations carry them)
APK_PATTERNS = [USER_PATH, PRIVATE_IP, TAILNET, HOMELAB, TOKEN, CRED_URL, KEY, EMAIL, ADB]
# raw binary entries (resources.arsc, binary XML, native code): byte noise can look like an address, so no e-mail
# rule there; their strings are read decoded through aapt2 instead
APK_BINARY_PATTERNS = [p for p in APK_PATTERNS if p is not EMAIL]
# hits of these kinds are findings even inside docs/mockups; everything else there is private-only info
SECRET_KINDS = {TOKEN[0], KEY[0], SECRET[0], KEYSTORE[0], FINGERPRINT[0], ENV_LINE[0],
                "a secret matching a fingerprint in privacy-local.txt", "a file matching a fingerprint in privacy-local.txt"}
SKIP_SUFFIX = {".png", ".jpg", ".jpeg", ".webp", ".ico", ".ttf", ".pdf", ".jar", ".p12", ".jks", ".keystore"}
SKIP_PREFIX = ("licenses/", "LICENSE", "app/src/main/assets/licenses/", "desktop/licenses/")   # verbatim license texts
WORD = re.compile(r"[^\s\"'`=:,;()<>\[\]{}|]{8,}")


class Local:
    """What tools/privacy-local.txt holds: extra patterns and SHA-256 fingerprints."""

    def __init__(self, root):
        self.patterns, self.fingerprints = [], set()
        f = root / LOCAL_NAME
        if not f.is_file():
            return
        for ln in f.read_text(encoding="utf-8").splitlines():
            v = ln.strip()
            if not v or v.startswith("#"):
                continue
            if v.lower().startswith("sha256:"):
                self.fingerprints.add(v[7:].strip().lower())
            elif v.startswith("case:"):
                self.patterns.append(("a private value from privacy-local.txt",
                                      re.compile(r"(?<![A-Za-z0-9_])" + re.escape(v[5:].strip()) + r"(?![A-Za-z0-9_])")))
            else:
                self.patterns.append(("a private value from privacy-local.txt", re.compile(re.escape(v), re.I)))

    def secret_words(self, text):
        if not self.fingerprints:
            return False
        return any(hashlib.sha256(w.encode("utf-8")).hexdigest() in self.fingerprints for w in WORD.findall(text))

    def secret_file(self, data):
        return bool(self.fingerprints) and hashlib.sha256(data).hexdigest() in self.fingerprints


# lines whose SHA-256 is a public checksum by design: the Gradle wrapper pins the published distribution hash
PUBLIC_CHECKSUM_LINE = re.compile(r"^\s*distributionSha256Sum\s*=")


def fingerprint_ok(line):
    """True if every fingerprint-shaped value on the line is a known public one."""
    if PUBLIC_CHECKSUM_LINE.match(line):
        return True
    for m in FINGERPRINT[1].finditer(line):
        if m.group(0).replace(":", "").lower() not in PUBLIC_FINGERPRINTS:
            return False
    return True


def git_lines(root, *args):
    out = subprocess.run(["git", *args], cwd=root, capture_output=True, check=True).stdout
    return [p for p in out.decode("utf-8").split("\0") if p]


def tree_files(root):
    try:
        return git_lines(root, "ls-files", "-z", "--cached", "--others", "--exclude-standard", ".")
    except (subprocess.CalledProcessError, OSError):
        # not a git checkout (a test copy): every file except build output
        skip = {".git", ".gradle", ".kotlin", "build", "__pycache__", ".idea"}
        files = []
        for d, dirs, names in os.walk(root):
            dirs[:] = [x for x in dirs if x not in skip]
            files += [os.path.relpath(os.path.join(d, n), root).replace("\\", "/") for n in names]
        return files


def mockup_lines(blobs):
    """Hashes of the distinctive lines of the docs/mockups files, to spot their content copied elsewhere."""
    out = set()
    for data in blobs:
        for line in data.decode("utf-8", "replace").splitlines():
            s = line.strip()
            if len(s) >= 40:
                out.add(hashlib.sha256(s.encode("utf-8")).hexdigest())
    return out


class Scan:
    def __init__(self, local, mock_hashes, mock_files):
        self.local, self.mock_hashes, self.mock_files = local, mock_hashes, mock_files
        self.found, self.info = [], []

    def text(self, rel, data, patterns, private=False):
        """One text file: every pattern, line by line."""
        try:
            text = data.decode("utf-8")
        except UnicodeDecodeError:
            return
        is_env = Path(rel).name == ".env" or Path(rel).name.startswith(".env.") or rel.endswith(".env")
        is_env = is_env and not rel.endswith(".env.example")
        hits = []
        copied = 0
        for n, line in enumerate(text.splitlines(), 1):
            for what, rx in patterns + self.local.patterns:
                if rx.search(line):
                    if what == FINGERPRINT[0] and fingerprint_ok(line):
                        continue
                    hits.append((n, what))
            if is_env and ENV_LINE[1].search(line):
                hits.append((n, ENV_LINE[0]))
            if self.local.secret_words(line):
                hits.append((n, "a secret matching a fingerprint in privacy-local.txt"))
            s = line.strip()
            if not private and len(s) >= 40 and hashlib.sha256(s.encode("utf-8")).hexdigest() in self.mock_hashes:
                copied += 1
        if not private and copied >= 3:
            hits.append((0, f"content copied from {PRIVATE_PREFIX} ({copied} lines)"))
        for n, what in hits:
            where = f"{rel}:{n}" if n else rel
            if private and what not in SECRET_KINDS:
                self.info.append(f"{where}: {what} (private-only, export-ignored)")
            else:
                self.found.append(f"{where}: {what}")

    def file(self, rel, data, patterns):
        private = rel.startswith(PRIVATE_PREFIX)
        if self.local.secret_file(data):
            self.found.append(f"{rel}: a file matching a fingerprint in privacy-local.txt")
        if not private and hashlib.sha256(data).hexdigest() in self.mock_files:
            self.found.append(f"{rel}: a copy of a file from {PRIVATE_PREFIX}")
        if Path(rel).suffix.lower() in SKIP_SUFFIX or rel.startswith(SKIP_PREFIX) or rel == LOCAL_NAME:
            return
        self.text(rel, data, patterns, private)


def read_mockups(root, files):
    blobs = []
    for rel in files:
        if rel.startswith(PRIVATE_PREFIX) and (root / rel).is_file():
            blobs.append((root / rel).read_bytes())
    return mockup_lines(blobs), {hashlib.sha256(b).hexdigest() for b in blobs}


def check_tree(root, local):
    files = tree_files(root)
    mh, mf = read_mockups(root, files)
    sc = Scan(local, mh, mf)
    for rel in files:
        p = root / rel
        if p.is_file():
            sc.file(rel, p.read_bytes(), TREE_PATTERNS)
    return sc, len(files)


def check_public(root, local):
    """Exactly the files `git archive HEAD` would publish (committed content, export-ignore applied)."""
    tar = subprocess.run(["git", "archive", "--format=tar", "HEAD"], cwd=root, capture_output=True, check=True).stdout
    members = []
    with tarfile.open(fileobj=io.BytesIO(tar)) as t:
        for m in t.getmembers():
            if m.isfile():
                members.append((m.name, t.extractfile(m).read()))
            elif m.name.rstrip("/").startswith(PRIVATE_PREFIX.rstrip("/")):
                members.append((m.name, None))
    mh, mf = read_mockups(root, tree_files(root))
    sc = Scan(local, mh, mf)
    for rel, data in members:
        if rel.startswith(PRIVATE_PREFIX) or rel.rstrip("/") == PRIVATE_PREFIX.rstrip("/"):
            sc.found.append(f"{rel}: a private {PRIVATE_PREFIX} file in the public set (export-ignore missing?)")
            continue
        # the desktop program's mockup (the spec for its screens) is private too, export-ignored like docs/mockups
        if rel.startswith(DESKTOP_PRIVATE_PREFIX):
            sc.found.append(f"{rel}: a private {DESKTOP_PRIVATE_PREFIX} file in the public set (export-ignore missing?)")
            continue
        sc.file(rel, data, TREE_PATTERNS)
    return sc, len([m for m in members if m[1] is not None])


def aapt2(root):
    """aapt2 from the SDK in local.properties or ANDROID_HOME, newest build-tools first."""
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    lp = root / "local.properties"
    if lp.is_file():
        m = re.search(r"^sdk\.dir=(.+)$", lp.read_text(encoding="utf-8"), re.M)
        if m:
            sdk = m.group(1).strip().replace("\\:", ":").replace("\\\\", "\\")
    if not sdk:
        return None
    for d in sorted((Path(sdk) / "build-tools").glob("*"), reverse=True):
        for name in ("aapt2.exe", "aapt2"):
            if (d / name).is_file():
                return d / name
    return None


def dex_strings(data):
    """Every string in a dex file's string table (MUTF-8, read as UTF-8), so code bytes are never scanned."""
    out = []
    if len(data) < 0x70 or not data.startswith(b"dex\n"):
        return out
    size = int.from_bytes(data[0x38:0x3C], "little")
    off = int.from_bytes(data[0x3C:0x40], "little")
    for i in range(size):
        pos = int.from_bytes(data[off + 4 * i:off + 4 * i + 4], "little")
        while pos < len(data) and data[pos] & 0x80:      # skip the ULEB128 length
            pos += 1
        pos += 1
        end = data.find(b"\x00", pos)
        if end < 0:
            break
        out.append(data[pos:end].decode("utf-8", "replace"))
    return out


def is_tld_list(s):
    """AndroidX carries the IANA top-level domain list as one long "|"-separated string; a privacy-local.txt word
    that is also a generic top-level domain matches inside it. Such a string is third-party, never ours."""
    return s.count("|") > 300 and "|com|" in s and "|org|" in s


# third-party build paths inside the APK's native libraries, allowed one by one: SQLCipher's libsqlcipher.so keeps
# the source paths of its vendor's own build machine (libtomcrypt files under a "sqlcipher-packages" source tree), and
# JNA's libjnidispatch.so (1.4.0) keeps the output path of its maintainer's Android build ("jnalib/build/native-android-")
APK_VENDOR_BUILD_PATHS = [re.compile(r"/Users/[^/\s]+/src/sqlcipher-packages/[^\s\x00]*"),
                          re.compile(r"/home/[^/\s]+/src/jnalib/build/native-android-[^\s\x00]*")]


def scan_apk_strings(sc, where, strings, patterns, local):
    for s in strings:
        for v in APK_VENDOR_BUILD_PATHS:
            s = v.sub("", s)
        for what, rx in patterns + local.patterns:
            if rx.search(s):
                if (what, rx) in local.patterns and is_tld_list(s):
                    continue
                sc.found.append(f"{where}: {what}")
        if local.secret_words(s):
            sc.found.append(f"{where}: a secret matching a fingerprint in privacy-local.txt")


def check_apk(apk, root, local):
    sc = Scan(local, set(), set())
    label = apk.name
    tmp = Path(tempfile.mkdtemp(prefix="ddf-privacy-apk-"))
    n = 0
    try:
        with zipfile.ZipFile(apk) as z:
            z.extractall(tmp)
        for p in sorted(tmp.rglob("*")):
            if not p.is_file():
                continue
            n += 1
            rel = p.relative_to(tmp).as_posix()
            data = p.read_bytes()
            if local.secret_file(data):
                sc.found.append(f"{label}!{rel}: a file matching a fingerprint in privacy-local.txt")
            if rel.endswith(".dex"):
                scan_apk_strings(sc, f"{label}!{rel}", dex_strings(data), APK_PATTERNS, local)
                continue
            try:
                scan_apk_strings(sc, f"{label}!{rel}", data.decode("utf-8").splitlines(), APK_PATTERNS, local)
                continue
            except UnicodeDecodeError:
                pass
            # binary (manifest, resources.arsc, res/ XML keep strings as UTF-16LE): raw and NUL-folded
            for text in (data.decode("latin-1"), data.replace(b"\x00", b"").decode("latin-1")):
                scan_apk_strings(sc, f"{label}!{rel}", [text], APK_BINARY_PATTERNS, local)
        tool = aapt2(root)
        if tool:
            for what in (["dump", "xmltree", "--file", "AndroidManifest.xml"], ["dump", "resources"],
                         ["dump", "strings"], ["dump", "badging"]):
                r = subprocess.run([str(tool), *what, str(apk)], capture_output=True,
                                   creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
                n += 1
                scan_apk_strings(sc, f"{label} (aapt2 {what[1]})", r.stdout.decode("utf-8", "replace").splitlines(),
                                 APK_PATTERNS, local)
        else:
            print("privacy check: aapt2 not found, decoded resources not scanned")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    seen, out = set(), []
    for f in sc.found:
        if f not in seen:
            seen.add(f)
            out.append(f)
    sc.found = out
    return sc, n


# The desktop app image (desktop/build.ps1) is mostly third-party: Compose, Skia and the Java runtime, whose strings
# carry their own authors' addresses, sample IPs and PEM header names. Our own code is the desktop module's jar
# (desktop-<hash>.jar): its entries get every APK pattern. Everything else gets only what could only be ours: a
# personal user path, a home-lab hostname or tailnet name, a token, a real private key, a login in a web address, and
# the privacy-local.txt values and fingerprints.
DESKTOP_OWN_JAR = re.compile(r"^desktop-[0-9a-f]{16,}\.jar$")
DESKTOP_KEY = ("a private key", re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----\s+[A-Za-z0-9+/=]{40,}"))
DESKTOP_THIRD_PARTY = [USER_PATH, HOMELAB, TAILNET, TOKEN, CRED_URL, DESKTOP_KEY]
# third-party strings in the app image, allowed one by one: Compose's preview device list names Google's older phones
# and tablets; the JDK's jaxp.properties shows an example catalog path, and its java.xml module keeps a Sun test
# path; the runtime's legal folder holds the vendors' license notices verbatim (author lists), like licenses/.
# (Written with [x] character classes, so these lines don't match the patterns they allow.)
DESKTOP_VENDOR_STRINGS = [re.compile(r"(?i)\bN[e]xus[ _](?:One|S|4|5X?|6P?|7(?:_2013)?|9|10)\b|id:N[e]xus[ _]\w+"),
                          re.compile(r"file:///[u]sers/auser/"),
                          re.compile(r"/space/[h]ome/stax/"),
                          # JNA's native libraries keep their maintainers' build paths (AIX and LoongArch builds)
                          re.compile(r"/[h]ome/matthiasblaesing/build-package-[^\s\x00]*"),
                          re.compile(r"/[h]ome/panxuefeng/vm-infra/src/jna/[^\s\x00]*")]
DESKTOP_SKIP = ("DeadDropFinance/runtime/legal/",)


def check_desktop(app, local):
    sc = Scan(local, set(), set())
    n = 0

    def blob(where, data, patterns):
        if local.secret_file(data):
            sc.found.append(f"{where}: a file matching a fingerprint in privacy-local.txt")
        # ASCII/UTF-8 strings, and UTF-16LE (Windows resources) folded to ASCII
        for text in (data.decode("latin-1"), data.replace(b"\x00", b"").decode("latin-1")):
            for v in DESKTOP_VENDOR_STRINGS:
                text = v.sub("", text)
            scan_apk_strings(sc, where, [text], patterns, local)

    for p in sorted(app.rglob("*")):
        if not p.is_file():
            continue
        n += 1
        rel = p.relative_to(app.parent).as_posix()
        data = p.read_bytes()
        own = bool(DESKTOP_OWN_JAR.match(p.name))
        if rel.startswith(DESKTOP_SKIP):
            continue
        if p.suffix.lower() == ".jar":
            with zipfile.ZipFile(io.BytesIO(data)) as z:
                for name in z.namelist():
                    if name.endswith("/"):
                        continue
                    n += 1
                    blob(f"{rel}!{name}", z.read(name), APK_BINARY_PATTERNS if own else DESKTOP_THIRD_PARTY)
            continue
        blob(rel, data, DESKTOP_THIRD_PARTY)
    seen, out = set(), []
    for f in sc.found:
        if f not in seen:
            seen.add(f)
            out.append(f)
    sc.found = out
    return sc, n


def report(sc, summary):
    for f in sc.found:
        print(f)
    for i in sc.info:
        print("info: " + i)
    print(summary + (f", {len(sc.info)} private-only info line(s)" if sc.info else ""))
    return bool(sc.found)


def main(argv):
    root = ROOT
    if "--root" in argv:
        root = Path(argv[argv.index("--root") + 1]).resolve()
    local = Local(root)
    modes = {m for m in ("--tree", "--public", "--apk", "--all", "--desktop") if m in argv} or {"--tree"}
    extra = f" (+{len(local.patterns)} local values, {len(local.fingerprints)} fingerprints)" \
        if local.patterns or local.fingerprints else ""
    bad = False
    if modes & {"--tree", "--all"}:
        sc, n = check_tree(root, local)
        bad |= report(sc, f"privacy check: {len(sc.found)} finding(s) in {n} files (tracked + new){extra}")
    if modes & {"--public", "--all"}:
        sc, n = check_public(root, local)
        bad |= report(sc, f"privacy check: {len(sc.found)} finding(s) in the public set ({n} files from git archive HEAD)")
    if modes & {"--apk", "--all"}:
        if "--apk" in argv:
            apk = Path(argv[argv.index("--apk") + 1]).resolve()
        else:
            apk = root / "app" / "build" / "outputs" / "apk" / "release" / "app-release.apk"
        if not apk.is_file():
            print(f"privacy check: no APK at {apk.name if apk.parent.exists() else apk}")
            return 1
        sc, n = check_apk(apk, root, local)
        bad |= report(sc, f"privacy check: {len(sc.found)} finding(s) in {apk.name} ({n} entries and dumps)")
    if "--desktop" in modes:
        app = Path(argv[argv.index("--desktop") + 1]).resolve()
        if not (app / "DeadDropFinance.exe").is_file():
            print(f"privacy check: no desktop app image at {app.name}")
            return 1
        sc, n = check_desktop(app, local)
        bad |= report(sc, f"privacy check: {len(sc.found)} finding(s) in the desktop app image ({n} files and jar entries)")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
