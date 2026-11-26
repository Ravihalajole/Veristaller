package dev.veristaller.domain.error

import dev.veristaller.domain.model.ErrorCode

class VeristallerException(
    val code: ErrorCode,
    override val message: String,
    val raw: String? = null,
    cause: Throwable? = null,
    val step: String? = null,
    val exitCode: Int? = null,
) : Exception(message, cause)
