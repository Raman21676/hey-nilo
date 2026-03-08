package com.projekt_x.studybuddy.model.memory

import com.google.gson.annotations.SerializedName

/**
 * User profile data class - stores permanent personal information
 * 
 * File: memory/core/user_profile.json
 * Retention: FOREVER (never deleted)
 */
data class UserProfile(
    @SerializedName("version")
    val version: Int = 1,
    
    @SerializedName("last_updated")
    val lastUpdated: String = "",
    
    @SerializedName("identity")
    val identity: Identity = Identity(),
    
    @SerializedName("contact")
    val contact: Contact = Contact(),
    
    @SerializedName("occupation")
    val occupation: Occupation = Occupation(),
    
    @SerializedName("preferences")
    val preferences: Preferences = Preferences(),
    
    @SerializedName("facts")
    val facts: MutableList<String> = mutableListOf()
) {
    /**
     * Get display name for context building
     * Returns preferred_name if available, otherwise name
     */
    fun getDisplayName(): String? {
        return identity.preferredName ?: identity.name
    }
    
    /**
     * Get location string for context building
     * Returns "city, country" or just city/country if only one available
     */
    fun getLocation(): String? {
        val city = contact.city
        val country = contact.country
        
        return when {
            city != null && country != null -> "$city, $country"
            city != null -> city
            country != null -> country
            else -> null
        }
    }
    
    /**
     * Get age string if available
     */
    fun getAgeString(): String? {
        return identity.age?.let { "$it years old" }
    }
    
    /**
     * Check if profile has any meaningful data
     */
    fun hasData(): Boolean {
        return identity.name != null ||
               identity.preferredName != null ||
               identity.age != null ||
               contact.city != null ||
               facts.isNotEmpty()
    }
    
    companion object {
        fun default(): UserProfile = UserProfile()
    }
}

/**
 * User identity information
 */
data class Identity(
    @SerializedName("name")
    val name: String? = null,
    
    @SerializedName("preferred_name")
    val preferredName: String? = null,
    
    @SerializedName("birthdate")
    val birthdate: String? = null,
    
    @SerializedName("age")
    val age: Int? = null,
    
    @SerializedName("gender")
    val gender: String? = null
)

/**
 * Contact information
 */
data class Contact(
    @SerializedName("address")
    val address: String? = null,
    
    @SerializedName("city")
    val city: String? = null,
    
    @SerializedName("country")
    val country: String? = null,
    
    @SerializedName("phone")
    val phone: String? = null
)

/**
 * Occupation information
 */
data class Occupation(
    @SerializedName("title")
    val title: String? = null,
    
    @SerializedName("company")
    val company: String? = null,
    
    @SerializedName("industry")
    val industry: String? = null
)

/**
 * User preferences
 */
data class Preferences(
    @SerializedName("communication_style")
    val communicationStyle: String = "friendly",
    
    @SerializedName("food")
    val food: MutableList<String> = mutableListOf(),
    
    @SerializedName("music")
    val music: MutableList<String> = mutableListOf(),
    
    @SerializedName("hobbies")
    val hobbies: MutableList<String> = mutableListOf()
)
