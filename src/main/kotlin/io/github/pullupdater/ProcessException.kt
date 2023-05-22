package io.github.pullupdater

import java.io.IOException

class ProcessException(val exitCode: Int, val errorOutput: String, message: String) : IOException(message)