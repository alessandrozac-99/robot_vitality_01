package com.example.vitality.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.*
import com.example.vitality.data.SmartPlugRepository
import com.example.vitality.data.WattsenseApi
import com.example.vitality.data.RetrofitClient
import com.example.vitality.data.WeatherRepository
import com.example.vitality.data.RoomSensorIds
import com.example.vitality.data.roomSensorIds
import com.example.vitality.data.activeZones
import com.example.vitality.data.Spmv
import com.example.vitality.data.firebase.FirebaseRepository
import com.example.vitality.data.firebase.FirebaseRepository.OfficeSnapshot
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.round

/**
 * FGS "dataSync"
 *  - PRESE: 1 sample/minuto esattamente su hh:mm:00 (count >5W) → flush a cambio ora
 *  - AMBIENTALI: 1 snapshot ogni 10' esattamente su hh:(00,10,20,...) → chiavi day/HH:mm
 *  - RETENTION: cleanup giornaliero (03:10) con keep=90 giorni
 */
class VitalityAggregationService : Service() {

    companion object {
        private const val TAG = "VitalityAggService"
        private const val NOTIF_CHANNEL_ID = "vitality_agg"
        private const val NOTIF_ID = 1001

        private const val W_THRESHOLD_W = 5.0
        private const val ENV_RETENTION_DAYS = 90
        private const val CLEANUP_HH_MM = "03:10"

        fun start(context: Context) {
            val i = Intent(context, VitalityAggregationService::class.java)
            ContextCompat.startForegroundService(context, i)
        }
    }

    // ---- Infra
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var firebaseRepo: FirebaseRepository
    private lateinit var smartPlugRepo: SmartPlugRepository
    private lateinit var wattsense: WattsenseApi
    private lateinit var weatherRepo: WeatherRepository

    // ---- Stato aggregazione prese
    private data class PlugAgg(var hourBucket: String, var countAbove5: Int)
    private val plugAggByName: MutableMap<String, PlugAgg> = mutableMapOf()

    // ---- TZ & scheduling helpers
    private val tz: TimeZone = TimeZone.getTimeZone("Europe/Rome")
    private fun isWorkingHours(nowMs: Long = System.currentTimeMillis()): Boolean {
        val c = Calendar.getInstance(tz).apply { timeInMillis = nowMs }
        val h = c.get(Calendar.HOUR_OF_DAY)
        return h in 8..19 // 08:00–19:59
    }
    private suspend fun delayUntil(tsTargetMs: Long) {
        val now = System.currentTimeMillis()
        val d = tsTargetMs - now
        if (d > 0) delay(d) else yield()
    }
    private fun nextMinuteBoundaryMs(nowMs: Long = System.currentTimeMillis()): Long {
        val c = Calendar.getInstance(tz).apply { timeInMillis = nowMs }
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        c.add(Calendar.MINUTE, 1)
        return c.timeInMillis
    }
    private fun nextTenMinuteBoundaryMs(nowMs: Long = System.currentTimeMillis()): Long {
        val c = Calendar.getInstance(tz).apply { timeInMillis = nowMs }
        val m = c.get(Calendar.MINUTE)
        val add = (10 - (m % 10)) % 10
        c.add(Calendar.MINUTE, if (add == 0) 10 else add)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate()")

        // Foreground ASAP (fix crash 5s rule)
        ensureNotifChannel(this, NOTIF_CHANNEL_ID, "Vitality Aggregation", "Servizio di aggregazione/sync dati")
        val notif = buildForegroundNotification(
            channelId = NOTIF_CHANNEL_ID,
            title = "Raccolta dati in esecuzione",
            text = "Sincronizzazione ambientali e prese"
        )
        try {
            startForeground(NOTIF_ID, notif)
            Log.i(TAG, "startForeground OK")
        } catch (fna: Exception) {
            Log.e(TAG, "startForeground FALLITA: ${fna.message}", fna)
            stopSelf(); return
        }

        // Init leggeri dopo il foreground
        try {
            if (FirebaseApp.getApps(this).isEmpty()) {
                FirebaseApp.initializeApp(this)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Firebase init error: ${t.message}", t)
        }

        val db = try { FirebaseDatabase.getInstance() } catch (e: Exception) {
            Log.e(TAG, "FirebaseDatabase.getInstance() FAILED: ${e.message}", e); null
        } ?: run { stopSelf(); return }

        firebaseRepo = FirebaseRepository(db)
        smartPlugRepo = SmartPlugRepository()
        wattsense = RetrofitClient.create(cacheDir)
        weatherRepo = WeatherRepository()

        // Avvio loop prese + ambientali (sincronizzati all'orario)
        startSmartPlugsLoopSynced()
        startEnvironmentLoopSynced()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand(startId=$startId, flags=$flags)")
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy() flushing pending hourly summaries…")
        flushAllPlugsSafely()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /* ============================================================
     *                     LOOP PRESE (tick al minuto)
     * ============================================================ */
    private fun startSmartPlugsLoopSynced() {
        scope.launch {
            var nextTick = nextMinuteBoundaryMs()
            while (isActive) {
                delayUntil(nextTick)
                val wakeLag = System.currentTimeMillis() - nextTick
                if (abs(wakeLag) > 1_000) {
                    Log.w(TAG, "⏱️ drift MINUTE wakeLag=${wakeLag}ms → riallineo al prossimo tick")
                }

                val tsSample = nextTick
                val currentHourBucket = FirebaseRepository.hourlyBucketId(tsSample)

                // Fuori orario → salta tutta la logica di scrittura
                if (!isWorkingHours(tsSample)) {
                    nextTick = nextMinuteBoundaryMs(tsSample)
                    continue
                }

                try {
                    for (room in activeZones) {
                        val statuses = smartPlugRepo.fetchPlugsForRoom(room)
                        statuses.forEach { st ->
                            val name = st.name
                            val agg = plugAggByName.getOrPut(name) {
                                PlugAgg(hourBucket = currentHourBucket, countAbove5 = 0)
                            }

                            if (agg.hourBucket != currentHourBucket) {
                                flushSinglePlug(name, agg, tsSample)
                                agg.hourBucket = currentHourBucket
                                agg.countAbove5 = 0
                            }

                            if (st.online && st.apower > W_THRESHOLD_W) {
                                agg.countAbove5 += 1
                            }

                            Log.d(
                                TAG,
                                "[${room}] ${name} @${currentHourBucket} W=${round(st.apower * 10) / 10.0} " +
                                        ">5=${st.apower > W_THRESHOLD_W} cnt=${agg.countAbove5}"
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "⚡ poll prese FAILED: ${e.message}", e)
                }

                nextTick = nextMinuteBoundaryMs(tsSample)
            }
        }
    }

    private fun flushSinglePlug(plugName: String, state: PlugAgg, commitTs: Long) {
        try {
            firebaseRepo.setPlugHourlySummary(
                plugName = plugName,
                hourBucket = state.hourBucket,
                countAbove5 = state.countAbove5,
                commitTsMs = commitTs
            )
            Log.i(TAG, "FLUSH plug=$plugName hour=${state.hourBucket} count> $W_THRESHOLD_W W = ${state.countAbove5}")
        } catch (e: Exception) {
            Log.e(TAG, "flushSinglePlug($plugName) FAILED: ${e.message}", e)
        }
    }

    private fun flushAllPlugsSafely() {
        val now = System.currentTimeMillis()
        plugAggByName.forEach { (name, agg) -> flushSinglePlug(name, agg, now) }
        plugAggByName.clear()
    }

    /* ============================================================
     *                 LOOP AMBIENTALI (tick a 10 minuti)
     * ============================================================ */
    private fun startEnvironmentLoopSynced() {
        scope.launch {
            var nextTick10 = nextTenMinuteBoundaryMs()
            while (isActive) {
                delayUntil(nextTick10)
                val wakeLag = System.currentTimeMillis() - nextTick10
                if (abs(wakeLag) > 1_000) {
                    Log.w(TAG, "⏱️ drift TEN-MIN wakeLag=${wakeLag}ms → riallineo al prossimo tick")
                }

                val tsTick = nextTick10
                val day = FirebaseRepository.dayBucketId(tsTick)
                val key10 = FirebaseRepository.tenMinKey(tsTick)

                if (!isWorkingHours(tsTick)) {
                    // Salta completamente fuori orario
                    nextTick10 = nextTenMinuteBoundaryMs(tsTick)
                    continue
                }

                if (roomSensorIds.isEmpty()) {
                    Log.w(TAG, "roomSensorIds è VUOTA: nessuna stanza scritta.")
                } else {
                    Log.i(TAG, "Environment cycle @ $day/$key10 → rooms=${roomSensorIds.keys}")
                }

                val tOutdoor = try {
                    weatherRepo.fetchAnconaOutdoorTempC()
                } catch (e: Exception) {
                    Log.e(TAG, "🌤 fetch outdoor FAILED: ${e.message}")
                    null
                }

                for ((room, ids) in roomSensorIds) {
                    try {
                        val snap = buildOfficeSnapshot(room, ids, tOutdoor, tsTick)
                        firebaseRepo.pushOfficeSnapshot(room, day, key10, snap)
                        Log.i(TAG, "🏢 [$room] $day/$key10 → T=${snap.t_amb} RH=${snap.rh} PMV=${snap.spmv}")
                    } catch (e: Exception) {
                        Log.e(TAG, "🏢 [$room] snapshot FAILED: ${e.message}", e)
                    }
                }

                if (key10 == CLEANUP_HH_MM) {
                    for (room in roomSensorIds.keys) {
                        runCatching { firebaseRepo.cleanupOldOfficeDays(room, ENV_RETENTION_DAYS) }
                            .onFailure { Log.e(TAG, "cleanup [$room] FAILED: ${it.message}", it) }
                    }
                }

                nextTick10 = nextTenMinuteBoundaryMs(tsTick)
            }
        }
    }

    private suspend fun buildOfficeSnapshot(
        room: String,
        ids: RoomSensorIds,
        tOutdoor: Double?,
        tsTick: Long
    ): OfficeSnapshot = withContext(Dispatchers.IO) {
        suspend fun getLatestDouble(pid: String?, label: String): Double? {
            if (pid.isNullOrBlank()) {
                Log.w(TAG, "[$room] $label: propertyId NULL/blank")
                return null
            }
            return runCatching {
                val resp = wattsense.getProperty(
                    deviceId = ids.deviceId,
                    propertyIdOrSlug = pid,
                    includeHistory = false
                )
                resp.scaledPayload ?: (resp.payload as? Number)?.toDouble()
            }.onFailure {
                Log.e(TAG, "[$room] getProperty($label) dev=${ids.deviceId} pid=$pid ❌ ${it.message}")
            }.getOrNull()
        }

        val t   = getLatestDouble(ids.temperatureId,     "T")
        val rh  = getLatestDouble(ids.humidityId,        "RH")
        val sp = if (t != null && rh != null && tOutdoor != null) {
            Spmv.compute(t, rh, tOutdoor)
        } else null

        return@withContext OfficeSnapshot(
            timestamp = tsTick,
            t_amb = t,
            rh = rh,
            spmv = sp?.pmv,
            spmv2 = sp?.pmv2,
            spmv3 = sp?.pmv3,
            clo_pred = sp?.cloPred
        )
    }

    /* ============================================================
     *                          UTILS
     * ============================================================ */
    private fun ensureNotifChannel(context: Context, id: String, name: String, desc: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(id, name, NotificationManager.IMPORTANCE_LOW).apply {
                description = desc
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
            Log.d(TAG, "NotificationChannel ensured (id=$id)")
        }
    }

    private fun buildForegroundNotification(
        channelId: String,
        title: String,
        text: String
    ): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentPi = launchIntent?.let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            )
        }

        val smallIconRes = resolveSmallIconRes()
        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(smallIconRes)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .apply { if (contentPi != null) setContentIntent(contentPi) }
            .build()
    }

    private fun resolveSmallIconRes(): Int {
        val appIcon = applicationInfo.icon
        return if (appIcon != 0) appIcon else android.R.drawable.stat_sys_download_done
    }
}
