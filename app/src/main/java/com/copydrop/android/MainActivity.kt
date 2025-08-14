package com.copydrop.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.content.Intent
import android.os.Bundle
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.copydrop.android.ui.theme.CopyDropTheme
import com.copydrop.android.ui.viewmodel.MainViewModel
import com.copydrop.android.service.ClipboardSyncService
import com.copydrop.android.service.BleDiscoveryService

class MainActivity : ComponentActivity() {
    
    private val viewModel: MainViewModel by viewModels()
    
    // 권한 요청 런처
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            // 권한이 모두 허용되면 초기화 진행
        } else {
            // 권한이 거부되면 사용자에게 안내
        }
    }
    
    private fun startClipboardSyncService(macAddress: String, port: Int) {
        val serviceIntent = Intent(this, ClipboardSyncService::class.java)
        serviceIntent.action = ClipboardSyncService.ACTION_START_SYNC
        serviceIntent.putExtra(ClipboardSyncService.EXTRA_MAC_ADDRESS, macAddress)
        serviceIntent.putExtra(ClipboardSyncService.EXTRA_MAC_PORT, port)
        ContextCompat.startForegroundService(this, serviceIntent)
    }
    
    private fun startBleDiscoveryService() {
        val serviceIntent = Intent(this, BleDiscoveryService::class.java)
        serviceIntent.action = BleDiscoveryService.ACTION_START_SCAN
        ContextCompat.startForegroundService(this, serviceIntent)
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        checkAndRequestPermissions()
        
        setContent {
            CopyDropTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(viewModel = viewModel)
                }
            }
        }
    }
    
    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf<String>()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) 
                != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        
        // BLE 권한 체크 (Android 12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) 
                != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) 
                != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
        
        // 위치 권한 (BLE 스캔에 필요)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) 
            != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        
        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    
    val context = androidx.compose.ui.platform.LocalContext.current
    val mainActivity = context as? MainActivity
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 앱 타이틀
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "CopyDrop",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Mac과 클립보드 공유",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        
        // 연결 상태 카드
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            colors = CardDefaults.cardColors(
                containerColor = when {
                    uiState.isConnected -> MaterialTheme.colorScheme.primaryContainer
                    uiState.isWifiConnected -> MaterialTheme.colorScheme.secondaryContainer
                    else -> MaterialTheme.colorScheme.errorContainer
                }
            )
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = when {
                        uiState.isConnected -> Icons.Default.CheckCircle
                        uiState.isWifiConnected -> Icons.Default.Check
                        else -> Icons.Default.Warning
                    },
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when {
                            uiState.isConnected -> "연결됨"
                            uiState.isWifiConnected -> "Wi-Fi 연결됨"
                            else -> "Wi-Fi 연결 안됨"
                        },
                        style = MaterialTheme.typography.titleSmall
                    )
                    if (uiState.macServerAddress.isNotEmpty()) {
                        Text(
                            text = "서버: ${uiState.macServerAddress}:${uiState.macServerPort}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                IconButton(onClick = { viewModel.refreshConnectionStatus() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "새로고침")
                }
            }
        }
        
        // 상태 메시지
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        ) {
            Text(
                text = uiState.statusMessage,
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
        }
        
        // Mac 연결 버튼
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Mac과 연결",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                
                Button(
                    onClick = { viewModel.startBleConnection() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    ),
                    enabled = !uiState.isBleScanning
                ) {
                    if (uiState.isBleScanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 3.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Mac 찾는 중...", style = MaterialTheme.typography.titleMedium)
                    } else {
                        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Mac과 연결", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
        
        // 동기화 제어 버튼
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (!uiState.isConnected) {
                    Button(
                        onClick = { viewModel.startClipboardSync() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = uiState.macServerAddress.isNotEmpty() && 
                                  uiState.isWifiConnected && 
                                  !uiState.isConnecting
                    ) {
                        if (uiState.isConnecting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (uiState.isConnecting) "연결 중..." else "클립보드 동기화 시작")
                    }
                } else {
                    Button(
                        onClick = { viewModel.stopClipboardSync() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("클립보드 동기화 중지")
                    }
                }
                
                if (uiState.isConnected) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "백그라운드에서 클립보드를 동기화하고 있습니다",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
        

    }
}
