package com.icy.lyrics.core.platform.runtime

import java.text.Normalizer
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

actual fun normalizeNfd(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)

internal actual fun parseHttpDate(value: String): Long? = runCatching {
  ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
}.getOrNull()
