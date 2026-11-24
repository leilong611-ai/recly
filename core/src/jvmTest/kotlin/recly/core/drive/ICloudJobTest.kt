@file:OptIn(ExperimentalTime::class)

package recly.core.drive

import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import recly.core.recording.MetaWriter
import recly.core.storage.CloudStorage
import recly.core.storage.ICloudFiles
import recly.core.storage.ICloudSettlementChecks
import recly.core.storage.UbiquityContainer
import recly.core.storage.UbiquityFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.runBlocking
import recly.core.job.Executor
import recly.core.job.JobStatus
import recly.core.job.JobStore
import recly.core.job.StepStatus
import recly.core.message.CoreMessage
import recly.core.message.CoreMessageRef
import recly.core.model.Step
import recly.core.storage.StorageKind
import recly.core.testing.START
import recly.core.testing.testWorkflow

/**
 * docs/03 "Storage location", end to end: a job bound for iCloud runs with no Google account at all, waits
 * — spending no attempt — while the system uploads, and is done once iCloud holds the recording.
 */
class ICloudJobTest {
    @Test
    fun `an iCloud job needs no Drive, waits for the system's upload, then is done`() = runBlocking<Unit> {
        val h = DriveHarness(
            partCount = 2,
            partBytes = DriveHarness.SMALL_BYTES,
            tokens = ScriptedTokenProvider(),
            icloud = true,
        )
        h.register()
        val store = JobStore(h.db, h.deps)
        // Drive reads as disconnected: only iCloud is there to upload to.
        store.disconnectDrive()
        val access = DriveJobAccess(h.deps, store)
        val executor = Executor(
            h.deps, store, h.recordings, mapOf(DriveUploadRunner.TYPE to h.runner),
            prepare = { access.prepare() },
            requireAccess = { access.requireAccess(it) },
        )
        val workflow = testWorkflow(steps = listOf(Step.DriveUpload(id = "up", store = StorageKind.ICLOUD)))
        val job = assertNotNull(store.enqueue(h.recordingId, workflow, START))

        executor.runDueJobs(START)

        val waiting = assertNotNull(store.get(job.id))
        assertEquals(JobStatus.WAITING, waiting.status)
        val step = store.stepsOf(job.id).single()
        assertEquals(StepStatus.PENDING, step.status)
        assertEquals(0, step.attempts, "waiting for iCloud spends nothing")
        assertEquals(CoreMessage.ICLOUD_UPLOADING, CoreMessageRef.parse(step.lastError!!)!!.message)
        assertTrue(h.drive.requests.isEmpty())

        h.container!!.settle()
        executor.runDueJobs(START + 31.seconds)

        assertEquals(JobStatus.DONE, assertNotNull(store.get(job.id)).status)
        assertEquals(setOf(h.recordingId), store.uploadedRecordings())
    }

    @Test
    fun `one stuck preflight lets the next job finish and both resume without duplicate parts`() = runBlocking<Unit> {
        val h = DriveHarness(partCount = 2, partBytes = DriveHarness.SMALL_BYTES, icloud = true)
        h.register()
        val original = assertNotNull(h.recordings.get(h.recordingId)).meta
        val second = original.copy(recordingId = "01J9SHORT00123456789ABCDEF", parts = emptyList())
        val base = MetaWriter.baseName(second)
        val dir = assertNotNull(h.dir.parent) / base
        h.fs.createDirectories(dir)
        val part = original.parts.first()
        val shortPart = part.copy(file = MetaWriter.partFileName(base, 1, part.track))
        h.fs.copy(h.dir / part.file, dir / shortPart.file)
        val short = second.copy(parts = listOf(shortPart), durationSec = shortPart.durationSec)
        MetaWriter.write(h.fs, dir, short)
        h.recordings.create(short, dir)

        val store = JobStore(h.db, h.deps)
        val flow = testWorkflow(steps = listOf(Step.DriveUpload(id = "up", store = StorageKind.ICLOUD)))
        val firstJob = assertNotNull(store.enqueue(h.recordingId, flow, START))
        val nextJob = assertNotNull(store.enqueue(short.recordingId, flow, START + 1.seconds))
        fun executor(runner: DriveUploadRunner) =
            Executor(h.deps, store, h.recordings, mapOf(DriveUploadRunner.TYPE to runner))
        executor(h.runner).runDueJobs(START + 2.seconds)
        val container = assertNotNull(h.container)
        container.settle()
        val versions = container.entries.mapValues { it.value.modifiedAt }
        val entered = CompletableDeferred<Continuation<Unit>>()
        val hanging = object : UbiquityContainer by container {
            override suspend fun file(path: String): UbiquityFile? {
                if (path.endsWith("/${part.file}")) {
                    suspendCoroutine<Unit> { check(entered.complete(it)) }
                }
                return container.file(path)
            }
        }
        val files = ICloudFiles(hanging, h.deps, ICloudSettlementChecks(h.deps.io, 250.milliseconds))
        val storage = CloudStorage(h.api, files, null)
        val runner = DriveUploadRunner(storage, FolderResolver(storage, h.store, h.deps), h.store, h.recordings, h.deps)
        val pass = async { executor(runner).runDueJobs(START + 60.seconds) }
        val blocked = withTimeout(5.seconds) { entered.await() }
        try {
            withTimeout(5.seconds) { pass.await() }
            assertEquals(JobStatus.WAITING, store.get(firstJob.id)?.status)
            assertEquals(JobStatus.DONE, store.get(nextJob.id)?.status)
        } finally {
            blocked.resume(Unit)
        }

        // Recreate the executor over the persisted step states with the native service healthy.
        executor(h.runner).runDueJobs(START + 600.seconds)
        assertEquals(JobStatus.DONE, store.get(firstJob.id)?.status)
        assertEquals(versions, container.entries.mapValues { it.value.modifiedAt })
    }

    /**
     * Disconnecting Google Drive clears Drive's completed jobs and leaves iCloud's: a completed iCloud
     * job's output is where playback finds the file ids once the local audio is swept.
     */
    @Test
    fun `disconnecting Drive keeps the completed iCloud jobs`() = runBlocking<Unit> {
        val h = DriveHarness(partCount = 1, partBytes = DriveHarness.SMALL_BYTES, icloud = true)
        h.register()
        val store = JobStore(h.db, h.deps)
        val icloud = assertNotNull(store.enqueue(
            h.recordingId,
            testWorkflow(id = "01J9C10VDW0RKF10W000000000", steps = listOf(Step.DriveUpload(id = "up", store = StorageKind.ICLOUD))),
            START,
        ))
        val drive = assertNotNull(store.enqueue(h.recordingId, testWorkflow(id = "01J9DR1VEW0RKF10W000000000"), START))
        store.updateJob(icloud.id, JobStatus.DONE, null, START)
        store.updateJob(drive.id, JobStatus.DONE, null, START)

        store.disconnectDrive()

        assertEquals(JobStatus.DONE, assertNotNull(store.get(icloud.id)).status)
        assertTrue(store.stepsOf(icloud.id).isNotEmpty())
        assertEquals(null, store.get(drive.id))
    }
}
