package com.konecto.series76.core

import javax.inject.Qualifier

/** Dispatcher for blocking I/O (Room, DataStore, OkHttp). Injected so tests can swap it. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

/** Dispatcher for CPU work (JSON mapping, date formatting, shader fallback rendering). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

/** Process-wide scope for work that must outlive a screen (outbox sync, connectivity listeners). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
