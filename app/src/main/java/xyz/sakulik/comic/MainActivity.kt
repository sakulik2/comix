package xyz.sakulik.comic

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import xyz.sakulik.comic.navigation.HomeRoute
import xyz.sakulik.comic.navigation.ReaderRoute
import xyz.sakulik.comic.navigation.SeriesDetailRoute
import xyz.sakulik.comic.navigation.SettingsRoute
import xyz.sakulik.comic.navigation.MetadataSearchRoute
import xyz.sakulik.comic.ui.theme.ComicReaderTheme
import androidx.lifecycle.viewmodel.compose.viewModel
import xyz.sakulik.comic.viewmodel.BookshelfViewModel
import xyz.sakulik.comic.viewmodel.ReaderViewModel
import xyz.sakulik.comic.model.db.AppDatabase
import android.app.Application
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import xyz.sakulik.comic.ui.bookshelf.BookshelfScreen
import xyz.sakulik.comic.model.db.ComicEntity

/**
 * Android 原生的单 Activity 根节点
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ComicReaderTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ComicAppNavHost()
                }
            }
        }
    }
}

/**
 * 【类型安全路由大满贯】
 * 利用 Kotlin Serialization 2.8+ 强绑定参数传递
 */
@Composable
fun ComicAppNavHost() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = HomeRoute
    ) {
        // ========== [页面 1: 首页漫画库] ==========
        composable<HomeRoute> {
            val context = androidx.compose.ui.platform.LocalContext.current.applicationContext as Application
            val bookshelfViewModel = viewModel {
                BookshelfViewModel(context, AppDatabase.getDatabase(context).comicDao())
            }

            BookshelfScreen(
                viewModel = bookshelfViewModel,
                onComicClick = { comic: ComicEntity ->
                    navController.navigate(ReaderRoute(comicId = comic.id, initialPage = comic.currentPage))
                },
                onSettingsClick = { 
                    navController.navigate(SettingsRoute)
                },
                onManualScrapeClick = { comic ->
                    navController.navigate(MetadataSearchRoute(comicId = comic.id))
                }
            )
        }

        // ========== [页面 2: 系列详情页] ==========
        // 暂未用到专门的 ViewModel，直接由 Bookshelf 的分组列表流转即可，但需要设计独立的展开屏
        // 为了目前跑通，我们在此可以将 BookshelfViewModel 直接提升作用域范围（例如依附于 parent navGraph）
        // 这里只是为了证明强类型路由的威力
        composable<SeriesDetailRoute> { backStackEntry ->
            val routeParams = backStackEntry.toRoute<SeriesDetailRoute>()
            Text("正在展示系列泳道分类：${routeParams.seriesName}。需重开UI专门渲染这部分泳道。")
        }

        // ========== [页面 3: 极致化底层沉浸阅读器] ==========
        composable<ReaderRoute> { backStackEntry ->
            val routeParams = backStackEntry.toRoute<ReaderRoute>()
            
            // 使用原生方式获取包含 SavedStateHandle 和 Dao 的独立 ViewModel
            val context = androidx.compose.ui.platform.LocalContext.current.applicationContext as Application
            val readerViewModel = viewModel(key = "reader_${routeParams.comicId}") {
                xyz.sakulik.comic.viewmodel.ReaderViewModel(
                    context, 
                    AppDatabase.getDatabase(context).comicDao(), 
                    androidx.lifecycle.SavedStateHandle(mapOf("comicId" to routeParams.comicId, "initialPage" to routeParams.initialPage))
                )
            }
            
            val state by readerViewModel.state.collectAsState()
            val isRtl by readerViewModel.isRtl.collectAsState()
            val currentComic = readerViewModel.currentEntity

            if (currentComic != null) {
                when (val s = state) {
                    is xyz.sakulik.comic.viewmodel.ComicState.Idle, is xyz.sakulik.comic.viewmodel.ComicState.Loading -> {
                        androidx.compose.foundation.layout.Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(androidx.compose.ui.graphics.Color(0xFF0D0D0F))
                        ) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.align(Alignment.Center),
                                color = androidx.compose.ui.graphics.Color(0xFFE53935)
                            )
                        }
                    }
                    is xyz.sakulik.comic.viewmodel.ComicState.Ready -> {
                        val readerMode by readerViewModel.readerMode.collectAsState()
                        val isImmersive by readerViewModel.isImmersive.collectAsState()
                        val isSharpenEnabled by readerViewModel.isSharpenEnabled.collectAsState()

                        xyz.sakulik.comic.ui.ReaderScreen(
                            loader = s.loader,
                            readerMode = readerMode,
                            pageCount = s.pageCount,
                            comicTitle = s.fileName,
                            initialPage = readerViewModel.matchedInitialPage,
                            isRtl = isRtl,
                            isImmersive = isImmersive,
                            isSharpenEnabled = isSharpenEnabled,
                            onPageChanged = { page ->
                                readerViewModel.updateProgress(page, s.pageCount)
                            },
                            onBack = { navController.popBackStack() },
                            onScrapeClick = { /* 开启刮削面板 */ },
                            onToggleRtl = { readerViewModel.toggleRtl() },
                            onToggleSharpen = { readerViewModel.toggleSharpen() },
                            onToggleReaderMode = { readerViewModel.toggleReaderMode() },
                            onToggleImmersive = { readerViewModel.setImmersive(it) }
                        )
                    }
                    is xyz.sakulik.comic.viewmodel.ComicState.Error -> {
                        xyz.sakulik.comic.ui.components.ErrorScreen(
                            message = s.message,
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
        
        // ========== [页面 5: 手动元数据搜索] ==========
        composable<MetadataSearchRoute> { backStackEntry ->
            val routeParams = backStackEntry.toRoute<MetadataSearchRoute>()
            val context = androidx.compose.ui.platform.LocalContext.current.applicationContext as Application
            val scrapeViewModel = viewModel {
                xyz.sakulik.comic.viewmodel.ScrapeViewModel(context, AppDatabase.getDatabase(context).comicDao())
            }
            
            xyz.sakulik.comic.ui.scrape.ScrapeSearchScreen(
                comicId = routeParams.comicId,
                viewModel = scrapeViewModel,
                onBack = { navController.popBackStack() }
            )
        }

        // ========== [页面 6: 全局设置中心] ==========
        composable<SettingsRoute> {
            val context = androidx.compose.ui.platform.LocalContext.current.applicationContext as Application
            val bookshelfViewModel = viewModel {
                BookshelfViewModel(context, AppDatabase.getDatabase(context).comicDao())
            }
            
            xyz.sakulik.comic.ui.settings.SettingsScreen(
                onBack = { navController.popBackStack() },
                onClearRemoteLibrary = { 
                    bookshelfViewModel.clearRemoteLibrary()
                }
            )
        }
    }
}
