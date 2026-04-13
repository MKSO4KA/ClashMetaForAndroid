package com.github.kr328.clash

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.common.util.setUUID
import com.github.kr328.clash.common.util.ticker
import com.github.kr328.clash.design.ProfilesDesign
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.util.withProfile
import com.github.kr328.clash.service.util.NearbyManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import io.github.g00fy2.quickie.QRResult
import io.github.g00fy2.quickie.ScanQRCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.util.*
import java.util.concurrent.TimeUnit
import com.github.kr328.clash.design.R

class ProfilesActivity : BaseActivity<ProfilesDesign>() {

    // --- ЛОГИКА РАЗРЕШЕНИЙ (PERMISSIONS) ---
    private var pendingNearbyAction: (() -> Unit)? = null

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions.entries.all { it.value }) {
            pendingNearbyAction?.invoke()
        } else {
            android.widget.Toast.makeText(this, "Требуются разрешения для передачи (Локация/Bluetooth)", android.widget.Toast.LENGTH_LONG).show()
        }
        pendingNearbyAction = null
    }

    private fun checkAndRunNearby(action: () -> Unit) {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        if (!locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Включите локацию")
                .setMessage("Для работы Mesh Bridge необходимо включить GPS в шторке (требование Android).")
                .setPositiveButton("Настройки") { _, _ -> startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }

        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.addAll(listOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }

        pendingNearbyAction = action
        requestPermissionLauncher.launch(permissions.toTypedArray())
    }

    // --- ЛОГИКА QR СКАНЕРА (QUICKIE) ---
    private val scanQrLauncher = registerForActivityResult(ScanQRCode()) { result ->
        if (result is QRResult.QRSuccess) {
            val token = result.content.rawValue ?: return@registerForActivityResult
            showMeshDialog("Прием профиля") { updateUI ->
                NearbyManager.startReceiving(this, token, updateUI)
            }
        } else if (result is QRResult.QRError) {
            android.widget.Toast.makeText(this, "Ошибка камеры: ${result.exception.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun generateQrBitmap(content: String): Bitmap {
        val size = 512
        val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
        return bitmap
    }

    private fun showMeshDialog(title: String, action: (updateText: (String, Boolean) -> Unit) -> Unit) {
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage("Инициализация...")
            .setCancelable(false)
            .setNegativeButton(android.R.string.cancel) { _, _ -> NearbyManager.stopAll(this) }
            .show()

        action { text, isFinished ->
            runOnUiThread {
                dialog.setMessage(text)
                if (isFinished) {
                    dialog.setCancelable(true)
                    dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).text = "Закрыть"
                }
            }
        }
    }

    // --- ОСНОВНОЙ ЦИКЛ ACTIVITY ---
    override suspend fun main() {
        val design = ProfilesDesign(this)
        setContentDesign(design)
        val ticker = ticker(TimeUnit.MINUTES.toMillis(1))

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ActivityStart, Event.ProfileChanged -> design.fetch()
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        ProfilesDesign.Request.Create -> startActivity(NewProfileActivity::class.intent)
                        ProfilesDesign.Request.UpdateAll -> withProfile {
                            try { queryAll().forEach { p -> if (p.imported && p.type != Profile.Type.File) update(p.uuid) } }
                            finally { withContext(Dispatchers.Main) { design.finishUpdateAll() } }
                        }
                        is ProfilesDesign.Request.Update -> withProfile { update(it.profile.uuid) }
                        is ProfilesDesign.Request.Delete -> withProfile { delete(it.profile.uuid) }
                        is ProfilesDesign.Request.Edit -> startActivity(PropertiesActivity::class.intent.setUUID(it.profile.uuid))
                        is ProfilesDesign.Request.Active -> withProfile {
                            if (it.profile.imported) setActive(it.profile) else design.requestSave(it.profile)
                        }
                        is ProfilesDesign.Request.Duplicate -> {
                            val uuid = withProfile { clone(it.profile.uuid) }
                            startActivity(PropertiesActivity::class.intent.setUUID(uuid))
                        }

                        // --- ИНТЕГРАЦИЯ SHARE (Генерация QR и запуск вещания) ---
                        is ProfilesDesign.Request.Share -> {
                            checkAndRunNearby {
                                // Генерируем уникальный токен на 8 символов
                                val token = "CFA_" + UUID.randomUUID().toString().substring(0, 8)
                                val qrBitmap = generateQrBitmap(token)

                                val imageView = ImageView(this@ProfilesActivity).apply {
                                    setImageBitmap(qrBitmap)
                                    setPadding(60, 60, 60, 60)
                                }

                                var isSuccess = false
                                val dialog = MaterialAlertDialogBuilder(this@ProfilesActivity)
                                    .setTitle("Покажите QR другу")
                                    .setView(imageView)
                                    .setMessage("Подготовка эфира...")
                                    .setNegativeButton("Отмена") { _, _ -> NearbyManager.stopAll(this@ProfilesActivity) }
                                    .setOnDismissListener { if (!isSuccess) NearbyManager.stopAll(this@ProfilesActivity) }
                                    .show()

                                NearbyManager.startSharing(this@ProfilesActivity, it.profile.uuid, token) { status, finished ->
                                    runOnUiThread {
                                        dialog.setMessage(status)
                                        if (finished) {
                                            isSuccess = true
                                            dialog.dismiss()
                                            android.widget.Toast.makeText(this@ProfilesActivity, status, android.widget.Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            }
                        }

                        // --- ИНТЕГРАЦИЯ RECEIVE (Запуск камеры Quickie) ---
                        is ProfilesDesign.Request.Receive -> {
                            checkAndRunNearby {
                                // Просто запускаем сканер, результат вернется в scanQrLauncher
                                scanQrLauncher.launch(null)
                            }
                        }
                    }
                }
                if (activityStarted) ticker.onReceive { design.updateElapsed() }
            }
        }
    }

    private suspend fun ProfilesDesign.fetch() {
        withProfile { patchProfiles(queryAll()) }
    }

    override fun onProfileUpdateCompleted(uuid: UUID?) {
        if(uuid == null) return
        launch {
            var name: String? = null
            withProfile { name = queryByUUID(uuid)?.name }
            design?.showToast(getString(R.string.toast_profile_updated_complete, name), ToastDuration.Long)
        }
    }

    override fun onProfileUpdateFailed(uuid: UUID?, reason: String?) {
        if(uuid == null) return
        launch {
            var name: String? = null
            withProfile { name = queryByUUID(uuid)?.name }
            design?.showToast(getString(R.string.toast_profile_updated_failed, name, reason), ToastDuration.Long) {
                setAction(R.string.edit) { startActivity(PropertiesActivity::class.intent.setUUID(uuid)) }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        NearbyManager.stopAll(this)
    }
}