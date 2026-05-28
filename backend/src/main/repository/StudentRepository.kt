package com.cs30.server.repository

import com.cs30.server.models.Student
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface StudentRepository : JpaRepository<Student, String> {
    fun findByEmail(email: String): Student?
    fun findByFirstName(firstName: String): List<Student>
    fun findByLastName(lastName: String): List<Student>
    fun findByFirstNameAndLastName(firstName: String, lastName: String): List<Student>
}