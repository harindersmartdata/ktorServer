# Primary / secondary replication

Tracking doc for the multi-terminal failover work. Tick steps off as they land.

## Goal

Four terminals. The server app runs on two of them (T1, T2). T1 is **primary** and
handles all traffic and storage. T2 is **secondary** and holds a replica. If T1 becomes
unavailable, T2 is promoted — by a human, not automatically — and another terminal can
then be made the new secondary.

**T1 and T2 may be Android or Windows.** Any design that needs root, admin rights or
network gear is out.

## Decisions

| # | Decision | Consequence we accepted |
|---|---|---|
| 1 | **Synchronous** replication | T1 waits for T2 to ack before confirming a write. |
| 1a | On T2 unreachable: **keep serving, queue silently** | Sync degrades to async whenever T2 is slow or down. "No data loss" holds *only while T2 is healthy*. Staleness is tracked internally but not surfaced as a loud alert. |
| 2 | Replicate **everything** | Replication is schema-agnostic — `change_log` carries `tableName` + JSON. New TuCount tables need no replication changes. |
| 3 | ~~Fixed IP + takeover~~ → **candidate list + redirect** | Original choice was fixed-IP takeover, **withdrawn**: stock Android cannot take over an IP. Clients instead hold both addresses; a non-primary replies `421` with the current primary's address and epoch. Works on both platforms, no infrastructure. |

## Steps

- [x] **1. Schema foundation** — `5cca18b`
      Globally unique string ids (autoincrement cannot survive failover), append-only
      `change_log` with tombstones, `replication_state` with node id and epoch, all
      writes atomic with their log entry via `WriteDao`. Migration `MIGRATION_1_2`
      verified against a real v1 database.
- [x] **2. Roles + epoch** — `POST /replication/role`, `/promote`, `/heartbeat`;
      writes rejected on a secondary with `421` + the primary's address; epoch adopted
      from the peer so a promotion always out-ranks the old primary; a superseded
      primary stands down on hearing a higher epoch.
      **Known window:** a returning primary accepts writes until it hears the higher
      epoch. Those writes are stamped with the stale epoch in `change_log`, which is
      how step 7 will find them. Closing the window is step 4 (confirm epoch with the
      peer before serving).
- [ ] **3. Synchronous replication** — ship the log to the peer, wait for ack, track the
      acked high-water mark, degrade per decision 1a.
- [ ] **4. Health + failure detection** — heartbeats, honest liveness, timeout tuning.
- [ ] **5. Manual promotion** — bump epoch, guard against split-brain.
- [ ] **6. Client redirection** — `421` + primary address; client-side candidate list.
- [ ] **7. Recovery + re-sync** — demote a stale returning node, catch it up, bootstrap
      a brand-new secondary from a snapshot.
- [ ] **8. Auth** — before any of this touches a real shop.

Steps 1 and 2 are prerequisites for everything else. **Step 7 is the hardest.**

## Challenges, and where each is handled

### Data model — *step 1, done*
Autoincrement ids collide across nodes. Deletes leave no trace for the peer to replay.
No change log means no way to know what changed. Wall-clock ordering is wrong because
terminals' clocks disagree. Multi-table transactions must apply atomically downstream
(`txId` groups them — the grouping exists, applying by group lands in step 3).

### Split-brain — *steps 2 (done), 4, 5 and 7*
T1 is not dead, just unreachable. T2 gets promoted. Both accept sales and diverge.
Manual promotion reduces this but does not solve it: the person at T2 cannot see that
T1 is still serving someone else. The guard is the **epoch** — any node that sees a
higher epoch than its own must stop accepting writes immediately. Implemented in step 2
and demonstrated end to end, including a returning primary standing down. It bounds the
damage rather than eliminating it: see the window noted against step 2.

### Sync cost — *step 3*
Per decision 1a, a slow or dead T2 must not stop the till, so the sync guarantee is
conditional. Whatever the UI does with that, the lag must be tracked internally or
catch-up cannot work.

### Failure detection — *step 4*
Timeout tuning is a real trade-off: too short and a Wi-Fi hiccup causes a false
failover, too long and tills are unusable for minutes. Also: if T2, T3 and T4 each
decide independently that T1 is dead, they can disagree about who is primary.

### Client redirection — *step 6*
Easy to leave too late. See decision 3.

### Recovery — *step 7*
A returning T1 holds writes T2 never saw; T2 holds everything since promotion. T1 must
detect it is stale and demote itself rather than resume as primary. Genuine conflicts
(same order, different totals) cannot be resolved by last-write-wins without losing money.

### Bootstrapping a new secondary — *step 7*
A fresh T3 starts empty: needs a full snapshot plus every change since, without taking
the primary offline.

### Security — *step 8*
Currently none. A replication endpoint means anyone on the LAN can push rows into the
database; a promotion endpoint means anyone can promote themselves.

### Android reliability — *step 4*
OEM battery management (Xiaomi, Oppo, Vivo) kills foreground services. **A secondary
that is silently dead is worse than no secondary** — you believe you are protected and
you are not. Liveness must be reported, never assumed.

## Notes

- There is no demo seed any more. Two nodes each inventing `ada@example.com` with
  different ids would collide on the unique email index the first time they synced.
  A fresh secondary takes its data from the primary.
- `/users/{id}` takes a string, not an integer. Breaking API change from step 1.
