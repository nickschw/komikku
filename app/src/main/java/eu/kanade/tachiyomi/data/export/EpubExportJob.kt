package eu.kanade.tachiyomi.data.export

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import exh.log.xLogE
import kotlinx.coroutines.CancellationException
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.kmk.KMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Exports series to EPUB in the background, one after another, so a long series
 * isn't cut off when the downloader finishes and the app goes to the background.
 */
class EpubExportJob(context: Context, workerParams: WorkerParameters) : CoroutineWorker(context, workerParams) {

    private var currentTitle: String? = null

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = applicationContext.notificationBuilder(Notifications.CHANNEL_DOWNLOADER_PROGRESS) {
            setContentTitle(applicationContext.stringResource(KMR.strings.epub_export_notification_title))
            currentTitle?.let { setContentText(it) }
            setSmallIcon(android.R.drawable.stat_sys_download)
            setOngoing(true)
            setOnlyAlertOnce(true)
        }.build()
        return ForegroundInfo(
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    override suspend fun doWork(): Result {
        setForegroundSafely()

        val mangas: List<Manga> = if (inputData.getBoolean(KEY_ALL, false)) {
            Injekt.get<GetFavorites>().await()
        } else {
            val getManga = Injekt.get<GetManga>()
            inputData.getLongArray(KEY_MANGA_IDS)?.distinct()?.mapNotNull { getManga.await(it) }.orEmpty()
        }

        val exporter = EpubExporter(applicationContext)
        mangas.forEach { manga ->
            currentTitle = manga.title
            setForegroundSafely()
            try {
                exporter.export(manga)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                xLogE("EPUB export failed for ${manga.title}", e)
            }
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "EpubExport"
        private const val KEY_MANGA_IDS = "manga_ids"
        private const val KEY_ALL = "all"
        private const val NOTIFICATION_ID = -2101

        /** Queues an export of the given series after any export already running. */
        fun export(context: Context, mangaIds: Collection<Long>) {
            if (mangaIds.isEmpty()) return
            enqueue(context, workDataOf(KEY_MANGA_IDS to mangaIds.toLongArray()))
        }

        /** Queues an export of every library series that has downloaded chapters. */
        fun exportAll(context: Context) {
            enqueue(context, workDataOf(KEY_ALL to true))
        }

        private fun enqueue(context: Context, data: Data) {
            val request = OneTimeWorkRequestBuilder<EpubExportJob>()
                .addTag(TAG)
                .setInputData(data)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(TAG, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
