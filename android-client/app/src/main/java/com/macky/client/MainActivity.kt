package com.macky.client

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.macky.client.ui.ConnectScreen
import com.macky.client.ui.QrScannerModal
import com.macky.client.ui.StreamScreen
import com.macky.client.ui.theme.DarkBg
import com.macky.client.ui.theme.MackyTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ensure soft input doesn't obscure buttons
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        setContent {
            MackyTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DarkBg
                ) {
                    val screenState by viewModel.screenState.collectAsState()
                    val isScanningQr by viewModel.isScanningQr.collectAsState()

                    when (val state = screenState) {
                        is ScreenState.Connect -> {
                            ConnectScreen(
                                viewModel = viewModel,
                                onOpenQrScanner = { viewModel.startScanningQr() }
                            )
                        }
                        is ScreenState.Stream -> {
                            StreamScreen(
                                viewModel = viewModel,
                                roomCode = state.roomCode,
                                onDisconnect = { viewModel.disconnect() }
                            )
                        }
                    }

                    if (isScanningQr) {
                        QrScannerModal(
                            onQrCodeDetected = { viewModel.onQrCodeScanned(it) },
                            onDismiss = { viewModel.stopScanningQr() }
                        )
                    }
                }
            }
        }
    }

    /**
     * App backgrounding handling (Requirement 3: Stays alive approach):
     * Video rendering is paused to save GPU resources, while the WebRTC
     * PeerConnection and WebSocket connection remain active in the background.
     */
    override fun onPause() {
        super.onPause()
        viewModel.webRtcClient.pauseRendering()
    }

    /**
     * App returns to foreground:
     * Video rendering resumes immediately with zero renegotiation latency.
     */
    override fun onResume() {
        super.onResume()
        viewModel.webRtcClient.resumeRendering()
    }
}
