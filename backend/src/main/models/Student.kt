package com.cs30.server.models

import jakarta.persistence.Entity
import jakarta.persistence.Id

@Entity
data class Student(
    @Id
    val email: String,
    val firstName: String,
    val lastName: String,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "course_id")
    var course: Course? = null
) {
        override fun toString(): String {
            return "Student(email='$email', firstName='$firstName', lastName='$lastName')"
        }
}