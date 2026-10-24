package com.cs30.server.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import java.io.File
import java.io.IOException

/**
 * A problem graded on files and command-line arguments rather than stdin/stdout: one with a
 * `file_io.yaml`. Each test case `N` may have, next to `N.in`, an `N.yaml` with `args:`, an
 * `N.files/` of starting files and an `N.expected/` of files the program must create (a file named
 * `stdout` stands for what it printed).
 *
 * The judge does the grading (kt-judge/src/main/resources/incontainer.py and the sandbox's
 * cs30-fileio). This side checks a problem against the same rules when it is added, so a mistake
 * is reported to whoever adds it rather than to students as a judge error, and shows the test
 * cases to TAs. Keep [problems] in step with `_file_io_setup` in incontainer.py.
 */
object FileIoProblem {
    const val CONFIG = "file_io.yaml"

    private val yaml = ObjectMapper(YAMLFactory())
    private val PLAIN_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
    private val ORDERS = setOf("strict", "any", "any_words")
    private val TEST_GROUPS = listOf("sample", "secret")
    /** The most of one file shown in a TA's view of a case; the rest is cut. */
    private const val SHOWN_FILE_CHARS = 20_000

    fun usesFiles(problemDir: File): Boolean = File(problemDir, CONFIG).isFile

    /** Every way [problemDir] breaks the file-I/O rules; empty when it has none, or no file_io.yaml. */
    fun problems(problemDir: File): List<String> {
        if (!usesFiles(problemDir)) return emptyList()
        val errors = mutableListOf<String>()
        val config = try {
            readYaml(File(problemDir, CONFIG))
        } catch (e: IOException) {
            return listOf("$CONFIG is not valid YAML: ${e.message}")
        }

        val compareNames = mutableSetOf<String>()
        if (config != null) {
            if (!config.isObject || config.fieldNames().asSequence().any { it != "compare" }) {
                errors += "$CONFIG: the only key allowed is 'compare'"
            } else {
                config.get("compare")?.takeUnless { it.isNull }?.let { compare ->
                    if (!compare.isObject) errors += "$CONFIG: 'compare' must map file names to options"
                    else compare.fields().forEach { (name, opts) -> errors += compareProblems(name, opts, compareNames) }
                }
            }
        }

        val problemYaml = File(problemDir, "problem.yaml")
        if (problemYaml.isFile) {
            val p = try { readYaml(problemYaml) } catch (e: IOException) { null }
            // `type` in the current format, `validation` in the legacy one bt upgrade converts from.
            val kinds = listOfNotNull(p?.get("type"), p?.get("validation"))
                .flatMap { if (it.isArray) it.map(JsonNode::asText) else it.asText().split(" ") }
            if (kinds.any { it == "interactive" || it == "multi-pass" }) {
                errors += "$CONFIG: file I/O does not work with interactive or multi-pass problems"
            }
        }

        val expectedNames = mutableSetOf<String>()
        for (inFile in testInputs(problemDir)) {
            val stem = inFile.path.removeSuffix(".in")
            val files = File("$stem.files")
            val expected = File("$stem.expected")
            if (files.isDirectory) errors += folderProblems(files, "starting file")
            if (expected.isDirectory) {
                if (File("$stem.ans").exists()) errors += "${rel(problemDir, File("$stem.ans"))} and ${rel(problemDir, expected)} both give the answer; keep one"
                val names = expected.listFiles().orEmpty()
                if (names.isEmpty()) errors += "${rel(problemDir, expected)} is empty"
                errors += folderProblems(expected, "expected file")
                names.forEach { expectedNames += it.name }
            }
        }
        val typos = compareNames - "*" - expectedNames
        if (typos.isNotEmpty()) errors += "$CONFIG: compare names ${typos.sorted()}, which no test case expects"
        return errors.map { "${problemDir.name}: $it" }
    }

    private fun compareProblems(name: String, opts: JsonNode, names: MutableSet<String>): List<String> {
        names += name
        if (name != "*" && !PLAIN_NAME.matches(name)) return listOf("$CONFIG: compare: '$name' is not a plain file name or '*'")
        if (!opts.isObject || opts.fieldNames().asSequence().any { it != "order" && it != "unique" }) {
            return listOf("$CONFIG: compare: $name: options are 'order' and 'unique'")
        }
        val order = opts.get("order")?.asText() ?: "strict"
        val unique = opts.get("unique")
        return listOfNotNull(
            "$CONFIG: compare: $name: order must be one of strict, any, any_words".takeIf { order !in ORDERS },
            "$CONFIG: compare: $name: unique must be true or false".takeIf { unique != null && !unique.isBoolean },
            "$CONFIG: compare: $name: unique needs order 'any' or 'any_words'"
                .takeIf { unique?.asBoolean() == true && order == "strict" },
        )
    }

    /** A test case's .files/ or .expected/ must hold only plain files with plain names: the judge copies them flat. */
    private fun folderProblems(folder: File, what: String): List<String> =
        folder.listFiles().orEmpty().sortedBy { it.name }
            .filter { !it.isFile || !PLAIN_NAME.matches(it.name) }
            .map { "$what ${it.name} in ${folder.name} must be a plain file named like input.txt (no folders)" }

    /**
     * How a file-I/O test case looks on the TA dashboard: its arguments, stdin and starting files as
     * the input, and its expected files as the answer, each under the `=== name ===` heading the
     * judge shows students. Without an `N.expected/` the answer is `N.ans`, as for any problem.
     */
    fun shownCase(inFile: File): Pair<String, String> {
        val stem = inFile.path.removeSuffix(".in")
        val input = buildList {
            val args = File("$stem.yaml").takeIf { it.isFile }
                ?.let { runCatching { readYaml(it)?.get("args") }.getOrNull() }
            if (args != null && args.isArray && args.size() > 0) add("=== arguments ===\n" + args.joinToString(" ") { it.asText() })
            val stdin = inFile.readText()
            if (stdin.isNotEmpty()) add("=== stdin ===\n$stdin")
            addAll(labelled(File("$stem.files")))
        }
        val expectedDir = File("$stem.expected")
        val expected = if (expectedDir.isDirectory) labelled(expectedDir)
        else listOfNotNull(File("$stem.ans").takeIf { it.isFile }?.readText())
        return joined(input) to joined(expected)
    }

    /**
     * The problem directory problemtools should render the statement from. problem2html prints each
     * sample's .in and .ans as the sample table; a file-I/O sample has neither (its stdin is usually
     * empty and its answer is in N.expected/), so the page would show an empty table under a "Can't
     * find file" line. For a file-I/O problem this is a copy under [scratch] whose sample .in/.ans hold
     * the same labelled view students get after Run ([shownCase]); any other problem renders as is.
     * Only the rendered page sees the copy: the problem itself is not changed.
     */
    fun statementSource(problemDir: File, scratch: File): File {
        if (!usesFiles(problemDir)) return problemDir
        val copy = File(scratch, problemDir.name)
        problemDir.copyRecursively(copy, overwrite = true)
        testInputs(copy)
            .filter { it.relativeTo(copy).invariantSeparatorsPath.startsWith("data/sample/") }
            .forEach { inFile ->
                val (input, expected) = shownCase(inFile)
                inFile.writeText(input)
                File(inFile.path.removeSuffix(".in") + ".ans").writeText(expected)
            }
        return copy
    }

    private fun labelled(folder: File): List<String> =
        folder.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }.map { f ->
            val text = f.readText()
            "=== ${f.name} ===\n" + if (text.length > SHOWN_FILE_CHARS) text.take(SHOWN_FILE_CHARS) + "\n[... cut]" else text
        }

    private fun joined(parts: List<String>): String = parts.joinToString("") { if (it.endsWith("\n")) it else "$it\n" }

    /** Every test case's .in under data/sample and data/secret, nested groups included. */
    fun testInputs(problemDir: File): List<File> =
        TEST_GROUPS.flatMap { group ->
            File(problemDir, "data/$group").walkTopDown().filter { it.isFile && it.extension == "in" }.toList()
        }.sortedBy { it.path }

    /** Total size of the starting and expected files of a test case. */
    fun extraBytes(inFile: File): Long {
        val stem = inFile.path.removeSuffix(".in")
        return listOf("$stem.files", "$stem.expected").sumOf { dir ->
            File(dir).listFiles().orEmpty().filter { it.isFile }.sumOf { it.length() }
        }
    }

    /** The parsed YAML, or null for an empty file. */
    private fun readYaml(file: File): JsonNode? =
        yaml.readTree(file)?.takeUnless { it.isMissingNode || it.isNull }

    private fun rel(root: File, f: File): String = f.relativeTo(root).invariantSeparatorsPath
}
