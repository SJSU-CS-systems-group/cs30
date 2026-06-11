package com.cs30.server.models

import jakarta.persistence.*
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID.randomUUID

@Embeddable
data class ScheduledLab(
    val labNumber: Int = 0,
    val startDateTime: LocalDateTime = LocalDateTime.now(),
    val endDateTime: LocalDateTime = LocalDateTime.now(),
    val problems: MutableList<Problem> = mutableListOf()
)

@Embeddable
data class Problem(
    val name: String = "",
    val language: String = ""
)

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
    var language: String = "",
    var studentGitRepo: String = "",
    var problemGitRepo: String = "",
    @ElementCollection
    @CollectionTable(name = "course_students", joinColumns = [JoinColumn(name = "course_id")])
    @Column(name = "student_email")
    val students: MutableSet<String> = mutableSetOf(),
    @ElementCollection
    @CollectionTable(name = "course_labs", joinColumns = [JoinColumn(name = "course_id")])
    val labs: MutableList<ScheduledLab> = mutableListOf(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Course) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "Course(id='$id', name='$code', section=$section)"
}
