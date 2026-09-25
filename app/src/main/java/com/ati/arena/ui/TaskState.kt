package com.ati.arena.ui

/** What the overlay is busy with. Drives the status pill and the panel's buttons. */
sealed interface TaskState {
    data object Idle : TaskState

    /** A probe run; [round] is 0 while it is starting up. */
    data class Probe(val round: Int, val maxRounds: Int, val hits: Int) : TaskState

    /** An arithmetic-title cleanup sweep. */
    data class Cleanup(val archived: Int) : TaskState

    /** The page is being reloaded because the reply errored or came back empty. */
    data object Recovery : TaskState
}
