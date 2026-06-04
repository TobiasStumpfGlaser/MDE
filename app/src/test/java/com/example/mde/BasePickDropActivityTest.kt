package com.example.mde

import android.content.Intent
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.AutoCompleteTextView
import android.widget.EditText
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import io.mockk.Runs
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowDialog
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BasePickDropActivityTest {

    @Before
    fun setUp() {
        mockkObject(TcpClient)
        every { TcpClient.sendCommand(any(), any(), any(), any(), any()) } answers {
            when (thirdArg<String>()) {
                "GetPickOverview" -> "{GetPickOverview}\nL1|P100|Projekt 100\n{/GetPickOverview}"
                "GetDropOverview" -> "{GetDropOverview}\nL1|P100|Projekt 100\n{/GetDropOverview}"
                "GetPick_L1" -> "{GetPick_L1}\n123.4567|3|1|Test Info\n{/GetPick_L1}"
                "GetDrop_L1" -> "{GetDrop_L1}\n123.4567|4|1|Test Info\n{/GetDrop_L1}"
                "GetArtikel" -> "{GetArtikel}\n123.4567|Artikel|A|B|C|D|E|F|ST|1|0|0|0|G|L\n{/GetArtikel}"
                "SetBuchung" -> "{SetBuchung}\nok\n{/SetBuchung}"
                else -> ""
            }
        }

        mockkObject(UiLoadingHelper)
        every { UiLoadingHelper.show(any(), any(), any(), any()) } just Runs
        every { UiLoadingHelper.hide() } just Runs
        every { UiLoadingHelper.showError(any(), any()) } just Runs
        every { UiLoadingHelper.update(any(), any(), any()) } just Runs
        every { UiLoadingHelper.playErrorSound(any()) } just Runs
        every {
            UiLoadingHelper.confirm(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } answers {
            val onOk = arg<() -> Unit>(7)
            onOk.invoke()
            Unit
        }

        DataRepository.clear()
    }

    @After
    fun tearDown() {
        unmockkObject(TcpClient)
        unmockkObject(UiLoadingHelper)
        DataRepository.clear()
    }

    private fun waitForDetailsLoaded(activity: BasePickDropActivity): RecyclerView {
        val rvDetails = activity.findViewById<RecyclerView>(R.id.rvDetails)

        var attempts = 0
        while (rvDetails.visibility != View.VISIBLE && attempts < 50) {
            Thread.sleep(100)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            attempts++
        }
        assertEquals(View.VISIBLE, rvDetails.visibility)

        attempts = 0
        while ((rvDetails.adapter?.itemCount ?: 0) == 0 && attempts < 50) {
            Thread.sleep(100)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            attempts++
        }
        assertTrue((rvDetails.adapter?.itemCount ?: 0) > 0)

        Thread.sleep(500)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        return rvDetails
    }

    @Test
    fun pickActivity_loadsOverviewList_successfully() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), PickListActivity::class.java)
        intent.putExtra("USERNAME", "tester")
        val activity = Robolectric.buildActivity(PickListActivity::class.java, intent)
            .create().start().resume().get()

        Shadows.shadowOf(Looper.getMainLooper()).idle()

        verify(timeout = 2000) {
            TcpClient.sendCommand(
                context = any(),
                settings = any(),
                command = "GetPickOverview",
                request = "{GetPickOverview}",
                endTag = "{/GetPickOverview}"
            )
        }
    }

    @Test
    fun pickActivity_filtersList_andLoadsDetails() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), PickListActivity::class.java)
        intent.putExtra("USERNAME", "tester")
        val activity = Robolectric.buildActivity(PickListActivity::class.java, intent)
            .create().start().resume().get()

        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val etListFilter = activity.findViewById<AutoCompleteTextView>(R.id.etListFilter)
        val etProjectNumber = activity.findViewById<EditText>(R.id.etProjectNumber)

        etListFilter.setText("L1")
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals("P100", etProjectNumber.text.toString())

        verify(timeout = 2000) {
            TcpClient.sendCommand(
                context = any(),
                settings = any(),
                command = "GetPick_L1",
                request = "{GetPick_L1}",
                endTag = "{/GetPick_L1}"
            )
        }
    }

    @Test
    fun pickActivity_scansBarcode_opensDialog() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), PickListActivity::class.java)
        intent.putExtra("USERNAME", "tester")
        val activity = Robolectric.buildActivity(PickListActivity::class.java, intent)
            .create().start().resume().get()

        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val etListFilter = activity.findViewById<AutoCompleteTextView>(R.id.etListFilter)
        etListFilter.setText("L1")
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        verify(timeout = 2000) {
            TcpClient.sendCommand(
                context = any(),
                settings = any(),
                command = "GetPick_L1",
                request = "{GetPick_L1}",
                endTag = "{/GetPick_L1}"
            )
        }

        waitForDetailsLoaded(activity)

        ShadowSystemClock.advanceBy(Duration.ofMillis(300))

        clearMocks(TcpClient, answers = false, recordedCalls = true)

        val etDetailFilter = activity.findViewById<AutoCompleteTextView>(R.id.etDetailFilter)
        etDetailFilter.setText("123.4567")
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val dialog = ShadowDialog.getLatestDialog()
        assertNotNull(dialog)
        assertTrue(dialog.isShowing)
    }

    @Test
    fun pickActivity_clicksDetailItem_opensDialog() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), PickListActivity::class.java)
        intent.putExtra("USERNAME", "tester")
        val activity = Robolectric.buildActivity(PickListActivity::class.java, intent)
            .create().start().resume().get()

        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val etListFilter = activity.findViewById<AutoCompleteTextView>(R.id.etListFilter)
        etListFilter.setText("L1")
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        verify(timeout = 2000) {
            TcpClient.sendCommand(
                context = any(),
                settings = any(),
                command = "GetPick_L1",
                request = "{GetPick_L1}",
                endTag = "{/GetPick_L1}"
            )
        }

        val rvDetails = waitForDetailsLoaded(activity)

        val adapter = rvDetails.adapter
        if (adapter != null && adapter.itemCount > 0) {
            val holder = adapter.createViewHolder(rvDetails, 0)
            adapter.onBindViewHolder(holder, 0)
            holder.itemView.performClick()
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            val dialog = ShadowDialog.getLatestDialog()
            assertNotNull(dialog)
            assertTrue(dialog.isShowing)
        }
    }

    @Test
    fun pickBooking_confirmButton_sendsNegativeAmount() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), PickListActivity::class.java)
        intent.putExtra("USERNAME", "tester")
        val activity = Robolectric.buildActivity(PickListActivity::class.java, intent)
            .create().start().resume().get()

        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val etListFilter = activity.findViewById<AutoCompleteTextView>(R.id.etListFilter)
        etListFilter.setText("L1")
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        verify(timeout = 2000) {
            TcpClient.sendCommand(
                context = any(),
                settings = any(),
                command = "GetPick_L1",
                request = "{GetPick_L1}",
                endTag = "{/GetPick_L1}"
            )
        }

        waitForDetailsLoaded(activity)

        ShadowSystemClock.advanceBy(Duration.ofMillis(300))

        clearMocks(TcpClient, answers = false, recordedCalls = true)

        val etDetailFilter = activity.findViewById<AutoCompleteTextView>(R.id.etDetailFilter)
        etDetailFilter.setText("123.4567")
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val dialog = ShadowDialog.getLatestDialog()
        assertNotNull(dialog)
        assertTrue(dialog.isShowing)

        val btnYes = dialog.findViewById<View>(R.id.btnYes)
        assertNotNull("Button btnYes not found", btnYes)
        btnYes.performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        verify(timeout = 2000) {
            TcpClient.sendCommand(
                context = any(),
                settings = any(),
                command = "SetBuchung",
                request = match<String> { it.contains("||-3|") },
                endTag = "{/SetBuchung}"
            )
        }
    }

    @Test
    fun dropBooking_confirmButton_sendsPositiveAmount() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), DropListActivity::class.java)
        intent.putExtra("USERNAME", "tester")
        val activity = Robolectric.buildActivity(DropListActivity::class.java, intent)
            .create().start().resume().get()

        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val etListFilter = activity.findViewById<AutoCompleteTextView>(R.id.etListFilter)
        etListFilter.setText("L1")
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        verify(timeout = 2000) {
            TcpClient.sendCommand(
                context = any(),
                settings = any(),
                command = "GetDrop_L1",
                request = "{GetDrop_L1}",
                endTag = "{/GetDrop_L1}"
            )
        }

        waitForDetailsLoaded(activity)

        ShadowSystemClock.advanceBy(Duration.ofMillis(300))

        clearMocks(TcpClient, answers = false, recordedCalls = true)

        val etDetailFilter = activity.findViewById<AutoCompleteTextView>(R.id.etDetailFilter)
        etDetailFilter.setText("123.4567")
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val dialog = ShadowDialog.getLatestDialog()
        assertNotNull(dialog)
        assertTrue(dialog.isShowing)

        val btnYes = dialog.findViewById<View>(R.id.btnYes)
        assertNotNull("Button btnYes not found", btnYes)
        btnYes.performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        verify(timeout = 2000) {
            TcpClient.sendCommand(
                context = any(),
                settings = any(),
                command = "SetBuchung",
                request = match<String> { it.contains("||+4|") },
                endTag = "{/SetBuchung}"
            )
        }
    }

    @Test
    fun dropActivity_loadsOverviewList_successfully() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), DropListActivity::class.java)
        intent.putExtra("USERNAME", "tester")
        val activity = Robolectric.buildActivity(DropListActivity::class.java, intent)
            .create().start().resume().get()

        Shadows.shadowOf(Looper.getMainLooper()).idle()

        verify(timeout = 2000) {
            TcpClient.sendCommand(
                context = any(),
                settings = any(),
                command = "GetDropOverview",
                request = "{GetDropOverview}",
                endTag = "{/GetDropOverview}"
            )
        }
    }

    @Test
    fun dispatchTouchEvent_resetsLogoutTimer() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val settings = AppSettings(context)
        val previousTimeout = settings.logoutTimeSec
        settings.logoutTimeSec = 1

        try {
            val intent = Intent(context, PickListActivity::class.java)
            intent.putExtra("USERNAME", "tester")
            val activity = Robolectric.buildActivity(PickListActivity::class.java, intent)
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
        } finally {
            settings.logoutTimeSec = previousTimeout
        }
    }
}
