package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup.LayoutParams
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView

/**
 * The facing page of the reader's book mode, opened on a second display such as the LG Dual Screen.
 *
 * Some devices (the LG V60 among them) don't let an app put a window on their second display, only start an activity
 * there, so the page lives in its own activity that [DualScreenBookController] fills in.
 */
class DualScreenPageActivity : ComponentActivity() {

    lateinit var pageView: BookPageView
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pageView = BookPageView(this)
        setContentView(pageView)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        // Without a reader to show pages for (like after the process was killed), there's nothing to show.
        if (!DualScreenBookController.attach(this)) finish()
    }

    override fun onDestroy() {
        DualScreenBookController.detach(this)
        super.onDestroy()
    }

    /**
     * Keys pressed while this display has focus (volume keys to turn pages, back to leave) go to the reader, so both
     * screens behave the same.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        return DualScreenBookController.dispatchKey(event) || super.dispatchKeyEvent(event)
    }
}

/**
 * One page image, with a spinner and a message for the other states.
 */
class BookPageView(context: Context) : FrameLayout(context) {

    val imageView = ReaderPageImageView(context)
    private val progress = ProgressBar(context).apply { isIndeterminate = true }
    private val message = TextView(context).apply {
        setTextColor(Color.LTGRAY)
        textSize = 18f
        gravity = Gravity.CENTER
    }

    init {
        setBackgroundColor(Color.BLACK)
        addView(imageView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(
            progress,
            FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )
        addView(message, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        showMessage("")
    }

    fun showLoading() {
        imageView.isVisible = false
        message.isVisible = false
        progress.isVisible = true
    }

    fun showImage() {
        imageView.isVisible = true
        message.isVisible = false
        progress.isVisible = false
    }

    fun showMessage(text: String) {
        imageView.isVisible = false
        progress.isVisible = false
        message.text = text
        message.isVisible = text.isNotEmpty()
    }
}
