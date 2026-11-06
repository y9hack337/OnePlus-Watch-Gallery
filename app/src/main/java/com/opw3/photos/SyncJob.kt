package com.opw3.photos

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Срабатывает, когда меняется MediaStore (поставили/сняли лайк в галерее OnePlus).
 * Работает без постоянного сервиса и без root.
 */
class SyncJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartJob(params: JobParameters): Boolean {
        Log.i("OPW3Sync", "SyncJob: изменение медиатеки (${params.triggeredContentUris?.size ?: 0} uri)")
        scope.launch {
            try {
                WatchSync.sync(applicationContext, "media")
            } finally {
                jobFinished(params, false)
                // Задания с content-триггером одноразовые — взводим заново
                schedule(applicationContext)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true

    companion object {
        private const val ID = 4201

        fun schedule(ctx: Context) {
            val js = ctx.getSystemService(JobScheduler::class.java)
            val info = JobInfo.Builder(ID, ComponentName(ctx, SyncJob::class.java))
                .addTriggerContentUri(
                    JobInfo.TriggerContentUri(
                        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
                        JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS
                    )
                )
                .setTriggerContentUpdateDelay(700)
                .setTriggerContentMaxDelay(4000)
                .build()
            js.schedule(info)
        }
    }
}
