package com.wisp.app.repo

import com.wisp.app.nostr.NostrEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JUnit port of iOS `wispTests/FollowHistoryGuardTests.swift`. Same eight
 * test cases, same boundary inputs — parity with the iOS suite is the
 * point. See `docs/follow-history-guard-parity.md`.
 *
 * Only the pure helpers ([FollowHistoryGuard.isSubstantialDrop],
 * [FollowHistoryGuard.followedPubkeys], [FollowHistoryGuard.bestVersion])
 * are exercised here — those have no Android Context or relay I/O so they
 * test directly without Robolectric.
 */
class FollowHistoryGuardTest {

    private fun kind3(pubkeys: List<String>, createdAt: Long = 0, kind: Int = 3): NostrEvent {
        return NostrEvent(
            id = "0".repeat(64),
            pubkey = "me",
            created_at = createdAt,
            kind = kind,
            tags = pubkeys.map { listOf("p", it) },
            content = "",
            sig = ""
        )
    }

    // -- isSubstantialDrop --

    @Test
    fun substantialDrop_classicWipe() {
        // 400 → 1 is the canonical clobbered-list case.
        assertTrue(FollowHistoryGuard.isSubstantialDrop(current = 1, previous = 400))
    }

    @Test
    fun substantialDrop_ignoresTinyPartialDrops() {
        // Below the meaningful floor we don't flag partial drops — normal
        // churn on a small list reads as proportionally large.
        assertFalse(FollowHistoryGuard.isSubstantialDrop(current = 1, previous = 9))
    }

    @Test
    fun substantialDrop_completeWipeAlwaysFiresWhenRecoverable() {
        // A drop to zero is unambiguous — surface whenever there's
        // anything recoverable. The deep sweep that produced `previous`
        // already filters out the "no history exists at all" case.
        assertTrue(FollowHistoryGuard.isSubstantialDrop(current = 0, previous = 9))
        assertTrue(FollowHistoryGuard.isSubstantialDrop(current = 0, previous = 5))
        assertTrue(FollowHistoryGuard.isSubstantialDrop(current = 0, previous = 1))
    }

    @Test
    fun substantialDrop_nothingToRecover() {
        // If literally nothing was published before, there's nothing to offer.
        assertFalse(FollowHistoryGuard.isSubstantialDrop(current = 0, previous = 0))
    }

    @Test
    fun substantialDrop_needsAbsoluteFloor() {
        // 12 → 8: proportionally > 50% (8 is 66%). And only 4 lost. Not flagged.
        assertFalse(FollowHistoryGuard.isSubstantialDrop(current = 8, previous = 12))
        // 20 → 17: only 3 lost — below the absolute floor.
        assertFalse(FollowHistoryGuard.isSubstantialDrop(current = 17, previous = 20))
    }

    @Test
    fun substantialDrop_needsRatioAndFloor() {
        // 30 → 14: less than half (14 < 15) and 16 lost. Flagged.
        assertTrue(FollowHistoryGuard.isSubstantialDrop(current = 14, previous = 30))
        // 30 → 16: 16 is > 50% of 30, so not "substantial" despite 14 lost.
        assertFalse(FollowHistoryGuard.isSubstantialDrop(current = 16, previous = 30))
    }

    @Test
    fun substantialDrop_growthIsNeverADrop() {
        assertFalse(FollowHistoryGuard.isSubstantialDrop(current = 500, previous = 400))
        assertFalse(FollowHistoryGuard.isSubstantialDrop(current = 400, previous = 400))
    }

    // -- followedPubkeys --

    @Test
    fun followedPubkeys_dedupesPreservingOrder() {
        val event = NostrEvent(
            id = "0".repeat(64),
            pubkey = "me",
            created_at = 0,
            kind = 3,
            tags = listOf(
                listOf("p", "alice"),
                listOf("e", "noteid"),          // wrong tag type
                listOf("p", "bob"),
                listOf("p", "alice"),           // duplicate
                listOf("p"),                    // malformed (too short)
                listOf("p", ""),                // empty pubkey
                listOf("client", "Wisp"),
                listOf("p", "carol")
            ),
            content = "",
            sig = ""
        )
        assertEquals(listOf("alice", "bob", "carol"), FollowHistoryGuard.followedPubkeys(event))
    }

    // -- bestVersion --

    @Test
    fun bestVersion_picksLargestBeatingCurrent() {
        val events = listOf(
            kind3(listOf("a", "b")),                       // 2
            kind3(listOf("a", "b", "c", "d", "e")),        // 5  <- best
            kind3(listOf("a", "b", "c"))                   // 3
        )
        val best = FollowHistoryGuard.bestVersion(events, beating = 2)
        assertEquals(5, best?.count)
    }

    @Test
    fun bestVersion_nilWhenNothingBeatsCurrent() {
        val events = listOf(kind3(listOf("a", "b")), kind3(listOf("a")))
        assertNull(FollowHistoryGuard.bestVersion(events, beating = 2))
    }

    @Test
    fun bestVersion_ignoresNonKind3() {
        val events = listOf(
            kind3(listOf("a", "b", "c", "d", "e"), kind = 30000),  // not a contact list
            kind3(listOf("a", "b"))
        )
        assertEquals(2, FollowHistoryGuard.bestVersion(events, beating = 1)?.count)
    }
}
