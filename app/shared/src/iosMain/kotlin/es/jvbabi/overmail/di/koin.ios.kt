package es.jvbabi.overmail.di

import org.koin.core.module.Module
import org.koin.dsl.module

/** Empty for now: everything iOS contributes is registered from `MainViewController`. */
actual fun platformModule(): Module = module { }
