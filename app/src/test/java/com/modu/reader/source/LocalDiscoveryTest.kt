package com.modu.reader.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 局域网自动发现：电脑端 UDP 应答的解析（纯函数，不碰网络）。 */
class LocalDiscoveryTest {

    @Test
    fun `取应答里的 api 地址`() {
        val reply = """{"app":"modu-spider","port":8765,"api":"http://192.168.1.20:8765"}"""
        assertEquals("http://192.168.1.20:8765", LocalDiscovery.parseReply(reply, "192.168.1.20"))
    }

    @Test
    fun `没有 api 字段时用来源 IP 加端口拼`() {
        val reply = """{"app":"modu-spider","port":9000}"""
        assertEquals("http://10.0.0.7:9000", LocalDiscovery.parseReply(reply, "10.0.0.7"))
    }

    @Test
    fun `去掉末尾斜杠，避免拼出双斜杠`() {
        val reply = """{"api":"http://192.168.1.20:8765/"}"""
        assertEquals("http://192.168.1.20:8765", LocalDiscovery.parseReply(reply, "x"))
    }

    @Test
    fun `不是我们的应答就返回 null`() {
        assertNull(LocalDiscovery.parseReply("hello", "1.2.3.4"))
        assertNull(LocalDiscovery.parseReply("""{"app":"other"}""", "1.2.3.4"))
    }
}
