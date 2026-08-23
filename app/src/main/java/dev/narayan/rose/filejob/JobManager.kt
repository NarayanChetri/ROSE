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

    fun cancelJob(jobId: String) {
        cancelledJobs.add(jobId)
        removeJob(jobId)
    }

    fun isCancelled(jobId: String): Boolean = cancelledJobs.contains(jobId)

    fun updateJob(job: FileJob) {
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