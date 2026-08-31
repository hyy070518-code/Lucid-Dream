package com.huyang.luciddream.data.repository

import com.huyang.luciddream.data.dao.ContactPolicyDao
import com.huyang.luciddream.data.dao.ExternalMessageDao
import com.huyang.luciddream.data.entity.ContactPolicyEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

data class ContactPolicyItem(
    val contactKey: String,
    val sourcePackage: String,
    val displayName: String,
    val isAllowlisted: Boolean,
    val replyLimit: Int,
)

@Singleton
class ContactPolicyRepository @Inject constructor(
    externalMessageDao: ExternalMessageDao,
    private val policyDao: ContactPolicyDao,
) {
    val contacts: Flow<List<ContactPolicyItem>> = combine(
        externalMessageDao.observeContacts(),
        policyDao.observeAll(),
    ) { observed, policies ->
        val policiesByKey = policies.associateBy { it.contactKey }
        val items = observed.map { contact ->
            val policy = policiesByKey[contact.contactKey]
            ContactPolicyItem(
                contactKey = contact.contactKey,
                sourcePackage = contact.sourcePackage,
                displayName = contact.displayName,
                isAllowlisted = policy?.isAllowlisted ?: false,
                replyLimit = if (policy?.isAllowlisted == true) policy.replyLimit else DEFAULT_LIMIT,
            )
        }.toMutableList()
        val observedKeys = observed.mapTo(mutableSetOf()) { it.contactKey }
        policies.filterNot { it.contactKey in observedKeys }.forEach { policy ->
            items += ContactPolicyItem(
                contactKey = policy.contactKey,
                sourcePackage = policy.sourcePackage,
                displayName = policy.displayName,
                isAllowlisted = policy.isAllowlisted,
                replyLimit = if (policy.isAllowlisted) policy.replyLimit else DEFAULT_LIMIT,
            )
        }
        items
    }

    suspend fun update(item: ContactPolicyItem, allowlisted: Boolean, replyLimit: Int) {
        val effectiveLimit = if (allowlisted) {
            require(replyLimit in ALLOWED_LIMITS)
            replyLimit
        } else {
            DEFAULT_LIMIT
        }
        policyDao.upsert(
            ContactPolicyEntity(
                contactKey = item.contactKey,
                sourcePackage = item.sourcePackage,
                displayName = item.displayName,
                isAllowlisted = allowlisted,
                replyLimit = effectiveLimit,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    companion object {
        const val DEFAULT_LIMIT = 3
        val ALLOWED_LIMITS = setOf(3, 5, 10)
    }
}
