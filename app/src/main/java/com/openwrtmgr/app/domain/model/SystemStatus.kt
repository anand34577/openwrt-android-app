package com.openwrtmgr.app.domain.model

/** Section 19 — one procd-managed service, from ubus `service list`. */
data class ServiceStatus(
    val name: String,
    val running: Boolean,
    val instanceCount: Int,
    val pid: Int?,
)

/** Section 21 — one line from ubus `log read` (procd's syslog buffer, same source as `logread`). */
data class LogEntry(
    val message: String,
    val severity: LogSeverity,
    val epochSeconds: Long,
)

/** Standard syslog priority levels (0=Emergency..7=Debug), as `log read` reports them. */
enum class LogSeverity(val level: Int, val label: String) {
    EMERGENCY(0, "Emergency"),
    ALERT(1, "Alert"),
    CRITICAL(2, "Critical"),
    ERROR(3, "Error"),
    WARNING(4, "Warning"),
    NOTICE(5, "Notice"),
    INFO(6, "Info"),
    DEBUG(7, "Debug"),
    ;

    companion object {
        fun fromLevel(level: Int?): LogSeverity = entries.find { it.level == level } ?: INFO
    }
}
