package orban.group.wireguard_flutter
import orban.group.wireguard_flutter.config.WgQuickParser
import orban.group.wireguard_flutter.config.WgQuickConfig

import java.io.StringReader
import android.content.pm.ServiceInfo


import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.PluginRegistry

import android.app.Activity
import io.flutter.embedding.android.FlutterActivity
import android.content.Intent
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import com.beust.klaxon.Klaxon
// import com.wireguard.android.backend.*
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.BackendException
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.crypto.Key
import com.wireguard.crypto.KeyPair
import com.wireguard.config.Config

import io.flutter.plugin.common.EventChannel
import kotlinx.coroutines.*
import java.util.*

import java.io.BufferedReader

import android.net.VpnService

import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream




import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat


/** WireguardFlutterPlugin */

const val PERMISSIONS_REQUEST_CODE = 10014
const val METHOD_CHANNEL_NAME = "orban.group.wireguard_flutter_plus/wgcontrol"
const val METHOD_EVENT_NAME = "orban.group.wireguard_flutter_plus/wgstage"
const val TRAFFIC_EVENT_NAME = "orban.group.wireguard_flutter_plus/traffic"

class WireguardFlutterPlugin : FlutterPlugin, MethodCallHandler, ActivityAware,
    PluginRegistry.ActivityResultListener {
    private lateinit var channel: MethodChannel
    private lateinit var events: EventChannel
    private lateinit var trafficEvents: EventChannel
    private lateinit var context: Context

    private var trafficMonitorJob: Job? = null

 
    private val futureBackend = CompletableDeferred<Backend>()
    private var vpnStageSink: EventChannel.EventSink? = null
    private var trafficSink: EventChannel.EventSink? = null
    private val scope = CoroutineScope(Job() + Dispatchers.Main.immediate)
    private var backend: Backend? = null
    private var havePermission = false
    private var permissionResult: MethodChannel.Result? = null
    private var vpnPermissionContinuation: ((Boolean) -> Unit)? = null

    private var previousRx: Long = 0
    private var previousTx: Long = 0
    private var lastUpdateTime: Long = 0
    private var trafficMonitorActive = false
    private var connectionStartTime: Long = 0L  // 👈 ADD THIS LINE
    private var activity: Activity? = null

    private lateinit var tunnelName: String
    private var vpnDisplayName: String = "WireGuard VPN" // Custom VPN display name
    private var config: com.wireguard.config.Config? = null
    private var tunnel: WireGuardTunnel? = null
    private val TAG = "NVPN"
    var isVpnChecked = false
    companion object {
        private var state: String = "no_connection"

        fun getStatus(): String {
            return state
        }
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        // this.havePermission =
        //     (requestCode == PERMISSIONS_REQUEST_CODE) && (resultCode == Activity.RESULT_OK)
        // return havePermission
         if (requestCode == PERMISSIONS_REQUEST_CODE) {
        val granted = resultCode == Activity.RESULT_OK
        havePermission = granted
        vpnPermissionContinuation?.invoke(granted)
        vpnPermissionContinuation = null
        return true
        }
        return false
    }

    override fun onAttachedToActivity(activityPluginBinding: ActivityPluginBinding) {
        this.activity = activityPluginBinding.activity as FlutterActivity
    }

    override fun onDetachedFromActivityForConfigChanges() {
        this.activity = null
    }

    override fun onReattachedToActivityForConfigChanges(activityPluginBinding: ActivityPluginBinding) {
        this.activity = activityPluginBinding.activity as FlutterActivity
    }

    override fun onDetachedFromActivity() {
        this.activity = null
    }

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, METHOD_CHANNEL_NAME)
        events = EventChannel(flutterPluginBinding.binaryMessenger, METHOD_EVENT_NAME)
        trafficEvents = EventChannel(flutterPluginBinding.binaryMessenger, TRAFFIC_EVENT_NAME)
        context = flutterPluginBinding.applicationContext

  
        channel.setMethodCallHandler(this)
        events.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                isVpnChecked = false
                vpnStageSink = events
            }

            override fun onCancel(arguments: Any?) {
                isVpnChecked = false
                vpnStageSink = null
            }
        })

       trafficEvents.setStreamHandler(object : EventChannel.StreamHandler {
        override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
           trafficSink = events

            scope.launch(Dispatchers.IO) {
              val isActive = isVpnActive()
              Log.i(TAG, "VPN active on trafficEvent listen: $isActive")
              if (isActive) {
                startTrafficMonitor()
              } else {
                stopTrafficMonitor()
              }
            }
        }

        override fun onCancel(arguments: Any?) {
         trafficSink = null
         stopTrafficMonitor()
        }
        }) 

     
            // Initialize backend async, then restore tunnel/config
    scope.launch(Dispatchers.IO) {
        try {
            backend = createBackend()
            futureBackend.complete(backend!!)

             // Now it's safe to access runningTunnelNames
            val runningTunnels = backend!!.runningTunnelNames
            Log.i(TAG, "Running tunnels after reopen: $runningTunnels")

            // After backend is ready, restore saved config (but don't auto-reconnect)
        val prefs = context.getSharedPreferences("vpn_prefs", Context.MODE_PRIVATE)
val savedTunnelName = prefs.getString("last_used_tunnel", null)
val savedConfigString = prefs.getString("last_used_config", null)

if (!savedTunnelName.isNullOrEmpty() && !savedConfigString.isNullOrEmpty()) {
    try {
        val parsed = com.wireguard.config.Config.parse(savedConfigString.byteInputStream())
        if (parsed != null) {
            tunnelName = savedTunnelName
            config = parsed
            Log.i(TAG, "Restored last used tunnel and config (not auto-connecting)")
        } else {
            Log.e(TAG, "Parsed config is null")
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to parse saved config: ${e.message}", e)
    }
}



            // Update stage/state after restoring
            val isActive = isVpnActive()
            
            // If VPN is active and we have restored config, register tunnel with backend
            // This allows disconnect to work without creating a new TUN interface
            if (isActive && !savedTunnelName.isNullOrEmpty() && config != null) {
                try {
                    // Only register if backend reports running tunnels
                    val runningTunnels = backend!!.runningTunnelNames
                    if (runningTunnels.isNotEmpty()) {
                        // Reset tunnel singleton to ensure fresh creation
                        tunnel = null
                        
                        // Create tunnel object to register with backend
                        val tunnelObj = tunnel(savedTunnelName) { state ->
                            updateStageFromState(state)
                        }
                        
                        Log.i(TAG, "Tunnel object recreated for existing VPN connection")
                        updateStage("connected")
                        
                        // Resume traffic monitoring and foreground service
                        startForegroundService()
                        startTrafficMonitor()
                    } else {
                        // System reports VPN active but backend has no tunnels
                        // This can happen briefly after disconnect
                        updateStage("disconnected")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to recreate tunnel object: ${e.message}", e)
                    updateStage("disconnected")
                }
            } else {
                updateStage(if (isActive) "connected" else "disconnected")
            }



        } catch (e: Exception) {
            Log.e(TAG, "Error initializing backend or restoring config", e)
        }
    }



    }
   

    private fun createBackend(): Backend {
        if (backend == null) {
            backend = GoBackend(context)
        }
        return backend as Backend
    }

    private fun flutterSuccess(result: Result, o: Any) {
        scope.launch(Dispatchers.Main) {
            result.success(o)
        }
    }

    private fun flutterError(result: Result, error: String) {
        scope.launch(Dispatchers.Main) {
            result.error(error, null, null)
        }
    }

    private fun flutterNotImplemented(result: Result) {
        scope.launch(Dispatchers.Main) {
            result.notImplemented()
        }
    }

    override fun onMethodCall(call: MethodCall, result: Result) {

        when (call.method) {
            "initialize" -> setupTunnel(
                call.argument<String>("localizedDescription").toString(),
                call.argument<String>("vpnName") ?: "WireGuard VPN",
                result
            )
            "checkVpnPermission" -> {
                checkAndRequestVpnPermission(result)
            }
            "start" -> {
                val wgQuickConfig = call.argument<String>("wgQuickConfig").toString()
                val excludedApps = call.argument<String>("excludedApps")
                val includedApps = call.argument<String>("includedApps")
                
                var finalConfig = wgQuickConfig
                if (!excludedApps.isNullOrEmpty()) {
                    finalConfig = finalConfig.replaceFirst(
                        Regex("(?i)\\[Interface\\]"),
                        "[Interface]\nExcludedApplications = $excludedApps"
                    )
                }
                if (!includedApps.isNullOrEmpty()) {
                    finalConfig = finalConfig.replaceFirst(
                        Regex("(?i)\\[Interface\\]"),
                        "[Interface]\nIncludedApplications = $includedApps"
                    )
                }

                connect(finalConfig, result)

                if (!isVpnChecked) {
        scope.launch(Dispatchers.IO) {
            val active = isVpnActive()
            state = if (active) "connected" else "disconnected"
            isVpnChecked = true
            println("VPN is ${if (active) "active" else "not active"}")
        }
    }
            }
            "stop" -> {
                disconnect(result)
            }
            "stage" -> {
                result.success(getStatus())
            }
            "checkPermission" -> {
                checkPermission()
                result.success(null)
            }
            "getDownloadData" -> {
                getDownloadData(result)
            }
        "getUploadData" -> {
            getUploadData(result)
        }
        
            else -> flutterNotImplemented(result)
        }
    }

    private fun isVpnActive(): Boolean {
        try {
            val connectivityManager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val activeNetwork = connectivityManager.activeNetwork
                val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
                return networkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            } else {
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "isVpnActive - ERROR - ${e.message}")
            return false
        }
    }




    private fun updateStage(stage: String?) {
        scope.launch(Dispatchers.Main) {
            val updatedStage = stage ?: "no_connection"
            state = updatedStage
            vpnStageSink?.success(updatedStage.lowercase(Locale.ROOT))
        }
    }

    private fun updateStageFromState(state: Tunnel.State) {
        scope.launch(Dispatchers.Main) {
            when (state) {
                Tunnel.State.UP -> updateStage("connected")
                Tunnel.State.DOWN -> updateStage("disconnected")
                else -> updateStage("wait_connection")
            }
        }
    }
private fun connect(wgQuickConfig: String, result: Result) {
    checkAndRequestVpnPermissionBlocking { granted ->
        if (!granted) {
            result.error("PERMISSION_DENIED", "User denied VPN permission", null)
            return@checkAndRequestVpnPermissionBlocking
        }
        scope.launch(Dispatchers.IO) {
            try {
                if (!havePermission) {
                    checkPermission()
                    throw Exception("Permissions are not given")
                }
                
                // Check if tunnel is already UP to avoid disconnect-then-connect cycle
                // But only if we have a valid tunnel object (not after a disconnect where we nullified it)
                val backend = futureBackend.await()
                val runningTunnels = backend.runningTunnelNames
                if (runningTunnels.contains(tunnelName) && tunnel != null && config != null) {
                    Log.i(TAG, "Tunnel $tunnelName is already UP, skipping connect")
                    withContext(Dispatchers.Main) {
                        flutterSuccess(result, "")
                    }
                    return@launch
                }
                
                updateStage("prepare")

                val inputStream = ByteArrayInputStream(wgQuickConfig.toByteArray())
                val parsedConfig = com.wireguard.config.Config.parse(inputStream)
                    ?: throw Exception("Failed to parse WireGuard config")

                updateStage("connecting")

                backend.setState(
                    tunnel(tunnelName) { state ->
                        scope.launch(Dispatchers.Main) {
                            Log.i(TAG, "onStateChange - $state")
                            updateStageFromState(state)
                        }
                    },
                    Tunnel.State.UP,
                    parsedConfig
                )

                withContext(Dispatchers.IO) {
                    //saveLastUsedConfig(tunnelName, parsedConfig)
                    saveLastUsedConfig(tunnelName, wgQuickConfig)
                }

                Log.i(TAG, "Connect - success!")
                startForegroundService()
                startTrafficMonitor()

                withContext(Dispatchers.Main) {
                    flutterSuccess(result, "")
                }
            } catch (e: BackendException) {
                Log.e(TAG, "Connect - BackendException - ERROR - ${e.reason}", e)
                withContext(Dispatchers.Main) {
                    flutterError(result, e.reason.toString())
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Connect - Can't connect to tunnel: $e", e)
                withContext(Dispatchers.Main) {
                    flutterError(result, e.message.toString())
                }
            }
        }
    }
}


private fun disconnect(result: Result) {
    trafficMonitorActive = false
    connectionStartTime = 0L

    scope.launch(Dispatchers.IO) {
        try {
            val backend = futureBackend.await()
            val runningTunnels = backend.runningTunnelNames

            Log.i(TAG, "Running tunnels: $runningTunnels")
            Log.i(TAG, "Current tunnelName: $tunnelName")
            Log.i(TAG, "Current Config: $config")

            updateStage("disconnecting")

            // Load config if not already loaded
            if (config == null) {
                val prefs = context.getSharedPreferences("vpn_prefs", Context.MODE_PRIVATE)
                val savedConfigString = prefs.getString("last_used_config", null)
                if (!savedConfigString.isNullOrEmpty()) {
                    config = com.wireguard.config.Config.parse(savedConfigString.byteInputStream())
                    Log.i(TAG, "Loaded config from SharedPreferences for disconnect")
                }
            }

            // If there are running tunnels, use normal disconnect
            // If no running tunnels but we have config, use orphaned tunnel termination
            if (runningTunnels.isNotEmpty()) {
                val activeTunnelName = runningTunnels.first()
                
                Log.i(TAG, "Disconnecting tunnel: $activeTunnelName")
                
                // Validate config before proceeding
                if (config == null) {
                    Log.e(TAG, "Config is null, cannot call setState() for disconnect")
                    throw Exception("Cannot disconnect: no configuration available")
                }
                
                Log.i(TAG, "Config validated, calling setState() to bring tunnel DOWN")
                
                // Get or create tunnel object
                if (tunnel == null) {
                    Log.i(TAG, "Creating new tunnel object for disconnect")
                    tunnelName = activeTunnelName
                    tunnel(activeTunnelName) { state ->
                        scope.launch(Dispatchers.Main) {
                            Log.i(TAG, "onStateChange - $state")
                            if (state == Tunnel.State.DOWN) {
                                resetTrafficStats()
                                stopTrafficMonitor()
                                updateStageFromState(state)
                            }
                        }
                    }
                } else {
                    Log.i(TAG, "Reusing existing tunnel object for disconnect")
                }
                
                // ✅ Stop traffic monitor and reset stats BEFORE calling setState
                stopTrafficMonitor()
                resetTrafficStats()
                
                // ✅ Always call setState to bring tunnel DOWN, even if backend reports no running tunnels
                Log.i(TAG, "About to call backend.setState() with Tunnel.State.DOWN")
                backend.setState(
                    tunnel!!,
                    Tunnel.State.DOWN,
                    config
                )
                Log.i(TAG, "backend.setState() call completed")
                
                Log.i(TAG, "Tunnel $activeTunnelName disconnected")
                
                // Wait for state change to propagate
                delay(1000)
                
                // ✅ Explicitly update stage to disconnected
                updateStage("disconnected")
            } else {
                // No running tunnels reported by backend, but we have tunnel and config
                // This can happen after app reopen - still need to bring it DOWN
                Log.i(TAG, "No running tunnels found, but we have tunnel and config")
                
                // ✅ Stop traffic monitor and reset stats BEFORE calling setState
                stopTrafficMonitor()
                resetTrafficStats()
                
                // ✅ Recreate tunnel if null on restart
                if (tunnel == null && this@WireguardFlutterPlugin::tunnelName.isInitialized && !tunnelName.isNullOrEmpty()) {
                    Log.i(TAG, "Creating new tunnel object for orphaned disconnect using tunnelName: $tunnelName")
                    tunnel(tunnelName) { state ->
                        scope.launch(Dispatchers.Main) {
                            Log.i(TAG, "onStateChange - $state")
                            if (state == Tunnel.State.DOWN) {
                                resetTrafficStats()
                                stopTrafficMonitor()
                                updateStageFromState(state)
                            }
                        }
                    }
                }
                
                // ✅ The backend doesn't know about this tunnel, so we need to bring it UP first
                // then immediately bring it DOWN to properly terminate it
                if (tunnel != null && config != null) {
                    Log.i(TAG, "Orphaned tunnel detected - bringing UP then DOWN to terminate")
                    try {
                        // First bring it UP so backend knows about it
                        backend.setState(
                            tunnel!!,
                            Tunnel.State.UP,
                            config
                        )
                        Log.i(TAG, "Tunnel brought UP temporarily")
                        
                        // Small delay to let it register
                        delay(100)
                        
                        // Now bring it DOWN
                        backend.setState(
                            tunnel!!,
                            Tunnel.State.DOWN,
                            config
                        )
                        Log.i(TAG, "Tunnel brought DOWN - orphaned connection terminated")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error terminating orphaned tunnel: ${e.message}", e)
                    }
                }
                
                updateStage("disconnected")
            }

            stopForegroundService()
            clearStatsFromStorage()
        
            deleteActiveTunnel()
            
            // ✅ Nullify tunnel object to ensure clean state
            tunnel = null
            config = null
            Log.i(TAG, "Tunnel object and config nullified")

            Log.i(TAG, "Disconnected successfully.")
            withContext(Dispatchers.Main) {
                flutterSuccess(result, "")
            }

         

        } catch (e: BackendException) {
            Log.e(TAG, "BackendException during disconnect: ${e.reason}", e)
            // ✅ Even on error, nullify tunnel to prevent ghost connections
            tunnel = null
            config = null
            withContext(Dispatchers.Main) {
                flutterError(result, e.reason.toString())
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Exception during disconnect: ${e.message}", e)
            // ✅ Even on error, nullify tunnel to prevent ghost connections
            tunnel = null
            config = null
            withContext(Dispatchers.Main) {
                flutterError(result, e.message.toString())
            }
        }
    }
}




 private fun resetTrafficStats() {
    VpnTrafficStats.uploadSpeed = "0.0 KB/s"
    VpnTrafficStats.downloadSpeed = "0.0 KB/s"
    VpnTrafficStats.duration = "00:00:00"

    // ✅ Must run on main thread to send to Flutter
    scope.launch(Dispatchers.Main) {
        trafficSink?.success(
            mapOf(
                "totalDownload" to 0,
                "totalUpload" to 0,
                "downloadSpeed" to 0,
                "uploadSpeed" to 0,
                "duration" to "00:00:00"
            )
        )
    }
    // Don't nullify trafficSink here - let the EventChannel's onCancel handle it
    // This allows traffic monitoring to continue working after reconnection
 }

  private fun clearStatsFromStorage() {
    val prefs = context.getSharedPreferences("WireGuardStats", Context.MODE_PRIVATE)
    prefs.edit().clear().apply()
 }


    private fun setupTunnel(localizedDescription: String, vpnName: String, result: Result) {
        scope.launch(Dispatchers.IO) {
            if (Tunnel.isNameInvalid(localizedDescription)) {
                flutterError(result, "Invalid Name")
                return@launch
            }
            tunnelName = localizedDescription
            vpnDisplayName = vpnName // Store the custom VPN name

            checkPermission()
            result.success(null)
        }
    }

    private fun checkPermission() {
        val intent = GoBackend.VpnService.prepare(this.activity)
        if (intent != null) {
            havePermission = false
            this.activity?.startActivityForResult(intent, PERMISSIONS_REQUEST_CODE)
        } else {
            havePermission = true
        }
    }
    private fun checkAndRequestVpnPermission(result: MethodChannel.Result) {
        val intent = VpnService.prepare(context)
        if (intent != null) {
            // Permission NOT granted yet, ask user by launching system dialog
            permissionResult = result
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            activity?.startActivityForResult(intent, PERMISSIONS_REQUEST_CODE)
                ?: run {
                    // If activity is null, cannot request permission properly
                    result.error("NO_ACTIVITY", "Activity is null, cannot request VPN permission", null)
                }
        } else {
            // Permission already granted
            havePermission = true
            result.success(true)
        }
    }

    fun checkAndRequestVpnPermissionBlocking(callback: (Boolean) -> Unit) {
     val intent = VpnService.prepare(context)
      if (intent != null) {
        vpnPermissionContinuation = callback
        activity?.startActivityForResult(intent, PERMISSIONS_REQUEST_CODE)
      } else {
        // Already granted
        havePermission = true
        callback(true)
     }
    }

    private fun getDownloadData(result: Result) {
     scope.launch(Dispatchers.IO) {
        try {
            val stats = futureBackend.await().getStatistics(tunnel(tunnelName))
            val downloadData = stats.totalRx() // Use totalRx() instead of totalRx
            flutterSuccess(result, downloadData)
        } catch (e: Throwable) {
            Log.e(TAG, "getDownloadData - ERROR - ${e.message}")
            flutterError(result, e.message.toString())
        }
     }
    }

     private fun getUploadData(result: Result) {
       scope.launch(Dispatchers.IO) {
        try {
            val stats = futureBackend.await().getStatistics(tunnel(tunnelName))
            val uploadData = stats.totalTx() // Use totalTx() instead of totalTx
            flutterSuccess(result, uploadData)
        } catch (e: Throwable) {
            Log.e(TAG, "getUploadData - ERROR - ${e.message}")
            flutterError(result, e.message.toString())
        }
      }
    }

    private fun getDataCounts(result: Result) {
        scope.launch(Dispatchers.IO) {
        try {
            val stats = futureBackend.await().getStatistics(tunnel(tunnelName))
            val dataCounts = mapOf(
                "download" to stats.totalRx(),
                "upload" to stats.totalTx()
            )
            flutterSuccess(result, dataCounts)
        } catch (e: Throwable) {
            Log.e(TAG, "getDataCounts - ERROR - ${e.message}")
            flutterError(result, e.message.toString())
        }
    }
    }

  private fun startTrafficMonitor() {
    if (!this::tunnelName.isInitialized) {
        Log.e("NVPN", "Tunnel name has not been initialized!")
        return
    }

    val prefs = context.getSharedPreferences("WireGuardStats", Context.MODE_PRIVATE)

    // For fresh connections, reset the start time. For app restarts, restore it.
    val savedStartTime = prefs.getLong("connectionStartTime", 0L)
    connectionStartTime = if (savedStartTime == 0L || !isVpnActive()) {
        System.currentTimeMillis()
    } else {
        savedStartTime
    }
    
    previousRx = prefs.getLong("totalRx", 0)
    previousTx = prefs.getLong("totalTx", 0)
    lastUpdateTime = 0L  // Always reset to 0 for fresh monitoring
    
    trafficMonitorActive = true
    

    scope.launch(Dispatchers.IO) {
        while (trafficMonitorActive) {
            try {
                val currentTime = System.currentTimeMillis()

                val stats = futureBackend.await().getStatistics(tunnel(tunnelName))
                val currentRx = stats.totalRx()
                val currentTx = stats.totalTx()

                // Calculate speeds (0 on first iteration)
                val timeDiff = if (lastUpdateTime != 0L) {
                    (currentTime - lastUpdateTime) / 1000.0
                } else {
                    1.0 // Use 1 second as default to avoid division by zero
                }
                
                val downloadSpeed = if (lastUpdateTime != 0L) {
                    (currentRx - previousRx) / timeDiff
                } else {
                    0.0 // First iteration, no speed yet
                }
                
                val uploadSpeed = if (lastUpdateTime != 0L) {
                    (currentTx - previousTx) / timeDiff
                } else {
                    0.0 // First iteration, no speed yet
                }

                // Calculate duration
                val elapsedMillis = currentTime - connectionStartTime
                val elapsedSeconds = (elapsedMillis / 1000) % 60
                val elapsedMinutes = (elapsedMillis / (1000 * 60)) % 60
                val elapsedHours = (elapsedMillis / (1000 * 60 * 60))
                val durationString = String.format("%02d:%02d:%02d", elapsedHours, elapsedMinutes, elapsedSeconds)

                VpnTrafficStats.uploadSpeed = String.format("%.1f KB/s", uploadSpeed / 1024)
                VpnTrafficStats.downloadSpeed = String.format("%.1f KB/s", downloadSpeed / 1024)
                VpnTrafficStats.duration = durationString

                // Always send data, even on first iteration
                val data = mapOf(
                    "totalDownload" to (currentRx / 1024),
                    "totalUpload" to (currentTx / 1024),
                    "downloadSpeed" to (downloadSpeed / 1024),
                    "uploadSpeed" to (uploadSpeed / 1024),
                    "duration" to durationString
                )

                scope.launch(Dispatchers.Main) {
                    trafficSink?.success(data)
                }

                // Save values persistently
                prefs.edit().apply {
                    putLong("totalRx", currentRx)
                    putLong("totalTx", currentTx)
                    putLong("lastUpdateTime", currentTime)
                    putLong("connectionStartTime", connectionStartTime)
                    apply()
                }

                previousRx = currentRx
                previousTx = currentTx
                lastUpdateTime = currentTime

                delay(1000)
            } catch (e: Throwable) {
                Log.e("NVPN", "Traffic monitor error: ${e.message}")
                delay(2000)
            }
        }
    }
  }


   private fun stopTrafficMonitor() {
        trafficMonitorActive = false
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        events.setStreamHandler(null)
        trafficEvents.setStreamHandler(null)
        isVpnChecked = false
    }

    private fun tunnel(name: String, callback: StateChangeCallback? = null): WireGuardTunnel {
        if (tunnel == null) {
            tunnel = WireGuardTunnel(name, callback)
        }
        return tunnel as WireGuardTunnel
    }







   private fun startForegroundService() {
    val intent = Intent(context, VpnForegroundService::class.java)
    intent.action = "START" // ✅ Important to set action
    intent.putExtra("vpnDisplayName", vpnDisplayName) // Pass custom VPN name
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
    } else {
        context.startService(intent)
    }
  }

  private fun stopForegroundService() {
    val stopIntent = Intent(context, VpnForegroundService::class.java)
    stopIntent.action = "STOP"
    context.startService(stopIntent)
  }


private fun saveLastUsedConfig(name: String, wgQuickString: String) {
    val sharedPreferences = context.getSharedPreferences("vpn_prefs", Context.MODE_PRIVATE)
    val editor = sharedPreferences.edit()
    editor.putString("last_used_tunnel", name)
    editor.putString("last_used_config", wgQuickString)
    editor.apply()
    Log.i(TAG, "Saved last used tunnel: $name")
    Log.i(TAG, "Saved config string:\n$wgQuickString")
}


private fun deleteActiveTunnel() {
    val prefs = context.getSharedPreferences("vpn_prefs", Context.MODE_PRIVATE)
    prefs.edit()
        .remove("last_used_tunnel")
        .remove("last_used_config")
        .apply()
    config = null
}



private fun Config.toWgQuickString(): String {
    return WgQuickConfig.toWgQuickString(this)
}

}

typealias StateChangeCallback = (Tunnel.State) -> Unit

class WireGuardTunnel(
    private val name: String, private val onStateChanged: StateChangeCallback? = null
) : Tunnel {

    override fun getName() = name

    override fun onStateChange(newState: Tunnel.State) {
        onStateChanged?.invoke(newState)
    }
}

object VpnTrafficStats {
    var uploadSpeed: String = "0 KB/s"
    var downloadSpeed: String = "0 KB/s"
    var duration: String = "00:00:00" // Initialize duration
}



class VpnForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "vpn_foreground_channel"
        const val NOTIFICATION_ID = 101
    }

    private val handler = Handler(Looper.getMainLooper())
    private val updateInterval = 1000L // 1 second

    private var connectionStartTime = 0L // Store start timestamp
    private var vpnDisplayName: String = "WireGuard VPN" // Custom VPN display name

    private val updateRunnable = object : Runnable {
        override fun run() {
            val uploadSpeed = getCurrentUploadSpeed()
            val downloadSpeed = getCurrentDownloadSpeed()
            val duration = getCurrentDuration()
            val contentText = "↑ $uploadSpeed | ↓ $downloadSpeed | $duration"

            updateNotification(contentText)

            handler.postDelayed(this, updateInterval)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = buildNotification("VPN is running")
       // startForeground(NOTIFICATION_ID, notification)
          if (Build.VERSION.SDK_INT >= 34) {
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    } else {
        startForeground(NOTIFICATION_ID, notification)
    }

        // Start updating notification with live traffic stats
        handler.post(updateRunnable)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "VPN Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
 

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(vpnDisplayName) // Use custom VPN name
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_lock_lock) // your VPN icon here
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(contentText: String) {
        val notification = buildNotification(contentText)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.notify(NOTIFICATION_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Get the custom VPN name from intent
        vpnDisplayName = intent?.getStringExtra("vpnDisplayName") ?: "WireGuard VPN"
        
        when (intent?.action) {
          "START" -> startForeground(NOTIFICATION_ID, buildNotification("VPN is running"))
          "STOP" -> {
            stopForeground(true)
            stopSelf()
          }
    }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(updateRunnable)
    }

    override fun onBind(intent: Intent?): IBinder? = null

   private fun getCurrentUploadSpeed(): String {
    return VpnTrafficStats.uploadSpeed

   }

   private fun getCurrentDownloadSpeed(): String {
    return VpnTrafficStats.downloadSpeed
 
   }

   private fun getCurrentDuration(): String {
    return VpnTrafficStats.duration
 
   }

}
