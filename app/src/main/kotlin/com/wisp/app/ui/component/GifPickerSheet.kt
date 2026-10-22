package com.wisp.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.wisp.app.repo.GiphyRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The composer's GIF picker behind the attach row's boxed "GIF" label —
 * Giphy search + trending, the Giphy-backed picker the iOS app ships. Tapping
 * a result hands the best rendition's URL to [onPick], which downloads and
 * re-hosts it through the standard Blossom pipeline so notes don't hotlink
 * Giphy's anonymous-quota CDN.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GifPickerSheet(
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val repo = remember { GiphyRepository(context.applicationContext) }
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var stickers by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<GiphyRepository.Gif>>(emptyList()) }
    var offset by remember { mutableIntStateOf(0) }
    var hasMore by remember { mutableStateOf(true) }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var searchJob by remember { mutableStateOf<Job?>(null) }

    fun load(query: String, stickers: Boolean, nextOffset: Int) {
        scope.launch {
            if (nextOffset == 0) loading = true else loadingMore = true
            error = null
            try {
                val page = if (query.isBlank()) {
                    repo.trending(stickers = stickers, offset = nextOffset)
                } else {
                    repo.search(query.trim(), stickers = stickers, offset = nextOffset)
                }
                results = if (nextOffset == 0) page else results + page
                offset = nextOffset + page.size
                hasMore = page.size >= 30
            } catch (e: Exception) {
                if (nextOffset == 0) results = emptyList()
                error = e.message ?: "Couldn't load GIFs"
            } finally {
                loading = false
                loadingMore = false
            }
        }
    }

    // Fresh load on open, and on (debounced) query / type changes.
    LaunchedEffect(query, stickers) {
        searchJob?.cancel()
        searchJob = launch {
            delay(if (query.isBlank()) 0 else 400)
            load(query, stickers, 0)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search Giphy") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            )

            Spacer(Modifier.height(8.dp))

            // GIFs / Stickers, the pill pair style from the poll picker.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                horizontalArrangement = Arrangement.Center
            ) {
                listOf(false to "GIFs", true to "Stickers").forEach { (isSticker, label) ->
                    val selected = stickers == isSticker
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer
                               else Color.Transparent,
                        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                       else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { if (!selected) stickers = isSticker }
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelLarge,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            val gridState = rememberLazyGridState()
            // Endless scroll: page in when the viewport nears the bottom.
            val endReached by remember {
                derivedStateOf {
                    val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                    results.isNotEmpty() && last >= results.size - 6
                }
            }
            LaunchedEffect(endReached) {
                if (endReached && hasMore && !loading && !loadingMore) load(query, stickers, offset)
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 320.dp, max = 460.dp)
            ) {
                when {
                    loading -> CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(32.dp),
                        strokeWidth = 3.dp
                    )
                    error != null -> Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(16.dp)
                    ) {
                        Text(
                            error ?: "",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        TextButton(onClick = { load(query, stickers, 0) }) { Text("Retry") }
                    }
                    results.isEmpty() -> Text(
                        if (query.isBlank()) "No trending GIFs right now."
                        else "No GIFs matched \"$query\".",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(16.dp)
                    )
                    else -> LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        state = gridState,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 460.dp)
                            .padding(horizontal = 16.dp)
                    ) {
                        items(results) { gif ->
                            AsyncImage(
                                model = gif.previewUrl,
                                contentDescription = gif.title ?: "GIF",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(4f / 3f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { gif.bestGifUrl?.let(onPick) }
                            )
                        }
                        if (loadingMore) {
                            item(span = { GridItemSpan(2) }) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp)
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier
                                            .align(Alignment.Center)
                                            .size(24.dp),
                                        strokeWidth = 2.dp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
