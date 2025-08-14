package com.copydrop.android.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
// WebSocket을 사용하므로 Retrofit은 제거됨
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit
import java.net.Socket
import java.net.DatagramSocket
import java.net.DatagramPacket
import java.net.InetSocketAddress
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

/**
 * 네트워크 통신을 관리하는 클래스
 */
class NetworkManager(private val context: Context) {
    
    // WebSocket 사용으로 Retrofit 제거됨
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
                                    // WebSocket 연결로 변경됨
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
     * Bonjour/mDNS로 CopyDrop 서버 찾기 (가장 확실한 방법!)
     */
    suspend fun findServerByBonjour(): String? = withContext(Dispatchers.IO) {
        try {
            val localAddress = getLocalIpAddress()?.let { InetAddress.getByName(it) }
            if (localAddress == null) return@withContext null
            
            val jmdns = JmDNS.create(localAddress)
            var foundService: ServiceInfo? = null
            
            // 다양한 서비스 타입 시도 (Mac CopyDrop이 사용할 가능성이 높은 것들)
            val serviceTypes = listOf(
                "_copydrop._tcp.local.",      // 가장 가능성 높음
                "_http._tcp.local.",          // 일반적인 HTTP 서비스
                "_clipboard._tcp.local.",     // 클립보드 관련
                "_copyservice._tcp.local.",   // 복사 서비스 관련
                "_macdrop._tcp.local."       // Mac Drop 관련
            )
            
            for (serviceType in serviceTypes) {
                try {
                    val services = jmdns.list(serviceType, 5000) // 5초 대기
                    
                    for (service in services) {
                        val serviceName = service.name.lowercase()
                        
                        // 서비스 속성 텍스트 생성
                        val serviceText = try {
                            val props = mutableListOf<String>()
                            for (prop in service.propertyNames) {
                                props.add("${prop}=${service.getPropertyString(prop)}")
                            }
                            props.joinToString(" ").lowercase()
                        } catch (e: Exception) {
                            ""
                        }
                        
                        // CopyDrop 관련 키워드 확인
                        if (serviceName.contains("copydrop") || 
                            serviceName.contains("clipboard") ||
                            serviceName.contains("copy") ||
                            serviceText.contains("copydrop") ||
                            serviceText.contains("clipboard")) {
                            
                            foundService = service
                            break
                        }
                    }
                    
                    if (foundService != null) break
                    
                } catch (e: Exception) {
                    // 다음 서비스 타입으로 계속
                    continue
                }
            }
            
            jmdns.close()
            
            if (foundService != null) {
                val hostAddress = foundService.inetAddresses?.firstOrNull()?.hostAddress
                val port = foundService.port
                
                if (hostAddress != null && port > 0) {
                    macServerAddress = hostAddress
                    macServerPort = port
                    // WebSocket 연결로 변경됨
                    return@withContext hostAddress
                }
            }
            
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        return@withContext null
    }
    
    /**
     * UDP 브로드캐스트로 CopyDrop 서버 찾기 (매우 빠름!)
     */
    suspend fun findServerByBroadcast(): String? = withContext(Dispatchers.IO) {
        try {
            val socket = DatagramSocket()
            socket.broadcast = true
            socket.soTimeout = 3000 // 3초 대기
            
            // 브로드캐스트 메시지 전송
            val message = "COPYDROP_DISCOVERY".toByteArray()
            val packet = DatagramPacket(
                message, 
                message.size, 
                InetAddress.getByName("255.255.255.255"), 
                9876 // 발견용 포트
            )
            
            socket.send(packet)
            
            // 응답 대기
            val buffer = ByteArray(1024)
            val responsePacket = DatagramPacket(buffer, buffer.size)
            
            socket.receive(responsePacket)
            
            val response = String(responsePacket.data, 0, responsePacket.length)
            if (response.startsWith("COPYDROP_SERVER:")) {
                val serverInfo = response.substring("COPYDROP_SERVER:".length)
                val parts = serverInfo.split(":")
                if (parts.size == 2) {
                    val serverIp = parts[0]
                    val serverPort = parts[1].toIntOrNull() ?: 8080
                    
                    macServerAddress = serverIp
                    macServerPort = serverPort
                    // WebSocket 연결로 변경됨
                    
                    socket.close()
                    return@withContext serverIp
                }
            }
            
            socket.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        return@withContext null
    }
    
    /**
     * 빠른 검색 (일반적인 IP만)
     */
    suspend fun startDiscoveryWithDetails(): DiscoveryResult = withContext(Dispatchers.IO) {
        try {
            val localAddress = getLocalIpAddress()
            if (localAddress == null) {
                return@withContext DiscoveryResult(false, scannedCount = 0)
            }
            
            val subnet = localAddress.substringBeforeLast(".")
            val lastOctet = localAddress.substringAfterLast(".").toIntOrNull() ?: 100
            
            // 빠른 검색: 현재 IP 주변과 일반적인 주소만
            val quickScanIps = mutableListOf<Int>()
            
            // 1. 현재 IP 주변 ±5
            for (i in (lastOctet - 5)..(lastOctet + 5)) {
                if (i in 1..254) quickScanIps.add(i)
            }
            
            // 2. 일반적인 주소들
            quickScanIps.addAll(listOf(1, 2, 10, 100, 101, 200, 254))
            
            val commonPorts = listOf(8080, 3000, 5000, 8000)
            var scannedCount = 0
            
            // IP별로 모든 포트 빠르게 테스트
            for (ip in quickScanIps.distinct()) {
                val testAddress = "$subnet.$ip"
                if (testAddress != localAddress) {
                    for (port in commonPorts) {
                        scannedCount++
                        if (isPortOpen(testAddress, port)) {
                            if (testCopyDropServer(testAddress, port)) {
                                macServerAddress = testAddress
                                macServerPort = port
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
     * CopyDrop WebSocket 서버인지 테스트
     */
    private suspend fun testCopyDropServer(address: String, port: Int): Boolean {
        return try {
            // HTTP 기본 응답 확인
            val okHttpClient = okhttp3.OkHttpClient.Builder()
                .connectTimeout(1000, java.util.concurrent.TimeUnit.MILLISECONDS)
                .readTimeout(2000, java.util.concurrent.TimeUnit.MILLISECONDS)
                .build()
            
            val request = okhttp3.Request.Builder()
                .url("http://$address:$port/")
                .build()
            
            val response = okHttpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            
            // CopyDrop 서버 식별
            val isCopyDrop = responseBody.lowercase().contains("copydrop") ||
                           responseBody.lowercase().contains("clipboard") ||
                           response.code in 200..299
            
            response.close()
            isCopyDrop
        } catch (e: Exception) {
            // HTTP 실패 시 기본 포트 연결 테스트
            isPortOpen(address, port)
        }
    }
    
    /**
     * 수동으로 Mac 서버 주소 설정
     */
    fun setMacServerAddress(address: String, port: Int = 8080) {
        macServerAddress = address
        macServerPort = port
        // WebSocket 연결로 변경됨
    }
    
    // WebSocket 방식으로 전환하여 Retrofit 제거됨
    
    // API 관련 함수들은 WebSocket으로 대체됨
    

    
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
    // 서버 연결 확인은 WebSocket 상태로 대체됨
    
    /**
     * 리소스 정리
     */
    fun cleanup() {
        // WebSocket은 WebSocketManager에서 처리됨
    }
}
