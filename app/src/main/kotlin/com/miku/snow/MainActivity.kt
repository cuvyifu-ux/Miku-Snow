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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
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
            Prefs.ensureAdbScript(this)
        } catch (t: Throwable) {
        }

        setContent {
            val saved = Prefs.themeMode(this)
            val safe = if (saved == 3 && Build.VERSION.SDK_INT < 31) 0 else saved
            var mode by remember { mutableStateOf(modeOf(safe)) }
            MiuixTheme(controller = remember(mode) { ThemeController(mode) }) {
                Screen(onModeChanged = {
                    Prefs.setThemeMode(this, it)
                    mode = modeOf(it)
                })
            }
        }
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

@Composable
private fun MikuDialog(
    onDismiss: () -> Unit,
    title: String,
    summary: String? = null,
    content: @Composable ColumnScope.() -> Unit
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
private fun Screen(onModeChanged: (Int) -> Unit) {
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
    var running by remember { mutableStateOf(Prefs.running(ctx)) }
    var canOverlay by remember { mutableStateOf(Settings.canDrawOverlays(ctx)) }
    var themeMode by remember { mutableStateOf(Prefs.themeMode(ctx)) }

    var showPerm by remember { mutableStateOf(false) }
    var showDiag by remember { mutableStateOf(false) }
    var showTheme by remember { mutableStateOf(false) }
    var showAdb by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf(0) }          // 0 = 主页，1 = 关于
    var latestUrl by remember { mutableStateOf("") }
    val currentVersion = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"
    } catch (t: Throwable) { "0" }
    var showNoPriv by remember { mutableStateOf(false) }
    var hasShellPriv by remember { mutableStateOf(Priv.cachedOk()) }
    var privChecked by remember { mutableStateOf(false) }
    var privDetail by remember { mutableStateOf("") }
    var privTick by remember { mutableStateOf(0) }
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

    // 后台探测一次权限；每 400ms 看结果是否回来了
    LaunchedEffect(canOverlay, showPerm, privTick) {
        try { Priv.refreshAsync(ctx) } catch (t: Throwable) { }
        repeat(10) {
            delay(400)
            hasShellPriv = Priv.cachedOk()
            privDetail = Priv.detail()
            if (hasShellPriv) return@LaunchedEffect
        }
        privChecked = true
    }

    Scaffold { pad ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp)
        ) {
            // 顶部大标题（照你「桌面弹窗」那页的排版：大标题 + 一行说明）
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 10.dp, start = 4.dp)
            ) {
                Text(text = "Miku", fontSize = 30.sp, fontWeight = FontWeight.Bold)
            }

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
                    title = "主题颜色",
                    summary = "跟随系统 / 浅色 / 深色 / 莫奈",
                    onClick = { showTheme = true },
                    endActions = { Text(modeName(themeMode)) }
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
            val skipUsable = privChecked && hasShellPriv
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
                            summary = "当前：" + OverlayService.layerState,
                            checked = secure,
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
                    label = "要飘的字（每个字单独飘）"
                )
                BasicComponent(
                    title = "字数",
                    summary = "${text.length} / ${Prefs.MAX_TEXT}　停顿 0.3 秒自动保存"
                )
            }

            Spacer(Modifier.height(12.dp))

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
            Spacer(Modifier.height(12.dp))
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
            Spacer(Modifier.height(12.dp))
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
    if (showImages) MikuDialog(
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

    // 关于页（从右往左滑入 / 滑出，避免生硬切换）
    androidx.compose.animation.AnimatedVisibility(
        visible = page == 1,
        enter = androidx.compose.animation.slideInHorizontally(initialOffsetX = { it }) +
                androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.slideOutHorizontally(targetOffsetX = { it }) +
                androidx.compose.animation.fadeOut()
    ) {
    run {
        var checkMsg by remember { mutableStateOf("") }
        var checking by remember { mutableStateOf(false) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(if (dark) Color(0xFF000000) else Color(0xFFFFFFFF))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(top = 24.dp, bottom = 28.dp)
            ) {
                Text(text = "关于", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                Text(text = "Miku · 飘雪", fontSize = 13.sp)
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

                // 功能
                Card(insideMargin = PaddingValues(0.dp)) {
                    BasicComponent(title = "功能", summary = "")
                    BasicComponent(title = "文字飘雪", summary = "每个字单独飘，可旋转、翻转、倾斜")
                    BasicComponent(title = "图片飘雪", summary = "最多 8 张，自动缩放，可只飘图片")
                    BasicComponent(title = "数量 / 速度 / 大小", summary = "三个滑杆实时调整")
                    BasicComponent(title = "主题颜色", summary = "跟随系统、浅色、深色、莫奈取色")
                    BasicComponent(title = "授权通道", summary = "Shizuku / Root / ADB 任选一条")
                    BasicComponent(title = "不进截图", summary = "需 root 或 ADB 授权后才能开启")
                }

                Spacer(Modifier.height(12.dp))

                Card(insideMargin = PaddingValues(0.dp)) {
                    BasicComponent(title = "创作者", endActions = { Text("du|壹玖") })
                    BasicComponent(title = "版本", endActions = { Text("5.2") })
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
