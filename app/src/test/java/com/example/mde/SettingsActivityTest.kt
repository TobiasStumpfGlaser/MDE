package com.example.mde

import android.content.Context
import android.os.Looper
import android.view.MotionEvent
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SettingsActivityTest {

    private lateinit var context: Context
    private lateinit var settings: AppSettings
    private var previousTimeout = 0
    private var previousOtaEnabled = false
    private var previousOtaServer = ""
    private var previousOtaConnectHost = ""
    private var previousOtaShare = ""
    private var previousOtaBasePath = ""
    private var previousOtaRealm = ""
    private var previousOtaKdcAddress = ""

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        settings = AppSettings(context)
        previousTimeout = settings.logoutTimeSec
        previousOtaEnabled = settings.otaEnabled
        previousOtaServer = settings.otaServer
        previousOtaConnectHost = settings.otaConnectHost
        previousOtaShare = settings.otaShare
        previousOtaBasePath = settings.otaBasePath
        previousOtaRealm = settings.otaRealm
        previousOtaKdcAddress = settings.otaKdcAddress
        settings.logoutTimeSec = 1
    }

    @After
    fun tearDown() {
        settings.logoutTimeSec = previousTimeout
        settings.otaEnabled = previousOtaEnabled
        settings.otaServer = previousOtaServer
        settings.otaConnectHost = previousOtaConnectHost
        settings.otaShare = previousOtaShare
        settings.otaBasePath = previousOtaBasePath
        settings.otaRealm = previousOtaRealm
        settings.otaKdcAddress = previousOtaKdcAddress
    }

    @Test
    fun otaSettings_areLoadedAndSaved() {
        settings.otaEnabled = false
        settings.otaServer = "old-server"
        settings.otaConnectHost = "old-server.example.test"
        settings.otaShare = "old-share"
        settings.otaBasePath = "old/path"
        settings.otaRealm = "OLD.EXAMPLE.TEST"
        settings.otaKdcAddress = "old-kdc.example.test:1088"

        val activity = Robolectric.buildActivity(SettingsActivity::class.java)
            .create().start().resume().get()
        val enabled = activity.findViewById<CheckBox>(R.id.cbOtaEnabled)
        val server = activity.findViewById<EditText>(R.id.etOtaServer)
        val connectHost = activity.findViewById<EditText>(R.id.etOtaConnectHost)
        val share = activity.findViewById<EditText>(R.id.etOtaShare)
        val basePath = activity.findViewById<EditText>(R.id.etOtaBasePath)
        val realm = activity.findViewById<EditText>(R.id.etOtaRealm)
        val kdcAddress = activity.findViewById<EditText>(R.id.etOtaKdcAddress)

        assertEquals(false, enabled.isChecked)
        assertEquals("old-server", server.text.toString())
        assertEquals("old-server.example.test", connectHost.text.toString())
        assertEquals("old-share", share.text.toString())
        assertEquals("old/path", basePath.text.toString())
        assertEquals("OLD.EXAMPLE.TEST", realm.text.toString())
        assertEquals("old-kdc.example.test:1088", kdcAddress.text.toString())

        enabled.isChecked = true
        server.setText("  new-server  ")
        connectHost.setText("  new-server.example.test  ")
        share.setText("  new-share  ")
        basePath.setText("new/path")
        realm.setText("new.example.test")
        kdcAddress.setText("new-kdc.example.test")
        activity.findViewById<Button>(R.id.btnSave).performClick()

        assertEquals(true, settings.otaEnabled)
        assertEquals("new-server", settings.otaServer)
        assertEquals("new-server.example.test", settings.otaConnectHost)
        assertEquals("new-share", settings.otaShare)
        assertEquals("new/path", settings.otaBasePath)
        assertEquals("NEW.EXAMPLE.TEST", settings.otaRealm)
        assertEquals("new-kdc.example.test:88", settings.otaKdcAddress)
    }

    @Test
    fun invalidOtaSetting_doesNotPersistAnyOtaValue() {
        settings.otaEnabled = false
        settings.otaServer = "old-server"
        settings.otaKdcAddress = "old-kdc.example.test:88"
        val activity = Robolectric.buildActivity(SettingsActivity::class.java)
            .create().start().resume().get()

        activity.findViewById<CheckBox>(R.id.cbOtaEnabled).isChecked = true
        activity.findViewById<EditText>(R.id.etOtaServer).setText("new-server")
        val kdcAddress = activity.findViewById<EditText>(R.id.etOtaKdcAddress)
        kdcAddress.setText("kdc.example.test:not-a-port")
        activity.findViewById<Button>(R.id.btnSave).performClick()

        assertEquals(false, settings.otaEnabled)
        assertEquals("old-server", settings.otaServer)
        assertEquals("old-kdc.example.test:88", settings.otaKdcAddress)
        assertNotNull(kdcAddress.error)
    }

    @Test
    fun dispatchTouchEvent_resetsInactivityTimer() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java)
            .create().start().resume().get()

        val shadowActivity = Shadows.shadowOf(activity)
        val mainLooper = Shadows.shadowOf(Looper.getMainLooper())

        mainLooper.idleFor(Duration.ofMillis(800))
        val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0f, 0f, 0)
        activity.dispatchTouchEvent(event)
        event.recycle()

        mainLooper.idleFor(Duration.ofMillis(300))
        assertNull(shadowActivity.nextStartedActivity)

        mainLooper.idleFor(Duration.ofMillis(800))
        val startedIntent = shadowActivity.nextStartedActivity
        assertNotNull(startedIntent)
        assertEquals(LoginActivity::class.java.name, startedIntent.component?.className)
    }
}
