package com.openwrtmgr.app

import com.openwrtmgr.app.domain.model.ConnectionType
import com.openwrtmgr.app.domain.model.DhcpLease
import com.openwrtmgr.app.domain.model.WifiAssociation
import com.openwrtmgr.app.domain.model.mergeClients
import org.junit.Assert.assertEquals
import org.junit.Test

class ClientMergeTest {

    @Test
    fun `leased device with a matching wifi association is wireless`() {
        val leases = listOf(DhcpLease("AA:BB:CC:DD:EE:FF", "192.168.1.50", "phone", 0))
        val associations = listOf(WifiAssociation("aa:bb:cc:dd:ee:ff", "phy0-ap0", "HomeNet", -50, null, null))

        val result = mergeClients(leases, associations)

        assertEquals(1, result.size)
        assertEquals(ConnectionType.WIRELESS, result[0].connectionType)
        assertEquals("phone", result[0].hostname)
    }

    @Test
    fun `leased device with no wifi association is wired`() {
        val leases = listOf(DhcpLease("11:22:33:44:55:66", "192.168.1.60", "printer", 0))

        val result = mergeClients(leases, emptyList())

        assertEquals(ConnectionType.WIRED, result[0].connectionType)
    }

    @Test
    fun `wifi association with no lease still appears, with no ip or hostname`() {
        val associations = listOf(WifiAssociation("CC:CC:CC:CC:CC:CC", "phy0-ap0", "HomeNet", -60, null, null))

        val result = mergeClients(emptyList(), associations)

        assertEquals(1, result.size)
        assertEquals(null, result[0].ipAddress)
        assertEquals(ConnectionType.WIRELESS, result[0].connectionType)
    }
}
