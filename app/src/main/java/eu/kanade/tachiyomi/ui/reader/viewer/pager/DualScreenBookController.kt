package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.app.ActivityOptions
import android.content.Intent
import android.graphics.ColorFilter
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.KeyEvent
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
import java.lang.ref.WeakReference

/**
 * Book mode for phones with a second display, such as the LG Dual Screen accessory (LG V50, V60, G8X).
 *
 * The second display shows the page next to the one in [viewer]'s pager, so both screens read like an open book.
 * The page lives in a [DualScreenPageActivity] started on that display: devices like the LG V60 refuse windows from
 * other apps there, but let any app start an activity on it.
 *
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

    /** The activity showing the facing page, once it has started. */
    private var host: DualScreenPageActivity? = null

    /** The display the facing page activity was started on, while it's starting or shown. */
    private var launchedDisplayId: Int? = null
    private var lastLaunchAt = 0L

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
        get() = host != null

    init {
        current = WeakReference(this)
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
        invalidate()
    }

    fun destroy() {
        activity.lifecycle.removeObserver(this)
        displayManager?.unregisterDisplayListener(displayListener)
        closeHost()
        scope.cancel()
        if (current?.get() === this) current = null
    }

    /** Takes [page] as the facing page, unless it's no longer wanted. Returns whether it was taken. */
    private fun onHostAttached(page: DualScreenPageActivity): Boolean {
        if (host != null || launchedDisplayId == null) return false
        host = page
        shownItem = null
        refreshPage()
        return true
    }

    private fun onHostDetached(page: DualScreenPageActivity) {
        if (host !== page) return
        host = null
        launchedDisplayId = null
        loadJob?.cancel()
        shownItem = null
    }

    private fun update() {
        val enabled = started && preferences.dualScreenBookMode.get()
        val display = if (enabled) findOtherDisplay() else null
        val hostDisplay = host?.let(::displayIdOf) ?: launchedDisplayId
        if (hostDisplay != null && (display == null || hostDisplay != display.displayId)) {
            closeHost()
        }

        var result = if (display == null) "no second display found" else "already open on ${display.displayId}"
        // Don't start it again right away if it closed itself, so a display that refuses it isn't hammered.
        val canLaunch = SystemClock.elapsedRealtime() - lastLaunchAt > 2000
        if (display != null && host == null && launchedDisplayId == null && canLaunch) {
            result = launchOn(display)
        }

        if (enabled && !diagnosed) {
            diagnosed = true
            showDiagnostics(result)
        }
    }

    private fun launchOn(display: Display): String {
        return try {
            val flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                Intent.FLAG_ACTIVITY_NO_ANIMATION
            val intent = Intent(activity, DualScreenPageActivity::class.java).addFlags(flags)
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(display.displayId)
            launchedDisplayId = display.displayId
            lastLaunchAt = SystemClock.elapsedRealtime()
            activity.startActivity(intent, options.toBundle())
            "started on ${display.displayId}"
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Couldn't start the book page on display ${display.displayId}" }
            launchedDisplayId = null
            "error on ${display.displayId}: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private fun closeHost() {
        loadJob?.cancel()
        loadJob = null
        shownItem = null
        val page = host
        host = null
        launchedDisplayId = null
        try {
            page?.finishAndRemoveTask()
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e)
        }
    }

    private fun displayIdOf(page: DualScreenPageActivity): Int? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            page.display?.displayId
        } else {
            @Suppress("DEPRECATION")
            page.windowManager.defaultDisplay.displayId
        }
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
        return dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull(usable)
            ?: dm.displays.firstOrNull(usable)
    }

    /**
     * Temporary diagnostics for testing on the LG Dual Screen: lists the displays Android reports and what happened.
     */
    private fun showDiagnostics(result: String) {
        val dm = displayManager
        val displays = dm?.displays.orEmpty().joinToString("\n") { d ->
            "• ${d.displayId} '${d.name}' state=${d.state} flags=${d.flags}"
        }
        val text = buildString {
            appendLine("${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
            appendLine("Reader on display ${currentDisplayId()}")
            appendLine("Displays:")
            appendLine(displays.ifEmpty { "(none)" })
            append("Result: $result")
        }
        logcat(LogPriority.INFO) { text }
        android.app.AlertDialog.Builder(activity)
            .setTitle("Dual Screen")
            .setMessage(text)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun refreshPage() {
        val pageView = host?.pageView ?: return
        val onLeft = (currentDisplayId() == Display.DEFAULT_DISPLAY) != preferences.dualScreenSwapSides.get()
        val item = if (currentPosition < 0) {
            null
        } else {
            items().getOrNull(if (onLeft) currentPosition - 1 else currentPosition + 1)
        }
        if (item != null && item === shownItem) return
        shownItem = item

        loadJob?.cancel()
        pageView.imageView.pageColorFilter = colorFilter
        when (item) {
            is ReaderPage -> loadJob = scope.launch { showPage(pageView, item) }
            is ChapterTransition -> pageView.showMessage(item.to?.let { "→ ${it.chapter.name}" }.orEmpty())
            else -> pageView.showMessage("")
        }
    }

    /**
     * Shows [page] once it's ready. The pager keeps the pages next to the current one loaded, so this only follows
     * the page's status instead of loading it again.
     */
    private suspend fun showPage(pageView: BookPageView, page: ReaderPage) {
        pageView.showLoading()
        page.statusFlow.collectLatest { state ->
            when (state) {
                Page.State.Ready -> setImage(pageView, page)
                is Page.State.Error -> pageView.showMessage("!")
                else -> pageView.showLoading()
            }
        }
    }

    private suspend fun setImage(pageView: BookPageView, page: ReaderPage) {
        val streamFn = page.stream ?: return
        try {
            val buffer = withContext(Dispatchers.IO) { streamFn().use { Buffer().readFrom(it) } }
            val (source, isAnimated) = withContext(Dispatchers.Default) {
                // The pager's own holder inserts split pages, so don't do it again here.
                val source = viewer.simplePaged.processPagedImage(page, buffer, viewer is L2RPagerViewer) {}
                source to ImageUtil.isAnimatedAndSupported(source)
            }
            pageView.imageView.setImage(
                source,
                isAnimated,
                ReaderPageImageView.Config(
                    zoomDuration = viewer.general.doubleTapAnimDuration,
                    minimumScaleType = viewer.paged.page.imageScaleType.value,
                    cropBorders = viewer.paged.page.cropBorders,
                    zoomStartPosition = ZoomStartPosition.CENTER,
                ),
            )
            pageView.showImage()
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e)
            pageView.showMessage("!")
        }
    }

    companion object {
        private var current: WeakReference<DualScreenBookController>? = null

        /** Hands [page] to the open reader. Returns false if there's no reader to show pages for. */
        fun attach(page: DualScreenPageActivity): Boolean {
            return current?.get()?.onHostAttached(page) ?: false
        }

        fun detach(page: DualScreenPageActivity) {
            current?.get()?.onHostDetached(page)
        }

        /** Sends [event] to the reader, returning whether it was handled. */
        fun dispatchKey(event: KeyEvent): Boolean {
            return current?.get()?.activity?.dispatchKeyEvent(event) ?: false
        }
    }
}
