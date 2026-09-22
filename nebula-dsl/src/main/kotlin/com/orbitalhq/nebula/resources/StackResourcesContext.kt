package com.orbitalhq.nebula.resources

/**
 * Carries the resources of the stack currently being compiled to the
 * `stack { }` block inside the script.
 *
 * A stack script is a Kotlin script whose `stack { }` block takes no arguments -
 * that's the whole point of the DSL, and we don't want every script to have to
 * declare where its files come from. The script body runs synchronously on the
 * thread that calls the scripting host, so the executor unpacks the bundle,
 * binds it here for the duration of the evaluation, and `stack { }` picks it up.
 *
 * Outside a submission (the CLI, a test constructing a stack by hand) there is
 * nothing bound and stacks get [StackResources.NONE].
 */
object StackResourcesContext {
    private val current = ThreadLocal<StackResources>()

    /**
     * Binds [resources] for the duration of [block], restoring whatever was
     * previously bound. Nesting is supported so a script that (unusually)
     * compiles another script doesn't clobber the outer binding.
     */
    fun <T> withResources(resources: StackResources, block: () -> T): T {
        val previous = current.get()
        current.set(resources)
        try {
            return block()
        } finally {
            if (previous == null) current.remove() else current.set(previous)
        }
    }

    /** The resources bound for the current thread, or [StackResources.NONE]. */
    fun current(): StackResources = current.get() ?: StackResources.NONE
}
