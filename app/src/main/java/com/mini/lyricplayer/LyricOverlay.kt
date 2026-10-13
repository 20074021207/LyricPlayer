package com.mini.lyricplayer

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.abs

/**
 * 顶层桌面歌词：通过 WindowManager 添加的系统级悬浮窗，
 * 悬浮在所有应用之上。拖动可移动位置，单击可暂停/继续播放。
 */
object LyricOverlay : PlayerManager.Listener {

    private var appContext: Context? = null
    private var windowManager: WindowManager? = null
    private var textView: TextView? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun hide() {
        val tv = textView ?: return
        runCatching { windowManager?.removeView(tv) }
        textView = null
    }

    override fun onPlayerStateChanged(state: PlayerManager.State) {
        val context = appContext ?: return
        if (!PlayerManager.overlayEnabled || state.track == null) {
            hide()
            return
        }
        if (!Settings.canDrawOverlays(context)) {
            hide()
            return
        }
        if (textView == null) show(context)
        val line = state.lyricLines.getOrNull(state.lyricIndex)?.text
        textView?.apply {
            text = if (line.isNullOrEmpty()) state.track.title else line
            alpha = if (state.playing) 1f else 0.6f
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun show(context: Context) {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm
        val tv = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(28, 14, 28, 14)
            maxWidth = context.resources.displayMetrics.widthPixels - 64
            background = GradientDrawable().apply {
                cornerRadius = 24f
                setColor(0xB0000000.toInt())
            }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // 默认显示在屏幕顶部居中，按密度留出顶部边距；恢复用户拖动后保存的位置
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = (20 * context.resources.displayMetrics.density).toInt()
            val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
            if (prefs.contains("overlayX")) {
                x = prefs.getInt("overlayX", x)
                y = prefs.getInt("overlayY", y)
            }
        }
        // 单击 = 打开App界面；长按 = 暂停/继续；拖动 = 移动位置
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        // PendingIntent 方式启动，兼容各版本的后台启动限制
        val openAppPi = android.app.PendingIntent.getActivity(
            context, 0,
            android.content.Intent(context, MainActivity::class.java),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        var longPressed = false
        val longPressAction = Runnable {
            if (!moved) {
                longPressed = true
                PlayerManager.toggle()
            }
        }
        tv.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = lp.x
                    startY = lp.y
                    moved = false
                    longPressed = false
                    handler.postDelayed(longPressAction, 500)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(e.rawX - downX) > 10 || abs(e.rawY - downY) > 10) {
                        moved = true
                        handler.removeCallbacks(longPressAction)
                    }
                    lp.x = startX + (e.rawX - downX).toInt()
                    lp.y = startY + (e.rawY - downY).toInt()
                    runCatching { wm.updateViewLayout(tv, lp) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPressAction)
                    // 拖动后持久化位置
                    if (moved) {
                        context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
                            .edit()
                            .putInt("overlayX", lp.x)
                            .putInt("overlayY", lp.y)
                            .apply()
                    }
                    if (!moved && !longPressed) {
                        runCatching { openAppPi.send() }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressAction)
                    true
                }
                else -> false
            }
        }
        wm.addView(tv, lp)
        textView = tv
    }
}
