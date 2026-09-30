package com.cs30.server.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/** Policy for the course.yml sync. See `cs30.course-sync.*` in application.properties. */
@Component
class CourseYamlSyncSettings(
    @Value("\${cs30.course-sync.enabled:true}")
    val enabled: Boolean,
    @Value("\${cs30.course-sync.file-name:course.yml}")
    val fileName: String,
    @Value("\${cs30.course-sync.commit-message:update course.yml from the database}")
    val commitMessage: String,
    /** When the last section of a course is deleted, drop the file instead of leaving it stale. */
    @Value("\${cs30.course-sync.remove-on-empty:true}")
    val removeOnEmpty: Boolean,
)
