package com.copydrop.android.service

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.copydrop.android.data.ClipboardData
import com.copydrop.android.data.ClipboardType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest

/**
 * 클립보드 관리를 담당하는 클래스
 */
class ClipboardManager(private val context: Context) {
    
    private val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val _clipboardData = MutableStateFlow<ClipboardData?>(null)
    val clipboardData: StateFlow<ClipboardData?> = _clipboardData.asStateFlow()
    
    private var lastClipboardHash: String = ""
    private var isUpdatingFromRemote = false
    
    /**
     * 클립보드 변경 리스너 등록
     */
    fun startMonitoring() {
        clipboardManager.addPrimaryClipChangedListener {
            if (!isUpdatingFromRemote) {
                val clipData = getClipboardContent()
                if (clipData != null && clipData.content.isNotEmpty()) {
                    val currentHash = generateHash(clipData.content)
                    if (currentHash != lastClipboardHash) {
                        lastClipboardHash = currentHash
                        _clipboardData.value = clipData
                    }
                }
            }
        }
    }
    
    /**
     * 현재 클립보드 내용 가져오기
     */
    fun getClipboardContent(): ClipboardData? {
        return try {
            val primaryClip = clipboardManager.primaryClip
            if (primaryClip != null && primaryClip.itemCount > 0) {
                val item = primaryClip.getItemAt(0)
                val content = item.text?.toString() ?: ""
                
                if (content.isNotEmpty()) {
                    ClipboardData(
                        content = content,
                        timestamp = System.currentTimeMillis(),
                        type = determineClipboardType(content)
                    )
                } else null
            } else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    /**
     * 클립보드에 내용 설정 (원격에서 받은 데이터)
     */
    fun setClipboardContent(content: String) {
        try {
            isUpdatingFromRemote = true
            val clip = ClipData.newPlainText("CopyDrop", content)
            clipboardManager.setPrimaryClip(clip)
            lastClipboardHash = generateHash(content)
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            isUpdatingFromRemote = false
        }
    }
    
    /**
     * 클립보드 타입 결정
     */
    private fun determineClipboardType(content: String): ClipboardType {
        return when {
            content.startsWith("http://") || content.startsWith("https://") -> ClipboardType.URL
            else -> ClipboardType.TEXT
        }
    }
    
    /**
     * 문자열 해시 생성 (중복 감지용)
     */
    private fun generateHash(content: String): String {
        return try {
            val digest = MessageDigest.getInstance("MD5")
            val hashBytes = digest.digest(content.toByteArray())
            hashBytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            content.hashCode().toString()
        }
    }
    
    /**
     * 모니터링 중지
     */
    fun stopMonitoring() {
        // Android ClipboardManager는 리스너 제거 메서드를 제공하지 않음
        // 대신 isUpdatingFromRemote 플래그로 제어
    }
}
