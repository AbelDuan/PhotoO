package com.abel.photoo

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import com.abel.photoo.data.PhotoRepository
import com.abel.photoo.data.media.MediaRequestBroker
import com.abel.photoo.data.prefs.AppPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

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
        container = AppContainer(this)
    }

    override fun onTerminate() {
        container.close()
        super.onTerminate()
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

    private val scope = CoroutineScope(SupervisorJob())

    val prefs = AppPrefs(app)
    val broker = MediaRequestBroker()
    val repository = PhotoRepository(app, scope, prefs, broker)

    fun close() {
        scope.cancel()
    }
}
