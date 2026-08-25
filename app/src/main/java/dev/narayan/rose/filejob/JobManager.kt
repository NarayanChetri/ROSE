package dev.narayan.rose.filejob

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Result of a finished job, emitted once so screens (e.g. the offline-files
 * toggle in RoseViewModel) can react to success/failure without polling.
 */
data class JobResult(
    val jobId: String,
    val job: FileJob,
    val success: Boolean,
    val error: String? = null
)

object JobManager {
    private val _activeJobs = MutableStateFlow<Map<String, FileJob>>(emptyMap())
    val activeJobs: StateFlow<Map<String, FileJob>> = _activeJobs.asStateFlow()

    // Buffered so a listener that subscribes slightly after completion (e.g. a
    // Composable that just entered composition) doesn't miss the result.
    private val _jobEvents = MutableSharedFlow<JobResult>(extraBufferCapacity = 16)
    val jobEvents: SharedFlow<JobResult> = _jobEvents.asSharedFlow()

    // FileOperationRunner spawns a raw Thread per job, and cancelJob() is called
    // from the main thread while isCancelled() is polled from those job threads.
    // A plain HashSet here gives no happens-before guarantee that a cancel
    // written on the main thread is ever observed by a background job thread -
    // a cancel button press could be silently ignored for an in-flight job.
    // ConcurrentHashMap-backed set gives proper cross-thread visibility.
    private val cancelledJobs = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    // FIX: the previous version called removeJob(jobId) right after adding
    // jobId to cancelledJobs, and removeJob() itself does
    // cancelledJobs.remove(jobId) - so the id was added and then immediately
    // stripped back out again in the same synchronous call. isCancelled()
    // would go back to returning false before a background job thread ever
    // had a realistic chance to observe true, so Cancel silently did nothing
    // to the actual running operation - it only hid the progress card.
    //
    // Fix: cancelJob() only pulls the job out of the *visible* active-jobs
    // map (so the UI reacts immediately). It leaves the id in cancelledJobs
    // so isCancelled(jobId) keeps returning true for as long as the
    // background thread is still alive. cancelledJobs is only cleared once
    // that thread actually finishes and calls completeJob() -> removeJob().
    fun cancelJob(jobId: String) {
        cancelledJobs.add(jobId)
        _activeJobs.update { current -> current - jobId }
    }

    fun isCancelled(jobId: String): Boolean = cancelledJobs.contains(jobId)

    fun updateJob(job: FileJob) {
        // If the job was already cancelled, don't let a stray progress update
        // from the background thread (which hasn't noticed the cancellation
        // yet) resurrect it in the UI - the user already dismissed it.
        if (cancelledJobs.contains(job.id)) return

        // Multiple job threads can call this concurrently (overlapping jobs are
        // explicitly supported - see FileJobService). `update` does an atomic
        // compare-and-set loop, so there's no read-modify-write race between
        // callers dropping each other's updates the way a manual
        // `.value.toMutableMap(); ...; .value = current` would.
        _activeJobs.update { current -> current + (job.id to job.copy()) }
    }

    fun removeJob(jobId: String) {
        _activeJobs.update { current -> current - jobId }
        cancelledJobs.remove(jobId)
    }

    /** Marks a job finished, tells anyone listening the outcome, and cleans it up. */
    fun completeJob(job: FileJob, success: Boolean, error: String? = null) {
        _jobEvents.tryEmit(JobResult(job.id, job.copy(), success, error))
        removeJob(job.id)
    }
}