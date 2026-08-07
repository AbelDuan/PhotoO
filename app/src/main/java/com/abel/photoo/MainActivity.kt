package com.abel.photoo

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.abel.photoo.data.media.MediaRequestBroker
import com.abel.photoo.ui.PhotoORoot
import com.abel.photoo.ui.PhotoOViewModel
import com.abel.photoo.ui.theme.PhotoOTheme
import kotlinx.coroutines.launch

/**
 * 唯一的 Activity。
 *
 * 除了托管 Compose 界面，它还承担一件只有 Activity 能做的事：
 * 把数据层发来的 IntentSender（系统删除/修改确认框）真正弹出来，
 * 并把用户的选择回传给挂起中的协程。
 */
class MainActivity : ComponentActivity() {

    private val vm: PhotoOViewModel by viewModels { PhotoOViewModel.Factory }

    /** 正在等待用户确认的那一个系统请求。 */
    private var pendingRequest: MediaRequestBroker.Request? = null

    private val intentSenderLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val granted = result.resultCode == Activity.RESULT_OK
        pendingRequest?.complete(granted)
        pendingRequest = null
        // 系统对话框确认后 MediaStore 才真正变化，这里补一次刷新兜底。
        if (granted) vm.refreshIfReady()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.broker.requests.collect { request ->
                    pendingRequest = request
                    val ok = runCatching {
                        intentSenderLauncher.launch(
                            IntentSenderRequest.Builder(request.intentSender).build()
                        )
                    }.isSuccess
                    if (!ok) {
                        pendingRequest = null
                        request.complete(false)
                    }
                }
            }
        }

        setContent {
            val settings by vm.settings.collectAsStateWithLifecycle()
            PhotoOTheme(
                themeMode = settings.themeMode,
                dynamicColor = settings.dynamicColor,
            ) {
                PhotoORoot(vm)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 从系统相机/其它相册应用切回来时，图库可能已经变了。
        vm.refreshIfReady()
    }
}
