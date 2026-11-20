package com.adguard.trusttunnel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import com.adguard.trusttunnel.log.FileLogger
import com.adguard.trusttunnel.utils.NetworkUtils
import com.adguard.trusttunnel.utils.concurrent.thread.ThreadManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class AppVpnStatus(val applied: AppliedAppVpnRules? = null, val error: String? = null)

class VpnService : android.net.VpnService(), VpnClientListener {

    companion object {
        private val LOG = Logger("VpnService")
        private val NETWORK_MANAGER_SYNC = Any()

        // Network monitoring
        private lateinit var connectivityManager: ConnectivityManager
        private lateinit var networkRequest: NetworkRequest
        private lateinit var networkCallback: NetworkUtils.Companion.NetworkCollector
        private var connectionInfoFile: PersistentRingBuffer? = null
        private val singleThread = ThreadManager.create("vpn-service", 1)

        private var fileLogger: FileLogger? = null
        private var initialized = false

        @Volatile
        private var configStorage: VpnConfigStorage? = null
        // The last VpnState observed by `onStateChanged`
        private var lastState: Int = 0
        private const val ACTION_START = "Start"
        private const val ACTION_STOP  = "Stop"
        private const val ACTION_APPLY_APP_VPN = "ApplyAppVpn"
        @Volatile var appVpnStatus = AppVpnStatus()
            private set

        fun reconnectWithAppVpn(context: Context, expectedRevision: String) {
            if (appVpnStatus.applied == null || !isPrepared(context)) throw AppVpnException("inactive")
            context.startForegroundService(getIntent(context, ACTION_APPLY_APP_VPN).putExtra("appVpnRevision", expectedRevision))
        }

        private fun publishAppVpn(applied: AppliedAppVpnRules?, error: String? = null) {
            appVpnStatus = AppVpnStatus(applied, error)
            eventsSync.execute { appNotifier?.onAppVpnChanged() }
        }
        private const val PARAM_CONFIG = "Config Extra"
        private const val PARAM_FALL_INTO_RECOVERY = "Fall Into Recovery Extra"
        private const val NOTIFICATION_ID = 1
        private val IPV4_NON_ROUTABLE = listOf("0.0.0.0/8", "224.0.0.0/3")
        private val ADGUARD_DNS_SERVERS = listOf("46.243.231.30", "46.243.231.31", "2a10:50c0::2:ff", "2a10:50c0::1:ff")
        private val FAKE_DNS_SERVER = listOf("198.18.53.53")

        private fun getConfigStorage(context: Context): VpnConfigStorage {
            return configStorage ?: VpnConfigStorage(context).also { configStorage = it }
        }

        private fun start(context: Context, intent: Intent, config: String?, fallIntoRecovery: Boolean) {
            try {
                if (!isPrepared(context)) {
                    LOG.warn("VPN is not prepared, can't manipulate the service")
                    return
                }
                config?.apply {
                    getConfigStorage(context).save(config)
                    intent.putExtra(PARAM_CONFIG, config)
                }
                intent.putExtra(PARAM_FALL_INTO_RECOVERY, fallIntoRecovery)
                context.startForegroundService(intent)
            } catch (e: Exception) {
                LOG.error("Error occurred while service starting", e)
            }
        }

        fun stop(context: Context) {
            getConfigStorage(context).clear()
            start(context, ACTION_STOP, null, false)
        }
        fun start(context: Context, config: String?, fallIntoRecovery: Boolean) =
                start(context, ACTION_START, config, fallIntoRecovery)
        private fun start(context: Context, action: String, config: String?, fallIntoRecovery: Boolean) =
                start(context, getIntent(context, action), config, fallIntoRecovery)

        fun startNetworkManager(context: Context) = synchronized(NETWORK_MANAGER_SYNC) {
            if (::networkCallback.isInitialized) {
                // Avoid double initialization
                return
            }

            connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            networkRequest = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addTransportType(NetworkCapabilities.TRANSPORT_BLUETOOTH)
                .build()

            networkCallback = NetworkUtils.Companion.NetworkCollector()
            connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
        }

        private fun startNetworkNotifications(vpnClient: VpnClient?) = synchronized(NETWORK_MANAGER_SYNC) {
            networkCallback.startNotifying(vpnClient)
        }

        private fun stopNetworkNotifications() = synchronized(NETWORK_MANAGER_SYNC) {
            networkCallback.stopNotifying()
        }

        fun isPrepared(context: Context): Boolean {
            return try {
                prepare(context) == null
            } catch (e: Exception) {
                LOG.error("Error while checking VPN service is prepared", e)
                false
            }
        }

        /** Gets an intent instance with [action] */
        private fun getIntent(context: Context, action: String): Intent = Intent(context, VpnService::class.java).setAction(action)

        private val eventsSync = ThreadManager.create("events-sync", 1)
        private var appNotifier: AppNotifier? = null
        fun setAppNotifier(file: File, notifier: AppNotifier) = eventsSync.execute {
            connectionInfoFile = PersistentRingBuffer(file)
            appNotifier = notifier
            // Notify current state
            appNotifier?.onStateChanged(lastState)
            // Notify all query logs
            connectionInfoFile?.apply {
                val records = readAll()
                if (records == null) {
                    clear()
                    return@execute
                }
                for (record in records) {
                    appNotifier?.onConnectionInfo(record)
                }
            }
        }

        /**
         * Initialize the adapter. Call once at app startup (e.g., from
         * [MainActivity.configureFlutterEngine]) to set up network monitoring and
         * file logging from the very beginning.
         *
         * Idempotent — safe to call multiple times.
         */
        fun initialize(context: Context) {
            if (initialized) return
            initialized = true

            // FileLogger — captures all logs from this point forward
            val logsDir = File(context.filesDir, "logs")
            val logger = FileLogger(logsDir, FileLogger.VPN_BASE_NAME)
            logger.install()
            fileLogger = logger

            // Network monitoring — detect network changes for VPN recovery
            startNetworkManager(context)
        }

        /**
         * Export log files to a timestamped temp directory.
         *
         * Returns absolute paths to snapshot copies. Caller (Flutter) owns cleanup.
         * Safe to call while VPN is active.
         */
        fun exportLogs(context: Context): List<String> {
            if (!initialized) return emptyList()

            val dateStr = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US).apply {
                timeZone = TimeZone.getDefault()
            }.format(Date())

            val exportDirName = "trusttunnel_android_logs_$dateStr"
            val exportDir = File(context.cacheDir, exportDirName)

            return fileLogger?.snapshotTo(exportDir) ?: emptyList()
        }

        /**
         * Delete all log files produced by the VPN service.
         *
         * Safe to call while the VPN is active.
         */
        fun clearLogs() {
            if (!initialized) return
            fileLogger?.clearLogs()
        }
    }

    private var currentStartId = -1
    private var vpnClient: VpnClient? = null
    @Volatile private var destroyed = false
    private var state = State.Stopped
    private var runningConfig: String? = null
    private var runningRecovery = false
    private var generation = 0L
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_PACKAGE_REMOVED && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
            val name = intent.data?.schemeSpecificPart ?: return
            val uid = intent.getIntExtra(Intent.EXTRA_UID, -1)
            singleThread.execute {
                if (destroyed) return@execute
                val applied = appVpnStatus.applied ?: return@execute
                if (applied.rules.enabled && (name in applied.rules.packages || uid in applied.installed.values)) {
                    val now = try { AppliedAppVpnRules.resolve(context, applied.rules) } catch (_: AppVpnException) { null }
                    if (now != applied) reconnect(currentStartId, packageChange = true)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(this, packageReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onDestroy() {
        destroyed = true
        unregisterReceiver(packageReceiver)
        singleThread.execute {
            if (vpnClient != null) {
                closeClient()
                publishAppVpn(null)
                onStateChanged(VpnState.DISCONNECTED.code)
            }
        }
        super.onDestroy()
    }

    @Volatile
    private var certificateVerificator: CertificateVerificator? = null


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            LOG.info("Received a null intent, doing nothing")
            stopSelf()
            return START_NOT_STICKY
        }

        // Foreground service must spawn its notification in the first 5 seconds of the service lifetime
        val notification = createNotification(this.applicationContext)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // We run all payload in another thread (not main) to minimize UI lags
        singleThread.execute {
            try {
                if (destroyed) return@execute
                currentStartId = startId
                val action = intent.action
                val config = intent.getStringExtra(PARAM_CONFIG)
                LOG.info("Start executing action=$action flags=$flags startId=$startId")
                when (action) {
                    ACTION_START    -> processStarting(config, intent.getBooleanExtra(PARAM_FALL_INTO_RECOVERY, false), startId)
                    ACTION_STOP     -> close(startId)
                    ACTION_APPLY_APP_VPN -> reconnect(startId, packageChange = false, expectedRevision = intent.getStringExtra("appVpnRevision"))
                    else            -> {
                        startNetworkManager(applicationContext)
                        LOG.info("System-triggered start (Always-On VPN), loading persisted config")
                        val persistedConfig = getConfigStorage(applicationContext).load()
                        // Always-On VPN should keep recovering instead of giving up after several attempts.
                        processStarting(persistedConfig, true, startId)
                    }
                }

                LOG.info("Command $action for the VPN has been executed")
            } catch (e: Exception) {
                LOG.error("Error while executing command", e)
            }
        }

        return START_NOT_STICKY
    }

    private fun processStarting(configStr: String?, fallIntoRecovery: Boolean, startId: Int, requestedRules: AppVpnRules? = null) {
        if (state == State.Started) {
            LOG.info("VPN service has already been started, do nothing")
            return
        }
        val config = configStr?.let { VpnServiceConfig.parseToml(it) }
        if (configStr == null || config == null) {
            LOG.error("VPN configuration is missing or invalid")
            close(startId, error = "connection")
            return
        }
        val rules = try {
            AppliedAppVpnRules.resolve(this, requestedRules ?: AppVpnStore.read(this))
        } catch (e: AppVpnException) {
            close(startId, error = e.reason)
            return
        }
        val vpnTunInterface = try {
            certificateVerificator = CertificateVerificator()
            createTunInterface(config, rules)
        } catch (e: AppVpnException) {
            close(startId, error = e.reason)
            return
        } catch (_: Exception) {
            null
        }
        if (vpnTunInterface == null) {
            close(startId, error = "connection")
            return
        }
        val currentGeneration = ++generation
        val service = this
        val proxyClientListener = object : VpnClientListener by service {
            override fun onStateChanged(state: Int) = singleThread.execute {
                if (destroyed || generation != currentGeneration) return@execute
                val next = VpnState.getByCode(state)
                LOG.info("VpnService onStateChanged: ${next.name}")
                if (next == VpnState.DISCONNECTED) close(currentStartId) else service.onStateChanged(state)
            }
        }
        val started = try {
            vpnTunInterface.use { descriptor ->
                val client = VpnClient(configStr, proxyClientListener)
                vpnClient = client
                startNetworkNotifications(client)
                client.start(descriptor, fallIntoRecovery)
            }
        } catch (_: Exception) {
            false
        }
        if (!started) {
            close(startId, error = "connection")
            return
        }
        state = State.Started
        runningConfig = configStr
        runningRecovery = fallIntoRecovery
        publishAppVpn(rules)
    }

    private fun reconnect(startId: Int, packageChange: Boolean, expectedRevision: String? = null) {
        val config = runningConfig
        if (config == null) {
            close(startId, error = "inactive")
            return
        }
        val current = appVpnStatus.applied
        val next = try {
            val desired = if (packageChange) requireNotNull(current).rules else AppVpnStore.read(this)
            if (!packageChange && desired.revision != expectedRevision) throw AppVpnException("changed")
            AppliedAppVpnRules.resolve(this, desired)
        } catch (e: AppVpnException) {
            // A removed/reassigned UID can invalidate the existing Android interface.
            if (packageChange) close(startId, error = e.reason)
            else publishAppVpn(appVpnStatus.applied, e.reason)
            return
        }
        if (current != null && current.rules.sameSelection(next.rules) && current.installed == next.installed) {
            publishAppVpn(next)
            return
        }
        val recover = runningRecovery
        onStateChanged(VpnState.CONNECTING.code)
        closeClient()
        publishAppVpn(null)
        processStarting(config, recover, startId, next.rules)
    }

    override fun onRevoke() = singleThread.execute {
        if (destroyed) return@execute
        LOG.info("Revoking the VPN service")
        close()
    }

    private fun createTunInterface(config: VpnServiceConfig, rules: AppliedAppVpnRules): ParcelFileDescriptor? {
        LOG.info("Request 'create tun interface' received")
        val tunConfig = config.listener.tun
        try {
            val builder = Builder().setSession("Trust Tunnel")
                .setMtu(tunConfig.mtuSize.toInt())
                .addAddress("172.20.2.13", 32)
                .addAddress("fdfd:29::2", 64)
            rules.applyTo(builder, this)
            val dnsServers = if (config.endpoint.dnsUpstreams.isEmpty()) {
                ADGUARD_DNS_SERVERS
            } else {
                FAKE_DNS_SERVER
            }
            dnsServers.forEach { server ->
                builder.addDnsServer(server)
            }

            val routes = VpnClient.excludeCidr(tunConfig.includedRoutes, tunConfig.excludedRoutes + IPV4_NON_ROUTABLE)
                ?: throw Exception("Failed to process routes")
            routes.forEach { route ->
                val r = NetworkUtils.convertCidrToAddressPrefixPair(route)
                if (r != null) {
                    builder.addRoute(r.first, r.second)
                } else {
                    throw Exception("Wrong syntax for included_routes")
                }
            }

            return builder.establish()

        } catch (e: AppVpnException) {
            throw e
        } catch (e: Exception) {
            LOG.error("Error while building the TUN interface", e)
            return null
        }
    }

    private fun closeClient() {
        ++generation // Ignore callbacks from this instance once a replacement is requested.
        if (vpnClient != null) {
            stopNetworkNotifications()
            vpnClient?.stop()
            vpnClient?.close()
            vpnClient = null
        }
        runningConfig = null
        state = State.Stopped
    }

    private fun close(startId: Int? = null, error: String? = null) {
        closeClient()
        publishAppVpn(null, error)
        onStateChanged(VpnState.DISCONNECTED.code)
        if (startId == null) stopSelf() else stopSelf(startId)
    }

    /** An enum to represent the VPN service states */
    enum class State {
        Started, Stopped
    }

    /**
     * Protects passed socket by the [VpnService].
     * @param socket socket to protect
     * @return true if socket was protected or false if an error occurred
     */
    override fun protectSocket(socket: Int): Boolean {
        if (protect(socket)) {
            LOG.info("The socket $socket has been protected successfully")
            return true
        }
        LOG.info("Failed to protect socket $socket")
        return false
    }

    override fun verifyCertificate(certificate: ByteArray?, rawChain: List<ByteArray?>?): Boolean {
        val verificator = certificateVerificator ?: return false
        return verificator.verifyCertificate(certificate, rawChain)
    }

    override fun onStateChanged(state: Int) = eventsSync.execute {
        lastState = state
        appNotifier?.onStateChanged(state)
    }

    override fun onConnectionInfo(info: String) = eventsSync.execute {
        LOG.debug("VpnService onConnectionInfo event")
        connectionInfoFile?.apply {
            if (!append(info)) {
                clear()
            }
        }
        appNotifier?.onConnectionInfo(info)
    }

    private fun createNotification(context: Context): Notification {
        val name = "ConnectionStatus"
        val descriptionText = "VPN connection status"
        val channel = NotificationChannel(
            name,
            descriptionText,
            NotificationManager.IMPORTANCE_LOW // Set importance to LOW to be less intrusive, but still visible.
        ).apply {
            description = "TrustTunnel status" // User-visible description of the channel
        }
        val notificationManager: NotificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
        return NotificationCompat.Builder(context, name)
            .setContentTitle("TrustTunnel") // Main title of the notification
            .setContentText("VPN is running in foreground") // Content text of the notification
            // Use a small icon that represents your VPN service.
            .setSmallIcon(android.R.drawable.ic_dialog_info) // Placeholder icon (replace with your app's icon)
            .setPriority(NotificationCompat.PRIORITY_LOW) // Set priority for older Android versions
            .setOngoing(true) // Makes the notification non-dismissible by the user.
            // This is a key characteristic of foreground service notifications.
            .build()
    }
}