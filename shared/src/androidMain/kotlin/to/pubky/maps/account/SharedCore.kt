package to.pubky.maps.account

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import to.pubky.maps.core.PubkyMapsCore

/**
 * The one Rust core of the process (the core expects one per process), built by [build] when first
 * asked for and shared by the account and the map links. A failed build is not kept: the next caller
 * tries again.
 */
class SharedCore(
    private val build: suspend () -> PubkyMapsCore,
) {
    private val mutex = Mutex()

    @Volatile
    private var core: PubkyMapsCore? = null

    suspend fun get(): PubkyMapsCore = core ?: mutex.withLock { core ?: build().also { core = it } }
}
