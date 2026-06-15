package com.wisp.app.repo

import android.content.Context
import android.content.SharedPreferences
import com.wisp.app.nostr.NostrEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class SafetyPreferences(private val context: Context, pubkeyHex: String? = null) {
    private var prefs: SharedPreferences =
        context.getSharedPreferences(prefsName(pubkeyHex), Context.MODE_PRIVATE)

    private val _spamFilterEnabled = MutableStateFlow(prefs.getBoolean(KEY_SPAM_FILTER, true))
    val spamFilterEnabled: StateFlow<Boolean> = _spamFilterEnabled

    private val _wotFilterEnabled = MutableStateFlow(prefs.getBoolean(KEY_WOT_FILTER, false))
    val wotFilterEnabled: StateFlow<Boolean> = _wotFilterEnabled

    private var safelistSet =
        HashSet(prefs.getStringSet(KEY_SPAM_SAFELIST, emptySet()) ?: emptySet())
    private val _spamSafelist = MutableStateFlow<Set<String>>(safelistSet.toSet())
    val spamSafelist: StateFlow<Set<String>> = _spamSafelist

    private val _hellthreadFilterEnabled = MutableStateFlow(prefs.getBoolean(KEY_HELLTHREAD_FILTER, false))
    val hellthreadFilterEnabled: StateFlow<Boolean> = _hellthreadFilterEnabled

    private val _hellthreadThreshold = MutableStateFlow(
        prefs.getInt(KEY_HELLTHREAD_THRESHOLD, NostrEvent.HELLTHREAD_THRESHOLD_DEFAULT)
    )
    val hellthreadThreshold: StateFlow<Int> = _hellthreadThreshold

    fun setSpamFilterEnabled(enabled: Boolean) {
        _spamFilterEnabled.value = enabled
        prefs.edit().putBoolean(KEY_SPAM_FILTER, enabled).apply()
    }

    fun setWotFilterEnabled(enabled: Boolean) {
        _wotFilterEnabled.value = enabled
        prefs.edit().putBoolean(KEY_WOT_FILTER, enabled).apply()
    }

    fun setHellthreadFilterEnabled(enabled: Boolean) {
        _hellthreadFilterEnabled.value = enabled
        prefs.edit().putBoolean(KEY_HELLTHREAD_FILTER, enabled).apply()
    }

    fun setHellthreadThreshold(count: Int) {
        val clamped = count.coerceIn(HELLTHREAD_THRESHOLD_MIN, HELLTHREAD_THRESHOLD_MAX)
        _hellthreadThreshold.value = clamped
        prefs.edit().putInt(KEY_HELLTHREAD_THRESHOLD, clamped).apply()
    }

    fun isSpamSafelisted(pubkey: String): Boolean = safelistSet.contains(pubkey)

    fun addToSpamSafelist(pubkey: String) {
        safelistSet.add(pubkey)
        _spamSafelist.value = safelistSet.toSet()
        prefs.edit().putStringSet(KEY_SPAM_SAFELIST, safelistSet.toSet()).apply()
    }

    fun removeFromSpamSafelist(pubkey: String) {
        safelistSet.remove(pubkey)
        _spamSafelist.value = safelistSet.toSet()
        prefs.edit().putStringSet(KEY_SPAM_SAFELIST, safelistSet.toSet()).apply()
    }

    /** Re-point to the new account's prefs file and refresh all StateFlows. */
    fun reload(newPubkeyHex: String?) {
        prefs = context.getSharedPreferences(prefsName(newPubkeyHex), Context.MODE_PRIVATE)
        _spamFilterEnabled.value = prefs.getBoolean(KEY_SPAM_FILTER, true)
        _wotFilterEnabled.value = prefs.getBoolean(KEY_WOT_FILTER, false)
        safelistSet = HashSet(prefs.getStringSet(KEY_SPAM_SAFELIST, emptySet()) ?: emptySet())
        _spamSafelist.value = safelistSet.toSet()
        _hellthreadFilterEnabled.value = prefs.getBoolean(KEY_HELLTHREAD_FILTER, false)
        _hellthreadThreshold.value = prefs.getInt(KEY_HELLTHREAD_THRESHOLD, NostrEvent.HELLTHREAD_THRESHOLD_DEFAULT)
    }

    companion object {
        private const val KEY_SPAM_FILTER = "spam_filter_enabled"
        private const val KEY_WOT_FILTER = "wot_filter_enabled"
        private const val KEY_SPAM_SAFELIST = "spam_safelist"
        private const val KEY_HELLTHREAD_FILTER = "hellthread_filter_enabled"
        private const val KEY_HELLTHREAD_THRESHOLD = "hellthread_threshold"

        const val HELLTHREAD_THRESHOLD_MIN = 25
        const val HELLTHREAD_THRESHOLD_MAX = 100
        const val HELLTHREAD_THRESHOLD_STEP = 5

        private fun prefsName(pubkeyHex: String?): String =
            if (pubkeyHex != null) "wisp_safety_$pubkeyHex" else "wisp_safety"
    }
}
