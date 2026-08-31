package com.huyang.luciddream

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.huyang.luciddream.ui.LucidDreamRoot
import com.huyang.luciddream.ui.theme.LucidDreamTheme
import com.huyang.luciddream.notification.NotificationAccess
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val requestedDestination = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NotificationAccess.requestRebind(this)
        requestedDestination.value = intent.getStringExtra(EXTRA_DESTINATION)
        enableEdgeToEdge()
        setContent {
            LucidDreamTheme {
                LucidDreamRoot(
                    requestedRoute = requestedDestination.value,
                    onRequestedRouteConsumed = { requestedDestination.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedDestination.value = intent.getStringExtra(EXTRA_DESTINATION)
    }

    override fun onResume() {
        super.onResume()
        NotificationAccess.requestRebind(this)
    }

    companion object {
        const val EXTRA_DESTINATION = "open_destination"
    }
}
