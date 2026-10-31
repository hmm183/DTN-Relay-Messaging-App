package com.dtn.mesh.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.Surface
import com.dtn.mesh.service.MessageNotifier
import com.dtn.mesh.ui.shell.DtnApp
import com.dtn.mesh.ui.theme.DtnTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: DtnViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val allGranted = grants.values.all { it }
        if (allGranted) {
            viewModel.onPermissionsGranted()
        } else {
            viewModel.onPermissionsDenied(grants.filterValues { !it }.keys.toList())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The shell is light (pale background, light top bar): dark system-bar icons everywhere.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )

        // Request all needed permissions upfront
        permissionLauncher.launch(PermissionHelper.requiredPermissions())

        setContent {
            DtnTheme {
                Surface {
                    DtnApp(viewModel)
                }
            }
        }

        // Only a fresh launch: after a rotation / process restore the same intent would re-open
        // the chat the user may already have left.
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val chatKey = intent?.getStringExtra(MessageNotifier.EXTRA_OPEN_CHAT_KEY)
        if (chatKey != null) {
            intent.removeExtra(MessageNotifier.EXTRA_OPEN_CHAT_KEY) // consumed: never re-opened later
            viewModel.openChat(chatKey)
            return
        }
        val data = intent?.data
        if (data != null) {
            intent.data = null // consumed: never re-opened later
            viewModel.handleIncomingLink(data.toString())
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.setAppInForeground(true)
    }

    override fun onStop() {
        super.onStop()
        viewModel.setAppInForeground(false)
    }
}
