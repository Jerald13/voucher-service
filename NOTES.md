# NOTES — per-user redemption limit

## What I changed and why

| Change | Why |
|---|---|
| `campaign.max_redemptions_per_user INT NOT NULL DEFAULT 2 CHECK (> 0)` + `Campaign.maxRedemptionsPerUser` | The limit belongs to the campaign. `DEFAULT 2` means every existing row gets 2 with no data backfill; `NOT NULL` + `CHECK` stop a campaign being created with a missing or zero limit. |
| Index `redemption(campaign_id, user_id)` | The limit check counts by exactly these columns on every redeem. |
| `RedemptionRepository.countByCampaignIdAndUserId` | The `redemption` table is already the per-user ledger, so I count rows instead of adding a counter column that could drift. |
| Limit check in `VoucherService.redeem` | Rejects with `result: FAILED` and the message `Per-user redemption limit reached: max N voucher(s) per user for this campaign`, following the existing response contract (HTTP 200 + `FAILED`), so the storefront needs no changes. |
| `redeem` is now `@Transactional` and starts with `SELECT … FOR UPDATE` on the campaign row | **Without this the feature does not work.** My first version was a plain check-then-insert; `VoucherConcurrencyTest` fires 4 parallel redeems for one user and **all 4 succeeded** against a limit of 2 (see commit `c81cf84`). Locking the campaign row serialises redemptions per campaign. It is a DB lock rather than `synchronized` so it still holds across several instances that share a database. As a side effect it also closes the existing races on `remaining_stock` (lost update/oversell) and on the same voucher being redeemed twice. |
| Voucher is loaded *after* the lock (via a scalar `findCampaignIdByCode` first) | If the voucher entity were loaded before the lock, Hibernate's first-level cache would return the stale `ACTIVE` status after waiting on the lock. |
| Audit call moved to `afterCommit` | Once `redeem` holds a row lock, a synchronous HTTP call (RestTemplate, no timeout) inside the transaction would hold the lock for every user in that campaign. It also means we no longer audit a redemption that gets rolled back. |
| Tests: `@Transactional` on `VoucherServiceTest`, `@MockBean AuditClient`, new `VoucherConcurrencyTest` | The existing tests mutated a shared in-memory DB (order-dependent) and made real network calls to `internal-audit.prizm.local` (about 3 s per redeem locally). Added cases: default of 2, limit reached, rejected request doesn't consume stock or voucher, existing `user-99` history counts, limit is per campaign, limit is configurable, and parallel requests. |

### An assumption I made
**Does a voided voucher count toward the limit?** The seed data has `user-99` with two redemptions on campaign 1, one of them later voided. I count **all** redemption rows, so `user-99` is already at the limit. Reasoning: `void` doesn't restore stock or remove the redemption row, and `/stats` still counts it as redeemed, so treating it as "used" is consistent with the rest of the service and is the conservative option for a promotion. If the business wants voids to free up a slot, it is a one-line query change (join to `voucher` and exclude `VOID`). This is the first thing I'd confirm with the product owner.

## Noticed but deliberately did not change

- **Hard-coded audit API key** in `AuditClient` (and sent in the request body). It's a real problem: the key should be rotated and moved to config/secret storage. But rotating it is an ops action outside this change, and moving it alone would only hide it in the git history.
- **HTTP 200 for every failure** (`result: FAILED`). Not REST-ish, but the storefront depends on this contract; changing it is a breaking API change, not part of this feature.
- **`void` endpoint**: has no auth, can void an already-REDEEMED voucher, doesn't restore stock, and isn't transactional. Its business rules are undefined, and fixing them changes what `/stats` reports.
- **`.get()` on `Optional`** in stats (unknown campaign → 500) and **N+1 queries** in `CampaignStatsService` (one query per voucher). Real bugs, but unrelated to redemption limits.
- **No validation of `userId`**: a blank `userId` is accepted and all anonymous calls share one limit bucket. That needs a decision about what the storefront sends.
- **Data oddities**: `total_stock = 100` while campaign 1 has only 6 vouchers; no unique constraint on `voucher.code`; no foreign keys; `java.util.Date` everywhere; field vs constructor injection is inconsistent. Cleanup, not this task.
- **`schema.sql` drops and recreates tables** on boot. In a real deployment the new column would ship as a migration (`ALTER TABLE campaign ADD COLUMN max_redemptions_per_user INT DEFAULT 2 NOT NULL`, via Flyway/Liquibase), not an edit to a create script.

## Running on 3 instances behind a load balancer — what breaks
- **Each instance has its own H2 in-memory database.** Stock, voucher status and redemption history aren't shared: the same voucher can be redeemed once per instance, stock is counted 3 times, a user gets `3 × limit` redemptions, and `/stats` gives a different answer depending on which instance serves it. Restarting an instance resets its data. → Needs a shared database (e.g. Postgres).
- With a shared DB, the row lock I added holds across instances; an in-JVM `synchronized` lock would not have.
- The audit call fires once per instance that handled the request, with the same fire-and-forget failure handling (lost events on failure, not retried).

## If I had another day
1. Confirm the voided-voucher rule above with the product owner.
2. Add a machine-readable reason code (e.g. `USER_LIMIT_REACHED`) to `RedeemResponse` alongside the message, so the storefront doesn't have to parse text.
3. Move the audit to an outbox table + async sender (currently `afterCommit` still runs on the request thread, so a slow audit server still slows the response, just without holding the lock), add timeouts, and move the API key to config.
4. Watch lock contention on hot flash campaigns: the campaign row lock serialises *all* redemptions in a campaign. If that becomes a bottleneck, use a conditional `UPDATE … SET remaining_stock = remaining_stock - 1 WHERE remaining_stock > 0` plus a per-(campaign, user) counter row with a unique constraint.
5. Admin API to set the limit per campaign; Flyway migrations; fix the stats N+1 and the 500s.

## Reflection

**What did you get wrong first, and how did you notice?**
The first implementation was a plain "count, then insert" check. The single-threaded tests passed, so it looked done. I wrote a test that sends 4 redeems for one user in parallel, and all 4 succeeded against a limit of 2. That made it clear the limit needs a database-level lock (commit `c81cf84` → `5e64c44`).

**Which AI suggestion did you reject, and why?**
The AI flagged that the brief doesn't say whether a voided voucher should count toward the per-user limit (`user-99` in the seed data has one), and suggested leaving that rule open until it was confirmed. I didn't. The question isn't blocking: either answer is a one-line change to the count query in `VoucherService.redeem`. So I decided to count voided redemptions, because that matches how the rest of the service already treats voids (`void` doesn't restore stock, and `/stats` still counts the redemption), and wrote it down as an assumption above.

**What took you longest?**
Understanding the concurrency fix. I hadn't run Spring Boot before, and the setup was quick: JDK 17 was already installed, the Gradle wrapper handled the rest, and the only hiccup was port 8080 already being in use on my machine. The slow part was understanding why the count check alone isn't enough. That meant understanding what `@Transactional` and `SELECT … FOR UPDATE` actually do, why a `synchronized` lock wouldn't survive multiple instances, and why the voucher has to be read *after* taking the lock (Hibernate would otherwise hand back a cached, stale status).

## AI usage
I used Claude Code throughout; the raw exported session is in `ai-log/`.
