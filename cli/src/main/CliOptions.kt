package com.cs30.cli

import picocli.CommandLine
import picocli.CommandLine.Model.CommandSpec
import java.io.PrintWriter

@CommandLine.Command(mixinStandardHelpOptions = true)
class CliOptions {

    @CommandLine.Spec
    lateinit var spec: CommandSpec

    fun out(info: String) {
        cmd().out.println(info)
    }

    fun err(err: String) {
        cmd().err.println(err)
    }

    fun cmd(): CommandLine {
        return spec.commandLine()
    }
}