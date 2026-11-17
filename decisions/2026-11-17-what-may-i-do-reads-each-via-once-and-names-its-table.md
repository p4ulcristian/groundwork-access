# "What may I do?" reads each via's function once, by a query made with the handler, and names the table it reads

> Decided by the user, 2026-11-17, from Iron Rainbow's prod: `groundwork-access/my-actions` took 3.4 s a call (stage 0.2 s). Since v0.8.11.

**Decision:** `make-my-actions-handler` makes its via and state-deny queries once, when the handler is made: one per via function and output column (`via_1` …), however many actions and sentences share it; at each call it reads each once and gives every action that via gives in the scopes it returns. Each query starts with a `-- reads: <table>` line, groundwork-postgres's way to name a table a function hides from the planner: a via's own `:table`, a state deny's kind's table. The answer is unchanged.

**Why:** groundwork-live-updates notes the tables every handler's steps read, and groundwork-postgres asks Postgres's planner once per query value, 7 statements, each a round trip. A query made at each call was planned at each call: Iron Rainbow's 46 via reads (16 functions) cost about 370 round trips a call, about 9 ms each on prod. Made once, the planner is asked once per process, and a call reads 16 times. And the planner sees a function scan, no table: the notes named none of the vias' tables, so a change to them (a package published) never refreshed the answer. A via function reads exactly its `:table` (the rest it calls reads only settings), and a state deny's function exactly its kind's table, so the line names what is read.

**Instead of:**
- **groundwork-postgres keeping the planner's answer by SQL text:** fixes every query made at each call, in every library, but changes groundwork-postgres for a cost only my-actions pays today; the notes would still name no table
- **every via in one statement (UNION ALL):** one round trip instead of 16; left for later, not needed for the gain
