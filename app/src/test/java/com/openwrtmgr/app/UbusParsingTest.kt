package com.openwrtmgr.app

import com.openwrtmgr.app.core.networking.buildWifiRadio
import com.openwrtmgr.app.core.networking.humanizeEncryption
import com.openwrtmgr.app.core.networking.parseApkListInstalled
import com.openwrtmgr.app.core.networking.parseApkListUpgradable
import com.openwrtmgr.app.core.networking.parseApkNameVersion
import com.openwrtmgr.app.core.networking.parseDhcpLeases
import com.openwrtmgr.app.core.networking.parseSystemInfo
import com.openwrtmgr.app.core.networking.toInterfaceInfo
import com.openwrtmgr.app.core.networking.toLogEntry
import com.openwrtmgr.app.core.networking.toPortForward
import com.openwrtmgr.app.core.networking.toServiceStatus
import com.openwrtmgr.app.core.networking.toWifiAssociation
import com.openwrtmgr.app.domain.model.LogSeverity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Smallest useful check on the ubus response parsing — the one place a router's raw JSON
 * meets our domain models. Real fixtures pulled from ubus `system.info` / `system.board` /
 * `network.interface.dump` / `network.wireless status` / `iwinfo.info` / `iwinfo.assoclist` /
 * `uci get_all firewall` on a stock OpenWrt 23.05 install.
 */
class UbusParsingTest {

    private val json = Json { ignoreUnknownKeys = true }
    private fun obj(text: String) = json.parseToJsonElement(text).jsonObject

    @Test
    fun `parses system info and board into SystemInfo`() {
        val info = obj("""{"uptime":12345,"load":[65536,32768,0],"memory":{"total":134217728,"free":67108864}}""")
        val board = obj(
            """{"hostname":"OpenWrt","model":"GL.iNet GL-MT3000","kernel":"5.15.150",
              "system":"aarch64","release":{"version":"23.05.3","description":"OpenWrt 23.05.3"}}""",
        )

        val result = parseSystemInfo(info, board)

        assertEquals("OpenWrt", result.hostname)
        assertEquals("GL.iNet GL-MT3000", result.model)
        assertEquals("OpenWrt 23.05.3", result.openWrtVersion)
        assertEquals(12345L, result.uptimeSeconds)
        assertEquals(listOf(1.0, 0.5, 0.0), result.loadAverage)
        assertEquals(134217728L, result.memoryTotalBytes)
        assertEquals(67108864L, result.memoryFreeBytes)
    }

    @Test
    fun `parses an interface entry`() {
        val iface = obj(
            """{"interface":"lan","proto":"static","device":"br-lan","up":true,
              "ipv4-address":[{"address":"192.168.1.1","mask":24}]}""",
        )

        val result = iface.toInterfaceInfo()

        assertEquals("lan", result.name)
        assertEquals("static", result.protocol)
        assertEquals(true, result.isUp)
        assertEquals("192.168.1.1", result.ipv4Address)
    }

    @Test
    fun `builds an active radio from wireless status plus iwinfo`() {
        val radioStatus = obj(
            """{"config":{"channel":"36","disabled":false},
              "interfaces":[{"ifname":"phy1-ap0","config":{"ssid":"HomeNet","mode":"ap","encryption":"psk2+ccmp"}}]}""",
        )
        val iwinfo = obj("""{"quality":60,"quality_max":70,"bitrate":866700,"txpower":20}""")

        val result = buildWifiRadio("radio1", radioStatus, iwinfo)

        assertEquals("HomeNet", result.ssid)
        assertEquals(36, result.channel)
        assertEquals("WPA2-PSK", result.encryption)
        assertEquals(true, result.isActive)
        assertEquals(85, result.signalQualityPercent) // 60*100/70, integer division
        assertEquals(866.7, result.bitrateMbps!!, 0.01)
    }

    @Test
    fun `disabled radio is inactive even with a stale ssid config`() {
        val radioStatus = obj(
            """{"config":{"disabled":true},
              "interfaces":[{"ifname":"phy0-ap0","config":{"ssid":"HomeNet","encryption":"none"}}]}""",
        )

        val result = buildWifiRadio("radio0", radioStatus, iwinfo = null)

        assertEquals(false, result.isActive)
        assertEquals(null, result.signalQualityPercent)
    }

    @Test
    fun `humanizes common encryption modes`() {
        assertEquals("Open", humanizeEncryption("none"))
        assertEquals("WPA2-PSK", humanizeEncryption("psk2+ccmp"))
        assertEquals("WPA2/WPA3-SAE", humanizeEncryption("sae-mixed"))
        assertEquals("Unknown", humanizeEncryption(null))
    }

    @Test
    fun `parses an assoclist entry`() {
        val entry = obj(
            """{"mac":"AA:BB:CC:DD:EE:FF","signal":-55,"rx":{"rate":866700},"tx":{"rate":433300}}""",
        )

        val result = entry.toWifiAssociation(device = "phy1-ap0", ssid = "HomeNet")

        assertEquals("AA:BB:CC:DD:EE:FF", result.macAddress)
        assertEquals(-55, result.signalDbm)
        assertEquals(866.7, result.rxRateMbps!!, 0.01)
        assertEquals(433.3, result.txRateMbps!!, 0.01)
    }

    @Test
    fun `parses dnsmasq lease lines, ignoring a hostname of star`() {
        val leases = parseDhcpLeases(
            "1700000000 aa:bb:cc:dd:ee:ff 192.168.1.50 johns-macbook 01:aa:bb:cc:dd:ee:ff\n" +
                "1700000100 11:22:33:44:55:66 192.168.1.51 * *\n",
        )

        assertEquals(2, leases.size)
        assertEquals("johns-macbook", leases[0].hostname)
        assertEquals("192.168.1.50", leases[0].ipAddress)
        assertEquals(null, leases[1].hostname)
    }

    @Test
    fun `parses a port forward redirect section`() {
        val section = obj(
            """{".type":"redirect","name":"Minecraft","enabled":"1","proto":"tcp",
              "src":"wan","dest":"lan","src_dport":"25565","dest_ip":"192.168.1.50","dest_port":"25565"}""",
        )

        val result = section.toPortForward("cfg01dc0c")

        assertEquals("cfg01dc0c", result.uciSectionId)
        assertEquals("Minecraft", result.name)
        assertEquals(true, result.enabled)
        assertEquals("25565", result.externalPort)
        assertEquals("192.168.1.50", result.internalIp)
    }

    @Test
    fun `service with a running instance is reported running`() {
        val service = obj(
            """{"instances":{"instance1":{"running":true,"pid":1234},"instance2":{"running":false}}}""",
        )

        val result = service.toServiceStatus("dnsmasq")

        assertEquals("dnsmasq", result.name)
        assertEquals(true, result.running)
        assertEquals(2, result.instanceCount)
        assertEquals(1234, result.pid)
    }

    @Test
    fun `service with no running instances is stopped`() {
        val service = obj("""{"instances":{"instance1":{"running":false}}}""")

        val result = service.toServiceStatus("uhttpd")

        assertEquals(false, result.running)
        assertEquals(null, result.pid)
    }

    @Test
    fun `parses a log entry and maps priority to severity`() {
        val entry = obj("""{"msg":"br-lan: link is up","priority":6,"time":1700000000}""")

        val result = entry.toLogEntry()

        assertEquals("br-lan: link is up", result.message)
        assertEquals(LogSeverity.INFO, result.severity)
        assertEquals(1700000000L, result.epochSeconds)
    }

    @Test
    fun `splits a simple apk name-version token`() {
        assertEquals("apk-tools" to "2.14.4-r0", parseApkNameVersion("apk-tools-2.14.4-r0"))
    }

    @Test
    fun `splits a hyphenated package name from a kernel-style version`() {
        assertEquals("kmod-usb-core" to "5.15.150-1", parseApkNameVersion("kmod-usb-core-5.15.150-1"))
    }

    @Test
    fun `returns null when there is no digit after any hyphen`() {
        assertEquals(null, parseApkNameVersion("no-version-here"))
    }

    @Test
    fun `parses apk list --installed output`() {
        val output = """
            apk-tools-2.14.4-r0 x86_64 {apk-tools} (GPL-2.0-only) [installed]
            busybox-1.36.1-r2 x86_64 {busybox} (GPL-2.0-only) [installed]
        """.trimIndent()

        val result = parseApkListInstalled(output)

        assertEquals(2, result.size)
        assertEquals("apk-tools", result[0].name)
        assertEquals("2.14.4-r0", result[0].installedVersion)
        assertEquals(false, result[0].isUpgradable)
    }

    @Test
    fun `parses apk list --upgradable output into a name-to-new-version map`() {
        val output = "busybox-1.36.1-r3 x86_64 {busybox} (GPL-2.0-only) [upgradable from: busybox-1.36.1-r2]"

        val result = parseApkListUpgradable(output)

        assertEquals("1.36.1-r3", result["busybox"])
    }
}
