package io.github.dylanneve1.iosonpixel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * The single-VM app's VM folder must come through the migration exactly as it was: the
 * migration records where it is and copies its settings, and touches no file.
 */
class InstallMigrationTest {
    @get:Rule val tmp = TemporaryFolder()

    /** A fake single-VM app: files/vm with a few files, and the shared folder next to it. */
    private fun fakeApp(): Triple<File, File, File> {
        val files = tmp.newFolder("files")
        val vm = File(files, "vm").apply { mkdirs() }
        File(vm, "vm.properties").writeText("kernel=kernelcache\nroot=root.qcow2\n")
        File(vm, "kernelcache").writeBytes(ByteArray(4096) { it.toByte() })
        File(vm, "root.qcow2").writeBytes(ByteArray(1 shl 20) { (it * 7).toByte() })
        File(vm, "sep_nvram").writeBytes(ByteArray(65536))
        // Old mtimes, so a rewrite would show.
        vm.listFiles()!!.forEach { it.setLastModified(1_700_000_000_000L) }
        val shared = tmp.newFolder("external", "vm")
        return Triple(files, vm, shared)
    }

    /** name -> (size, mtime, sha-256) for every file, and the folder's own listing. */
    private fun snapshot(dir: File): Map<String, Triple<Long, Long, String>> =
        dir.listFiles()!!.sortedBy { it.name }.associate { f ->
            val sha = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
            f.name to Triple(f.length(), f.lastModified(), sha)
        }

    private val userSettings = VmSettings(
        ramGb = 4, smp = 4, tbSizeMb = 1024, splitWx = false, vcpuAffinity = VmSettings.AUTO,
        backend = Backend.QEMU, inProcess = true,
    )

    @Test fun legacyVmBecomesInstallOneInPlace() {
        val (_, vm, shared) = fakeApp()
        val before = snapshot(vm)
        val dirMtime = vm.lastModified()

        val install = InstallMigration.fromLegacy(InstallMigration.Legacy(userSettings, vmInternal = true, internalVmDir = vm, sharedVmDir = shared))

        assertEquals("1", install.id)
        assertEquals("iOS 14 (iPhone 11)", install.name)
        assertEquals(Backend.QEMU, install.backend)
        assertTrue(install.legacy)
        assertTrue(install.internal)
        assertEquals(vm.absolutePath, install.dir.absolutePath)
        assertEquals(shared.absolutePath, install.sharedDir)
        assertEquals(userSettings, install.settings)
        // Not one byte, size or mtime changed, and nothing was added or removed.
        assertEquals(before, snapshot(vm))
        assertEquals(dirMtime, vm.lastModified())
    }

    @Test fun sharedStorageVmStaysInSharedStorage() {
        val (_, vm, shared) = fakeApp()
        val install = InstallMigration.fromLegacy(InstallMigration.Legacy(userSettings, vmInternal = false, internalVmDir = vm, sharedVmDir = shared))
        assertEquals(shared.absolutePath, install.dir.absolutePath)
    }

    @Test fun encodeDecodeRoundTripsEverySetting() {
        val (files, vm, shared) = fakeApp()
        val a = InstallMigration.fromLegacy(InstallMigration.Legacy(userSettings, true, vm, shared))
        val (i2, s2) = InstallMigration.dirsFor(files, shared.parentFile!!, "2")
        val b = Install(
            id = "2", name = "iOS 16.5 (AVF)", internalDir = i2.path, sharedDir = s2.path, internal = true,
            settings = VmSettings(ramGb = 3, smp = 1, backend = Backend.AVF, security = Security.FULL, extraArgs = "debug=0x8 -v"),
        )
        val decoded = InstallMigration.decode(InstallMigration.encode(listOf(a, b)))
        assertEquals(listOf(a, b), decoded)
        assertEquals(File(files, "installs/2").path, decoded!![1].internalDir)
    }

    @Test fun garbageOrDuplicateIdsDecodeToNull() {
        assertNull(InstallMigration.decode("not json"))
        assertNull(InstallMigration.decode("{}"))
        val one = InstallMigration.encode(listOf(InstallMigration.fromLegacy(
            InstallMigration.Legacy(userSettings, true, File("/x/vm"), File("/y/vm")))))
        assertNotNull(InstallMigration.decode(one))
        val dup = one.removeSuffix("]") + "," + one.removePrefix("[")
        assertNull(InstallMigration.decode(dup))
    }

    @Test fun unknownSettingsFallBackToDefaults() {
        val raw = """[{"id":"1","name":"n","internal_dir":"/a","shared_dir":"/b","internal":true,
            "settings":{"backend":"nonsense","security":"weird","ram_gb":99}}]"""
        val s = InstallMigration.decode(raw)!!.single().settings
        assertEquals(Backend.QEMU, s.backend)
        assertEquals(Security.DEFAULT, s.security)
        assertEquals(VmSettings.MAX_RAM_GB, s.ramGb)
    }

    @Test fun olderBuildChangesAreAdoptedAfterReupgrade() {
        val (_, vm, shared) = fakeApp()
        val legacy = InstallMigration.Legacy(userSettings, true, vm, shared)
        val list = listOf(InstallMigration.fromLegacy(legacy))
        val seen = InstallMigration.seen(userSettings, true)
        // Nothing changed: no resync.
        assertNull(InstallMigration.resync(list, legacy, seen))
        // An older build changed the old prefs in between.
        val changed = legacy.copy(settings = userSettings.copy(smp = 2, inProcess = false))
        val synced = InstallMigration.resync(list, changed, seen)!!
        assertEquals(2, synced.single().settings.smp)
        assertEquals(false, synced.single().settings.inProcess)
        assertEquals(vm.absolutePath, synced.single().dir.absolutePath)
    }
}
