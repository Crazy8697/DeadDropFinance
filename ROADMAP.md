# Dead Drop Finance roadmap

```text
PREP
    Name locked
    Feature plan
    Build toolchain verified
    Icon
    darkrelay.net Coming soon card

SETUP AND SHIPPING
 0  Project setup: folder, private repo, noreply identity, .gitignore, license check, Kotlin version
 1  Skeleton app: opens, shows name and version, nothing else
 2  Signed release APK: keystore outside any repo, backed up in two separate places
 3  Privacy sweep + privacy_check gate (privacy_check excludes the export-ignored docs/mockups from the public set and fails if it ever appears there)
 4  License, notices + fresh public GitHub repo
 5  Update check (opens download page) + first published build 0.5.0, the owner installs

FEATURES
 6  Base: encrypted database, change log for future sync, accounts (bank, payment app, cash) and transfers, transactions (splits, pass-through, partial payments), envelopes (fill to target, add every payday, goal, seasonal, debt, Cash, payday auto-fill in list order, Rollover), app lock, hide screen toggle, Old School CSV import; SQLCipher's BSD notice and any new libraries get added to the notices when they arrive
 7  First-run setup wizard: Easy (SimpleFIN), Old School (CSV), SimpleFIN bank sync working (bank sync removed in 1.3.0), track-only accounts and held amounts, Delete account, Erase everything
 8  Auto-categorize rules + approve flow + live spend alerts + returns + balance check
 9  Receipt scanning (multi-photo capture, stitching, gallery pick, on-device text recognition, subtotal check, learned item rules, split to envelopes, match to bank charge, scan on every store charge, gas station holds, photos deleted after Apply split, trip detail screen, return item matching)
10  Recurring bills, paydays, reminders (off by default, per bill), Payday checklist, autopay warnings, subscriptions screen, balance forecast, live spend alerts from bank notifications
11  Safe to Spend home screen (built from the reference prototype) + parked buys + payday runs by itself when the deposit lands (sync, CSV, notification)
12  Encrypted one-tap backup and restore + JSON setup importer + Purge data + icon into the app (adaptive, themed)

RELEASE PREP
13  Review once-over + bug fixes
14  Third-party terms: SimpleFIN terms, privacy policy, end user agreement
15  UI polish to the DarkRelay look + About + Help (SimpleFIN credits)
16  Publish 1.0.0

DISTRIBUTION
17  darkrelay.net card flips from Coming soon to Download
18  AlternativeTo (Android)
19  Reddit

POST-RELEASE
20  Reports and trends (merchant pages: totals, trips, by category, most-bought items) + price history
21  Debt payoff planning: interest, payoff plans, projected dates (simple debt envelopes are in Stage 6)
22  Self-hosted server: two-way sync + in-depth desktop web version
69G Google Drive sync
69D Dropbox sync

Cut: OneDrive sync, Play Store, Android Studio install
Notes: Every stage ships as 0.N.0; 1.0.0 (Stage 16) is the first full release. Stages 12 to 15 shipped together as 0.15.0. Kotlin pinned to 2.4.21 + KSP 2.3.12, fallback bundled 2.2.10. Test the Room / SQLCipher androidx.sqlite version clash at Stage 6. Behavior spec lives in FEATURES.md.
```
