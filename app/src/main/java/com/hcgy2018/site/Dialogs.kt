package com.hcgy2018.site

import android.app.AlertDialog
import androidx.core.content.ContextCompat

/**
 * 弹窗统一圆角（长期规范，2026-09-11 起）。
 *
 * 平台 AlertDialog 默认画直角背景，和全站其余组件的圆角语言不一致。
 * 做法分两层：
 *  1. 调用侧用 [dialogBuilder] 建弹窗 —— 它挂上 `Theme.MatchShell.Dialog`，
 *     该主题把 `android:windowBackground` 换成了 22dp 圆角白面；
 *  2. show() 之后再调 [roundCorners] 兜底 —— 部分平台版本会在内部分区再画一层
 *     背景，把主题里的圆角盖住，这里直接把窗口背景再钉一次。
 *
 * ⚠️ 圆角是否覆盖干净（四角会不会残留平台自己的边距）必须在真机上看一眼。
 */
fun dialogBuilder(context: android.content.Context): AlertDialog.Builder =
    AlertDialog.Builder(context, R.style.Theme_MatchShell_Dialog)

/** 见 [dialogBuilder]。在 show() 之后调用。 */
fun AlertDialog.roundCorners(): AlertDialog = apply {
    window?.setBackgroundDrawable(
        ContextCompat.getDrawable(context, R.drawable.dialog_background)
    )
}
