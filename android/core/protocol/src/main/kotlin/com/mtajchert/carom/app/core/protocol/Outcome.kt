package com.mtajchert.carom.app.core.protocol

/**
 * Result of an operation on untrusted input. Failures carry a typed error whose [ErrorCode.code]
 * matches the error codes used by protocol/vectors, so a port can be compared case by case.
 */
sealed interface Outcome<out T, out E : ErrorCode> {
    data class Ok<out T>(val value: T) : Outcome<T, Nothing>

    data class Err<out E : ErrorCode>(val error: E) : Outcome<Nothing, E>
}

/** A stable snake_case error code. */
interface ErrorCode {
    val code: String
}

val Outcome<*, *>.isOk: Boolean get() = this is Outcome.Ok

fun <T> Outcome<T, *>.getOrNull(): T? = (this as? Outcome.Ok)?.value

/** "ok" or the error code; handy for logs and vector comparisons. */
val Outcome<*, *>.code: String
    get() = when (this) {
        is Outcome.Ok -> "ok"
        is Outcome.Err -> error.code
    }
