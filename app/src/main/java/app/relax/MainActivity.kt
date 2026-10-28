@file:OptIn(ExperimentalMaterial3Api::class)

package app.relax

import android.Manifest
import android.app.Application
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
// LaunchedEffect in runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.core.content.FileProvider
import java.io.File
private val BG = Color(0xFF0B1220)
private val CARD = Color(0xFF151E2E)
private val CARD2 = Color(0xFF1B2638)
private val BORDER = Color(0xFF2A364A)
private val MUTED = Color(0xFF8B9BB4)
private val TEXT = Color(0xFFF1F5F9)
private val ACCENT = Color(0xFF38BDF8)
private val AMBER = Color(0xFFF59E0B)
private val GREEN = Color(0xFF22C55E)
private val TIERC = mapOf(1 to AMBER, 2 to ACCENT, 3 to MUTED)
private val TIERN = mapOf(1 to "P1", 2 to "P2", 3 to "P3")
private val ShapeMd = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
private val ShapeSm = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)

private val POSTED = listOf(
    "Within 1 hour" to 1L, "Within 6 hours" to 6L, "Within 12 hours" to 12L, "Within a day" to 24L,
    "Within 2 days" to 48L, "Within 3 days" to 72L, "Within 4 days" to 96L, "Within 5 days" to 120L,
    "Within 6 days" to 144L, "Within 7 days" to 168L, "Within 10 days" to 240L,
    "Within 20 days" to 480L, "Within 1 month" to 720L,
)
private val PORTALS = listOf("All portals" to "all", "LinkedIn" to "linkedin", "Naukri" to "naukri", "Internshala" to "internshala")
private val SORTS = listOf("Best match" to false, "Newest first" to true)

fun age(j: Posting): String {
    val h = (System.currentTimeMillis() - j.postedAt) / H.toDouble()
    val s = when {
        h < 1 -> "${maxOf(1, (h * 60).toInt())}m"
        h < 24 -> "${h.toInt()}h"
        else -> "${(h / 24).toInt()}d"
    }
    return (if (j.approx) "~" else "") + s + " ago"
}

class VM(app: Application) : AndroidViewModel(app) {
    val st = Store(app)
    var profiles by mutableStateOf(st.allProfiles())
    var defaultId by mutableStateOf(st.defaultId)
    var active by mutableStateOf(profiles.firstOrNull { it.id == st.defaultId } ?: BUILTIN[0])
    var jobs by mutableStateOf<List<Posting>>(emptyList())
    var saved by mutableStateOf(st.saved())
    var applied by mutableStateOf(st.applied())
    var resumeDoc by mutableStateOf<Resume.ProfileDoc?>(null)
    var pendingFit by mutableStateOf<Posting?>(null)
    var loading by mutableStateOf(false)
    var status by mutableStateOf<Map<String, String>>(emptyMap())

    var q by mutableStateOf("")
    var fPosted by mutableStateOf(168L)
    var fPortal by mutableStateOf("all")
    var fLoc by mutableStateOf("")
    var fHideWeak by mutableStateOf(true)
    var fNewest by mutableStateOf(false)

    val prevOpen = st.lastOpen
    private var gen = 0
    private var task: kotlinx.coroutines.Job? = null

    init {
        st.lastOpen = System.currentTimeMillis()
        resumeDoc = Resume.load(app)
        open(active)
    }

    fun open(p: Profile) {
        active = p
        val appliedKeys = st.appliedKeys()
        jobs = if (p.id == "adhoc") emptyList()
        else st.cache(p.id).filter { Engine.key(it) !in appliedKeys }
        status = emptyMap()
        val stale = System.currentTimeMillis() - st.stamp(p.id) > H
        if (jobs.isEmpty() || stale) refresh()
    }

    fun reload() {
        if (active.id != "adhoc") jobs = st.cache(active.id)
        saved = st.saved()
        applied = st.applied()
    }

    fun refresh() {
        val p = active
        val my = ++gen
        task?.cancel()
        loading = true
        task = viewModelScope.launch {
            try {
                val r = Engine.ingest(p)
                if (my != gen) return@launch
                val adhoc = p.id == "adhoc"
                val merged = if (adhoc) r.jobs else Engine.merge(st.cache(p.id), r.jobs)
                if (!adhoc) st.putCache(p.id, merged)
                st.addSeen(r.jobs.map { Engine.key(it) })
                val appliedKeys = st.appliedKeys()
                jobs = merged.filter { Engine.key(it) !in appliedKeys }
                status = r.status
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = mapOf("Error" to (e.message ?: e.javaClass.simpleName))
            } finally {
                if (my == gen) loading = false
            }
        }
    }

    fun search(text: String) {
        val qs = text.split(",", "\n").map { it.trim() }.filter { it.length >= 2 }.take(6)
        if (qs.isEmpty()) return
        active = Profile("adhoc", "Search: " + qs.joinToString(", "), qs)
        jobs = emptyList(); status = emptyMap()
        refresh()
    }

    fun saveActive(makeDefault: Boolean) {
        val a = active
        if (a.id != "adhoc") return
        val p = Profile("c" + System.currentTimeMillis(), a.queries.joinToString(", ").take(40), a.queries)
        st.putProfiles(st.customProfiles() + p)
        st.putCache(p.id, jobs)
        profiles = st.allProfiles()
        active = p
        if (makeDefault) setDefault(p)
    }

    fun setDefault(p: Profile) { st.defaultId = p.id; defaultId = p.id }

    fun delete(p: Profile) {
        if (p.builtIn) return
        st.deleteProfile(p.id)
        profiles = st.allProfiles()
        if (defaultId == p.id) { st.defaultId = "ops"; defaultId = "ops" }
        open(profiles.firstOrNull { it.id == defaultId } ?: BUILTIN[0])
    }

    fun toggleSave(j: Posting) {
        val k = Engine.key(j)
        val l = if (saved.any { Engine.key(it) == k }) saved.filter { Engine.key(it) != k } else listOf(j) + saved
        saved = l; st.putSaved(l)
    }

    fun toggleApplied(j: Posting) {
        saved = saved.map { if (it.id == j.id) it.copy(applied = !it.applied) else it }
        st.putSaved(saved)
    }
}


    fun markApplied(j: Posting) {
        val k = Engine.key(j)
        val cur = st.applied().toMutableList()
        if (cur.none { Engine.key(it) == k }) {
            cur.add(0, j.copy(applied = true))
            st.putApplied(cur)
            applied = cur
        }
        // remove from saved if present
        val s = saved.filter { Engine.key(it) != k }
        if (s.size != saved.size) { saved = s; st.putSaved(s) }
        // hide from current jobs list
        jobs = jobs.filter { Engine.key(it) != k }
        // strip from profile cache so it won't reappear
        if (active.id != "adhoc") {
            st.putCache(active.id, st.cache(active.id).filter { Engine.key(it) != k })
        }
    }

    fun unmarkApplied(j: Posting) {
        val k = Engine.key(j)
        val cur = applied.filter { Engine.key(it) != k }
        st.putApplied(cur)
        applied = cur
    }

    fun setDefault(id: String) {
        defaultId = id
        st.defaultId = id
        st.alertHours = fPosted
        profiles = st.allProfiles()
    }

    fun savePreference(name: String, makeDefault: Boolean) {
        val qs = if (active.id == "adhoc") active.queries else active.queries
        if (qs.isEmpty()) return
        val id = "pref_" + System.currentTimeMillis().toString(36)
        val p = Profile(id, name.ifBlank { qs.first().take(32) }, qs, ops = false)
        val customs = st.customProfiles() + p
        st.putProfiles(customs)
        profiles = st.allProfiles()
        if (makeDefault) setDefault(id)
        open(p)
    }

    fun deletePreference(id: String) {
        if (id in BUILTIN.map { it.id }) return
        st.deleteProfile(id)
        profiles = st.allProfiles()
        if (defaultId == id) setDefault("ops")
        if (active.id == id) open(profiles.firstOrNull { it.id == defaultId } ?: BUILTIN[0])
    }


    fun setResume(doc: Resume.ProfileDoc?) {
        resumeDoc = doc
        if (doc != null) Resume.save(getApplication(), doc) else Resume.clear(getApplication())
    }

class MainActivity : ComponentActivity() {
    private val vm: VM by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Alerts.channel(this)
        Alerts.schedule(this)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = ACCENT,
                    onPrimary = BG,
                    secondary = AMBER,
                    background = BG,
                    surface = CARD,
                    onBackground = TEXT,
                    onSurface = TEXT,
                    outline = BORDER,
                ),
            ) { Surface(color = BG, modifier = Modifier.fillMaxSize()) { App(vm) } }
        }
    }

    override fun onStart() {
        super.onStart()
        vm.reload()   // pick up jobs found by background checks
    }
}

@Composable
fun App(vm: VM) {
    var tab by remember { mutableStateOf(0) }
    Scaffold(
        containerColor = BG,
        bottomBar = {
            NavigationBar(
                containerColor = CARD,
                tonalElevation = 0.dp,
            ) {
                val items = listOf(
                    Triple(0, "Jobs", "⌕"),
                    Triple(1, "Saved (${vm.saved.size})", "★"),
                    Triple(2, "Applied (${vm.applied.size})", "✓"),
                    Triple(3, "Resume", "⌂"),
                    Triple(4, "Alerts", "◷"),
                )
                items.forEach { (i, name, icon) ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = {
                            Text(icon, fontSize = 18.sp, color = if (tab == i) ACCENT else MUTED)
                        },
                        label = {
                            Text(
                                name,
                                fontSize = 10.sp,
                                maxLines = 1,
                                fontWeight = if (tab == i) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (tab == i) TEXT else MUTED,
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = ACCENT.copy(alpha = 0.15f),
                        ),
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> JobsTab(vm)
                1 -> SavedTab(vm)
                2 -> AppliedTab(vm)
                3 -> ResumeTab(vm)
                else -> AlertsTab(vm)
            }
        }
    }
}

@Composable
fun <T> Picker(label: String, options: List<Pair<String, T>>, selected: T, onPick: (T) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(
            onClick = { open = true },
            modifier = Modifier.fillMaxWidth(),
            shape = ShapeSm,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = TEXT),
            border = ButtonDefaults.outlinedButtonBorder.copy(
                width = 1.dp,
                brush = androidx.compose.ui.graphics.SolidColor(BORDER),
            ),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(
                options.firstOrNull { it.second == selected }?.first ?: label,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
                fontSize = 13.sp,
            )
            Text(" ▾", color = MUTED, fontSize = 12.sp)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (name, v) ->
                DropdownMenuItem(
                    text = {
                        Text(
                            name,
                            color = if (v == selected) ACCENT else TEXT,
                            fontWeight = if (v == selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                    onClick = { onPick(v); open = false },
                )
            }
        }
    }
}

@Composable
fun JobCard(
    j: Posting, saved: Boolean, isNew: Boolean, saveText: String, onSave: () -> Unit,
    onOpen: () -> Unit = {},
    onTailor: (() -> Unit)? = null,
    extra: (@Composable RowScope.() -> Unit)? = null,
) {
    val uri = LocalUriHandler.current

    val uri = LocalUriHandler.current
    Card(
        colors = CardDefaults.cardColors(containerColor = CARD),
        shape = ShapeMd,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .border(1.dp, BORDER.copy(alpha = 0.55f), ShapeMd),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                        .background((TIERC[j.tier] ?: MUTED).copy(alpha = 0.18f))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(
                        TIERN[j.tier] ?: "P3",
                        color = TIERC[j.tier] ?: MUTED,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    j.title,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = TEXT,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (isNew) {
                    Spacer(Modifier.width(6.dp))
                    Text("NEW", color = GREEN, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "${j.company}  ·  ${j.location.ifEmpty { "—" }}",
                color = MUTED,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                listOf(j.portal.replaceFirstChar { it.uppercase() }, age(j), j.exp, j.salary)
                    .filter { it.isNotBlank() }.joinToString("  ·  "),
                color = MUTED.copy(alpha = 0.85f),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = {
                        onOpen()
                        runCatching { uri.openUri(j.url) }
                    },
                    shape = ShapeSm,
                    colors = ButtonDefaults.buttonColors(containerColor = ACCENT, contentColor = BG),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                ) { Text("Open", fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                OutlinedButton(
                    onClick = onSave,
                    shape = ShapeSm,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = if (saved) AMBER else TEXT),
                    border = ButtonDefaults.outlinedButtonBorder.copy(
                        width = 1.dp,
                        brush = androidx.compose.ui.graphics.SolidColor(if (saved) AMBER else BORDER),
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                ) { Text(if (saved) saveText else "☆ Save", fontSize = 13.sp) }
                if (onTailor != null) {
                    OutlinedButton(
                        onClick = onTailor,
                        shape = ShapeSm,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = ACCENT),
                        border = ButtonDefaults.outlinedButtonBorder.copy(
                            width = 1.dp,
                            brush = androidx.compose.ui.graphics.SolidColor(ACCENT),
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    ) { Text("Fit", fontSize = 13.sp) }
                }
                extra?.invoke(this)
            }
        }
    }
}

@Composable
fun JobsTab(vm: VM) {
    val ctx = LocalContext.current
    val shown = remember(vm.jobs, vm.fPosted, vm.fPortal, vm.fLoc, vm.fHideWeak, vm.fNewest) {
        Engine.filter(vm.jobs, vm.fPosted, vm.fPortal, vm.fLoc, vm.fHideWeak, vm.fNewest)
    }
    val savedKeys = vm.saved.map { Engine.key(it) }.toSet()
    val adhoc = vm.active.id == "adhoc"

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Picker(
                    label = vm.active.name,
                    options = vm.profiles.map { ((if (it.id == vm.defaultId) "★ " else "") + it.name) to it.id },
                    selected = vm.active.id,
                    onPick = { id -> vm.profiles.firstOrNull { it.id == id }?.let { vm.open(it) } },
                    modifier = Modifier.weight(1f),
                )
                FilledIconButton(
                    onClick = { vm.refresh() },
                    enabled = !vm.loading,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = ACCENT,
                        contentColor = BG,
                        disabledContainerColor = ACCENT.copy(alpha = 0.4f),
                    ),
                    modifier = Modifier.size(44.dp),
                ) { Text(if (vm.loading) "…" else "↻", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                OutlinedTextField(
                    value = vm.q, onValueChange = { vm.q = it }, modifier = Modifier.weight(1f), singleLine = true,
                    placeholder = { Text("Search other jobs, e.g. data analyst, python") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { vm.search(vm.q) }),
                )
                Button(onClick = { vm.search(vm.q) }) { Text("Search") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (adhoc) {
                    TextButton(onClick = { vm.saveActive(false) }, enabled = vm.jobs.isNotEmpty()) { Text("＋ Save profile") }
                    TextButton(onClick = { vm.saveActive(true) }, enabled = vm.jobs.isNotEmpty()) { Text("★ Save & default") }
                } else {
                    if (vm.active.id != vm.defaultId) TextButton(onClick = { vm.setDefault(vm.active) }) { Text("★ Make default") }
                    else Text("★ Default · used for alerts", color = MUTED, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp))
                    if (!vm.active.builtIn) TextButton(onClick = { vm.delete(vm.active) }) { Text("Delete") }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { shareCsv(ctx, shown) }, enabled = shown.isNotEmpty()) { Text("Share CSV") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Picker(label = "Posted", options = POSTED, selected = vm.fPosted, onPick = { vm.fPosted = it; vm.st.alertHours = it }, modifier = Modifier.weight(1f))
                Picker(label = "Portal", options = PORTALS, selected = vm.fPortal, onPick = { vm.fPortal = it }, modifier = Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                OutlinedTextField(
                    value = vm.fLoc, onValueChange = { vm.fLoc = it }, modifier = Modifier.weight(1f), singleLine = true,
                    placeholder = { Text("Location, e.g. Mumbai") },
                )
                Picker(label = "Sort", options = SORTS, selected = vm.fNewest, onPick = { vm.fNewest = it }, modifier = Modifier.weight(1f))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = vm.fHideWeak, onCheckedChange = { vm.fHideWeak = it })
                Text("  Hide weak matches", fontSize = 13.sp)
            }
            if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
            Text("Showing ${shown.size} of ${vm.jobs.size}", color = MUTED, fontSize = 12.sp)
            if (vm.status.isNotEmpty()) {
                Text(vm.status.entries.joinToString("  ·  ") { "${it.key}: ${it.value}" }, color = MUTED, fontSize = 11.sp)
            }
            Spacer(Modifier.height(4.dp))
        }
        items(shown) { j ->
            JobCard(
                j = j, saved = Engine.key(j) in savedKeys,
                isNew = !adhoc && vm.prevOpen > 0 && j.firstSeen > vm.prevOpen,
                saveText = "★ Saved", onSave = { vm.toggleSave(j) },
                onOpen = { vm.markApplied(j) },
                onTailor = if (vm.resumeDoc != null) ({ vm.pendingFit = j }) else null,
            )
        }
        if (shown.isEmpty() && !vm.loading) {
            item {
                Text(
                    "No jobs match. Widen Posted, switch off 'Hide weak matches', clear the location, or tap ↻.",
                    color = MUTED, modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

@Composable
fun SavedTab(vm: VM) {
    val ctx = LocalContext.current
    var hideApplied by remember { mutableStateOf(false) }
    val list = vm.saved.filter { !(hideApplied && it.applied) }.sortedBy { it.applied }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${vm.saved.size} saved · ${vm.saved.count { it.applied }} applied", color = MUTED)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { shareCsv(ctx, vm.saved) }, enabled = vm.saved.isNotEmpty()) { Text("Share CSV") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = hideApplied, onCheckedChange = { hideApplied = it })
                Text("  Hide applied", fontSize = 13.sp)
            }
            if (list.isEmpty()) {
                Text("Nothing here yet. Tap ☆ Save on any job to apply later.", color = MUTED, modifier = Modifier.padding(16.dp))
            }
        }
        items(list) { j ->
            JobCard(j = j, saved = true, isNew = false, saveText = "✕ Remove", onSave = { vm.toggleSave(j) }, onOpen = { vm.markApplied(j) }) {
                OutlinedButton(onClick = { vm.toggleApplied(j) }) { Text(if (j.applied) "✓ Applied" else "Mark applied") }
            }
        }
    }
}

private fun clock(mins: Int): String =
    "%d:%02d %s".format(((mins / 60 + 11) % 12) + 1, mins % 60, if (mins / 60 < 12) "AM" else "PM")


@Composable
fun AppliedTab(vm: VM) {
    val list = vm.applied
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    ) {
        item {
            Text(
                "${list.size} applied — hidden from search & alerts",
                color = MUTED,
                fontSize = 13.sp,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            if (list.isEmpty()) {
                Text(
                    "Tap Open on any job to mark it applied. Those jobs stay here and never show in search again.",
                    color = MUTED,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        }
        items(list, key = { it.id }) { j ->
            JobCard(
                j = j,
                saved = false,
                isNew = false,
                saveText = "☆ Save",
                onSave = { vm.toggleSave(j) },
                onOpen = { /* already applied */ },
            ) {
                OutlinedButton(
                    onClick = { vm.unmarkApplied(j) },
                    shape = ShapeSm,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TEXT),
                    border = ButtonDefaults.outlinedButtonBorder.copy(
                        width = 1.dp,
                        brush = androidx.compose.ui.graphics.SolidColor(BORDER),
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                ) { Text("Undo", fontSize = 12.sp) }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}


@Composable
fun ResumeTab(vm: VM) {
    val ctx = LocalContext.current
    val doc = vm.resumeDoc
    var status by remember { mutableStateOf("") }
    var fitJob by remember { mutableStateOf<Posting?>(null) }
    var fitResult by remember { mutableStateOf<Resume.FitResult?>(null) }

    // consume pending fit from Jobs tab
    LaunchedEffect(vm.pendingFit) {
        val j = vm.pendingFit ?: return@LaunchedEffect
        val d = vm.resumeDoc
        if (d != null) {
            fitJob = j
            fitResult = Resume.fit(d, j)
        } else {
            status = "Upload your resume first, then tap Fit on a job."
        }
        vm.pendingFit = null
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        status = "Reading…"
        try {
            val text = Resume.extractText(ctx, uri)
            if (text.length < 40) {
                status = "Could not read text from that file. Use PDF/DOCX with selectable text, or paste plain text."
                return@rememberLauncherForActivityResult
            }
            val parsed = Resume.parse(text)
            vm.setResume(parsed)
            status = "Loaded · ${parsed.skills.size} skills · ${parsed.bullets.size} bullets"
        } catch (e: Exception) {
            status = "Failed: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
    ) {
        Text("Resume", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = TEXT)
        Spacer(Modifier.height(4.dp))
        Text(
            "Upload once. For any job, tap Fit — we score the match and rewrite your existing bullets with JD keywords. Nothing beyond your real work is added.",
            color = MUTED,
            fontSize = 13.sp,
            lineHeight = 18.sp,
        )
        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { picker.launch("application/*") },
                shape = ShapeSm,
                colors = ButtonDefaults.buttonColors(containerColor = ACCENT, contentColor = BG),
            ) { Text(if (doc == null) "Upload PDF / DOCX" else "Replace resume", fontWeight = FontWeight.SemiBold) }
            if (doc != null) {
                OutlinedButton(
                    onClick = { vm.setResume(null); fitResult = null; fitJob = null; status = "Cleared" },
                    shape = ShapeSm,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TEXT),
                    border = ButtonDefaults.outlinedButtonBorder.copy(
                        width = 1.dp,
                        brush = androidx.compose.ui.graphics.SolidColor(BORDER),
                    ),
                ) { Text("Remove") }
            }
        }

        if (status.isNotEmpty()) {
            Text(status, color = MUTED, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        }

        if (doc != null) {
            Spacer(Modifier.height(14.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = CARD),
                shape = ShapeMd,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, BORDER.copy(alpha = 0.55f), ShapeMd),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(doc.name.ifBlank { "Your resume" }, fontWeight = FontWeight.SemiBold, color = TEXT)
                    if (doc.headline.isNotBlank()) {
                        Text(doc.headline, color = MUTED, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
                    }
                    val contact = listOf(doc.email, doc.phone).filter { it.isNotBlank() }.joinToString(" · ")
                    if (contact.isNotEmpty()) {
                        Text(contact, color = MUTED, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Skills detected", fontSize = 12.sp, color = MUTED)
                    Text(
                        doc.skills.take(16).joinToString(" · ").ifBlank { "—" },
                        fontSize = 13.sp,
                        color = TEXT,
                        lineHeight = 18.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text("${doc.bullets.size} experience lines indexed", fontSize = 12.sp, color = MUTED)
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(
                "Go to Jobs → open any card → tap Fit. Or pick from Saved / Applied below.",
                color = MUTED,
                fontSize = 12.sp,
            )

            val pool = (vm.saved + vm.applied + vm.jobs).distinctBy { it.id }.take(12)
            if (pool.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("Quick fit", fontWeight = FontWeight.SemiBold, color = TEXT)
                pool.forEach { j ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                fitJob = j
                                fitResult = Resume.fit(doc, j)
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(j.title, color = TEXT, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1)
                        Text("Fit", color = ACCENT, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                    HorizontalDivider(color = BORDER.copy(alpha = 0.5f))
                }
            }
        }

        // Fit result panel
        val fr = fitResult
        val fj = fitJob
        if (fr != null && fj != null && doc != null) {
            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = BORDER)
            Spacer(Modifier.height(12.dp))
            Text("Fit for ${fj.title}", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TEXT)
            Text("${fj.company} · ${fj.location}", color = MUTED, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))

            // score bar
            val scoreColor = when {
                fr.score >= 70 -> GREEN
                fr.score >= 45 -> AMBER
                else -> Color(0xFFF87171)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${fr.score}", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = scoreColor)
                Text(" / 100 fit", color = MUTED, fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp))
            }
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { fr.score / 100f },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(ShapeSm),
                color = scoreColor,
                trackColor = BORDER,
            )

            Spacer(Modifier.height(12.dp))
            if (fr.matched.isNotEmpty()) {
                Text("Matched", fontSize = 12.sp, color = GREEN, fontWeight = FontWeight.SemiBold)
                Text(fr.matched.joinToString(" · "), color = TEXT, fontSize = 13.sp, lineHeight = 18.sp)
            }
            if (fr.missing.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text("JD asks — not on your resume (not added)", fontSize = 12.sp, color = AMBER, fontWeight = FontWeight.SemiBold)
                Text(fr.missing.take(10).joinToString(" · "), color = MUTED, fontSize = 13.sp, lineHeight = 18.sp)
            }

            Spacer(Modifier.height(12.dp))
            Text("Tailored headline", fontSize = 12.sp, color = MUTED)
            Text(fr.tailoredHeadline, color = TEXT, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text("Summary", fontSize = 12.sp, color = MUTED)
            Text(fr.tailoredSummary, color = TEXT, fontSize = 13.sp, lineHeight = 18.sp)
            Spacer(Modifier.height(8.dp))
            Text("Bullets (your work, JD wording)", fontSize = 12.sp, color = MUTED)
            fr.tailoredBullets.forEach {
                Text("• $it", color = TEXT, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp))
            }

            Spacer(Modifier.height(8.dp))
            fr.notes.forEach {
                Text("· $it", color = MUTED, fontSize = 11.sp, lineHeight = 15.sp)
            }

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    val text = Resume.renderText(doc, fr, fj)
                    Resume.share(ctx, text, "Resume — ${fj.title}")
                },
                shape = ShapeSm,
                colors = ButtonDefaults.buttonColors(containerColor = ACCENT, contentColor = BG),
            ) { Text("Share tailored resume", fontWeight = FontWeight.SemiBold) }
        }

        Spacer(Modifier.height(28.dp))
    }
}

@Composable
fun AlertsTab(vm: VM) {
    val ctx = LocalContext.current
    val st = vm.st
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(st.alertMode) }
    var mins by remember { mutableStateOf(st.alertMinutes) }
    var strong by remember { mutableStateOf(st.alertStrong) }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) perm.launch(Manifest.permission.POST_NOTIFICATIONS)
        st.alertHours = vm.fPosted
    }

    fun apply(m: String) {
        mode = m
        st.alertMode = m
        st.alertHours = vm.fPosted
        Alerts.schedule(ctx)
        if (m != "off" && Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) perm.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    val options = listOf(
        "off" to "Off", "hourly" to "Every hour", "morning" to "Every morning · 8:00 AM",
        "evening" to "Every evening · 6:00 PM", "daily" to "Daily at ${clock(mins)}",
    )
    val profileName = vm.profiles.firstOrNull { it.id == vm.defaultId }?.name ?: "My Ops roles"

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Job alerts", fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Text(
            "Relax searches your default profile in the background and notifies you when new jobs are waiting.",
            color = MUTED, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp),
        )
        Text("Searching: ★ $profileName  (change it from the Jobs tab)", fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        options.forEach { (key, label) ->
            Row(Modifier.fillMaxWidth().clickable { apply(key) }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = mode == key, onClick = { apply(key) })
                Text(label, modifier = Modifier.weight(1f))
                if (key == "daily") {
                    TextButton(onClick = {
                        TimePickerDialog(ctx, { _, h, m ->
                            mins = h * 60 + m
                            st.alertMinutes = mins
                            if (mode == "daily") Alerts.schedule(ctx)
                        }, mins / 60, mins % 60, false).show()
                    }) { Text("Set time") }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            Switch(checked = strong, onCheckedChange = { strong = it; st.alertStrong = it })
            Text("  Only strong matches (P1–P2)", fontSize = 13.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
            Button(enabled = !busy, onClick = {
                busy = true; msg = "Checking…"
                scope.launch {
                    msg = try {
                        val n = Alerts.check(ctx)
                        vm.reload()
                        if (n > 0) "$n new job(s) found — notification sent" else "No new unseen jobs right now"
                    } catch (e: Exception) {
                        "Check failed: ${e.message ?: e.javaClass.simpleName}"
                    }
                    busy = false
                }
            }) { Text("Check now") }
            OutlinedButton(onClick = { Alerts.test(ctx) }) { Text("Test notification") }
        }
        if (msg.isNotEmpty()) Text(msg, color = MUTED, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        Text(
            "Timing is approximate — Android may delay background work to save battery. For reliable alerts set " +
                "Settings → Apps → Relax → Battery → Unrestricted. Alerts only fire for jobs you haven't seen yet, " +
                "so a quiet check shows no notification.",
            color = MUTED, fontSize = 12.sp, modifier = Modifier.padding(top = 16.dp),
        )
    }
}

fun shareCsv(context: Context, jobs: List<Posting>) {
    val csvHeader = "Title,Company,Location,Portal,Salary,URL\n"
    val csvRows = jobs.joinToString("\n") { j ->
        "\"${j.title.replace("\"", "\"\"")}\"," +
        "\"${j.company.replace("\"", "\"\"")}\"," +
        "\"${j.location.replace("\"", "\"\"")}\"," +
        "\"${j.portal}\"," +
        "\"${j.salary.replace("\"", "\"\"")}\"," +
        "\"${j.url}\""
    }
    
    val file = File(context.cacheDir, "relaxed_jobs.csv")
    file.writeText(csvHeader + csvRows)
    
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share CSV"))
}
