package orban.group.wireguard_flutter.config

import java.io.StringReader
import java.io.BufferedReader
import org.amnezia.awg.config.Config


object WgQuickConfig {

    fun toWgQuickString(config: Config): String {
        val sb = StringBuilder()
        val iface = config.`interface`

        // Safe way to get privateKey string using reflection fallback
        val privateKeyString = try {
            val field = iface.javaClass.getDeclaredField("privateKey")
            field.isAccessible = true
            field.get(iface).toString()
        } catch (e: Exception) {
            ""
        }

        sb.append("[Interface]\n")
        sb.append("PrivateKey = $privateKeyString\n")

        iface.addresses.forEach {
            sb.append("Address = $it\n")
        }

        iface.listenPort?.let {
            sb.append("ListenPort = $it\n")
        }

        iface.dnsServers.forEach {
            sb.append("DNS = $it\n")
        }

        iface.mtu?.let {
            sb.append("MTU = $it\n")
        }

        config.peers.forEach { peer ->
            sb.append("\n[Peer]\n")
            sb.append("PublicKey = ${peer.publicKey}\n")
            peer.preSharedKey?.let {
                sb.append("PresharedKey = $it\n")
            }
            peer.allowedIps.forEach {
                sb.append("AllowedIPs = $it\n")
            }
            peer.endpoint?.let {
                sb.append("Endpoint = $it\n")
            }
            peer.persistentKeepalive?.let {
                sb.append("PersistentKeepalive = $it\n")
            }
        }

        return sb.toString()
    }

    // fun parse(configText: String): Config {
    //     val reader = configText.reader().buffered()
    //     return Config.parse(reader)
    // }
    fun parse(configText: String): Config {
        val reader = BufferedReader(StringReader(configText))
        return Config.parse(reader)
    }
}
