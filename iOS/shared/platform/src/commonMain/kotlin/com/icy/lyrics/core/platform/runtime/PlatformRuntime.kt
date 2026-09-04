package com.icy.lyrics.core.platform.runtime

import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
fun epochMillis(): Long = Clock.System.now().toEpochMilliseconds()

/** Canonical decomposition only; callers retain the existing matching policy. */
expect fun normalizeNfd(value: String): String

internal expect fun parseHttpDate(value: String): Long?
