package dev.veristaller.domain.model

sealed interface Outcome {
    data class Success(val details: SwapDetails) : Outcome
    data class Failed(val error: JobError) : Outcome
    data class DryRun(val commands: List<String>) : Outcome
}

data class JobUiState(
    val running: Boolean = false,
    val jobId: String? = null,
    val packageName: String = "",
    val step: StepId? = null,
    val completed: Set<StepId> = emptySet(),
    val title: String = "",
    val detail: String = "",
    val progress: Float? = null,
    val log: List<JobLogLine> = emptyList(),
    val outcome: Outcome? = null,
)
