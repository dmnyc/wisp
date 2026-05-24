# Follow History Guard — Cross-Platform Parity

Source: `barrydeen/wisp-ios` PR
[#185 — feat(follows): preserve & restore clobbered follow history](https://github.com/barrydeen/wisp-ios/pull/185).

Target: `barrydeen/wisp` (Android, Kotlin/Compose).

This is the parity contract the iOS PR body promised. Keep the keys, constants,
and decision heuristic identical so a user who flips between iOS and Android
sees the same behavior on the same account.

---

## What's Being Protected Against

Cross-client kind-3 contact-list overwrites — the single most common way a
Nostr user loses their follow graph. Another app (a buggy relay client, a
stale device, an unrelated tool) publishes a partial or empty kind-3 that
becomes the latest replaceable event for the user's pubkey on some relays.
Wisp then ingests the small list as current truth and rebuilds the feed from
the wreckage.

The guard detects the wipe, sweeps relays for an older intact version, and
offers to republish + restore it.

---

## Shared Contract

The pieces below MUST match across platforms — they're keyed on the user's
pubkey and the iOS/Android stores see the same on-relay state.

### Persistence Keys

Per-account SharedPreferences (Android) / UserDefaults (iOS):

| Key | Type | Purpose |
|---|---|---|
| `follow_count_highwater_<pubkey>` | Int | Largest follow count Wisp has ever recorded for this account. Monotonic — only ever raised when a healthy arrival is recorded. Acts as the trusted baseline for the substantial-drop heuristic. |
| `follow_restore_declined_count_<pubkey>` | Int | Largest candidate count the user has explicitly declined to restore. Without this we'd re-offer the same recovered list on every launch since the corrupting kind-3 stays the newest one on relays. |

iOS stores both as flat `UserDefaults` keys under the suffix-encoded form
above. Android stores them under the same names but inside the per-pubkey
`wisp_prefs_<pubkey>` SharedPreferences file (so the suffix is structural —
the file itself is per-account, the keys inside are `follow_count_highwater`
and `follow_restore_declined_count`). The on-disk pair is the same identity.

### Heuristic Constants

```
minMeaningfulPreviousCount = 10
substantialDropRatio       = 0.5
minAbsoluteDrop            = 5
```

These tune the partial-drop branch. Don't change one platform without the
other.

### `isSubstantialDrop(current, previous)`

The single decision helper that gates everything. Pure function — no I/O.

```
if current == 0:        return previous >= 1
if previous < 10:       return false
if previous - current < 5:  return false
return current < previous * 0.5
```

Rationale (must hold on both platforms):

- **Complete wipe (`current == 0`)** always fires when there's anything at all
  to recover. The deep relay sweep that produced `previous` already filters
  the no-history case, so any recoverable list beats starting over.
- **Partial-drop floor** prevents nagging on small lists where normal churn
  reads as a "big" proportional drop. A user with 9 follows dropping to 3 is
  intentional cleanup, not a clobber.
- **Absolute-drop floor (5)** prevents a 20→17 wobble from triggering.
- **Ratio (0.5)** demands the current list be under half the previous to count
  as substantial.

The unit tests pin all four boundary conditions; the same cases must pass on
both platforms.

### `followedPubkeys(event)`

Extracts the `p` tag values from a kind-3 event, de-duplicated but
**order-preserving** (first-seen-wins). Empty pubkeys skipped, malformed `p`
tags (length < 2) skipped, non-`p` tags ignored.

iOS and Android must produce the **same list in the same order** from the
same event — restored lists need to read the same as the original (positional
metadata, if any, isn't shuffled).

### `bestVersion(events, beating: currentCount)`

Over an already-fetched set of events, returns the kind-3 with the most `p`
tags, if that count beats `currentCount`. Non-kind-3 events ignored. Cheap
pre-check before paying for the broad relay sweep.

### Recovery Relay Set

The deep sweep queries the union (preserving order, de-duped) of:

1. Onboarding relays
2. Fallback relays
3. Indexer relays

On iOS these come from `RelayDefaults.{onboarding,fallbacks,indexers}`. On
Android there's only one published set today —
`RelayConfig.DEFAULT_INDEXER_RELAYS`. **Android parity action:** use
`DEFAULT_INDEXER_RELAYS` + the user's currently-configured read relays as the
sweep set. (When a richer Android `RelayDefaults` exists, expand to match
iOS.) The point is breadth across servers that might still serve the
pre-clobber version — indexers tend to keep only the newest replaceable
event, so we need the wider pool to find tombstoned/overwritten copies.

### Restore Publish Path

The recovered list is republished **verbatim** as a new kind-3:

- `p` tags in **original order** (no `Set` round-trip).
- The user's own pubkey appended if absent (matches `follow`/`unfollow` self-include).
- A `client` tag if the platform's client-tag setting is enabled.
- Published to the user's NIP-65 write relays + indexers (Android:
  `DEFAULT_INDEXER_RELAYS`).
- Local follow cache (Android: `ContactRepository`) updated to the recovered
  list immediately, before the publish round-trip resolves.

On iOS this is `FollowSender.restore`. On Android it's
`ContactRepository.restoreFollows()`.

### Account Cleanup

When an account is removed from the device, the two SharedPreferences /
UserDefaults keys above must be wiped along with the rest of the account's
local state. iOS routes through `NostrKey.deleteAccount` which queries
`FollowHistoryGuard.userDefaultsKeys(for:)`. Android wipes them implicitly
because they live inside the per-pubkey `wisp_prefs_<pubkey>` file, which
`KeyRepository.removeAccount` now clears entirely (along with
`wisp_contacts_<pubkey>`) — a pre-existing gap fixed in this PR.

---

## Android Implementation

### Files Added

- `app/src/main/kotlin/com/wisp/app/repo/FollowHistoryGuard.kt`
  Pure detection helpers + persistence + relay-sweep. The Kotlin twin of
  iOS `FollowHistoryGuard.swift`. Object (singleton) with the same public
  surface: `recordedHighWater`, `recordHighWater`, `resetHighWater`,
  `recordDeclined`, `clearDeclined`, `followedPubkeys`, `isSubstantialDrop`,
  `bestVersion`, `evaluateRestore`, `findRecoverable`, `didRestore`,
  `didDecline`.

- `app/src/main/kotlin/com/wisp/app/ui/component/FollowRestorePromptSheet.kt`
  Compose `ModalBottomSheet` — Android twin of iOS
  `FollowRestorePromptSheet.swift`. Same copy patterns ("Restore N follows" /
  "Keep N follows" / "Start fresh"), same plural-aware wording, same
  relative-date phrasing ("from 6 days ago").

- `app/src/test/kotlin/com/wisp/app/repo/FollowHistoryGuardTest.kt`
  JUnit 4 port of `wispTests/FollowHistoryGuardTests.swift`. Same eight
  test cases, same boundary inputs.

### Files Modified

- `app/src/main/kotlin/com/wisp/app/repo/ContactRepository.kt`
  Adds `suspend fun restoreFollows(pubkeys, signer, relayPool)` — order-preserving
  republish of the recovered list. Mirrors `FollowSender.restore`.

- `app/src/main/kotlin/com/wisp/app/repo/KeyRepository.kt`
  `removeAccount` now also wipes the per-pubkey `wisp_prefs_<pubkey>` and
  `wisp_contacts_<pubkey>` SharedPreferences files. Pre-existing gap; iOS
  parity for the new guard requires it.

- `app/src/main/kotlin/com/wisp/app/ui/screen/FeedScreen.kt`
  `LaunchedEffect(activePubkey)` fires the guard once per account session
  after the first kind-3 lands in `ContactRepository`. Shows
  `FollowRestorePromptSheet` if a candidate is returned. Restore path calls
  `ContactRepository.restoreFollows` then `FollowHistoryGuard.didRestore`;
  keep path calls `FollowHistoryGuard.didDecline`.

### Why One Mount Point (vs. iOS's Two)

iOS has both an onboarding-time path (`OnboardingViewModel.start`) and a
launch-time path (`MainView.runFollowGuardOnce`) because iOS's
`OnboardingViewModel` is what synchronously fetches the user's kind-3 during
sign-in — pausing on a "Waiting" step makes the restore choice block
onboarding completion.

Android's existing-user sign-in (`ExistingUserOnboardingScreen` →
`StartupCoordinator`) is non-blocking: kind-3 streams in via the relay event
pipeline and `EventRouter` updates `ContactRepository` opportunistically.
There's no analog of iOS's blocking onboarding step to mount on.

The launch-time hook in `FeedScreen` covers both the cross-session-clobber
case AND the just-signed-in case — both flows land on `FeedScreen` after the
kind-3 fetch resolves. Same coverage, one hook.

### Once-Per-Session Gate

The iOS launch-time guard uses a `@State` flag on `MainView`. On Android we
use a single-shot `LaunchedEffect` keyed on the active pubkey — re-fires on
account switch (correct), doesn't re-fire on screen rebuild (correct).

### Watch-Only Accounts

Skipped on both platforms — a watch-only account can't sign a restore
republish, so offering one would be a dead end.
