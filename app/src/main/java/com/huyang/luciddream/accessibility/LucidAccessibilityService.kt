package com.huyang.luciddream.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.ActivityOptions
import android.app.KeyguardManager
import android.app.PendingIntent
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.PowerManager
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.huyang.luciddream.data.dao.DelegationSessionDao
import com.huyang.luciddream.reply.AutoReplyState
import com.huyang.luciddream.reply.ReplyTransportResult
import dagger.hilt.android.AndroidEntryPoint
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

@AndroidEntryPoint
class LucidAccessibilityService : AccessibilityService() {
    @Inject lateinit var coordinator: AccessibilityReplyCoordinator
    @Inject lateinit var connectionState: AccessibilityConnectionState
    @Inject lateinit var sessionDao: DelegationSessionDao
    @Inject lateinit var autoReplyState: AutoReplyState

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        coordinator.attach(this)
        connectionState.update(true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (coordinator.detach(this)) connectionState.update(false)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (coordinator.detach(this)) connectionState.update(false)
        if (::coordinator.isInitialized) runCatching { recognizer.close() }
        super.onDestroy()
    }

    suspend fun execute(task: WechatReplyTask): ReplyTransportResult = withContext(Dispatchers.Main.immediate) {
        if (System.currentTimeMillis() - task.createdAt > TASK_TTL_MS) {
            return@withContext rejected("TASK_EXPIRED", "回复任务已过期")
        }
        preflight(task)?.let { return@withContext it }

        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard.isDeviceLocked) {
            return@withContext rejected("DEVICE_LOCKED", "手机仍处于锁定状态，未尝试绕过锁屏")
        }

        val wakeLock = acquireShortWakeLock()
        var wechatOpened = false
        try {
            if (!openWechat(task.pendingIntent)) {
                return@withContext rejected("BAL_BLOCKED", "系统未允许从后台打开对应微信聊天")
            }
            if (!waitForPackage(WECHAT_PACKAGE, OPEN_CHAT_TIMEOUT_MS)) {
                return@withContext rejected("BAL_BLOCKED", "未进入微信聊天，已停止发送")
            }
            wechatOpened = true

            delay(CHAT_OPEN_SETTLE_MS)
            val displayMetrics = resources.displayMetrics

            if (!tap(
                    displayMetrics.widthPixels * INPUT_X_RATIO,
                    displayMetrics.heightPixels * INPUT_Y_RATIO,
                )
            ) {
                return@withContext rejected("INPUT_FOCUS_FAILED", "无法聚焦微信输入框")
            }
            delay(KEYBOARD_SETTLE_MS)

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                return@withContext rejected("ANDROID_VERSION_UNSUPPORTED", "无障碍文本输入需要 Android 13 或更高版本")
            }

            val activeInputMethod = inputMethod
                ?: return@withContext rejected("INPUT_METHOD_MISSING", "Android 无障碍输入法桥不可用")
            val input = activeInputMethod.currentInputConnection
                ?: return@withContext rejected("INPUT_CONNECTION_MISSING", "微信输入连接不可用")
            val editor = activeInputMethod.currentInputEditorInfo
            if (editor?.packageName != WECHAT_PACKAGE) {
                return@withContext rejected("INPUT_CONNECTION_MISMATCH", "当前输入目标不是微信")
            }

            input.performContextMenuAction(android.R.id.selectAll)
            input.commitText(task.reply, 1, null)
            delay(TEXT_SETTLE_MS)

            val sendScreen = try {
                captureRecognizedScreen()
            } catch (_: Exception) {
                null
            }
            val sendPoint = sendScreen?.let(::findSendButton)
                ?: (displayMetrics.widthPixels * SEND_X_RATIO to
                    displayMetrics.heightPixels * SEND_Y_RATIO)

            preflight(task)?.let { return@withContext it }
            if (!tap(sendPoint.first, sendPoint.second)) {
                return@withContext rejected("SEND_GESTURE_FAILED", "微信发送按钮点击失败")
            }
            delay(SEND_SETTLE_MS)
            ReplyTransportResult.Dispatched
        } catch (_: PendingIntent.CanceledException) {
            rejected("NOTIFICATION_EXPIRED", "微信通知入口已失效")
        } catch (_: SecurityException) {
            rejected("SYSTEM_REJECTED", "系统拒绝打开微信或执行无障碍操作")
        } catch (_: Exception) {
            rejected("ACCESSIBILITY_SEND_FAILED", "微信界面回复流程异常，已停止")
        } finally {
            if (wechatOpened) performGlobalAction(GLOBAL_ACTION_HOME)
            if (wakeLock?.isHeld == true) wakeLock.release()
        }
    }

    private suspend fun preflight(task: WechatReplyTask): ReplyTransportResult.Rejected? {
        if (!autoReplyState.isEnabled) {
            return rejected("PREVIEW_ONLY", "实验性微信自动回复已关闭")
        }
        if (sessionDao.getActive()?.id != task.sessionId) {
            return rejected("CANCELLED_SESSION_ENDED", "发送前托管 Session 已结束或已被替换")
        }
        return null
    }

    private fun openWechat(pendingIntent: PendingIntent): Boolean = runCatching {
        val options = ActivityOptions.makeBasic()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            options.setPendingIntentBackgroundActivityStartMode(
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
            )
        }
        pendingIntent.send(this, 0, null, null, null, null, options.toBundle())
        true
    }.getOrDefault(false)

    private suspend fun waitForPackage(packageName: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (rootInActiveWindow?.packageName?.toString() == packageName) return true
            delay(POLL_MS)
        }
        return false
    }

    private suspend fun captureRecognizedScreen(): RecognizedScreen? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val bitmap = suspendCancellableCoroutine<Bitmap?> { continuation ->
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                Executor { command -> mainExecutor.execute(command) },
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        val buffer = screenshot.hardwareBuffer
                        val copied = try {
                            Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                                ?.copy(Bitmap.Config.ARGB_8888, false)
                        } finally {
                            buffer.close()
                        }
                        if (continuation.isActive) continuation.resume(copied)
                    }

                    override fun onFailure(errorCode: Int) {
                        if (continuation.isActive) continuation.resume(null)
                    }
                },
            )
        } ?: return null
        return try {
            val text = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
            RecognizedScreen(bitmap.width, bitmap.height, text)
        } finally {
            bitmap.recycle()
        }
    }

    private fun findSendButton(screen: RecognizedScreen): Pair<Float, Float>? {
        val candidates = screen.text.textBlocks
            .flatMap { it.lines }
            .flatMap { line -> line.elements }
            .mapNotNull { element ->
                val bounds = element.boundingBox ?: return@mapNotNull null
                if (WechatScreenVerifier.normalize(element.text) != "发送") return@mapNotNull null
                if (bounds.centerX() < screen.width * 0.60f) return@mapNotNull null
                if (bounds.centerY() < screen.height * 0.45f) return@mapNotNull null
                bounds
            }
        val bounds = candidates.maxByOrNull { it.centerY() } ?: return null
        return bounds.centerX().toFloat() to bounds.centerY().toFloat()
    }

    private suspend fun tap(x: Float, y: Float): Boolean = suspendCancellableCoroutine { continuation ->
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, TAP_DURATION_MS))
            .build()
        val accepted = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(false)
                }
            },
            null,
        )
        if (!accepted && continuation.isActive) continuation.resume(false)
    }

    @Suppress("DEPRECATION")
    private fun acquireShortWakeLock(): PowerManager.WakeLock? = runCatching {
        getSystemService(PowerManager::class.java).newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "$packageName:wechatReply",
        ).apply { acquire(WAKE_LOCK_TIMEOUT_MS) }
    }.getOrNull()

    private fun rejected(status: String, reason: String) =
        ReplyTransportResult.Rejected(status, reason)

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { value ->
            if (continuation.isActive) continuation.resume(value)
        }
        addOnFailureListener { error ->
            if (continuation.isActive) continuation.resumeWithException(error)
        }
    }

    private data class RecognizedScreen(
        val width: Int,
        val height: Int,
        val text: Text,
    )

    private companion object {
        const val WECHAT_PACKAGE = "com.tencent.mm"
        const val TASK_TTL_MS = 45_000L
        const val OPEN_CHAT_TIMEOUT_MS = 6_000L
        const val CHAT_OPEN_SETTLE_MS = 1_200L
        const val POLL_MS = 150L
        const val KEYBOARD_SETTLE_MS = 900L
        const val TEXT_SETTLE_MS = 650L
        const val SEND_SETTLE_MS = 800L
        const val TAP_DURATION_MS = 80L
        const val WAKE_LOCK_TIMEOUT_MS = 20_000L
        const val INPUT_X_RATIO = 0.44f
        const val INPUT_Y_RATIO = 0.946f
        const val SEND_X_RATIO = 0.912f
        const val SEND_Y_RATIO = 0.589f
    }
}
