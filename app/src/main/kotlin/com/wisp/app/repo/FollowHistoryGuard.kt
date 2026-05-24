package com.wisp.app.repo

import android.content.Context
import android.util.Log
import com.wisp.app.nostr.ClientMessage
import com.wisp.app.nostr.Filter
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.relay.RelayConfig
import com.wisp.app.relay.RelayPool
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

/**
 * A recoverable contact list pulled from relay history (an overwritten or
 * tombstoned kind-3) that has substantially more follows than the version
 * the user just arrived with.
 *
 * `createdAt` is surfaced so the UI can say roughly how old the recovered
 * list is.
 */
data class FollowRestoreCandidate(
    /** De-duplicated, order-preserving list of followed pubkeys. */
    val pubkeys: List<String>,
    /** `created_at` of the kind-3 this came from. */
    val createdAt: Long
) {
    val count: Int get() = pubkeys.size
}

/**
 * Detects the "my follow list got clobbered" failure mode that plagues
 * Nostr: a buggy or malicious client republishes a tiny (or empty) kind-3
 * that overwrites the user's real contact list on most relays. When the
 * user next arrives in Wisp they'd otherwise silently inherit the
 * wreckage.
 *
 * The guard compares the freshly-fetched contact list against (a) the
 * largest count Wisp has ever seen for this account locally and (b) older /
 * overwritten / tombstoned kind-3 versions still served by other relays. If
 * the current list is a substantial drop from a recoverable one, the
 * launch-time flow offers to restore it.
 *
 * Pure decision helpers are kept free of relay/IO so they can be
 * unit-tested. See [FollowHistoryGuardTest] for boundary cases.
 *
 * Cross-platform parity with iOS `FollowHistoryGuard.swift` — same
 * persistence key names, same heuristic constants. See
 * `docs/follow-history-guard-parity.md`.
 */
object FollowHistoryGuard {

    private const val TAG = "FollowGuard"

    // MARK: - Tunables (MUST match iOS)

    /** Below this previous count we don't bother — for a user who follows a
     *  handful of people, normal churn looks like a "big" proportional drop
     *  and nagging them would be noise. */
    const val MIN_MEANINGFUL_PREVIOUS_COUNT = 10

    /** The current list has to be under this fraction of the previous best
     *  to count as "substantially lower". */
    const val SUBSTANTIAL_DROP_RATIO = 0.5

    /** …and the absolute loss has to be at least this many follows. Stops a
     *  12 → 5 wobble from triggering while still catching real wipes. */
    const val MIN_ABSOLUTE_DROP = 5

    // MARK: - Persistence keys

    private const val KEY_HIGHWATER = "follow_count_highwater"
    private const val KEY_DECLINED = "follow_restore_declined_count"

    private fun prefs(context: Context, pubkey: String) =
        context.getSharedPreferences("wisp_prefs_$pubkey", Context.MODE_PRIVATE)

    // MARK: - High-water mark

    fun recordedHighWater(context: Context, pubkey: String): Int =
        prefs(context, pubkey).getInt(KEY_HIGHWATER, 0)

    /** Monotonic: only ever raises the mark. Called on a healthy arrival so
     *  we remember how many follows the user genuinely had. */
    fun recordHighWater(context: Context, pubkey: String, count: Int) {
        if (count > recordedHighWater(context, pubkey)) {
            prefs(context, pubkey).edit().putInt(KEY_HIGHWATER, count).apply()
        }
    }

    /** Accept [count] as the new baseline even if it's lower. Used when the
     *  user deliberately keeps a smaller list (declines a restore) so that
     *  an intentional cull doesn't keep looking like a wipe. */
    fun resetHighWater(context: Context, pubkey: String, count: Int) {
        prefs(context, pubkey).edit().putInt(KEY_HIGHWATER, count).apply()
    }

    private fun recordedDeclinedCount(context: Context, pubkey: String): Int =
        prefs(context, pubkey).getInt(KEY_DECLINED, 0)

    /** Remember that the user passed on restoring a list of this size so we
     *  don't pester them again unless an even larger version turns up
     *  later. */
    fun recordDeclined(context: Context, pubkey: String, candidateCount: Int) {
        if (candidateCount > recordedDeclinedCount(context, pubkey)) {
            prefs(context, pubkey).edit().putInt(KEY_DECLINED, candidateCount).apply()
        }
    }

    private fun clearDeclined(context: Context, pubkey: String) {
        prefs(context, pubkey).edit().remove(KEY_DECLINED).apply()
    }

    // MARK: - Pure decision helpers

    /** Extract the followed pubkeys from a kind-3, de-duplicated but
     *  keeping first-seen order (so a restored list reads the same as the
     *  original). */
    fun followedPubkeys(event: NostrEvent): List<String> {
        val seen = HashSet<String>()
        val ordered = ArrayList<String>(event.tags.size)
        for (tag in event.tags) {
            if (tag.size < 2 || tag[0] != "p") continue
            val pk = tag[1]
            if (pk.isEmpty()) continue
            if (seen.add(pk)) ordered.add(pk)
        }
        return ordered
    }

    /**
     * Is [current] a substantial drop from [previous]? Encodes the
     * ratio + absolute-floor + minimum-meaningful-previous rules.
     *
     * A complete wipe (`current == 0`) surfaces whenever there's anything
     * at all to recover. The thresholds in the other branch exist to avoid
     * nagging on normal churn for small follow lists — but a full clobber
     * to zero is never normal churn, and recovering even a single follow
     * beats starting over. The deep relay sweep that produced `previous`
     * has already filtered out the no-history case.
     */
    fun isSubstantialDrop(current: Int, previous: Int): Boolean {
        if (current == 0) return previous >= 1
        if (previous < MIN_MEANINGFUL_PREVIOUS_COUNT) return false
        if (previous - current < MIN_ABSOLUTE_DROP) return false
        return current.toDouble() < previous.toDouble() * SUBSTANTIAL_DROP_RATIO
    }

    /** Pick the kind-3 with the most follows out of an already-fetched set
     *  (e.g. whatever the indexer query returned), if any beats
     *  [currentCount]. Cheap pre-check before paying for the broad recovery
     *  sweep. */
    fun bestVersion(events: List<NostrEvent>, beating: Int): FollowRestoreCandidate? {
        var best: FollowRestoreCandidate? = null
        for (event in events) {
            if (event.kind != 3) continue
            val pks = followedPubkeys(event)
            val bestCount = best?.count ?: beating
            if (pks.size > beating && pks.size > bestCount) {
                best = FollowRestoreCandidate(pubkeys = pks, createdAt = event.created_at)
            }
        }
        return best
    }

    // MARK: - Recovery (relay IO)

    /**
     * Decide whether to offer a restore for [currentFollows].
     *
     * [fetched] is whatever the caller already pulled (the cheap indexer
     * query) — used both as a suspicion signal and as a candidate source.
     * We cast the wide net for overwritten/tombstoned copies when the list
     * looks clobbered *or* on the very first arrival for this account (no
     * local history yet): that's precisely when the good list may survive
     * only on a relay the indexers never saw, and it's a one-time cost.
     * The substantial-drop guard below still keeps a healthy arrival
     * silent.
     *
     * Returns null when no restore should be offered.
     */
    suspend fun evaluateRestore(
        context: Context,
        relayPool: RelayPool,
        pubkey: String,
        currentFollows: List<String>,
        fetched: List<NostrEvent>
    ): FollowRestoreCandidate? {
        val currentCount = currentFollows.size
        val highWater = recordedHighWater(context, pubkey)
        val firstArrival = highWater == 0
        val cheapBest = bestVersion(fetched, beating = currentCount)

        // Always cast the wide net when the current list is empty.
        // Indexers typically only retain the newest replaceable event, so a
        // clobber event leaves the cheap fetch with nothing to suggest a
        // drop; the recoverable older versions live on whichever relays
        // haven't yet received the wipe. Without this we'd silently skip
        // the sweep that is the whole point of the feature.
        val emptyArrival = currentCount == 0

        val suspicious =
            firstArrival ||
            emptyArrival ||
            isSubstantialDrop(currentCount, previous = highWater) ||
            (cheapBest?.let { isSubstantialDrop(currentCount, previous = it.count) } ?: false)
        if (!suspicious) return null

        val deep = findRecoverable(relayPool, pubkey)

        val candidate = listOfNotNull(cheapBest, deep).maxByOrNull { it.count } ?: return null
        if (!isSubstantialDrop(currentCount, previous = candidate.count)) return null

        // Don't re-nag about a list the user already turned down, unless an
        // even bigger one has since surfaced.
        if (candidate.count <= recordedDeclinedCount(context, pubkey)) return null

        return candidate
    }

    /**
     * Broad multi-relay sweep for every historical kind-3 the author ever
     * published. Relays that never received (or didn't honor) the
     * clobbering replacement still serve the intact list; we de-dupe by
     * event id, not by replaceable key, so distinct versions all surface.
     *
     * Uses [RelayConfig.DEFAULT_INDEXER_RELAYS] as the sweep set — broadest
     * pool of well-connected relays Wisp has on hand. See parity doc.
     */
    suspend fun findRecoverable(
        relayPool: RelayPool,
        pubkey: String
    ): FollowRestoreCandidate? = coroutineScope {
        val subId = "fhg-recover-${pubkey.take(8)}-${System.currentTimeMillis()}"
        val filter = Filter(kinds = listOf(3), authors = listOf(pubkey))
        val req = ClientMessage.req(subId, filter)

        val relays = RelayConfig.DEFAULT_INDEXER_RELAYS
        val seenIds = HashSet<String>()
        val collected = ArrayList<NostrEvent>()
        val done = CompletableDeferred<Unit>()
        val eoseCount = AtomicInteger(0)

        val collectJob = launch {
            relayPool.relayEvents.collect { ev ->
                if (ev.subscriptionId != subId) return@collect
                if (ev.event.kind != 3 || ev.event.pubkey != pubkey) return@collect
                if (seenIds.add(ev.event.id)) collected.add(ev.event)
            }
        }
        val eoseJob = launch {
            relayPool.eoseSignals.collect { id ->
                if (id == subId && eoseCount.incrementAndGet() >= relays.size) {
                    done.complete(Unit)
                }
            }
        }

        try {
            for (url in relays) relayPool.sendToRelayOrEphemeral(url, req)
            withTimeoutOrNull(15_000) { done.await() }
        } finally {
            collectJob.cancel()
            eoseJob.cancel()
            relayPool.closeOnAllRelays(subId)
        }

        var best: FollowRestoreCandidate? = null
        for (event in collected) {
            val pks = followedPubkeys(event)
            val bestCount = best?.count ?: 0
            if (pks.size > bestCount) {
                best = FollowRestoreCandidate(pubkeys = pks, createdAt = event.created_at)
            }
        }
        if (best != null) {
            Log.d(TAG, "deep sweep for ${pubkey.take(8)} → best ${best.count} follows from createdAt=${best.createdAt}")
        }
        best
    }

    // MARK: - Outcome bookkeeping

    /** User accepted: the restored size is now the trusted baseline and
     *  any prior "declined" memory is moot. */
    fun didRestore(context: Context, pubkey: String, count: Int) {
        resetHighWater(context, pubkey, count)
        clearDeclined(context, pubkey)
    }

    /** User kept the smaller list: treat it as intentional so we don't
     *  keep flagging the same drop, and remember the size they passed
     *  on. */
    fun didDecline(context: Context, pubkey: String, currentCount: Int, candidateCount: Int) {
        resetHighWater(context, pubkey, currentCount)
        recordDeclined(context, pubkey, candidateCount)
    }
}
