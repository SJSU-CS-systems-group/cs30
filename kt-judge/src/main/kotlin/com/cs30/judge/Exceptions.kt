package com.cs30.judge

// Bad request the caller can fix -> HTTP 400.
class JudgeError(message: String) : RuntimeException(message)

// Too many jobs in flight -> HTTP 429.
class QueueFull(message: String) : RuntimeException(message)

// Admitted but didn't finish within the caller's wait budget -> HTTP 504.
class SyncTimeout(message: String) : RuntimeException(message)
