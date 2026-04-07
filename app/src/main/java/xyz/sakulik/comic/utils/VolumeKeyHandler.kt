package xyz.sakulik.comic.utils

import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * 跨层按键处理器
 * 用于将 MainActivity 拦截到的物理按键事件分发给 ReaderScreen
 */
object VolumeKeyHandler {
    // 全局开关，控制 MainActivity 是否拦截音量键
    var isEnabled: Boolean = false
    
    // 动作类型
    enum class PageAction { PREVIOUS, NEXT }
    
    // 动作分发管道
    val actions = MutableSharedFlow<PageAction>(extraBufferCapacity = 1)
}
