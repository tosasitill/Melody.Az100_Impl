package com.tosasitill.az100

/** Mutable state shared by the before/after callbacks of one invocation. */
class HookCall internal constructor(
    val instanceOrNull: Any?,
    val args: Array<Any?>
) {
    var state: Any? = null

    private var value: Any? = null
    private var failure: Throwable? = null
    internal var returnEarly = false
        private set

    var result: Any?
        get() = value
        set(result) {
            complete(result, null)
            returnEarly = true
        }

    val hasThrowable: Boolean get() = failure != null
    val throwable: Throwable? get() = failure

    internal fun complete(result: Any?, error: Throwable?) {
        value = result
        failure = error
        returnEarly = false
    }

    internal fun resultOrThrow(): Any? {
        failure?.let { throw it }
        return value
    }
}

/** Keeps the existing callbacks while executing through a libxposed interceptor. */
class HookCallbacks {
    private var beforeCallback: (HookCall.() -> Unit)? = null
    private var afterCallback: (HookCall.() -> Unit)? = null

    fun before(callback: HookCall.() -> Unit) {
        beforeCallback = callback
    }

    fun after(callback: HookCall.() -> Unit) {
        afterCallback = callback
    }

    internal fun invoke(
        receiver: Any?,
        arguments: Array<Any?>,
        proceed: (Array<Any?>) -> Any?,
        reportFailure: (Throwable) -> Unit
    ): Any? {
        val call = HookCall(receiver, arguments.copyOf())
        try {
            beforeCallback?.invoke(call)
        } catch (t: Throwable) {
            reportFailure(t)
            // A failing behaviour hook must not corrupt the original call.
            arguments.copyInto(call.args)
            call.complete(null, null)
        }
        if (!call.returnEarly) {
            try {
                call.complete(proceed(call.args), null)
            } catch (t: Throwable) {
                call.complete(null, t)
            }
        }
        val originalResult = call.result
        val originalFailure = call.throwable
        try {
            afterCallback?.invoke(call)
        } catch (t: Throwable) {
            reportFailure(t)
            call.complete(originalResult, originalFailure)
        }
        return call.resultOrThrow()
    }
}
