package com.copydrop.android.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit
import java.net.Socket

/**
 * 네트워크 통신을 관리하는 클래스
 */
class NetworkManager(private val context: Context) {
    
    private var retrofit: Retrofit? = null
    private var api: CopyDropApi? = null
    private var macServerAddress: String? = null
    private var macServerPort: Int = 8080
    
    companion object {
        private const val DISCOVERY_TIMEOUT = 10000L
        private const val CONNECTION_TIMEOUT = 5000L
        private const val READ_TIMEOUT = 10000L
        private const val DEFAULT_PORT = 8080
    }
    
    /**
     * 검색 결과 정보
     */
    data class DiscoveryResult(
        val serverFound: Boolean,
        val address: String? = null,
        val port: Int = 0,
        val scannedCount: Int = 0
    )
    
    /**
     * Mac CopyDrop 서버 검색 시작 (스마트 검색)
     */
    suspend fun startDiscovery(): String? = withContext(Dispatchers.IO) {
        try {
            val localAddress = getLocalIpAddress()
            if (localAddress != null) {
                val subnet = localAddress.substringBeforeLast(".")
                
                // 여러 포트로 스마트 검색
                val commonPorts = listOf(8080, 3000, 5000, 8000, 9090, 8888, 7777)
                
                // 효율적인 IP 범위
                val commonRanges = listOf(
                    (1..10),      // 라우터, 게이트웨이
                    (100..120),   // 일반적인 DHCP 범위
                    (150..170),   // 확장 DHCP 범위
                    (20..30),     // 정적 IP
                    (200..210)    // 추가 범위
                )
                
                // 포트별로 빠른 스캔
                for (port in commonPorts) {
                    for (range in commonRanges) {
                        for (i in range) {
                            val testAddress = "$subnet.$i"
                            if (testAddress != localAddress && isPortOpen(testAddress, port)) {
                                // HTTP 서버인지 확인 (CopyDrop 여부는 나중에)
                                if (testHttpServer(testAddress, port)) {
                                    macServerAddress = testAddress
                                    macServerPort = port
                                    setupRetrofit()
                                    return@withContext testAddress
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        return@withContext null
    }
    
    /**
     * 자세한 정보와 함께 검색
     */
    suspend fun startDiscoveryWithDetails(): DiscoveryResult = withContext(Dispatchers.IO) {
        try {
            val localAddress = getLocalIpAddress()
            if (localAddress == null) {
                return@withContext DiscoveryResult(false, scannedCount = 0)
            }
            
            val subnet = localAddress.substringBeforeLast(".")
            val commonPorts = listOf(8080, 3000, 5000, 8000, 9090, 8888, 7777)
            val commonRanges = listOf(
                (1..10), (100..120), (150..170), (20..30), (200..210)
            )
            
            var scannedCount = 0
            
            for (port in commonPorts) {
                for (range in commonRanges) {
                    for (i in range) {
                        val testAddress = "$subnet.$i"
                        if (testAddress != localAddress) {
                            scannedCount++
                            if (isPortOpen(testAddress, port)) {
                                if (testHttpServer(testAddress, port)) {
                                    macServerAddress = testAddress
                                    macServerPort = port
                                    setupRetrofit()
                                    return@withContext DiscoveryResult(
                                        serverFound = true,
                                        address = testAddress,
                                        port = port,
                                        scannedCount = scannedCount
                                    )
                                }
                            }
                        }
                    }
                }
            }
            
            return@withContext DiscoveryResult(false, scannedCount = scannedCount)
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext DiscoveryResult(false, scannedCount = 0)
        }
    }
    
    /**
     * 로컬 IP 주소 공개 함수
     */
    fun getLocalIpAddress(): String? {
        return try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address.hostAddress?.contains(":") == false) {
                        return address.hostAddress
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * 포트가 열려있는지 확인 (빠른 타임아웃)
     */
    private fun isPortOpen(address: String, port: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress(address, port), 500) // 0.5초로 단축
                true
            }
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * HTTP 서버인지 테스트 (더 관대한 검사)
     */
    private suspend fun testHttpServer(address: String, port: Int): Boolean {
        return try {
            // 일단 HTTP 응답이 오는지만 확인
            val okHttpClient = okhttp3.OkHttpClient.Builder()
                .connectTimeout(500, java.util.concurrent.TimeUnit.MILLISECONDS)
                .readTimeout(1000, java.util.concurrent.TimeUnit.MILLISECONDS)
                .build()
            
            val request = okhttp3.Request.Builder()
                .url("http://$address:$port/")
                .build()
            
            val response = okHttpClient.newCall(request).execute()
            response.isSuccessful || response.code in 400..499 // 4xx도 서버 응답으로 간주
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * CopyDrop 서버인지 테스트
     */
    private suspend fun testCopyDropServer(address: String, port: Int): Boolean {
        return try {
            setMacServerAddress(address, port)
            isServerConnected()
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 수동으로 Mac 서버 주소 설정
     */
    fun setMacServerAddress(address: String, port: Int = 8080) {
        macServerAddress = address
        macServerPort = port
        setupRetrofit()
    }
    
    /**
     * Retrofit 설정
     */
    private fun setupRetrofit() {
        val serverAddress = macServerAddress ?: return
        
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(CONNECTION_TIMEOUT, TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT, TimeUnit.MILLISECONDS)
            .writeTimeout(READ_TIMEOUT, TimeUnit.MILLISECONDS)
            .build()
        
        retrofit = Retrofit.Builder()
            .baseUrl("http://$serverAddress:$macServerPort/")
            .addConverterFactory(GsonConverterFactory.create())
            .client(okHttpClient)
            .build()
        
        api = retrofit?.create(CopyDropApi::class.java)
    }
    
    /**
     * API 인스턴스 반환
     */
    fun getApi(): CopyDropApi? = api
    

    
    /**
     * Wi-Fi 연결 상태 확인
     */
    fun isConnectedToWifi(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = connectivityManager.activeNetworkInfo
            networkInfo?.type == ConnectivityManager.TYPE_WIFI && networkInfo.isConnected
        }
    }
    
    /**
     * 서버 연결 상태 확인
     */
    suspend fun isServerConnected(): Boolean {
        return try {
            val response = api?.ping()
            response?.isSuccessful == true
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 리소스 정리
     */
    fun cleanup() {
        api = null
        retrofit = null
    }
}
