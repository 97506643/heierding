package h.heiErDing.event

fun interface EventHandler<E> {
    fun handleEvent(event: E)
}
