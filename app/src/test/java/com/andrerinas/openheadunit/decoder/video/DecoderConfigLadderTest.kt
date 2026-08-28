package com.andrerinas.openheadunit.decoder.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules for trying optional decoder keys.
 *
 * The property that matters more than any individual key: the last rung is always empty, so whatever
 * the ladder decides, the fallback is the behaviour that shipped before it existed.
 */
class DecoderConfigLadderTest {

    private val mtk = "OMX.MTK.VIDEO.DECODER.HEVC"
    private val qcom = "OMX.qcom.video.decoder.avc"
    private val exynos = "OMX.Exynos.avc.dec"
    private val amlogic = "OMX.amlogic.hevc.decoder"
    private val hisi = "OMX.hisi.video.decoder.avc"
    private val unknown = "OMX.rk.video_decoder.avc"

    private val lowLatencySpellings = setOf(
        DecoderConfigLadder.KEY_LOW_LATENCY,
        DecoderConfigLadder.MTK_LOW_LATENCY,
        DecoderConfigLadder.QUALCOMM_LOW_LATENCY,
        DecoderConfigLadder.EXYNOS_LOW_LATENCY,
        DecoderConfigLadder.AMLOGIC_LOW_LATENCY,
        DecoderConfigLadder.HISILICON_LOW_LATENCY_REQ,
        DecoderConfigLadder.HISILICON_LOW_LATENCY_RDY,
    )

    @Test
    fun `the last rung is always empty, whatever else is offered`() {
        val cases = listOf(mtk, qcom, exynos, amlogic, hisi, unknown, "")
        for (name in cases) {
            for (sdk in listOf(16, 21, 23, 27, 29, 30, 34)) {
                for (feature in listOf(true, false)) {
                    for (requested in listOf(true, false)) {
                        for (rate in listOf(0, 30, 60)) {
                            val tiers = DecoderConfigLadder.tiers(name, sdk, feature, requested, rate)
                            assertTrue("$name/$sdk: no rungs at all", tiers.isNotEmpty())
                            assertEquals(
                                "$name/$sdk/feature=$feature/requested=$requested/rate=$rate " +
                                    "must end with no optional keys",
                                DecoderConfigLadder.NO_OPTIONAL_KEYS,
                                tiers.last()
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `not asking for low latency is exactly the shipped behaviour`() {
        val tiers = DecoderConfigLadder.tiers(
            mtk, 34, advertisesLowLatencyFeature = true, lowLatencyRequested = false, operatingRate = 60
        )
        assertEquals(listOf(DecoderConfigLadder.NO_OPTIONAL_KEYS), tiers)
    }

    @Test
    fun `the official key is preferred where the component advertises it`() {
        val tiers = DecoderConfigLadder.tiers(qcom, 34, advertisesLowLatencyFeature = true, lowLatencyRequested = true)
        assertEquals(2, tiers.size)
        assertTrue(tiers[0].integerKeys.containsKey(DecoderConfigLadder.KEY_LOW_LATENCY))
        assertFalse(tiers[0].integerKeys.containsKey(DecoderConfigLadder.QUALCOMM_LOW_LATENCY))
    }

    @Test
    fun `the official key is not used below the API that has it, even if the feature is claimed`() {
        val tiers = DecoderConfigLadder.tiers(qcom, 29, advertisesLowLatencyFeature = true, lowLatencyRequested = true)
        assertTrue(
            "should fall back to the vendor spelling",
            tiers[0].integerKeys.containsKey(DecoderConfigLadder.QUALCOMM_LOW_LATENCY)
        )
        assertFalse(tiers[0].integerKeys.containsKey(DecoderConfigLadder.KEY_LOW_LATENCY))
    }

    @Test
    fun `the vendor spelling is used when the component does not advertise the feature`() {
        // The case that matters for #839: a MediaTek component on API 27, where the official key does
        // not exist at all.
        val tiers = DecoderConfigLadder.tiers(mtk, 27, advertisesLowLatencyFeature = false, lowLatencyRequested = true)
        assertTrue(tiers.all { it.integerKeys.containsKey(DecoderConfigLadder.MTK_LOW_LATENCY) || it.integerKeys.isEmpty() })
        assertTrue(tiers.first().integerKeys.containsKey(DecoderConfigLadder.MTK_LOW_LATENCY))
    }

    @Test
    fun `the two spellings are never combined`() {
        for (name in listOf(mtk, qcom, exynos, amlogic, hisi)) {
            for (sdk in listOf(27, 30, 34)) {
                for (feature in listOf(true, false)) {
                    val tiers = DecoderConfigLadder.tiers(name, sdk, feature, lowLatencyRequested = true, operatingRate = 60)
                    for (tier in tiers) {
                        val spellings = tier.integerKeys.keys.filter { it in lowLatencySpellings }
                        val official = spellings.contains(DecoderConfigLadder.KEY_LOW_LATENCY)
                        assertFalse(
                            "$name/$sdk/feature=$feature set both spellings: $spellings",
                            official && spellings.size > 1
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `MediaTek gets the reorder keys above the rung that is known to work`() {
        // vdec-lowlatency is the only MediaTek key with a log behind it. The reorder pair goes on its
        // own rung so a component that dislikes it lands back on the measured one rather than on
        // nothing at all.
        val tiers = DecoderConfigLadder.tiers(mtk, 28, advertisesLowLatencyFeature = false, lowLatencyRequested = true)
        assertEquals(3, tiers.size)
        assertEquals(1, tiers[0].integerKeys[DecoderConfigLadder.MTK_NO_REORDER])
        assertEquals(0, tiers[0].integerKeys[DecoderConfigLadder.MTK_CLEAR_MOTION])
        assertEquals(1, tiers[0].integerKeys[DecoderConfigLadder.MTK_LOW_LATENCY])

        assertFalse(tiers[1].integerKeys.containsKey(DecoderConfigLadder.MTK_NO_REORDER))
        assertFalse(tiers[1].integerKeys.containsKey(DecoderConfigLadder.MTK_CLEAR_MOTION))
        assertEquals(1, tiers[1].integerKeys[DecoderConfigLadder.MTK_LOW_LATENCY])
    }

    @Test
    fun `no other vendor gets the MediaTek reorder keys`() {
        for (name in listOf(qcom, exynos, amlogic, hisi, unknown)) {
            val tiers = DecoderConfigLadder.tiers(name, 28, advertisesLowLatencyFeature = false, lowLatencyRequested = true)
            for (tier in tiers) {
                assertFalse(name, tier.integerKeys.containsKey(DecoderConfigLadder.MTK_NO_REORDER))
                assertFalse(name, tier.integerKeys.containsKey(DecoderConfigLadder.MTK_CLEAR_MOTION))
            }
        }
    }

    @Test
    fun `an unknown vendor is still never guessed at`() {
        assertTrue(DecoderConfigLadder.vendorLowLatencyKeys(unknown).isEmpty())
        assertTrue(DecoderConfigLadder.vendorLowLatencyKeys("").isEmpty())
        val tiers = DecoderConfigLadder.tiers(unknown, 27, advertisesLowLatencyFeature = false, lowLatencyRequested = true)
        for (tier in tiers) {
            assertTrue(
                "guessed a vendor spelling for an unknown component: ${tier.integerKeys.keys}",
                tier.integerKeys.keys.none { it in lowLatencySpellings }
            )
        }
    }

    @Test
    fun `an unknown vendor still gets the hints, which belong to no vendor`() {
        val tiers = DecoderConfigLadder.tiers(
            unknown, 27, advertisesLowLatencyFeature = false, lowLatencyRequested = true, operatingRate = 30
        )
        assertEquals(2, tiers.size)
        assertEquals("realtime", tiers[0].label)
        assertEquals(0, tiers[0].integerKeys[DecoderConfigLadder.KEY_PRIORITY])
        assertEquals(30, tiers[0].integerKeys[DecoderConfigLadder.KEY_OPERATING_RATE])
    }

    @Test
    fun `below the API that has the hints, an unknown vendor is offered nothing`() {
        val tiers = DecoderConfigLadder.tiers(
            unknown, 21, advertisesLowLatencyFeature = false, lowLatencyRequested = true, operatingRate = 30
        )
        assertEquals(listOf(DecoderConfigLadder.NO_OPTIONAL_KEYS), tiers)
    }

    @Test
    fun `the hints ride on every offered rung rather than costing one of their own`() {
        for (name in listOf(mtk, qcom, exynos, amlogic, hisi)) {
            val tiers = DecoderConfigLadder.tiers(name, 28, advertisesLowLatencyFeature = false, lowLatencyRequested = true, operatingRate = 60)
            for (tier in tiers.dropLast(1)) {
                assertEquals("$name/${tier.label}", 0, tier.integerKeys[DecoderConfigLadder.KEY_PRIORITY])
                assertEquals("$name/${tier.label}", 60, tier.integerKeys[DecoderConfigLadder.KEY_OPERATING_RATE])
            }
        }
    }

    @Test
    fun `priority is asked for even without a rate to name, and the rate is not`() {
        val hints = DecoderConfigLadder.realtimeHints(28, 0)
        assertEquals(mapOf(DecoderConfigLadder.KEY_PRIORITY to 0), hints)
        assertTrue(DecoderConfigLadder.realtimeHints(22, 60).isEmpty())
    }

    @Test
    fun `each vendor family is matched by its component name`() {
        assertEquals(mapOf(DecoderConfigLadder.MTK_LOW_LATENCY to 1), DecoderConfigLadder.vendorLowLatencyKeys(mtk))
        assertEquals(mapOf(DecoderConfigLadder.QUALCOMM_LOW_LATENCY to 1), DecoderConfigLadder.vendorLowLatencyKeys(qcom))
        assertEquals(mapOf(DecoderConfigLadder.EXYNOS_LOW_LATENCY to 1), DecoderConfigLadder.vendorLowLatencyKeys(exynos))
        assertEquals(mapOf(DecoderConfigLadder.AMLOGIC_LOW_LATENCY to 1), DecoderConfigLadder.vendorLowLatencyKeys(amlogic))
        assertEquals(
            mapOf(
                DecoderConfigLadder.HISILICON_LOW_LATENCY_REQ to 1,
                DecoderConfigLadder.HISILICON_LOW_LATENCY_RDY to -1,
            ),
            DecoderConfigLadder.vendorLowLatencyKeys(hisi)
        )
    }

    @Test
    fun `the Codec2 spellings of the same families are matched too`() {
        assertEquals(mapOf(DecoderConfigLadder.MTK_LOW_LATENCY to 1), DecoderConfigLadder.vendorLowLatencyKeys("c2.mtk.hevc.decoder"))
        assertEquals(mapOf(DecoderConfigLadder.QUALCOMM_LOW_LATENCY to 1), DecoderConfigLadder.vendorLowLatencyKeys("c2.qti.avc.decoder"))
        assertEquals(mapOf(DecoderConfigLadder.EXYNOS_LOW_LATENCY to 1), DecoderConfigLadder.vendorLowLatencyKeys("c2.exynos.hevc.decoder"))
    }
}
