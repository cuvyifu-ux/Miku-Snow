package com.miku.snow

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import androidx.compose.ui.input.nestedscroll.nestedScroll
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        CrashCatcher.install(this)

        // 上次崩溃过：先用纯原生 View 把堆栈显示出来（不碰 Compose，绝不会再崩）
        if (!intent.getBooleanExtra("skip_crash_screen", false)) {
            try {
                val f = CrashCatcher.file(this)
                if (f.exists() && f.length() > 0) {
                    showCrashScreen(CrashCatcher.last(this))
                    return
                }
            } catch (t: Throwable) {
            }
        }
        // Shizuku 的接口权限 API_V23 是 dangerous，必须运行时申请，
        // 否则管理器拒绝把 binder 发给本应用，永远显示「未连接」。
        try {
            if (!ShizukuHelper.apiPermissionGranted(this)) {
                requestPermissions(arrayOf("moe.shizuku.manager.permission.API_V23"), 2001)
            }
        } catch (t: Throwable) {
        }

        try {
            ShizukuHelper.init(this)
        } catch (t: Throwable) {
            // Shizuku 没装 / binder 不通时不要连累主界面
        }
        try {
            // provider 的 onCreate 不一定被拉起，这里主动要一次 binder，
            // 否则已经授权了也 pingBinder=false，界面一直灰着
            ShizukuHelper.requestBinder(this)
        } catch (t: Throwable) {
        }
        try {
            Prefs.ensureAdbScript(this)
        } catch (t: Throwable) {
        }

        setContent {
            val saved = Prefs.themeMode(this)
            val safe = if (saved == 3 && Build.VERSION.SDK_INT < 31) 0 else saved
            var mode by remember { mutableStateOf(modeOf(safe)) }
            var scale by remember { mutableStateOf(Prefs.uiScale(this)) }
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = baseDensity.density,
                    fontScale = baseDensity.fontScale * scale / 100f
                )
            ) {
                MiuixTheme(controller = remember(mode) { ThemeController(mode) }) {
                    Screen(onModeChanged = {
                        Prefs.setThemeMode(this, it)
                        mode = modeOf(it)
                    }, onScaleChanged = {
                        Prefs.setUiScale(this, it)
                        scale = it
                    })
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (Prefs.running(this)) {
            try {
                startService(
                    Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_START)
                )
            } catch (t: Throwable) {
            }
        }
        try {
            if (Prefs.running(this)) {
                val i = Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_START)
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
            }
        } catch (t: Throwable) {
        }
        try {
            // 从后台回来时 overlay 可能已经停画（SurfaceView 的 Surface 被系统回收），
            // 这里重发一次 START，服务里没层会重建，有层会重新套配置
            if (Prefs.running(this)) {
                val i = Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_START)
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
            }
        } catch (t: Throwable) {
        }
        try {
            ShizukuHelper.init(this)
            ShizukuHelper.requestBinder(this)
        } catch (t: Throwable) {
        }
        Thread {
            try {
                RishHelper.prepare(this)
                Priv.refreshAsync(this)
            } catch (t: Throwable) {
            }
        }.start()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2001) {
            ShizukuHelper.init(this)
            try {
                ShizukuHelper.requestBinder(this)
            } catch (t: Throwable) {
            }
        }
    }
}

private fun MainActivity.showCrashScreen(trace: String) {
    val root = android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        setPadding(32, 64, 32, 32)
    }
    val tip = android.widget.TextView(this).apply {
        text = "上次启动崩溃了。把这一屏截图发给我，就能定位。\n\n" + trace
        textSize = 12f
        setTextIsSelectable(true)
    }
    val scroll = android.widget.ScrollView(this).apply { addView(tip) }
    val btn = android.widget.Button(this).apply {
        text = "清除记录并进入"
        setOnClickListener {
            CrashCatcher.clear(this@showCrashScreen)
            startActivity(
                Intent(this@showCrashScreen, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    .putExtra("skip_crash_screen", true)
            )
            finish()
        }
    }
    root.addView(btn)
    root.addView(
        scroll,
        android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT
        )
    )
    setContentView(root)
}


// Miuix 的 OverlayDialog 内部用了 NavigationBackHandler，需要宿主提供
// LocalNavigationEventDispatcherOwner（activity-compose 1.11 没提供），一显示就崩。
// 这里用 Miuix 的 Card / TextButton 自绘一个同款底部/居中弹窗，外观一致且不挑宿主。
private const val GitHubUrl = "https://github.com/cuvyifu-ux/Miku-Snow"

private val COLOR_NAMES = listOf("雪白", "冰蓝", "樱花粉", "薄荷绿", "暖金", "紫")
private val COLOR_VALUES = listOf(
    0xFFFFFFFF, 0xFF7FE7FF, 0xFFFFB3CE, 0xFF6FF7C8, 0xFFFFD166, 0xFFC9A7FF
)

@Composable
private fun MikuDialog(
    onDismiss: () -> Unit,
    title: String,
    summary: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xA6000000))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                ),
            contentAlignment = Alignment.Center
        ) {
            Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            insideMargin = PaddingValues(18.dp)
        ) {
            Column {
                Text(text = title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                if (summary != null) {
                    Spacer(Modifier.height(12.dp))
                    Column(
                        modifier = Modifier
                            .heightIn(max = 260.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(text = summary, fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    content()
                }
            }
        }
        }
    }
}

@Composable
private fun DlgButton(
    text: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(44.dp),
        colors = if (primary) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors()
    ) {
        Text(text = text, fontSize = 13.sp)
    }
}

private fun modeOf(v: Int): ColorSchemeMode = when (v) {
    1 -> ColorSchemeMode.Light
    2 -> ColorSchemeMode.Dark
    3 -> ColorSchemeMode.MonetSystem
    else -> ColorSchemeMode.System
}

private fun modeName(v: Int): String = when (v) {
    1 -> "始终浅色"
    2 -> "始终深色（黑）"
    3 -> "莫奈取色（跟随壁纸）"
    else -> "跟随系统"
}

private fun toast(ctx: Context, msg: String) {
    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
}

// 这张卡照的是 KernelSU manager 的 HomeMiuix.kt StatusCard：
// 浅色 #DFFAE4 / 深色 #1A3825 底，右下 110dp 绿色 CheckCircleOutline，左上 22sp「工作中」。
@Composable
private fun WorkingCard(running: Boolean, dark: Boolean, onClick: () -> Unit) {
    val bg = when {
        running && dark -> Color(0xFF1A3825)
        running -> Color(0xFFDFFAE4)
        dark -> Color(0xFF262628)
        else -> Color(0xFFF1F2F4)
    }
    val accent = if (running) Color(0xFF36D167) else Color(0xFF9AA0A6)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(158.dp),
        colors = CardDefaults.defaultColors(color = bg),
        onClick = onClick,
        showIndication = true
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .offset(27.dp, 22.dp),
                contentAlignment = Alignment.BottomEnd
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_check_circle_outline),
                    contentDescription = null,
                    modifier = Modifier.size(110.dp),
                    colorFilter = ColorFilter.tint(accent.copy(alpha = 0.85f))
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 20.dp, top = 20.dp, end = 122.dp),
                contentAlignment = Alignment.TopStart
            ) {
                Column {
                    Text(
                        text = if (running) "工作中" else "已停止",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = if (running) "点一下停止 · 正在覆盖全屏" else "点一下开始飘雪",
                        fontSize = 15.sp
                    )
                }
            }
        }
    }
}


@Composable
private fun FoldingTitle(
    scroll: androidx.compose.foundation.ScrollState,
    title: String,
    dark: Boolean,
    onBack: (() -> Unit)? = null,
    blurEnabled: Boolean = true
) {
    val frac = (scroll.value / 220f).coerceIn(0f, 1f)
    val bg = if (dark) Color(0xFF000000) else Color(0xFFFFFFFF)
    val fg = if (dark) Color(0xFFFFFFFF) else Color(0xFF000000)

    // 大标题：越往下滚越模糊、越淡
    val bigAlpha = (1f - frac * 1.8f).coerceIn(0f, 1f)
    val bigBlur = (frac * 9f)
    // 小标题：从模糊到清晰
    val smallAlpha = ((frac - 0.45f) / 0.4f).coerceIn(0f, 1f)
    val smallBlur = ((1f - smallAlpha) * 7f)

    val height = (96f - frac * 44f).dp

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .background(bg)
    ) {
        if (onBack != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 16.dp)
                    .clickable { onBack() }
            ) {
                Text(text = "←", fontSize = 22.sp, color = fg)
            }
        }
        // 大标题，左上角
        Text(
            text = title,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            color = fg,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = if (onBack != null) 20.dp else 20.dp, bottom = 8.dp)
                .alpha(bigAlpha)
                .then(if (blurEnabled) Modifier.blur(bigBlur.dp) else Modifier)
        )
        // 小标题，正中
        Text(
            text = title,
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            color = fg,
            modifier = Modifier
                .align(Alignment.Center)
                .alpha(smallAlpha)
                .then(if (blurEnabled) Modifier.blur(smallBlur.dp) else Modifier)
        )
    }
}

@Composable
private fun Screen(onModeChanged: (Int) -> Unit, onScaleChanged: (Int) -> Unit = {}) {
    val ctx = LocalContext.current

    var text by remember { mutableStateOf(Prefs.text(ctx)) }
    var count by remember { mutableStateOf(Prefs.count(ctx)) }
    var speed by remember { mutableStateOf(Prefs.speedRaw(ctx)) }
    var size by remember { mutableStateOf(Prefs.sizeRaw(ctx)) }
    var spin by remember { mutableStateOf(Prefs.spin(ctx)) }
    var flip by remember { mutableStateOf(Prefs.flip(ctx)) }
    var tilt by remember { mutableStateOf(Prefs.tilt(ctx)) }
    var secure by remember { mutableStateOf(Prefs.secure(ctx)) }
    var useImages by remember { mutableStateOf(Prefs.useImages(ctx)) }
    var onlyImages by remember { mutableStateOf(Prefs.onlyImages(ctx)) }
    var noText by remember { mutableStateOf(Prefs.noText(ctx)) }
    var singleChar by remember { mutableStateOf(Prefs.singleChar(ctx)) }
    var textColor by remember { mutableStateOf(Prefs.textColor(ctx)) }
    var showColor by remember { mutableStateOf(false) }
    var showPalette by remember { mutableStateOf(false) }
    var blurTitle by remember { mutableStateOf(Prefs.blurTitle(ctx)) }
    var predictBack by remember { mutableStateOf(Prefs.predictBack(ctx)) }
    var uiScale by remember { mutableStateOf(Prefs.uiScale(ctx)) }
    var editKind by remember { mutableStateOf(0) }   // 1 数量 2 速度 3 大小 4 缩放
    var running by remember { mutableStateOf(Prefs.running(ctx)) }
    var canOverlay by remember { mutableStateOf(Settings.canDrawOverlays(ctx)) }
    var themeMode by remember { mutableStateOf(Prefs.themeMode(ctx)) }

    var showPerm by remember { mutableStateOf(false) }
    var showDiag by remember { mutableStateOf(false) }
    var showTheme by remember { mutableStateOf(false) }
    var showAdb by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf(0) }          // 0 = 主页，1 = 关于
    var latestUrl by remember { mutableStateOf("") }
    // 关于页里做返回手势 → 回主页，别直接退出应用
    // 预测性返回：手势过程中页面跟手位移，松手完成才回主页
    var backProgress by remember { mutableStateOf(0f) }
    val backScope = rememberCoroutineScope()
    val screenW = with(LocalDensity.current) {
        LocalConfiguration.current.screenWidthDp.dp.toPx()
    }
    if (predictBack) {
        androidx.activity.compose.PredictiveBackHandler(enabled = page != 0) { progress ->
            try {
                progress.collect { ev -> backProgress = ev.progress }
            } catch (c: kotlinx.coroutines.CancellationException) {
                backProgress = 0f
                throw c
            }
            backScope.launch {
                // 快速一甩时事件推进很少，这里补一段滑动把页面送出画面
                var p = backProgress
                while (p < 1f) {
                    p = (p + 0.10f).coerceAtMost(1f)
                    backProgress = p
                    kotlinx.coroutines.delay(16)
                }
                kotlinx.coroutines.delay(30)
                // 切页时不要立刻把位移清零，否则退场动画会把页面拉回原位闪一下
                page = 0
                kotlinx.coroutines.delay(220)
                backProgress = 0f
            }
        }
    } else {
        // 关掉预测性返回：退回普通返回，页面不跟手
        androidx.activity.compose.BackHandler(enabled = page != 0) {
            backProgress = 0f
            page = 0
        }
    }

    val currentVersion = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"
    } catch (t: Throwable) { "0" }
    var showNoPriv by remember { mutableStateOf(false) }
    var hasShellPriv by remember { mutableStateOf(Priv.cachedOk() || Priv.rememberedOk(ctx)) }
    var retryTick by remember { mutableStateOf(0) }
    var autoNew by remember { mutableStateOf("") }     // 启动时自动查到的更新提示
    var pendingCheck by remember { mutableStateOf(false) }

    var privDetail by remember { mutableStateOf("") }

    var showFirstRun by remember { mutableStateOf(false) }
    var showImages by remember { mutableStateOf(false) }
    var imgCount by remember { mutableStateOf(ImageStore.count(ctx)) }
    var imgs by remember { mutableStateOf(ImageStore.files(ctx)) }
    var imgBusy by remember { mutableStateOf(false) }
    var imgTick by remember { mutableStateOf(0) }
    var diag by remember { mutableStateOf("") }

    val dark = when (themeMode) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }

    // 状态栏 / 导航栏跟着主题走：原来固定用 Light 主题，深色界面顶上就留一条白条
    androidx.compose.runtime.SideEffect {
        val act = ctx as? android.app.Activity
        val w = act?.window
        if (w != null) {
            val bar = if (dark) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            w.statusBarColor = bar
            w.navigationBarColor = bar
            if (Build.VERSION.SDK_INT >= 30) {
                val c = w.insetsController
                if (c != null) {
                    val light = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    val lightNav = android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                    c.setSystemBarsAppearance(
                        if (dark) 0 else (light or lightNav),
                        light or lightNav
                    )
                }
            } else {
                @Suppress("DEPRECATION")
                w.decorView.systemUiVisibility =
                    if (dark) 0 else android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            }
        }
    }

    fun push() {
        if (!running) return
        try {
            ctx.startService(Intent(ctx, OverlayService::class.java).setAction(OverlayService.ACTION_REFRESH))
        } catch (t: Throwable) {
        }
    }

    fun startOverlay() {
        val i = Intent(ctx, OverlayService::class.java).setAction(OverlayService.ACTION_START)
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        Prefs.setRunning(ctx, true)
        running = true
    }

    fun stopOverlay() {
        ctx.startService(Intent(ctx, OverlayService::class.java).setAction(OverlayService.ACTION_STOP))
        Prefs.setRunning(ctx, false)
        running = false
    }

    LaunchedEffect(text) {
        delay(300)
        Prefs.setText(ctx, text)
        push()
    }

    LaunchedEffect(Unit) {
        // 等 Scaffold 把弹窗宿主建立好再显示首启提醒，否则第一帧挂 Dialog 会崩
        delay(800)
        if (Prefs.firstRun(ctx)) showFirstRun = true
        while (true) {
            canOverlay = Settings.canDrawOverlays(ctx)
            delay(1200)
        }
    }

    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(ImageStore.MAX_IMAGES)
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        imgBusy = true
        Thread {
            val n = try { ImageStore.importUris(ctx, uris) } catch (t: Throwable) { 0 }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                imgBusy = false
                imgCount = ImageStore.count(ctx)
                imgs = ImageStore.files(ctx)
                toast(ctx, "已导入 $n 张图片（最多 ${ImageStore.MAX_IMAGES} 张）")
                push()
            }
        }.start()
    }

    // 常驻探测：每 2 秒看一次。Shizuku 授权后 authorized() 立刻变 true，
    // 不必等 rish 那条慢通道，界面能马上从灰变亮。
    LaunchedEffect(Unit) {
        while (true) {
            if (!ShizukuHelper.available()) {
                try { ShizukuHelper.requestBinder(ctx) } catch (t: Throwable) { }
            }
            val authorized = try { ShizukuHelper.authorized() } catch (t: Throwable) { false }
            val rootFast = hasShellPriv
            hasShellPriv = authorized || rootFast || Priv.cachedOk()
            if (!hasShellPriv) {
                try { Priv.refreshAsync(ctx) } catch (t: Throwable) { }
            }
            privDetail = Priv.detail()
            // 从后台回来 / binder 掉过，skip 层可能已经失效：开着但没生效就重建一次
            retryTick++
            if (retryTick % 3 == 0) {
                val applied = OverlayService.layerState.contains("生效")
                if (Prefs.running(ctx) && Prefs.secure(ctx) && hasShellPriv && !applied) {
                    try {
                        ctx.startService(
                            Intent(ctx, OverlayService::class.java)
                                .setAction(OverlayService.ACTION_REFRESH)
                        )
                    } catch (t: Throwable) {
                    }
                }
            }
            delay(2000)
        }
    }

    // 打开就自动查一次，有新版本就在底部浮一条
    LaunchedEffect(Unit) {
        delay(1500)
        Thread {
            try {
                val r = UpdateChecker.check(GitHubUrl, currentVersion)
                if (r != null && r.hasUpdate) {
                    val msg = r.message
                    android.os.Handler(android.os.Looper.getMainLooper()).post { autoNew = msg }
                }
            } catch (t: Throwable) {
            }
        }.start()
    }

    val homeScroll = rememberScrollState()
    Scaffold(
        topBar = { FoldingTitle(homeScroll, "Miku", dark, blurEnabled = blurTitle) }
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(pad)
                .verticalScroll(homeScroll)
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp)
        ) {
            WorkingCard(running = running, dark = dark) {
                if (running) stopOverlay()
                else if (!canOverlay) showPerm = true
                else startOverlay()
            }

            Spacer(Modifier.height(12.dp))

            Card(insideMargin = PaddingValues(0.dp)) {
                BasicComponent(
                    title = "悬浮窗权限",
                    summary = if (canOverlay) "已获得悬浮窗权限" else "未获得，点这里去授权",
                    onClick = { showPerm = true },
                    endActions = { Text(if (canOverlay) "已开启" else "去授权") }
                )
                BasicComponent(
                    title = "主题",
                    summary = "颜色、标题模糊、返回手势、调整大小",
                    onClick = { page = 2 },
                    endActions = { Text("›") }
                )
                BasicComponent(
                    title = "关于",
                    summary = "版本、作者与开源信息",
                    onClick = { page = 1 },
                    endActions = { Text("›") }
                )
            }

            Spacer(Modifier.height(12.dp))

            // 一个普通开关：有 root / ADB 时正常可用，否则灰掉、点了弹「当前权限无法调用」
            val skipUsable = hasShellPriv
            // 分组标题放在卡片外面、左上角，跟系统设置页那种写法一致
            Text(
                text = "实验性选项",
                fontSize = 13.sp,
                modifier = Modifier.padding(start = 16.dp, bottom = 6.dp)
            )
            Card(insideMargin = PaddingValues(0.dp)) {
                Box {
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (skipUsable) Modifier
                                else Modifier.alpha(0.45f)
                            )
                    ) {
                        SwitchPreference(
                            title = "无法截屏、录屏获取",
                            summary = if (!skipUsable) "当前：未生效（需要 root 或 Shizuku 授权）"
                            else "当前：" + OverlayService.layerState,
                            checked = skipUsable && secure,
                            enabled = skipUsable,
                            onCheckedChange = { v ->
                                if (!skipUsable) {
                                    showNoPriv = true
                                } else {
                                    secure = v
                                    Prefs.setSecure(ctx, v)
                                    push()
                                }
                            }
                        )
                    }
                    if (!skipUsable) {
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .clickable { showNoPriv = true }
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Card(insideMargin = PaddingValues(0.dp)) {
                TextField(
                    value = text,
                    onValueChange = { v -> if (v.length <= Prefs.MAX_TEXT) text = v },
                    label = "要飘的文字"
                )
                BasicComponent(
                    title = "字数",
                    summary = "${text.length} / ${Prefs.MAX_TEXT}　停顿 0.3 秒自动保存"
                )
            }

            Spacer(Modifier.height(12.dp))

            Card(insideMargin = PaddingValues(0.dp)) {
                BasicComponent(
                    title = "飘字颜色",
                    summary = if (textColor == 6) "自定义颜色"
                    else COLOR_NAMES.getOrElse(textColor) { "雪白" },
                    onClick = { showColor = true },
                    endActions = { Text(if (textColor == 6) "自定义" else COLOR_NAMES.getOrElse(textColor) { "雪白" }) }
                )
            }

            Spacer(Modifier.height(12.dp))

            Box {
            SliderPreference(
                value = count.toFloat(),
                onValueChange = { count = it.roundToInt() },
                onValueChangeFinished = {
                    Prefs.setCount(ctx, count)
                    push()
                },
                title = "数量",
                valueText = "$count 个",
                valueRange = 20f..110f,
                steps = 89
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 24.dp, top = 6.dp)
                    .width(120.dp)
                    .height(34.dp)
                    .clickable { editKind = 1 }
            )
        }
            Spacer(Modifier.height(12.dp))
            Box {
            SliderPreference(
                value = speed.toFloat(),
                onValueChange = { speed = it.roundToInt() },
                onValueChangeFinished = {
                    Prefs.setSpeedRaw(ctx, speed)
                    push()
                },
                title = "速度",
                valueText = "$speed%",
                valueRange = 60f..220f,
                steps = 159
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 24.dp, top = 6.dp)
                    .width(120.dp)
                    .height(34.dp)
                    .clickable { editKind = 2 }
            )
        }
            Spacer(Modifier.height(12.dp))
            Box {
            SliderPreference(
                value = size.toFloat(),
                onValueChange = { size = it.roundToInt() },
                onValueChangeFinished = {
                    Prefs.setSizeRaw(ctx, size)
                    push()
                },
                title = "大小",
                valueText = "$size%",
                valueRange = 60f..220f,
                steps = 159
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 24.dp, top = 6.dp)
                    .width(120.dp)
                    .height(34.dp)
                    .clickable { editKind = 3 }
            )
        }

            Spacer(Modifier.height(8.dp))
            Card(insideMargin = PaddingValues(0.dp)) {
                BasicComponent(
                    title = "恢复默认数值",
                    summary = "数量 44 个 · 速度 100% · 大小 100%",
                    onClick = {
                        count = 44
                        speed = 100
                        size = 100
                        Prefs.setCount(ctx, 44)
                        Prefs.setSpeedRaw(ctx, 100)
                        Prefs.setSizeRaw(ctx, 100)
                        push()
                    },
                    endActions = { Text("恢复") }
                )
            }

            Spacer(Modifier.height(12.dp))
            Card(insideMargin = PaddingValues(0.dp)) {
                SwitchPreference(
                    title = "旋转",
                    summary = "字会绕着中心转",
                    checked = spin,
                    onCheckedChange = {
                        spin = it
                        Prefs.setSpin(ctx, it)
                        push()
                    }
                )
                SwitchPreference(
                    title = "3D 翻转",
                    summary = "翻到侧面会变薄，像雪花翻面",
                    checked = flip,
                    onCheckedChange = {
                        flip = it
                        Prefs.setFlip(ctx, it)
                        push()
                    }
                )
                SwitchPreference(
                    title = "倾斜",
                    summary = "斜着落 + 字形斜切，左右各一半",
                    checked = tilt,
                    onCheckedChange = {
                        tilt = it
                        Prefs.setTilt(ctx, it)
                        push()
                    }
                )
            }

            Spacer(Modifier.height(12.dp))

            Card(insideMargin = PaddingValues(0.dp)) {
                BasicComponent(
                    title = "导入图片",
                    summary = "从相册 / 文件里选，最多 ${ImageStore.MAX_IMAGES} 张",
                    onClick = {
                        pickImages.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    endActions = { Text("选择") }
                )
                BasicComponent(
                    title = "管理图片",
                    summary = if (imgCount == 0) "还没有图片" else "已导入 $imgCount 张，可逐张删除",
                    onClick = {
                        imgs = ImageStore.files(ctx)
                        imgCount = imgs.size
                        showImages = true
                    },
                    endActions = { Text("管理") }
                )
                SwitchPreference(
                    title = "一起飘图片",
                    summary = "导入的图片混在文字里一起飘",
                    checked = useImages,
                    onCheckedChange = {
                        useImages = it
                        Prefs.setUseImages(ctx, it)
                        push()
                    }
                )
                SwitchPreference(
                    title = "只飘图片",
                    summary = "关掉文字，只让图片飘",
                    checked = onlyImages,
                    onCheckedChange = {
                        onlyImages = it
                        Prefs.setOnlyImages(ctx, it)
                        push()
                    }
                )
                SwitchPreference(
                    title = "不飘文字",
                    summary = "还没有导入图片时，文字照旧飘",
                    checked = noText,
                    onCheckedChange = {
                        noText = it
                        Prefs.setNoText(ctx, it)
                        push()
                    }
                )
                SwitchPreference(
                    title = "单字飘落",
                    summary = "勾上后每个字单独往下落，不勾就是整段文字一起飘",
                    checked = singleChar,
                    onCheckedChange = {
                        singleChar = it
                        Prefs.setSingleChar(ctx, it)
                        push()
                    }
                )
            }

            Spacer(Modifier.height(20.dp))

            // 底部三颗按钮，排版照你模块那一页
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        if (running) stopOverlay()
                        else if (!canOverlay) showPerm = true
                        else startOverlay()
                    },
                    modifier = Modifier.weight(1f),
                    colors = if (running) ButtonDefaults.buttonColors() else ButtonDefaults.buttonColorsPrimary()
                ) {
                    Text(if (running) "停止飘雪" else "开始飘雪")
                }
                Button(
                    onClick = { showAdb = true },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("ADB 激活")
                }
                Button(
                    onClick = {
                        diag = ShizukuHelper.diagnostics(ctx) + "\n\n--- Root ---\n" + RootHelper.lastLog() +
                                "\n\n--- 上次崩溃 ---\n" + CrashCatcher.last(ctx)
                        showDiag = true
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("诊断")
                }
            }

        }

        // 下面这些弹窗必须留在 Scaffold 内容域里，OverlayDialog 依赖它提供的 CompositionLocal
        // 截屏与录屏说明
    // 管理图片（之前被误删过，这里补回来）
    if (showImages) {
        val thumbs = remember { mutableStateMapOf<String, androidx.compose.ui.graphics.ImageBitmap>() }
        LaunchedEffect(imgs) {
            val list = imgs
            Thread {
                list.forEach { f ->
                    try {
                        val bmp = android.graphics.BitmapFactory.decodeFile(f.absolutePath)
                        if (bmp != null) {
                            val img = bmp.asImageBitmap()
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                thumbs[f.name] = img
                            }
                        }
                    } catch (t: Throwable) {
                    }
                }
            }.start()
        }
        MikuDialog(
        onDismiss = { showImages = false },
        title = "管理图片",
        summary = "最多 ${ImageStore.MAX_IMAGES} 张，按文件名顺序飘"
    ) {
        Column {
            if (imgBusy) {
                Text(text = "正在导入…", fontSize = 13.sp)
            }
            if (imgs.isEmpty()) {
                Text(text = "还没有图片", fontSize = 13.sp)
            } else {
                imgs.forEachIndexed { idx, f ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val thumb = thumbs[f.name]
                        if (thumb != null) {
                            Image(
                                bitmap = thumb,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(RoundedCornerShape(12.dp))
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (dark) Color(0xFF2A2A2A) else Color(0xFFEDEDED))
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = "${idx + 1}. " + f.name,
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f)
                        )
                        DlgButton("删除") {
                            ImageStore.delete(ctx, f.name)
                            imgs = ImageStore.files(ctx)
                            imgCount = imgs.size
                            imgTick++
                            push()
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DlgButton("添加图片", Modifier.weight(1f), primary = true) {
                    showImages = false
                    pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                DlgButton("关闭", Modifier.weight(1f)) { showImages = false }
            }
        }
        }
    }

    // 没有 root / ADB 时的提示
    if (showNoPriv) MikuDialog(
        onDismiss = { showNoPriv = false },
        title = "当前权限无法调用",
        summary = "「无法截屏、录屏获取」需要系统层面权限"
    ) {
        Column {
            Text(
                text = "这块功能要靠 root 或 ADB 授权的 shell 身份才能指定「这一层不进截图」。\n\n普通应用权限下按钮没有效果，所以这里先禁用。\n\n去「悬浮窗权限」里用 Shizuku 授权或 Root 授权之后再回来打开。",
                fontSize = 13.sp
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DlgButton("去授权", Modifier.weight(1f), primary = true) {
                    showNoPriv = false
                    showPerm = true
                }
                DlgButton("知道了", Modifier.weight(1f)) { showNoPriv = false }
            }
        }
    }

    // 权限
    if (showPerm) MikuDialog(
        onDismiss = { showPerm = false },
        title = "悬浮窗权限",
        summary = "SYSTEM_ALERT_WINDOW · 任选一条通道"
    ) {
        Column {
            // 原来这里在 Composable 里直接调 RishHelper.state(ctx)：那是阻塞的 rish 进程调用，
            // 每次重组都卡主线程，点「悬浮窗权限」就会僵住。改成先显示占位，再后台查。
            var permState by remember { mutableStateOf("正在检测 Shizuku…") }
            LaunchedEffect(Unit) {
                Thread {
                    val s = RishHelper.state(ctx)
                    val e = ShizukuHelper.lastErrorText()
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        permState = "Shizuku：" + s + (if (e.isEmpty()) "" else "\n" + e)
                    }
                }.start()
            }
            Text(text = permState, fontSize = 12.sp)
            Spacer(Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DlgButton("Shizuku 授权", Modifier.weight(1f), primary = true) {
                    Thread {
                        val out = RishHelper.grantOverlay(ctx)
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            canOverlay = Settings.canDrawOverlays(ctx)
                            if (out == null) {
                                diag = "Shizuku 授权失败：\n" + RishHelper.lastLog()
                                toast(ctx, "没跑通，看诊断")
                            } else {
                                diag = "Shizuku 授权输出：\n" + out + "\n\ncanDrawOverlays=" + canOverlay
                                // rish 真跑通了，说明 shell 身份拿到了。直接置为可用，
                                // 否则界面还要等后台探测，期间一直是灰的。
                                Priv.markOk(ctx, "已授权（终端方式）")
                                hasShellPriv = true
                                toast(ctx, if (canOverlay) "已授权" else "命令已执行，看诊断里的 id 输出")
                            }
                        }
                    }.start()
                }
                DlgButton("Root 授权", Modifier.weight(1f)) {
                    if (!RootHelper.available()) {
                        toast(ctx, "没有可用的 su，这台机器没 root 或者被拒绝了")
                    }
                    diag = RootHelper.grantOverlay(ctx)
                    canOverlay = Settings.canDrawOverlays(ctx)
                    if (RootHelper.available()) {
                        Priv.markOk(ctx, "root 可用")
                        hasShellPriv = true
                    }
                    toast(ctx, if (Settings.canDrawOverlays(ctx)) "已授权" else "命令已执行，见诊断")
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DlgButton("ADB 激活", Modifier.weight(1f)) { showAdb = true }
                DlgButton("系统设置", Modifier.weight(1f)) {
                    try {
                        ctx.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + ctx.packageName)
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (t: Throwable) {
                        toast(ctx, "打不开系统设置")
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            DlgButton("查看诊断信息", Modifier.fillMaxWidth()) {
                diag = ShizukuHelper.diagnostics(ctx) + "\n\n--- Root ---\n" + RootHelper.lastLog() +
                        "\n\n--- 上次崩溃 ---\n" + CrashCatcher.last(ctx)
                showDiag = true
            }
            Spacer(Modifier.height(12.dp))
            DlgButton("关闭", Modifier.fillMaxWidth()) { showPerm = false }
        }
    }

    // ADB 激活
    if (showAdb) MikuDialog(
        onDismiss = { showAdb = false },
        title = "ADB 激活",
        summary = "电脑连上手机执行下面任意一条；脚本已经写在手机的 activate.sh 里。"
    ) {
        Column {
            TextButton(
                text = "复制：adb shell appops set … allow",
                onClick = {
                    val cmd = "adb shell appops set ${ctx.packageName} SYSTEM_ALERT_WINDOW allow"
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("adb", cmd))
                    toast(ctx, "已复制，粘到电脑终端回车")
                },
                modifier = Modifier.fillMaxWidth().height(44.dp)
            )
            TextButton(
                text = "复制：adb shell sh …/activate.sh",
                onClick = {
                    val cmd = "adb shell sh " + Prefs.adbScriptPath(ctx)
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("adb", cmd))
                    toast(ctx, "已复制脚本命令")
                },
                modifier = Modifier.fillMaxWidth().height(44.dp)
            )
            TextButton(
                text = "查看脚本内容",
                onClick = {
                    diag = Prefs.readAdbScript(ctx)
                    showDiag = true
                },
                modifier = Modifier.fillMaxWidth().height(44.dp)
            )
            TextButton(
                text = "关闭",
                onClick = { showAdb = false },
                modifier = Modifier.fillMaxWidth().height(44.dp)
            )
        }
    }

    // 诊断
    if (showDiag) MikuDialog(
        onDismiss = { showDiag = false },
        title = "诊断信息",
        summary = diag
    ) {
        TextButton(
            text = "关闭",
            onClick = { showDiag = false },
            modifier = Modifier.fillMaxWidth().height(44.dp)
        )
    }

    // 底部浮条：自动检查发现新版本
    if (autoNew.isNotEmpty() && page == 0) {
        androidx.compose.ui.window.Popup(alignment = Alignment.BottomCenter) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 18.dp),
                insideMargin = PaddingValues(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = autoNew, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(10.dp))
                    Button(
                        onClick = {
                            autoNew = ""
                            page = 1
                            pendingCheck = true
                        },
                        modifier = Modifier.height(38.dp)
                    ) { Text("查看") }
                }
            }
        }
    }

    // 飘字颜色
    if (showColor) MikuDialog(
        onDismiss = { showColor = false },
        title = "飘字颜色",
        summary = "每个字在这组颜色里随机取一个"
    ) {
        Column {
            COLOR_VALUES.forEachIndexed { idx, c ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            textColor = idx
                            Prefs.setTextColor(ctx, idx)
                            showColor = false
                            push()
                        }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(Color(c))
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(text = COLOR_NAMES[idx], fontSize = 14.sp, modifier = Modifier.weight(1f))
                    if (idx == textColor) Text(text = "✓", fontSize = 14.sp)
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        showColor = false
                        showPalette = true
                    }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Color(Prefs.customColor(ctx)))
                )
                Spacer(Modifier.width(12.dp))
                Text(text = "自定义颜色", fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text(text = if (textColor == 6) "✓" else "调色", fontSize = 13.sp)
            }
            Spacer(Modifier.height(8.dp))
            DlgButton("关闭", Modifier.fillMaxWidth()) { showColor = false }
        }
    }

    // 调色盘
    if (showPalette) {
        val init = Prefs.customColor(ctx)
        val hsv0 = FloatArray(3)
        android.graphics.Color.colorToHSV(init, hsv0)
        var hue by remember { mutableStateOf(hsv0[0]) }
        var sat by remember { mutableStateOf(hsv0[1] * 100f) }
        var valv by remember { mutableStateOf(hsv0[2] * 100f) }
        val cur = android.graphics.Color.HSVToColor(floatArrayOf(hue, sat / 100f, valv / 100f))

        MikuDialog(
            onDismiss = { showPalette = false },
            title = "自定义颜色",
            summary = "调好后点应用，会覆盖原来的自定义色卡"
        ) {
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(cur))
                )
                Spacer(Modifier.height(10.dp))
                SliderPreference(
                    value = hue,
                    onValueChange = { hue = it },
                    onValueChangeFinished = {},
                    title = "色相",
                    valueText = "${hue.roundToInt()}°",
                    valueRange = 0f..360f
                )
                SliderPreference(
                    value = sat,
                    onValueChange = { sat = it },
                    onValueChangeFinished = {},
                    title = "饱和度",
                    valueText = "${sat.roundToInt()}%",
                    valueRange = 0f..100f
                )
                SliderPreference(
                    value = valv,
                    onValueChange = { valv = it },
                    onValueChangeFinished = {},
                    title = "亮度",
                    valueText = "${valv.roundToInt()}%",
                    valueRange = 0f..100f
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DlgButton("应用", Modifier.weight(1f), primary = true) {
                        Prefs.setCustomColor(ctx, cur)
                        textColor = 6
                        Prefs.setTextColor(ctx, 6)
                        showPalette = false
                        push()
                    }
                    DlgButton("重置", Modifier.weight(1f)) {
                        Prefs.setCustomColor(ctx, 0xFFFFFFFF.toInt())
                        textColor = 0
                        Prefs.setTextColor(ctx, 0)
                        showPalette = false
                        push()
                    }
                }
                Spacer(Modifier.height(8.dp))
                DlgButton("关闭", Modifier.fillMaxWidth()) { showPalette = false }
            }
        }
    }

    // 手动输入数值
    if (editKind != 0) {
        val t = when (editKind) { 1 -> "数量"; 2 -> "速度"; 3 -> "大小"; else -> "调整大小" }
        val initV = when (editKind) { 1 -> count; 2 -> speed; 3 -> size; else -> uiScale }
        val minV = when (editKind) { 1 -> 20; 4 -> 70; else -> 60 }
        val maxV = when (editKind) { 1 -> 110; 4 -> 150; else -> 220 }
        val unit = when (editKind) { 1 -> "个"; else -> "%" }
        var buf by remember(editKind) { mutableStateOf(initV.toString()) }

        MikuDialog(
            onDismiss = { editKind = 0 },
            title = "手动输入",
            summary = "$t：$minV - $maxV$unit"
        ) {
            Column {
                TextField(
                    value = buf,
                    onValueChange = { v -> buf = v.filter { it.isDigit() }.take(3) },
                    label = t
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DlgButton("确定", Modifier.weight(1f), primary = true) {
                        val v = buf.toIntOrNull()
                        if (v == null || v < minV || v > maxV) {
                            toast(ctx, "请输入 $minV - $maxV 之间的数值")
                        } else {
                            when (editKind) {
                                1 -> { count = v; Prefs.setCount(ctx, v) }
                                2 -> { speed = v; Prefs.setSpeedRaw(ctx, v) }
                                3 -> { size = v; Prefs.setSizeRaw(ctx, v) }
                                else -> {
                                    uiScale = v
                                    Prefs.setUiScale(ctx, v)
                                    onScaleChanged(v)
                                }
                            }
                            editKind = 0
                            push()
                        }
                    }
                    DlgButton("取消", Modifier.weight(1f)) { editKind = 0 }
                }
            }
        }
    }

    // 主题页（整页覆盖，排版参考系统设置）
    androidx.compose.animation.AnimatedVisibility(
        visible = page == 2,
        enter = androidx.compose.animation.slideInHorizontally(initialOffsetX = { it }) +
                androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.fadeOut(animationSpec = androidx.compose.animation.core.tween(120))
    ) {
        run {
            var seg by remember { mutableStateOf(if (themeMode == 3) 0 else themeMode) }
            var localScale by remember { mutableStateOf(Prefs.uiScale(ctx)) }
            val themeScroll = rememberScrollState()

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationX = backProgress * screenW }
                    .background(if (dark) Color(0xFF000000) else Color(0xFFFFFFFF))
            ) {
                FoldingTitle(
                    scroll = themeScroll,
                    title = "主题",
                    dark = dark,
                    onBack = { page = 0 },
                    blurEnabled = blurTitle
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(themeScroll)
                        .padding(horizontal = 16.dp)
                        .padding(top = 12.dp, bottom = 28.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("跟随系统", "浅色", "深色").forEachIndexed { idx, name ->
                            val on = seg == idx && themeMode != 3
                            Button(
                                onClick = {
                                    seg = idx
                                    themeMode = idx
                                    Prefs.setThemeMode(ctx, idx)
                                    onModeChanged(idx)
                                },
                                modifier = Modifier.weight(1f),
                                colors = if (on) ButtonDefaults.buttonColorsPrimary()
                                else ButtonDefaults.buttonColors()
                            ) {
                                Text(text = name, fontSize = 13.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))

                    Card(insideMargin = PaddingValues(0.dp)) {
                        SwitchPreference(
                            title = "启用莫奈取色",
                            summary = "跟随壁纸取色（需要 Android 12 及以上）",
                            checked = themeMode == 3,
                            onCheckedChange = { v ->
                                if (v) {
                                    themeMode = 3
                                    Prefs.setThemeMode(ctx, 3)
                                    onModeChanged(3)
                                } else {
                                    themeMode = seg
                                    Prefs.setThemeMode(ctx, seg)
                                    onModeChanged(seg)
                                }
                            }
                        )
                    }
                    Spacer(Modifier.height(10.dp))

                    Card(insideMargin = PaddingValues(0.dp)) {
                        SwitchPreference(
                            title = "标题模糊",
                            summary = "滑动时大标题模糊消散，居中小标题由模糊变清晰",
                            checked = blurTitle,
                            onCheckedChange = {
                                blurTitle = it
                                Prefs.setBlurTitle(ctx, it)
                            }
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(if (dark) Color(0xFF262626) else Color(0xFFEDEDED))
                        )
                        SwitchPreference(
                            title = "预测性返回手势",
                            summary = "在主题页、关于页做返回手势时退回主页，而不是退出应用",
                            checked = predictBack,
                            onCheckedChange = {
                                predictBack = it
                                Prefs.setPredictBack(ctx, it)
                            }
                        )
                    }
                    Spacer(Modifier.height(10.dp))

                    Card(insideMargin = PaddingValues(0.dp)) {
                        BasicComponent(
                            title = "调整大小",
                            summary = "调整全局字号比例",
                            onClick = { editKind = 4 },
                            endActions = { Text("${localScale}%") }
                        )
                        SliderPreference(
                            value = localScale.toFloat(),
                            onValueChange = { v ->
                                localScale = v.roundToInt()
                                Prefs.setUiScale(ctx, localScale)
                                onScaleChanged(localScale)
                            },
                            onValueChangeFinished = {},
                            title = "",
                            valueText = "",
                            valueRange = 70f..150f
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                    DlgButton("返回", Modifier.fillMaxWidth()) { page = 0 }
                }
            }
        }
    }

    // 关于页（从右往左滑入 / 滑出，避免生硬切换）
    androidx.compose.animation.AnimatedVisibility(
        visible = page == 1,
        enter = androidx.compose.animation.slideInHorizontally(initialOffsetX = { it }) +
                androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.fadeOut(animationSpec = androidx.compose.animation.core.tween(120))
    ) {
    run {
        var checkMsg by remember { mutableStateOf("") }
        var checking by remember { mutableStateOf(false) }

        LaunchedEffect(pendingCheck) {
            if (pendingCheck) {
                pendingCheck = false
                if (!checking) {
                    checking = true
                    checkMsg = "正在检查…"
                    Thread {
                        val r = UpdateChecker.check(GitHubUrl, currentVersion)
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            checking = false
                            checkMsg = r.message
                            if (r.hasUpdate && r.url.isNotEmpty()) latestUrl = r.url
                        }
                    }.start()
                }
            }
        }

        val aboutScroll = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationX = backProgress * screenW }
                .background(if (dark) Color(0xFF000000) else Color(0xFFFFFFFF))
        ) {
            FoldingTitle(
                scroll = aboutScroll,
                title = "关于",
                dark = dark,
                onBack = { page = 0 },
                blurEnabled = blurTitle
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(aboutScroll)
                    .padding(horizontal = 16.dp)
                    .padding(top = 12.dp, bottom = 28.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(R.drawable.app_icon),
                        contentDescription = null,
                        modifier = Modifier.size(58.dp)
                    )
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(text = "Miku", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(2.dp))
                        Text(text = "版本 " + currentVersion, fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.height(16.dp))

                // 想法
                Card(insideMargin = PaddingValues(0.dp)) {
                    BasicComponent(
                        title = "为什么做这个",
                        summary = "雪花落下来的时候很好看。我就想，为什么不能做一个小工具，让屏幕上也飘一会儿自己的雪？于是有了 Miku。\n\n" +
                                "后来看到一个能把画面盖在屏幕上、又不进截图的 SH 脚本，这个想法就更具体了：飘雪要能盖住整个屏幕，还要能在截图和录屏里干干净净地不出现。",
                    )
                }

                Spacer(Modifier.height(12.dp))

                // 功能（分组小标题，卡片外面左上角）
                Text(
                    text = "功能",
                    fontSize = 13.sp,
                    modifier = Modifier.padding(start = 16.dp, bottom = 6.dp)
                )
                Card(insideMargin = PaddingValues(0.dp)) {
                    BasicComponent(title = "文字飘雪", summary = "每个字单独飘，可旋转、翻转、倾斜")
                    BasicComponent(title = "图片飘雪", summary = "最多 8 张，自动缩放，可只飘图片")
                    BasicComponent(title = "数量 / 速度 / 大小", summary = "三个滑杆实时调整")
                    BasicComponent(title = "主题", summary = "颜色、标题模糊、返回手势、调整大小")
                    BasicComponent(title = "授权通道", summary = "Shizuku / Root / ADB 任选一条")
                    BasicComponent(title = "不进截图", summary = "需 root 或 ADB 授权后才能开启")
                }

                Spacer(Modifier.height(12.dp))

                Card(insideMargin = PaddingValues(0.dp)) {
                    BasicComponent(title = "创作者", endActions = { Text("du|壹玖") })
                    BasicComponent(title = "版本", endActions = { Text("7.1") })
                    BasicComponent(
                        title = "开源页",
                        summary = "当前项目已经开源，点击用默认浏览器打开 GitHub 页",
                        endActions = { Text("打开") },
                        onClick = {
                            try {
                                ctx.startActivity(
                                    Intent(Intent.ACTION_VIEW, android.net.Uri.parse(GitHubUrl))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            } catch (t: Throwable) {
                                toast(ctx, "没有可用的浏览器")
                            }
                        }
                    )
                    BasicComponent(
                        title = "检查更新",
                        summary = if (checkMsg.isEmpty()) "对比开源页上安装包的版本后缀" else checkMsg,
                        endActions = { Text(if (checking) "检查中…" else "检查") },
                        onClick = {
                            if (checking) return@BasicComponent
                            checking = true
                            checkMsg = "正在检查…"
                            Thread {
                                val r = UpdateChecker.check(GitHubUrl, currentVersion)
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    checking = false
                                    checkMsg = r.message
                                    if (r.hasUpdate && r.url.isNotEmpty()) latestUrl = r.url
                                }
                            }.start()
                        }
                    )
                }

                if (latestUrl.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    DlgButton("更新", Modifier.fillMaxWidth(), primary = true) {
                        try {
                            ctx.startActivity(
                                Intent(Intent.ACTION_VIEW, android.net.Uri.parse(latestUrl))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        } catch (t: Throwable) {
                            toast(ctx, "打不开浏览器")
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                DlgButton("返回", Modifier.fillMaxWidth(), primary = true) { page = 0 }
            }
        }
    }
    }

    // 首次进入的提醒
    if (showFirstRun) MikuDialog(
        onDismiss = {
            showFirstRun = false
            Prefs.setFirstRun(ctx, false)
        },
        title = "开始之前",
        summary = "1）把本应用的省电策略改成「无限制」，否则后台会被杀；\n" +
                "2）悬浮窗权限用 Shizuku、Root 或 ADB 任一条通道打开；\n" +
                "3）开了「无法截屏、录屏获取」后，截图里看不到飘雪是正常现象。"
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DlgButton("去设置省电策略", Modifier.weight(1f)) {
                try {
                    ctx.startActivity(
                        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (t: Throwable) {
                    toast(ctx, "打不开电池设置")
                }
            }
            DlgButton("知道了", Modifier.weight(1f), primary = true) {
                showFirstRun = false
                Prefs.setFirstRun(ctx, false)
            }
        }
    }
    }
}
