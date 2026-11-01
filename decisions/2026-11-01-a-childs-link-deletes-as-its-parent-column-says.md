# A child's link to its parent deletes as its parent column says

> Paul's decision, 2026-11-01 (the switch of Iron Rainbow onto composer v8: "child rows: the link follows the column"). Since v0.8.4. Changes in part [Records under records carry their parent's columns, by cellar's link and a fill](2026-10-23-records-under-records-carry-their-parents-columns.md): its "price", a parent with children can't be deleted.

**Decision:** the link groundwork-access gives a record under a record (the reference over the parent column and the carried ones, `ON UPDATE CASCADE`) takes its `:on-delete` and `:checked-at` from the parent column's own `:references`. A job whose `:job/order-id` says `:on-delete :cascade` goes with its order, by the link as by the column; one that says `:refuse` or `:restrict` keeps an order that has jobs, checked after each `:statement` or at the `:commit`, as the column says. The link's `:on-update` stays `:cascade`: that is what carries a parent's change down.

```clojure
[:job/order-id :uuid {:references {:to :order/id :on-delete :cascade :on-update :refuse :checked-at :statement}}]
:parent {:column :job/order-id :carries {:job/shop-id :order/shop-id}}
;; the link put-gates-on-tables adds:
{:columns [:job/order-id :job/shop-id] :to [:order/id :order/shop-id]
 :on-delete :cascade :on-update :cascade :checked-at :statement}
```

Checked at the start, beside the parent's other words:
- **a parent column that says `:set-null`** is refused with a problem: the parent column is NOT NULL, so its children can't be left without a parent; say `:cascade`, `:refuse` or `:restrict`.
- **a map whose own `:references` say the link** (a reference over the parent column and the carried ones, in any order) is refused with a problem: the link is groundwork-access's, made from `:parent`; groundwork-postgres names a reference by its columns, so the map's would be silently replaced by it.

**Why:** the parent column says what a parent's delete does to its children; a second reference over the same rows that says otherwise makes the column's word a lie. With the link always refusing, a parent column that cascades held only by the order Postgres fires the two references' triggers: in creation order, so a link made before the column's own reference refused the delete, one made after let it through (tried on Postgres 18). A column checked at the commit was refused at the statement by its link. Iron Rainbow's database before groundwork-access (composer v0.5.1) already made each such link with its parent column's delete: batches and processes go with their order and batch, invoice lines and payments keep their invoice.

**Instead of:**
- **the link always refusing, the price kept:** a cascading parent column would cascade or refuse by trigger order, and an app would delete an order's batches itself before the order
- **a separate `:on-delete` in `:parent`:** two words for one thing, which could disagree with the column
- **the map saying the link itself and groundwork-access checking it:** the app would write out what groundwork-access knows, and a link left out would be a child without one
