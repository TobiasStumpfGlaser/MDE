package com.example.mde

import android.content.Context
import android.os.Looper
import android.view.MotionEvent
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LoginActivityTest {

    private lateinit var context: Context
    private lateinit var settings: AppSettings
    private var previousTimeout = 0
    private var previousDefaultUser = ""
    private var previousOtaEnabled = false

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        settings = AppSettings(context)

        previousTimeout = settings.logoutTimeSec
        previousDefaultUser = settings.defaultUser
        previousOtaEnabled = settings.otaEnabled

        settings.logoutTimeSec = 1
        settings.defaultUser = "missing-user"
        settings.otaEnabled = false

        mockkObject(TcpClient)
        every { TcpClient.sendCommand(any(), any(), any(), any(), any()) } returns
                "{GetBediener}\nAA|1234|User A\n{/GetBediener}"

        mockkObject(UiLoadingHelper)
        every { UiLoadingHelper.show(any(), any(), any(), any()) } just Runs
        every { UiLoadingHelper.update(any(), any(), any()) } just Runs
        every { UiLoadingHelper.showError(any(), any()) } just Runs
        every { UiLoadingHelper.hide() } just Runs
        every { UiLoadingHelper.playErrorSound(any()) } just Runs
    }

    @After
    fun tearDown() {
        unmockkObject(TcpClient)
        unmockkObject(UiLoadingHelper)

        settings.logoutTimeSec = previousTimeout
        settings.defaultUser = previousDefaultUser
        settings.otaEnabled = previousOtaEnabled

        UserCache.userList.clear()
        UserCache.userPinMap.clear()
        UserCache.nameToInitials.clear()
    }

    @Test
    fun currentVersionCheck_isShownOnLoginScreen() {
        val activity = Robolectric.buildActivity(LoginActivity::class.java)
            .create()
            .start()
            .resume()
            .get()

        settings.otaEnabled = true
        activity.runOnUiThread {
            activity.showUpdateCheckResult(Result.success(null))
        }

        val txtVersion = activity.findViewById<TextView>(R.id.txtVersion)
        assertEquals(
            "App-Version: ${BuildConfig.VERSION_NAME}\nVersion ist aktuell",
            txtVersion.text.toString()
        )
    }

    @Test
    fun otaDisabled_hidesVersionCheckStatusButKeepsAppVersion() {
        val activity = Robolectric.buildActivity(LoginActivity::class.java)
            .create()
            .start()
            .resume()
            .get()

        val txtVersion = activity.findViewById<TextView>(R.id.txtVersion)
        assertEquals("App-Version: ${BuildConfig.VERSION_NAME}", txtVersion.text.toString())
    }

    @Test
    fun inactivityTimeout_clearsPinAndUsername() {

        val activity = Robolectric.buildActivity(LoginActivity::class.java)
            .create()
            .start()
            .resume()
            .get()

        val txtUsername =
            activity.findViewById<AutoCompleteTextView>(R.id.txtUsername)

        val txtPin =
            activity.findViewById<EditText>(R.id.txtPin)

        activity.runOnUiThread {
            txtUsername.setText("Someone", false)
            txtPin.setText("9999")
        }

        shadowOf(Looper.getMainLooper()).idle()

        shadowOf(Looper.getMainLooper())
            .idleFor(Duration.ofMillis(1100))

        assertEquals("", txtPin.text.toString())
        assertEquals("", txtUsername.text.toString())
    }

    @Test
    fun dispatchTouchEvent_resetsInactivityTimer() {

        val activity = Robolectric.buildActivity(LoginActivity::class.java)
            .create()
            .start()
            .resume()
            .get()

        val txtPin =
            activity.findViewById<EditText>(R.id.txtPin)

        activity.runOnUiThread {
            txtPin.setText("9999")
        }

        shadowOf(Looper.getMainLooper()).idle()

        val mainLooper = shadowOf(Looper.getMainLooper())

        mainLooper.idleFor(Duration.ofMillis(800))

        val event = MotionEvent.obtain(
            0,
            0,
            MotionEvent.ACTION_DOWN,
            0f,
            0f,
            0
        )

        activity.dispatchTouchEvent(event)
        event.recycle()

        mainLooper.idleFor(Duration.ofMillis(300))

        assertEquals(
            "9999",
            txtPin.text.toString()
        )

        mainLooper.idleFor(Duration.ofMillis(800))

        assertEquals(
            "",
            txtPin.text.toString()
        )
    }

    @Test
    fun onCreate() {

        mockkObject(DatalogicWedgeController)

        every {
            DatalogicWedgeController.setWedgeConfig(any())
        } returns true

        coEvery {
            TcpClient.sendCommand(
                any(),
                any(),
                "GetBediener",
                "{GetBediener}",
                "{/GetBediener}"
            )
        } returns "Initial|PIN|NameLang"

        Robolectric.buildActivity(LoginActivity::class.java)
            .create()
            .start()
            .resume()
            .get()

        shadowOf(Looper.getMainLooper()).idle()

        verify(exactly = 1) {
            DatalogicWedgeController.setWedgeConfig(any())
        }

        coVerify(atLeast = 1) {
            TcpClient.sendCommand(any(), any(), any(), any(), any())
        }
    }

    @Test
    fun login_withValidCredentials_startsMainActivity() {

        UserCache.userList.add("User A")
        UserCache.userPinMap["User A"] = "1234"
        UserCache.nameToInitials["User A"] = "AAA"

        val activity = Robolectric.buildActivity(LoginActivity::class.java)
            .create()
            .start()
            .resume()
            .get()

        val txtUsername =
            activity.findViewById<AutoCompleteTextView>(R.id.txtUsername)

        val txtPin =
            activity.findViewById<EditText>(R.id.txtPin)

        val btnLogin =
            activity.findViewById<Button>(R.id.btnLogin)

        activity.runOnUiThread {
            txtUsername.setText("User A", false)
            txtPin.setText("1234")
            btnLogin.performClick()
        }

        shadowOf(Looper.getMainLooper()).idle()

        val nextIntent =
            shadowOf(activity).nextStartedActivity

        assertEquals(
            MainActivity::class.java.name,
            nextIntent.component?.className
        )

        assertEquals(
            "AAA",
            nextIntent.getStringExtra("USERNAME")
        )
    }

    @Test
    fun login_withWrongPin_clearsPin() {

        UserCache.userList.add("User A")
        UserCache.userPinMap["User A"] = "1234"

        val activity = Robolectric.buildActivity(LoginActivity::class.java)
            .create()
            .start()
            .resume()
            .get()

        val txtUsername =
            activity.findViewById<AutoCompleteTextView>(R.id.txtUsername)

        val txtPin =
            activity.findViewById<EditText>(R.id.txtPin)

        val btnLogin =
            activity.findViewById<Button>(R.id.btnLogin)

        activity.runOnUiThread {
            txtUsername.setText("User A", false)
            txtPin.setText("9999")
            btnLogin.performClick()
        }

        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(
            "",
            txtPin.text.toString()
        )

        verify {
            UiLoadingHelper.showError(
                activity,
                "PIN falsch"
            )
        }
    }

    @Test
    fun login_withInvalidUser_showsError() {

        UserCache.userList.add("User A")
        UserCache.userPinMap["User A"] = "1234"

        val activity = Robolectric.buildActivity(LoginActivity::class.java)
            .create()
            .start()
            .resume()
            .get()

        val txtUsername =
            activity.findViewById<AutoCompleteTextView>(R.id.txtUsername)

        val txtPin =
            activity.findViewById<EditText>(R.id.txtPin)

        val btnLogin =
            activity.findViewById<Button>(R.id.btnLogin)

        activity.runOnUiThread {
            txtUsername.setText("Unknown", false)
            txtPin.setText("1234")
            btnLogin.performClick()
        }

        shadowOf(Looper.getMainLooper()).idle()

        verify {
            UiLoadingHelper.showError(
                activity,
                "Ungültiger Benutzername"
            )
        }
    }

    @Test
    fun onCreate_loadsUserList() {

        Robolectric.buildActivity(LoginActivity::class.java)
            .create()
            .start()
            .resume()
            .get()

        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(
            1,
            UserCache.userList.size
        )

        assertEquals(
            "User A",
            UserCache.userList[0]
        )

        assertEquals(
            "1234",
            UserCache.userPinMap["User A"]
        )

        verify {
            UiLoadingHelper.show(any(), any(), any(), any())
        }
    }

    @Test
    fun login_withoutLoadedUserList_showsError() {

        UserCache.userList.clear()
        UserCache.userPinMap.clear()

        val activity = Robolectric.buildActivity(LoginActivity::class.java)
            .create()
            .start()
            .resume()
            .get()

        val btnLogin =
            activity.findViewById<Button>(R.id.btnLogin)

        activity.runOnUiThread {
            btnLogin.performClick()
        }

        shadowOf(Looper.getMainLooper()).idle()

        verify {
            UiLoadingHelper.showError(
                activity,
                "Benutzerliste noch nicht geladen"
            )
        }
    }

    @Test
    fun defaultUser_isSelectedAutomatically() {

        settings.defaultUser = "User A"
        UserCache.userList.add("User A")

        val activity = Robolectric.buildActivity(LoginActivity::class.java)
            .create()
            .start()
            .resume()
            .get()

        shadowOf(Looper.getMainLooper()).idle()

        val txtUsername =
            activity.findViewById<AutoCompleteTextView>(R.id.txtUsername)

        assertEquals(
            "User A",
            txtUsername.text.toString()
        )
    }

    @Test
    fun inactivityTimeout_restoresDefaultUser() {

        settings.defaultUser = "User A"

        val activity = Robolectric.buildActivity(LoginActivity::class.java)
            .create()
            .start()
            .resume()
            .get()

        val txtUsername =
            activity.findViewById<AutoCompleteTextView>(R.id.txtUsername)

        val txtPin =
            activity.findViewById<EditText>(R.id.txtPin)

        activity.runOnUiThread {
            txtUsername.setText("Other User", false)
            txtPin.setText("9999")
        }

        shadowOf(Looper.getMainLooper())
            .idleFor(Duration.ofMillis(1100))

        assertEquals(
            "User A",
            txtUsername.text.toString()
        )

        assertEquals(
            "",
            txtPin.text.toString()
        )
    }

    @Test
    fun login_success_finishesActivity() {

        UserCache.userList.add("User A")
        UserCache.userPinMap["User A"] = "1234"
        UserCache.nameToInitials["User A"] = "AA"

        val activity = Robolectric.buildActivity(LoginActivity::class.java)
            .create()
            .start()
            .resume()
            .get()

        val txtUsername =
            activity.findViewById<AutoCompleteTextView>(R.id.txtUsername)

        val txtPin =
            activity.findViewById<EditText>(R.id.txtPin)

        val btnLogin =
            activity.findViewById<Button>(R.id.btnLogin)

        activity.runOnUiThread {
            txtUsername.setText("User A", false)
            txtPin.setText("1234")
            btnLogin.performClick()
        }

        shadowOf(Looper.getMainLooper()).idle()

        assert(activity.isFinishing)
    }

    @Test
    fun login_usesUsernameWhenNoInitialsExist() {

        UserCache.userList.add("User A")
        UserCache.userPinMap["User A"] = "1234"

        UserCache.nameToInitials.clear()

        val activity = Robolectric.buildActivity(LoginActivity::class.java)
            .create()
            .start()
            .resume()
            .get()

        val txtUsername =
            activity.findViewById<AutoCompleteTextView>(R.id.txtUsername)

        val txtPin =
            activity.findViewById<EditText>(R.id.txtPin)

        val btnLogin =
            activity.findViewById<Button>(R.id.btnLogin)

        activity.runOnUiThread {
            txtUsername.setText("User A", false)
            txtPin.setText("1234")
            btnLogin.performClick()
        }

        shadowOf(Looper.getMainLooper()).idle()

        val intent =
            shadowOf(activity).nextStartedActivity

        assertEquals(
            "User A",
            intent.getStringExtra("USERNAME")
        )
    }
}
