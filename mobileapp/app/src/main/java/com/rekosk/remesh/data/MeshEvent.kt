package com.rekosk.remesh.data

/**
 * Something worth telling the user about, emitted once as it happens.
 *
 * Distinct from the repository's StateFlows: those describe what *is*, and a
 * notification must fire on the transition, not on every recomposition.
 */
sealed interface MeshEvent {

    data class MessageReceived(
        val conversationId: String,
        /** Channel name, or the contact's name for a direct message. */
        val conversationTitle: String,
        val author: String,
        val text: String,
        val isChannel: Boolean,
        /**
         * True when the node had queued this while the app was not connected, so it
         * arrives in a burst during the handshake rather than live.
         */
        val whileDisconnected: Boolean,
    ) : MeshEvent

    data class NewContact(val name: String) : MeshEvent

    /** The node's contact table is full; it cannot learn about new nodes. */
    data object ContactsFull : MeshEvent
}
