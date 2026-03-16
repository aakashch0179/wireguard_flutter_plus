package orban.group.wireguard_flutter.config

import org.amnezia.awg.config.Config
import java.io.BufferedReader
import java.io.StringReader

object WgQuickParser {
    @Throws(Exception::class)
    fun parse(content: String): Config {
        val reader = BufferedReader(StringReader(content))
        return Config.parse(reader)
    }
}
