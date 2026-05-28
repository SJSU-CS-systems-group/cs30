package labx.lockdown

internal actual fun currentEpochMs(): Long = dateNow().toLong()

private fun dateNow(): Double = js("Date.now()")
