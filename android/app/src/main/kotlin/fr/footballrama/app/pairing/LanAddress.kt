package fr.footballrama.app.pairing

import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Locale

/**
 * Choix de l'adresse d'ecoute : IPv4 PRIVEE (10/8, 172.16/12, 192.168/16) d'une interface Ethernet ou Wi-Fi
 * active. Sans permission Android (NetworkInterface, pas de ConnectivityManager/WifiManager).
 */
object LanAddress {

    data class Candidate(
        val interfaceName: String,
        val address: InetAddress,
        val up: Boolean = true,
        val loopback: Boolean = false,
        val virtual: Boolean = false,
        val pointToPoint: Boolean = false,
    )

    /** Interfaces exclues : VPN, donnees mobiles, Wi-Fi Direct, conteneurs. */
    private val EXCLUDED_PREFIXES = listOf("tun", "tap", "ppp", "rmnet", "ccmni", "wwan", "p2p", "dummy", "docker", "veth", "ipsec", "clat", "lo")

    fun isPrivateIpv4(a: Inet4Address): Boolean {
        val b = a.address
        val o1 = b[0].toInt() and 0xFF
        val o2 = b[1].toInt() and 0xFF
        return o1 == 10 || (o1 == 172 && o2 in 16..31) || (o1 == 192 && o2 == 168)
    }

    /** Ethernet d'abord (plus stable sur un boitier), puis Wi-Fi, puis le reste. null si aucune adresse convenable. */
    fun choose(candidates: List<Candidate>): Inet4Address? = candidates
        .asSequence()
        .filter { it.up && !it.loopback && !it.virtual && !it.pointToPoint }
        .filter { c -> EXCLUDED_PREFIXES.none { c.interfaceName.lowercase(Locale.ROOT).startsWith(it) } }
        .mapNotNull { c -> (c.address as? Inet4Address)?.takeIf(::isPrivateIpv4)?.let { c.interfaceName to it } }
        .sortedBy { (name, _) -> rank(name.lowercase(Locale.ROOT)) }
        .firstOrNull()?.second

    private fun rank(name: String) = when {
        name.startsWith("eth") || name.startsWith("en") -> 0
        name.startsWith("wlan") || name.startsWith("wl") -> 1
        else -> 2
    }

    /** Adresse du reseau local de l'appareil, null si aucun reseau prive (hors ligne, donnees mobiles, VPN seul). */
    fun find(): Inet4Address? {
        val fromInterfaces = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().flatMap { ni ->
                val up = runCatching { ni.isUp }.getOrDefault(false)
                val loop = runCatching { ni.isLoopback }.getOrDefault(true)
                val p2p = runCatching { ni.isPointToPoint }.getOrDefault(false)
                ni.inetAddresses.toList().map { Candidate(ni.name.orEmpty(), it, up, loop, ni.isVirtual, p2p) }
            }
        }.getOrDefault(emptyList())
        choose(fromInterfaces)?.let { return it }
        // Repli : adresse source de la route par defaut (UDP "connecte" : aucun paquet n'est envoye).
        return runCatching {
            DatagramSocket().use { s ->
                s.connect(InetAddress.getByName("192.0.2.1"), 9)
                (s.localAddress as? Inet4Address)?.takeIf(::isPrivateIpv4)
            }
        }.getOrNull()
    }
}
