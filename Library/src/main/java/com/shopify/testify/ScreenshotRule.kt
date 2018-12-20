package com.shopify.testify

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
import android.os.Bundle
import android.os.Debug
import android.os.Looper
import android.support.annotation.FloatRange
import android.support.annotation.IdRes
import android.support.annotation.IntRange
import android.support.annotation.LayoutRes
import android.support.test.InstrumentationRegistry
import android.support.test.InstrumentationRegistry.getInstrumentation
import android.support.test.espresso.Espresso
import android.support.test.rule.ActivityTestRule
import android.util.Pair
import android.view.View
import android.view.ViewGroup
import com.shopify.testify.annotation.BitmapComparisonExactness
import com.shopify.testify.annotation.ScreenshotInstrumentation
import com.shopify.testify.annotation.TestifyLayout
import com.shopify.testify.internal.exception.AssertSameMustBeLastException
import com.shopify.testify.internal.exception.NoScreenshotsOnUiThreadException
import com.shopify.testify.internal.exception.RootViewNotFoundException
import com.shopify.testify.internal.exception.ScreenshotBaselineNotDefinedException
import com.shopify.testify.internal.exception.ScreenshotIsDifferentException
import com.shopify.testify.internal.modification.HideCursorViewModification
import com.shopify.testify.internal.modification.HidePasswordViewModification
import com.shopify.testify.internal.modification.HideScrollbarsViewModification
import com.shopify.testify.internal.modification.HideTextSuggestionsViewModification
import com.shopify.testify.internal.modification.SoftwareRenderViewModification
import junit.framework.Assert.assertNotNull
import junit.framework.Assert.assertTrue
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit


typealias ViewModification = (rootView: ViewGroup) -> Unit
typealias EspressoActions = () -> Unit
typealias ViewProvider = (rootView: ViewGroup) -> View

open class ScreenshotRule<T : Activity> @JvmOverloads constructor(private val activityClass: Class<T>, initialTouchMode: Boolean = false, launchActivity: Boolean = true)
    : ActivityTestRule<T>(activityClass, initialTouchMode, launchActivity), TestRule {

    @LayoutRes private var targetLayoutId: Int = NO_ID
    @IdRes private var rootViewId: Int = android.R.id.content

    private var bitmapCompare: BitmapCompare? = null
    private var defaultLocale: Locale? = null
    private var locale: Locale? = null
    private var exactness: Float? = null

    private var assertSameInvoked = false
    private var hideSoftKeyboard = true
    private var hideScrollbars = true
    private var hidePasswords = true
    private var hideCursor = true
    private var hideTextSuggestions = true
    private var isLayoutInspectionModeEnabled = false
    private var useSoftwareRenderer = false

    lateinit var testMethodName: String
    private lateinit var testSimpleClassName: String
    private lateinit var testClass: String
    private var activityMonitor: Instrumentation.ActivityMonitor? = null

    private var viewModification: ViewModification? = null
    private var espressoActions: EspressoActions? = null
    private var screenshotViewProvider: ViewProvider? = null
    private val testContext = InstrumentationRegistry.getContext()

    private val hidePasswordViewModification = HidePasswordViewModification()
    private val hideScrollbarsViewModification = HideScrollbarsViewModification()
    private val hideTextSuggestionsViewModification = HideTextSuggestionsViewModification()
    private val softwareRenderViewModification = SoftwareRenderViewModification()
    private val hideCursorViewModification = HideCursorViewModification()

    private var throwable: Throwable? = null

    constructor(activityClass: Class<T>, @IdRes rootViewId: Int) : this(activityClass) {
        this.rootViewId = rootViewId
    }

    val testName: String
        get() = testSimpleClassName + "_" + testMethodName

    private val testNameComponents: Boolean
        get () = Looper.getMainLooper().thread == Thread.currentThread()

    private val getTestNameComponents : Pair<String, String>
        get() = Pair(testSimpleClassName, testMethodName)

    private val fullyQualifiedTestPath : String
        get() = testClass

    fun setExactness(@FloatRange(from = 0.0, to = 1.0) exactness: Float): ScreenshotRule<T> {
        this.exactness = exactness
        return this
    }

    fun setHideSoftKeyboard(hideSoftKeyboard: Boolean): ScreenshotRule<T> {
        this.hideSoftKeyboard = hideSoftKeyboard
        return this
    }

    fun setHideScrollbars(hideScrollbars: Boolean): ScreenshotRule<T> {
        this.hideScrollbars = hideScrollbars
        return this
    }

    fun setHidePasswords(hidePasswords: Boolean): ScreenshotRule<T> {
        this.hidePasswords = hidePasswords
        return this
    }

    fun setHideCursor(hideCursor: Boolean): ScreenshotRule<T> {
        this.hideCursor = hideCursor
        return this
    }

    fun setHideTextSuggestions(hideTextSuggestions: Boolean): ScreenshotRule<T> {
        this.hideTextSuggestions = hideTextSuggestions
        return this
    }

    fun setUseSoftwareRenderer(useSoftwareRenderer: Boolean): ScreenshotRule<T> {
        this.useSoftwareRenderer = useSoftwareRenderer
        return this
    }

    fun setRootViewId(@IdRes rootViewId: Int): ScreenshotRule<T> {
        this.rootViewId = rootViewId
        return this
    }

    fun setTargetLayoutId(@LayoutRes layoutId: Int): ScreenshotRule<T> {
        this.targetLayoutId = layoutId
        return this
    }

    fun setEspressoActions(espressoActions: EspressoActions): ScreenshotRule<T> {
        if (assertSameInvoked) {
            throw AssertSameMustBeLastException()
        }
        this.espressoActions = espressoActions
        return this
    }

    fun setViewModifications(viewModification: ViewModification): ScreenshotRule<T> {
        if (assertSameInvoked) {
            throw AssertSameMustBeLastException()
        }
        this.viewModification = viewModification
        return this
    }

    fun setLayoutInspectionModeEnabled(layoutInspectionModeEnabled: Boolean): ScreenshotRule<T> {
        this.isLayoutInspectionModeEnabled = layoutInspectionModeEnabled
        return this
    }

    fun setScreenshotViewProvider(viewProvider: ViewProvider): ScreenshotRule<T> {
        this.screenshotViewProvider = viewProvider
        return this
    }

    fun setLocale(newLocale: Locale): ScreenshotRule<T> {
        this.locale = newLocale
        return this
    }

    /**
     * Install an activity monitor and set the requested orientation.
     * Blocks and waits for the orientation change to complete before returning.
     *
     * @param requestedOrientation SCREEN_ORIENTATION_LANDSCAPE or SCREEN_ORIENTATION_PORTRAIT
     */
    fun setOrientation(@IntRange(from = SCREEN_ORIENTATION_LANDSCAPE.toLong(), to = SCREEN_ORIENTATION_PORTRAIT.toLong()) requestedOrientation: Int) {
        if (activity.requestedOrientation != requestedOrientation) {
            val activityMonitor = getActivityMonitor()
            getInstrumentation().addMonitor(activityMonitor)

            activity.requestedOrientation = requestedOrientation
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        }
    }

    private fun getActivityMonitor(): Instrumentation.ActivityMonitor {
        if (activityMonitor == null) {
            activityMonitor = Instrumentation.ActivityMonitor(activityClass.name, null, true)
        }
        return activityMonitor!!
    }

    override fun getActivity(): T {
        var activity: Activity? = null
        if (activityMonitor != null) {
            activity = activityMonitor!!.lastActivity
        }
        if (activity == null) {
            activity = super.getActivity()
        }
        @Suppress("UNCHECKED_CAST")
        return activity as T
    }

    override fun apply(base: Statement, description: Description): Statement {
        checkForScreenshotInstrumentationAnnotation(description)

        applyExactness(description)

        espressoActions = null
        testSimpleClassName = description.testClass.simpleName
        testMethodName = description.methodName
        testClass = description.testClass.canonicalName + "#" + description.methodName
        val testifyLayout = description.getAnnotation(TestifyLayout::class.java)
        targetLayoutId = testifyLayout?.layoutId ?: View.NO_ID
        return super.apply(ScreenshotStatement(base), description)
    }

    private fun checkForScreenshotInstrumentationAnnotation(description: Description) {
        val classAnnotation = description.testClass.getAnnotation(ScreenshotInstrumentation::class.java)
        if (classAnnotation == null) {
            val methodAnnotation = description.getAnnotation(ScreenshotInstrumentation::class.java)
            if (methodAnnotation == null) {
                this.throwable = Exception("Please add @ScreenshotInstrumentation for the test '${description.methodName}'")
            }
        }
    }

    // todo merge getActivityIntent() and getIntent()
    override fun getActivityIntent(): Intent {
        var intent: Intent? = super.getActivityIntent()
        if (intent == null) {
            intent = getIntent()
        }
        return intent
    }

    protected fun getIntent(): Intent {
        var intent = super.getActivityIntent()
        if (intent == null) {
            intent = Intent()
        }
        return intent
    }

    private fun applyExactness(description: Description) {
        if (exactness == null) {
            val bitmapComparison = description.getAnnotation(BitmapComparisonExactness::class.java)
            exactness = bitmapComparison?.exactness
        }
    }

    /**
     * @param exactness 0.0: matches against anything(no differences)
     *                  1.0: exact matches only
     * @throws Exception same as assertSame()
     */
    fun assertSame() {
        assertSameInvoked = true
        try {
            locale?.let {
                defaultLocale = Locale.getDefault()
                LocaleHelper.setTestLocale(it)
            }

            exactness?.let {
                bitmapCompare = FuzzyCompare(it)
            }

            try {
                if (testNameComponents) {
                    throw NoScreenshotsOnUiThreadException()
                }

                initializeView(activity)

                if (espressoActions != null) {
                    espressoActions!!.invoke()
                }

                Espresso.onIdle()

                if (hideSoftKeyboard) {
                    Espresso.closeSoftKeyboard()
                }

                val screenshotUtility = ScreenshotUtility()
                screenshotUtility.setLocale(if (defaultLocale != null) Locale.getDefault() else null)

                var screenshotView: View? = null
                if (screenshotViewProvider != null) {
                    screenshotView = screenshotViewProvider!!.invoke(getRootView(activity))
                }

                val outputFileName = DeviceIdentifier.formatDeviceString(DeviceIdentifier.DeviceStringFormatter(testContext, getTestNameComponents), getOutputFileNameFormatString())
                val currentBitmap = screenshotUtility.createBitmapFromActivity(activity, outputFileName, screenshotView)
                assertNotNull("Failed to capture bitmap from activity", currentBitmap)

                if (isLayoutInspectionModeEnabled) {
                    Thread.sleep(LAYOUT_INSPECTION_TIME_MS.toLong())
                }

                val baselineBitmap = screenshotUtility.loadBaselineBitmapForComparison(testContext, testName)
                    ?: if (isRecordMode()) {
                        instrumentationPrintln("\n\t✓ " + 27.toChar() + "[36mRecording baseline for " + testName + 27.toChar() + "[0m")
                        return
                    } else {
                        throw ScreenshotBaselineNotDefinedException(getModuleName(), testName, fullyQualifiedTestPath)
                    }

                if (bitmapCompare == null) {
                    bitmapCompare = screenshotUtility
                }

                if (bitmapCompare!!.compareBitmaps(baselineBitmap, currentBitmap)) {
                    assertTrue("Could not delete cached bitmap $testName", screenshotUtility.deleteBitmap(activity, outputFileName))
                } else {
                    if (isRecordMode()) {
                        instrumentationPrintln("\n\t✓ " + 27.toChar() + "[36mRecording baseline for " + testName + 27.toChar() + "[0m")
                    } else {
                        throw ScreenshotIsDifferentException(getModuleName(), fullyQualifiedTestPath)
                    }
                }
            } finally {
                defaultLocale?.let {
                    LocaleHelper.setTestLocale(it)
                }
            }
        } finally {
            removeActivityMonitor()
            if (throwable != null) {
                //noinspection ThrowFromfinallyBlock
                throw RuntimeException(throwable)
            }
        }
    }

    private fun removeActivityMonitor() {
        if (activityMonitor != null) {
            getInstrumentation().removeMonitor(activityMonitor)
            activityMonitor = null
        }
    }

    private fun initializeView(activity: Activity) {
        val parentView = getRootView(activity)
        val latch = CountDownLatch(1)

        activity.runOnUiThread {
            if (targetLayoutId != NO_ID) {
                activity.layoutInflater.inflate(targetLayoutId, parentView, true)
            }
            if (viewModification != null) {
                viewModification!!.invoke(parentView)
            }

            hideScrollbarsViewModification.modify(parentView)
            hideTextSuggestionsViewModification.modify(parentView)
            hidePasswordViewModification.modify(parentView)
            softwareRenderViewModification.modify(parentView)
            hideCursorViewModification.modify(parentView)

            latch.countDown()
        }
        if (Debug.isDebuggerConnected()) {
            latch.await()
        } else {
            assertTrue(latch.await(INFLATE_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        }
    }

    private fun getRootView(activity: Activity): ViewGroup {
        return activity.findViewById(rootViewId)
            ?: throw RootViewNotFoundException(activity, rootViewId)
    }

    private fun instrumentationPrintln(str: String) {
        val b = Bundle()
        b.putString(Instrumentation.REPORT_KEY_STREAMRESULT, "\n" + str)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, b)
    }

    private fun isRecordMode(): Boolean {
        val extras = InstrumentationRegistry.getArguments()
        return extras.containsKey("isRecordMode") && extras.get("isRecordMode") == "true"
    }

    private fun getModuleName(): String {
        val extras = InstrumentationRegistry.getArguments()
        return if (extras.containsKey("moduleName")) extras.getString("moduleName")!! + ":" else ""
    }

    private fun getOutputFileNameFormatString(): String {
        val extras = InstrumentationRegistry.getArguments()
        var formatString: String = DeviceIdentifier.DEFAULT_NAME_FORMAT
        if (extras.containsKey("outputFileNameFormat")) {
            formatString = extras.getString("outputFileNameFormat")
        }
        return formatString
    }

    private inner class ScreenshotStatement constructor(private val base: Statement) : Statement() {

        override fun evaluate() {
            assertSameInvoked = false
            base.evaluate()
            // Safeguard against accidentally omitting the call to `assertSame`
            if (!assertSameInvoked) {
                throw RuntimeException("\n\n* You must call assertSame on the ScreenshotRule *\n")
            }
        }
    }

    companion object {
        const val NO_ID = -1
        private const val LAYOUT_INSPECTION_TIME_MS = 60000
        private const val INFLATE_TIMEOUT_SECONDS: Long = 5
    }
}