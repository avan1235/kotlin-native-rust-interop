import ReportedError.*
import ReportedError.Companion.runReportingErrors
import ReportedError.Companion.checkExitCode
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cstr
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import librust_lib.unzip

@OptIn(ExperimentalForeignApi::class)
fun main(args: Array<String>) = runReportingErrors {
    val inputFile = args.singleOrNull() ?: InvalidArgs.exit()

    if (!inputFile.endsWith(".zip")) InputFileNotZip.exit()

    val inputFilePath = Path(inputFile)
    if (!SystemFileSystem.exists(inputFilePath)) InputFileNotExists.exit()

    val inputFilePathParent = inputFilePath.parent ?: InputFileParentNotExists.exit()

    val inputPath = inputFilePath.toString()
    val outputPath = inputFilePathParent.toString()
    unzip(inputPath.cstr, outputPath.cstr).checkExitCode()
}
