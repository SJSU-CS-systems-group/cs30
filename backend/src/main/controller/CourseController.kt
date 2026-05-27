package com.cs30.server.controller

import com.cs30.server.models.Course
import com.cs30.server.repository.CourseRepository
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/courses")
class CourseController(
    private val courseRepository: CourseRepository
) {

    @GetMapping
    fun getAllCourses(): List<Course> {
        return courseRepository.findAll()
    }

    @GetMapping("/{id}")
    fun getCourse(@PathVariable id: String): ResponseEntity<Course> {
        return courseRepository.findById(id)
            .map { ResponseEntity.ok(it) }
            .orElse(ResponseEntity.notFound().build())
    }

    @PostMapping
    fun createCourse(@RequestBody course: Course): Course {
        return courseRepository.save(course)
    }

    @PutMapping("/{id}")
    fun updateCourse(@PathVariable id: String, @RequestBody course: Course): ResponseEntity<Course> {
        return if (courseRepository.existsById(id)) {
            ResponseEntity.ok(courseRepository.save(course.copy(id = id)))
        } else {
            ResponseEntity.notFound().build()
        }
    }

    @DeleteMapping("/{id}")
    fun deleteCourse(@PathVariable id: String): ResponseEntity<Void> {
        return if (courseRepository.existsById(id)) {
            courseRepository.deleteById(id)
            ResponseEntity.noContent().build()
        } else {
            ResponseEntity.notFound().build()
        }
    }
}