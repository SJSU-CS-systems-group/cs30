package com.cs30.cli

import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Parameters
import java.util.concurrent.Callable
import kotlin.system.exitProcess

@Command(
    name = "cs30",
    mixinStandardHelpOptions = true,
    version = ["1.0"],
    description = ["CS30 Course Management CLI"],
    subcommands = [
        AddCourse::class,
        DeleteCourse::class,
        AddStudent::class,
        DeleteStudent::class
    ]
)
class MainCommand : Callable<Int> {
    override fun call(): Int {
        CommandLine(this).usage(System.out)
        return 0
    }
}

@Command(name = "addcourse", description = ["Add a new course"])
class AddCourse : Callable<Int> {
    @Parameters(index = "0", description = ["Course name"])
    lateinit var courseName: String

    override fun call(): Int {
        // TODO: implement
        println("Adding course: $courseName")
        return 0
    }
}

@Command(name = "deletecourse", description = ["Delete a course"])
class DeleteCourse : Callable<Int> {
    @Parameters(index = "0", description = ["Course name"])
    lateinit var courseName: String

    override fun call(): Int {
        // TODO: implement
        println("Deleting course: $courseName")
        return 0
    }
}

@Command(name = "addstudent", description = ["Add a new student"])
class AddStudent : Callable<Int> {
    @Parameters(index = "0", description = ["Student ID"])
    lateinit var studentId: String

    override fun call(): Int {
        // TODO: implement
        println("Adding student: $studentId")
        return 0
    }
}

@Command(name = "deletestudent", description = ["Delete a student"])
class DeleteStudent : Callable<Int> {
    @Parameters(index = "0", description = ["Student ID"])
    lateinit var studentId: String

    override fun call(): Int {
        // TODO: implement
        println("Deleting student: $studentId")
        return 0
    }
}

fun main(args: Array<String>) {
    val exitCode = CommandLine(MainCommand()).execute(*args)
    exitProcess(exitCode)
}