package pl.iptv.tv.ui

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** (v2) Odtwarzacz Stremio w Nowoczesnym v2: pigułka samej jakości obrazu (bez nazwy silnika). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class V727StremioV2Test {
    @Test fun qualityPillShowsOnlyResolutionAndHdr() {
        assertEquals("", StremioV2Hud.quality(null))
        assertEquals("1080p", StremioV2Hud.quality(Format.Builder().setWidth(1920).setHeight(1080).build()))
        val hdr = ColorInfo.Builder().setColorTransfer(C.COLOR_TRANSFER_ST2084).build()
        assertEquals("4K HDR", StremioV2Hud.quality(Format.Builder().setWidth(3840).setHeight(2160).setColorInfo(hdr).build()))
    }
}
