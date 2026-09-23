package dev.narayan.rose

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class SearchFilter(
    val label: String,
    val icon: ImageVector,
    val accentColor: Color,
    val fileType: FileType?
) {
    ALL("All", Icons.Default.GridView, Color(0xFF3D72ED), null),
    PHOTOS("Photos", Icons.Default.Image, Color(0xFF4C8DFF), FileType.IMAGE),
    VIDEOS("Videos", Icons.Default.VideoLibrary, Color(0xFF9C6ADE), FileType.VIDEO),
    DOCUMENTS("Documents", Icons.Default.Description, Color(0xFF4C9A72), FileType.DOCUMENT),
    ARCHIVES("Archives", Icons.Default.Archive, Color(0xFFC98A2E), FileType.ZIP),
    APKS("APKs", Icons.Default.Android, Color(0xFF4CAF7D), FileType.APK);

    fun matches(item: FileItem): Boolean {
        return when (this) {
            ALL -> true
            PHOTOS -> item.matchesCategory(FileType.IMAGE)
            VIDEOS -> item.matchesCategory(FileType.VIDEO)
            DOCUMENTS -> item.matchesCategory(FileType.DOCUMENT)
            ARCHIVES -> item.matchesCategory(FileType.ZIP)
            APKS -> item.matchesCategory(FileType.APK)
        }
    }
}

fun filterSearchResults(results: List<FileItem>, filter: SearchFilter): List<FileItem> {
    if (filter == SearchFilter.ALL) return results
    return results.filter { filter.matches(it) }
}

fun computeSearchFilterCounts(results: List<FileItem>): Map<SearchFilter, Int> {
    val counts = mutableMapOf<SearchFilter, Int>()
    counts[SearchFilter.ALL] = results.size
    var photos = 0
    var videos = 0
    var documents = 0
    var archives = 0
    var apks = 0

    for (item in results) {
        when {
            item.matchesCategory(FileType.IMAGE) -> photos++
            item.matchesCategory(FileType.VIDEO) -> videos++
            item.matchesCategory(FileType.DOCUMENT) -> documents++
            item.matchesCategory(FileType.ZIP) -> archives++
            item.matchesCategory(FileType.APK) -> apks++
        }
    }

    counts[SearchFilter.PHOTOS] = photos
    counts[SearchFilter.VIDEOS] = videos
    counts[SearchFilter.DOCUMENTS] = documents
    counts[SearchFilter.ARCHIVES] = archives
    counts[SearchFilter.APKS] = apks
    return counts
}

@Composable
fun SearchFilterChipsRow(
    selectedFilter: SearchFilter,
    onFilterSelected: (SearchFilter) -> Unit,
    counts: Map<SearchFilter, Int>,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
) {
    val scrollState = rememberScrollState()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SearchFilter.entries.forEach { filter ->
            val isSelected = filter == selectedFilter
            val count = counts[filter] ?: 0

            val animatedBg by animateColorAsState(
                targetValue = if (isSelected) {
                    filter.accentColor.copy(alpha = 0.16f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f)
                },
                animationSpec = tween(180, easing = FastOutSlowInEasing),
                label = "FilterChipBg"
            )

            val animatedBorderColor by animateColorAsState(
                targetValue = if (isSelected) {
                    filter.accentColor
                } else {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                },
                animationSpec = tween(180, easing = FastOutSlowInEasing),
                label = "FilterChipBorder"
            )

            val animatedContentColor by animateColorAsState(
                targetValue = if (isSelected) {
                    filter.accentColor
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                animationSpec = tween(180, easing = FastOutSlowInEasing),
                label = "FilterChipContent"
            )

            Surface(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .clickable { onFilterSelected(filter) }
                    .border(
                        BorderStroke(if (isSelected) 1.5.dp else 1.dp, animatedBorderColor),
                        shape = RoundedCornerShape(20.dp)
                    ),
                shape = RoundedCornerShape(20.dp),
                color = animatedBg
            ) {
                Row(
                    modifier = Modifier
                        .height(34.dp)
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = filter.icon,
                        contentDescription = filter.label,
                        tint = animatedContentColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = filter.label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        color = animatedContentColor
                    )

                    if (count > 0) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(
                                    if (isSelected) filter.accentColor else MaterialTheme.colorScheme.surfaceContainerHighest
                                )
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (count > 99) "99+" else "$count",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 10.sp,
                                    lineHeight = 12.sp,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SearchLoadingView(
    query: String,
    categoryName: String? = null,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "search_loading_pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.65f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(24.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(72.dp)
            ) {
                // Outer subtle glowing/pulsing circle
                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .graphicsLayer(scaleX = pulseScale, scaleY = pulseScale, alpha = pulseAlpha)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f))
                )
                // Center icon
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(52.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
                // Spinner around the icon
                CircularProgressIndicator(
                    modifier = Modifier.size(68.dp),
                    strokeWidth = 2.5.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            val titleText = if (categoryName != null) {
                "Searching in $categoryName..."
            } else {
                "Searching files..."
            }
            Text(
                text = titleText,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )

            if (query.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Looking for \"$query\"",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
            }
        }
    }
}

@Composable
fun SearchEmptyStateView(
    query: String,
    filter: SearchFilter = SearchFilter.ALL,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(24.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.size(56.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.SearchOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            val mainMessage = if (filter == SearchFilter.ALL) {
                "No results found for \"$query\""
            } else {
                "No ${filter.label.lowercase()} found for \"$query\""
            }

            Text(
                text = mainMessage,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = if (filter != SearchFilter.ALL) "Try switching filter to 'All'" else "Check the spelling or try another query",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

