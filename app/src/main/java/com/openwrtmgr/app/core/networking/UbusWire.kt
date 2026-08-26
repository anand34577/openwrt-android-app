package com.openwrtmgr.app.core.networking

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Wire format for OpenWrt's ubus-over-HTTP JSON-RPC, as served by uhttpd-mod-ubus
 * (the same transport LuCI itself uses — not a LuCI-specific endpoint).
 *
 * Request: {"jsonrpc":"2.0","id":N,"method":"call","params":[sid, object, method, args]}
 * Response: {"jsonrpc":"2.0","id":N,"result":[ubusStatusCode, dataObject]}
 *   ubusStatusCode 0 == UBUS_STATUS_OK.
 */
@Serializable
data class UbusRequest(
    val jsonrpc: String = "2.0",
    val id: Int,
    val method: String = "call",
    val params: List<JsonElement>,
)

@Serializable
data class UbusResponse(
    val jsonrpc: String = "2.0",
    val id: Int,
    val result: List<JsonElement>? = null,
    val error: UbusError? = null,
)

@Serializable
data class UbusError(val code: Int, val message: String)

@Serializable
data class UbusSessionLoginResult(
    @SerialName("ubus_rpc_session") val session: String,
    val timeout: Int = 0,
)

/** The anonymous session id every ubus-HTTP conversation starts from before login. */
const val UBUS_ANONYMOUS_SESSION = "00000000000000000000000000000000"

/** ubus's own status codes (from libubus), the values `call()` decodes out of `result[0]`. */
object UbusStatus {
    const val OK = 0
    const val PERMISSION_DENIED = 6
}
