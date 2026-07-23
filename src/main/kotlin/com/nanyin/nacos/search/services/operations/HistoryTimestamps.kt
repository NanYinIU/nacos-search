package com.nanyin.nacos.search.services.operations

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Resolves Nacos history timestamps across wire formats.
 *
 * V1 Open API returns ISO-ish strings under `lastModifiedTime` / `createdTime`
 * (e.g. `2020-12-05T01:48:03.380+0000`). Some fixtures and newer payloads use
 * numeric millis under `lastModified` / `modifyTime`.
 */
object HistoryTimestamps {

    private val NACOS_OFFSET: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
    private val NACOS_OFFSET_NO_MILLIS: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ")
    private val DISPLAY: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

    fun resolveMillis(
        lastModified: Long? = null,
        modifyTime: Long? = null,
        createTime: Long? = null,
        lastModifiedTime: String? = null,
        createdTime: String? = null
    ): Long {
        sequenceOf(lastModified, modifyTime, createTime)
            .firstOrNull { it != null && it > 0L }
            ?.let { return it }
        parseWireTime(lastModifiedTime)?.let { return it }
        parseWireTime(createdTime)?.let { return it }
        return 0L
    }

    fun parseWireTime(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        val trimmed = value.trim()
        trimmed.toLongOrNull()?.takeIf { it > 0L }?.let { return it }
        tryParseInstant(trimmed)?.let { return it }
        return null
    }

    fun formatForDisplay(millis: Long): String {
        if (millis <= 0L) return "—"
        return DISPLAY.format(Instant.ofEpochMilli(millis))
    }

    fun formatOpType(opType: String?): String {
        val raw = opType?.trim()?.takeIf { it.isNotEmpty() } ?: return "—"
        return when (raw.uppercase()) {
            "U", "UPDATE" -> "Update"
            "I", "INSERT" -> "Insert"
            "D", "DELETE" -> "Delete"
            "PUBLISH" -> "Publish"
            else -> raw
        }
    }

    private fun tryParseInstant(value: String): Long? {
        try {
            return Instant.parse(normalizeToInstant(value)).toEpochMilli()
        } catch (_: DateTimeParseException) {
            // fall through
        }
        for (formatter in listOf(NACOS_OFFSET, NACOS_OFFSET_NO_MILLIS, DateTimeFormatter.ISO_OFFSET_DATE_TIME)) {
            try {
                return OffsetDateTime.parse(value, formatter).toInstant().toEpochMilli()
            } catch (_: DateTimeParseException) {
                // try next
            }
        }
        try {
            val local = LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            return local.toInstant(ZoneOffset.UTC).toEpochMilli()
        } catch (_: DateTimeParseException) {
            return null
        }
    }

    /** Nacos often emits `+0000` instead of `Z` / `+00:00`. */
    private fun normalizeToInstant(value: String): String {
        val withColonOffset = value.replace(Regex("([+-]\\d{2})(\\d{2})$"), "$1:$2")
        return if (withColonOffset.endsWith("+00:00") || withColonOffset.endsWith("-00:00")) {
            withColonOffset.dropLast(6) + "Z"
        } else {
            withColonOffset
        }
    }
}
