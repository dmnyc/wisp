package com.wisp.app.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wisp.app.repo.FollowRestoreCandidate
import kotlin.math.max

/**
 * Modal sheet surfaced when [com.wisp.app.repo.FollowHistoryGuard] detects that
 * the active account's follow list looks clobbered (substantially smaller than
 * a recoverable version from relay history). The user can either restore the
 * larger list — republishing it as a fresh kind-3 — or keep what they arrived
 * with, in which case the smaller list becomes the new baseline.
 *
 * Cross-platform parity with iOS `FollowRestorePromptSheet.swift` — same copy
 * patterns, same plural-aware wording, same relative-date phrasing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FollowRestorePromptSheet(
    candidate: FollowRestoreCandidate,
    currentCount: Int,
    onRestore: () -> Unit,
    onKeep: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var isRestoring by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = {
            if (!isRestoring) onDismiss()
        },
        sheetState = sheetState,
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.History,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
            )

            Text(
                text = "Your follow list looks shorter than usual",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface
            )

            Text(
                text = bodyText(candidate = candidate, currentCount = currentCount),
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = {
                    isRestoring = true
                    onRestore()
                },
                enabled = !isRestoring,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                if (isRestoring) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.size(8.dp))
                    Text("Restoring…", fontWeight = FontWeight.SemiBold)
                } else {
                    Text(
                        text = "Restore ${candidate.count} ${followsWord(candidate.count)}",
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            OutlinedButton(
                onClick = onKeep,
                enabled = !isRestoring,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (currentCount == 0) "Start fresh"
                           else "Keep $currentCount ${followsWord(currentCount)}",
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

private fun bodyText(candidate: FollowRestoreCandidate, currentCount: Int): String {
    val when_ = relativeDate(candidate.createdAt)
    val backup = "${candidate.count} ${followsWord(candidate.count)}"
    return if (currentCount == 0) {
        "Wisp found a backup with $backup from $when_. Another app may have cleared your contact list while you were away. Would you like to restore it?"
    } else {
        val loaded = if (currentCount == 1) "only 1 is loaded right now"
                     else "only $currentCount are loaded right now"
        "Wisp found a backup with $backup from $when_, but $loaded. Another app may have shortened your contact list. Restore the larger version?"
    }
}

private fun followsWord(n: Int): String = if (n == 1) "follow" else "follows"

private fun relativeDate(createdAtSeconds: Long): String {
    val nowSeconds = System.currentTimeMillis() / 1000
    val diffSeconds = max(1L, nowSeconds - createdAtSeconds)
    val days = diffSeconds / 86_400
    val hours = diffSeconds / 3_600
    val minutes = diffSeconds / 60
    return when {
        days >= 365 -> {
            val years = days / 365
            if (years == 1L) "1 year ago" else "$years years ago"
        }
        days >= 30 -> {
            val months = days / 30
            if (months == 1L) "1 month ago" else "$months months ago"
        }
        days >= 1 -> if (days == 1L) "1 day ago" else "$days days ago"
        hours >= 1 -> if (hours == 1L) "1 hour ago" else "$hours hours ago"
        minutes >= 1 -> if (minutes == 1L) "1 minute ago" else "$minutes minutes ago"
        else -> "just now"
    }
}
