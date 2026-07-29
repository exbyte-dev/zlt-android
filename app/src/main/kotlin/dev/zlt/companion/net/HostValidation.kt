package dev.zlt.companion.net

/**
 * Validates and normalises the router address the user types.
 *
 * This is the second half of the cleartext story. `network_security_config.xml`
 * denies cleartext by default and re-permits it for an enumerated list of
 * private gateway addresses; this rejects anything outside the private ranges
 * at the point of entry, so the app cannot be aimed at a public host even by
 * hand. The two together give "cleartext to private addresses only" — which
 * Android's config format cannot express on its own, having no notion of a
 * CIDR range.
 *
 * IPv4 literals only. The router is reached by address on the LAN, there is no
 * name to resolve, and accepting hostnames would mean accepting names that
 * resolve to public addresses.
 */
object HostValidation {

    /** RFC1918 plus the CGNAT range, as (first octet, predicate) rules. */
    private fun isPrivate(octets: List<Int>): Boolean {
        val (a, b) = octets
        return when {
            a == 10 -> true                       // 10.0.0.0/8
            a == 172 && b in 16..31 -> true       // 172.16.0.0/12
            a == 192 && b == 168 -> true          // 192.168.0.0/16
            a == 100 && b in 64..127 -> true      // 100.64.0.0/10, CGNAT
            else -> false
        }
    }

    /**
     * Strips a scheme, surrounding whitespace, and any trailing path, leaving
     * the bare address. Tolerant on input because people paste what their
     * browser shows them.
     */
    fun normalize(input: String): String =
        input.trim()
            .removePrefix("http://")
            .removePrefix("https://")
            .substringBefore('/')
            .trim()

    /**
     * @return null when the (already normalised) host is usable, otherwise a
     *   plain-language reason suitable for showing under the text field.
     */
    fun reasonInvalid(host: String): String? {
        if (host.isEmpty()) return "Enter your router's address, for example 192.168.0.1"

        val parts = host.split('.')
        if (parts.size != 4) {
            return "That doesn't look like a router address. Use four numbers " +
                "separated by dots, like 192.168.0.1"
        }

        val octets = parts.map { it.toIntOrNull() ?: -1 }
        if (octets.any { it !in 0..255 }) {
            return "Each part of the address has to be a number from 0 to 255"
        }
        if (parts.any { it.length > 1 && it.startsWith("0") }) {
            return "Remove the leading zeros — write 192.168.0.1, not 192.168.000.001"
        }
        if (!isPrivate(octets)) {
            return "That is a public address. This app only connects to routers " +
                "on your own network, so the address has to start with 192.168, " +
                "10., or 172.16 through 172.31"
        }
        return null
    }

    fun isValid(host: String): Boolean = reasonInvalid(host) == null
}
