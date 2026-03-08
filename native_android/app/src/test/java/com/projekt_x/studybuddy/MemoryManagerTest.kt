package com.projekt_x.studybuddy

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.projekt_x.studybuddy.bridge.FileSystemManager
import com.projekt_x.studybuddy.bridge.MemoryManager
import com.projekt_x.studybuddy.model.memory.MemoryDefaults
import com.projekt_x.studybuddy.model.memory.Relationship
import com.projekt_x.studybuddy.model.memory.Reminder
import com.projekt_x.studybuddy.model.memory.ReminderType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for MemoryManager
 * 
 * Run with: ./gradlew testDebugUnitTest
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class MemoryManagerTest {

    private lateinit var context: Context
    private lateinit var fileSystemManager: FileSystemManager
    private lateinit var memoryManager: MemoryManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        fileSystemManager = FileSystemManager(context)
        memoryManager = MemoryManager(context, fileSystemManager)
    }

    @After
    fun cleanup() {
        // Clean up test files
        runBlocking {
            fileSystemManager.deleteAll()
        }
    }

    @Test
    fun `test initialize creates default files`() = runBlocking {
        // Initialize
        val success = memoryManager.initialize()
        
        // Assert
        assertTrue("Initialization should succeed", success)
        assertTrue("User profile file should exist", fileSystemManager.getUserProfileFilePath().exists())
        assertTrue("Relationships file should exist", fileSystemManager.getRelationshipsFilePath().exists())
        assertTrue("Reminders file should exist", fileSystemManager.getRemindersFilePath().exists())
    }

    @Test
    fun `test buildContextBlock returns valid format`() = runBlocking {
        // Setup
        memoryManager.initialize()
        
        // Add some test data
        memoryManager.updateProfileField("name", "Kali")
        memoryManager.addFact("Uses Android tablet")
        
        // Build context
        val context = memoryManager.buildContextBlock()
        
        // Assert
        assertTrue("Should contain [MEMORY] tag", context.contains("[MEMORY]"))
        assertTrue("Should contain [/MEMORY] tag", context.contains("[/MEMORY]"))
        assertTrue("Should contain user name", context.contains("Kali"))
        assertTrue("Should contain facts", context.contains("Uses Android tablet"))
    }

    @Test
    fun `test buildContextBlock respects token limit`() = runBlocking {
        // Setup
        memoryManager.initialize()
        
        // Add many facts to test truncation
        repeat(20) { i ->
            memoryManager.addFact("Fact number $i with some content to make it longer")
        }
        
        // Build context with small limit
        val context = memoryManager.buildContextBlock(maxTokens = 100)
        
        // Assert
        val estimatedTokens = MemoryDefaults.estimateTokens(context)
        assertTrue("Should respect token limit (estimated: $estimatedTokens)", estimatedTokens <= 120)
    }

    @Test
    fun `test add and get relationship`() = runBlocking {
        // Setup
        memoryManager.initialize()
        
        // Add relationship
        val rel = Relationship(
            id = "rel_001",
            relation = "mother",
            name = "Priya",
            notes = "Lives in Mumbai"
        )
        val success = memoryManager.addRelationship(rel)
        
        // Assert
        assertTrue("Should add relationship", success)
        
        val retrieved = memoryManager.getRelationship("rel_001")
        assertNotNull("Should retrieve relationship", retrieved)
        assertEquals("Priya", retrieved?.name)
        assertEquals("mother", retrieved?.relation)
    }

    @Test
    fun `test search relationships`() = runBlocking {
        // Setup
        memoryManager.initialize()
        
        // Add relationships
        memoryManager.addRelationship(Relationship("rel_001", "mother", "Priya"))
        memoryManager.addRelationship(Relationship("rel_002", "friend", "Rohan"))
        memoryManager.addRelationship(Relationship("rel_003", "father", "Rajesh"))
        
        // Search
        val results = memoryManager.searchRelationships("priya")
        
        // Assert
        assertEquals("Should find 1 match", 1, results.size)
        assertEquals("Priya", results[0].name)
    }

    @Test
    fun `test add and complete reminder`() = runBlocking {
        // Setup
        memoryManager.initialize()
        
        // Add reminder
        val reminder = Reminder(
            id = "rem_001",
            type = ReminderType.CALL,
            text = "Call mom about dinner"
        )
        val added = memoryManager.addReminder(reminder)
        
        // Assert added
        assertTrue("Should add reminder", added)
        
        val pending = memoryManager.getPendingReminders()
        assertEquals("Should have 1 pending", 1, pending.size)
        
        // Complete
        val completed = memoryManager.completeReminder("rem_001")
        assertTrue("Should complete reminder", completed)
        
        val pendingAfter = memoryManager.getPendingReminders()
        assertEquals("Should have 0 pending", 0, pendingAfter.size)
    }

    @Test
    fun `test update profile field`() = runBlocking {
        // Setup
        memoryManager.initialize()
        
        // Update
        val success = memoryManager.updateProfileField("name", "Kali")
        
        // Assert
        assertTrue("Should update field", success)
        
        val profile = memoryManager.getUserProfile()
        assertEquals("Kali", profile.identity.name)
    }

    @Test
    fun `test add and remove fact`() = runBlocking {
        // Setup
        memoryManager.initialize()
        
        // Add fact
        val added = memoryManager.addFact("Likes Italian food")
        assertTrue("Should add fact", added)
        
        val profileWithFact = memoryManager.getUserProfile()
        assertTrue("Should contain fact", profileWithFact.facts.contains("Likes Italian food"))
        
        // Remove fact
        val removed = memoryManager.removeFact("Likes Italian food")
        assertTrue("Should remove fact", removed)
        
        val profileAfter = memoryManager.getUserProfile()
        assertFalse("Should not contain fact", profileAfter.facts.contains("Likes Italian food"))
    }

    @Test
    fun `test context block with empty profile`() = runBlocking {
        // Setup
        memoryManager.initialize()
        
        // Build context (no data added)
        val context = memoryManager.buildContextBlock()
        
        // Assert basic structure still present
        assertTrue("Should contain [MEMORY]", context.contains("[MEMORY]"))
        assertTrue("Should contain [/MEMORY]", context.contains("[/MEMORY]"))
    }
}
