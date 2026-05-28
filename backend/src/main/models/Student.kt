package com.cs30.server.models

import jakarta.persistence.*

@Entity
@Table(name = "students")
data class Student(
    @Id
    val email: String = "",
    val firstName: String = "",
    val lastName: String = "",
    @ManyToMany(mappedBy = "students", fetch = FetchType.LAZY)
    val courses: MutableSet<Course> = mutableSetOf()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Student) return false
        return email == other.email
    }

    override fun hashCode(): Int = email.hashCode()

    override fun toString(): String = "Student(email='$email', firstName='$firstName', lastName='$lastName')"
}