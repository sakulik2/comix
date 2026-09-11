package xyz.sakulik.comic.utils

import android.content.Context
import androidx.annotation.StringRes
import xyz.sakulik.comic.R
import java.io.FileNotFoundException

/**
 * 尚未解析成字符串的文案。
 *
 * model 层（loader、scanner、纯 object 工具类）拿不到 Context，无法调用 getString()。
 * 让它们传递资源 id + 参数，由 UI 层在拥有 Context 时再解析，异常消息因此也能跟随系统语言。
 */
sealed interface UiText {
    /** 可本地化的文案 */
    data class Res(@param:StringRes val id: Int, val args: List<Any> = emptyList()) : UiText

    /** 已经是成品的文案：服务端返回的错误、系统异常的 message 等，本地无法翻译 */
    data class Raw(val text: String) : UiText
}

fun UiText.resolve(context: Context): String = when (this) {
    is UiText.Res -> context.getString(id, *args.toTypedArray())
    is UiText.Raw -> text
}

/** 异常携带可本地化文案。ViewModel 的 catch 只需判断这个接口，不必逐个匹配异常类型。 */
interface LocalizedThrowable {
    val uiText: UiText
}

/** message 只给 logcat 看，用户看到的是 uiText。 */
class LocalizedIllegalStateException(
    override val uiText: UiText,
    technicalMessage: String
) : IllegalStateException(technicalMessage), LocalizedThrowable

/** 同上，给参数校验失败用。 */
class LocalizedIllegalArgumentException(
    override val uiText: UiText,
    technicalMessage: String
) : IllegalArgumentException(technicalMessage), LocalizedThrowable

/**
 * ViewModel 的 catch 统一入口：能本地化的用自带文案，
 * 其余按已知系统异常归类，兜底一句通用的解析失败。
 */
fun Throwable.toUiText(): UiText = when {
    this is LocalizedThrowable -> uiText
    message?.contains("ENOSPC") == true -> UiText.Res(R.string.error_no_space)
    this is FileNotFoundException -> UiText.Res(R.string.error_file_missing)
    else -> UiText.Res(R.string.error_parse_failed)
}
