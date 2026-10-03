package com.openwrtmgr.app

import com.openwrtmgr.app.data.*
import com.openwrtmgr.app.ui.wifiQrPayload
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParsersTest {
    private fun j(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test fun memoryUsesAvailableNotFree() {
        val i = parseSysInfo(j("""{"uptime":10,"load":[65536,0,0],"memory":{"total":1000,"free":100,"available":600},"root":{"total":100,"used":25}}"""))
        assertEquals(40f, i.memUsedPct, 0.01f)
        assertEquals(1.0, i.load[0], 0.001)
        assertEquals(25f, i.rootUsedPct, 0.01f)
    }

    @Test fun cpuJiffies() {
        val (busy, total) = parseCpuJiffies("cpu  100 0 50 800 50 0 0 0 0 0\ncpu0 1 2 3 4")!!
        assertEquals(1000L, total); assertEquals(150L, busy)
        assertNull(parseCpuJiffies("garbage"))
    }

    @Test fun wirelessMapsSectionsToIfnamesAndBands() {
        val uci = j("""{
          "radio0":{".type":"wifi-device",".index":0,"band":"2g","channel":"6","htmode":"HE20"},
          "radio1":{".type":"wifi-device",".index":1,"band":"5g","channel":"auto","disabled":"1"},
          "default_radio0":{".type":"wifi-iface",".index":2,"device":"radio0","network":"lan","ssid":"Home","encryption":"sae-mixed","key":"secret123"},
          "guest":{".type":"wifi-iface",".index":3,"device":"radio0","network":["guest"],"ssid":"Guest","encryption":"none","isolate":"1"}
        }""")
        val status = j("""{"radio0":{"up":true,"interfaces":[{"section":"default_radio0","ifname":"phy0-ap0"},{"section":"guest","ifname":"phy0-ap1"}]},"radio1":{"up":false,"interfaces":[]}}""")
        val (radios, ssids) = parseWireless(uci, status)
        assertEquals(listOf("2.4 GHz", "5 GHz"), radios.map { it.band })
        assertTrue(radios[1].disabled)
        assertEquals("phy0-ap1", ssids.first { it.ssid == "Guest" }.ifname)
        assertEquals(listOf("guest"), ssids.first { it.ssid == "Guest" }.network)
        assertTrue(ssids.first { it.ssid == "Guest" }.isolate)
    }

    @Test fun mergeDevicesPrefersReservationNameAndMarksBlocked() {
        val d = mergeDevices(
            hints = listOf(HostHint("AA:BB:CC:00:00:01", "hint-name", listOf("192.168.1.5"), emptyList())),
            leases = listOf(Lease("AA:BB:CC:00:00:01", "192.168.1.5", "android-123", 100)),
            stations = listOf(Station("AA:BB:CC:00:00:01", "phy0-ap0", -50, 1000, 2000, 1, 2, 60)),
            ssidByIf = mapOf("phy0-ap0" to "Home"),
            reservations = listOf(StaticLease("cfg1", "Pixel", "aa:bb:cc:00:00:01", "192.168.1.5", true)),
            blockedMacs = setOf("AA:BB:CC:00:00:01"),
        ).single()
        assertEquals("Pixel", d.name)
        assertEquals("Home", d.ssid)
        assertTrue(d.blocked && d.wireless && d.online)
        assertEquals(DeviceKind.PHONE, d.kind)
    }

    @Test fun hostapdClientMacIsUppercased() {
        val s = parseHostapdClients("phy0-ap0", j("""{"clients":{"aa:bb:cc:dd:ee:ff":{"signal":-60,"rate":{"rx":173000,"tx":240200},"bytes":{"rx":5,"tx":6}}}}""")).single()
        assertEquals("AA:BB:CC:DD:EE:FF", s.mac)
    }

    @Test fun firewallAndDhcp() {
        val fw = parseFirewall(j("""{
          "z1":{".type":"zone",".index":0,"name":"wan","input":"REJECT","masq":"1","network":["wan","wan6"]},
          "f1":{".type":"forwarding",".index":1,"src":"lan","dest":"wan"},
          "r1":{".type":"rule",".index":2,"name":"Block: tv","src":"*","dest":"wan","src_mac":["AA:BB:CC:DD:EE:FF"],"target":"REJECT","proto":"all"}
        }"""))
        assertTrue(fw.zones.single().masq)
        assertEquals(listOf("AA:BB:CC:DD:EE:FF"), fw.rules.single().srcMac)
        val dhcp = parseDhcp(j("""{"lan":{".type":"dhcp","interface":"lan","start":"100","limit":"150","leasetime":"12h"},"h":{".type":"host","mac":"aa:bb:cc:dd:ee:ff","ip":"192.168.1.9","name":"nas"}}"""))
        assertEquals(100, dhcp.pools.single().start)
        assertEquals("AA:BB:CC:DD:EE:FF", dhcp.hosts.single().mac)
    }

    @Test fun cidrStaticIfaces() {
        val (ifs, _) = parseNetworkConfig(j("""{"lan":{".type":"interface","proto":"static","ipaddr":"192.168.1.1/24","device":"br-lan"}}"""))
        assertEquals("192.168.1.1", ifs.single().ipaddr)
        assertEquals("255.255.255.0", ifs.single().netmask)
    }

    @Test fun apkJsonAndOpkgPackages() {
        val apk = parsePackages("""[{"name":"curl","version":"8.9-r1","description":"URL tool","installed-size":1234,"status":["installed"]}]""", false)
        assertTrue(apk.single().installed)
        // list-installed on opkg is /usr/lib/opkg/status; list-available is the unpacked feed lists.
        val status = "Package: curl\nVersion: 8.4.0-1\nDepends: libc, libcurl4\nStatus: install user installed\nArchitecture: aarch64_cortex-a53\nInstalled-Size: 1234\n\n" +
            "Package: old-thing\nVersion: 1.0\nStatus: deinstall ok not-installed\n\n"
        val opkg = parsePackages(status, true)
        assertEquals(listOf("curl"), opkg.map { it.name })
        assertEquals("8.4.0-1", opkg.single().version); assertEquals(1234L, opkg.single().size)
        val feed = parsePackages("Package: tcpdump\nVersion: 4.99.4-1\nSize: 160000\nDescription: Network monitoring and data acquisition tool\n continued\n\nPackage: iperf3\nVersion: 3.17-1\n", false)
        assertEquals(listOf("tcpdump", "iperf3"), feed.map { it.name })
        assertEquals("Network monitoring and data acquisition tool", feed[0].description)
        assertTrue(feed.none { it.installed })
    }

    @Test fun nlbwmonTotalsPerMac() {
        // nlbw -c json -g mac: one row per MAC per address family, summed.
        val u = parseNlbw("""{"columns":["family","mac","conns","rx_bytes","rx_pkts","tx_bytes","tx_pkts"],"data":[[4,"aa:bb:cc:dd:ee:01",10,1000,1,200,1],[6,"aa:bb:cc:dd:ee:01",2,500,1,50,1],[4,"aa:bb:cc:dd:ee:02",1,7,1,3,1]]}""")!!
        assertEquals(HostUsage(1500, 250, 12), u["AA:BB:CC:DD:EE:01"])
        assertEquals(10L, u["AA:BB:CC:DD:EE:02"]!!.total)
        assertNull(parseNlbw("nlbw: connection refused"))
    }

    @Test fun uplinksAreInterfacesWithDefaultRoutes() {
        val ifs = parseInterfaces(j("""{"interface":[
            {"interface":"wan","up":true,"l3_device":"eth1","route":[{"target":"0.0.0.0","mask":0,"nexthop":"203.0.113.1"}]},
            {"interface":"wan6","up":true,"l3_device":"eth1","route":[{"target":"::","mask":0,"nexthop":"fe80::1"}]},
            {"interface":"lan","up":true,"l3_device":"br-lan","route":[]}]}"""))
        assertEquals(listOf("wan", "wan6"), ifs.filter { it.uplink }.map { it.name })
    }

    @Test fun conntrackDeltaSplitsByLocalIp() {
        fun c(src: String, dst: String, sport: String, bytes: Long) = Conn("tcp", src, sport, dst, "443", bytes, 1)
        val before = listOf(c("192.168.1.10", "1.1.1.1", "1", 100), c("192.168.1.20", "8.8.8.8", "2", 100))
        val after = listOf(c("192.168.1.10", "1.1.1.1", "1", 400), c("192.168.1.20", "8.8.8.8", "2", 150), c("9.9.9.9", "192.168.1.20", "3", 25))
        assertEquals(mapOf("192.168.1.10" to 300L, "192.168.1.20" to 75L), conntrackDeltaByIp(before, after, setOf("192.168.1.10", "192.168.1.20")))
    }

    @Test fun hostapdRatesAreAlreadyKbps() {
        val s = parseHostapdClients("phy1-ap0", j("""{"clients":{"aa:bb:cc:dd:ee:ff":{"rate":{"rx":866700,"tx":650000},"bytes":{"rx":1,"tx":2},"signal":-50}}}""")).single()
        assertEquals(866700L, s.rxRate); assertEquals(650000L, s.txRate)
    }

    @Test fun scanEncryptionLabels() {
        assertEquals("Open", scanEncryption(j("""{"enabled":false}""")))
        assertEquals("WPA2/WPA3", scanEncryption(j("""{"enabled":true,"wpa":[2,3],"authentication":["psk","sae"],"ciphers":["ccmp"]}""")))
        assertEquals("WPA2", scanEncryption(j("""{"enabled":true,"wpa":[2],"authentication":["psk"]}""")))
        assertEquals("Enterprise", scanEncryption(j("""{"enabled":true,"wpa":[2],"authentication":["802.1x"]}""")))
        assertEquals("WEP", scanEncryption(j("""{"enabled":true,"wep":["open"]}""")))
    }

    @Test fun portLinkFollowsCarrierNotAdminState() {
        val stats = parseDeviceStats(j("""{"lan1":{"up":true,"carrier":true,"speed":"1000F"},"lan2":{"up":true,"carrier":false}}"""))
        val ports = parsePorts(j("""{"result":[{"role":"lan","device":"lan1"},{"role":"lan","device":"lan2"}]}"""), stats)
        assertEquals(listOf(true, false), ports.map { it.up })
    }

    @Test fun processVszUnits() {
        assertEquals(1234L, kib("1234")); assertEquals(12L * 1024, kib("12m")); assertEquals(0L, kib(null))
        val p = parseProcesses(j("""{"result":[{"PID":"1","USER":"root","VSZ":"12m","%MEM":"1%","%CPU":"0%","COMMAND":"/sbin/procd"}]}""")).single()
        assertEquals(12L * 1024, p.vsz)
    }

    @Test fun wifiQrEscapesSpecialChars() {
        assertEquals("WIFI:T:WPA;S:my\\;net;P:p\\:w;;", wifiQrPayload("my;net", "psk2+ccmp", "p:w", false))
        assertEquals("WIFI:T:nopass;S:Guest;H:true;;", wifiQrPayload("Guest", "none", "", true))
    }

    @Test fun neighborsDrivePresenceAndStaticIp() {
        val n = parseNeighbors(
            """
            192.168.50.123 dev br-lan.50  used 0/0/0 probes 6 FAILED
            192.168.110.21 dev br-lan.110 lladdr bc:24:11:00:00:01 ref 1 used 0/0/0 probes 1 REACHABLE
            192.168.50.117 dev br-lan.50 lladdr 02:00:00:00:00:17 used 0/0/0 probes 0 STALE
            """.trimIndent(),
        )
        assertEquals(2, n.size) // FAILED row has no lladdr
        val devs = mergeDevices(
            hints = emptyList(),
            leases = listOf(Lease("02:00:00:00:00:17", "192.168.50.117", "phone", 3600), Lease("02:00:00:00:00:23", "192.168.50.123", "gone", 3600)),
            stations = emptyList(), ssidByIf = emptyMap(), reservations = emptyList(), blockedMacs = emptySet(),
            neighbors = n, netByDev = mapOf("br-lan.110" to "app", "br-lan.50" to "home"),
        ).associateBy { it.mac }
        val vm = devs.getValue("BC:24:11:00:00:01")
        assertEquals(Presence.ONLINE, vm.presence); assertTrue(vm.staticIp); assertEquals("app", vm.network)
        assertEquals(DeviceKind.VM, vm.kind); assertTrue(vm.name.startsWith("Proxmox VM"))
        assertEquals(Presence.IDLE, devs.getValue("02:00:00:00:00:17").presence)
        // A leftover DHCP lease alone must not make a device "online" (the old wired-ghost bug).
        assertEquals(Presence.OFFLINE, devs.getValue("02:00:00:00:00:23").presence)
    }

    @Test fun logSourceSplit() {
        val l = parseLog(j("""{"log":[{"msg":"dnsmasq[123]: query A","priority":30,"time":1}]}""")).single()
        assertEquals("dnsmasq", l.source); assertEquals("query A", l.message); assertEquals(6, l.priority)
    }

    @Test fun assoclistForOneMacIsTopLevel() {
        // rpcd answers `assoclist {device, mac}` with the station itself, no "results" array.
        val one = parseAssoclist("phy1-ap0", j("""{"mac":"F0:18:98:6B:22:C4","signal":-46,"rx":{"bytes":100,"rate":866700},"tx":{"bytes":900,"rate":866700}}""")).single()
        assertEquals("F0:18:98:6B:22:C4", one.mac); assertEquals(100L, one.rxBytes); assertEquals(900L, one.txBytes)
        assertEquals(2, parseAssoclist("phy1-ap0", j("""{"results":[{"mac":"aa:bb:cc:dd:ee:01"},{"mac":"aa:bb:cc:dd:ee:02"}]}""")).size)
        assertTrue(parseAssoclist("phy1-ap0", j("{}")).isEmpty())
    }

    @Test fun conntrackDeltaSurvivesClosedFlows() {
        fun c(sport: String, bytes: Long) = Conn("tcp", "192.168.1.101", sport, "1.1.1.1", "443", bytes, 1)
        // A big flow closed (gone from the list) while another grew by 300 and a new one moved 50.
        val before = listOf(c("40000", 1_000_000), c("40001", 1_000))
        val after = listOf(c("40001", 1_300), c("40002", 50))
        assertEquals(350L, conntrackDelta(before, after))
    }
}
