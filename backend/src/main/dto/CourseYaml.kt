package com.cs30.server.dto

import com.fasterxml.jackson.annotation.JsonFormat
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonPropertyOrder
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The `course.yml` document: one file per course, covering every section.
 *
 * These classes are both the parse model (`addcourse --course-file`) and the emit model
 * (CourseYamlService), so a file written from the database re-imports to the same database. Keep
 * the two directions on one set of annotations - that equality is what lets the roster live in the
 * database without the next re-import undoing it.
 *
 * Property order here is the key order in the emitted file, pinned by @JsonPropertyOrder so a
 * reordering of the constructor can't quietly churn every course.yml in every repo.
 */
@JsonPropertyOrder("name", "language", "note")
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ProblemInput(
    val name: String,
    var language: String? = null,
    var note: String? = null
)

@JsonPropertyOrder("number", "startDateTime", "endDateTime", "problems")
data class LabInput(
    val number: Int,
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    val startDateTime: LocalDateTime,
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    val endDateTime: LocalDateTime,
    val problems: List<ProblemInput> = emptyList()
)

@JsonPropertyOrder("number", "tas", "students", "labs")
@JsonInclude(JsonInclude.Include.NON_NULL)
data class SectionInput(
    val number: Int,
    // `tas` is the current form. `ta` is the single-TA form these files used before a section could
    // have several, still read so existing course files load unchanged; both are merged. Only `tas`
    // is ever written - `ta` stays null when rendering, so NON_NULL keeps it out of the output.
    val ta: String? = null,
    val tas: List<String> = emptyList(),
    val labs: List<LabInput> = emptyList(),
    val students: List<String> = emptyList()
) {
    /** Every TA named for the section, in either form, without duplicates. */
    fun taEmails(): List<String> =
        (listOfNotNull(ta?.takeIf { it.isNotBlank() }) + tas.filter { it.isNotBlank() })
            .distinctBy { it.lowercase() }
}

@JsonPropertyOrder(
    "code", "year", "semester", "startDate", "endDate",
    "studentGitRepo", "problemGitRepo", "language", "sections"
)
data class CourseInput(
    val code: String,
    val year: Int,
    val semester: String,
    @JsonFormat(pattern = "yyyy-MM-dd")
    val startDate: LocalDate,
    @JsonFormat(pattern = "yyyy-MM-dd")
    val endDate: LocalDate,
    val studentGitRepo: String = "",
    val problemGitRepo: String = "",
    val language: String = "",
    val sections: List<SectionInput> = emptyList()
)
