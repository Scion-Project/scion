package io.github.dylanneve1.iosonpixel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class QemuEnvTest {
    private val cache = File("/data/user/0/io.github.dylanneve1.iosonpixel/cache")

    @Test fun tmpDirIsTheCacheDirOnBothPaths() {
        // -snapshot puts its overlays in TMPDIR (#68); the child process and the :vm path share one env.
        for (inProcess in listOf(false, true)) {
            val env = QemuCommand.env(cache, VmSettings(inProcess = inProcess))
            assertEquals(cache.absolutePath, env["TMPDIR"])
        }
    }

    @Test fun autoAffinityFollowsSmp() {
        val env = QemuCommand.env(cache, VmSettings(smp = 4))
        assertEquals("2-7;2-7;2-7;0-1", env["QEMU_VCPU_AFFINITY"])
    }

    @Test fun emptyAffinityLeavesThePinningOut() {
        assertFalse("QEMU_VCPU_AFFINITY" in QemuCommand.env(cache, VmSettings(vcpuAffinity = "")))
    }
}
