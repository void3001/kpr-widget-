package com.example.widgettimetable

import android.Manifest
import android.app.DatePickerDialog
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.widgettimetable.data.*
import com.example.widgettimetable.receiver.AssignmentReminderReceiver
import com.example.widgettimetable.theme.*
import com.example.widgettimetable.updater.RemoteUpdateInfo
import com.example.widgettimetable.updater.UpdateManager
import com.example.widgettimetable.widget.TimetableWidgetProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {

    private var initialTab: NavigationTab = NavigationTab.TIMETABLE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (intent?.getStringExtra("open_tab") == "assignments") {
            initialTab = NavigationTab.ASSIGNMENTS
        }

        val themePreferences = ThemePreferences(this)
        val assignmentRepo = AssignmentRepository(this)
        val timetableRepo = TimetableRepository(this)

        setContent {
            var currentThemeMode by remember { mutableStateOf(themePreferences.themeMode) }
            var notificationsEnabled by remember { mutableStateOf(themePreferences.notificationsEnabled) }
            val colorScheme = TimetableColors.forMode(currentThemeMode)

            // Notifications permission launcher for Android 13+
            val notificationPermissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) { isGranted ->
                if (isGranted) {
                    Toast.makeText(this, "Notifications enabled for assignment reminders", Toast.LENGTH_SHORT).show()
                }
            }

            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }

            Surface(
                modifier = Modifier.fillMaxSize(),
                color = colorScheme.background
            ) {
                MainAppScreen(
                    initialTab = initialTab,
                    colorScheme = colorScheme,
                    currentThemeMode = currentThemeMode,
                    onThemeChange = { newMode ->
                        themePreferences.themeMode = newMode
                        currentThemeMode = newMode
                    },
                    notificationsEnabled = notificationsEnabled,
                    onNotificationToggle = { enabled ->
                        themePreferences.notificationsEnabled = enabled
                        notificationsEnabled = enabled
                    },
                    assignmentRepo = assignmentRepo,
                    timetableRepo = timetableRepo,
                    onAddWidgetClick = {
                        val appWidgetManager = getSystemService(AppWidgetManager::class.java)
                        val myProvider = ComponentName(this@MainActivity, TimetableWidgetProvider::class.java)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && appWidgetManager.isRequestPinAppWidgetSupported) {
                            val successCallback = PendingIntent.getBroadcast(
                                this@MainActivity, 0,
                                Intent(this@MainActivity, TimetableWidgetProvider::class.java),
                                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                            )
                            appWidgetManager.requestPinAppWidget(myProvider, null, successCallback)
                        } else {
                            Toast.makeText(this@MainActivity, "Widget pinning is supported via Home Screen launcher", Toast.LENGTH_LONG).show()
                        }
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // Send broadcast to update the home screen widget immediately
        val intent = Intent(this, TimetableWidgetProvider::class.java).apply {
            action = TimetableWidgetProvider.ACTION_UPDATE_TIMETABLE
        }
        sendBroadcast(intent)
    }
}

enum class NavigationTab(val title: String) {
    TIMETABLE("Timetable"),
    ASSIGNMENTS("My Assignments"),
    SETTINGS("Settings")
}

@Composable
fun MainAppScreen(
    initialTab: NavigationTab,
    colorScheme: AppColorScheme,
    currentThemeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
    notificationsEnabled: Boolean,
    onNotificationToggle: (Boolean) -> Unit,
    assignmentRepo: AssignmentRepository,
    timetableRepo: TimetableRepository,
    onAddWidgetClick: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var selectedTab by remember { mutableStateOf(initialTab) }
    var assignments by remember { mutableStateOf(assignmentRepo.getAllAssignments()) }
    var showAddAssignmentDialog by remember { mutableStateOf(false) }
    var preselectedSubjectCode by remember { mutableStateOf<String?>(null) }
    var preselectedSubjectName by remember { mutableStateOf<String?>(null) }

    // Remote OTA update states
    var remoteUpdateInfo by remember { mutableStateOf<RemoteUpdateInfo?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf<Float?>(null) }

    fun refreshAssignments() {
        assignments = assignmentRepo.getAllAssignments()
    }

    // Auto-check for updates on app startup
    LaunchedEffect(Unit) {
        val result = UpdateManager.checkRemoteUpdate()
        result.onSuccess { info ->
            if (info != null) {
                remoteUpdateInfo = info
                showUpdateDialog = true
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colorScheme.backgroundGradient)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 72.dp)
        ) {
            when (selectedTab) {
                NavigationTab.TIMETABLE -> {
                    TimetableScreen(
                        colorScheme = colorScheme,
                        timetableRepo = timetableRepo,
                        onAddAssignmentClick = { code, name ->
                            preselectedSubjectCode = code
                            preselectedSubjectName = name
                            showAddAssignmentDialog = true
                        }
                    )
                }
                NavigationTab.ASSIGNMENTS -> {
                    AssignmentsScreen(
                        colorScheme = colorScheme,
                        assignments = assignments,
                        onToggleComplete = { id ->
                            assignmentRepo.toggleCompletion(id)
                            refreshAssignments()
                        },
                        onDelete = { id ->
                            assignmentRepo.deleteAssignment(id)
                            refreshAssignments()
                        },
                        onAddClick = {
                            preselectedSubjectCode = null
                            preselectedSubjectName = null
                            showAddAssignmentDialog = true
                        }
                    )
                }
                NavigationTab.SETTINGS -> {
                    SettingsScreen(
                        colorScheme = colorScheme,
                        currentThemeMode = currentThemeMode,
                        onThemeChange = onThemeChange,
                        notificationsEnabled = notificationsEnabled,
                        onNotificationToggle = onNotificationToggle,
                        onAddWidgetClick = onAddWidgetClick,
                        onCheckUpdateNow = {
                            coroutineScope.launch {
                                Toast.makeText(context, "Checking for latest updates...", Toast.LENGTH_SHORT).show()
                                val checkResult = UpdateManager.checkRemoteUpdate()
                                checkResult.fold(
                                    onSuccess = { info ->
                                        if (info != null) {
                                            remoteUpdateInfo = info
                                            showUpdateDialog = true
                                        } else {
                                            Toast.makeText(context, "You are using the latest version (${UpdateManager.CURRENT_VERSION_NAME})", Toast.LENGTH_LONG).show()
                                        }
                                    },
                                    onFailure = { err ->
                                        Toast.makeText(context, "Could not reach update server. You are on ${UpdateManager.CURRENT_VERSION_NAME}", Toast.LENGTH_SHORT).show()
                                    }
                                )
                            }
                        }
                    )
                }
            }
        }

        // Modern Glassmorphic Bottom Navigation Bar
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(colorScheme.surface)
                    .border(1.dp, colorScheme.surfaceBorder, RoundedCornerShape(24.dp))
                    .padding(vertical = 8.dp, horizontal = 12.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                NavigationTab.values().forEach { tab ->
                    val isSelected = selectedTab == tab
                    val pendingCount = if (tab == NavigationTab.ASSIGNMENTS) assignments.count { !it.isCompleted } else 0

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (isSelected) colorScheme.accentPrimary.copy(alpha = 0.2f) else Color.Transparent)
                            .clickable { selectedTab = tab }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = tab.title,
                                color = if (isSelected) colorScheme.accentPrimary else colorScheme.textSecondary,
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                            if (pendingCount > 0) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(Color(0xFFEF4444))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = pendingCount.toString(),
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Add Assignment Dialog
        if (showAddAssignmentDialog) {
            AddAssignmentDialog(
                colorScheme = colorScheme,
                initialSubjectCode = preselectedSubjectCode,
                initialSubjectName = preselectedSubjectName,
                onDismiss = { showAddAssignmentDialog = false },
                onSave = { assignment ->
                    assignmentRepo.addAssignment(assignment)
                    refreshAssignments()
                    showAddAssignmentDialog = false
                }
            )
        }

        // In-App OTA Update Dialog
        if (showUpdateDialog && remoteUpdateInfo != null) {
            val update = remoteUpdateInfo!!
            AlertDialog(
                onDismissRequest = {
                    if (downloadProgress == null) showUpdateDialog = false
                },
                confirmButton = {
                    if (downloadProgress != null) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            LinearProgressIndicator(
                                progress = { downloadProgress ?: 0f },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                color = colorScheme.accentPrimary
                            )
                            Text(
                                text = "Downloading update... ${(downloadProgress!! * 100).toInt()}%",
                                color = colorScheme.textSecondary,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }
                    } else {
                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    downloadProgress = 0f
                                    UpdateManager.downloadAndInstallApk(
                                        context = context,
                                        apkUrl = update.apkUrl,
                                        onProgress = { downloadProgress = it },
                                        onComplete = {
                                            downloadProgress = null
                                            showUpdateDialog = false
                                        },
                                        onError = { error ->
                                            downloadProgress = null
                                            Toast.makeText(context, error, Toast.LENGTH_LONG).show()
                                        }
                                    )
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = colorScheme.accentPrimary),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Download & Install", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                },
                dismissButton = {
                    if (downloadProgress == null) {
                        TextButton(onClick = { showUpdateDialog = false }) {
                            Text("Later", color = colorScheme.textSecondary)
                        }
                    }
                },
                title = {
                    Text("New Update Available (${update.versionName})", color = colorScheme.textPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "A new version of Widget Timetable is available with new features and improvements.",
                            color = colorScheme.textSecondary,
                            fontSize = 13.sp
                        )
                        Text(
                            text = "What's New in ${update.versionName}:",
                            color = colorScheme.textPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        update.changelog.forEach { item ->
                            Text(text = item, color = colorScheme.textSecondary, fontSize = 12.sp)
                        }
                    }
                },
                containerColor = colorScheme.surface,
                shape = RoundedCornerShape(18.dp)
            )
        }
    }
}

@Composable
fun TimetableScreen(
    colorScheme: AppColorScheme,
    timetableRepo: TimetableRepository,
    onAddAssignmentClick: (String?, String?) -> Unit
) {
    var selectedDay by remember {
        val today = LocalDate.now(ZoneId.of("Asia/Kolkata")).dayOfWeek
        val dayName = when (today) {
            DayOfWeek.MONDAY -> "Monday"
            DayOfWeek.TUESDAY -> "Tuesday"
            DayOfWeek.WEDNESDAY -> "Wednesday"
            DayOfWeek.THURSDAY -> "Thursday"
            DayOfWeek.FRIDAY -> "Friday"
            DayOfWeek.SATURDAY -> "Saturday"
            else -> "Monday"
        }
        mutableStateOf(dayName)
    }

    val days = TimetableRepository.DAYS

    var currentTime by remember { mutableStateOf(LocalTime.now(ZoneId.of("Asia/Kolkata"))) }
    var currentSystemDay by remember {
        mutableStateOf(
            when (LocalDate.now(ZoneId.of("Asia/Kolkata")).dayOfWeek) {
                DayOfWeek.MONDAY -> "Monday"
                DayOfWeek.TUESDAY -> "Tuesday"
                DayOfWeek.WEDNESDAY -> "Wednesday"
                DayOfWeek.THURSDAY -> "Thursday"
                DayOfWeek.FRIDAY -> "Friday"
                DayOfWeek.SATURDAY -> "Saturday"
                else -> "Sunday"
            }
        )
    }

    // Dynamic Edit Mode state
    var isEditMode by remember { mutableStateOf(false) }
    var daySchedule by remember(selectedDay) { mutableStateOf(timetableRepo.getDaySchedule(selectedDay)) }
    var showEditPeriodDialog by remember { mutableStateOf(false) }
    var periodToEdit by remember { mutableStateOf<PeriodItem?>(null) }

    fun refreshSchedule() {
        daySchedule = timetableRepo.getDaySchedule(selectedDay)
    }

    LaunchedEffect(Unit) {
        while (true) {
            currentTime = LocalTime.now(ZoneId.of("Asia/Kolkata"))
            val dow = LocalDate.now(ZoneId.of("Asia/Kolkata")).dayOfWeek
            currentSystemDay = when (dow) {
                DayOfWeek.MONDAY -> "Monday"
                DayOfWeek.TUESDAY -> "Tuesday"
                DayOfWeek.WEDNESDAY -> "Wednesday"
                DayOfWeek.THURSDAY -> "Thursday"
                DayOfWeek.FRIDAY -> "Friday"
                DayOfWeek.SATURDAY -> "Saturday"
                else -> "Sunday"
            }
            delay(10000)
        }
    }

    val currentActiveSlot = timetableRepo.getCurrentSlot(selectedDay, currentTime)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 44.dp, start = 16.dp, end = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // App Header with Edit Mode Action
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Class Timetable",
                    color = colorScheme.textPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
                val statusText = if (currentSystemDay == "Sunday") {
                    "Happy Sunday! No classes today"
                } else if (currentActiveSlot != null && selectedDay == currentSystemDay) {
                    if (currentActiveSlot.isBreak) {
                        "Current: ${currentActiveSlot.slotName}"
                    } else {
                        "Current: ${currentActiveSlot.slotName} (${if (currentActiveSlot.subjectCode.isNotEmpty()) currentActiveSlot.subjectCode else "Free"})"
                    }
                } else {
                    "No active class right now"
                }
                Text(
                    text = statusText,
                    color = colorScheme.accentSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            // Edit Mode Toggle Button
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isEditMode) Color(0xFF10B981) else colorScheme.surfaceVariant)
                    .border(1.dp, if (isEditMode) Color(0xFF10B981) else colorScheme.surfaceBorder, RoundedCornerShape(12.dp))
                    .clickable { isEditMode = !isEditMode }
                    .padding(horizontal = 14.dp, vertical = 7.dp)
            ) {
                Text(
                    text = if (isEditMode) "Done" else "Edit",
                    color = if (isEditMode) Color.White else colorScheme.accentPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Edit Mode Action Bar (+ Add Period)
        if (isEditMode) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Editing $selectedDay's schedule",
                    color = colorScheme.textSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )

                Button(
                    onClick = {
                        periodToEdit = null
                        showEditPeriodDialog = true
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colorScheme.accentPrimary),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text("+ Add Period", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        val dayListState = rememberLazyListState()

        LaunchedEffect(selectedDay) {
            val targetIdx = days.indexOf(selectedDay)
            if (targetIdx >= 0) {
                dayListState.animateScrollToItem(targetIdx)
            }
        }

        // Day Selector Pills (Auto-scrolled & fully visible)
        LazyRow(
            state = dayListState,
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 4.dp)
        ) {
            items(days.size) { index ->
                val day = days[index]
                val isSelected = day == selectedDay
                val isCurrentSystemDay = day == currentSystemDay

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(
                            if (isSelected) colorScheme.pillActive else colorScheme.pillInactive
                        )
                        .border(
                            width = 1.dp,
                            color = if (isCurrentSystemDay) colorScheme.accentSecondary else if (isSelected) colorScheme.pillActive else colorScheme.pillBorder,
                            shape = RoundedCornerShape(18.dp)
                        )
                        .clickable {
                            selectedDay = day
                            daySchedule = timetableRepo.getDaySchedule(day)
                        }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = day.substring(0, 3),
                        color = if (isSelected) Color.White else colorScheme.textSecondary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Timetable Period Rows (Editable & Reorderable)
        if (daySchedule.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "No periods scheduled for $selectedDay",
                        color = colorScheme.textMuted,
                        fontSize = 14.sp
                    )
                    if (isEditMode) {
                        Button(
                            onClick = {
                                periodToEdit = null
                                showEditPeriodDialog = true
                            },
                            modifier = Modifier.padding(top = 8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = colorScheme.accentPrimary)
                        ) {
                            Text("+ Add First Period", color = Color.White)
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(top = 10.dp, bottom = 24.dp)
            ) {
                itemsIndexed(daySchedule, key = { _, item -> item.id }) { index, item ->
                    val isCurrentSlot = currentSystemDay == selectedDay && item == currentActiveSlot

                    PeriodCard(
                        colorScheme = colorScheme,
                        item = item,
                        isActive = isCurrentSlot,
                        isEditMode = isEditMode,
                        canMoveUp = index > 0,
                        canMoveDown = index < daySchedule.size - 1,
                        onMoveUp = {
                            timetableRepo.reorderPeriods(selectedDay, index, index - 1)
                            refreshSchedule()
                        },
                        onMoveDown = {
                            timetableRepo.reorderPeriods(selectedDay, index, index + 1)
                            refreshSchedule()
                        },
                        onEditClick = {
                            periodToEdit = item
                            showEditPeriodDialog = true
                        },
                        onDeleteClick = {
                            timetableRepo.deletePeriod(selectedDay, item.id)
                            refreshSchedule()
                        },
                        onAddAssignmentClick = {
                            onAddAssignmentClick(item.subjectCode, item.subjectName)
                        }
                    )
                }
            }
        }
    }

    // Edit / Add Period Dialog
    if (showEditPeriodDialog) {
        EditPeriodDialog(
            colorScheme = colorScheme,
            existingItem = periodToEdit,
            onDismiss = { showEditPeriodDialog = false },
            onSave = { updatedItem ->
                if (periodToEdit != null) {
                    timetableRepo.updatePeriod(selectedDay, updatedItem)
                } else {
                    timetableRepo.addPeriod(selectedDay, updatedItem)
                }
                refreshSchedule()
                showEditPeriodDialog = false
            }
        )
    }
}

@Composable
fun PeriodCard(
    colorScheme: AppColorScheme,
    item: PeriodItem,
    isActive: Boolean,
    isEditMode: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onEditClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onAddAssignmentClick: () -> Unit
) {
    val cardBg = when {
        isActive -> colorScheme.activeCardBackground
        item.isBreak -> Color(0x18F59E0B)
        else -> colorScheme.surface
    }
    val cardBorder = when {
        isActive -> colorScheme.activeCardBorder
        item.isBreak -> Color(0x50F59E0B)
        else -> colorScheme.surfaceBorder
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(cardBg)
            .border(
                width = if (isActive) 1.5.dp else 1.dp,
                color = cardBorder,
                shape = RoundedCornerShape(16.dp)
            )
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Reorder Controls in Edit Mode
            if (isEditMode) {
                Column(
                    modifier = Modifier.padding(end = 8.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "▲",
                        color = if (canMoveUp) colorScheme.accentPrimary else colorScheme.textMuted.copy(alpha = 0.3f),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clickable(enabled = canMoveUp) { onMoveUp() }
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                    Text(
                        text = "▼",
                        color = if (canMoveDown) colorScheme.accentPrimary else colorScheme.textMuted.copy(alpha = 0.3f),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clickable(enabled = canMoveDown) { onMoveDown() }
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }

            // Time & Slot Name Column
            Column(modifier = Modifier.width(105.dp)) {
                val leftLabel = when {
                    item.isBreak -> "Break"
                    item.slotName.contains("Mentor", ignoreCase = true) || item.subjectCode.equals("MH", ignoreCase = true) -> "Morning"
                    else -> item.slotName
                }
                Text(
                    text = leftLabel,
                    color = if (item.isBreak) Color(0xFFF59E0B) else colorScheme.textMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = item.formattedTime,
                    color = if (isActive) colorScheme.accentPrimary else colorScheme.textPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            // Divider Line
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(36.dp)
                    .background(if (item.isBreak) Color(0x40F59E0B) else colorScheme.dividerColor)
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Subject Info Column
            Column(modifier = Modifier.weight(1f)) {
                if (item.isBreak) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = item.slotName,
                            color = colorScheme.textPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0x33F59E0B))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "BREAK",
                                color = Color(0xFFF59E0B),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                } else if (item.subjectCode.isNotEmpty() || item.subjectName.isNotEmpty()) {
                    val mainTitle = if (item.subjectCode.isNotEmpty()) item.subjectCode else item.subjectName
                    Text(
                        text = mainTitle,
                        color = colorScheme.textPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false
                    )
                    if (item.subjectName.isNotEmpty() &&
                        !item.subjectName.equals(mainTitle, ignoreCase = true) &&
                        !item.subjectName.equals(item.slotName, ignoreCase = true)
                    ) {
                        Text(
                            text = item.subjectName,
                            color = colorScheme.textSecondary,
                            fontSize = 12.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Clip
                        )
                    }
                } else {
                    Text(
                        text = "Free Period",
                        color = colorScheme.textSecondary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // Right Actions: Edit/Delete in Edit Mode, or Clean White '+' in Normal View
            if (isEditMode) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(colorScheme.surfaceVariant)
                            .clickable { onEditClick() }
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Text("Edit", color = colorScheme.accentPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0x28EF4444))
                            .clickable { onDeleteClick() }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_trash),
                            contentDescription = "Delete",
                            tint = Color(0xFFEF4444),
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
            } else {
                if (!item.isBreak) {
                    Box(
                        modifier = Modifier
                            .clickable { onAddAssignmentClick() }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "+",
                            color = Color.White,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Light
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun EditPeriodDialog(
    colorScheme: AppColorScheme,
    existingItem: PeriodItem?,
    onDismiss: () -> Unit,
    onSave: (PeriodItem) -> Unit
) {
    val context = LocalContext.current

    var slotName by remember { mutableStateOf(existingItem?.slotName ?: "Period") }
    var startTime by remember { mutableStateOf(existingItem?.startTime ?: LocalTime.of(8, 55)) }
    var endTime by remember { mutableStateOf(existingItem?.endTime ?: LocalTime.of(9, 50)) }
    var isBreak by remember { mutableStateOf(existingItem?.isBreak ?: false) }
    var subjectCode by remember { mutableStateOf(existingItem?.subjectCode ?: "") }
    var subjectName by remember { mutableStateOf(existingItem?.subjectName ?: "") }

    val timeFormatter = DateTimeFormatter.ofPattern("h:mm a")

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = {
                    val finalItem = PeriodItem(
                        id = existingItem?.id ?: java.util.UUID.randomUUID().toString(),
                        slotName = slotName.trim(),
                        startTime = startTime,
                        endTime = endTime,
                        isBreak = isBreak,
                        subjectCode = subjectCode.trim(),
                        subjectName = subjectName.trim(),
                        faculty = ""
                    )
                    onSave(finalItem)
                },
                colors = ButtonDefaults.buttonColors(containerColor = colorScheme.accentPrimary),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(if (existingItem != null) "Save Changes" else "Add Period", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = colorScheme.textSecondary)
            }
        },
        title = {
            Text(
                text = if (existingItem != null) "Edit Period" else "Add New Period",
                color = colorScheme.textPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Slot Name
                OutlinedTextField(
                    value = slotName,
                    onValueChange = { slotName = it },
                    label = { Text("Slot Name (e.g. Period 1, Mentor Hour)") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = colorScheme.textPrimary,
                        unfocusedTextColor = colorScheme.textPrimary,
                        focusedBorderColor = colorScheme.accentPrimary,
                        unfocusedBorderColor = colorScheme.surfaceBorder
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                // Time Pickers Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(colorScheme.surfaceVariant)
                            .clickable {
                                TimePickerDialog(
                                    context,
                                    { _, h, m -> startTime = LocalTime.of(h, m) },
                                    startTime.hour,
                                    startTime.minute,
                                    false
                                ).show()
                            }
                            .padding(10.dp)
                    ) {
                        Column {
                            Text("Start Time", color = colorScheme.textMuted, fontSize = 10.sp)
                            Text(startTime.format(timeFormatter), color = colorScheme.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(colorScheme.surfaceVariant)
                            .clickable {
                                TimePickerDialog(
                                    context,
                                    { _, h, m -> endTime = LocalTime.of(h, m) },
                                    endTime.hour,
                                    endTime.minute,
                                    false
                                ).show()
                            }
                            .padding(10.dp)
                    ) {
                        Column {
                            Text("End Time", color = colorScheme.textMuted, fontSize = 10.sp)
                            Text(endTime.format(timeFormatter), color = colorScheme.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // Break Checkbox
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { isBreak = !isBreak }
                ) {
                    Checkbox(
                        checked = isBreak,
                        onCheckedChange = { isBreak = it },
                        colors = CheckboxDefaults.colors(checkedColor = colorScheme.accentPrimary)
                    )
                    Text("This slot is a Break (e.g. Tea / Lunch)", color = colorScheme.textPrimary, fontSize = 12.sp)
                }

                if (!isBreak) {
                    // Subject Code
                    OutlinedTextField(
                        value = subjectCode,
                        onValueChange = { subjectCode = it },
                        label = { Text("Subject Code (e.g. U25CSG18)") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = colorScheme.textPrimary,
                            unfocusedTextColor = colorScheme.textPrimary,
                            focusedBorderColor = colorScheme.accentPrimary,
                            unfocusedBorderColor = colorScheme.surfaceBorder
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Subject Name
                    OutlinedTextField(
                        value = subjectName,
                        onValueChange = { subjectName = it },
                        label = { Text("Subject Name (e.g. DBMS Lab)") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = colorScheme.textPrimary,
                            unfocusedTextColor = colorScheme.textPrimary,
                            focusedBorderColor = colorScheme.accentPrimary,
                            unfocusedBorderColor = colorScheme.surfaceBorder
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        containerColor = colorScheme.surface,
        shape = RoundedCornerShape(18.dp)
    )
}

@Composable
fun AssignmentsScreen(
    colorScheme: AppColorScheme,
    assignments: List<Assignment>,
    onToggleComplete: (String) -> Unit,
    onDelete: (String) -> Unit,
    onAddClick: () -> Unit
) {
    var selectedFilter by remember { mutableStateOf("All") }
    val filters = listOf("All", "High", "Medium", "Low", "Pending", "Completed")

    val filteredAssignments = remember(assignments, selectedFilter) {
        when (selectedFilter) {
            "High" -> assignments.filter { it.priority == Priority.HIGH }
            "Medium" -> assignments.filter { it.priority == Priority.MEDIUM }
            "Low" -> assignments.filter { it.priority == Priority.LOW }
            "Pending" -> assignments.filter { !it.isCompleted }
            "Completed" -> assignments.filter { it.isCompleted }
            else -> assignments
        }
    }

    val totalCount = assignments.size
    val pendingCount = assignments.count { !it.isCompleted }
    val completedCount = assignments.count { it.isCompleted }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 44.dp, start = 16.dp, end = 16.dp)
    ) {
        // Screen Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "My Assignments",
                    color = colorScheme.textPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Track deadlines and priorities",
                    color = colorScheme.textSecondary,
                    fontSize = 13.sp
                )
            }

            // Quick Add Button
            Button(
                onClick = onAddClick,
                colors = ButtonDefaults.buttonColors(containerColor = colorScheme.accentPrimary),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text("+ New", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Metrics Summary Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(colorScheme.surface)
                .border(1.dp, colorScheme.surfaceBorder, RoundedCornerShape(16.dp))
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            MetricPill("Total", totalCount.toString(), colorScheme.textPrimary)
            MetricPill("Pending", pendingCount.toString(), Color(0xFFF59E0B))
            MetricPill("Completed", completedCount.toString(), Color(0xFF10B981))
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Filter Pills
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(filters.size) { index ->
                val filter = filters[index]
                val isSelected = filter == selectedFilter

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (isSelected) colorScheme.pillActive else colorScheme.pillInactive)
                        .border(
                            1.dp,
                            if (isSelected) colorScheme.pillActive else colorScheme.pillBorder,
                            RoundedCornerShape(16.dp)
                        )
                        .clickable { selectedFilter = filter }
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = filter,
                        color = if (isSelected) Color.White else colorScheme.textSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Assignments List
        if (filteredAssignments.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "No assignments found",
                        color = colorScheme.textMuted,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Tap '+ New' or the '+' on a timetable slot to add one.",
                        color = colorScheme.textSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(filteredAssignments, key = { it.id }) { assignment ->
                    AssignmentCard(
                        colorScheme = colorScheme,
                        assignment = assignment,
                        onToggleComplete = { onToggleComplete(assignment.id) },
                        onDelete = { onDelete(assignment.id) }
                    )
                }
            }
        }
    }
}

@Composable
fun MetricPill(label: String, count: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = count, color = color, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(text = label, color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun AssignmentCard(
    colorScheme: AppColorScheme,
    assignment: Assignment,
    onToggleComplete: () -> Unit,
    onDelete: () -> Unit
) {
    val dueDateTime = LocalDateTime.ofInstant(
        Instant.ofEpochMilli(assignment.dueDateEpochMillis),
        ZoneId.of("Asia/Kolkata")
    )
    val formatter = DateTimeFormatter.ofPattern("MMM dd, h:mm a")
    val formattedDue = dueDateTime.format(formatter)

    val isOverdue = !assignment.isCompleted && assignment.dueDateEpochMillis < System.currentTimeMillis()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colorScheme.surface)
            .border(1.dp, colorScheme.surfaceBorder, RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            // Checkbox
            Checkbox(
                checked = assignment.isCompleted,
                onCheckedChange = { onToggleComplete() },
                colors = CheckboxDefaults.colors(
                    checkedColor = Color(0xFF10B981),
                    uncheckedColor = colorScheme.textMuted
                ),
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Main Info
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (assignment.subjectCode.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(colorScheme.surfaceVariant)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = assignment.subjectCode,
                                color = colorScheme.accentPrimary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Priority Badge
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(assignment.priority.badgeColor))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = assignment.priority.label,
                            color = Color(assignment.priority.colorHex),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = assignment.title,
                    color = if (assignment.isCompleted) colorScheme.textMuted else colorScheme.textPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    textDecoration = if (assignment.isCompleted) TextDecoration.LineThrough else TextDecoration.None
                )

                if (assignment.description.isNotEmpty()) {
                    Text(
                        text = assignment.description,
                        color = colorScheme.textSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Due date chip
                Text(
                    text = if (isOverdue) "Overdue: $formattedDue" else "Due: $formattedDue",
                    color = if (isOverdue) Color(0xFFEF4444) else colorScheme.textMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            // Delete Action
            Text(
                text = "X",
                color = colorScheme.textMuted,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable { onDelete() }
                    .padding(6.dp)
            )
        }
    }
}

@Composable
fun SettingsScreen(
    colorScheme: AppColorScheme,
    currentThemeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
    notificationsEnabled: Boolean,
    onNotificationToggle: (Boolean) -> Unit,
    onAddWidgetClick: () -> Unit,
    onCheckUpdateNow: () -> Unit
) {
    var showChangelogDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 44.dp, start = 16.dp, end = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "Settings",
            color = colorScheme.textPrimary,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Customization & App Details",
            color = colorScheme.textSecondary,
            fontSize = 13.sp
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Theme Customization Card
        Text(
            text = "APPEARANCE & THEME",
            color = colorScheme.accentPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(colorScheme.surface)
                .border(1.dp, colorScheme.surfaceBorder, RoundedCornerShape(18.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ThemeMode.values().forEach { mode ->
                val isSelected = currentThemeMode == mode

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isSelected) colorScheme.surfaceVariant else Color.Transparent)
                        .border(
                            1.dp,
                            if (isSelected) colorScheme.accentPrimary else Color.Transparent,
                            RoundedCornerShape(12.dp)
                        )
                        .clickable { onThemeChange(mode) }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = mode.displayName,
                            color = colorScheme.textPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = mode.subtitle,
                            color = colorScheme.textSecondary,
                            fontSize = 11.sp
                        )
                    }

                    RadioButton(
                        selected = isSelected,
                        onClick = { onThemeChange(mode) },
                        colors = RadioButtonDefaults.colors(selectedColor = colorScheme.accentPrimary)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Notifications Settings Card
        Text(
            text = "NOTIFICATIONS",
            color = colorScheme.accentPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(colorScheme.surface)
                .border(1.dp, colorScheme.surfaceBorder, RoundedCornerShape(18.dp))
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Assignment Reminders",
                        color = colorScheme.textPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Receive alerts before and at due dates",
                        color = colorScheme.textSecondary,
                        fontSize = 11.sp
                    )
                }

                Switch(
                    checked = notificationsEnabled,
                    onCheckedChange = { onNotificationToggle(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = colorScheme.accentPrimary,
                        uncheckedThumbColor = colorScheme.textMuted,
                        uncheckedTrackColor = colorScheme.surfaceVariant
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // App Updates & Version Card
        Text(
            text = "IN-APP UPDATES (OTA)",
            color = colorScheme.accentPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(colorScheme.surface)
                .border(1.dp, colorScheme.surfaceBorder, RoundedCornerShape(18.dp))
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Current Version: ${UpdateManager.CURRENT_VERSION_NAME}",
                        color = colorScheme.textPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Build ${UpdateManager.CURRENT_VERSION_CODE} - GitHub Releases",
                        color = Color(0xFF10B981),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Button(
                    onClick = onCheckUpdateNow,
                    colors = ButtonDefaults.buttonColors(containerColor = colorScheme.accentPrimary),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Check", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "View What's New / Changelog",
                color = colorScheme.accentPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clickable { showChangelogDialog = true }
                    .padding(vertical = 4.dp)
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Home Screen Widget Shortcut
        Text(
            text = "HOME SCREEN WIDGET",
            color = colorScheme.accentPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = onAddWidgetClick,
            colors = ButtonDefaults.buttonColors(containerColor = colorScheme.surfaceVariant),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, colorScheme.surfaceBorder, RoundedCornerShape(14.dp))
        ) {
            Text(
                text = "Pin Timetable Widget to Home Screen",
                color = colorScheme.textPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(modifier = Modifier.height(20.dp))
    }

    // Changelog Dialog
    if (showChangelogDialog) {
        AlertDialog(
            onDismissRequest = { showChangelogDialog = false },
            confirmButton = {
                TextButton(onClick = { showChangelogDialog = false }) {
                    Text("Got it", color = colorScheme.accentPrimary, fontWeight = FontWeight.Bold)
                }
            },
            title = {
                Text("What's New in ${UpdateManager.CURRENT_VERSION_NAME}", color = colorScheme.textPrimary, fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    UpdateManager.FALLBACK_CHANGELOG.forEach { item ->
                        Text(text = item, color = colorScheme.textSecondary, fontSize = 13.sp)
                    }
                }
            },
            containerColor = colorScheme.surface,
            shape = RoundedCornerShape(18.dp)
        )
    }
}

@Composable
fun AddAssignmentDialog(
    colorScheme: AppColorScheme,
    initialSubjectCode: String?,
    initialSubjectName: String?,
    onDismiss: () -> Unit,
    onSave: (Assignment) -> Unit
) {
    val context = LocalContext.current

    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf(Priority.MEDIUM) }
    var selectedSubjectCode by remember { mutableStateOf(initialSubjectCode ?: "U25ADG01") }
    var selectedSubjectName by remember { mutableStateOf(initialSubjectName ?: "Digital Principles and Computer Org") }

    // Date & Time states with custom month & period support
    var selectedDate by remember { mutableStateOf(LocalDate.now(ZoneId.of("Asia/Kolkata")).plusDays(1)) }
    var selectedTime by remember { mutableStateOf(LocalTime.of(17, 0)) }
    var selectedPeriodName by remember { mutableStateOf<String?>(null) }

    val dateFormatter = DateTimeFormatter.ofPattern("EEE, MMM dd, yyyy")
    val timeFormatter = DateTimeFormatter.ofPattern("h:mm a")

    val uniqueSubjects = remember {
        val list = mutableListOf<Pair<String, String>>()
        listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday").forEach { day ->
            TimetableData.timeSlots.forEach { slot ->
                val sub = TimetableData.getSubjectForSlot(day, slot)
                if (sub != null && !sub.code.startsWith("MH")) {
                    if (list.none { it.first == sub.code }) {
                        list.add(sub.code to sub.name)
                    }
                }
            }
        }
        list
    }

    val periodSlots = remember {
        TimetableData.timeSlots.filter { !it.isBreak }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = {
                    val finalTitle = if (title.isBlank()) "Assignment for $selectedSubjectCode" else title.trim()
                    val dueInstant = LocalDateTime.of(selectedDate, selectedTime)
                        .atZone(ZoneId.of("Asia/Kolkata"))
                        .toInstant()
                        .toEpochMilli()

                    val assignment = Assignment(
                        subjectCode = selectedSubjectCode,
                        subjectName = selectedSubjectName,
                        title = finalTitle,
                        description = description.trim(),
                        dueDateEpochMillis = dueInstant,
                        priority = priority
                    )
                    onSave(assignment)
                },
                colors = ButtonDefaults.buttonColors(containerColor = colorScheme.accentPrimary),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Save Assignment", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = colorScheme.textSecondary)
            }
        },
        title = {
            Text("Add Assignment", color = colorScheme.textPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // If added directly from subject card (+), show fixed subject info banner (no picker list)
                if (initialSubjectCode != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(colorScheme.surfaceVariant)
                            .border(1.dp, colorScheme.surfaceBorder, RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = "Subject",
                            color = colorScheme.textMuted,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = selectedSubjectCode,
                                color = colorScheme.accentPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = selectedSubjectName,
                                color = colorScheme.textPrimary,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                } else {
                    // Subject Picker Chips (Shown only when + New button is clicked)
                    Column {
                        Text("Select Subject:", color = colorScheme.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        Spacer(modifier = Modifier.height(6.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(uniqueSubjects) { item ->
                                val isSelected = selectedSubjectCode == item.first
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (isSelected) colorScheme.accentPrimary else colorScheme.surfaceVariant)
                                        .clickable {
                                            selectedSubjectCode = item.first
                                            selectedSubjectName = item.second
                                        }
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = item.first,
                                        color = if (isSelected) Color.White else colorScheme.textSecondary,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }

                // Title Input
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Assignment Title (e.g. Lab Record)") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = colorScheme.textPrimary,
                        unfocusedTextColor = colorScheme.textPrimary,
                        focusedBorderColor = colorScheme.accentPrimary,
                        unfocusedBorderColor = colorScheme.surfaceBorder
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                // Notes Input
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description / Notes (Optional)") },
                    maxLines = 2,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = colorScheme.textPrimary,
                        unfocusedTextColor = colorScheme.textPrimary,
                        focusedBorderColor = colorScheme.accentPrimary,
                        unfocusedBorderColor = colorScheme.surfaceBorder
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                // Priority Selection
                Column {
                    Text("Priority Level:", color = colorScheme.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Priority.values().forEach { p ->
                            val isSelected = priority == p
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isSelected) Color(p.colorHex) else colorScheme.surfaceVariant)
                                    .clickable { priority = p }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = p.label,
                                    color = if (isSelected) Color.White else Color(p.colorHex),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                // Due Date & Month Picker
                Column {
                    Text("Due Date & Month:", color = colorScheme.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Spacer(modifier = Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(colorScheme.surfaceVariant)
                            .border(1.dp, colorScheme.surfaceBorder, RoundedCornerShape(12.dp))
                            .clickable {
                                DatePickerDialog(
                                    context,
                                    { _, year, month, dayOfMonth ->
                                        selectedDate = LocalDate.of(year, month + 1, dayOfMonth)
                                    },
                                    selectedDate.year,
                                    selectedDate.monthValue - 1,
                                    selectedDate.dayOfMonth
                                ).show()
                            }
                            .padding(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = selectedDate.format(dateFormatter),
                                    color = colorScheme.textPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Tap to pick any month / day",
                                    color = colorScheme.textMuted,
                                    fontSize = 11.sp
                                )
                            }
                            Text(
                                text = "Select Date",
                                color = colorScheme.accentPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Due Time & Specific Period Picker
                Column {
                    Text("Due Time / Specific Period:", color = colorScheme.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Spacer(modifier = Modifier.height(6.dp))

                    // Period Selection Chips
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(bottom = 8.dp)
                    ) {
                        items(periodSlots) { slot ->
                            val isSelected = selectedPeriodName == slot.name && selectedTime == slot.startTime
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isSelected) colorScheme.accentPrimary else colorScheme.surfaceVariant)
                                    .clickable {
                                        selectedTime = slot.startTime
                                        selectedPeriodName = slot.name
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = "${slot.name} (${slot.startTime.format(timeFormatter)})",
                                    color = if (isSelected) Color.White else colorScheme.textSecondary,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }

                    // Custom Time Button
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(colorScheme.surfaceVariant)
                            .border(1.dp, colorScheme.surfaceBorder, RoundedCornerShape(12.dp))
                            .clickable {
                                TimePickerDialog(
                                    context,
                                    { _, hourOfDay, minute ->
                                        selectedTime = LocalTime.of(hourOfDay, minute)
                                        selectedPeriodName = null
                                    },
                                    selectedTime.hour,
                                    selectedTime.minute,
                                    false
                                ).show()
                            }
                            .padding(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                val periodInfo = if (selectedPeriodName != null) " • $selectedPeriodName" else ""
                                Text(
                                    text = "${selectedTime.format(timeFormatter)}$periodInfo",
                                    color = colorScheme.textPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Tap to pick exact custom time",
                                    color = colorScheme.textMuted,
                                    fontSize = 11.sp
                                )
                            }
                            Text(
                                text = "Change Time",
                                color = colorScheme.accentPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        },
        containerColor = colorScheme.surface,
        shape = RoundedCornerShape(18.dp)
    )
}
