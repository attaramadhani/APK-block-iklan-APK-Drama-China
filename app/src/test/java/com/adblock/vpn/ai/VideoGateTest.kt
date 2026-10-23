package com.adblock.vpn.ai

import org.junit.Assert.*
import org.junit.Test

class VideoGateTest {

    private fun open(d: String) = assertTrue("$d harus DIBUKA", VideoGate.evaluate(d).allowed)
    private fun closed(d: String) = assertFalse("$d harus DITUTUP", VideoGate.evaluate(d).allowed)

    @Test
    fun videoAndContentDomainsAreOpen() {
        open("api.tmtreader.com")
        open("api22-normal-quic-alisg.tmtreader.com")
        open("passport.tmtreader.com")
        open("v16-ml.melolostatic.com")
        open("vod-urls-mya.byteintlapi.com")
        open("v16-novel-sg.byteoversea.com")
        open("v19-default.akamaized.net")
        open("p16-novel-sg.ibyteimg.com")
        open("sf16-passport-sg.ibytedtos.com")
        open("lf16-gecko-source.tiktokcdn.com")
        open("tnc16-alisg.isnssdk.com")
        open("log15.byteoversea.com")
        open("fp-sg.pipopay.com")
        open("accounts.google.com")
    }

    @Test
    fun everythingElseIsClosed() {
        closed("mediation-sg.pangle.io")
        closed("api16-access-sg.pangle.io")
        closed("sf16-static.i18n-pglstatp.com")
        closed("lf-static.tiktokpangle-cdn-us.com")
        closed("p16-ad-sg.ibyteimg.com")
        closed("mon-va.byteoversea.com")
        closed("rtlog.sgsnssdk.com")
        closed("googleads.g.doubleclick.net")
        closed("d.applovin.com")
        closed("sdkapi.dsp-api.moloco.com")
        closed("app-measurement.com")
        closed("graph.facebook.com")
        closed("some-new-adnetwork.xyz")
        closed("random-sub.byteoversea.com")
    }
}
