package com.abel.photoo.data.media

import android.content.IntentSender
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * 系统媒体操作确认框的中转站。
 *
 * Android 10 之后，删除 / 修改不属于自己的媒体文件都必须由用户在系统弹窗里点一次确认
 * （拿到 MANAGE_MEDIA 权限后系统会自动放行，弹窗一闪而过甚至不出现）。
 * 这个 API 需要 Activity 才能 startIntentSender，但发起方在 Repository 里，
 * 所以用一个 Channel 把 IntentSender 送到 Activity，再把用户的选择回传。
 */
class MediaRequestBroker {

    class Request(
        val intentSender: IntentSender,
        private val deferred: CompletableDeferred<Boolean>,
    ) {
        fun complete(granted: Boolean) {
            deferred.complete(granted)
        }
    }

    private val channel = Channel<Request>(Channel.BUFFERED)

    /** Activity 侧收集这个流，逐个把 IntentSender 丢给系统。 */
    val requests = channel.receiveAsFlow()

    /** 数据层调用：挂起等待用户确认结果。 */
    suspend fun confirm(sender: IntentSender): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        channel.send(Request(sender, deferred))
        return deferred.await()
    }
}
