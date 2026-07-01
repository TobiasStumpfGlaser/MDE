package com.example.mde

import android.content.Intent
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AutoCompleteTextView
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.example.mde.model.Artikel
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
import android.widget.TextView

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BasePickDropActivityTest {

    @Before
    fun setUp() {
        mockkObject(TcpClient)
        every { TcpClient.sendCommand(any(), any(), any(), any(), any()) } answers {
            when (thirdArg<String>()) {
                "GetPickOverview" -> "{GetPickOverview}\nL1|PK|P100|Projekt 100\n{/GetPickOverview}"
                "GetDropOverview" -> "{GetDropOverview}\nL1|GN|P100|Projekt 100\n{/GetDropOverview}"
                "GetPick_L1" -> "{GetPick_L1}\n123.4567|3|1|Test Info\n{/GetPick_L1}"
                "GetDrop_L1" -> "{GetDrop_L1}\n123.4567|4|1|Test Info\n{/GetDrop_L1}"
                "GetArtikel" -> "{GetArtikel}\n123.4567|Artikel|A|B|C|D|E|F|ST|1|0|0|0|G||0|0|4000000000001|SUCH1|LIEF1\n765.4321|Ersatzartikel|A|B|C|D|E|F|ST|1|0|0|0|H||0|0|4001234567892|SUCH2|LIEF2\n{/GetArtikel}"
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

    private fun findEditText(root: View): EditText? {
        if (root is EditText) return root
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                val match = findEditText(root.getChildAt(index))
                if (match != null) return match
            }
        }
        return null
    }

    @Test
    fun pickActivity_loadsOverviewList_successfully() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), PickListActivity::class.java)
        intent.putExtra("USERNAME", "tester")
        Robolectric.buildActivity(PickListActivity::class.java, intent)
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
    fun pickBooking_changeArticleDialog_resolvesScannedEanToArtikelnummer() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), PickListActivity::class.java)
        intent.putExtra("USERNAME", "tester")
        val activity = Robolectric.buildActivity(PickListActivity::class.java, intent)
            .create().start().resume().get()

        Shadows.shadowOf(Looper.getMainLooper()).idle()

        activity.findViewById<AutoCompleteTextView>(R.id.etListFilter).setText("L1")
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        waitForDetailsLoaded(activity)

        ShadowSystemClock.advanceBy(Duration.ofMillis(300))

        val etDetailFilter = activity.findViewById<AutoCompleteTextView>(R.id.etDetailFilter)
        etDetailFilter.setText("123.4567")
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val itemDialog = ShadowDialog.getLatestDialog()
        assertNotNull(itemDialog)
        assertTrue(itemDialog.isShowing)

        itemDialog.findViewById<View>(R.id.btnChangeArticle).performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val changeDialog = ShadowDialog.getLatestDialog() as AlertDialog
        activity.onBarcodeScanned("4001234567892")
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        changeDialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        clearMocks(TcpClient, answers = false, recordedCalls = true)

        itemDialog.findViewById<View>(R.id.btnYes).performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        verify(timeout = 2000) {
            TcpClient.sendCommand(
                context = any(),
                settings = any(),
                command = "SetBuchung",
                request = match<String> { it.contains("123.4567|765.4321|-3|") },
                endTag = "{/SetBuchung}"
            )
        }
    }

    @Test
    fun pickBooking_changeArticleDialog_rejectsPartialEanMatch() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), PickListActivity::class.java)
        intent.putExtra("USERNAME", "tester")
        val activity = Robolectric.buildActivity(PickListActivity::class.java, intent)
            .create().start().resume().get()

        Shadows.shadowOf(Looper.getMainLooper()).idle()

        activity.findViewById<AutoCompleteTextView>(R.id.etListFilter).setText("L1")
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        waitForDetailsLoaded(activity)

        ShadowSystemClock.advanceBy(Duration.ofMillis(300))

        activity.findViewById<AutoCompleteTextView>(R.id.etDetailFilter).setText("123.4567")
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val itemDialog = ShadowDialog.getLatestDialog()
        assertNotNull(itemDialog)
        assertTrue(itemDialog.isShowing)

        itemDialog.findViewById<View>(R.id.btnChangeArticle).performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val changeDialog = ShadowDialog.getLatestDialog() as AlertDialog
        clearMocks(UiLoadingHelper, answers = false, recordedCalls = true)
        activity.onBarcodeScanned("400123456789")
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        changeDialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        verify {
            UiLoadingHelper.showError(
                activity,
                "Ersatzartikel muss das Format ddd.dddd haben (z.B. 123.4567)"
            )
        }
    }

    @Test
    fun pickBooking_changeArticleDialog_rejectsInvalidReplacementFormat() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), PickListActivity::class.java)
        intent.putExtra("USERNAME", "tester")
        val activity = Robolectric.buildActivity(PickListActivity::class.java, intent)
            .create().start().resume().get()

        Shadows.shadowOf(Looper.getMainLooper()).idle()

        activity.findViewById<AutoCompleteTextView>(R.id.etListFilter).setText("L1")
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        waitForDetailsLoaded(activity)

        ShadowSystemClock.advanceBy(Duration.ofMillis(300))

        activity.findViewById<AutoCompleteTextView>(R.id.etDetailFilter).setText("123.4567")
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val itemDialog = ShadowDialog.getLatestDialog()
        assertNotNull(itemDialog)
        assertTrue(itemDialog.isShowing)

        itemDialog.findViewById<View>(R.id.btnChangeArticle).performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val changeDialog = ShadowDialog.getLatestDialog() as AlertDialog
        val replacementInput = requireNotNull(findEditText(changeDialog.window!!.decorView))
        replacementInput.setText("1234")

        changeDialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        verify {
            UiLoadingHelper.showError(
                activity,
                "Ersatzartikel muss das Format ddd.dddd haben (z.B. 123.4567)"
            )
        }
    }

    @Test
    fun pickBooking_changeArticleDialog_allowsEmptyReplacementToClearExistingReplacement() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), PickListActivity::class.java)
        intent.putExtra("USERNAME", "tester")
        val activity = Robolectric.buildActivity(PickListActivity::class.java, intent)
            .create().start().resume().get()

        Shadows.shadowOf(Looper.getMainLooper()).idle()

        activity.findViewById<AutoCompleteTextView>(R.id.etListFilter).setText("L1")
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        waitForDetailsLoaded(activity)

        ShadowSystemClock.advanceBy(Duration.ofMillis(300))

        val etDetailFilter = activity.findViewById<AutoCompleteTextView>(R.id.etDetailFilter)
        etDetailFilter.setText("123.4567")
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val itemDialog = ShadowDialog.getLatestDialog()
        assertNotNull(itemDialog)
        assertTrue(itemDialog.isShowing)

        itemDialog.findViewById<View>(R.id.btnChangeArticle).performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val changeDialog = ShadowDialog.getLatestDialog() as AlertDialog
        activity.onBarcodeScanned("4001234567892")
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        changeDialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        itemDialog.findViewById<View>(R.id.btnChangeArticle).performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val clearedChangeDialog = ShadowDialog.getLatestDialog() as AlertDialog
        val replacementInput = requireNotNull(findEditText(clearedChangeDialog.window!!.decorView))
        replacementInput.setText("")

        clearedChangeDialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        clearMocks(TcpClient, answers = false, recordedCalls = true)

        itemDialog.findViewById<View>(R.id.btnYes).performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        verify(timeout = 2000) {
            TcpClient.sendCommand(
                context = any(),
                settings = any(),
                command = "SetBuchung",
                request = match<String> { it.contains("123.4567||-3|") },
                endTag = "{/SetBuchung}"
            )
        }
    }

    @Test
    fun pickBooking_blocksSnPflichtArtikelWithoutSerials() {
        DataRepository.artikelListe = listOf(
            Artikel(
                artNr = "123.4567",
                bez = "Artikel",
                lagerorteW1 = listOf("A", "B", "C"),
                lagerorteW2 = listOf("D", "E", "F"),
                masseinheit = "ST",
                bestand = "1",
                empfBestMenge = 0,
                bestellTrigger = 0,
                mindestbestand = 0,
                grossInfo = "Info",
                liefBestNr = "LIEF1",
                snPflicht = true,
                EAN = "4000000000001",
                suchZusatz = "SUCH1",
                bestellt3M = 0,
                bestellt6M = 0
            )
        )

        val intent = Intent(ApplicationProvider.getApplicationContext(), PickListActivity::class.java)
        intent.putExtra("USERNAME", "tester")
        val activity = Robolectric.buildActivity(PickListActivity::class.java, intent)
            .create().start().resume().get()

        Shadows.shadowOf(Looper.getMainLooper()).idle()

        activity.findViewById<AutoCompleteTextView>(R.id.etListFilter).setText("L1")
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        waitForDetailsLoaded(activity)

        ShadowSystemClock.advanceBy(Duration.ofMillis(300))

        clearMocks(TcpClient, UiLoadingHelper, answers = false, recordedCalls = true)

        activity.findViewById<AutoCompleteTextView>(R.id.etDetailFilter).setText("123.4567")
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val dialog = ShadowDialog.getLatestDialog()
        assertNotNull(dialog)
        assertTrue(dialog.isShowing)

        dialog.findViewById<View>(R.id.btnYes).performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        verify {
            UiLoadingHelper.showError(activity, "Artikel ist SN-pflichtig – bitte Seriennummern erfassen")
        }
        verify(exactly = 0) {
            TcpClient.sendCommand(
                context = any(),
                settings = any(),
                command = "SetBuchung",
                request = any(),
                endTag = "{/SetBuchung}"
            )
        }
    }

    @Test
    fun pickBooking_blocksSnPflichtReplacementArtikelWithoutSerials() {
        DataRepository.artikelListe = listOf(
            Artikel(
                artNr = "123.4567",
                bez = "Artikel",
                lagerorteW1 = listOf("A", "B", "C"),
                lagerorteW2 = listOf("D", "E", "F"),
                masseinheit = "ST",
                bestand = "1",
                empfBestMenge = 0,
                bestellTrigger = 0,
                mindestbestand = 0,
                grossInfo = "Info",
                liefBestNr = "LIEF1",
                snPflicht = false,
                EAN = "4000000000001",
                suchZusatz = "SUCH1",
                bestellt3M = 0,
                bestellt6M = 0
            ),
            Artikel(
                artNr = "765.4321",
                bez = "Ersatzartikel",
                lagerorteW1 = listOf("A", "B", "C"),
                lagerorteW2 = listOf("D", "E", "F"),
                masseinheit = "ST",
                bestand = "1",
                empfBestMenge = 0,
                bestellTrigger = 0,
                mindestbestand = 0,
                grossInfo = "Info",
                liefBestNr = "LIEF2",
                snPflicht = true,
                EAN = "4001234567892",
                suchZusatz = "SUCH2",
                bestellt3M = 0,
                bestellt6M = 0
            )
        )

        val intent = Intent(ApplicationProvider.getApplicationContext(), PickListActivity::class.java)
        intent.putExtra("USERNAME", "tester")
        val activity = Robolectric.buildActivity(PickListActivity::class.java, intent)
            .create().start().resume().get()

        Shadows.shadowOf(Looper.getMainLooper()).idle()

        activity.findViewById<AutoCompleteTextView>(R.id.etListFilter).setText("L1")
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        waitForDetailsLoaded(activity)

        ShadowSystemClock.advanceBy(Duration.ofMillis(300))

        activity.findViewById<AutoCompleteTextView>(R.id.etDetailFilter).setText("123.4567")
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val itemDialog = ShadowDialog.getLatestDialog()
        assertNotNull(itemDialog)
        assertTrue(itemDialog.isShowing)

        itemDialog.findViewById<View>(R.id.btnChangeArticle).performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val changeDialog = ShadowDialog.getLatestDialog() as AlertDialog
        activity.onBarcodeScanned("4001234567892")
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        changeDialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        clearMocks(TcpClient, UiLoadingHelper, answers = false, recordedCalls = true)

        itemDialog.findViewById<View>(R.id.btnYes).performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        verify {
            UiLoadingHelper.showError(activity, "Artikel ist SN-pflichtig – bitte Seriennummern erfassen")
        }
        verify(exactly = 0) {
            TcpClient.sendCommand(
                context = any(),
                settings = any(),
                command = "SetBuchung",
                request = any(),
                endTag = "{/SetBuchung}"
            )
        }
    }

    @Test
    fun dropActivity_loadsOverviewList_successfully() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), DropListActivity::class.java)
        intent.putExtra("USERNAME", "tester")
        Robolectric.buildActivity(DropListActivity::class.java, intent)
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
    fun dispatchTouchEvent_resetsInactivityTimer() {
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

    @Test
    fun detailView_displaysArtikelDataFromFillDetailsWithArtikelData() {
        val intent = Intent(
            ApplicationProvider.getApplicationContext(),
            PickListActivity::class.java
        )
        intent.putExtra("USERNAME", "tester")

        val activity = Robolectric.buildActivity(
            PickListActivity::class.java,
            intent
        ).create().start().resume().get()

        Shadows.shadowOf(Looper.getMainLooper()).idle()

        activity.findViewById<AutoCompleteTextView>(R.id.etListFilter)
            .setText("L1")

        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val rvDetails = waitForDetailsLoaded(activity)

        var text = ""

        repeat(30) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(100)

            val adapter = rvDetails.adapter ?: return@repeat
            if (adapter.itemCount == 0) return@repeat

            val holder = adapter.createViewHolder(rvDetails, 0)
            adapter.onBindViewHolder(holder, 0)

            text = holder.itemView
                .findViewById<TextView>(android.R.id.text1)
                .text
                .toString()

            if (text.contains("Bezeichnung: Artikel")) {
                return@repeat
            }
        }

        assertEquals(
            """
        LagerOrt: A, B, C
        Groß-Info: G
        Artikelnummer: 123.4567
        To pick: 3
        Bezeichnung: Artikel
        """.trimIndent(),
            text
        )
    }
}
