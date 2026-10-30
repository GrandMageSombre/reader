package com.modu.reader.source

import com.modu.reader.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * 局域网自动发现「本地爬虫库」。
 *
 * 用户在电脑上跑 `python -m tools.spider.cli serve`，PC 端会在 UDP 8766 上应答；
 * 手机只要广播一个小包，就能拿到 `http://<电脑IP>:8765`，不用手填 IP。
 *
 * 发现结果写进 [SettingsStore.localSpiderApi]，之后每次直接用。
 */
object LocalDiscovery {

    const val DISCOVERY_PORT = 8766
    private const val PROBE = "MODU_DISCOVER_V1"

    /** 解析应答体：`{"app":"modu-spider","port":8765,"api":"http://x.x.x.x:8765"}` */
    fun parseReply(text: String, from: String): String? {
        val api = Regex("\"api\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
        if (!api.isNullOrBlank() && api.startsWith("http")) return api.trimEnd('/')
        val port = Regex("\"port\"\\s*:\\s*(\\d+)").find(text)?.groupValues?.get(1) ?: return null
        return "http://$from:$port"
    }

    /**
     * 广播一次，等 [timeoutMs] 收集应答。
     *
     * @return 找到的第一个可用地址；没找到返回 null
     */
    suspend fun discover(timeoutMs: Int = 2000): String? = withContext(Dispatchers.IO) {
        var sock: DatagramSocket? = null
        try {
            sock = DatagramSocket().apply {
                broadcast = true
                soTimeout = 400
            }
            val msg = PROBE.toByteArray()
            // 广播地址 + 本机回环各发一次（模拟器 / 同机调试时能命中）
            val targets = listOf(
                "255.255.255.255" to DISCOVERY_PORT,
                "127.0.0.1" to DISCOVERY_PORT
            )
            targets.forEach { (host, port) ->
                try {
                    sock.send(DatagramPacket(msg, msg.size, InetAddress.getByName(host), port))
                } catch (_: Exception) {
                }
            }

            val deadline = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(512)
            while (System.currentTimeMillis() < deadline) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    sock.receive(packet)
                    val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    val api = parseReply(text, packet.address.hostAddress ?: "")
                    if (api != null) {
                        SettingsStore.updateLocalSpiderApi(api)
                        return@withContext api
                    }
                } catch (_: SocketTimeoutException) {
                    // 继续等到总超时
                } catch (_: Exception) {
                    return@withContext null
                }
            }
            null
        } catch (e: Exception) {
            null
        } finally {
            try {
                sock?.close()
            } catch (_: Exception) {
            }
        }
    }
}
