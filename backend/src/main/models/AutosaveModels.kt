package com.cs30.server.models

data class AutosaveRequest(
    val problemSlug: String,
    val code: String,
    val language: String,
)
