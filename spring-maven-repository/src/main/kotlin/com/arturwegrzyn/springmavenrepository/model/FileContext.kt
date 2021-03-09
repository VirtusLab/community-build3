package com.arturwegrzyn.springmavenrepository.model

import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import kotlin.math.ln
import kotlin.math.pow

class FileContext(filename: String, size: Long, lastModified: Long, isDirectory: Boolean) {
    public val filename = if (isDirectory) "$filename/" else filename
    public val size = if (isDirectory) "-" else humanReadableByteCount(size, false)
    public val lastModified = DATE_FORMAT.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(lastModified), TimeZone.getDefault().toZoneId()))
    public val isDirectory = isDirectory

    companion object {
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd hh:mm:ss")
        private fun humanReadableByteCount(bytes: Long, si: Boolean): String {
            val unit = if (si) 1000 else 1024
            if (bytes < unit) return bytes.toString() + "B"
            val exp = (ln(bytes.toDouble()) / ln(unit.toDouble())).toInt()
            val pre = (if (si) "kMGTPE" else "KMGTPE")[exp - 1].toString() + if (si) "" else "i"
            return String.format("%.1f%sB", bytes / unit.toDouble().pow(exp.toDouble()), pre)
        }
    }
}