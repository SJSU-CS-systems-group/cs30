package com.cs30.server.models

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table

/**
 * Maps one cs30 enrollment email to the Canvas student id to match instead of the email, for a
 * student whose Canvas account carries a different address (typically a personal one). Read by
 * submissions2canvas through CanvasSyncController and managed with the addoverride /
 * removeoverride / listoverrides CLI commands.
 *
 * Global rather than per course: a student's Canvas identity does not change between sections,
 * and living in its own table keeps it out of reach of addcourse, which rebuilds course rosters
 * wholesale from the course file.
 */
@Entity
@Table(name = "student_overrides")
data class StudentOverride(
    /** The enrollment email, stored lowercased so lookups never depend on how it was typed. */
    @Id
    val email: String = "",
    @Column(name = "student_id")
    var studentId: String = "",
)
