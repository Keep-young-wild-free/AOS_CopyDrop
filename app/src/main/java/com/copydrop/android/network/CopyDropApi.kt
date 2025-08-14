package com.copydrop.android.network

import com.copydrop.android.data.ClipboardData
import com.copydrop.android.data.DeviceInfo
import com.copydrop.android.data.NetworkResponse
import retrofit2.Response
import retrofit2.http.*

/**
 * Mac CopyDrop과 통신하기 위한 API 인터페이스
 */
interface CopyDropApi {
    
    /**
     * Mac 기기에 클립보드 데이터 전송
     */
    @POST("/clipboard")
    suspend fun sendClipboard(@Body clipboardData: ClipboardData): Response<NetworkResponse>
    
    /**
     * Mac 기기로부터 클립보드 데이터 수신
     */
    @GET("/clipboard")
    suspend fun getClipboard(): Response<ClipboardData>
    
    /**
     * 기기 등록 (페어링)
     */
    @POST("/device/register")
    suspend fun registerDevice(@Body deviceInfo: DeviceInfo): Response<NetworkResponse>
    
    /**
     * 연결 상태 확인
     */
    @GET("/ping")
    suspend fun ping(): Response<NetworkResponse>
    
    /**
     * 기기 등록 해제
     */
    @DELETE("/device/unregister")
    suspend fun unregisterDevice(@Query("deviceId") deviceId: String): Response<NetworkResponse>
}
