package com.swithun.cmpmermaid.core

sealed interface GMResult<out T, out E> {
    data class Ok<T>(val value: T) : GMResult<T, Nothing>

    data class Err<E>(val error: E) : GMResult<Nothing, E>
}

inline fun <T, E, R> GMResult<T, E>.map(transform: (T) -> R): GMResult<R, E> = when (this) {
    is GMResult.Ok -> GMResult.Ok(transform(value))
    is GMResult.Err -> this
}

inline fun <T, E, R> GMResult<T, E>.flatMap(
    transform: (T) -> GMResult<R, E>,
): GMResult<R, E> = when (this) {
    is GMResult.Ok -> transform(value)
    is GMResult.Err -> this
}

fun <T, E> GMResult<T, E>.getOrNull(): T? = when (this) {
    is GMResult.Ok -> value
    is GMResult.Err -> null
}
