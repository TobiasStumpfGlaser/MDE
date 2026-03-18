package com.example.mde

import android.app.Activity
import android.app.AlertDialog
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.view.Gravity
import android.view.View
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.content.Context
import android.media.MediaPlayer
import android.widget.*
import kotlinx.coroutines.*
import java.util.*

object UiLoadingHelper {

    private var loadingDialog: AlertDialog? = null
    private var loadingText: TextView? = null
    private var iconView: View? = null
    private var autoHideJob: Job? = null

    fun show(
        activity: Activity,
        message: String = "Kommunikation mit Server...",
        status: LoadingStatus = LoadingStatus.LOADING
    ) {

        autoHideJob?.cancel()  // vorherige auto-hide stoppen

        // Icon erstellen
        iconView = when (status) {
            LoadingStatus.LOADING -> ProgressBar(activity)
            LoadingStatus.SUCCESS -> TextView(activity).apply {
                text = "✅"
                textSize = 48f
                gravity = Gravity.CENTER
            }
            LoadingStatus.ERROR -> {
                playErrorSound(activity)
                TextView(activity).apply {
                    text = "❌"
                    textSize = 48f
                    gravity = Gravity.CENTER
                }
            }
        }

        // TextView
        val text = TextView(activity).apply {
            this.text = message
            setPadding(20, 20, 20, 20)
            gravity = Gravity.CENTER
            textAlignment = TextView.TEXT_ALIGNMENT_CENTER
        }
        loadingText = text

        // Layout
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
            gravity = Gravity.CENTER
            addView(iconView)
            addView(text)
        }

        val builder = AlertDialog.Builder(activity)
            .setView(layout)

        builder.setPositiveButton("OK", null)

        when (status) {
            LoadingStatus.LOADING -> {
                builder.setCancelable(false)
            }

            LoadingStatus.SUCCESS -> {
                builder.setCancelable(false)
            }

            LoadingStatus.ERROR -> {
                builder.setCancelable(true)
                builder.setPositiveButton("OK") { dialog, _ ->
                    dialog.dismiss()
                    hide()
                }
            }
        }

        loadingDialog = builder.create()
        loadingDialog?.show()
        loadingDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.visibility = View.GONE

        // Auto-hide nur bei SUCCESS oder ERROR
        if (status == LoadingStatus.SUCCESS) {
            autoHideJob = CoroutineScope(Dispatchers.Main).launch {
                delay(2000)  // 2 Sekunden sichtbar
                hide()
            }
        }
    }

    fun showError(activity: Activity, message: String) {
        autoHideJob?.cancel()
        playErrorSound(activity)

        // Icon erstellen
        iconView = TextView(activity).apply {
            text = "❌"
            textSize = 48f
            gravity = Gravity.CENTER
        }

        // TextView
        val text = TextView(activity).apply {
            this.text = message
            setPadding(20, 20, 20, 20)
            gravity = Gravity.CENTER
            textAlignment = TextView.TEXT_ALIGNMENT_CENTER
        }
        loadingText = text

        // Layout
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
            gravity = Gravity.CENTER
            addView(iconView)
            addView(text)
        }

        val builder = AlertDialog.Builder(activity)
            .setView(layout)
            .setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()
                hide()
            }

        loadingDialog = builder.create()
        loadingDialog?.setCancelable(true)
        loadingDialog?.show()
    }

    fun update(
        activity: Activity,
        message: String,
        status: LoadingStatus
    ) {
        autoHideJob?.cancel()

        val btn = loadingDialog?.getButton(AlertDialog.BUTTON_POSITIVE)
        // Button je nach Status steuern
        when (status) {
            LoadingStatus.ERROR -> {
                playErrorSound(activity)
                btn?.visibility = View.VISIBLE
                btn?.setOnClickListener {
                    hide()
                }
            }

            else -> {
                btn?.visibility = View.GONE
            }
        }

        // Neues Icon erstellen
        val newIcon = when (status) {
            LoadingStatus.LOADING -> ProgressBar(activity)
            LoadingStatus.SUCCESS -> TextView(activity).apply {
                text = "✅"
                textSize = 48f
                gravity = Gravity.CENTER
            }
            LoadingStatus.ERROR -> TextView(activity).apply {
                text = "❌"
                textSize = 48f
                gravity = Gravity.CENTER
            }
        }

        // Icon ersetzen
        val parent = iconView?.parent as? LinearLayout
        parent?.let {
            it.removeView(iconView)
            it.addView(newIcon, 0)
        }
        iconView = newIcon

        // Text ändern
        loadingText?.text = message

        // Verhalten anpassen
        when (status) {
            LoadingStatus.LOADING -> {
                loadingDialog?.setCancelable(false)
            }

            LoadingStatus.SUCCESS -> {
                loadingDialog?.setCancelable(false)

                autoHideJob = CoroutineScope(Dispatchers.Main).launch {
                    delay(2000)
                    hide()
                }
            }

            LoadingStatus.ERROR -> {
                loadingDialog?.setCancelable(false)

                loadingDialog?.setButton(AlertDialog.BUTTON_POSITIVE, "OK") { dialog, _ ->
                    dialog.dismiss()
                    hide()
                }
            }
        }
    }

    fun hide() {
        autoHideJob?.cancel()
        loadingDialog?.dismiss()
        loadingDialog = null
    }

    fun playErrorSound(context: Context) {
        val mp = MediaPlayer.create(context, R.raw.error)
        mp.start()
        mp.setOnCompletionListener { it.release() }
    }

    enum class LoadingStatus { LOADING, SUCCESS, ERROR }
}