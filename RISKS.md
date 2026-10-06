# Risks in the two-terminal approach

Written after a day of running the pair on real hardware — a Samsung SM-X210 and a
Xiaomi Redmi Note 8 Pro — not from first principles. Where something was actually
observed, it says so. Where it is reasoning, it says that too.

Ordered by how likely it is to bite you, not by how interesting it is.

---

## 1. The network is the weakest part, by a distance

**Observed, repeatedly.** The Redmi moved between two Wi-Fi networks three times in one
afternoon. Every time, the pair silently split: each terminal thought the other was
dead, both screens looked healthy, and replication stopped. Once, that led to a
promotion that produced two primaries.

Both routers handed out `192.168.2.x`, so the addresses looked compatible and nothing
appeared obviously wrong. That is the trap — it does not announce itself.

**What makes it worse**

- **DHCP churn.** Addresses moved repeatedly (`.11` → `.67` → `.11`). mDNS discovery now
  follows a peer that moves, but only while both can see each other.
- **Client isolation.** Some access points block device-to-device traffic entirely.
  Internet works, terminals cannot see each other, and nothing in the app can fix it.
- **Multicast filtering.** Guest networks and some enterprise kit drop multicast, which
  kills both mDNS discovery and the constant `trucount.local` address. The candidate-list
  fallback covers the client, not the terminals finding each other.
- **Two bands, one SSID.** A terminal on 5GHz and another on 2.4GHz may be isolated
  depending on the AP.

**Before any shop goes live**

- Pin both terminals to one SSID and ideally one access point. Forget every other network
  on those devices, and turn off any "switch to stronger network" assistant.
- DHCP reservations for both, so addresses stop moving.
- Verify device-to-device traffic is permitted on that AP before installing anything.

---

## 2. Android may stop the server without telling anyone

**Observed once, cause not established.** Both servers were found dead with their
processes still alive and their screens still up — nothing listening on 8080. The
tablet's log showed its foreground-service notification being removed minutes after a
confirmed-healthy check. They only came back when force-restarted.

A reinstall shortly beforehand is a plausible confound, so this is **suspicious, not
proven**. It is the single most important open question in the project: a terminal that
quietly stops is unusable regardless of how correct the replication is.

**What it would take to settle it:** leave both running untouched for an hour, polling
from outside, and see whether they stop with nothing acting on them.

**Known aggravators**

- OEM battery management (Xiaomi, Oppo, Vivo, and Samsung to a lesser degree) kills
  foreground services on its own schedule.
- `START_STICKY` asks Android to restart the service, but OEM builds do not always honour it.
- The app auto-starts the server when the Activity opens — if the system restarts the
  process *without* the Activity, nothing starts it.

**Mitigations to plan for:** battery-optimisation exemption per device, mains power,
kiosk/pinned mode, a watchdog that restarts the service, and a `BOOT_COMPLETED` receiver
so a rebooted terminal comes back by itself.

---

## 3. A person can break it faster than the software can

**Observed.** The audit trail records three consecutive **forced** promotions — epochs 3,
4 and 5 — each overriding a peer that was answering as primary. The pair ended with *no*
primary at all, refusing every write, with nothing on screen saying so.

Since fixed: the promotion button only appears when there is something to fix, and a
pair with no primary now says so in red. But the underlying hazard stands — the software
cannot tell "the other terminal is dead" from "the other terminal is on a different
Wi-Fi", so the decision rests with a person who may be under pressure at 9am.

**Residual**

- The failover gap is **human-sized**. Detection takes ~10 seconds; a person deciding and
  acting may take minutes. That is the cost of manual promotion, which is also what
  prevents two terminals trading at once.
- Force exists for a primary that answers but is unusable. Used to settle an argument
  between two working terminals, it produces exactly the mess above.

---

## 4. "No data loss" holds only while the backup is healthy

This follows from the synchronous-with-degradation choice, deliberately made.

A write waits for the backup to confirm, but if the backup is slow or unreachable the
write is **still accepted** and queued. So whenever the backup is down, this is
asynchronous replication, and a failover during that window loses whatever had not been
acknowledged.

The lag is tracked and shown, but nothing refuses a sale to protect the guarantee —
because a dead backup must not be able to stop the terminal trading.

**Also:** the local write commits *before* replication is attempted, so a write the peer
later rejects is already durable locally. True no-loss would need two-phase commit, at
the cost of a round trip per sale and a stall whenever the peer is down.

---

## 5. Replication is not a backup

The most commonly mistaken point, and worth stating plainly.

**A mistaken `DELETE` replicates to the other terminal in under a second.** So does a
bad migration, a bug that corrupts rows, or an operator clearing the wrong thing. There
is no point-in-time recovery, no snapshot, and no undo.

Two terminals protect against **one terminal failing**. They protect against nothing
else. A shop still needs a periodic export somewhere off both devices.

---

## 6. Data problems that only appear at scale or with the real schema

- **`change_log` is never truncated.** It grows with every write, forever. Bootstrapping
  a replacement terminal replays the entire history. Fine at today's volumes; a problem
  after a year of real sales. Needs a retention policy plus a snapshot mechanism.
- **Multi-table transactions are designed for but untested.** `txId` groups them and a
  batch applies in one transaction, but everything so far has run against a single demo
  table. Real orders with line items and payments are where this gets exercised properly.
- **Conflicts resolve by last-writer-wins** (`REPLACE` on apply). For two edits of the
  same order with different totals, that silently picks one. It has not mattered yet
  because only one terminal accepts writes at a time — it will matter after any
  split-brain episode.
- **Orphans need a human.** A terminal demoted while holding unreplicated writes reports
  them and offers a handover, deliberately not automatic. Someone has to look. Adopting
  an orphan can also resurrect a row the new primary deliberately deleted.
- **Pre-existing rows need backfilling.** Rows written before replication existed carry no
  log entries. Handled now, but the same trap applies to any future table added outside
  the logged write path.

---

## 7. Security is partial

- **No TLS.** Tokens cross the LAN in clear text. Anyone able to capture packets on the
  shop Wi-Fi can take one and use it. This is the largest security gap and it is not
  small to close: certificates both terminals trust, on devices with no fixed names.
- **`/users` authentication is off by default** so the API can be opened in a browser.
  It must be switched on before a shop goes live.
- **The pairing code is ~40 bits**, appropriate for a device that also has to be on the
  network, but not a secret to publish.
- **Anyone on the LAN can read the data** while auth is off, and push replication batches
  if they obtain the node code.

---

## 8. Platform and delivery

- **The Windows `.exe` has never been built.** `jpackage` does not cross-compile, so it
  must be produced on Windows. The desktop logic is the same JVM bytecode proven on
  macOS, but packaging, the `LOCALAPPDATA` path and the firewall prompt are unexercised.
- **iOS cannot be a server.** The OS suspends a backgrounded app and the listening socket
  dies with it. Fine as a client; not viable as a terminal.
- **MIUI blocks operational tooling** — `adb` input injection and `pm clear` both refused
  without a Mi account. Worth knowing before a rollout where support staff expect remote
  access.
- **Android 8.0 minimum** (`minSdk 26`).

---

## 9. There are no automated tests

Zero test sources, zero test dependencies. Every verification in this project has been
manual: curl against live servers, and tapping real devices.

**There is no regression safety net.** Nothing would catch a future change breaking the
epoch fencing, the idempotent apply, or the orphan detection — all of which had real bugs
that only testing by hand found. The failure would surface in a shop.

This is the cheapest large risk to reduce: the fencing rules and the apply path are pure
logic and straightforward to test without devices.

---

## What I would do first

1. **Settle the Android stoppage question.** Nothing else matters if terminals stop on
   their own.
2. **Lock down the network** — one SSID, one AP, DHCP reservations, confirm
   device-to-device traffic is allowed.
3. **Write tests around the fencing and apply logic**, before the code changes further.
4. **Build the Windows `.exe`** and exercise it, so that half the deployment stops being
   theoretical.
5. **Decide on TLS** before a real shop, and turn API authentication on.
6. **Run the real TruCount schema through it**, because multi-table transactions are the
   largest untested assumption in the design.
