package com.cs30.server.models

import jakarta.persistence.*
import java.time.LocalDateTime
import java.util.UUID.randomUUID

@Entity
@Table(name = "courses")
data class Course(
    @Id
    val id: String = randomUUID().toString(),
    val name: String,
    val section: Int,
    val startDate: LocalDateTime,
    val endDate: LocalDateTime,
    val githubProblemsUrl: String,
    val githubSubmissionsUrl: String,
    @OneToMany(mappedBy = "course", cascade = [CascadeType.ALL], orphanRemoval = true)
    val students: MutableList<Student> = mutableListOf()
)