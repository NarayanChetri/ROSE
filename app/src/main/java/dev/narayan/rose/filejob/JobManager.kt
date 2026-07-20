package dev.narayan.rose.filejob

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

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

    private val cancelledJobs = mutableSetOf<String>()

    fun cancelJob(jobId: String) {
        cancelledJobs.add(jobId)
        removeJob(jobId)
    }

    fun isCancelled(jobId: String): Boolean = cancelledJobs.contains(jobId)

    fun updateJob(job: FileJob) {
        val current = _activeJobs.value.toMutableMap()
        current[job.id] = job.copy()
        _activeJobs.value = current
    }

    fun removeJob(jobId: String) {
        val current = _activeJobs.value.toMutableMap()
        current.remove(jobId)
        _activeJobs.value = current
        cancelledJobs.remove(jobId)
    }

    /** Marks a job finished, tells anyone listening the outcome, and cleans it up. */
    fun completeJob(job: FileJob, success: Boolean, error: String? = null) {
        _jobEvents.tryEmit(JobResult(job.id, job.copy(), success, error))
        removeJob(job.id)
    }
}
