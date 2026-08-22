package com.mezo.trace.infra.persistence

import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Instant
import java.time.ZoneOffset

/**
 * Small JDBC helpers shared by the repositories (pgjdbc conventions).
 */
internal fun ResultSet.instant(column: String): Instant =
    getObject(column, java.time.OffsetDateTime::class.java).toInstant()

internal fun PreparedStatement.setInstant(index: Int, value: Instant) {
    setObject(index, java.time.OffsetDateTime.ofInstant(value, ZoneOffset.UTC))
}

internal fun PreparedStatement.setNullableString(index: Int, value: String?) {
    if (value == null) setNull(index, java.sql.Types.VARCHAR) else setString(index, value)
}
