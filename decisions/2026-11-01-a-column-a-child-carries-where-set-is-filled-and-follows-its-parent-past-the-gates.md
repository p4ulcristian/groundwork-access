# A column a child carries where set is filled, and follows its parent past the gates

> Paul's decision, 2026-11-01 (the switch of Iron Rainbow onto composer v8: a batch carries its order's customer, as v0.5.1 did). Since v0.8.5. Changes in part [Records under records carry their parent's columns, by cellar's link and a fill](2026-10-23-records-under-records-carry-their-parents-columns.md): its carried columns are NOT NULL, and its rejected "trigger on the parent that updates the children".

**Decision:** a record under a record may carry a column that may be empty, said apart from the others: `:parent {:column :job/order-id :carries {:job/shop-id :order/shop-id} :carries-optional {:job/customer-id :order/customer-id}}`. An order's customer is the case: a guest's order has none, until she signs in and claims it.

- **the fill** sets it on insert (and on a change of the parent column) from the parent, as it sets the others, read past the gates; a value the app names is overwritten.
- **the follow:** a trigger on the parent, after a change of its column (`UPDATE OF order__customer_id`), carries the new value down to the children whose value differs (`groundwork_access.follow_…()`). It runs past the gates: it reads with `groundwork_access.passing` on and writes with `groundwork_access.carrying` on, which only the write gate of a table with `:carries-optional` lets through (`is_carrying()`), both back to what they were after. A guest order claimed later sets its customer, and its jobs follow, though the person who claims it may not write them.
- **not the link:** a reference holds nothing over an empty column (MATCH SIMPLE skips a row with one), so the column is no part of it; the fill and the follow keep it equal.
- **checked at the start:** each column exists on both sides, of one type, is neither the parent column nor one of `:carries`; the child's may be NOT NULL only when the parent's is. The child's sentences name it like any column (`[:me :job/customer-id]`), so the gates, the check and the leak test treat it as any.

**Why:** a customer reads her order's work by her own column on each row (one comparison, at every depth, as the other carried columns are read), and v0.5.1 kept that column equal to the order's. The NOT NULL link can't hold a guest's order, and an empty value, later set, is the case it must get right. The earlier rejection of a trigger on the parent was that it runs as the asker, held by the children's gates: the clerk who links an order to its customer may write the order, not its jobs, so the jobs would silently not follow. Past the gates, by groundwork-access's own function and a setting only the children's write gate reads, the follow reaches every child, as Postgres's cascade does for the link.

**The price:** another setting a gate reads; like `passing`, it is set only by groundwork-access's own function, and the app's SQL must never set it. The child's column can be changed by the app's own update apart from its parent (nothing refuses it): the fill and the follow set it, nothing else should.

**Instead of:**
- **the optional column in the link:** a reference over an empty column holds nothing, and would let the required ones go unchecked too
- **reaching the customer through the order on every read:** slows with depth and with long lists, as for the other carried columns
- **the follow as the asker:** the clerk's claim would leave the jobs as they were
