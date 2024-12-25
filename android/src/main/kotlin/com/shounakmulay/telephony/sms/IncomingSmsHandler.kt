package com.shounakmulay.telephony.sms

import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Telephony
import android.telephony.SmsMessage
import android.util.Log
import com.shounakmulay.telephony.utils.Constants
import com.shounakmulay.telephony.utils.Constants.HANDLE
import com.shounakmulay.telephony.utils.Constants.HANDLE_BACKGROUND_MESSAGE
import com.shounakmulay.telephony.utils.Constants.MESSAGE
import com.shounakmulay.telephony.utils.Constants.MESSAGE_BODY
import com.shounakmulay.telephony.utils.Constants.ON_MESSAGE
import com.shounakmulay.telephony.utils.Constants.ORIGINATING_ADDRESS
import com.shounakmulay.telephony.utils.Constants.SERVICE_CENTER_ADDRESS
import com.shounakmulay.telephony.utils.Constants.SHARED_PREFERENCES_NAME
import com.shounakmulay.telephony.utils.Constants.SHARED_PREFS_BACKGROUND_MESSAGE_HANDLE
import com.shounakmulay.telephony.utils.Constants.SHARED_PREFS_BACKGROUND_SETUP_HANDLE
import com.shounakmulay.telephony.utils.Constants.SHARED_PREFS_DISABLE_BACKGROUND_EXE
import com.shounakmulay.telephony.utils.Constants.STATUS
import com.shounakmulay.telephony.utils.Constants.TIMESTAMP
import com.shounakmulay.telephony.utils.SmsAction
import io.flutter.FlutterInjector
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.FlutterJNI
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.embedding.engine.loader.FlutterLoader
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.EventChannel
import io.flutter.view.FlutterCallbackInformation
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.collections.HashMap


class IncomingSmsReceiver : BroadcastReceiver() {

    companion object {
        var foregroundSmsChannel: MethodChannel? = null
    }

    override fun onReceive(context: Context, intent: Intent?) {
        Log.d("IncomingSmsReceiver", "onReceive called")
        ContextHolder.applicationContext = context.applicationContext
        val smsList = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        Log.d("IncomingSmsReceiver", "Received ${smsList.size} SMS messages")
        val messagesGroupedByOriginatingAddress = smsList.groupBy { it.originatingAddress }
        messagesGroupedByOriginatingAddress.forEach { group ->
            processIncomingSms(context, group.value)
        }
    }

    /**
     * Calls [ON_MESSAGE] method on the Foreground Channel if the application is in foreground.
     *
     * If the application is not in the foreground and the background isolate is not running, it initializes the
     * background isolate. The SMS is added to a background queue that will be processed on the isolate is initialized.
     *
     * If the application is not in the foreground but the the background isolate is running, it calls the
     * [IncomingSmsHandler.executeDartCallbackInBackgroundIsolate] with the SMS.
     *
     */
    private fun processIncomingSms(context: Context, smsList: List<SmsMessage>) {
        Log.d("IncomingSmsReceiver", "processIncomingSms called")
        val messageMap = smsList.first().toMap()
        smsList.forEachIndexed { index, smsMessage ->
            if (index > 0) {
                messageMap[MESSAGE_BODY] = (messageMap[MESSAGE_BODY] as String)
                    .plus(smsMessage.messageBody.trim())
            }
        }
                // Отправка сообщения в стрим
        IncomingSmsHandler.sendSmsToStream(messageMap)
        Log.d("IncomingSmsReceiver", "Message map created: $messageMap")
        if (IncomingSmsHandler.isApplicationForeground(context)) {
            Log.d("IncomingSmsReceiver", "Application is in foreground")
            val args = HashMap<String, Any>()
            args[MESSAGE] = messageMap
            foregroundSmsChannel?.invokeMethod(ON_MESSAGE, args)
        } else {
            Log.d("IncomingSmsReceiver", "Application is in background")
            val preferences =
                context.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE)
            val disableBackground =
                preferences.getBoolean(SHARED_PREFS_DISABLE_BACKGROUND_EXE, false)
            if (!disableBackground) {
                Log.d("IncomingSmsReceiver", "Processing in background")
                processInBackground(context, messageMap)
            } else {
                Log.d("IncomingSmsReceiver", "Background processing is disabled")
            }
        }

    }

    private fun processInBackground(context: Context, sms: HashMap<String, Any?>) {
        Log.d("IncomingSmsReceiver", "processInBackground called")
        IncomingSmsHandler.apply {
            if (!isIsolateRunning.get()) {
                Log.d("IncomingSmsReceiver", "Isolate is not running, initializing")
                initialize(context)
                val preferences =
                    context.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE)
                val backgroundCallbackHandle =
                    preferences.getLong(SHARED_PREFS_BACKGROUND_SETUP_HANDLE, 0)
                startBackgroundIsolate(context, backgroundCallbackHandle)
                backgroundMessageQueue.add(sms)
                Log.d("IncomingSmsReceiver", "Added SMS to background message queue")
            } else {
                Log.d("IncomingSmsReceiver", "Isolate is running, executing Dart callback")
                executeDartCallbackInBackgroundIsolate(context, sms)
            }
        }
    }
}

/**
 * Convert the [SmsMessage] to a [HashMap]
 */
fun SmsMessage.toMap(): HashMap<String, Any?> {
    val smsMap = HashMap<String, Any?>()
    this.apply {
        smsMap[MESSAGE_BODY] = messageBody
        smsMap[TIMESTAMP] = timestampMillis.toString()
        smsMap[ORIGINATING_ADDRESS] = originatingAddress
        smsMap[STATUS] = status.toString()
        smsMap[SERVICE_CENTER_ADDRESS] = serviceCenterAddress
    }
    Log.d("SmsMessage", "Converted SMS to map: $smsMap")
    return smsMap
}

/**
 * Handle all the background processing on received SMS
 *
 * Call [setBackgroundSetupHandle] and [setBackgroundMessageHandle] before performing any other operations.
 *
 *
 * Will throw [RuntimeException] if [backgroundChannel] was not initialized by calling [startBackgroundIsolate]
 * before calling [executeDartCallbackInBackgroundIsolate]
 */
object IncomingSmsHandler : MethodChannel.MethodCallHandler, EventChannel.StreamHandler {

    internal val backgroundMessageQueue =
        Collections.synchronizedList(mutableListOf<HashMap<String, Any?>>())
    internal var isIsolateRunning = AtomicBoolean(false)

    private var eventSink: EventChannel.EventSink? = null

    private lateinit var backgroundChannel: MethodChannel
    private lateinit var backgroundFlutterEngine: FlutterEngine
    private lateinit var flutterLoader: FlutterLoader

    private var backgroundMessageHandle: Long? = null

    /**
     * Initializes a background flutter execution environment and executes the callback
     * to setup the background [MethodChannel]
     *
     * Also initializes the method channel on the android side
     */
    fun startBackgroundIsolate(context: Context, callbackHandle: Long) {
        Log.d("IncomingSmsHandler", "startBackgroundIsolate called with handle: $callbackHandle")
        val appBundlePath = flutterLoader.findAppBundlePath()
        val flutterCallback = FlutterCallbackInformation.lookupCallbackInformation(callbackHandle)

        val dartEntryPoint =
            DartExecutor.DartCallback(context.assets, appBundlePath, flutterCallback)

        backgroundFlutterEngine = FlutterEngine(context, flutterLoader, FlutterJNI())
        backgroundFlutterEngine.dartExecutor.executeDartCallback(dartEntryPoint)

        backgroundChannel =
            MethodChannel(backgroundFlutterEngine.dartExecutor, Constants.CHANNEL_SMS_BACKGROUND)
        backgroundChannel.setMethodCallHandler(this)
        Log.d("IncomingSmsHandler", "Background isolate started")
    }

    /**
     * Called when the background dart isolate has completed setting up the method channel
     *
     * If any SMS were received during the background isolate was being initialized, it will process
     * all those messages.
     */
    fun onChannelInitialized(applicationContext: Context) {
        Log.d("IncomingSmsHandler", "onChannelInitialized called")
        isIsolateRunning.set(true)
        synchronized(backgroundMessageQueue) {

            // Handle all the messages received before the Dart isolate was
            // initialized, then clear the queue.
            val iterator = backgroundMessageQueue.iterator()
            while (iterator.hasNext()) {
                executeDartCallbackInBackgroundIsolate(applicationContext, iterator.next())
            }
            backgroundMessageQueue.clear()
            Log.d("IncomingSmsHandler", "Processed all queued messages")
        }
    }

    /**
     * Invoke the method on background channel to handle the message
     */
    internal fun executeDartCallbackInBackgroundIsolate(
        context: Context,
        message: HashMap<String, Any?>
    ) {
        Log.d("IncomingSmsHandler", "executeDartCallbackInBackgroundIsolate called with message: $message")
        if (!this::backgroundChannel.isInitialized) {
            throw RuntimeException(
                "setBackgroundChannel was not called before messages came in, exiting."
            )
        }

        val args: MutableMap<String, Any?> = HashMap()
        if (backgroundMessageHandle == null) {
            backgroundMessageHandle = getBackgroundMessageHandle(context)
        }
        args[HANDLE] = backgroundMessageHandle
        args[MESSAGE] = message
        backgroundChannel.invokeMethod(HANDLE_BACKGROUND_MESSAGE, args)
        Log.d("IncomingSmsHandler", "Dart callback executed")
    }

    /**
     * Gets an instance of FlutterLoader from the FlutterInjector, starts initialization and
     * waits until initialization is complete.
     *
     * Should be called before invoking any other background methods.
     */
    internal fun initialize(context: Context) {
        Log.d("IncomingSmsHandler", "initialize called")
        val flutterInjector = FlutterInjector.instance()
        flutterLoader = flutterInjector.flutterLoader()
        flutterLoader.startInitialization(context)
        flutterLoader.ensureInitializationComplete(context.applicationContext, null)
        Log.d("IncomingSmsHandler", "FlutterLoader initialized")
    }

    fun setBackgroundMessageHandle(context: Context, handle: Long) {
        Log.d("IncomingSmsHandler", "setBackgroundMessageHandle called with handle: $handle")
        backgroundMessageHandle = handle

        // Store background message handle in shared preferences so it can be retrieved
        // by other application instances.
        val preferences =
            context.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE)
        preferences.edit().putLong(SHARED_PREFS_BACKGROUND_MESSAGE_HANDLE, handle).apply()

    }

    fun setBackgroundSetupHandle(context: Context, setupBackgroundHandle: Long) {
        Log.d("IncomingSmsHandler", "setBackgroundSetupHandle called with handle: $setupBackgroundHandle")
        // Store background setup handle in shared preferences so it can be retrieved
        // by other application instances.
        val preferences =
            context.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE)
        preferences.edit().putLong(SHARED_PREFS_BACKGROUND_SETUP_HANDLE, setupBackgroundHandle)
            .apply()
    }

    private fun getBackgroundMessageHandle(context: Context): Long {
        val handle = context
            .getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getLong(SHARED_PREFS_BACKGROUND_MESSAGE_HANDLE, 0)
        Log.d("IncomingSmsHandler", "getBackgroundMessageHandle returned: $handle")
        return handle
    }

    fun isApplicationForeground(context: Context): Boolean {
        Log.d("IncomingSmsHandler", "isApplicationForeground called")
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (keyguardManager.isKeyguardLocked) {
            Log.d("IncomingSmsHandler", "Keyguard is locked")
            return false
        }
        val myPid = Process.myPid()
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        var list: List<ActivityManager.RunningAppProcessInfo>
        if (activityManager.runningAppProcesses.also { list = it } != null) {
            for (aList in list) {
                var info: ActivityManager.RunningAppProcessInfo
                if (aList.also { info = it }.pid == myPid) {
                    val isForeground = info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
                    Log.d("IncomingSmsHandler", "Application is in foreground: $isForeground")
                    return isForeground
                }
            }
        }
        Log.d("IncomingSmsHandler", "Application is not in foreground")
        return false
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        Log.d("IncomingSmsHandler", "onMethodCall called with method: ${call.method}")
        if (SmsAction.fromMethod(call.method) == SmsAction.BACKGROUND_SERVICE_INITIALIZED) {
            onChannelInitialized(
                ContextHolder.applicationContext
                    ?: throw RuntimeException("Context not initialised!")
            )
        }
    }

    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        Log.d("IncomingSmsHandler", "onListen called")
        eventSink = events
    }

    override fun onCancel(arguments: Any?) {
        Log.d("IncomingSmsHandler", "onCancel called")
        eventSink = null
    }

    fun sendSmsToStream(sms: HashMap<String, Any?>) {
        Log.d("IncomingSmsHandler", "sendSmsToStream called with sms: $sms")
        eventSink?.success(sms)
    }
}