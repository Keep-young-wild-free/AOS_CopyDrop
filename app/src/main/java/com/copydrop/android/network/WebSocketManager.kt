package com.copydrop.android.network

import android.content.Context
import android.os.Build
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Mac CopyDrop과 호환되는 WebSocket 통신 관리자
 */
class WebSocketManager(private val context: Context) {
    
    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder().build()
    
    // 동적으로 주입되는 32바이트 키
    @Volatile private var encryptionKey: ByteArray = ByteArray(32)
    
    /**
     * 암호화 키 설정 (Base64 문자열)
     */
    fun setEncryptionKey(base64: String) {
        encryptionKey = Base64.decode(base64, Base64.DEFAULT)
    }
    
    // 연결 상태
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState
    
    // 받은 클립보드 데이터
    private val _receivedClipboard = MutableStateFlow<String?>(null)
    val receivedClipboard: StateFlow<String?> = _receivedClipboard
    
    // 마지막 해시 (중복 전송 방지)
    private var lastHash: String? = null
    
    enum class ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING
    }
    
    /**
     * WebSocket 연결 시작
     */
    fun connect(serverAddress: String, serverPort: Int) {
        val url = "ws://$serverAddress:$serverPort/ws"
        _connectionState.value = ConnectionState.CONNECTING
        
        val request = Request.Builder()
            .url(url)
            .build()
        
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _connectionState.value = ConnectionState.CONNECTED
            }
            
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleIncomingMessage(bytes.toByteArray())
            }
            
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _connectionState.value = ConnectionState.RECONNECTING
                // 2초 후 재연결 시도
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    connect(serverAddress, serverPort)
                }, 2000)
            }
            
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _connectionState.value = ConnectionState.DISCONNECTED
            }
        })
    }
    
    /**
     * WebSocket 연결 종료
     */
    fun disconnect() {
        webSocket?.close(1000, "User disconnected")
        _connectionState.value = ConnectionState.DISCONNECTED
    }
    
    /**
     * 클립보드 데이터 전송
     */
    fun sendClipboard(text: String) {
        val hash = sha256(text)
        if (hash == lastHash) return // 중복 전송 방지
        
        lastHash = hash
        
        val json = JSONObject().apply {
            put("t", System.currentTimeMillis())
            put("from", "android-${Build.MODEL}")
            put("type", "text")
            put("payload", text)
            put("hash", hash)
        }
        
        val encrypted = encrypt(json.toString().toByteArray(Charsets.UTF_8))
        if (encrypted != null) {
            webSocket?.send(ByteString.of(*encrypted))
        }
    }
    
    /**
     * 수신된 메시지 처리
     */
    private fun handleIncomingMessage(data: ByteArray) {
        val decrypted = decrypt(data) ?: return
        
        try {
            val json = JSONObject(String(decrypted, Charsets.UTF_8))
            val payload = json.getString("payload")
            val hash = json.getString("hash")
            
            if (hash != lastHash) {
                lastHash = hash
                _receivedClipboard.value = payload
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    /**
     * AES-256-GCM 암호화
     * 결과: IV(12바이트) + 암호문 + 태그(16바이트)
     */
    private fun encrypt(plainText: ByteArray): ByteArray? {
        return try {
            val iv = ByteArray(12)
            SecureRandom().nextBytes(iv)
            
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val keySpec = SecretKeySpec(encryptionKey, "AES")
            val gcmSpec = GCMParameterSpec(128, iv)
            
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
            val cipherTextWithTag = cipher.doFinal(plainText)
            
            // IV + 암호문+태그 결합
            ByteBuffer.allocate(iv.size + cipherTextWithTag.size)
                .put(iv)
                .put(cipherTextWithTag)
                .array()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    /**
     * AES-256-GCM 복호화
     */
    private fun decrypt(data: ByteArray): ByteArray? {
        return try {
            if (data.size < 12 + 16) return null // 최소 크기 확인
            
            val iv = data.copyOfRange(0, 12)
            val cipherTextWithTag = data.copyOfRange(12, data.size)
            
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val keySpec = SecretKeySpec(encryptionKey, "AES")
            val gcmSpec = GCMParameterSpec(128, iv)
            
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
            cipher.doFinal(cipherTextWithTag)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    /**
     * SHA-256 해시 계산
     */
    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { String.format("%02x", it) }
        return "sha256:$hex"
    }
    
    /**
     * 리소스 정리
     */
    fun cleanup() {
        disconnect()
    }
}
