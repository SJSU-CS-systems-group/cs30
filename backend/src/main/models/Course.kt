package com.cs30.server.models

import jakarta.persistence.*
import java.time.LocalDateTime
import java.util.UUID.randomUUID

@Entity
@Table(name = "courses")
data class Course(
    @Id
    val id: String = randomUUID().toString(),
    val name: String = "",
    val section: Int = 0,
    val startDate: LocalDateTime = LocalDateTime.now(),
    val endDate: LocalDateTime = LocalDateTime.now(),
    val githubProblemsUrl: String = "",
    val githubSubmissionsUrl: String = "",
    @ManyToMany(cascade = [CascadeType.PERSIST, CascadeType.MERGE], fetch = FetchType.LAZY)
    @JoinTable(
        name = "course_students",
        joinColumns = [JoinColumn(name = "course_id")],
        inverseJoinColumns = [JoinColumn(name = "student_email")]
    )
    val students: MutableSet<Student> = mutableSetOf()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Course) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "Course(id='$id', name='$name', section=$section)"
}
