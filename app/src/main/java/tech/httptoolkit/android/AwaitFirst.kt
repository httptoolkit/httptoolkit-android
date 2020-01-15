package tech.httptoolkit.android

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.util.concurrent.atomic.AtomicInteger

suspend fun <T>Collection<Deferred<T>>.awaitFirst(): T {
    // Create a custom deferred to return later
    val result = CompletableDeferred<T>()
    val stillRunningCount = AtomicInteger(this.size)

    // When the first item completes successfully, we complete the returned deferred.
    // If all items fail, we reject the deferred with the final error.
    this.forEach { item ->
        item.invokeOnCompletion { error ->
            synchronized(result) {
                if (!result.isActive) return@invokeOnCompletion

                if (error == null) {
                    @Suppress("EXPERIMENTAL_API_USAGE")
                    result.complete(item.getCompleted())
                } else {
                    val remaining = stillRunningCount.decrementAndGet()
                    if (remaining == 0) {
                        result.completeExceptionally(error)
                    }
                }
                return@synchronized // Avoid issues with implicit return of above
            }
        }
    }

    return result.await()
}