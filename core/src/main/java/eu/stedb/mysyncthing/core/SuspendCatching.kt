package eu.stedb.mysyncthing.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * [runCatching] for work that suspends. A cancelled coroutine stops here: plain
 * runCatching catches its cancellation too, so the caller would go on to retry, report
 * a failure or use a default for work that was only called off. A
 * [CancellationException] that is not this coroutine's own (a timeout inside [block],
 * a cancelled job it waited for) is a failure like any other. Code that cannot suspend
 * (parsing, file calls) keeps runCatching.
 */
suspend inline fun <T> runSuspendCatching(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    currentCoroutineContext().ensureActive()
    Result.failure(e)
} catch (e: Throwable) {
    Result.failure(e)
}
