package com.steve.mcpodface.backfill

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.steve.mcpodface.data.prefs.GlobalSettings
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/** Archive backfill every 6 hours (PLAN.md §7.4). Wi-Fi by default; optionally only while charging. */
@HiltWorker
class BackfillWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val runner: BackfillRunner,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        runner.run()
        return Result.success()
    }

    companion object {
        const val PERIODIC_WORK = "backfill"
        const val NOW_WORK = "backfill-now"

        fun constraints(s: GlobalSettings): Constraints = Constraints.Builder()
            .setRequiredNetworkType(if (s.backfillWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresCharging(s.backfillChargingOnly)
            .setRequiresBatteryNotLow(true)
            .build()

        /** Keeps the periodic work in line with the settings; called whenever they change. */
        fun schedule(workManager: WorkManager, s: GlobalSettings) {
            if (!s.backfillEnabled) {
                workManager.cancelUniqueWork(PERIODIC_WORK)
                return
            }
            workManager.enqueueUniquePeriodicWork(
                PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<BackfillWorker>(6, TimeUnit.HOURS)
                    .setConstraints(constraints(s))
                    .setInitialDelay(15, TimeUnit.MINUTES)
                    .build(),
            )
        }

        /** "Backfill now" from the archive UI: one run as soon as the constraints allow. */
        fun runNow(workManager: WorkManager, s: GlobalSettings) {
            workManager.enqueueUniqueWork(
                NOW_WORK,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<BackfillWorker>().setConstraints(constraints(s)).build(),
            )
        }
    }
}
