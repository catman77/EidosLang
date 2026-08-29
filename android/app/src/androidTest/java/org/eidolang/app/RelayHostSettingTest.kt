package org.eidolang.app

import androidx.test.platform.app.InstrumentationRegistry
import org.eidolang.feature.home.EidoRelaySettings
import org.eidolang.feature.home.Relay
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The relay address is a setting, and changing it must not quietly loosen TLS.
 *
 * The certificate used to be trusted for one hard-coded IP in the manifest, so making the address
 * configurable would have broken every other address outright — and cleartext is forbidden, so
 * there is no fallback to fall into. Pinning the certificate instead keeps the guarantee while
 * letting the relay move, and this checks both halves: the setting survives, and the default
 * address still answers over the pinned certificate.
 */
class RelayHostSettingTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun restore() {
        EidoRelaySettings.setHost(ctx, Relay.DEFAULT_HOST)
    }

    @Test
    fun theHostIsStoredValidatedAndStillReachableOverThePinnedCertificate() {
        EidoRelaySettings.apply(ctx)
        assertTrue("релей по умолчанию не отвечает", Relay.reachable())

        assertTrue("адрес не сохранился", EidoRelaySettings.setHost(ctx, "10.1.2.3"))
        assertEquals("адрес не перечитывается", "10.1.2.3", EidoRelaySettings.host(ctx))
        assertEquals("клиент не подхватил новый адрес", "10.1.2.3", Relay.host)
        assertTrue("база не собралась из адреса", Relay.base.startsWith("https://10.1.2.3:"))
        assertFalse("несуществующий релей отчитался как живой", Relay.reachable())

        // Refused, not sanitised: this string is dialled and pasted into a URL.
        listOf("", "a", "плохой хост", "1.2.3.4/../x", "1.2.3.4 && rm").forEach {
            assertFalse("принят негодный адрес: '$it'", EidoRelaySettings.setHost(ctx, it))
        }
        assertEquals("негодный адрес всё-таки записался", "10.1.2.3", EidoRelaySettings.host(ctx))

        EidoRelaySettings.setHost(ctx, Relay.DEFAULT_HOST)
        assertTrue("возврат к адресу по умолчанию не восстановил связь", Relay.reachable())
        println("RELAYHOST PASS адрес настраивается, проверяется и остаётся под пином сертификата")
    }
}
