package es.jvbabi.overmail.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import es.jvbabi.overmail.BuildKonfig
import es.jvbabi.overmail.data.database.OvermailDatabase
import es.jvbabi.overmail.data.repository.OvermailAppRepositoryImpl
import es.jvbabi.overmail.data.repository.UpdateRepositoryImpl
import es.jvbabi.overmail.data.repository.fake.FakeOvermailAppRepository
import es.jvbabi.overmail.data.repository.fake.FakeUpdateRepository
import es.jvbabi.overmail.domain.repository.OvermailAppRepository
import es.jvbabi.overmail.domain.repository.UpdateRepository
import es.jvbabi.overmail.domain.usecase.app.CheckAppIsLatestVersionUseCase
import es.jvbabi.overmail.domain.usecase.app.GetReleaseChangelogsUseCase
import es.jvbabi.overmail.ui.overlay.update_available.UpdateAvailableViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.mp.KoinPlatformTools

actual fun getDatabaseBuilder(): RoomDatabase.Builder<OvermailDatabase> {
    val context = KoinPlatformTools.defaultContext().get().get<Context>()

    return Room.databaseBuilder<OvermailDatabase>(
        context = context,
        name = context.getDatabasePath("overmail.db").absolutePath
    )
}

/**
 * Android is the only platform that updates itself, so the whole updater is declared here.
 *
 * What the updater talks to the outside world through — releases on GitHub, the file system, the
 * system installer — comes from one of two interchangeable scenarios, so the flow can be walked
 * through on a machine with nothing to update to.
 */
actual fun platformModule(): Module = module {
    includes(if (BuildKonfig.FAKE_UPDATE) fakeUpdateScenario else realUpdateScenario)

    singleOf(::CheckAppIsLatestVersionUseCase)
    singleOf(::GetReleaseChangelogsUseCase)

    viewModelOf(::UpdateAvailableViewModel)
}

/** The real thing: releases read from GitHub, APKs written to disk, installs handed to the system. */
private val realUpdateScenario = module {
    single<OvermailAppRepository> {
        OvermailAppRepositoryImpl(
            httpClient = get(named(KOIN_HTTP_CLIENT_THIRD_PARTY)),
        )
    }

    single<UpdateRepository> { realUpdateRepository() }
}

/**
 * The fakes, switched on with `app.dev.fake-update=true` in `local.properties`.
 *
 * There is always an update to install, no request reaches GitHub, and nothing is written or
 * installed — see [FakeOvermailAppRepository] and [FakeUpdateRepository] for what each of them stands
 * in for. Worth reaching for beyond the obvious: GitHub allows an unauthenticated client 60 requests
 * an hour, and working on this flow for real goes through that in minutes.
 */
private val fakeUpdateScenario = module {
    single<OvermailAppRepository> { FakeOvermailAppRepository() }

    // Wrapped rather than replaced: everything the system decides rather than we do — whether the app
    // may install, where the settings and Downloads screens are — should behave exactly as it will in
    // production, so only the steps that need a genuine APK are simulated.
    single<UpdateRepository> { FakeUpdateRepository(real = realUpdateRepository()) }
}

private fun org.koin.core.scope.Scope.realUpdateRepository() = UpdateRepositoryImpl(
    context = get(),
    httpClient = get(named(KOIN_HTTP_CLIENT_THIRD_PARTY)),
)
