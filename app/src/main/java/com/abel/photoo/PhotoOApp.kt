package com.abel.photoo

import android.app.Application
import android.util.Log
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import com.abel.photoo.data.PhotoRepository
import com.abel.photoo.data.media.MediaRequestBroker
import com.abel.photoo.data.prefs.AppPrefs
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用入口。
 *
 * 依赖很少，用不着 Hilt —— 一个 Application 级别的手写容器就够了，
 * 少一个注解处理器就少一份构建风险。
 */
class PhotoOApp : Application(), SingletonImageLoader.Factory {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        installCrashLogger()
        container = AppContainer(this)
    }

    override fun onTerminate() {
        container.close()
        super.onTerminate()
    }

    /**
     * 兜底：把任何未被协程/界面捕获的崩溃栈写入 filesDir/photoo_crash.log，
     * 方便在没有 IDE 的真机上定位问题。写完仍交给系统默认处理器弹崩溃框。
     */
    private fun installCrashLogger() {
        val default = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val log = File(filesDir, "photoo_crash.log")
                log.appendText(
                    "=== ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())} ===\n" +
                        throwable.stackTraceToString() + "\n\n"
                )
            } catch (_: Throwable) {
                // 写日志失败也不能影响系统崩溃流程
            }
            default?.uncaughtException(thread, throwable)
        }
    }

    /**
     * 相册应用的图片加载有两个特点：缩略图数量极多、单张原图很大。
     * 默认内存缓存偏小，长列表来回滚动会反复解码，这里放宽到堆的 1/4。
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizeBytes(
                        maxOf(48L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 4)
                    )
                    .build()
            }
            .build()
}

class AppContainer(app: Application) {

    /** 协程异常兜底：记录日志而不是让进程直接被杀。 */
    private val exceptionHandler = CoroutineExceptionHandler { _, e ->
        Log.e("PhotoO", "uncaught in repository scope", e)
    }

    private val scope = CoroutineScope(SupervisorJob() + exceptionHandler)

    val prefs = AppPrefs(app)
    val broker = MediaRequestBroker()
    val repository = PhotoRepository(app, scope, prefs, broker)

    fun close() {
        scope.cancel()
    }
}
