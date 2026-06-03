package com.cs30.server.models

import jakarta.persistence.*
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID.randomUUID

@Entity
@Table(name = "courses")
data class Course(
    @Id
    val id: String = randomUUID().toString(),
    val code: String = "",
    val section: Int = 0,
    @Column(name = "course_year")
    val year: Int = LocalDateTime.now().year,
    val semester: String = if (LocalDateTime.now().monthValue <= 6) "Spring" else "Fall",
    var startDate: LocalDateTime = LocalDateTime.now(),
    var endDate: LocalDateTime = LocalDateTime.now(),
    @ElementCollection
    @CollectionTable(name = "course_days", joinColumns = [JoinColumn(name = "course_id")])
    @Enumerated(EnumType.STRING)
    @Column(name = "day_of_week")
    var days: MutableSet<DayOfWeek> = mutableSetOf(),
    var startTime: LocalTime? = null,
    var endTime: LocalTime? = null,
    var githubProblemsUrl: String = "",
    var language: String = "",
    var studentGitRepo: String = "",
    @ElementCollection
    @CollectionTable(name = "course_students", joinColumns = [JoinColumn(name = "course_id")])
    @Column(name = "student_email")
    val students: MutableSet<String> = mutableSetOf(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Course) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "Course(id='$id', name='$code', section=$section)"
}
