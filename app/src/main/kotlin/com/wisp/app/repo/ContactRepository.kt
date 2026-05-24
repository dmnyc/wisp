package com.wisp.app.repo

import android.content.Context
import android.content.SharedPreferences
import com.wisp.app.nostr.ClientMessage
import com.wisp.app.nostr.Nip02
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.NostrSigner
import com.wisp.app.relay.RelayConfig
import com.wisp.app.relay.RelayPool
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

class ContactRepository(private val context: Context, pubkeyHex: String? = null) {
    private var prefs: SharedPreferences =
        context.getSharedPreferences(prefsName(pubkeyHex), Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _followList = MutableStateFlow<List<Nip02.FollowEntry>>(emptyList())
    val followList: StateFlow<List<Nip02.FollowEntry>> = _followList

    private var followSet = HashSet<String>()
    private var lastUpdated: Long = 0

    init {
        loadFromPrefs()
    }

    fun updateFromEvent(event: NostrEvent) {
        if (event.kind != 3) return
        if (event.created_at <= lastUpdated) return
        val entries = Nip02.parseFollowList(event)
        _followList.value = entries
        followSet = HashSet(entries.map { it.pubkey })
        lastUpdated = event.created_at
        saveToPrefs(entries)
    }

    fun isFollowing(pubkey: String): Boolean = followSet.contains(pubkey)

    fun getFollowList(): List<Nip02.FollowEntry> = _followList.value

    /**
     * Republish a recovered contact list verbatim and make it the local
     * source of truth. Unlike the incremental follow/unfollow paths this
     * preserves the recovered ordering instead of round-tripping through
     * a set, so a restored list reads the same as the original.
     *
     * Used by [FollowHistoryGuard] when the user accepts a restore offer.
     * Self-pubkey is appended if absent (matches onboarding/finishOnboarding).
     */
    suspend fun restoreFollows(
        pubkeys: List<String>,
        signer: NostrSigner,
        relayPool: RelayPool,
        clientTagEnabled: Boolean
    ): NostrEvent {
        val seen = HashSet<String>()
        val ordered = ArrayList<String>(pubkeys.size + 1)
        for (pk in pubkeys) {
            if (pk.isEmpty()) continue
            if (seen.add(pk)) ordered.add(pk)
        }
        val myPubkey = signer.pubkeyHex
        if (seen.add(myPubkey)) ordered.add(myPubkey)

        val entries = ordered.map { Nip02.FollowEntry(it) }
        val tags = Nip02.buildFollowTags(entries).toMutableList()
        if (clientTagEnabled) tags.add(listOf("client", "Wisp"))

        val event = signer.signEvent(kind = 3, content = "", tags = tags)
        updateFromEvent(event)

        val msg = ClientMessage.event(event)
        relayPool.sendToWriteRelays(msg)
        for (url in RelayConfig.DEFAULT_INDEXER_RELAYS) {
            relayPool.sendToRelayOrEphemeral(url, msg)
        }
        return event
    }

    fun clear() {
        _followList.value = emptyList()
        followSet = HashSet()
        lastUpdated = 0
        prefs.edit().clear().apply()
    }

    fun reload(pubkeyHex: String?) {
        clear()
        prefs = context.getSharedPreferences(prefsName(pubkeyHex), Context.MODE_PRIVATE)
        loadFromPrefs()
    }

    private fun saveToPrefs(entries: List<Nip02.FollowEntry>) {
        val serializable = entries.map { SerializableFollow(it.pubkey, it.relayHint, it.petname) }
        prefs.edit()
            .putString("follows", json.encodeToString(serializable))
            .putLong("follows_updated", lastUpdated)
            .apply()
    }

    private fun loadFromPrefs() {
        lastUpdated = prefs.getLong("follows_updated", 0)
        val str = prefs.getString("follows", null) ?: return
        try {
            val serializable = json.decodeFromString<List<SerializableFollow>>(str)
            val entries = serializable.map { Nip02.FollowEntry(it.pubkey, it.relayHint, it.petname) }
            _followList.value = entries
            followSet = HashSet(entries.map { it.pubkey })
        } catch (_: Exception) {}
    }

    @Serializable
    private data class SerializableFollow(
        val pubkey: String,
        val relayHint: String? = null,
        val petname: String? = null
    )

    companion object {
        private fun prefsName(pubkeyHex: String?): String =
            if (pubkeyHex != null) "wisp_contacts_$pubkeyHex" else "wisp_contacts"
    }
}
