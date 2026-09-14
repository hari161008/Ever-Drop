package com.sameerasw.medrop

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.gson.Gson
import com.sameerasw.medrop.data.repository.MeDropRepository
import com.sameerasw.medrop.domain.model.EverDropItem
import com.sameerasw.medrop.domain.model.MeDropProfileType
import com.sameerasw.medrop.domain.model.MeDropSettings
import com.sameerasw.medrop.utils.EverDropFileManager
import com.sameerasw.medrop.ui.activities.SettingsActivity
import com.sameerasw.medrop.ui.components.MeDropFloatingToolbar
import com.sameerasw.medrop.ui.components.ToolbarItem
import com.sameerasw.medrop.ui.core.sheets.PermissionItem
import com.sameerasw.medrop.ui.core.sheets.PermissionsBottomSheet
import com.sameerasw.medrop.ui.features.EverDropReceiveUI
import com.sameerasw.medrop.ui.features.EverDropShareHubUI
import com.sameerasw.medrop.ui.features.MeDropHeaderUI
import com.sameerasw.medrop.ui.features.MeDropProfileFieldsUI
import com.sameerasw.medrop.ui.modifiers.BlurDirection
import com.sameerasw.medrop.ui.modifiers.progressiveBlur
import com.sameerasw.medrop.ui.theme.MeDropTheme
import com.sameerasw.medrop.utils.HapticUtil
import com.sameerasw.medrop.utils.DeviceFacing
import com.sameerasw.medrop.utils.EverDropOrientationDetector
import com.sameerasw.medrop.utils.MeDropContactPickerHelper
import com.sameerasw.medrop.utils.PermissionUtils
import com.sameerasw.medrop.viewmodels.MeDropViewModel
import kotlinx.coroutines.launch

/**
 * MainActivity
 *
 * Core application entry point:
 * - Hosts Ever Drop Share Hub and Profile UI in edge-to-edge Scaffold
 * - Manages dynamic IME / keyboard layout adjustment
 * - Processes incoming Android Share Sheet intents (ACTION_SEND & ACTION_SEND_MULTIPLE)
 * - Drives NFC Host Card Emulation (HCE) broadcasting and foreground reader mode
 */
@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : AppCompatActivity() {

    private val currentIntent = mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        currentIntent.value = intent
        enableEdgeToEdge(
            statusBarStyle =
                SystemBarStyle.auto(
                    android.graphics.Color.TRANSPARENT,
                    android.graphics.Color.TRANSPARENT,
                ),
            navigationBarStyle =
                SystemBarStyle.auto(
                    android.graphics.Color.TRANSPARENT,
                    android.graphics.Color.TRANSPARENT,
                ),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        splashScreen.setOnExitAnimationListener { splashScreenViewProvider ->
            try {
                val splashScreenView = splashScreenViewProvider.view
                val fadeOut =
                    android.animation.ObjectAnimator.ofFloat(splashScreenView, "alpha", 1f, 0f).apply {
                        interpolator = androidx.interpolator.view.animation.FastOutSlowInInterpolator()
                        duration = 400
                    }
                fadeOut.addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        splashScreenViewProvider.remove()
                    }
                })
                fadeOut.start()
            } catch (e: Exception) {
                splashScreenViewProvider.remove()
            }
        }

        val isDarkMode =
            (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        window.setBackgroundDrawableResource(if (isDarkMode) android.R.color.black else R.color.app_window_background)

        setContent {
            val context = LocalContext.current
            val viewModel: MeDropViewModel = viewModel()

            val isPitchBlackThemeEnabled by viewModel.isPitchBlackThemeEnabled
            val isBlurEnabled by viewModel.isBlurEnabled
            val hasContactsPerm by viewModel.hasContactsPermission
            val settings by viewModel.meDropSettings
            val safeSettings = settings ?: MeDropSettings()

            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner, safeSettings.enableReceiving) {
                val observer =
                    LifecycleEventObserver { _, event ->
                        when (event) {
                            Lifecycle.Event.ON_RESUME -> {
                                com.sameerasw.medrop.utils.EverDropWifiDirectManager.isAppInForeground = true
                                if (!com.sameerasw.medrop.utils.EverDropWifiDirectManager.isTransferBusy()) {
                                    com.sameerasw.medrop.services.EverDropReceiveService.stop(context)
                                }
                                if (safeSettings.enableReceiving) {
                                    com.sameerasw.medrop.utils.EverDropWifiDirectManager.startDiscoverableReceiver(context)
                                }
                                viewModel.check(context)
                            }
                            Lifecycle.Event.ON_PAUSE -> {
                                com.sameerasw.medrop.utils.EverDropWifiDirectManager.isAppInForeground = false
                                if (safeSettings.enableReceiving) {
                                    com.sameerasw.medrop.services.EverDropReceiveService.start(context)
                                }
                            }
                            Lifecycle.Event.ON_DESTROY -> {
                                com.sameerasw.medrop.utils.EverDropWifiDirectManager.isAppInForeground = false
                            }
                            else -> {}
                        }
                    }
                lifecycleOwner.lifecycle.addObserver(observer)
                if (safeSettings.enableReceiving) {
                    com.sameerasw.medrop.utils.EverDropWifiDirectManager.startDiscoverableReceiver(context)
                }
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                }
            }

            remember(context) { viewModel.check(context) }

            val incomingWifiRequest by com.sameerasw.medrop.utils.EverDropWifiDirectManager.incomingRequest.collectAsState()
            val wifiTransferProgress by com.sameerasw.medrop.utils.EverDropWifiDirectManager.transferProgress.collectAsState()

            val activeShareType by com.sameerasw.medrop.utils.EverDropNfcShareManager.activeShareType.collectAsState()
            var receivedItem by remember { mutableStateOf<com.sameerasw.medrop.domain.model.EverDropItem?>(null) }

            val activity = context as? androidx.activity.ComponentActivity
            val mainView = androidx.compose.ui.platform.LocalView.current

            fun processReceivedItem(item: EverDropItem) {
                val loc = IntArray(2)
                mainView.getLocationInWindow(loc)
                val cx = loc[0] + (mainView.width / 2f)
                val cy = loc[1] + (mainView.height / 2f)
                MainActivity.triggerLiquidRipple(cx, cy)
                HapticUtil.performHeavyHaptic(mainView)

                when (item) {
                    is EverDropItem.Text -> {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        clipboard?.setPrimaryClip(ClipData.newPlainText("Received Text", item.text))
                        Toast.makeText(context, "Text copied to clipboard", Toast.LENGTH_SHORT).show()
                    }
                    is EverDropItem.FileItem -> {
                        if (item.localSavedUri == null && !item.base64Data.isNullOrBlank()) {
                            try {
                                val bytes = android.util.Base64.decode(item.base64Data, android.util.Base64.DEFAULT)
                                EverDropFileManager.saveFileToEverShare(
                                    context,
                                    item.name,
                                    item.mimeType,
                                    bytes
                                )
                            } catch (_: Exception) {}
                        }
                        Toast.makeText(
                            context,
                            "Saved to Downloads/Ever Share: ${item.name}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    is EverDropItem.Contact -> {
                        receivedItem = item
                    }
                    is EverDropItem.P2pHandover -> {
                        Toast.makeText(
                            context,
                            "NFC Handover: ${item.deviceName} sharing ${item.payloadName} via Wi-Fi Direct",
                            Toast.LENGTH_LONG
                        ).show()
                        com.sameerasw.medrop.utils.EverDropWifiDirectManager.startDiscoverableReceiver(context)
                    }
                }
            }

            fun handleIncomingIntent(incomingIntent: Intent?) {
                if (incomingIntent == null) return

                // 1. NFC NDEF Tag / Beam Discovered
                if (incomingIntent.action == android.nfc.NfcAdapter.ACTION_NDEF_DISCOVERED) {
                    val rawMsgs = incomingIntent.getParcelableArrayExtra(android.nfc.NfcAdapter.EXTRA_NDEF_MESSAGES)
                    if (rawMsgs != null) {
                        for (raw in rawMsgs) {
                            val msg = raw as? android.nfc.NdefMessage ?: continue
                            val parsed = com.sameerasw.medrop.utils.MeDropNfcManager.parseNdefMessage(context, msg)
                            if (parsed != null) {
                                processReceivedItem(parsed)
                                break
                            }
                        }
                    }
                    return
                }

                // 2. Android Share Sheet: Single file or text
                if (incomingIntent.action == Intent.ACTION_SEND) {
                    val streamUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        incomingIntent.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        incomingIntent.getParcelableExtra(Intent.EXTRA_STREAM)
                    } ?: incomingIntent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri

                    val sharedText = incomingIntent.getStringExtra(Intent.EXTRA_TEXT)
                        ?: incomingIntent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()

                    if (streamUri != null) {
                        val (name, sizeStr, rawBytes) = EverDropFileManager.queryFileInfoWithRawSize(context, streamUri)
                        val mime = incomingIntent.type ?: context.contentResolver.getType(streamUri) ?: "*/*"
                        if (EverDropFileManager.isTextFile(name, mime)) {
                            val fileText = EverDropFileManager.readTextFromUri(context, streamUri)
                            if (fileText != null) {
                                viewModel.clearShareFile(context)
                                viewModel.setShareText(context, fileText)
                                Toast.makeText(context, "Text ready to beam via Ever Drop", Toast.LENGTH_SHORT).show()
                                return
                            }
                        }
                        viewModel.setSelectedFile(context, streamUri, name, sizeStr, rawBytes, mime)
                        Toast.makeText(context, "File ready to beam: $name", Toast.LENGTH_SHORT).show()
                    } else if (!sharedText.isNullOrBlank()) {
                        viewModel.setShareText(context, sharedText)
                        Toast.makeText(context, "Text ready to beam via Ever Drop", Toast.LENGTH_SHORT).show()
                    }
                    return
                }

                // 3. Android Share Sheet: Multiple files
                if (incomingIntent.action == Intent.ACTION_SEND_MULTIPLE) {
                    val streamUris = incomingIntent.getParcelableArrayListExtra<android.net.Uri>(Intent.EXTRA_STREAM)
                        ?: run {
                            val list = ArrayList<android.net.Uri>()
                            incomingIntent.clipData?.let { cd ->
                                for (i in 0 until cd.itemCount) {
                                    cd.getItemAt(i)?.uri?.let { list.add(it) }
                                }
                            }
                            list
                        }
                    val firstUri = streamUris.firstOrNull()
                    if (firstUri != null) {
                        val (name, sizeStr, rawBytes) = EverDropFileManager.queryFileInfoWithRawSize(context, firstUri)
                        val mime = context.contentResolver.getType(firstUri) ?: "*/*"
                        if (EverDropFileManager.isTextFile(name, mime)) {
                            val fileText = EverDropFileManager.readTextFromUri(context, firstUri)
                            if (fileText != null) {
                                viewModel.clearShareFile(context)
                                viewModel.setShareText(context, fileText)
                                Toast.makeText(context, "Text ready to beam via Ever Drop", Toast.LENGTH_SHORT).show()
                                return
                            }
                        }
                        viewModel.setSelectedFile(context, firstUri, name, sizeStr, rawBytes, mime)
                        val countMsg = if (streamUris.size > 1) " (1 of ${streamUris.size})" else ""
                        Toast.makeText(context, "File ready to beam: $name$countMsg", Toast.LENGTH_SHORT).show()
                    }
                    return
                }
            }

            val activeIntent by currentIntent
            LaunchedEffect(activeIntent) {
                handleIncomingIntent(activeIntent)
            }

            val pagerState = rememberPagerState(
                initialPage = 0,
                pageCount = { 2 }
            )

            // Orientation detector: detects screen facing down (share), facing top (receive), or anyhow (both)
            val orientationDetector = remember { EverDropOrientationDetector(context) }
            val facing by orientationDetector.deviceFacing.collectAsState()

            val isReceiveTabActive = pagerState.currentPage == 1
            val isScanActive by com.sameerasw.medrop.services.MeDropHceService.isScanActive.collectAsState()

            val hasStagedContent = activeShareType == com.sameerasw.medrop.utils.ShareTargetType.FILE ||
                    activeShareType == com.sameerasw.medrop.utils.ShareTargetType.TEXT

            val shouldShare = if (isScanActive) {
                true // Maintain broadcast during active APDU session regardless of minor tilts
            } else if (isReceiveTabActive) {
                false
            } else if (hasStagedContent) {
                // When user actively selected a file or text to beam, keep share active
                true
            } else if (safeSettings.orientationShare) {
                facing == DeviceFacing.FACING_DOWN || facing == DeviceFacing.ANYHOW
            } else {
                true
            }

            val shouldReceive = if (isScanActive) {
                false
            } else if (isReceiveTabActive) {
                true // Explicitly discoverable & ready to receive on the Receive page
            } else if (safeSettings.orientationShare) {
                safeSettings.enableReceiving && (facing == DeviceFacing.FACING_UP || facing == DeviceFacing.ANYHOW)
            } else {
                safeSettings.enableReceiving
            }

            DisposableEffect(activity, lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        orientationDetector.start()
                    } else if (event == Lifecycle.Event.ON_PAUSE) {
                        orientationDetector.stop()
                        com.sameerasw.medrop.utils.EverDropWifiDirectManager.stopPeerDiscovery()
                        if (activity != null) {
                            com.sameerasw.medrop.utils.MeDropNfcManager.disableReaderMode(activity)
                            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                                com.sameerasw.medrop.utils.MeDropNfcManager.stopBroadcast(activity)
                            }
                        }
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                orientationDetector.start()
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                    orientationDetector.stop()
                    if (activity != null) {
                        com.sameerasw.medrop.utils.MeDropNfcManager.disableReaderMode(activity)
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                            com.sameerasw.medrop.utils.MeDropNfcManager.stopBroadcast(activity)
                        }
                    }
                }
            }

            // Dynamically coordinate NFC Broadcast based on shouldShare, safeSettings, and activeShareType
            LaunchedEffect(activity, shouldShare, safeSettings, activeShareType) {
                if (activity != null) {
                    if (shouldShare) {
                        com.sameerasw.medrop.utils.MeDropNfcManager.startBroadcast(activity, safeSettings)
                    } else {
                        com.sameerasw.medrop.utils.MeDropNfcManager.stopBroadcast(activity)
                    }
                }
            }

            // Automatically clear staged share after sharing file or text completes
            LaunchedEffect(Unit) {
                com.sameerasw.medrop.services.MeDropHceService.onTransferCompleted.collect {
                    val currentType = com.sameerasw.medrop.utils.EverDropNfcShareManager.activeShareType.value
                    if (currentType == com.sameerasw.medrop.utils.ShareTargetType.FILE ||
                        currentType == com.sameerasw.medrop.utils.ShareTargetType.TEXT) {
                        viewModel.clearShareFile(context)
                        viewModel.clearShareText(context)
                        com.sameerasw.medrop.utils.EverDropNfcShareManager.clearShare()
                        Toast.makeText(context, "Transfer complete", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            // Reset Share Files and Share Text containers after Wi-Fi Direct transfer completes
            LaunchedEffect(Unit) {
                com.sameerasw.medrop.utils.EverDropWifiDirectManager.onSenderTransferCompleted.collect { completedType ->
                    when (completedType) {
                        com.sameerasw.medrop.domain.model.TransferType.FILE -> {
                            viewModel.clearShareFile(context)
                        }
                        com.sameerasw.medrop.domain.model.TransferType.TEXT -> {
                            viewModel.clearShareText(context)
                        }
                    }
                    com.sameerasw.medrop.utils.EverDropNfcShareManager.clearShare()
                }
            }

            // Dynamically coordinate NFC Reader Mode based on shouldReceive
            LaunchedEffect(activity, shouldReceive) {
                if (activity != null) {
                    if (shouldReceive) {
                        com.sameerasw.medrop.utils.MeDropNfcManager.enableReaderMode(activity) { item ->
                            processReceivedItem(item)
                        }
                    } else {
                        com.sameerasw.medrop.utils.MeDropNfcManager.disableReaderMode(activity)
                    }
                }
            }

            val entranceProgress = remember { androidx.compose.animation.core.Animatable(0f) }
            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(250)
                entranceProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(
                        durationMillis = 1200,
                        easing = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0.0f, 0f, 1.0f)
                    )
                )
            }

            val density = LocalDensity.current
            val view = LocalView.current
            val scope = rememberCoroutineScope()

            // Haptics on tab switch
            LaunchedEffect(pagerState) {
                var isFirst = true
                snapshotFlow { pagerState.currentPage }.collect { _ ->
                    if (isFirst) {
                        isFirst = false
                    } else {
                        HapticUtil.performHeavyHaptic(view)
                    }
                }
            }

            var showPermissionsSheet by remember { mutableStateOf(false) }

            val contactPickerLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.StartActivityForResult()
            ) { result ->
                if (result.resultCode == Activity.RESULT_OK) {
                    result.data?.data?.let { uri ->
                        scope.launch {
                            val pickedContact = MeDropContactPickerHelper.processResult(uri, context)
                            viewModel.setMeDropContact(context, pickedContact)
                        }
                    }
                }
            }

            val requestPermissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) { isGranted ->
                viewModel.hasContactsPermission.value = isGranted
                if (isGranted) {
                    showPermissionsSheet = false
                    contactPickerLauncher.launch(MeDropContactPickerHelper.buildPickIntent())
                }
            }

            val requiredWifiPermissions = remember {
                val list = mutableListOf<String>()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    list.add(Manifest.permission.POST_NOTIFICATIONS)
                    list.add(Manifest.permission.NEARBY_WIFI_DEVICES)
                } else {
                    list.add(Manifest.permission.ACCESS_FINE_LOCATION)
                }
                list
            }

            val wifiPermissionsLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions()
            ) { perms ->
                val wifiGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    perms[Manifest.permission.NEARBY_WIFI_DEVICES] == true
                } else {
                    perms[Manifest.permission.ACCESS_FINE_LOCATION] == true
                }
                if (wifiGranted && safeSettings.enableReceiving) {
                    com.sameerasw.medrop.utils.EverDropWifiDirectManager.startDiscoverableReceiver(context)
                }
            }

            LaunchedEffect(safeSettings.enableReceiving) {
                if (safeSettings.enableReceiving) {
                    val ungranted = requiredWifiPermissions.filter {
                        androidx.core.content.ContextCompat.checkSelfPermission(context, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
                    }
                    if (ungranted.isNotEmpty()) {
                        wifiPermissionsLauncher.launch(ungranted.toTypedArray())
                    }
                }
            }

            val toolbarItems = listOf(
                ToolbarItem(
                    iconRes = R.drawable.rounded_share_24,
                    labelRes = R.string.share_section_title,
                    onClick = {
                        scope.launch {
                            pagerState.animateScrollToPage(0, animationSpec = tween(300))
                        }
                    }
                ),
                ToolbarItem(
                    iconRes = R.drawable.rounded_contactless_24,
                    labelRes = R.string.receive_section_title,
                    onClick = {
                        scope.launch {
                            pagerState.animateScrollToPage(1, animationSpec = tween(300))
                        }
                    }
                )
            )

            val imeBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
            val isKeyboardVisible = imeBottom > 0.dp

            MeDropTheme(pitchBlackTheme = isPitchBlackThemeEnabled) {
                Scaffold(
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ) { _ ->
                    val statusBarHeightPx =
                        with(density) {
                            WindowInsets.statusBars
                                .asPaddingValues()
                                .calculateTopPadding()
                                .toPx()
                        }

                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .progressiveBlur(
                                    blurRadius = if (isBlurEnabled) 40f else 0f,
                                    height = statusBarHeightPx * 1.15f,
                                    direction = BlurDirection.TOP,
                                ),
                    ) {
                        val scrollState = rememberScrollState()

                        // Smoothly scroll to give generous clearance when keyboard appears
                        LaunchedEffect(isKeyboardVisible) {
                            if (isKeyboardVisible) {
                                kotlinx.coroutines.delay(100)
                                scrollState.animateScrollTo(scrollState.maxValue)
                            }
                        }

                        Column(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .imePadding()
                                    .progressiveBlur(
                                        blurRadius = if (isBlurEnabled) 40f else 0f,
                                        height = with(density) { 150.dp.toPx() },
                                        direction = BlurDirection.BOTTOM,
                                    )
                                    .verticalScroll(scrollState),
                        ) {
                            Spacer(
                                modifier =
                                    Modifier.height(
                                        WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 12.dp
                                    ),
                            )

                            val contentOffsetY = with(density) { (1f - entranceProgress.value) * 300.dp.toPx() }
                            val contentAlpha = entranceProgress.value.coerceIn(0f, 1f)

                            HorizontalPager(
                                state = pagerState,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .offset { androidx.compose.ui.unit.IntOffset(0, contentOffsetY.toInt()) }
                                    .graphicsLayer { alpha = contentAlpha },
                                verticalAlignment = Alignment.Top,
                            ) { page ->
                                when (page) {
                                    0 -> {
                                        // Share Hub: Options to share files and text
                                        EverDropShareHubUI(
                                            viewModel = viewModel,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                        )
                                    }
                                    1 -> {
                                        // Receive Page: Discoverable status and incoming transfers
                                        EverDropReceiveUI(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                        )
                                    }
                                }
                            }

                            // Dynamic bottom spacing: Generous clearance when keyboard is open
                            val bottomSpacerHeight = if (isKeyboardVisible) {
                                160.dp
                            } else {
                                WindowInsets.navigationBars
                                    .asPaddingValues()
                                    .calculateBottomPadding() + 150.dp
                            }
                            Spacer(
                                modifier = Modifier.height(bottomSpacerHeight),
                            )
                        }

                        // Hide floating toolbar when software keyboard is open so it does not obstruct the text area
                        if (!isKeyboardVisible) {
                            val toolbarOffsetY = with(density) { (1f - entranceProgress.value) * 150.dp.toPx() }
                            val isTileAdded by viewModel.isTileAdded
                            MeDropFloatingToolbar(
                                items = toolbarItems,
                                selectedIndex = pagerState.currentPage.coerceIn(0, toolbarItems.size - 1),
                                fabIconRes = R.drawable.rounded_settings_24,
                                fabHasBadge = !isTileAdded,
                                fabAction = {
                                    HapticUtil.performVirtualKeyHaptic(view)
                                    val intent = Intent(context, SettingsActivity::class.java)
                                    context.startActivity(intent)
                                },
                                fabContentDescription = stringResource(R.string.action_settings),
                                modifier =
                                    Modifier
                                        .align(Alignment.BottomCenter)
                                        .offset { androidx.compose.ui.unit.IntOffset(0, toolbarOffsetY.toInt()) }
                                        .graphicsLayer { alpha = entranceProgress.value }
                                        .zIndex(1f),
                            )
                        }
                    }

                    if (showPermissionsSheet) {
                        val permItems = listOf(
                            PermissionItem(
                                iconRes = R.drawable.rounded_contacts_product_24,
                                title = stringResource(R.string.perm_contacts_title),
                                description = stringResource(R.string.perm_contacts_desc),
                                isGranted = hasContactsPerm,
                                actionLabel = stringResource(R.string.perm_action_grant),
                                action = {
                                    requestPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                                }
                            )
                        )

                        PermissionsBottomSheet(
                            onDismissRequest = { showPermissionsSheet = false },
                            featureTitle = stringResource(R.string.feat_medrop_title),
                            permissions = permItems
                        )
                    }

                    when (val item = receivedItem) {
                        is EverDropItem.Contact -> {
                            val contact = item.parsed ?: com.sameerasw.medrop.utils.VCardParser.parse(item.vcard)
                            if (contact != null) {
                                com.sameerasw.medrop.ui.sheets.ReceivedContactBottomSheet(
                                    contact = contact,
                                    onDismissRequest = { receivedItem = null }
                                )
                            }
                        }
                        null -> {}
                        else -> {
                            receivedItem = null
                        }
                    }

                    var currentIncomingRequest by remember { mutableStateOf<com.sameerasw.medrop.domain.model.IncomingTransferRequest?>(null) }
                    LaunchedEffect(incomingWifiRequest) {
                        if (incomingWifiRequest != null) {
                            currentIncomingRequest = incomingWifiRequest
                        }
                    }
                    LaunchedEffect(wifiTransferProgress.status) {
                        if (wifiTransferProgress.status == com.sameerasw.medrop.domain.model.TransferProgressStatus.IDLE ||
                            wifiTransferProgress.status == com.sameerasw.medrop.domain.model.TransferProgressStatus.CANCELLED ||
                            wifiTransferProgress.status == com.sameerasw.medrop.domain.model.TransferProgressStatus.FAILED) {
                            currentIncomingRequest = null
                        }
                    }

                    val activeReq = currentIncomingRequest
                    if (activeReq != null) {
                        com.sameerasw.medrop.ui.activities.IncomingTransferDialog(
                            initialName = activeReq.name,
                            initialSize = activeReq.size,
                            initialType = activeReq.type,
                            initialSender = activeReq.senderName,
                            onDismiss = {
                                currentIncomingRequest = null
                                com.sameerasw.medrop.utils.EverDropWifiDirectManager.resetTransferState()
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        activeDecorView = window.decorView
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        currentIntent.value = intent
    }

    override fun onDestroy() {
        super.onDestroy()
        if (activeDecorView === window.decorView) {
            activeDecorView = null
            activeRippleEffect = null
        }
    }

    companion object {
        private var activeDecorView: android.view.View? = null
        private var activeRippleEffect: com.sameerasw.medrop.ui.effects.NfcRippleEffect? = null

        fun triggerLiquidRipple(cx: Float, cy: Float) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val decor = activeDecorView ?: return
                var ripple = activeRippleEffect
                if (ripple == null) {
                    ripple = com.sameerasw.medrop.ui.effects.NfcRippleEffect(decor)
                    activeRippleEffect = ripple
                }
                ripple.animate(cx, cy)
            }
        }
    }
}