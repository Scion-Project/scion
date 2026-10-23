package io.github.dylanneve1.iosonpixel.avf

import android.content.Context
import java.io.InputStream
import java.lang.reflect.Constructor
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.concurrent.Executor

/** A failed AVF call, carrying the framework's own exception (unwrapped from reflection). */
class AvfException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The AVF Java API (`android.system.virtualmachine`, in the com.android.virt APEX), through reflection.
 *
 * None of it is in the SDK's android.jar. VirtualMachineManager, VirtualMachineConfig and
 * VirtualMachine are `@SystemApi`; the custom-kernel builder (VirtualMachineCustomImageConfig)
 * and the setter that attaches it are `@hide`, so the runtime's hidden-API policy refuses them
 * unless `hidden_api_blacklist_exemptions` lists them ([EXEMPTIONS]). Reflection is the only way
 * in for a sideloaded app, and it lets [unreachable] name exactly what the policy refuses.
 *
 * The member set and call order are the AVF probe's (scion-avf, probe/app, Probe.kt),
 * which booted a custom kernel + initrd this way on a Pixel 10 (Android 17). Resolved once per
 * process ([instance]): an exemption only applies to processes started after it was set.
 */
class AvfApi private constructor(
    private val classes: Map<String, Class<*>>,
    private val methods: Map<String, Method>,
    private val ctors: Map<String, Constructor<*>>,
    /** Every member in [MEMBERS] that didn't resolve, in table order. */
    val unreachable: List<Member>,
) {
    /** One reflective member. [key] is "Class.member"; [hidden] = `@hide`, needs [EXEMPTIONS]. */
    data class Member(val key: String, val hidden: Boolean, val optional: Boolean)

    /** The framework classes are on the boot classpath (false before Android 14 or without the APEX). */
    val present: Boolean get() = CLASSES.keys.all { it in classes }

    /** Required members that didn't resolve: non-empty means no custom VM can be built. */
    val blocked: List<Member> get() = unreachable.filter { !it.optional }

    /** virtio-blk disks need the (optional) Disk members too. */
    val disksSupported: Boolean get() = unreachable.none { it.key in DISK_MEMBERS }

    /** The custom-image builder can ask for a network device (`useNetwork`, Android 15+). */
    val networkSupported: Boolean get() = unreachable.none { it.key == "ImageBuilder.useNetwork" }

    /**
     * Whether virtualizationservice was built with its network feature
     * ([FEATURE_NETWORK]: a TAP from vmnic, tethered by the system). Null if it can't be asked.
     */
    fun networkFeature(manager: Any): Boolean? =
        runCatching { call("Manager.isFeatureEnabled", manager, FEATURE_NETWORK) as Boolean }.getOrNull()

    // ---- VirtualMachineManager ------------------------------------------------

    /** The VirtualMachineManager system service, or null if the device has none. */
    fun manager(ctx: Context): Any? {
        @Suppress("UNCHECKED_CAST")
        return ctx.getSystemService(cls("Manager") as Class<Any>)
    }

    /** Bit set of [CAPABILITY_PROTECTED_VM] and [CAPABILITY_NON_PROTECTED_VM]. */
    fun capabilities(manager: Any): Int = call("Manager.getCapabilities", manager) as Int

    /** Creates VM instance [name] from [config], first deleting a stale instance of that name. */
    fun create(manager: Any, name: String, config: Any): Vm {
        if (call("Manager.get", manager, name) != null) call("Manager.delete", manager, name)
        return Vm(call("Manager.create", manager, name, config)!!)
    }

    /** Deletes instance [name] (its directory, <app data>/vm/<name>); the VM must be stopped. */
    fun delete(manager: Any, name: String) {
        if (call("Manager.get", manager, name) != null) call("Manager.delete", manager, name)
    }

    // ---- config ---------------------------------------------------------------

    /** What to boot. Paths must be files the app can open; the framework hands their fds to crosvm. */
    data class Spec(
        val name: String,
        val kernel: String,
        val initrd: String?,
        val params: List<String>,
        val disks: List<Disk>,
        val memoryBytes: Long,
        /** One vCPU per host CPU instead of a single vCPU: the only two topologies the API offers. */
        val matchHostCpus: Boolean,
        /** A virtio-gpu with 3D (crosvm --gpu); null = none. */
        val gpu: Gpu? = null,
        /**
         * A virtio-net device on a TAP that virtualizationservice makes and Android's tethering
         * serves (DHCP, DNS, NAT): the same network the Terminal app's VM gets.
         */
        val network: Boolean = false,
    )

    /**
     * crosvm's virtio-gpu, through the @hide VirtualMachineCustomImageConfig.GpuConfig: on the
     * Pixel 10 the backend is gfxstream, which draws with the phone's GPU (EGL/GLES, or Vulkan).
     */
    data class Gpu(
        val backend: String = "gfxstream",
        val contextTypes: List<String> = listOf("gfxstream-gles"),
        val egl: Boolean = true,
        val gles: Boolean = true,
        val surfaceless: Boolean = true,
        val vulkan: Boolean = false,
    )

    data class Disk(val path: String, val writable: Boolean)

    /**
     * The custom-image builder, handed to [AvfIo.configure] before the config is built so it can
     * ask for devices (display, GPU, input) without this class knowing about them.
     */
    class ImageBuilder internal constructor(val raw: Any) {
        /**
         * Calls the public builder method [name] with [args] (matched by count and type).
         * Returns false, without throwing, if the method doesn't exist or is hidden from us.
         */
        fun call(name: String, vararg args: Any?): Boolean {
            val m = raw.javaClass.methods.firstOrNull { m ->
                m.name == name && m.parameterTypes.size == args.size &&
                    m.parameterTypes.zip(args).all { (t, a) -> a == null || box(t).isInstance(a) }
            } ?: return false
            invoke("ImageBuilder.$name", m, raw, *args)
            return true
        }
    }

    /**
     * A VirtualMachineConfig for a non-protected VM booting [spec]'s kernel, with console and
     * log output captured (that needs DEBUG_LEVEL_FULL). [configure] may add devices.
     */
    fun buildConfig(ctx: Context, spec: Spec, configure: (ImageBuilder) -> Unit = {}): Any {
        if (spec.disks.isNotEmpty() && !disksSupported) {
            throw AvfException("disk images need VirtualMachineCustomImageConfig.Disk, which the hidden-API policy blocks")
        }
        val img = ctor("ImageBuilder").let { invokeCtor("ImageBuilder.<init>", it) }
        call("ImageBuilder.setName", img, spec.name)
        call("ImageBuilder.setKernelPath", img, spec.kernel)
        spec.initrd?.let { call("ImageBuilder.setInitrdPath", img, it) }
        for (p in spec.params) call("ImageBuilder.addParam", img, p)
        for (d in spec.disks) {
            val disk = call(if (d.writable) "Disk.RWDisk" else "Disk.RODisk", null, d.path)
            call("ImageBuilder.addDisk", img, disk)
        }
        if (spec.network) {
            if (!networkSupported) throw AvfException("a network device needs VirtualMachineCustomImageConfig.Builder.useNetwork, which this Android doesn't have")
            call("ImageBuilder.useNetwork", img, true)
        }
        configure(ImageBuilder(img))
        spec.gpu?.let { setGpu(img, it) }
        val image = call("ImageBuilder.build", img)

        val c = invokeCtor("ConfigBuilder.<init>", ctor("ConfigBuilder"), ctx)
        call("ConfigBuilder.setCustomImageConfig", c, image)
        call("ConfigBuilder.setProtectedVm", c, false)
        call("ConfigBuilder.setMemoryBytes", c, spec.memoryBytes)
        call("ConfigBuilder.setCpuTopology", c,
            constant("Config", if (spec.matchHostCpus) "CPU_TOPOLOGY_MATCH_HOST" else "CPU_TOPOLOGY_ONE_CPU",
                if (spec.matchHostCpus) 1 else 0))
        call("ConfigBuilder.setDebugLevel", c, constant("Config", "DEBUG_LEVEL_FULL", 1))
        call("ConfigBuilder.setVmOutputCaptured", c, true)
        return call("ConfigBuilder.build", c)!!
    }

    /** GpuConfig.Builder by reflection (not in [MEMBERS]: optional, and nested in a hidden class). */
    private fun setGpu(img: Any, gpu: Gpu) {
        try {
            val cls = Class.forName("$PKG.VirtualMachineCustomImageConfig\$GpuConfig\$Builder")
            var b: Any = cls.getConstructor().newInstance()
            fun set(name: String, arg: Any) {
                val m = b.javaClass.methods.first { it.name == name && it.parameterTypes.size == 1 }
                b = m.invoke(b, arg) ?: b
            }
            set("setBackend", gpu.backend)
            set("setContextTypes", gpu.contextTypes.toTypedArray())
            set("setRendererUseEgl", gpu.egl)
            set("setRendererUseGles", gpu.gles)
            set("setRendererUseSurfaceless", gpu.surfaceless)
            set("setRendererUseVulkan", gpu.vulkan)
            val config = b.javaClass.getMethod("build").invoke(b)
            img.javaClass.methods.first { it.name == "setGpuConfig" && it.parameterTypes.size == 1 }.invoke(img, config)
        } catch (t: Throwable) {
            throw wrap("GpuConfig", t)
        }
    }

    // ---- VirtualMachine -------------------------------------------------------

    /** Lifecycle callbacks, delivered on the executor given to [Vm.setCallback]. */
    interface Listener {
        fun onStopped(reason: Int)
        fun onError(code: Int, message: String)
        /** onPayloadStarted / onPayloadReady / onPayloadFinished (Microdroid-style; rare for custom VMs). */
        fun onEvent(name: String) {}
    }

    /** One VirtualMachine instance. */
    inner class Vm internal constructor(val raw: Any) {
        fun setCallback(executor: Executor, listener: Listener) {
            val cb = cls("Callback")
            val proxy = Proxy.newProxyInstance(cb.classLoader, arrayOf(cb)) { self, m, args ->
                when (m.name) {
                    "onStopped" -> { listener.onStopped(args[1] as Int); null }
                    "onError" -> { listener.onError(args[1] as Int, args[2]?.toString().orEmpty()); null }
                    "onPayloadStarted", "onPayloadReady", "onPayloadFinished" -> { listener.onEvent(m.name); null }
                    "hashCode" -> System.identityHashCode(self)
                    "equals" -> self === args?.get(0)
                    "toString" -> "AvfCallback"
                    else -> null
                }
            }
            call("VM.setCallback", raw, executor, proxy)
        }

        /** Starts the VM; returns once crosvm is running. */
        fun run() { call("VM.run", raw) }

        /** Stops it like pulling the plug; onStopped follows. */
        fun stop() { call("VM.stop", raw) }

        /** STATUS_STOPPED = 0, STATUS_RUNNING = 1, STATUS_DELETED = 2. */
        val status: Int get() = call("VM.getStatus", raw) as Int

        /** The guest console (ttyS0 and hvc0); available after [run]. */
        fun consoleOutput(): InputStream = call("VM.getConsoleOutput", raw) as InputStream

        /** The guest log port (hvc2); available after [run]. */
        fun logOutput(): InputStream = call("VM.getLogOutput", raw) as InputStream

        /** Releases the instance's binder and file handles. */
        fun close() { call("VM.close", raw) }
    }

    /** STOP_REASON_* name for [code], from the runtime's own constants. */
    fun stopReasonName(code: Int): String = constantName("STOP_REASON_", code)

    /** ERROR_* name for [code]. */
    fun errorName(code: Int): String = constantName("ERROR_", code)

    /** VirtualMachineCallback.STOP_REASON_SHUTDOWN: the guest powered itself off. */
    val stopReasonShutdown: Int get() = constant("Callback", "STOP_REASON_SHUTDOWN", 5)

    /** VirtualMachineCallback.STOP_REASON_KILLED: stopped from the host (our stop()). */
    val stopReasonKilled: Int get() = constant("Callback", "STOP_REASON_KILLED", 3)

    // ---- plumbing -------------------------------------------------------------

    private fun cls(key: String): Class<*> = classes[key] ?: throw AvfException("class $key unavailable")

    private fun ctor(key: String): Constructor<*> =
        ctors["$key.<init>"] ?: throw AvfException("$key.<init> unavailable (hidden-API policy)")

    private fun call(key: String, target: Any?, vararg args: Any?): Any? =
        invoke(key, methods[key] ?: throw AvfException("$key unavailable (hidden-API policy)"), target, *args)

    private fun invokeCtor(key: String, c: Constructor<*>, vararg args: Any?): Any = try {
        c.newInstance(*args)
    } catch (t: Throwable) {
        throw wrap(key, t)
    }

    private fun constant(clsKey: String, name: String, fallback: Int): Int =
        runCatching { cls(clsKey).getField(name).getInt(null) }.getOrDefault(fallback)

    private fun constantName(prefix: String, code: Int): String =
        classes["Callback"]?.fields
            ?.firstOrNull { it.name.startsWith(prefix) && Modifier.isStatic(it.modifiers) && runCatching { it.getInt(null) }.getOrNull() == code }
            ?.name?.removePrefix(prefix) ?: "code $code"

    companion object {
        private const val PKG = "android.system.virtualmachine"
        const val FEATURE = "android.software.virtualization_framework"
        const val PERM_MANAGE = "android.permission.MANAGE_VIRTUAL_MACHINE"
        const val PERM_CUSTOM = "android.permission.USE_CUSTOM_VIRTUAL_MACHINE"
        const val CAPABILITY_PROTECTED_VM = 1
        const val CAPABILITY_NON_PROTECTED_VM = 2
        /** VirtualMachineManager.FEATURE_NETWORK. */
        const val FEATURE_NETWORK = "com.android.kvm.NETWORK"

        /**
         * Signature prefixes for `settings put global hidden_api_blacklist_exemptions`, which takes
         * them comma-separated (a ':' list is silently ignored). The first covers the whole
         * custom-image config (builder, disks, and the display/input configs [AvfIo] will use);
         * the second is the one @hide setter on the public config builder.
         */
        val EXEMPTIONS = listOf(
            "Landroid/system/virtualmachine/VirtualMachineCustomImageConfig",
            "Landroid/system/virtualmachine/VirtualMachineConfig\$Builder;->setCustomImageConfig(",
        )

        /**
         * With the Network setting: VirtualMachineManager.isFeatureEnabled is @SystemApi and
         * blocked for a sideloaded app, so asking whether AVF has networking needs this prefix too.
         * Without it the app can't ask, but still requests the card ([Spec.network]).
         */
        val NETWORK_EXEMPTIONS = listOf(
            "Landroid/system/virtualmachine/VirtualMachineManager;->isFeatureEnabled(",
        )

        /** Resolved on first use, once per process. */
        val instance: AvfApi by lazy { resolve() }

        private val CLASSES = linkedMapOf(
            "Manager" to "VirtualMachineManager",
            "Config" to "VirtualMachineConfig",
            "ConfigBuilder" to "VirtualMachineConfig\$Builder",
            "Image" to "VirtualMachineCustomImageConfig",
            "ImageBuilder" to "VirtualMachineCustomImageConfig\$Builder",
            "Disk" to "VirtualMachineCustomImageConfig\$Disk",
            "VM" to "VirtualMachine",
            "Callback" to "VirtualMachineCallback",
        )

        private val DISK_MEMBERS = setOf("Disk.RWDisk", "Disk.RODisk", "ImageBuilder.addDisk")

        /** key -> parameter types, by class key or primitive/JDK name. `<init>` = constructor. */
        private class Def(val key: String, val params: List<String>, val hidden: Boolean, val optional: Boolean = false)

        private val MEMBERS = listOf(
            Def("Manager.getCapabilities", listOf(), false),
            Def("Manager.get", listOf("String"), false),
            Def("Manager.delete", listOf("String"), false),
            Def("Manager.create", listOf("String", "Config"), false),
            Def("Manager.isFeatureEnabled", listOf("String"), true, optional = true),
            Def("ConfigBuilder.<init>", listOf("Context"), false),
            Def("ConfigBuilder.setProtectedVm", listOf("boolean"), false),
            Def("ConfigBuilder.setMemoryBytes", listOf("long"), false),
            Def("ConfigBuilder.setCpuTopology", listOf("int"), false),
            Def("ConfigBuilder.setDebugLevel", listOf("int"), false),
            Def("ConfigBuilder.setVmOutputCaptured", listOf("boolean"), false),
            Def("ConfigBuilder.build", listOf(), false),
            Def("ConfigBuilder.setCustomImageConfig", listOf("Image"), true),
            Def("ImageBuilder.<init>", listOf(), true),
            Def("ImageBuilder.setName", listOf("String"), true),
            Def("ImageBuilder.setKernelPath", listOf("String"), true),
            Def("ImageBuilder.setInitrdPath", listOf("String"), true),
            Def("ImageBuilder.addParam", listOf("String"), true),
            Def("ImageBuilder.build", listOf(), true),
            Def("ImageBuilder.addDisk", listOf("Disk"), true, optional = true),
            Def("ImageBuilder.useNetwork", listOf("boolean"), true, optional = true),
            Def("Disk.RWDisk", listOf("String"), true, optional = true),
            Def("Disk.RODisk", listOf("String"), true, optional = true),
            Def("VM.setCallback", listOf("Executor", "Callback"), false),
            Def("VM.run", listOf(), false),
            Def("VM.stop", listOf(), false),
            Def("VM.getStatus", listOf(), false),
            Def("VM.getConsoleOutput", listOf(), false),
            Def("VM.getLogOutput", listOf(), false),
            Def("VM.close", listOf(), false),
        )

        private fun resolve(): AvfApi {
            val classes = CLASSES.mapNotNull { (k, n) ->
                runCatching { Class.forName("$PKG.$n") }.getOrNull()?.let { k to it }
            }.toMap()
            fun type(n: String): Class<*>? = when (n) {
                "String" -> String::class.java
                "boolean" -> Boolean::class.javaPrimitiveType
                "long" -> Long::class.javaPrimitiveType
                "int" -> Int::class.javaPrimitiveType
                "Context" -> Context::class.java
                "Executor" -> Executor::class.java
                else -> classes[n]
            }
            val methods = mutableMapOf<String, Method>()
            val ctors = mutableMapOf<String, Constructor<*>>()
            val missing = mutableListOf<Member>()
            for (d in MEMBERS) {
                val owner = classes[d.key.substringBefore('.')]
                val types = d.params.map { type(it) }
                val name = d.key.substringAfter('.')
                // A hidden member is simply invisible to getMethod/getConstructor.
                val ok = if (owner == null || types.any { it == null }) false
                else if (name == "<init>") runCatching { owner.getConstructor(*types.map { it!! }.toTypedArray()) }
                    .getOrNull()?.also { ctors[d.key] = it } != null
                else runCatching { owner.getMethod(name, *types.map { it!! }.toTypedArray()) }
                    .getOrNull()?.also { methods[d.key] = it } != null
                if (!ok) missing += Member(d.key, d.hidden, d.optional)
            }
            return AvfApi(classes, methods, ctors, missing)
        }

        private fun invoke(key: String, m: Method, target: Any?, vararg args: Any?): Any? = try {
            m.invoke(target, *args)
        } catch (t: Throwable) {
            throw wrap(key, t)
        }

        private fun wrap(key: String, t: Throwable): AvfException {
            val cause = if (t is InvocationTargetException) t.targetException ?: t else t
            return AvfException("$key: ${cause.javaClass.simpleName}: ${cause.message}", cause)
        }

        private fun box(t: Class<*>): Class<*> = when (t) {
            Boolean::class.javaPrimitiveType -> java.lang.Boolean::class.java
            Int::class.javaPrimitiveType -> java.lang.Integer::class.java
            Long::class.javaPrimitiveType -> java.lang.Long::class.java
            Float::class.javaPrimitiveType -> java.lang.Float::class.java
            else -> t
        }
    }
}
