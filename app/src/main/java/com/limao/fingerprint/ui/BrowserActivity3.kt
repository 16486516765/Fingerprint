package com.limao.fingerprint.ui

/**
 * slot 3 专用的浏览器 Activity，跑在 :wv3 进程。
 * 本体逻辑全在父类 BrowserActivity，这里只是换个组件名，
 * 让 Manifest 能给每个 slot 配独立的 android:process。
 */
class BrowserActivity3 : BrowserActivity()
