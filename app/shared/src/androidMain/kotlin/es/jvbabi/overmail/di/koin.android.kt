package es.jvbabi.overmail.di

import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Empty for now: everything Android contributes is registered from `MainApplication`, which is the
 * only place that has the application context.
 */
actual fun platformModule(): Module = module { }
