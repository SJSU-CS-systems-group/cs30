package com.cs30.server.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/** Policy for the TA dashboard. See `cs30.ta.*` in application.properties. */
@Component
class TaDashboardSettings(
    /** A problem whose test data is at least this many bytes is flagged large, so the dashboard warns it may load slowly. */
    @Value("\${cs30.ta.large-problem-bytes:5000000}")
    val largeProblemBytes: Long,
)
