package com.tosasitill.az100

import com.highcapable.kavaref.resolver.ConstructorResolver
import com.highcapable.kavaref.resolver.MethodResolver
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable
import java.util.concurrent.atomic.AtomicInteger

/** KavaRef selects the member; libxposed registers the actual runtime hook. */
class HookRuntime(
    private val framework: XposedInterface,
    val appClassLoader: ClassLoader
) {
    private val installedHooks = AtomicInteger()

    val hookCount: Int get() = installedHooks.get()

    fun MethodResolver<*>?.hook(callbacks: HookCallbacks.() -> Unit) =
        register(this?.self ?: throw NoSuchMethodException("Hook method not found"), callbacks)

    fun ConstructorResolver<*>?.hook(callbacks: HookCallbacks.() -> Unit) =
        register(this?.self ?: throw NoSuchMethodException("Hook constructor not found"), callbacks)

    fun Executable.hook(callbacks: HookCallbacks.() -> Unit) = register(this, callbacks)

    fun List<MethodResolver<*>>.hookAll(callbacks: HookCallbacks.() -> Unit) {
        if (isEmpty()) throw NoSuchMethodException("No hook methods found")
        forEach { register(it.self, callbacks) }
    }

    private fun register(member: Executable, configure: HookCallbacks.() -> Unit): XposedInterface.HookHandle {
        val callbacks = HookCallbacks().apply(configure)
        val handle = framework.hook(member).intercept { chain ->
            callbacks.invoke(
                chain.thisObject,
                chain.args.toTypedArray(),
                { args -> chain.proceed(args) },
                { t -> Logs.e("hook callback failed: ${member.declaringClass.name}#${member.name}", t) }
            )
        }
        installedHooks.incrementAndGet()
        return handle
    }
}
