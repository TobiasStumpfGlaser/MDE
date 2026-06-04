package com.example.mde

import android.app.AlertDialog
import android.content.Intent
import android.os.Looper
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

/**
 * Tests für BasePickDropActivity.
 * Diese Tests nutzen die öffentlichen Funktionen und UI-Interaktionen der Activity.
 */
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

    @Test
    fun pickActivity_loadsOverviewList_successfully() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), PickListActivity::class.java)
        intent.putExtra("USERNAME", "tester")
        val activity = Robolectric.buildActivity(PickListActivity::class.java, intent)
            .create().start().resume().get()

        Shadows.shadowOf(Looper.getMainLooper()).idle()

        // Verify TcpClient was called to load overview
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

        // Get the list filter EditText
        val etListFilter = activity.findViewById<AutoCompleteTextView>(R.id.etListFilter)
        val etProjectNumber = activity.findViewById<EditText>(R.id.etProjectNumber)

        // Simulate user entering list number
        etListFilter.setText("L1")
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        // Verify project number was set
        assertEquals("P100", etProjectNumber.text.toString())

        // Verify details were loaded
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

        // Load list first
        val etListFilter = activity.findViewById<AutoCompleteTextView>(R.id.etListFilter)
        etListFilter.setText("L1")
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        // Wait for details to be loaded
        verify(timeout = 2000) {
            TcpClient.sendCommand(
                context = any(),
                settings = any(),
                command = "GetPick_L1",
                request = "{GetPick_L1}",
                endTag = "{/GetPick_L1}"
            )
        }

        // Ensure details view is visible
        val rvDetails = activity.findViewById<RecyclerView>(R.id.rvDetails)
        assertEquals("Details view should be visible", View.VISIBLE, rvDetails.visibility)

        clearMocks(TcpClient, answers = false, recordedCalls = true)

        // Simulate barcode scan for article number that exists in the loaded details
        activity.onBarcodeScanned("123.4567")
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        // Verify dialog was shown
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertNotNull("Dialog should be created after scanning article that exists in details", dialog)
    }

    @Test
    fun pickActivity_clicksDetailItem_opensDialog() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), PickListActivity::class.java)
        intent.putExtra("USERNAME", "tester")
        val activity = Robolectric.buildActivity(PickListActivity::class.java, intent)
            .create().start().resume().get()

        Shadows.shadowOf(Looper.getMainLooper()).idle()

        // Load details
        val etListFilter = activity.findViewById<AutoCompleteTextView>(R.id.etListFilter)
        etListFilter.setText("L1")
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        // Wait for details to be loaded
        verify(timeout = 2000) {
            TcpClient.sendCommand(
                context = any(),
                settings = any(),
                command = "GetPick_L1",
                request = "{GetPick_L1}",
                endTag = "{/GetPick_L1}"
            )
        }

        // Get the details RecyclerView
        val rvDetails = activity.findViewById<RecyclerView>(R.id.rvDetails)
        assertNotNull("Details RecyclerView should exist", rvDetails)

        // Click first item in the list
        val adapter = rvDetails.adapter
        if (adapter != null && adapter.itemCount > 0) {
            val holder = adapter.createViewHolder(rvDetails, 0)
            adapter.onBindViewHolder(holder, 0)
            holder.itemView.performClick()
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            // Verify dialog was shown
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertNotNull("Dialog should be created after clicking detail item", dialog)
        }
    }

    @Test
    fun pickBooking_confirmButton_sendsNegativeAmount() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), PickListActivity::class.java)
        intent.putExtra("USERNAME", "tester")
        val activity = Robolectric.buildActivity(PickListActivity::class.java, intent)
            .create().start().resume().get()

        Shadows.shadowOf(Looper.getMainLooper()).idle()

        // Load details
        val etListFilter = activity.findViewById<AutoCompleteTextView>(R.id.etListFilter)
        etListFilter.setText("L1")
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        // Wait for details to be loaded
        verify(timeout = 2000) {
            TcpClient.sendCommand(
                context = any(),
                settings = any(),
                command = "GetPick_L1",
                request = "{GetPick_L1}",
                endTag = "{/GetPick_L1}"
            )
        }

        clearMocks(TcpClient, answers = false, recordedCalls = true)

        // Simulate barcode scan
        activity.onBarcodeScanned("123.4567")
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertNotNull("Dialog should be created", dialog)

        // Click positive button (Yes)
        (dialog as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        // Verify booking was sent with negative amount (pick)
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

        // Load details
        val etListFilter = activity.findViewById<AutoCompleteTextView>(R.id.etListFilter)
        etListFilter.setText("L1")
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        // Wait for details to be loaded
        verify(timeout = 2000) {
            TcpClient.sendCommand(
                context = any(),
                settings = any(),
                command = "GetDrop_L1",
                request = "{GetDrop_L1}",
                endTag = "{/GetDrop_L1}"
            )
        }

        clearMocks(TcpClient, answers = false, recordedCalls = true)

        // Simulate barcode scan
        activity.onBarcodeScanned("123.4567")
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertNotNull("Dialog should be created", dialog)

        // Click positive button (Yes)
        (dialog as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        // Verify booking was sent with positive amount (drop)
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

        // Verify TcpClient was called to load overview
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
}
