package com.cs30.server.models

import jakarta.persistence.*
import java.time.LocalDateTime
import java.util.UUID.randomUUID

@Entity
@Table(name = "problems")
data class Problem(
    @Id
    val id: String = randomUUID().toString(),
    val name: String = "",
    var language: String = "",
    var note: String? = null,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lab_id")
    var lab: ScheduledLab? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Problem) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "Problem(id='$id', name='$name', language='$language')"
}

@Entity
@Table(name = "scheduled_labs")
data class ScheduledLab(
    @Id
    val id: String = randomUUID().toString(),
    val labNumber: Int = 0,
    var startDateTime: LocalDateTime = LocalDateTime.now(),
    var endDateTime: LocalDateTime = LocalDateTime.now(),
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "course_id")
    var course: Course? = null,
    @OneToMany(mappedBy = "lab", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.EAGER)
    val problems: MutableList<Problem> = mutableListOf()
) {
    /** Whether the lab is currently active based on current time vs start/end times */
    @get:Transient
    val isActive: Boolean
        get() = LocalDateTime.now().let { now -> now >= startDateTime && now <= endDateTime }
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ScheduledLab) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "ScheduledLab(id='$id', labNumber=$labNumber)"

    fun addProblem(problem: Problem) {
        problems.add(problem)
        problem.lab = this
    }

    fun removeProblem(problem: Problem) {
        problems.remove(problem)
        problem.lab = null
    }
}

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
    var taEmail: String? = null,
    @ElementCollection
    @CollectionTable(name = "course_students", joinColumns = [JoinColumn(name = "course_id")])
    @Column(name = "student_email")
    val students: MutableSet<String> = mutableSetOf(),
    @OneToMany(mappedBy = "course", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.EAGER)
    val labs: MutableList<ScheduledLab> = mutableListOf(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Course) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "Course(id='$id', name='$code', section=$section)"

    fun addLab(lab: ScheduledLab) {
        labs.add(lab)
        lab.course = this
    }

    fun removeLab(lab: ScheduledLab) {
        labs.remove(lab)
        lab.course = null
    }
}
