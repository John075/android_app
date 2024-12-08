package privastead.camera

/*
 * Copyright (C) 2024  Ardalan Amiri Sani
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

/*
 * This file uses and modifies some code from:
 * https://github.com/firebase/quickstart-android/
 * Apache License, Version 2.0
 */

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.util.Base64
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import privastead.camera.PrivasteadCameraApplication
import privastead.camera.R
import java.text.SimpleDateFormat
import java.util.Date

class PushNotificationService : FirebaseMessagingService() {
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        val wakeLock: PowerManager.WakeLock =
            (applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager).run {
                newWakeLock(PowerManager.FULL_WAKE_LOCK, "Privastead::onMessageReceived").apply {
                    acquire()
                }
            }

        remoteMessage.data?.let {
            val encodedByteArray = it["body"]

            if (encodedByteArray != null) {
                val byteArray = Base64.decode(encodedByteArray, Base64.DEFAULT)
                val sharedPref = getSharedPreferences(getString(R.string.shared_preferences), Context.MODE_PRIVATE)
                val response = RustNativeInterface().decode(byteArray, sharedPref, applicationContext)

                if (response == "None") {
                    // In this case, the camera just sent a notification for us to start downloading.
                    // Note: it is important to start the foreground service after we have decrypted
                    // the FCM payload. Otherwise, we might process an MLS update, which would make
                    // it impossible to decrypt the FCM payload.

                    val workRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
                        .setConstraints(
                            Constraints.Builder()
                                .setRequiredNetworkType(NetworkType.CONNECTED)
                                .build()
                        )
                        .build()

                    WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                        "DownloadTask", // Unique name
                        ExistingWorkPolicy.KEEP, // Prevent duplicate work
                        workRequest
                    )
                } else if (response != "Error") {
                    var needNotification = sharedPref?.getBoolean(applicationContext.getString(R.string.saved_need_notification_state), true)

                    if (needNotification == true) {
                        //FIXME: does this need to be called every time?
                        createNotificationChannel()
                        val cameraName = response.split("_").toTypedArray().get(0)
                        val timestamp = response.split("_").toTypedArray().get(1)
                        sendNotification(cameraName, timestamp)
                        addPendingToRepository(cameraName, timestamp)
                    }
                }
            }
        }

        wakeLock.release()
    }

    private fun addPendingToRepository(cameraName: String, timestamp: String) {
        val repository = (applicationContext as PrivasteadCameraApplication).repository
        val videoName = "video_" + cameraName + "_" + timestamp + ".mp4"
        val video = Video(cameraName, videoName, false, true)
        repository.insertVideo(video)
    }

    //FIXME: same as code in VideoListAdapter
    private fun convertTimeToDate(timeInMillis: Long): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
        val date = Date(timeInMillis)
        return dateFormat.format(date)
    }

    private fun sendNotification(cameraName: String, timestamp: String) {
        val intent =
            Intent(
                applicationContext,
                VideoListActivity::class.java
            ).apply {
                flags =
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
        val bundle = Bundle()
        bundle.putString("camera", cameraName)
        intent.putExtras(bundle)

        val pendingIntent: PendingIntent = PendingIntent.getActivity(
            applicationContext,
            System.currentTimeMillis().toInt(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT
        )

        var timestamp_long: Long? = timestamp?.toLongOrNull()
        val date = timestamp_long?.let { convertTimeToDate(it * 1000L) }

        var builder = applicationContext?.let {
            NotificationCompat.Builder(
                it,
                applicationContext.getString(R.string.channel_id)
            )
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(cameraName)
                .setContentText("Motion at $date")
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setVibrate(longArrayOf(500, 500, 500, 500, 500))
                .setLights(Color.GREEN, 2000, 2000)
        }

        if (builder != null) {
            with(applicationContext?.let {
                NotificationManagerCompat.from(
                    it
                )
            }) {
                if (applicationContext?.let {
                        ActivityCompat.checkSelfPermission(
                            it,
                            Manifest.permission.POST_NOTIFICATIONS
                        )
                    } != PackageManager.PERMISSION_GRANTED
                ) {
                    return
                }
                this?.notify(
                    System.currentTimeMillis().toInt(),
                    builder.build()
                )
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = applicationContext.getString(R.string.channel_name)
            val descriptionText = applicationContext.getString(R.string.channel_description)
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(applicationContext.getString(R.string.channel_id), name, importance).apply {
                description = descriptionText
            }
            // Register the channel with the system.
            val notificationManager: NotificationManager =
                applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onNewToken(token: String) {
        // FIXME: should we offload this to the WorkManager as well?
        val wakeLock: PowerManager.WakeLock =
            (getSystemService(Context.POWER_SERVICE) as PowerManager).run {
                newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Privastead::onNewToken").apply {
                    acquire()
                }
            }

        val sharedPref = getSharedPreferences(getString(R.string.shared_preferences), Context.MODE_PRIVATE)

        // First, we'll try to update here
        if (RustNativeInterface().updateToken(token, sharedPref, applicationContext)) {
            wakeLock.release()
            return
        }

        // We failed to update. Let's save it here and have it be updated on next launch.
        with(sharedPref.edit()) {
            putString(getString(R.string.fcm_token), token)
            putBoolean(getString(R.string.need_update_fcm_token), true)
            apply()
        }

        wakeLock.release()
    }
}