package com.wisp.app.repo

import android.util.Log
import com.wisp.app.nostr.ClientMessage
import com.wisp.app.nostr.Nip78
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.NostrSigner
import com.wisp.app.nostr.RelayMessage
import com.wisp.app.relay.RelayEvent
import com.wisp.app.relay.RelayPool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield

/**
 * NIP-78 cross-device sync of UI preferences. Publishes a single
 * NIP-44-encrypted kind 30078 event addressed by the
 * `wisp-app-settings:v1` d-tag whenever any synced setting changes
 * (debounced 4s — matches iOS). On launch, fetches and applies the
 * remote backup non-destructively (each field has its own guard so
 * missing values stay at the local default).
 *
 * Only fires when [InterfacePreferences.isSyncSettingsToRelays] is
 * true. The toggle defaults to on. The user can disable it from the
 * "Cross-device sync" section of the Interface settings screen.
 */
class AppSettingsRepository(
    private val interfacePrefs: InterfacePreferences,
    private val fiatPrefs: FiatPreferences,
    private val zapPrefs: ZapPreferences
) {
    private val TAG = "AppSettingsSync"

    /** Active signer. Set by FeedViewModel after the user logs in / out. */
    @Volatile
    var signer: NostrSigner? = null

    /** Relay pool to publish through / read from. Set once at construction. */
    @Volatile
    var relayPool: RelayPool? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var debounceJob: Job? = null

    init {
        // Register sync callbacks on every prefs source. Any synced-field
        // setter (e.g. `interfacePrefs.setLargeText(...)`) will now bounce
        // off `scheduleSettingsSync()` automatically.
        interfacePrefs.onSyncedFieldChanged = { scheduleSettingsSync() }
        fiatPrefs.onSyncedFieldChanged = { scheduleSettingsSync() }
        zapPrefs.onSyncedFieldChanged = { scheduleSettingsSync() }
    }

    /**
     * Schedule a debounced sync. Called by setting setters whenever a
     * synced field mutates. Coalesces rapid edits — only the last
     * `scheduleSettingsSync()` in a 4s window actually publishes.
     */
    fun scheduleSettingsSync() {
        if (!interfacePrefs.isSyncSettingsToRelays()) return
        val s = signer ?: return
        val pool = relayPool ?: return

        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(DEBOUNCE_MS)
            try {
                publishNow(s, pool)
            } catch (e: Exception) {
                Log.w(TAG, "publish failed: ${e.message}")
            }
        }
    }

    /** Build the current payload from all synced prefs sources. */
    private fun buildPayload(): Nip78.AppSettingsPayload {
        val mediaLayout = interfacePrefs.getMediaLayoutStyle().key
        return Nip78.AppSettingsPayload(
            zapIconStyle = if (interfacePrefs.isZapBoltIcon()) "bolt" else "default",
            largeText = interfacePrefs.isLargeText(),
            themeName = interfacePrefs.getTheme(),
            accentColorARGB = interfacePrefs.getAccentColor(),
            autoLoadMedia = interfacePrefs.isAutoLoadMedia(),
            videoAutoplay = interfacePrefs.isVideoAutoPlay(),
            mediaLayoutStyle = mediaLayout,
            clientTagEnabled = interfacePrefs.isClientTagEnabled(),
            postUndoTimerEnabled = interfacePrefs.isPostUndoTimerEnabled(),
            postUndoTimerSeconds = interfacePrefs.getPostUndoTimerSeconds(),
            postUndoTimerForReplies = interfacePrefs.isPostUndoTimerForReplies(),
            fiatModeEnabled = fiatPrefs.isFiatMode(),
            fiatCurrency = fiatPrefs.getCurrency(),
            zapPresetsCSV = zapPrefs.toCSV(),
            version = 1
        )
    }

    private suspend fun publishNow(signer: NostrSigner, pool: RelayPool) {
        pool.ensureWriteRelaysConnected()
        val payload = buildPayload()
        val event = Nip78.createAppSettingsEvent(signer, payload)
        val sent = pool.sendToWriteRelays(ClientMessage.event(event))
        Log.d(TAG, "publish sent=$sent")
    }

    /**
     * Fetch the latest backup for this user and apply it
     * non-destructively. Called by StartupCoordinator on launch / account
     * switch. Each field has its own null-guard so missing values keep
     * the local default — adding a new field on iOS won't wipe its value
     * on Android (and vice-versa).
     */
    suspend fun restoreSettingsBackup() {
        val s = signer ?: return
        val pool = relayPool ?: return
        if (!interfacePrefs.isSyncSettingsToRelays()) return

        try {
            pool.ensureWriteRelaysConnected()
        } catch (_: Exception) { /* best-effort */ }

        val pubkey = s.pubkeyHex
        val subId = "app-settings-${System.currentTimeMillis()}"
        val filter = Nip78.appSettingsFilter(pubkey)
        val seen = mutableSetOf<String>()
        val events = mutableListOf<NostrEvent>()
        var eoseCount = 0

        val collectJob = scope.launch {
            pool.relayEvents.collect { re: RelayEvent ->
                if (re.subscriptionId == subId && seen.add(re.event.id)) events.add(re.event)
            }
        }
        val eoseJob = scope.launch {
            pool.eoseSignals.collect { id ->
                if (id == subId) eoseCount++
            }
        }
        yield()
        val total = pool.getRelayUrls().size
        val minEose = (total * 2 + 2) / 3
        pool.sendToAll(ClientMessage.req(subId, filter))
        withTimeoutOrNull(8_000) {
            while (eoseCount < total) {
                delay(150)
                if (eoseCount >= minEose) break
            }
        }
        collectJob.cancel()
        eoseJob.cancel()
        pool.closeOnAllRelays(subId)

        val newest = events
            .filter { Nip78.extractDTag(it) == Nip78.APP_SETTINGS_D_TAG }
            .maxByOrNull { it.created_at } ?: return

        val payload = Nip78.decryptAppSettings(s, newest) ?: return
        applyPayload(payload)
    }

    /**
     * Apply a decoded payload to local prefs. Each field is guarded —
     * a null in the payload preserves the local value, so older devices
     * with fewer fields don't wipe newer ones.
     *
     * Sync callbacks are temporarily detached so the restore doesn't
     * trigger an immediate re-publish loop. (ZapPrefs `applyCSV` has its
     * own suppress flag.)
     */
    private fun applyPayload(p: Nip78.AppSettingsPayload) {
        val iface = interfacePrefs.onSyncedFieldChanged
        val fiat = fiatPrefs.onSyncedFieldChanged
        val zap = zapPrefs.onSyncedFieldChanged
        interfacePrefs.onSyncedFieldChanged = null
        fiatPrefs.onSyncedFieldChanged = null
        zapPrefs.onSyncedFieldChanged = null
        try {
            p.zapIconStyle?.let { interfacePrefs.setZapBoltIcon(it == "bolt") }
            p.largeText?.let { interfacePrefs.setLargeText(it) }
            p.themeName?.let { interfacePrefs.setTheme(it) }
            p.accentColorARGB?.let { interfacePrefs.setAccentColor(it) }
            p.autoLoadMedia?.let { interfacePrefs.setAutoLoadMedia(it) }
            p.videoAutoplay?.let { interfacePrefs.setVideoAutoPlay(it) }
            p.mediaLayoutStyle?.let {
                interfacePrefs.setMediaLayoutStyle(InterfacePreferences.MediaLayoutStyle.fromKey(it))
            }
            p.clientTagEnabled?.let { interfacePrefs.setClientTagEnabled(it) }
            p.postUndoTimerEnabled?.let { interfacePrefs.setPostUndoTimerEnabled(it) }
            p.postUndoTimerSeconds?.let { interfacePrefs.setPostUndoTimerSeconds(it) }
            p.postUndoTimerForReplies?.let { interfacePrefs.setPostUndoTimerForReplies(it) }
            p.fiatModeEnabled?.let { fiatPrefs.setFiatMode(it) }
            p.fiatCurrency?.let { fiatPrefs.setCurrency(it) }
            p.zapPresetsCSV?.let { zapPrefs.applyCSV(it) }
        } finally {
            interfacePrefs.onSyncedFieldChanged = iface
            fiatPrefs.onSyncedFieldChanged = fiat
            zapPrefs.onSyncedFieldChanged = zap
        }
    }

    /** Switch the active account. Re-points the per-user zap prefs and clears the debounce. */
    fun reload(pubkeyHex: String?) {
        debounceJob?.cancel()
        zapPrefs.reload(pubkeyHex)
    }

    fun close() {
        debounceJob?.cancel()
        scope.cancel()
    }

    companion object {
        private const val DEBOUNCE_MS = 4_000L
    }
}
