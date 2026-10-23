package com.adblock.vpn.ai

import org.junit.Assert.*
import org.junit.Test

class DomainAiAnalyzerTest {

    @Test
    fun knownAdNetworksAreBlocked() {
        val v = DomainAiAnalyzer.analyze("d.applovin.com")
        assertEquals(DomainAiAnalyzer.Category.AD_NETWORK, v.category)
        assertEquals(DomainAiAnalyzer.Action.BLOCK, v.action)
        assertTrue(v.adScore >= 90)
    }

    @Test
    fun meloloCoreIsNeverBlocked() {
        for (d in listOf("api.tmtreader.com", "passport.tmtreader.com", "p19-novel-sg.ibyteimg.com",
            "vod-urls-mya.byteintlapi.com", "lf16-gecko-source.tiktokcdn.com", "fp-sg.pipopay.com")) {
            val v = DomainAiAnalyzer.analyze(d)
            assertEquals(d, DomainAiAnalyzer.Action.ALLOW, v.action)
            assertEquals(d, DomainAiAnalyzer.Category.CORE_APP, v.category)
        }
    }

    @Test
    fun byteDanceSubdomainPatterns() {
        assertEquals(DomainAiAnalyzer.Category.TRACKER, DomainAiAnalyzer.analyze("mon-va.byteoversea.com").category)
        assertEquals(DomainAiAnalyzer.Category.TRACKER, DomainAiAnalyzer.analyze("sgali-dpprofile.byteoversea.com").category)
        assertEquals(DomainAiAnalyzer.Category.TELEMETRY, DomainAiAnalyzer.analyze("log15.byteoversea.com").category)
        assertEquals(DomainAiAnalyzer.Action.REVIEW, DomainAiAnalyzer.analyze("log.isnssdk.com").action)
    }

    @Test
    fun scoringModelForUnknownDomains() {
        val ad = DomainAiAnalyzer.analyze("banner-tracking.unknownnet.xyz")
        assertEquals(DomainAiAnalyzer.Category.UNKNOWN, ad.category)
        assertEquals(DomainAiAnalyzer.Action.BLOCK, ad.action)

        val content = DomainAiAnalyzer.analyze("video-cdn.somedrama.net")
        assertEquals(DomainAiAnalyzer.Action.ALLOW, content.action)
        assertTrue(content.adScore < 20)

        val neutral = DomainAiAnalyzer.analyze("example.com")
        assertEquals(DomainAiAnalyzer.Action.ALLOW, neutral.action)
    }

    @Test
    fun tokenizerSplitsLabels() {
        assertEquals(listOf("sg", "gcp", "ad", "track", "mtgglobals"),
            DomainAiAnalyzer.tokenize("sg-gcp-ad-track16.mtgglobals.com"))
        // "uploads" bukan token iklan "ads"
        assertFalse(DomainAiAnalyzer.tokenize("uploads-sg.files.com").contains("ads"))
    }
}
