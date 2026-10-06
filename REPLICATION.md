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
| 3 | **One constant URL**, `http://tucount.local:8080` | Fixed-IP takeover was withdrawn first (stock Android cannot take over an IP without root), then the candidate list alone was rejected: the POS app needs a single address it is configured with once. Whichever terminal is primary claims the name over mDNS and gives it up on demotion, so the URL never changes and nothing needs reconfiguring after a failover. The `421` redirect and candidate list remain as the fallback for clients that cannot resolve `.local`. |

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
      (The window this left open — a returning primary accepting writes before hearing
      the higher epoch — was closed in step 4.)
- [x] **3. Synchronous replication** — `POST /replication/apply` receives batches; the
      primary pushes and waits for the ack before answering the client. Degrades per
      decision 1a: an unreachable peer does not block the till, the backlog stays in
      `change_log`, and a catch-up loop drains it when the peer returns. Applying is
      idempotent (keyed on `nodeId, seq`) so a retry after a timeout is harmless, and a
      batch applies in one transaction so a multi-table sale cannot half-land.
      `/replication/status` reports `unreplicated`.
      **Known gap:** the local write commits *before* replication is attempted, so a
      write the peer later rejects is already durable locally. True no-loss would need
      two-phase commit; as built, such a write becomes an orphan for step 7 to reconcile.
- [x] **4. Health + failure detection** — both nodes probe each other every 2s via
      `/replication/heartbeat`, exchanging epochs in one round trip. A secondary raises
      `primaryUnavailable` after 10s of silence and the UI offers a promotion button;
      promotion stays a human decision, because a partition and a dead primary look
      identical from here. **Closes step 2's window:** a restarted node will not accept
      writes until it has confirmed its epoch with the peer (503 + Retry-After), or the
      5s grace expires with the peer unreachable.
      **Remaining:** a recovered node is a secondary but still missing whatever was
      written while it was away — reverse catch-up is step 7.
- [x] **5. Manual promotion** — `POST /replication/promote` probes the peer first and
      **refuses with 409 if it is still answering and still claims primary** — that is
      not a failover, it is a second primary. `force=true` overrides (a primary can
      answer heartbeats with a wedged database) and the override is recorded. Every
      promotion is written to `promotion_log`: epoch, time, whether forced, whether the
      peer was reachable, what it claimed, and the operator's reason. After promoting,
      the new epoch is announced to the peer immediately, so the old primary stands
      down in one round trip instead of waiting for its next heartbeat. The on-screen
      button goes through the same `PromotionCoordinator` as the API, with a plain
      confirmation dialog and a second, blunter one for the override.
      **Note:** `promotion_log` is per-node and is not replicated, so reconstructing a
      full failover history means reading it from both terminals.
- [x] **6. Client redirection** — `TuCountClient` (commonMain) holds both terminal
      addresses and works out which is primary, re-resolving when that changes, so
      failover is invisible to calling code. `GET /replication/primary` is answered by
      **any** node, so one reachable terminal is enough to locate the primary. A write
      to a secondary gets `421` naming the real primary; a restarted primary still
      checking its epoch gets `503` + `Retry-After` and the client waits rather than
      going elsewhere. Every response carries `X-Replication-Role` and
      `X-Replication-Epoch` so a client can tell it is reading a replica.
      `./gradlew clientProbe` drives it against a live pair.
      **Measured:** 22 writes across a real failover — 21 succeeded, the one failure
      being the second when T1 was dead and T2 not yet promoted. All 21 landed.
      **Note:** that gap is as long as a human takes to promote. Manual promotion is
      what keeps two tills from trading at once; the cost is that the window is human
      sized, not machine sized.
- [x] **7. Recovery + re-sync**
      - *Reverse catch-up needed no code.* A recovered node receives what it missed
        through the ordinary catch-up loop, because the new primary originates under its
        own node id and pushes that stream like any other. Verified end to end.
      - **Fixed an unrecoverable split-brain.** Two nodes claiming primary at the *same*
        epoch never resolved: fencing only demotes on a strictly higher epoch, so
        neither stood down, permanently. Heartbeats now carry role as well as epoch, and
        an equal-epoch clash is broken deterministically on node id — the larger yields.
        Both sides compute the same answer, so exactly one demotes with no negotiation.
      - **Closed the hole that caused it.** `POST /replication/role` could hand out the
        primary role with no safety check at all, bypassing everything `/promote`
        enforces. It now refuses to elevate a secondary and points at `/promote`. `role`
        became optional so a demoted terminal can be repointed at its replacement
        without tripping that guard.
      - **Orphans are surfaced, never merged silently.** `GET /replication/orphans`
        lists changes this node originated that the peer never confirmed; on a demoted
        node those are sales taken while it believed it was in charge.
        `POST /replication/orphans/handover` delivers them, on the operator's say-so.
      - **Fresh secondary bootstrap** works by replaying the log from zero: pointing at
        a new peer resets `peerAckedSeq`, so a blank terminal receives the whole history.
      **Remaining:** handover is all-or-nothing with no per-entry review; an adopted
      orphan overwrites the primary's version of the same row (apply uses REPLACE); and
      `change_log` is never truncated, so bootstrap replays everything ever written.
- [x] **8. Auth** — bearer tokens in two tiers. The **node** token admits a caller to
      the replication and operator routes (apply, promote, role, orphans, history); the
      **client** token only reaches `/users`. A till's credentials being lifted off a
      device therefore cannot promote a terminal or inject replication batches.
      `/replication/primary` takes either, because a till cannot route without it.
      `/health` stays open for watchdogs but returns only liveness to an anonymous
      caller — node id, role, epoch and row counts are not owed to whoever reaches the
      port. Tokens are generated on first run, persisted, shown on screen for pairing,
      and overridable with `TUCOUNT_NODE_TOKEN` / `TUCOUNT_CLIENT_TOKEN`. Comparison is
      constant-time so a token cannot be recovered a character at a time.
      **Not a substitute for TLS.** The server speaks plain HTTP, so every request
      carries its token in clear text; anyone who can capture packets on the shop LAN
      can take it and use it. This raises the bar from "anyone who can reach the port"
      to "anyone who can watch the wire", which is worth having, but real protection
      needs certificates both terminals trust.
      **Diagnosability gap:** a token mismatch between terminals surfaces as
      `peerReachable=false` — the same signal as a dead peer. An operator would be told
      the other terminal is not responding when it is actually rejecting the
      credentials.

All eight steps are done. The outstanding risks are recorded against each step above;
the largest are the lack of TLS (step 8) and the human-sized failover window (step 6).

## Challenges, and where each is handled

### Data model — *step 1, done*
Autoincrement ids collide across nodes. Deletes leave no trace for the peer to replay.
No change log means no way to know what changed. Wall-clock ordering is wrong because
terminals' clocks disagree. Multi-table transactions must apply atomically downstream
(`txId` groups them — the grouping exists, applying by group lands in step 3).

### Split-brain — *steps 2, 4, 5 done; 7 remaining*
T1 is not dead, just unreachable. T2 gets promoted. Both accept sales and diverge.
Manual promotion reduces this but does not solve it: the person at T2 cannot see that
T1 is still serving someone else. The guard is the **epoch** — any node that sees a
higher epoch than its own must stop accepting writes immediately. Implemented in step 2
and demonstrated end to end, including a returning primary standing down. It bounds the
damage rather than eliminating it: see the window noted against step 2.

### Sync cost — *step 3 (done)*
Per decision 1a, a slow or dead T2 must not stop the till, so the sync guarantee is
conditional. Whatever the UI does with that, the lag must be tracked internally or
catch-up cannot work.

### Failure detection — *step 4 (done)*
Timeout tuning is a real trade-off: too short and a Wi-Fi hiccup causes a false
failover, too long and tills are unusable for minutes. Also: if T2, T3 and T4 each
decide independently that T1 is dead, they can disagree about who is primary.

### Client redirection — *step 6 (done)*
See decision 3. Address-based rather than a moving IP, because IP takeover needs root
and a server terminal may be an Android tablet.

### Recovery — *step 7 (done)*
A returning T1 holds writes T2 never saw; T2 holds everything since promotion. T1 must
detect it is stale and demote itself rather than resume as primary. Genuine conflicts
(same order, different totals) cannot be resolved by last-write-wins without losing money.

### Bootstrapping a new secondary — *step 7 (done)*
A fresh T3 starts empty: needs a full snapshot plus every change since, without taking
the primary offline.

### Security — *step 8 (done, with limits)*
Two-tier bearer tokens close the open door: no anonymous caller can read rows, push a
replication batch or promote a terminal. What remains is that the tokens travel in
clear text over HTTP — see step 8.

### Android reliability — *step 4 (partly done)*
OEM battery management (Xiaomi, Oppo, Vivo) kills foreground services. **A secondary
that is silently dead is worse than no secondary** — you believe you are protected and
you are not. Liveness must be reported, never assumed. The monitor actively probes rather than
waiting to be told, so a reaped process is detected within the failure threshold.

## Verified on hardware

Samsung SM-X210 (Android 15) as primary, macOS desktop as secondary, bridged with
`adb forward` / `adb reverse`. Running it on a device found five defects that building
and desktop-only testing had not:

1. **Neither background loop ran on Android.** `PeerMonitor.start()` and
   `startCatchUp()` were called only from `DesktopServerController`. Android therefore
   had no heartbeats, no failure detection, no epoch exchange and no catch-up — and
   because the startup check never completed, an Android primary refused every write.
   Steps 3 and 4 were effectively dead on that platform.
2. **Pre-replication rows could never replicate.** `MIGRATION_1_2` converted the old
   table without writing `change_log` entries, so a shop upgrading from the old build
   would pair a secondary that silently missed all historical data. A node now
   backfills them when it is made primary.
3. **A replaced secondary was never resynced.** The primary kept its `peerAckedSeq`
   across a wipe at the same address and resumed above it, so the replacement silently
   never received the earlier changes. The peer's own report now wins.
4. **The heartbeat reply omitted the high-water mark**, which made the above
   reconciliation rewind to zero on every tick and resend the whole log forever.
5. **Broken string interpolation** showed `${role.name}` as literal text in the
   replication card and the promotion-refusal dialog, and the pairing token was
   generated too late to ever appear.

Not a finding, but worth recording: Android's cleartext restriction does **not** apply
here. Ktor CIO uses raw sockets rather than the HTTP stack that consults
`NetworkSecurityPolicy`, so no `usesCleartextTraffic` or network security config is
needed. An earlier commit added one on a wrong diagnosis; it was removed after testing
showed replication works without it.

## Failover, verified on hardware

Full cycle, both directions, tablet and desktop:

| | |
|---|---|
| Tablet primary force-stopped | Desktop raised `primaryUnavailable` in 10s |
| Desktop promoted (unforced) | epoch 2, took 2 sales while the tablet was down |
| Tablet returned believing PRIMARY/epoch 1 | Fenced on its **first** write attempt — `421`, already SECONDARY/epoch 2 |
| Reverse catch-up | Tablet received both missed sales unprompted |
| Desktop primary killed | Tablet raised the on-screen alert in 10s |
| Operator tapped promote on the tablet | Confirmation dialog, then PRIMARY/epoch 3, trading immediately |
| Desktop returned believing PRIMARY/epoch 2 | Fenced on its first write attempt — SECONDARY/epoch 3 |
| Final state | Both 8 rows, one primary, epoch 3 |

**Zero writes leaked through a stale primary in either direction.**

Two further defects came out of this run and are fixed: the heartbeat reply omitted
`lastAppliedSeq`, so a primary under-reported replication progress and `unreplicated`
claimed sales were at risk that were already on the peer; and a promotion made from the
screen recorded no reason in `promotion_log`, leaving the audit trail blank for exactly
the promotions a person made by hand.

## The constant URL

The POS app is configured once with:

```
http://tucount.local:8080
```

Whichever terminal is primary announces that name over mDNS; a terminal that is demoted
withdraws it within a heartbeat. Only a primary ever claims it, so two terminals cannot
answer for it at once.

Verified on macOS: announced as `tucount.local -> 172.24.0.60`, served `/health` and a
`POST /users` through the name, stopped resolving on demotion, and came back on
promotion.

Verified on Android (Samsung SM-X210): the multicast lock is acquired on promotion and
the name announced against the correct LAN address, the name is given up and the lock
**released** on demotion, and both are re-acquired on re-promotion. The lock tracks the
role exactly, so a secondary is not left holding a battery-draining lock. Confirmed from
the system log rather than the app's own, since a silently-filtered responder would look
identical from inside the app:

```
WifiService: acquireMulticastLock uid=10274 lockTag=tucount-mdns
mDNS - Announcing tucount.local -> 192.168.2.66:8080
```

**Still not verified: resolution by a remote client.** Both test devices have so far
been on different networks from the desktop, so nothing has yet resolved `tucount.local`
*across* the LAN to an Android primary. `.local` lookup is patchy on some Android
versions, needs Bonjour on Windows, and some networks block multicast — which is why the
candidate-list fallback stays in the client. Closing this needs a client and an Android
primary on the same Wi-Fi.

Finding the address to advertise also needed fixing. Enumerating interfaces picked a
self-assigned `169.254` address on one host and an unreachable virtual `192.168.2.1` on
another, while the real LAN address was `172.24.0.60`. It now asks the routing table by
connecting a UDP socket, which transmits nothing but reveals the interface that actually
carries traffic.

## Peer discovery

Each terminal announces itself under `_tucount._tcp` with its node id, whatever its
role, and follows the peer to a new address when DHCP moves it. Matching is on node id,
so a terminal follows the one it is paired with and not whatever else is on the network.

Verified on a Xiaomi Redmi Note 8 Pro (Android 11, MIUI):

```
acquireMulticastLock uid=10251
mDNS - Announced self as 1a1f0d46 at 192.168.2.67:8080; browsing for peers
mDNS - Claimed tucount.local -> 192.168.2.67:8080
```

Verified between two Android terminals on real Wi-Fi, with no `adb` bridges: a Samsung
SM-X210 at `192.168.2.66` and a Xiaomi Redmi Note 8 Pro at `192.168.2.67` found each
other by mDNS, were paired from the tablet's screen, and replicated two writes with zero
lag.

Still unverified: whether the POS app resolves `tucount.local`. The desktop proved
resolution on macOS; nothing has yet resolved it to an *Android* primary.

## Setting up a pair

On the terminal that should be the main one, tap **Make this the main terminal**. On the
other, find it under **Other terminals found** — mDNS puts it there, nobody types an
address — tap **Connect to this**, and enter the code from the first terminal's screen.
That single action sets the shared code, the role and the peer address together.

The code is eight characters from an alphabet with no `0`/`O` or `1`/`I`, shown as
`2J7J-6A2P`, so it can be read aloud across a shop. The previous 32-character hex token
was unusable: nobody transcribes that correctly between two tablets.

## Notes

- There is no demo seed any more. Two nodes each inventing `ada@example.com` with
  different ids would collide on the unique email index the first time they synced.
  A fresh secondary takes its data from the primary.
- `/users/{id}` takes a string, not an integer. Breaking API change from step 1.
