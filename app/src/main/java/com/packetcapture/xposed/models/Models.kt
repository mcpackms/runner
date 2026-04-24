// app/src/main/java/com/packetcapture/xposed/models/Models.kt
package com.packetcapture.xposed.models

import com.google.gson.annotations.SerializedName

data class ModificationRule(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("urlPattern") val urlPattern: String,
    @SerializedName("type") val type: String, // "request" or "response"
    @SerializedName("findPattern") val findPattern: String,
    @SerializedName("replaceWith") val replaceWith: String,
    @SerializedName("isRegex") val isRegex: Boolean = false,
    @SerializedName("enabled") val enabled: Boolean = true
)

sealed class RequestResult {
    object Continue : RequestResult()
    data class Modified(val body: String, val headers: Map<String, String>? = null) : RequestResult()
}

sealed class ResponseResult {
    object Continue : ResponseResult()
    data class Modified(val body: String, val headers: Map<String, String>? = null) : ResponseResult()
}

data class RequestInfo(
    val id: String,
    val url: String,
    val method: String,
    val headers: Map<String, String>,
    val body: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class CommandResponse(
    val success: Boolean,
    val data: String = "",
    val message: String = ""
)
