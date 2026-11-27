Dead Drop Finance for Windows 0.15.10: CSV imports find what's already in your budget, show you before saving, and can be undone.

- Each imported transaction keeps the bank's own description, such as USAA's "Original Description". Renaming the payee no longer makes a later import add it again.
- A transaction you entered by hand, or one imported before this version, counts as already in when the amount matches on the same day. If it's a day or two off, or more than one could match, the row is shown as unsure with the possible match beside it.
- Same-day repeats count separately: three $3.00 rows in the file against two in the program adds one.
- Before anything is saved you see a preview: how many are new, already in, unsure and pending. New rows are ticked, unsure rows aren't, and you can change any of them. Rows already in are under a count you can open. Rows the bank lists as Pending are not added; they come in posted with a later file.
- Activity, on an account's tab: Undo last import, next to Import CSV. After asking, it removes the transactions that import added, and nothing else. It also works on an import made before this version.
- Opens budgets from 0.15.9 and keeps everything in them.
