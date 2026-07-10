package com.rekosk.remesh.data

import com.rekosk.remesh.ble.MeshFrame

/**
 * What a heard repeat's path hash resolves to, once matched against the contacts
 * the node knows about.
 *
 * A path hash is only the first byte or two of a public key, so it names a node
 * about as well as a house number names a street: usually enough, occasionally
 * ambiguous, sometimes nobody we know.
 */
sealed interface RepeaterName {

    /** Exactly one known contact starts with this hash. */
    data class Known(val name: String) : RepeaterName

    /** No contact starts with this hash. */
    data object Unknown : RepeaterName

    /** Several contacts share this prefix; the user picks between them. */
    data class Duplicated(val names: List<String>) : RepeaterName
}

/** The row a "heard repeats" screen draws, hash and resolution together. */
data class ResolvedRepeat(
    val hashHex: String,
    val name: RepeaterName,
    val snr: Float,
    val rssi: Int,
) {
    /** "Čingov 1", "Unknown (89)" or "Duplicated (89)". */
    val title: String
        get() = when (name) {
            is RepeaterName.Known -> name.name
            is RepeaterName.Unknown -> "Unknown ($hashHex)"
            is RepeaterName.Duplicated -> "Duplicated ($hashHex)"
        }

    /** How many contacts the hash matched, phrased as the reference app phrases it. */
    val subtitle: String
        get() = when (name) {
            is RepeaterName.Known -> "1 known repeater"
            is RepeaterName.Unknown -> "No known repeater"
            is RepeaterName.Duplicated -> "${name.names.size} known repeaters"
        }

    val isAmbiguous: Boolean get() = name is RepeaterName.Duplicated
}

/**
 * Matches [hashHex] against the leading bytes of every known contact's public key.
 *
 * Comparison is on the hex prefix rather than on bytes so it works whatever path
 * hash size the mesh runs at: a 2-byte mode yields a four-character hash, and a
 * contact matches when its key starts with those same four characters.
 */
fun resolveRepeaterName(hashHex: String, contacts: List<MeshFrame.Contact>): RepeaterName {
    val prefix = hashHex.lowercase()
    val matches = contacts
        .filter { contact ->
            contact.publicKey.joinToString("") { "%02x".format(it) }.startsWith(prefix)
        }
        .map { it.name.ifBlank { "(unnamed)" } }
        .distinct()

    return when (matches.size) {
        0 -> RepeaterName.Unknown
        1 -> RepeaterName.Known(matches.first())
        else -> RepeaterName.Duplicated(matches.sorted())
    }
}
