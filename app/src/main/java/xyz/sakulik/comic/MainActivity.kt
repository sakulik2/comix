package xyz.sakulik.comic

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AbstractSavedStateViewModelFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import xyz.sakulik.comic.model.db.AppDatabase
import xyz.sakulik.comic.model.db.ComicEntity
import xyz.sakulik.comic.navigation.*
import xyz.sakulik.comic.ui.ReaderScreen
import xyz.sakulik.comic.ui.bookshelf.BookshelfScreen
import xyz.sakulik.comic.ui.bookshelf.SeriesDetailContent
import xyz.sakulik.comic.ui.components.ErrorScreen
import xyz.sakulik.comic.ui.scrape.ScrapeSearchScreen
import xyz.sakulik.comic.ui.settings.SettingsScreen
import xyz.sakulik.comic.ui.theme.ComicReaderTheme
import xyz.sakulik.comic.viewmodel.BookshelfViewModel
import xyz.sakulik.comic.viewmodel.ComicState
import xyz.sakulik.comic.viewmodel.ReaderViewModel
import xyz.sakulik.comic.viewmodel.ScrapeViewModel
import xyz.sakulik.comic.viewmodel.SeriesGroupData

/**
 * Android 原生的单 Activity 根节点
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
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
 * Compose 导航宿主，定义应用所有页面的路由结构
 * 使用 Navigation Compose 2.8+ 的强类型路由（@Serializable 数据类）
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ComicAppNavHost() {
    // 将 BookshelfViewModel 提升至 Activity 作用域，使其在所有路由间共享同一实例
    // 使用 AbstractSavedStateViewModelFactory 确保 SavedStateHandle 被正确注入，
    // 避免通过 getBackStackEntry() 取 ViewModel 在进程恢复时可能触发的 IllegalArgumentException
    val activity = androidx.compose.ui.platform.LocalContext.current as ComponentActivity
    val application = activity.application
    val bookshelfViewModel: BookshelfViewModel = viewModel(
        viewModelStoreOwner = activity,
        factory = object : AbstractSavedStateViewModelFactory(activity, activity.intent?.extras) {
            override fun <T : ViewModel> create(key: String, modelClass: Class<T>, handle: SavedStateHandle): T {
                val db = AppDatabase.getDatabase(application)
                @Suppress("UNCHECKED_CAST")
                return BookshelfViewModel(
                    application,
                    db.comicDao(),
                    db.collectionDao(),
                    handle
                ) as T
            }
        }
    )

    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = HomeRoute,
        modifier = Modifier.fillMaxSize()
    ) {
        // ========== [页面 1: 首页漫画库] ==========
        composable<HomeRoute> {
            BookshelfScreen(
                viewModel = bookshelfViewModel,
                onComicClick = { comic: ComicEntity ->
                    navController.navigate(ReaderRoute(comicId = comic.id, initialPage = comic.currentPage))
                },
                onSeriesClick = { group ->
                    navController.navigate(SeriesDetailRoute(seriesName = group.seriesName))
                },
                onSettingsClick = {
                    navController.navigate(SettingsRoute)
                },
                onManualScrapeClick = { comic ->
                    navController.navigate(MetadataSearchRoute(comicId = comic.id))
                },
                onCollectionClick = { col ->
                    navController.navigate(CollectionDetailRoute(collectionId = col.id, collectionName = col.name))
                }
            )
        }

        // ========== [页面 2: 合集详情页] ==========
        composable<CollectionDetailRoute> { backStackEntry ->
            val routeParams = backStackEntry.toRoute<CollectionDetailRoute>()
            val collections by bookshelfViewModel.collections.collectAsState()
            val collectionData = collections.find { it.collection.id == routeParams.collectionId }
            
            val configuration = LocalConfiguration.current
            val adaptiveColumns = when {
                configuration.screenWidthDp < 600 -> 3
                configuration.screenWidthDp < 840 -> 5
                else -> 7
            }

            Scaffold(
                topBar = {
                    TopAppBar(
                        navigationIcon = {
                            IconButton(onClick = { navController.popBackStack() }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack, 
                                    contentDescription = "后退"
                                )
                            }
                        },
                        title = { Text(routeParams.collectionName) }
                    )
                }
            ) { padding ->
                if (collectionData != null) {
                    SeriesDetailContent(
                        series = SeriesGroupData(
                            seriesName = collectionData.collection.name,
                            coverComic = collectionData.comics.firstOrNull() ?: ComicEntity(id = -1L, title="", uri="", extension=""),
                            bookCount = collectionData.comics.size,
                            books = collectionData.comics
                        ),
                        adaptiveColumns = adaptiveColumns,
                        onComicClick = { comic ->
                            navController.navigate(ReaderRoute(comicId = comic.id, initialPage = comic.currentPage))
                        },
                        onLongClick = { comic ->
                            navController.navigate(MetadataSearchRoute(comicId = comic.id))
                        },
                        onRemoveFromCollection = { comic ->
                            bookshelfViewModel.removeComicFromCollection(routeParams.collectionId, comic.id)
                        },
                        onSetAsCover = { comic ->
                            bookshelfViewModel.updateCollectionCover(routeParams.collectionId, comic.id)
                        },
                        modifier = Modifier.padding(padding)
                    )
                }
            }
        }

        // ========== [页面 2: 系列详情页] ==========
        composable<SeriesDetailRoute> { backStackEntry ->
            val routeParams = backStackEntry.toRoute<SeriesDetailRoute>()
            // 直接使用 Activity 级共享的 bookshelfViewModel
            val seriesData by bookshelfViewModel.getSeriesByName(routeParams.seriesName).collectAsState(initial = null)
            val configuration = androidx.compose.ui.platform.LocalConfiguration.current
            val adaptiveColumns = when {
                configuration.screenWidthDp < 600 -> 3
                configuration.screenWidthDp < 840 -> 5
                else -> 7
            }

            Scaffold(
                topBar = {
                    TopAppBar(
                        navigationIcon = {
                            IconButton(onClick = { navController.popBackStack() }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack, 
                                    contentDescription = "后退"
                                )
                            }
                        },
                        title = { 
                            Text(
                                routeParams.seriesName, 
                                maxLines = 1, 
                                overflow = TextOverflow.Ellipsis
                            ) 
                        }
                    )
                }
            ) { padding ->
                if (seriesData != null) {
                    SeriesDetailContent(
                        series = seriesData!!,
                        adaptiveColumns = adaptiveColumns,
                        onComicClick = { comic ->
                            navController.navigate(ReaderRoute(comicId = comic.id, initialPage = comic.currentPage))
                        },
                        onLongClick = { comic ->
                            // 跳转至元数据搜索页进行手动更正/刮削
                            navController.navigate(MetadataSearchRoute(comicId = comic.id))
                        },
                        modifier = Modifier.padding(padding)

                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(), 
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }

        }


        // 阅读器页面
        composable<ReaderRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<ReaderRoute>()
            // 使用 NavBackStackEntry 自身的 SavedStateHandle 构造 ViewModel
            val readerViewModel = viewModel {
                val savedStateHandle = backStackEntry.savedStateHandle
                // 显式同步强类型参数到 SavedStateHandle，确保 ViewModel 内部读取不为空
                if (!savedStateHandle.contains("comicId")) {
                    savedStateHandle["comicId"] = route.comicId
                    savedStateHandle["initialPage"] = route.initialPage
                }
                ReaderViewModel(
                    application, 
                    AppDatabase.getDatabase(application).comicDao(), 
                    savedStateHandle
                )
            }
            
            val state by readerViewModel.state.collectAsState()
            val isRtl by readerViewModel.isRtl.collectAsState()
            readerViewModel.currentEntity

            // 修正：即使 currentComic 为 null，只要状态是 Loading，也要展示进度条
            when (val s = state) {
                is ComicState.Idle, is ComicState.Loading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF0D0D0F))
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center),
                            color = Color(0xFFE53935)
                        )
                    }
                }
                is ComicState.Ready -> {
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
                        onScrapeClick = { readerViewModel.currentEntity?.id?.let { navController.navigate(MetadataSearchRoute(it)) } },
                        onToggleRtl = { readerViewModel.toggleRtl() },
                        onToggleSharpen = { readerViewModel.toggleSharpen() },
                        onToggleReaderMode = { readerViewModel.toggleReaderMode() },
                        onToggleImmersive = { readerViewModel.setImmersive(it) }
                    )
                }
                is ComicState.Error -> {
                    xyz.sakulik.comic.ui.components.ErrorScreen(
                        message = s.message,
                        onBack = { navController.popBackStack() }
                    )
                }
            }
        }
        
        // ========== [页面 5: 手动元数据搜索] ==========
        composable<MetadataSearchRoute> { backStackEntry ->
            val routeParams = backStackEntry.toRoute<MetadataSearchRoute>()
            val context = androidx.compose.ui.platform.LocalContext.current.applicationContext as Application
            val scrapeViewModel = viewModel {
                ScrapeViewModel(context, AppDatabase.getDatabase(context).comicDao())
            }
            
            xyz.sakulik.comic.ui.scrape.ScrapeSearchScreen(
                comicId = routeParams.comicId,
                viewModel = scrapeViewModel,
                onBack = { navController.popBackStack() }
            )
        }

        // ========== [页面 6: 全局设置中心] ==========
        composable<SettingsRoute> {
            // 直接使用 Activity 级共享的 bookshelfViewModel
            xyz.sakulik.comic.ui.settings.SettingsScreen(
                onBack = { navController.popBackStack() },
                onClearRemoteLibrary = { 
                    bookshelfViewModel.clearRemoteLibrary()
                }
            )
        }
    }
}
