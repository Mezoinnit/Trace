package com.mezo.trace.application.port

import com.mezo.trace.domain.Email
import com.mezo.trace.domain.User
import com.mezo.trace.domain.UserId

interface UserRepository {
    fun insert(user: User)
    fun findByEmail(email: Email): User?
    fun findById(id: UserId): User?
    fun delete(id: UserId)
}
