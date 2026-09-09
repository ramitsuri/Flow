package io.github.aedev.flow.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavController
import io.github.aedev.flow.data.shorts.queue.ShortsQueueSource
import kotlinx.coroutines.delay

@Composable
fun HandleDeepLinks(
    deeplinkVideoId: String?,
    isShort: Boolean,
    navController: NavController,
    onDeeplinkConsumed: () -> Unit,
) {
    LaunchedEffect(deeplinkVideoId, isShort) {
        if (deeplinkVideoId != null) {
            val maxAttempts = 30
            var navigated = false
            for (attempt in 1..maxAttempts) {
                delay(100L)
                try {
                    if (navController.currentDestination != null) {
                        if (isShort) {
                            val src = Uri.encode(ShortsQueueSource.SeededFeed(deeplinkVideoId).encode())
                            navController.navigate("shorts?src=$src") {
                                launchSingleTop = true
                            }
                        } else {
                            navController.navigate("player/$deeplinkVideoId") {
                                launchSingleTop = true
                            }
                        }
                        navigated = true
                        break
                    }
                } catch (e: Exception) {
                    android.util.Log.w(
                        "HandleDeepLinks",
                        "Navigation attempt $attempt failed for $deeplinkVideoId: ${e.message}",
                    )
                }
            }
            if (!navigated) {
                android.util.Log.e(
                    "HandleDeepLinks",
                    "Navigation failed after $maxAttempts attempts for: $deeplinkVideoId",
                )
            }
            onDeeplinkConsumed()
        }
    }
}
