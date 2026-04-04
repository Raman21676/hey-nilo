package com.projekt_x.studybuddy.ui.memory

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.projekt_x.studybuddy.bridge.MemoryManager
import com.projekt_x.studybuddy.bridge.MemoryCompaction
import com.projekt_x.studybuddy.model.memory.Relationship
import com.projekt_x.studybuddy.model.memory.RelationshipCategory
import com.projekt_x.studybuddy.model.memory.Reminder
import com.projekt_x.studybuddy.model.memory.ReminderType
import com.projekt_x.studybuddy.model.memory.ReminderStatus
import com.projekt_x.studybuddy.model.memory.MemoryDefaults
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * MemoryManagerScreen - UI for viewing and managing persistent memory
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryManagerScreen(
    memoryManager: MemoryManager,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Profile", "People", "Reminders", "History", "Storage")
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Memory Manager") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                modifier = Modifier.fillMaxWidth()
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) }
                    )
                }
            }
            
            Box(modifier = Modifier.fillMaxSize()) {
                when (selectedTab) {
                    0 -> ProfileTab(memoryManager, snackbarHostState)
                    1 -> PeopleTab(memoryManager, snackbarHostState)
                    2 -> RemindersTab(memoryManager, snackbarHostState)
                    3 -> HistoryTab(memoryManager)
                    4 -> StorageTab(memoryManager, snackbarHostState)
                }
            }
        }
    }
}

@Composable
private fun ProfileTab(
    memoryManager: MemoryManager,
    snackbarHostState: SnackbarHostState
) {
    val scope = rememberCoroutineScope()
    val profile = remember { memoryManager.getUserProfile() }
    
    var name by remember { mutableStateOf(profile.identity.name ?: "") }
    var age by remember { mutableStateOf(profile.identity.age?.toString() ?: "") }
    var city by remember { mutableStateOf(profile.contact.city ?: "") }
    var company by remember { mutableStateOf(profile.occupation.company ?: "") }
    var title by remember { mutableStateOf(profile.occupation.title ?: "") }
    
    var newFact by remember { mutableStateOf("") }
    val facts = remember { profile.facts.toMutableStateList() }
    
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                "Your Profile",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Information about you that SMITH remembers",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        item {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Basic Information", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(value = age, onValueChange = { age = it.filter { it.isDigit() } }, label = { Text("Age") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(value = city, onValueChange = { city = it }, label = { Text("City") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(value = company, onValueChange = { company = it }, label = { Text("Company") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Job Title") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    
                    Button(
                        onClick = {
                            scope.launch {
                                memoryManager.updateProfileField("name", name.takeIf { it.isNotBlank() })
                                memoryManager.updateProfileField("age", age.toIntOrNull())
                                memoryManager.updateProfileField("city", city.takeIf { it.isNotBlank() })
                                memoryManager.updateProfileField("company", company.takeIf { it.isNotBlank() })
                                memoryManager.updateProfileField("title", title.takeIf { it.isNotBlank() })
                                snackbarHostState.showSnackbar("Profile updated")
                            }
                        },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Save Changes")
                    }
                }
            }
        }
        
        item {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Facts About You", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(value = newFact, onValueChange = { newFact = it }, label = { Text("Add a fact") }, modifier = Modifier.weight(1f), singleLine = true)
                        IconButton(
                            onClick = {
                                if (newFact.isNotBlank()) {
                                    scope.launch {
                                        memoryManager.addFact(newFact)
                                        facts.add(newFact)
                                        newFact = ""
                                        snackbarHostState.showSnackbar("Fact added")
                                    }
                                }
                            }
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Add fact")
                        }
                    }
                    
                    if (facts.isEmpty()) {
                        Text("No facts yet. SMITH will learn facts from your conversations automatically.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        facts.forEachIndexed { index, fact ->
                            FactItem(fact = fact, onDelete = {
                                scope.launch {
                                    memoryManager.removeFact(fact)
                                    facts.removeAt(index)
                                    snackbarHostState.showSnackbar("Fact removed")
                                }
                            })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FactItem(fact: String, onDelete: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(text = fact, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Delete", tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun PeopleTab(memoryManager: MemoryManager, snackbarHostState: SnackbarHostState) {
    val scope = rememberCoroutineScope()
    var relationships by remember { mutableStateOf(memoryManager.getAllRelationships()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    
    val filteredRelationships = remember(relationships, searchQuery) {
        if (searchQuery.isBlank()) relationships else memoryManager.searchRelationships(searchQuery)
    }
    
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(value = searchQuery, onValueChange = { searchQuery = it }, label = { Text("Search people") }, modifier = Modifier.fillMaxWidth(), leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }, singleLine = true)
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = { showAddDialog = true }, modifier = Modifier.align(Alignment.End)) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(4.dp))
            Text("Add Person")
        }
        Spacer(modifier = Modifier.height(8.dp))
        
        if (filteredRelationships.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(if (searchQuery.isBlank()) "No people added yet" else "No matches found", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(filteredRelationships, key = { it.id }) { relationship ->
                    RelationshipCard(relationship = relationship, onDelete = {
                        scope.launch {
                            memoryManager.deleteRelationship(relationship.id)
                            relationships = memoryManager.getAllRelationships()
                            snackbarHostState.showSnackbar("${relationship.name} removed")
                        }
                    })
                }
            }
        }
    }
    
    if (showAddDialog) {
        AddRelationshipDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { name, relation, note ->
                scope.launch {
                    val newRel = Relationship(id = UUID.randomUUID().toString(), relation = relation, name = name, notes = note)
                    memoryManager.addRelationship(newRel)
                    relationships = memoryManager.getAllRelationships()
                    showAddDialog = false
                    snackbarHostState.showSnackbar("Added $name")
                }
            }
        )
    }
}

@Composable
private fun RelationshipCard(relationship: Relationship, onDelete: () -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = relationship.name ?: "Unknown", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(text = relationship.relation?.replaceFirstChar { it.uppercase() } ?: "Unknown", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                relationship.notes?.let { notes ->
                    if (notes.isNotBlank()) {
                        Text(text = notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                    }
                }
            }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun AddRelationshipDialog(onDismiss: () -> Unit, onAdd: (String, String, String?) -> Unit) {
    var name by remember { mutableStateOf("") }
    var relation by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val commonRelations = listOf("mother", "father", "sister", "brother", "friend", "colleague", "spouse")
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Person") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(value = relation, onValueChange = { relation = it }, label = { Text("Relation") }, singleLine = true)
                Text("Quick select:", style = MaterialTheme.typography.labelSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    commonRelations.forEach { rel ->
                        AssistChip(onClick = { relation = rel }, label = { Text(rel) })
                    }
                }
                OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("Notes (optional)") }, minLines = 2)
            }
        },
        confirmButton = { Button(onClick = { onAdd(name, relation, note.takeIf { it.isNotBlank() }) }, enabled = name.isNotBlank() && relation.isNotBlank()) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun RemindersTab(memoryManager: MemoryManager, snackbarHostState: SnackbarHostState) {
    val scope = rememberCoroutineScope()
    var pendingReminders by remember { mutableStateOf(memoryManager.getPendingReminders()) }
    var allReminders by remember { mutableStateOf(memoryManager.getAllReminders()) }
    var showCompleted by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    
    val displayReminders = if (showCompleted) allReminders else pendingReminders
    
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = showCompleted, onClick = { showCompleted = !showCompleted }, label = { Text(if (showCompleted) "Showing All" else "Pending Only") })
            Button(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("Add Reminder")
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        
        if (displayReminders.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Notifications, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(if (showCompleted) "No reminders" else "No pending reminders", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(displayReminders, key = { it.id }) { reminder ->
                    ReminderCard(reminder = reminder, isCompleted = reminder.status == ReminderStatus.COMPLETED, onComplete = {
                        scope.launch {
                            memoryManager.completeReminder(reminder.id)
                            pendingReminders = memoryManager.getPendingReminders()
                            allReminders = memoryManager.getAllReminders()
                            snackbarHostState.showSnackbar("Reminder completed")
                        }
                    }, onDelete = {
                        scope.launch {
                            memoryManager.deleteReminder(reminder.id)
                            pendingReminders = memoryManager.getPendingReminders()
                            allReminders = memoryManager.getAllReminders()
                            snackbarHostState.showSnackbar("Reminder deleted")
                        }
                    })
                }
            }
        }
    }
    
    if (showAddDialog) {
        AddReminderDialog(onDismiss = { showAddDialog = false }, onAdd = { text, type, target ->
            scope.launch {
                val reminder = Reminder(id = UUID.randomUUID().toString(), type = type, text = text, targetPerson = target, createdAt = MemoryDefaults.getCurrentTimestamp())
                memoryManager.addReminder(reminder)
                pendingReminders = memoryManager.getPendingReminders()
                allReminders = memoryManager.getAllReminders()
                showAddDialog = false
                snackbarHostState.showSnackbar("Reminder added")
            }
        })
    }
}

@Composable
private fun ReminderCard(reminder: Reminder, isCompleted: Boolean, onComplete: () -> Unit, onDelete: () -> Unit) {
    val containerColor = if (isCompleted) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f) else MaterialTheme.colorScheme.secondaryContainer
    ElevatedCard(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.elevatedCardColors(containerColor = containerColor)) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!isCompleted) {
                    Checkbox(checked = false, onCheckedChange = { onComplete() })
                } else {
                    Icon(Icons.Default.CheckCircle, contentDescription = "Completed", tint = MaterialTheme.colorScheme.primary)
                }
                Column {
                    Text(text = reminder.text, style = MaterialTheme.typography.bodyLarge, textDecoration = if (isCompleted) androidx.compose.ui.text.style.TextDecoration.LineThrough else null)
                    reminder.targetPerson?.let { Text(text = "With: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun AddReminderDialog(onDismiss: () -> Unit, onAdd: (String, ReminderType, String?) -> Unit) {
    var text by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf(ReminderType.REMINDER) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Reminder") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("What to remember") }, minLines = 2)
                OutlinedTextField(value = target, onValueChange = { target = it }, label = { Text("Person (optional)") }, singleLine = true)
                Text("Type:", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ReminderType.entries.forEach { type ->
                        FilterChip(selected = selectedType == type, onClick = { selectedType = type }, label = { Text(type.name.lowercase().replace("_", " ")) })
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onAdd(text, selectedType, target.takeIf { it.isNotBlank() }) }, enabled = text.isNotBlank()) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun HistoryTab(memoryManager: MemoryManager) {
    val scope = rememberCoroutineScope()
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<MemoryManager.SearchResults?>(null) }
    var isSearching by remember { mutableStateOf(false) }
    var recentConversations by remember { mutableStateOf<List<com.projekt_x.studybuddy.model.memory.ConversationSummary>>(emptyList()) }
    
    LaunchedEffect(Unit) { recentConversations = memoryManager.getRecentConversationSummaries(20) }
    
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            label = { Text("Search conversations") },
            modifier = Modifier.fillMaxWidth(),
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (searchQuery.isNotBlank()) {
                    IconButton(onClick = { searchQuery = ""; searchResults = null }) { Icon(Icons.Default.Close, contentDescription = "Clear") }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = {
                if (searchQuery.isNotBlank()) {
                    isSearching = true
                    scope.launch { searchResults = memoryManager.searchAll(searchQuery); isSearching = false }
                }
            },
            modifier = Modifier.align(Alignment.End),
            enabled = searchQuery.isNotBlank() && !isSearching
        ) {
            if (isSearching) { CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp) } else { Text("Search") }
        }
        Spacer(modifier = Modifier.height(16.dp))
        
        if (searchResults != null) { SearchResultsView(searchResults!!) }
        else if (recentConversations.isNotEmpty()) {
            Text("Recent Conversations", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(recentConversations) { convo -> ConversationSummaryCard(convo) }
            }
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No conversation history yet", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun SearchResultsView(results: MemoryManager.SearchResults) {
    if (results.isEmpty()) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Text("No results found", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        return
    }
    
    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (results.profileMatches.isNotEmpty()) {
            item { Text("Profile (${results.profileMatches.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            items(results.profileMatches) { match ->
                ListItem(headlineContent = { Text(match.field) }, supportingContent = { Text(match.value) }, leadingContent = { Icon(Icons.Default.Person, contentDescription = null) })
            }
        }
        if (results.relationshipMatches.isNotEmpty()) {
            item { Text("People (${results.relationshipMatches.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            items(results.relationshipMatches) { rel ->
                ListItem(headlineContent = { Text(rel.name ?: "") }, supportingContent = { Text(rel.relation ?: "") }, leadingContent = { Icon(Icons.Default.Person, contentDescription = null) })
            }
        }
        if (results.reminderMatches.isNotEmpty()) {
            item { Text("Reminders (${results.reminderMatches.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            items(results.reminderMatches) { reminder ->
                ListItem(headlineContent = { Text(reminder.text) }, supportingContent = { reminder.targetPerson?.let { Text(it) } }, leadingContent = { Icon(Icons.Default.Notifications, contentDescription = null) })
            }
        }
        if (results.conversationMatches.isNotEmpty()) {
            item { Text("Conversations (${results.conversationMatches.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            items(results.conversationMatches) { match ->
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(match.date, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(match.excerpt, style = MaterialTheme.typography.bodyMedium, maxLines = 3)
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationSummaryCard(conversation: com.projekt_x.studybuddy.model.memory.ConversationSummary) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(conversation.date, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(conversation.mode, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(conversation.summary, style = MaterialTheme.typography.bodyMedium, maxLines = 2, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StorageTab(memoryManager: MemoryManager, snackbarHostState: SnackbarHostState) {
    val scope = rememberCoroutineScope()
    var storageBreakdown by remember { mutableStateOf<MemoryCompaction.StorageBreakdown?>(null) }
    var isCompacting by remember { mutableStateOf(false) }
    
    LaunchedEffect(Unit) { storageBreakdown = memoryManager.getStorageBreakdown() }
    
    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Storage Usage", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        
        if (storageBreakdown == null) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            val breakdown = storageBreakdown!!
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Total Used", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(breakdown.getTotalFormatted(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("of ${breakdown.getMaxFormatted()}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { breakdown.getUsagePercentage() / 100f }, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("${breakdown.getUsagePercentage()}% used", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Breakdown", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    StorageItem("Conversations", breakdown.getConversationsFormatted(), breakdown.conversationsSize.toFloat() / breakdown.totalUsed.coerceAtLeast(1))
                    StorageItem("Core Data", breakdown.getCoreFormatted(), breakdown.coreSize.toFloat() / breakdown.totalUsed.coerceAtLeast(1))
                    StorageItem("Work & Tasks", breakdown.getWorkFormatted(), breakdown.workSize.toFloat() / breakdown.totalUsed.coerceAtLeast(1))
                    StorageItem("System", breakdown.getSystemFormatted(), breakdown.systemSize.toFloat() / breakdown.totalUsed.coerceAtLeast(1))
                }
            }
            
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Archive Old Conversations", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Conversations older than 1 year will be archived into quarterly summaries to free up space.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(
                        onClick = {
                            isCompacting = true
                            scope.launch {
                                val result = memoryManager.forceCompaction()
                                isCompacting = false
                                storageBreakdown = memoryManager.getStorageBreakdown()
                                val message = if (result.success) "Archived ${result.filesArchived} conversations, freed ${result.getBytesFreedFormatted()}" else "Compaction failed: ${result.errors.firstOrNull() ?: "Unknown error"}"
                                snackbarHostState.showSnackbar(message)
                            }
                        },
                        enabled = !isCompacting,
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        if (isCompacting) { CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp) } else { Text("Archive Now") }
                    }
                }
            }
        }
    }
}

@Composable
private fun StorageItem(label: String, size: String, percentage: Float) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(size, style = MaterialTheme.typography.bodyMedium)
        }
        LinearProgressIndicator(progress = { percentage.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
    }
}
