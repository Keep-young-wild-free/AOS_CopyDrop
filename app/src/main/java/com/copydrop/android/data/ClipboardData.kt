package com.copydrop.android.data

import com.google.gson.annotations.SerializedName

/**
 * 클립보드 데이터를 나타내는 데이터 클래스
 */
data class ClipboardData(
    @SerializedName("content")
    val content: String,
    
    @SerializedName("timestamp")
    val timestamp: Long = System.currentTimeMillis(),
    
    @SerializedName("type")
    val type: ClipboardType = ClipboardType.TEXT,
    
    @SerializedName("deviceId")
    val deviceId: String = ""
)

enum class ClipboardType {
    TEXT,
    IMAGE,
    URL
}

/**
 * 네트워크 응답을 위한 데이터 클래스
 */
data class NetworkResponse(
    @SerializedName("success")
    val success: Boolean,
    
    @SerializedName("message")
    val message: String = "",
    
    @SerializedName("data")
    val data: ClipboardData? = null
)

/**
 * 기기 정보를 위한 데이터 클래스
 */
data class DeviceInfo(
    @SerializedName("deviceId")
    val deviceId: String,
    
    @SerializedName("deviceName")
    val deviceName: String,
    
    @SerializedName("deviceType")
    val deviceType: String = "android",
    
    @SerializedName("ipAddress")
    val ipAddress: String = "",
    
    @SerializedName("port")
    val port: Int = 8080
)
