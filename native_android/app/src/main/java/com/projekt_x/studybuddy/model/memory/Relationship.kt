package com.projekt_x.studybuddy.model.memory

import com.google.gson.annotations.SerializedName

/**
 * Relationships container - stores all user relationships
 * 
 * File: memory/core/relationships.json
 * Retention: FOREVER (never deleted)
 */
data class Relationships(
    @SerializedName("version")
    val version: Int = 1,
    
    @SerializedName("last_updated")
    val lastUpdated: String = "",
    
    @SerializedName("family")
    val family: MutableList<Relationship> = mutableListOf(),
    
    @SerializedName("friends")
    val friends: MutableList<Relationship> = mutableListOf(),
    
    @SerializedName("colleagues")
    val colleagues: MutableList<Relationship> = mutableListOf(),
    
    @SerializedName("important_people")
    val importantPeople: MutableList<Relationship> = mutableListOf()
) {
    /**
     * Get all relationships across all categories
     */
    fun getAll(): List<Relationship> {
        return family + friends + colleagues + importantPeople
    }
    
    /**
     * Find relationship by ID
     */
    fun findById(id: String): Relationship? {
        return getAll().find { it.id == id }
    }
    
    /**
     * Find relationship by name (case-insensitive)
     */
    fun findByName(name: String): Relationship? {
        return getAll().find { 
            it.name?.equals(name, ignoreCase = true) == true 
        }
    }
    
    /**
     * Search relationships by query (name, relation type, or notes)
     */
    fun search(query: String): List<Relationship> {
        val lowerQuery = query.lowercase()
        return getAll().filter { rel ->
            rel.name?.lowercase()?.contains(lowerQuery) == true ||
            rel.relation?.lowercase()?.contains(lowerQuery) == true ||
            rel.notes?.lowercase()?.contains(lowerQuery) == true
        }
    }
    
    /**
     * Add relationship to appropriate category
     */
    fun addRelationship(relationship: Relationship) {
        when (relationship.category) {
            RelationshipCategory.FAMILY -> family.add(relationship)
            RelationshipCategory.FRIEND -> friends.add(relationship)
            RelationshipCategory.COLLEAGUE -> colleagues.add(relationship)
            RelationshipCategory.IMPORTANT -> importantPeople.add(relationship)
        }
    }
    
    /**
     * Remove relationship by ID from any category
     */
    fun removeById(id: String): Boolean {
        return family.removeAll { it.id == id } ||
               friends.removeAll { it.id == id } ||
               colleagues.removeAll { it.id == id } ||
               importantPeople.removeAll { it.id == id }
    }
    
    /**
     * Update relationship by ID
     */
    fun updateById(id: String, updates: RelationshipUpdates): Boolean {
        val allLists = listOf(family, friends, colleagues, importantPeople)
        
        for (list in allLists) {
            val index = list.indexOfFirst { it.id == id }
            if (index != -1) {
                val existing = list[index]
                list[index] = existing.copy(
                    name = updates.name ?: existing.name,
                    relation = updates.relation ?: existing.relation,
                    contact = updates.contact ?: existing.contact,
                    notes = updates.notes ?: existing.notes,
                    birthdate = updates.birthdate ?: existing.birthdate
                )
                return true
            }
        }
        return false
    }
    
    /**
     * Get count of all relationships
     */
    fun getCount(): Int {
        return family.size + friends.size + colleagues.size + importantPeople.size
    }
    
    companion object {
        fun default(): Relationships = Relationships()
    }
}

/**
 * Individual relationship entry
 */
data class Relationship(
    @SerializedName("id")
    val id: String,
    
    @SerializedName("relation")
    val relation: String? = null,
    
    @SerializedName("name")
    val name: String? = null,
    
    @SerializedName("contact")
    val contact: String? = null,
    
    @SerializedName("notes")
    val notes: String? = null,
    
    @SerializedName("birthdate")
    val birthdate: String? = null
) {
    /**
     * Get category based on relation type
     */
    val category: RelationshipCategory
        get() = when (relation?.lowercase()) {
            "mother", "father", "mom", "dad", "parent",
            "sister", "brother", "sibling",
            "daughter", "son", "child",
            "grandmother", "grandfather", "grandparent",
            "aunt", "uncle", "cousin",
            "wife", "husband", "spouse", "partner" -> RelationshipCategory.FAMILY
            "friend", "buddy", "pal" -> RelationshipCategory.FRIEND
            "colleague", "coworker", "boss", "manager", "teammate" -> RelationshipCategory.COLLEAGUE
            else -> RelationshipCategory.IMPORTANT
        }
    
    /**
     * Format for context block display
     * Example: "Mom: Priya (Mumbai)"
     */
    fun formatForContext(): String {
        val parts = mutableListOf<String>()
        
        relation?.let { parts.add(it.replaceFirstChar { c -> c.uppercase() }) }
        name?.let { parts.add(": $it") }
        notes?.let { parts.add(" ($it)") }
        
        return parts.joinToString("")
    }
}

/**
 * Relationship update data class (partial updates)
 */
data class RelationshipUpdates(
    val name: String? = null,
    val relation: String? = null,
    val contact: String? = null,
    val notes: String? = null,
    val birthdate: String? = null
)

/**
 * Relationship categories
 */
enum class RelationshipCategory {
    FAMILY,
    FRIEND,
    COLLEAGUE,
    IMPORTANT
}
