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

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import java.io.File

class RustNativeInterface {

    //FIXME: we can get sharedPref from context: val sharedPref = context.getSharedPreferences(context.getString(R.string.shared_preferences), Context.MODE_PRIVATE)
    private fun connectCore(cameraName: String, sharedPref: SharedPreferences, context: Context, firstTime: Boolean): Boolean {
        var serverIP = sharedPref.getString(context.getString(R.string.saved_ip), "Error")
        if (serverIP == null || serverIP == "Error") {
            Log.e(context.getString(R.string.app_name), "Error: Failed to retrieve the server IP address")
            return false
        }

        var fcmToken = sharedPref.getString(context.getString(R.string.fcm_token), "Error")
        if (fcmToken == null || fcmToken == "Error") {
            Log.e(context.getString(R.string.app_name), "Error: Failed to retrieve the FCM token")
            return false
        }

        var userCredentialsString = sharedPref.getString(context.getString(R.string.user_credentials), "Error")
        if (userCredentialsString == null || userCredentialsString == "Error") {
            Log.e(context.getString(R.string.app_name), "Error: Failed to retrieve user credentials")
            return false
        }

        var userCredentials = Base64.decode(userCredentialsString, Base64.DEFAULT)

        var filesDir = context.getFilesDir().toString() + "/camera_dir_" + cameraName

        // Create directory if it doesn't exist
        val dir = File(filesDir)
        if (!dir.exists()) {
            val success = dir.mkdirs()
            if (!success) {
                Log.e(context.getString(R.string.app_name), context.getString(R.string.error_create_dir))
            }
        }
        
        return RustNative().initialize(serverIP, fcmToken, filesDir, cameraName, firstTime, userCredentials)
    }

    private fun connect(cameraName: String, sharedPref: SharedPreferences, context: Context): Boolean {
        // FIXME: There's a potential race here for determining the firstTimeConnectionDone.
        // For example, if there's a push notification to retrieve videos when we want to pair
        // a camera, we might end up with two initializations with firstTime=true.
        var firstTimeConnectionDone =
            sharedPref.getBoolean(context.getString(R.string.first_time_connection_done) + "_" + cameraName, false)

        if (!firstTimeConnectionDone) {
            val success = RustNativeInterface().connectCore(cameraName, sharedPref, context, true)
            
            //FIXME: the goal here was to inform the user that we couldn't connect.
            //But this crashed on the very first call by updateToken at install time.
            //In general, we need a better mechanism to let the user know that the IP address
            //they set is wrong.
            /*
            else {
                Toast.makeText(
                    context,
                    context.getString(R.string.not_connected_server),
                    Toast.LENGTH_LONG
                ).show()

            }
             */

            return success
        } else {
            return connectCore(cameraName, sharedPref, context, false)
        }
    }

    fun deregister(cameraName: String, sharedPref: SharedPreferences, context: Context): Boolean {
        if (!connect(cameraName, sharedPref, context)) {
            return false
        }

        RustNative().deregister(cameraName)

        with(sharedPref.edit()) {
            putBoolean(context.getString(R.string.first_time_connection_done), false)
            apply()
        }

        return true
    }

    fun updateToken(cameraName: String, token: String, sharedPref: SharedPreferences, context: Context): Boolean {
        if (!connect(cameraName, sharedPref, context)) {
            return false
        }

        return RustNative().updateToken(token, cameraName)
    }

    fun addCamera(cameraName: String, cameraIP: String, cameraSecret: ByteArray,
                  sharedPref: SharedPreferences, context: Context): Boolean {
        if (!connect(cameraName, sharedPref, context)) {
            return false
        }

        return RustNative().addCamera(cameraName, cameraIP, cameraSecret)
    }

    fun receive(cameraName: String, sharedPref: SharedPreferences, context: Context): String {
        if (!connect(cameraName, sharedPref, context)) {
            return "Error"
        }

        return RustNative().receive(cameraName)
    }

    fun decode(cameraName: String, msg: ByteArray, sharedPref: SharedPreferences, context: Context): String {
        //FIXME: we need to initialize, but don't need to connect to the server.
        if (!connect(cameraName, sharedPref, context)) {
            return "Error"
        }

        return RustNative().decode(cameraName, msg)
    }

    fun livestreamStart(cameraName: String, sharedPref: SharedPreferences, context: Context): Boolean {
        if (!connect(cameraName, sharedPref, context)) {
            return false
        }

        return RustNative().livestreamStart(cameraName)
    }

    fun livestreamStartNoConnect(cameraName: String): Boolean {
        return RustNative().livestreamStart(cameraName)
    }

    fun livestreamEnd(cameraName: String, sharedPref: SharedPreferences, context: Context): Boolean {
        if (!connect(cameraName, sharedPref, context)) {
            return false
        }

        return RustNative().livestreamEnd(cameraName)
    }

    fun livestreamEndNoConnect(cameraName: String): Boolean {
        return RustNative().livestreamEnd(cameraName)
    }

    fun livestreamReadNoConnect(cameraName: String, len: Int): ByteArray {
        return RustNative().livestreamRead(cameraName, len)
    }
}