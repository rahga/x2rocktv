package com.rahga.x2rock.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.rahga.x2rock.lan.SonosHousehold
import com.rahga.x2rock.radio.RadioDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Test
import org.junit.Rule
import com.rahga.x2rock.apple.ITunesSearch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import kotlin.coroutines.CoroutineContext
import java.util.concurrent.Executors

/** Models Main.immediate: the replacement starts inline, before the old cancellation resumes. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReplacedReadTest {
    @get:Rule val baselineMain = MainDispatcherRule()
    @Test fun replacedCategoryMustStayLoading() = checkReplacement(apple = false)

    @Test fun replacedAppleSearchMustStayLoading() = checkReplacement(apple = true)

    private fun checkReplacement(apple: Boolean) = runBlocking<Unit> {
        val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "review-main") }
        val main = object : MainCoroutineDispatcher() {
            override val immediate: MainCoroutineDispatcher get() = this
            override fun isDispatchNeeded(context: CoroutineContext) = Thread.currentThread().name != "review-main"
            override fun dispatch(context: CoroutineContext, block: Runnable) { executor.execute(block) }
        }
        Dispatchers.setMain(main)
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
            }
            start()
        }
        val owner = ViewModelStore()
        val scope = CoroutineScope(SupervisorJob())
        val household = SonosHousehold(scope, ssdp = { emptyList() })
        try {
            val vm = withContext(main) {
                val saved = SavedStateHandle(mapOf("groupId" to "unused"))
                val base = server.url("/").toString().trimEnd('/')
                val model = if (apple) AppleMusicViewModel(household, ITunesSearch(OkHttpClient(), base), saved).also {
                    it.setQuery("first")
                    it.search()
                } else RadioViewModel(household, RadioDirectory(OkHttpClient(), base), saved)
                model.also { owner.put("review", it) }
            }
            check(server.takeRequest(5, TimeUnit.SECONDS) != null)
            withContext(main) {
                when (vm) {
                    is RadioViewModel -> vm.select(vm.categories.last())
                    is AppleMusicViewModel -> {
                        vm.setQuery("second")
                        vm.search()
                    }
                }
            }
            check(server.takeRequest(5, TimeUnit.SECONDS) != null)
            delay(200)
            withContext(main) {
                when (vm) {
                    is RadioViewModel -> assertTrue("Replacement is ${vm.stations.value}", vm.stations.value is RadioViewModel.Stations.Loading)
                    is AppleMusicViewModel -> assertTrue("Replacement is ${vm.results.value}", vm.results.value is AppleMusicViewModel.Results.Searching)
                }
            }
        } finally {
            withContext(main) { owner.clear() }
            withContext(main) { }
            household.disconnect()
            scope.cancel()
            server.shutdown()
            // Other test classes keep Unconfined Main installed; restore that baseline.
            Dispatchers.setMain(Dispatchers.Unconfined)
            executor.shutdownNow()
        }
    }
}
