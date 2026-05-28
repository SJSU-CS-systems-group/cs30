import picocli.CommandLine
import picocli.CommandLine.Model.CommandSpec
import java.io.PrintWriter

@CommandLine.Command(mixinStandardHelpOptions = true)
class CliOptions {

    @CommandLine.Spec
    lateinit var spec: CommandSpec

    fun out(): PrintWriter {
        return cmd().out
    }

    fun err(): PrintWriter {
        return cmd().err
    }

    fun cmd(): CommandLine {
        return spec.commandLine()
    }
}