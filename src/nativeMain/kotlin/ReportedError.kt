import kotlinx.cinterop.ExperimentalForeignApi
import librust_lib.*
import kotlin.system.exitProcess

enum class ReportedError(private val message: String) {
    InvalidArgs("invalid arguments"),
    InputFileNotZip("input file is not a zip file"),
    InputFileNotExists("input file doesn't exist"),
    InputFileParentNotExists("input file parent doesn't exist"),
    FileOpenError("cannot open file"),
    FileCreateError("cannot create file"),
    FileSetPermissionError("cannot set permission on file"),
    ZipError("error when processing zip"),
    UnknownError("unknown error"),
    ;

    fun exit(): Nothing = throw ReportedErrorException(this)

    val status: Int get() = ordinal + 1

    companion object {
        private class ReportedErrorException(val error: ReportedError) : Exception(error.message)

        fun runReportingErrors(action: () -> Unit) {
            try {
                action()
            } catch (e: ReportedErrorException) {
                val error = e.error
                println("Error: ${error.message}")
                exitProcess(error.status)
            }
        }

        @OptIn(ExperimentalForeignApi::class)
        fun Int.checkExitCode(): Unit = when (this) {
            OK -> Unit
            FILE_OPEN_ERROR -> FileOpenError.exit()
            CREATE_FILE_ERROR -> FileCreateError.exit()
            SET_PERMISSION_ERROR -> FileSetPermissionError.exit()
            ZIP_ERROR -> ZipError.exit()
            INVALID_STRING_CONVERSION_ERROR -> UnknownError.exit()
            else -> UnknownError.exit()
        }
    }
}
