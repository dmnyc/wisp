package com.wisp.app.repo

/**
 * A Bitcoin transaction sent to the wallet's Spark deposit address that has
 * not yet been pulled into the spendable balance.
 *
 * This type exists because on-chain receive has a second half people don't
 * expect: bitcoin sent to the deposit address confirms on-chain but does
 * **not** appear in the Spark balance until it is claimed. A wallet that only
 * showed the address would leave users watching a confirmed transaction that
 * their balance never reflects.
 */
data class OnchainDeposit(
    val txid: String,
    val vout: UInt,
    val amountSats: Long,
    /** Whether the deposit has enough confirmations to be claimable yet. */
    val isMature: Boolean,
    /**
     * Outcome of an instant (0-conf) claim, once one has been attempted. Null
     * when none has been — the deposit is simply waiting for confirmations.
     */
    val instantClaim: InstantClaim? = null,
    /**
     * Set when a previous claim attempt failed. Claiming is retried by the
     * user, so the reason has to survive to be shown.
     */
    val failure: Failure? = null,
) {
    /** `txid:vout` — a transaction can pay the deposit address more than once. */
    val id: String get() = "$txid:$vout"

    sealed interface Failure {
        /**
         * On-chain fees rose above the cap the claim was willing to pay.
         * Recoverable: retry when fees fall, or accept the higher fee.
         */
        data class FeeExceeded(val requiredSats: Long) : Failure

        /**
         * The output the SDK expected is no longer there — typically an
         * unconfirmed parent that got replaced.
         */
        data object MissingUtxo : Failure

        data class Other(val text: String) : Failure

        val message: String
            get() = when (this) {
                is FeeExceeded ->
                    "On-chain fees rose above the limit. Claiming this now would cost about $requiredSats sats."
                MissingUtxo ->
                    "The transaction this deposit came from is no longer on-chain."
                is Other -> text
            }

        /**
         * Whether waiting is likely to help. A fee spike passes; a missing
         * output does not come back.
         */
        val isWorthRetrying: Boolean
            get() = when (this) {
                is FeeExceeded -> true
                MissingUtxo -> false
                is Other -> true
            }
    }

    /**
     * Status of an instant (0-conf) claim, in which the SSP fronts the
     * confirmation risk for a spread.
     *
     * We never ask for one. The SSP sells that risk at broadcast time, but the
     * SDK doesn't report a deposit until it already has a confirmation — by
     * which point there is no risk left to sell, and the request is declined
     * with no plan available. Reading the status is still worth it: if the SDK
     * ever claims a deposit this way on its own, the UI needs to know not to
     * touch it while it settles.
     */
    sealed interface InstantClaim {
        /**
         * Submitted and settling. The SDK requires that a deposit in this
         * state not be re-claimed, so the UI must not offer an action.
         */
        data object Submitted : InstantClaim
        data class Declined(val reason: Reason) : InstantClaim

        sealed interface Reason {
            /** The SSP offered no 0-conf plan for this deposit. */
            data object NoPlan : Reason
            /** The quoted spread was above the ceiling that was offered. */
            data class FeeExceeded(val quotedSats: Long, val quotedBps: Int) : Reason
            data object SubmissionFailed : Reason
        }
    }

    /** An instant claim is submitted and settling. */
    val isClaimInFlight: Boolean get() = instantClaim == InstantClaim.Submitted

    /**
     * Claimable right now: confirmed enough, no claim already in flight, and
     * not blocked by a failure that retrying won't fix.
     */
    val isClaimable: Boolean
        get() {
            if (!isMature || isClaimInFlight) return false
            return failure?.isWorthRetrying ?: true
        }
}

/** Everything the on-chain receive screen needs about pending deposits. */
data class OnchainDepositSummary(
    val deposits: List<OnchainDeposit> = emptyList(),
) {
    val isEmpty: Boolean get() = deposits.isEmpty()

    /**
     * Total still waiting to be claimed — the number a user compares against
     * what they sent.
     */
    val pendingSats: Long get() = deposits.sumOf { it.amountSats }

    /** Deposits that can be claimed right now. */
    val claimable: List<OnchainDeposit> get() = deposits.filter { it.isClaimable }

    /**
     * Confirmed but not yet claimable, so the screen can say "waiting for
     * confirmations" rather than showing a dead button.
     */
    val awaitingConfirmations: List<OnchainDeposit> get() = deposits.filter { !it.isMature }
}

/**
 * Confirmation speed for an on-chain send, mapped to the SDK's three fee
 * tiers. Kept SDK-free so the UI and tests don't import the SDK.
 */
enum class OnchainSendSpeed(val label: String, val detail: String) {
    SLOW("Economy", "Cheapest. May take hours to confirm."),
    MEDIUM("Standard", "Balanced fee and confirmation time."),
    FAST("Priority", "Highest fee. Confirms soonest."),
}

/**
 * What an on-chain send would cost, quoted before anything is signed.
 *
 * Fees are added on top of the amount — the SDK's default fees-excluded
 * policy — so the recipient gets exactly [amountSats] and the wallet spends
 * [totalSats]. That's the opposite of draining, where the fee comes out of the
 * amount, and it's why a balance check has to be against the total.
 */
data class OnchainSendQuote(
    val address: String,
    /** What actually lands at the destination. */
    val amountSats: Long,
    /** Service fee plus the L1 broadcast fee, both real cost to the user. */
    val feeSats: Long,
    val speed: OnchainSendSpeed,
    /**
     * Set when emptying the wallet would leave a token balance behind. The
     * bitcoin balance is separate from token balances, so draining strands any
     * stablecoin in a wallet this app won't convert with.
     */
    val leavesTokensBehind: Boolean = false,
) {
    /** What leaves the wallet. */
    val totalSats: Long get() = amountSats + feeSats

    /**
     * Fee as a share of the amount being sent. On-chain fees don't scale with
     * amount, so a small send can cost more in fees than it delivers.
     */
    val feeShare: Double get() = if (amountSats > 0) feeSats.toDouble() / amountSats.toDouble() else 0.0

    /** True when the fee is a large enough share of the send to warn about. */
    val isFeeDisproportionate: Boolean get() = feeShare >= 0.10
}
