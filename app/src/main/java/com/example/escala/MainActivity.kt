package com.example.escala

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val reqCapture = 1001
    private val reqNotif = 1002

    private var mode = 2        // 0 bilinear, 1 bicúbico, 2 bicúbico + nitidez
    private var scalePct = 67   // % da resolução da tela usada na captura
    private var mini = true     // true = miniatura de teste

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = "Escala – esqueleto\nCaptura a tela, aplica upscaling na GPU e mostra num overlay."
            textSize = 16f
        })
        addGroup(root, "Filtro", listOf("Bilinear (referência)", "Bicúbico", "Bicúbico + nitidez"), mode) { mode = it }
        addGroup(root, "Resolução da captura", listOf("50%", "67%", "75%", "100%"), 1) {
            scalePct = intArrayOf(50, 67, 75, 100)[it]
        }
        addGroup(root, "Overlay", listOf("Miniatura (teste)", "Tela cheia"), 0) { mini = it == 0 }
        root.addView(button("1. Permitir sobreposição de tela") { openOverlaySettings() })
        root.addView(button("2. Iniciar") { start() })
        root.addView(button("Parar") { stopService(Intent(this, CaptureService::class.java)) })
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
    }

    private fun addGroup(
        parent: LinearLayout, title: String, options: List<String>, checked: Int, onPick: (Int) -> Unit
    ) {
        parent.addView(TextView(this).apply {
            text = title
            textSize = 14f
            setPadding(0, 32, 0, 0)
        })
        val group = RadioGroup(this)
        options.forEach { label ->
            group.addView(RadioButton(this).apply {
                text = label
                id = View.generateViewId()
            })
        }
        (group.getChildAt(checked) as RadioButton).isChecked = true
        group.setOnCheckedChangeListener { g, id -> onPick(g.indexOfChild(g.findViewById(id))) }
        parent.addView(group)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun openOverlaySettings() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun start() {
        if (!Settings.canDrawOverlays(this)) {
            toast("Permita a sobreposição de tela primeiro (botão 1).")
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), reqNotif)
            toast("Autorize as notificações e toque em Iniciar de novo.")
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mpm.createScreenCaptureIntent(), reqCapture)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != reqCapture) return
        if (resultCode == RESULT_OK && data != null) {
            val svc = Intent(this, CaptureService::class.java)
                .putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(CaptureService.EXTRA_DATA, data)
                .putExtra(CaptureService.EXTRA_MODE, mode)
                .putExtra(CaptureService.EXTRA_SCALE, scalePct)
                .putExtra(CaptureService.EXTRA_MINI, mini)
            startForegroundService(svc)
            moveTaskToBack(true)
        } else {
            toast("Captura de tela não autorizada.")
        }
    }
}
