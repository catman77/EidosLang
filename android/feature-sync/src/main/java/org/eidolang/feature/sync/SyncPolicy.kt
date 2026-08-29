package org.eidolang.feature.sync

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

object SyncPolicy {
    const val PERIODIC_HEAD_REFRESH="eidolang-head-refresh"
    fun schedule(context:Context) {
        val constraints=Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val periodic=PeriodicWorkRequestBuilder<HeadRefreshWorker>(6,TimeUnit.HOURS)
            .setConstraints(constraints).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC_HEAD_REFRESH,ExistingPeriodicWorkPolicy.UPDATE,periodic)
    }
}

class HeadRefreshWorker(ctx:Context,params:WorkerParameters):Worker(ctx,params) {
    override fun doWork():Result {
        // Repository/transport wiring is R18. R17 only owns scheduling policy.
        return Result.success()
    }
}
