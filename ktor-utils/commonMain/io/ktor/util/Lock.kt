package io.ktor.util

@InternalAPI
expect class Lock() {
    fun lock()
    fun unlock()
}

@InternalAPI
inline fun <R> Lock.use(block: () -> R): R {
    try {
        lock()
        return block()
    } finally {
        unlock()
    }
}
