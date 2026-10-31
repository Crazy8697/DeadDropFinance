Dead Drop Finance for Windows 0.15.3: receipts read properly at many more stores, and an envelope for each item.

- The receipt reader handles the common layouts of many stores: tax letters with marks after them, quantity and weight lines, savings and coupons, section headers, and the tax block in its usual forms. Savings, tax, total and payment lines are never read as items.
- The Tax box gets the receipt's total tax, and tax rows that print a taxable amount settle which letters are taxed on their own. A receipt with tax but no tax letters asks which items are taxed before it saves.
- Every item on the receipt form has its own envelope, guessed from the last pick for that item or the receipt's section. When the receipt meets its charge, the charge is split across those envelopes.
- Scan receipt is on Activity and on Budget's envelope list.
- Opens budgets from 0.15.2 and keeps everything in them.
