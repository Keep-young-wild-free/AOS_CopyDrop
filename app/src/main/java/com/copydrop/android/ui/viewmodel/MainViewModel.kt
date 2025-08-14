package com.copydrop.android.ui.viewmodel

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.copydrop.android.network.NetworkManager
import com.copydrop.android.service.ClipboardSyncService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 메인 화면의 ViewModel
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {
    
    private val networkManager = NetworkManager(application)
    
    // UI 상태
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()
    
    init {
        checkWifiConnection()
    }
    
    /**
     * Wi-Fi 연결 상태 확인
     */
    private fun checkWifiConnection() {
        val isWifiConnected = networkManager.isConnectedToWifi()
        _uiState.value = _uiState.value.copy(
            isWifiConnected = isWifiConnected
        )
    }
    
    /**
     * Mac 서버 자동 검색
     */
    fun startAutoDiscovery() {
        if (!_uiState.value.isWifiConnected) {
            _uiState.value = _uiState.value.copy(
                statusMessage = "Wi-Fi에 연결되어 있지 않습니다"
            )
            return
        }
        
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isDiscovering = true,
                statusMessage = "Mac CopyDrop을 찾는 중..."
            )
            
            try {
                val localIp = networkManager.getLocalIpAddress()
                
                // 1단계: Bonjour/mDNS로 정확한 검색 (5초)
                _uiState.value = _uiState.value.copy(
                    statusMessage = "🎯 Bonjour로 Mac CopyDrop 검색 중... (내 IP: $localIp)"
                )
                
                val bonjourResult = networkManager.findServerByBonjour()
                if (bonjourResult != null) {
                    _uiState.value = _uiState.value.copy(
                        isDiscovering = false,
                        macServerAddress = bonjourResult,
                        statusMessage = "✨ Mac CopyDrop 발견! $bonjourResult (Bonjour 서비스)"
                    )
                    return@launch
                }
                
                // 2단계: UDP 브로드캐스트로 빠른 검색 (3초)
                _uiState.value = _uiState.value.copy(
                    statusMessage = "🔊 브로드캐스트로 Mac 찾는 중..."
                )
                
                val broadcastResult = networkManager.findServerByBroadcast()
                if (broadcastResult != null) {
                    _uiState.value = _uiState.value.copy(
                        isDiscovering = false,
                        macServerAddress = broadcastResult,
                        statusMessage = "✨ Mac 서버 발견! $broadcastResult (브로드캐스트)"
                    )
                    return@launch
                }
                
                // 3단계: 마지막 수단으로 IP 스캔
                _uiState.value = _uiState.value.copy(
                    statusMessage = "🔍 주변 네트워크 스캔 중..."
                )
                
                val result = networkManager.startDiscoveryWithDetails()
                if (result.serverFound) {
                    _uiState.value = _uiState.value.copy(
                        isDiscovering = false,
                        macServerAddress = result.address!!,
                        statusMessage = "🎯 Mac 서버 발견! ${result.address}:${result.port} (${result.scannedCount}개 위치 검색)"
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        isDiscovering = false,
                        statusMessage = "❌ Mac 서버를 찾을 수 없음. Mac에서 CopyDrop이 실행 중이고 같은 WiFi에 연결되어 있는지 확인하거나 고급 설정을 사용하세요."
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isDiscovering = false,
                    statusMessage = "검색 실패: ${e.message}"
                )
            }
        }
    }
    
    /**
     * Mac 서버 주소 수동 설정
     */
    fun setMacServerAddress(address: String, port: Int = 8080) {
        if (address.isBlank()) {
            _uiState.value = _uiState.value.copy(
                statusMessage = "올바른 IP 주소를 입력해주세요"
            )
            return
        }
        
        _uiState.value = _uiState.value.copy(
            macServerAddress = address,
            macServerPort = port,
            statusMessage = "서버 주소가 설정되었습니다: $address:$port"
        )
        
        networkManager.setMacServerAddress(address, port)
    }
    
    /**
     * 클립보드 동기화 시작
     */
    fun startClipboardSync() {
        val address = _uiState.value.macServerAddress
        if (address.isBlank()) {
            _uiState.value = _uiState.value.copy(
                statusMessage = "먼저 Mac 서버 주소를 설정해주세요"
            )
            return
        }
        
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isConnecting = true,
                statusMessage = "연결 중..."
            )
            
            try {
                // 서버 연결 확인
                val isConnected = false // WebSocket 상태로 대체 예정
                if (isConnected) {
                    // 동기화 서비스 시작
                    val intent = Intent(getApplication(), ClipboardSyncService::class.java).apply {
                        action = ClipboardSyncService.ACTION_START_SYNC
                        putExtra(ClipboardSyncService.EXTRA_MAC_ADDRESS, address)
                        putExtra(ClipboardSyncService.EXTRA_MAC_PORT, _uiState.value.macServerPort)
                    }
                    getApplication<Application>().startService(intent)
                    
                    _uiState.value = _uiState.value.copy(
                        isConnecting = false,
                        isConnected = true,
                        statusMessage = "클립보드 동기화가 시작되었습니다"
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        isConnecting = false,
                        statusMessage = "서버에 연결할 수 없습니다. 주소를 확인해주세요."
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isConnecting = false,
                    statusMessage = "연결 실패: ${e.message}"
                )
            }
        }
    }
    
    /**
     * 클립보드 동기화 중지
     */
    fun stopClipboardSync() {
        val intent = Intent(getApplication(), ClipboardSyncService::class.java).apply {
            action = ClipboardSyncService.ACTION_STOP_SYNC
        }
        getApplication<Application>().startService(intent)
        
        _uiState.value = _uiState.value.copy(
            isConnected = false,
            statusMessage = "클립보드 동기화가 중지되었습니다"
        )
    }
    
    /**
     * 연결 상태 새로고침
     */
    fun refreshConnectionStatus() {
        checkWifiConnection()
        viewModelScope.launch {
            val isServerConnected = false // WebSocket 상태로 대체 예정
            _uiState.value = _uiState.value.copy(
                isConnected = isServerConnected,
                statusMessage = if (isServerConnected) "서버에 연결됨" else "서버 연결 끊김"
            )
        }
    }
    
    override fun onCleared() {
        super.onCleared()
        networkManager.cleanup()
    }
}

/**
 * 메인 화면의 UI 상태
 */
data class MainUiState(
    val isWifiConnected: Boolean = false,
    val isDiscovering: Boolean = false,
    val isConnecting: Boolean = false,
    val isConnected: Boolean = false,
    val macServerAddress: String = "",
    val macServerPort: Int = 8080,
    val statusMessage: String = "Wi-Fi 연결을 확인하고 시작하세요"
)
