package com.copydrop.android.ui.viewmodel

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.copydrop.android.network.NetworkManager
import com.copydrop.android.service.ClipboardSyncService
import com.copydrop.android.service.BleDiscoveryService
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
     * BLE 연결 시작
     */
    fun startBleConnection() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isBleScanning = true,
                statusMessage = "BLE로 Mac을 찾는 중..."
            )
            
            try {
                val intent = Intent(getApplication(), BleDiscoveryService::class.java).apply {
                    action = BleDiscoveryService.ACTION_START_SCAN
                }
                getApplication<Application>().startService(intent)
                
                // BLE 스캔 상태는 서비스에서 관리되므로 여기서는 시작만 알림
                _uiState.value = _uiState.value.copy(
                    statusMessage = "BLE 스캔이 시작되었습니다. Mac을 찾는 중..."
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isBleScanning = false,
                    statusMessage = "BLE 스캔 실패: ${e.message}"
                )
            }
        }
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
    val isConnecting: Boolean = false,
    val isConnected: Boolean = false,
    val isBleScanning: Boolean = false,
    val macServerAddress: String = "",
    val macServerPort: Int = 8080,
    val statusMessage: String = "Mac과 연결을 시작하세요"
)
