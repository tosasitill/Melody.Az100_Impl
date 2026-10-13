package com.tosasitill.az100

import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.condition.MethodCondition
import com.highcapable.kavaref.condition.type.Modifiers
import com.highcapable.kavaref.resolver.ConstructorResolver
import com.highcapable.kavaref.resolver.FieldResolver
import com.highcapable.kavaref.resolver.MethodResolver
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * The reflection this module was written against, with KavaRef doing the lookup.
 *
 * ## Why a shim instead of a straight rewrite to the KavaRef DSL
 *
 * Every hook here was written and verified on device against
 * `de.robv.android.xposed.XposedHelpers`, and that helper's looseness is
 * load-bearing:
 *
 * | lookup | `XposedHelpers` | KavaRef default |
 * | --- | --- | --- |
 * | parameter match | assignable, best match wins | exact equality |
 * | superclass walk | always, skipping superclass privates | off unless `superclass()` |
 * | field lookup | recursive up the hierarchy | declaring class only |
 *
 * The AOSP and OPPO classes this module hooks hit exactly those cases:
 * `okToConnect` and `getConnectionPolicy` are declared once on a shared
 * `ConnectableProfile` base rather than on each profile service, `mGroupStatus` is
 * read through a class that does not declare it, and several calls pass a subtype
 * where the signature names the supertype.  A mechanical
 * `callMethod` -> `firstMethod {}` rewrite compiles and then throws `NoSuchMethod`
 * on the device -- the failure this module spent most of its debugging budget
 * learning to avoid.
 *
 * So the *semantics* stay, and the *engine* changes: KavaRef resolves every
 * member, and only the invocation itself is plain `java.lang.reflect`.  That is
 * the direction YukiHookAPI is going anyway -- 2.0.0 removes Yuki's own
 * reflection API in favour of KavaRef, and `XposedHelpers` is the legacy API this
 * migration exists to leave behind.
 *
 * KavaRef's own `superclass()` walk is *not* used.  It only continues when the
 * current class produced nothing, which inverts the helper's precedence: an
 * exact hit on a subclass must always beat an assignable hit on a base class,
 * but `firstMethodOrNull { superclass() }` would return the subclass's
 * assignable near-miss the moment it is non-empty.  So the hierarchy is walked
 * here, one class at a time, with `superclass()` left off -- KavaRef does the
 * per-class resolution and the widening comparison lives in its `parameters {}`
 * predicate.
 *
 * ## Resolution order
 *
 * [findMethodBestMatch] mirrors the Xposed helper exactly:
 *
 * 1. the class itself, exact parameter types
 * 2. the class itself, assignable parameter types (fewest widening steps wins)
 * 3. each superclass in turn, the same two passes, privates excluded
 *
 * The order is why an exact hit on the class always beats an assignable hit on a
 * base class, and it is preserved rather than folded into one KavaRef condition.
 *
 * A null argument matches any non-primitive parameter.  The Xposed helper gets
 * away without this because it derives the signature from the argument objects
 * and never sees a null; here callers pass null on purpose (probing a method to
 * see what it does), so the null is treated as "unknown type, accept".
 *
 * ## Caching
 *
 * Results are cached per (class, name, signature), including misses -- the old
 * helper cached negative results as `null` for the same reason: a hook that runs
 * on every reconnect should not pay for a miss every time.
 */
object Reflect {

    private val methods = HashMap<String, Any>()
    private val fields = HashMap<String, Any>()
    private val constructors = HashMap<String, Any>()

    /** Cached negative result. */
    private object Missing

    private val lock = Any()

    /* ------------------------------------------------------------------ */
    /* classes                                                             */
    /* ------------------------------------------------------------------ */

    /**
     * The loader's own `loadClass`, then `Class.forName`.
     *
     * `forName` second because a few vendor classes are only visible to the boot
     * loader, which is not the loader the hook was handed.
     */
    @Throws(ClassNotFoundException::class)
    fun findClass(name: String, loader: ClassLoader?): Class<*> {
        if (loader != null) {
            try {
                return loader.loadClass(name)
            } catch (ignored: ClassNotFoundException) {
                // fall through to forName
            }
        }
        return Class.forName(name, false, loader)
    }

    /* ------------------------------------------------------------------ */
    /* methods                                                             */
    /* ------------------------------------------------------------------ */

    /**
     * The named class only, exact types.  Kept as its own entry point because the
     * call sites that use it want exactly that -- see
     * [LeAudioTransportHook.findMethod] for the ones that do not.
     *
     * A null entry can never match a real parameter type, so it fails the lookup
     * rather than being skipped: callers writing a signature by hand get a
     * `NoSuchMethodException` instead of an unrelated overload.
     */
    @Throws(NoSuchMethodException::class)
    fun findMethodExact(clazz: Class<*>, name: String, vararg signature: Class<*>?): Method {
        if (signature.any { it == null }) throw NoSuchMethodException("${clazz.name}#$name")
        val types = Array(signature.size) { signature[it]!! }
        val method = clazz.getDeclaredMethod(name, *types)
        method.isAccessible = true
        return method
    }

    /** See the class comment for the pass order. */
    @Throws(NoSuchMethodException::class)
    fun findMethodBestMatch(clazz: Class<*>, name: String, vararg signature: Class<*>?): Method {
        val key = methodKey(clazz, name, signature)
        synchronized(lock) { methods[key] }?.let {
            if (it === Missing) throw NoSuchMethodException(key)
            return it as Method
        }
        val found = try {
            resolveBestMatch(clazz, name, signature)
        } catch (t: Throwable) {
            synchronized(lock) { methods[key] = Missing }
            throw t
        }
        synchronized(lock) { methods[key] = found }
        return found
    }

    private fun resolveBestMatch(clazz: Class<*>, name: String, signature: Array<out Class<*>?>): Method {
        var current: Class<*>? = clazz
        var considerPrivate = true
        while (current != null) {
            exactOnClass(current, name, signature)?.let { return it }
            assignableOnClass(current, name, signature, considerPrivate)?.let { return it }
            considerPrivate = false
            current = current.superclass
        }
        throw NoSuchMethodException("${clazz.name}#$name")
    }

    /** Pass 1: exact types, this class only. */
    private fun exactOnClass(clazz: Class<*>, name: String, signature: Array<out Class<*>?>): Method? {
        // A null argument cannot participate in an exact match; let pass 2 take it.
        if (signature.any { it == null }) return null
        val types = Array(signature.size) { signature[it]!! }
        val method = resolveOnClass(clazz) {
            this.name = name
            parameterCount(types.size)
            if (types.isNotEmpty()) parameters(*types)
        }.firstOrNull()?.self ?: return null
        method.isAccessible = true
        return method
    }

    /**
     * The `Method`s of one class matching [condition], KavaRef-resolved.
     *
     * `superclass()` is never set: the walk belongs to [resolveBestMatch], which
     * needs to stop at each level rather than fall through to the next one.
     */
    @Suppress("UNCHECKED_CAST")
    private fun resolveOnClass(
        clazz: Class<*>,
        condition: MethodCondition<Any>.() -> Unit,
    ): List<MethodResolver<Any>> =
        (clazz as Class<Any>).resolve().optional(silent = true).method(condition)

    /**
     * Pass 2: assignable types on one class, fewest widening steps wins.
     *
     * [considerPrivate] is false past the first class, matching the helper's
     * refusal to touch a superclass's private helpers.
     */
    private fun assignableOnClass(
        clazz: Class<*>,
        name: String,
        signature: Array<out Class<*>?>,
        considerPrivate: Boolean,
    ): Method? {
        val candidates = resolveOnClass(clazz) {
            this.name = name
            parameterCount(signature.size)
            parameters { formals -> wideningCost(signature, formals) != null }
            if (!considerPrivate) modifiersNot(Modifiers.PRIVATE)
        }
        var best: Method? = null
        var bestCost = Int.MAX_VALUE
        for (resolver in candidates) {
            val cost = wideningCost(signature, resolver.self.parameterTypes.toList()) ?: continue
            if (cost < bestCost) {
                best = resolver.self
                bestCost = cost
            }
        }
        best?.isAccessible = true
        return best
    }

    /**
     * Widening steps needed to pass [actual] where [formal] is wanted, or null when
     * the call is not legal.
     *
     * Null entries are skipped: an unknown argument type matches anything that is
     * not a primitive.
     */
    private fun wideningCost(actual: Array<out Class<*>?>, formal: List<Class<*>>): Int? {
        if (actual.size != formal.size) return null
        var cost = 0
        for (i in actual.indices) {
            val have = actual[i] ?: continue
            val want = formal[i]
            when {
                have == want -> Unit
                want.isAssignableFrom(have) -> cost++
                isBoxed(want, have) -> cost++
                else -> return null
            }
        }
        return cost
    }

    /**
     * `int` vs `Integer` and friends.
     *
     * The Xposed helper does not need this because it is handed the argument
     * classes and Kotlin has already boxed them to match the declaration.  Here a
     * signature can be written by hand (`Int::class.javaPrimitiveType`), so the
     * boxed form has to be accepted too.
     */
    private fun isBoxed(formal: Class<*>, actual: Class<*>): Boolean = when (formal) {
        java.lang.Integer.TYPE -> actual == java.lang.Integer::class.java
        java.lang.Long.TYPE -> actual == java.lang.Long::class.java
        java.lang.Boolean.TYPE -> actual == java.lang.Boolean::class.java
        java.lang.Short.TYPE -> actual == java.lang.Short::class.java
        java.lang.Byte.TYPE -> actual == java.lang.Byte::class.java
        java.lang.Character.TYPE -> actual == java.lang.Character::class.java
        java.lang.Float.TYPE -> actual == java.lang.Float::class.java
        java.lang.Double.TYPE -> actual == java.lang.Double::class.java
        else -> false
    }

    /** Argument classes the way the Xposed helper derives them. */
    private fun parameterTypesOf(args: Array<out Any?>): Array<Class<*>?> =
        Array(args.size) { args[it]?.javaClass }

    /* ------------------------------------------------------------------ */
    /* invocation                                                          */
    /* ------------------------------------------------------------------ */

    /**
     * Calls the best-matching method.
     *
     * An `InvocationTargetException` is unwrapped so the caller sees the callee's
     * own exception -- the hooks match on the real type (a vendor NPE, an
     * `IllegalState`), not on the reflection wrapper.
     */
    fun callMethod(receiver: Any?, name: String, vararg args: Any?): Any? {
        requireNotNull(receiver) { "receiver must not be null" }
        val method = findMethodBestMatch(receiver.javaClass, name, *parameterTypesOf(args))
        return invoke(method, receiver, args)
    }

    /**
     * [callMethod] with an explicit signature.
     *
     * Needed whenever an argument is null: a derived signature cannot describe a
     * null, and the call sites that pass one are asking for a specific overload.
     */
    fun callMethod(receiver: Any?, name: String, signature: Array<Class<*>>, vararg args: Any?): Any? {
        requireNotNull(receiver) { "receiver must not be null" }
        val method = findMethodBestMatch(receiver.javaClass, name, *signature)
        return invoke(method, receiver, args)
    }

    fun callStaticMethod(clazz: Class<*>?, name: String, vararg args: Any?): Any? {
        requireNotNull(clazz) { "clazz must not be null" }
        val method = findMethodBestMatch(clazz, name, *parameterTypesOf(args))
        return invoke(method, null, args)
    }

    fun callStaticMethod(clazz: Class<*>?, name: String, signature: Array<Class<*>>, vararg args: Any?): Any? {
        requireNotNull(clazz) { "clazz must not be null" }
        val method = findMethodBestMatch(clazz, name, *signature)
        return invoke(method, null, args)
    }

    private fun invoke(method: Method, receiver: Any?, args: Array<out Any?>): Any? = try {
        method.invoke(receiver, *args)
    } catch (t: InvocationTargetException) {
        throw t.cause ?: t
    }

    /* ------------------------------------------------------------------ */
    /* fields                                                              */
    /* ------------------------------------------------------------------ */

    /**
     * The class first, then each superclass.
     *
     * KavaRef's field lookup is declaring-class-only by default, so the walk is
     * asked for explicitly.
     */
    @Throws(NoSuchFieldException::class)
    fun findField(clazz: Class<*>, name: String): Field {
        val key = "${clazz.name}#$name"
        synchronized(lock) { fields[key] }?.let {
            if (it === Missing) throw NoSuchFieldException(key)
            return it as Field
        }
        val field = try {
            resolveField(clazz, name) ?: throw NoSuchFieldException(key)
        } catch (t: Throwable) {
            synchronized(lock) { fields[key] = Missing }
            throw t
        }
        synchronized(lock) { fields[key] = field }
        return field
    }

    /**
     * This class, then each superclass -- `findFieldRecursiveImpl` in the helper.
     *
     * The helper stops before `Object`, which is not worth reproducing: `Object`
     * declares no instance fields a hook could want.
     */
    @Suppress("UNCHECKED_CAST")
    private fun resolveField(clazz: Class<*>, name: String): Field? {
        var current: Class<*>? = clazz
        while (current != null) {
            val resolver: FieldResolver<Any>? = (current as Class<Any>)
                .resolve()
                .optional(silent = true)
                .firstFieldOrNull { this.name = name }
            val field = resolver?.self
            if (field != null) {
                field.isAccessible = true
                return field
            }
            current = current.superclass
        }
        return null
    }

    fun getObjectField(receiver: Any?, name: String): Any? {
        requireNotNull(receiver) { "receiver must not be null" }
        return findField(receiver.javaClass, name).get(receiver)
    }

    fun getStaticObjectField(clazz: Class<*>?, name: String): Any? {
        requireNotNull(clazz) { "clazz must not be null" }
        return findField(clazz, name).get(null)
    }

    fun setObjectField(receiver: Any?, name: String, value: Any?) {
        requireNotNull(receiver) { "receiver must not be null" }
        findField(receiver.javaClass, name).set(receiver, value)
    }

    fun setStaticBooleanField(clazz: Class<*>?, name: String, value: Boolean) {
        requireNotNull(clazz) { "clazz must not be null" }
        findField(clazz, name).setBoolean(null, value)
    }

    /* ------------------------------------------------------------------ */
    /* construction                                                        */
    /* ------------------------------------------------------------------ */

    /** Same best-match rule as [findMethodBestMatch], applied to constructors. */
    fun newInstance(clazz: Class<*>, vararg args: Any?): Any {
        val signature = parameterTypesOf(args)
        val key = "${clazz.name}<init>(${signature.joinToString(",") { it?.name ?: "*" }})"
        synchronized(lock) { constructors[key] }?.let {
            if (it !== Missing) return construct(it as Constructor<*>, args)
        }
        val constructor = try {
            resolveConstructor(clazz, signature) ?: throw NoSuchMethodException(key)
        } catch (t: Throwable) {
            synchronized(lock) { constructors[key] = Missing }
            throw t
        }
        synchronized(lock) { constructors[key] = constructor }
        return construct(constructor, args)
    }

    @Suppress("UNCHECKED_CAST")
    private fun resolveConstructor(clazz: Class<*>, signature: Array<Class<*>?>): Constructor<*>? {
        val candidates: List<ConstructorResolver<Any>> = (clazz as Class<Any>)
            .resolve()
            .optional(silent = true)
            .constructor {
                parameterCount(signature.size)
                parameters { formals -> wideningCost(signature, formals) != null }
            }
        var best: Constructor<*>? = null
        var bestCost = Int.MAX_VALUE
        for (resolver in candidates) {
            val cost = wideningCost(signature, resolver.self.parameterTypes.toList()) ?: continue
            if (cost < bestCost) {
                best = resolver.self
                bestCost = cost
            }
        }
        return best
    }

    private fun construct(constructor: Constructor<*>, args: Array<out Any?>): Any = try {
        constructor.isAccessible = true
        constructor.newInstance(*args)
    } catch (t: InvocationTargetException) {
        throw t.cause ?: t
    }

    private fun methodKey(clazz: Class<*>, name: String, signature: Array<out Class<*>?>): String =
        buildString {
            append(clazz.name).append('#').append(name).append('(')
            signature.forEach { append(it?.name ?: "*").append(',') }
            append(')')
        }
}
