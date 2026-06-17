package com.cs30.server.models

data class AutosaveRequest(
    val courseId: String,
    val section: Int,
    val labNumber: Int,
    val problemSlug: String,
    val code: String,
    val language: String,
)
