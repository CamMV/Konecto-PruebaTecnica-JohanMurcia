package com.konecto.series76.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.konecto.series76.core.ApplicationScope
import com.konecto.series76.domain.repository.NetworkMonitor
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Entry point the repositories use to ask for a sync (keeps them independent from WorkManager). */
interface SyncScheduler {
    fun requestSync()
}

/**
 * Three triggers for the outbox:
 *  1. right away, in the application scope, when there is connectivity;
 *  2. when connectivity comes back (NetworkMonitor listener);
 *  3. a unique WorkManager job with a CONNECTED constraint, which survives the app being killed.
 */
@Singleton
class SyncManager @Inject constructor(
    private val processor: OutboxProcessor,
    private val network: NetworkMonitor,
    private val workManager: WorkManager,
    @ApplicationScope private val scope: CoroutineScope,
) : SyncScheduler {

    fun start() {
        scope.launch { network.isOnline.filter { it }.collect { flushNow() } }
        enqueueWorker()
    }

    override fun requestSync() {
        enqueueWorker()
        if (network.isOnline.value) flushNow()
    }

    private fun flushNow() {
        scope.launch { processor.flush() }
    }

    private fun enqueueWorker() {
        val request = OneTimeWorkRequestBuilder<OutboxWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(OutboxWorker.NAME, ExistingWorkPolicy.KEEP, request)
    }
}

@HiltWorker
class OutboxWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val processor: OutboxProcessor,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = when (processor.flush()) {
        OutboxProcessor.Outcome.DONE -> Result.success()
        OutboxProcessor.Outcome.RETRY_LATER -> Result.retry()
    }

    companion object {
        const val NAME = "konecto-outbox"
    }
}
