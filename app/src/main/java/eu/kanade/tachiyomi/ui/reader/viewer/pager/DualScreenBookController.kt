package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.app.Activity
import android.app.Presentation
import android.graphics.Color
import android.graphics.ColorFilter
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup.LayoutParams
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView.ZoomStartPosition
import eu.kanade.tachiyomi.ui.reader.viewer.processPagedImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import okio.Buffer
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat

/**
 * Book mode for phones with a second display, such as the LG Dual Screen accessory (LG V50, V60, G8X).
 *
 * The second display shows the page next to the one in [viewer]'s pager, so both screens read like an open book.
 * The LG Dual Screen sits to the left of the phone, so when the reader is on the phone's own display the second
 * display shows the item on the left of the current one, and the item on its right otherwise. Pager items always run
 * left to right on screen (right to left viewers reverse their items), so this keeps the reading order in both
 * directions.
 */
class DualScreenBookController(
    private val viewer: PagerViewer,
    private val items: () -> List<Any>,
) : DefaultLifecycleObserver {

    private val activity = viewer.activity
    private val preferences = activity.settingsViewModel.preferences
    private val displayManager = activity.getSystemService(DisplayManager::class.java)
    private val scope = MainScope()

    private var presentation: BookPresentation? = null
    private var loadJob: Job? = null
    private var shownItem: Any? = null
    private var currentPosition = -1
    private var colorFilter: ColorFilter? = null
    private var started = activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    /** Whether the diagnostics were shown for this reader session. */
    private var diagnosed = false

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = update()
        override fun onDisplayRemoved(displayId: Int) = update()
        override fun onDisplayChanged(displayId: Int) = update()
    }

    /** Whether the second display is showing a page, so turning pages should move by two. */
    val isActive: Boolean
        get() = presentation?.isShowing == true

    init {
        activity.lifecycle.addObserver(this)
        displayManager?.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        preferences.dualScreenBookMode.changes()
            .onEach { update() }
            .launchIn(scope)
        preferences.dualScreenSwapSides.changes()
            .onEach { invalidate() }
            .launchIn(scope)
    }

    override fun onStart(owner: LifecycleOwner) {
        started = true
        update()
    }

    override fun onStop(owner: LifecycleOwner) {
        started = false
        update()
    }

    /** Called when the pager moves to [position]. */
    fun onPageChanged(position: Int) {
        currentPosition = position
        refreshPage()
    }

    /** Shows the page again, for when the image settings or the items changed. */
    fun invalidate() {
        shownItem = null
        refreshPage()
    }

    fun setColorFilter(filter: ColorFilter?) {
        if (colorFilter == filter) return
        colorFilter = filter
        presentation?.imageView?.pageColorFilter = filter
        invalidate()
    }

    fun destroy() {
        activity.lifecycle.removeObserver(this)
        displayManager?.unregisterDisplayListener(displayListener)
        dismiss()
        scope.cancel()
    }

    private fun update() {
        val enabled = started && preferences.dualScreenBookMode.get()
        val display = if (enabled) findOtherDisplay() else null
        val current = presentation
        if (current != null && (display == null || current.display.displayId != display.displayId)) {
            dismiss()
        }
        var result = if (display == null) "no second display found" else "already showing on ${display.displayId}"
        if (display != null && presentation == null) {
            presentation = try {
                BookPresentation(activity, display).also { it.show() }
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Couldn't show the book page on display ${display.displayId}" }
                result = "error on ${display.displayId}: ${e.javaClass.simpleName}: ${e.message}"
                null
            }
            if (presentation != null) result = "showing on ${display.displayId}"
            shownItem = null
        }
        if (enabled && !diagnosed) {
            diagnosed = true
            showDiagnostics(result)
        }
        refreshPage()
    }

    /**
     * Temporary diagnostics for testing on the LG Dual Screen: lists the displays Android reports and what happened.
     */
    private fun showDiagnostics(result: String) {
        val displays = displayManager?.displays.orEmpty().joinToString("\n") { d ->
            "• ${d.displayId} '${d.name}' state=${d.state} flags=${d.flags}"
        }
        val text = "Dual Screen (reader on ${currentDisplayId()})\n$displays\n→ $result"
        logcat(LogPriority.INFO) { text }
        android.widget.Toast.makeText(activity, text, android.widget.Toast.LENGTH_LONG).show()
    }

    private fun dismiss() {
        loadJob?.cancel()
        loadJob = null
        shownItem = null
        try {
            presentation?.dismiss()
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e)
        }
        presentation = null
    }

    private fun currentDisplayId(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.display?.displayId ?: Display.DEFAULT_DISPLAY
        } else {
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay.displayId
        }
    }

    private fun findOtherDisplay(): Display? {
        val dm = displayManager ?: return null
        val current = currentDisplayId()
        val usable = { display: Display -> display.displayId != current && display.state != Display.STATE_OFF }
        // Some devices report the accessory display as off or unknown, so fall back to any other display.
        return dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull(usable)
            ?: dm.displays.firstOrNull(usable)
            ?: dm.displays.firstOrNull { it.displayId != current }
    }

    private fun refreshPage() {
        val presentation = presentation ?: return
        val onLeft = (currentDisplayId() == Display.DEFAULT_DISPLAY) != preferences.dualScreenSwapSides.get()
        val item = if (currentPosition < 0) {
            null
        } else {
            items().getOrNull(if (onLeft) currentPosition - 1 else currentPosition + 1)
        }
        if (item != null && item === shownItem) return
        shownItem = item

        loadJob?.cancel()
        presentation.imageView.pageColorFilter = colorFilter
        when (item) {
            is ReaderPage -> loadJob = scope.launch { showPage(presentation, item) }
            is ChapterTransition -> presentation.showMessage(item.to?.let { "→ ${it.chapter.name}" }.orEmpty())
            else -> presentation.showMessage("")
        }
    }

    /**
     * Shows [page] once it's ready. The pager keeps the pages next to the current one loaded, so this only follows
     * the page's status instead of loading it again.
     */
    private suspend fun showPage(presentation: BookPresentation, page: ReaderPage) {
        presentation.showLoading()
        page.statusFlow.collectLatest { state ->
            when (state) {
                Page.State.Ready -> setImage(presentation, page)
                is Page.State.Error -> presentation.showMessage("!")
                else -> presentation.showLoading()
            }
        }
    }

    private suspend fun setImage(presentation: BookPresentation, page: ReaderPage) {
        val streamFn = page.stream ?: return
        try {
            val buffer = withContext(Dispatchers.IO) { streamFn().use { Buffer().readFrom(it) } }
            val (source, isAnimated) = withContext(Dispatchers.Default) {
                // The pager's own holder inserts split pages, so don't do it again here.
                val source = viewer.simplePaged.processPagedImage(page, buffer, viewer is L2RPagerViewer) {}
                source to ImageUtil.isAnimatedAndSupported(source)
            }
            presentation.imageView.setImage(
                source,
                isAnimated,
                ReaderPageImageView.Config(
                    zoomDuration = viewer.general.doubleTapAnimDuration,
                    minimumScaleType = viewer.paged.page.imageScaleType.value,
                    cropBorders = viewer.paged.page.cropBorders,
                    zoomStartPosition = ZoomStartPosition.CENTER,
                ),
            )
            presentation.showImage()
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e)
            presentation.showMessage("!")
        }
    }

    /**
     * The window on the second display: one page image, with a spinner and a message for the other states.
     */
    private class BookPresentation(
        private val activity: Activity,
        display: Display,
    ) : Presentation(activity, display) {

        lateinit var imageView: ReaderPageImageView
            private set
        private lateinit var progress: ProgressBar
        private lateinit var message: TextView

        /**
         * Keys pressed while this display has focus (volume keys to turn pages, back to leave) go to the reader, so
         * both screens behave the same and back never closes just this window.
         */
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            return activity.dispatchKeyEvent(event)
        }

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            setCancelable(false)
            imageView = ReaderPageImageView(context)
            progress = ProgressBar(context).apply { isIndeterminate = true }
            message = TextView(context).apply {
                setTextColor(Color.LTGRAY)
                textSize = 18f
                gravity = Gravity.CENTER
            }
            val root = FrameLayout(context).apply {
                setBackgroundColor(Color.BLACK)
                addView(
                    imageView,
                    FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
                )
                addView(
                    progress,
                    FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER),
                )
                addView(
                    message,
                    FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER),
                )
            }
            setContentView(root)
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
}
