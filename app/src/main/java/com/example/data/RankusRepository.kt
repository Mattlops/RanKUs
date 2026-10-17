package com.example.data

import android.content.Context
import com.example.R
import com.example.data.firestore.FirestoreActivity
import com.example.data.firestore.FirestoreScore
import com.example.data.firestore.FirestoreUser
import com.example.data.firestore.OperationType
import com.example.data.firestore.handleFirestoreError
import com.example.data.models.ActivityFeedItem
import com.example.data.models.DailyGameStatus
import com.example.data.models.GameType
import com.example.data.models.ParsedScoreResult
import com.example.data.models.Player
import com.example.data.models.ScoreHistoryItem
import com.example.data.models.TermoVariant
import com.example.data.models.UserProfile
import com.example.data.models.WeeklyComparisonDay
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalDate

class RankusRepository(private val db: FirebaseFirestore? = null) {

    constructor(context: Context) : this(
        try {
            FirebaseFirestore.getInstance(
                context.applicationContext.getString(R.string.firestore_database_id)
            )
        } catch (_: Exception) {
            null
        }
    )

    // Auth Session State
    private val _isLoggedIn = MutableStateFlow(Firebase.auth.currentUser != null)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    // Daily Scores State (true when at least 1 score was recorded today)
    private val _hasDailyScores = MutableStateFlow(false)
    val hasDailyScores: StateFlow<Boolean> = _hasDailyScores.asStateFlow()

    // Current User Profile State
    private val _currentUser = MutableStateFlow(
        UserProfile(
            id = "gamer_local",
            name = "Gamer",
            handle = "@gamer",
            bio = "Competidor oficial RanKUs",
            avatarUrl = "https://lh3.googleusercontent.com/aida-public/AB6AXuArX2Naz8deCUE-iK-e43qANrfcLUYgmrDcshFaoSdcgNxv26A0dOXusFi8nsBhQz5jD_wxS_q2Z0DRMIHQWTLWGQLGZUbL6vROHg8445-FoSGtS-c7wP-abafkJGrquQJc3SerLYwlLMZOq1JejFWQwgZlnejCcdhdfN09-qc3lONtDKzOtFgUNE7CDOI8eHMmFCQ0fx5g5FWB_mTzCWVA4nHbC6dF26RexBB-aoxC2JwR2vjRwT2naQ",
            totalScore = 0,
            todayScore = 0,
            currentRank = 1,
            streakDays = 0,
            favoriteGame = GameType.TERMO,
            matchesPlayed = 0,
            winRatePercent = 0,
            bestRank = 1
        )
    )
    val currentUser: StateFlow<UserProfile> = _currentUser.asStateFlow()

    // Daily Games Status (All 3 uncompleted initially)
    private val _dailyGames = MutableStateFlow(
        listOf(
            DailyGameStatus(GameType.TERMO, isCompleted = false, points = 0, friendsCount = 0),
            DailyGameStatus(GameType.GLOBLE, isCompleted = false, points = 0, friendsCount = 0),
            DailyGameStatus(GameType.SIZEITUP, isCompleted = false, points = 0, friendsCount = 0)
        )
    )
    val dailyGames: StateFlow<List<DailyGameStatus>> = _dailyGames.asStateFlow()

    // Activity Feed (Starts empty - only real cloud/local activities)
    private val _activities = MutableStateFlow<List<ActivityFeedItem>>(emptyList())
    val activities: StateFlow<List<ActivityFeedItem>> = _activities.asStateFlow()

    // Leaderboard Players (Starts empty or only real users)
    private val _players = MutableStateFlow<List<Player>>(emptyList())
    val players: StateFlow<List<Player>> = _players.asStateFlow()

    // User's Score History
    private val _scoreHistory = MutableStateFlow<List<ScoreHistoryItem>>(emptyList())
    val scoreHistory: StateFlow<List<ScoreHistoryItem>> = _scoreHistory.asStateFlow()

    init {
        // Sync with Firebase Auth state
        Firebase.auth.addAuthStateListener { auth ->
            val user = auth.currentUser
            _isLoggedIn.value = user != null
            if (user != null) {
                val displayName = user.displayName?.ifBlank { null }
                    ?: user.email?.substringBefore("@")
                    ?: "Gamer_${user.uid.take(4)}"
                _currentUser.update {
                    it.copy(
                        id = user.uid,
                        name = displayName,
                        handle = user.email ?: "@$displayName"
                    )
                }
                syncUserProfileFromCloud(user.uid, displayName, user.email)
                syncScoresFromCloud(user.uid)
                attachCloudListeners()
            }
        }

        // Attach listeners if already authenticated
        if (Firebase.auth.currentUser != null) {
            val user = Firebase.auth.currentUser!!
            val displayName = user.displayName?.ifBlank { null }
                ?: user.email?.substringBefore("@")
                ?: "Gamer_${user.uid.take(4)}"
            _currentUser.update {
                it.copy(
                    id = user.uid,
                    name = displayName,
                    handle = user.email ?: "@$displayName"
                )
            }
            syncUserProfileFromCloud(user.uid, displayName, user.email)
            syncScoresFromCloud(user.uid)
            attachCloudListeners()
        }
    }

    private fun attachCloudListeners() {
        // Real-time snapshot listener on cloud activities
        db?.collection("activities")
            ?.orderBy("createdAt", Query.Direction.DESCENDING)
            ?.limit(30)
            ?.addSnapshotListener { snapshot, error ->
                if (error != null) {
                    handleFirestoreError(error, OperationType.LIST, "activities")
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    val cloudActivities = snapshot.documents.mapNotNull { doc ->
                        val playerName = doc.getString("playerName") ?: return@mapNotNull null
                        val gameName = doc.getString("gameName") ?: "Jogo"
                        val points = doc.getLong("pointsEarned")?.toInt() ?: 0
                        val fire = doc.getLong("fireReactions")?.toInt() ?: 0
                        val applause = doc.getLong("applauseReactions")?.toInt() ?: 0
                        val target = doc.getLong("targetReactions")?.toInt() ?: 0
                        val avatarUrl = doc.getString("avatarUrl") ?: _currentUser.value.avatarUrl
                        ActivityFeedItem(
                            id = doc.id,
                            playerName = playerName,
                            avatarUrl = avatarUrl,
                            gameName = gameName,
                            gameLetter = gameName.first().toString(),
                            timeAgo = "recentemente",
                            pointsEarned = points,
                            fireReactions = fire,
                            applauseReactions = applause,
                            targetReactions = target
                        )
                    }
                    _activities.value = cloudActivities
                }
            }

        // Real-time snapshot listener on cloud users for Leaderboard
        db?.collection("users")
            ?.orderBy("totalScore", Query.Direction.DESCENDING)
            ?.limit(50)
            ?.addSnapshotListener { snapshot, error ->
                if (error != null) {
                    handleFirestoreError(error, OperationType.LIST, "users")
                    return@addSnapshotListener
                }
                if (snapshot != null && !snapshot.isEmpty) {
                    val currentUid = Firebase.auth.currentUser?.uid ?: _currentUser.value.id
                    val cloudPlayers = snapshot.documents.mapIndexedNotNull { idx, doc ->
                        val uid = doc.getString("userId") ?: doc.id
                        val name = doc.getString("nickname") ?: "Gamer"
                        val totalScore = doc.getLong("totalScore")?.toInt() ?: 0
                        val streak = doc.getLong("streakDays")?.toInt() ?: 0
                        val matches = doc.getLong("matchesPlayed")?.toInt() ?: 0
                        val avatarUrl = doc.getString("avatarUrl") ?: _currentUser.value.avatarUrl
                        val isCurrent = uid == currentUid
                        Player(
                            id = uid,
                            name = if (isCurrent) "Você ($name)" else name,
                            handle = "@${name.lowercase().replace(" ", "_")}",
                            avatarUrl = avatarUrl,
                            score = totalScore,
                            rank = idx + 1,
                            streak = streak,
                            gamesCount = matches,
                            isCurrentUser = isCurrent,
                            badge = if (idx == 0) "LÍDER" else if (isCurrent) "VOCÊ" else null,
                            winRate = if (matches > 0) 85 else 0
                        )
                    }
                    _players.value = cloudPlayers
                }
            }
    }

    private fun syncScoresFromCloud(uid: String) {
        db?.collection("scores")
            ?.whereEqualTo("userId", uid)
            ?.get()
            ?.addOnSuccessListener { snapshot ->
                if (snapshot != null) {
                    val todayStr = LocalDate.now().toString()
                    val cloudScores = snapshot.documents.mapNotNull { doc ->
                        val gameTypeStr = doc.getString("gameType") ?: return@mapNotNull null
                        val gType = when (gameTypeStr.lowercase()) {
                            "termo" -> GameType.TERMO
                            "globle" -> GameType.GLOBLE
                            "sizeitup" -> GameType.SIZEITUP
                            else -> return@mapNotNull null
                        }
                        val scoreVal = doc.getLong("score")?.toInt() ?: 0
                        val dateKey = doc.getString("dateKey") ?: todayStr
                        val details = doc.getString("details") ?: ""
                        val rawText = doc.getString("rawText") ?: ""
                        ScoreHistoryItem(
                            id = doc.id,
                            userId = uid,
                            gameType = gType,
                            dateKey = dateKey,
                            score = scoreVal,
                            details = details,
                            rawGrid = if (rawText.isNotBlank()) rawText.lines() else emptyList()
                        )
                    }.sortedByDescending { it.dateKey }

                    _scoreHistory.value = cloudScores

                    // Check today's scores
                    val todayScores = cloudScores.filter { it.dateKey == todayStr }
                    if (todayScores.isNotEmpty()) {
                        _hasDailyScores.value = true
                        _dailyGames.update { list ->
                            list.map { g ->
                                val scoreItem = todayScores.find { it.gameType == g.gameType }
                                if (scoreItem != null) {
                                    g.copy(isCompleted = true, points = scoreItem.score)
                                } else g
                            }
                        }
                        val sumToday = todayScores.sumOf { it.score }
                        _currentUser.update { it.copy(todayScore = sumToday) }
                    }
                }
            }
    }

    private fun syncUserProfileFromCloud(uid: String, displayName: String, email: String?) {
        db?.collection("users")?.document(uid)?.get()?.addOnSuccessListener { doc ->
            if (doc != null && doc.exists()) {
                val nickname = doc.getString("nickname") ?: displayName
                val bio = doc.getString("bio") ?: "Gamer do RanKUs"
                val totalScore = doc.getLong("totalScore")?.toInt() ?: 0
                val streak = doc.getLong("streakDays")?.toInt() ?: 0
                val matches = doc.getLong("matchesPlayed")?.toInt() ?: 0
                val avatar = doc.getString("avatarUrl") ?: _currentUser.value.avatarUrl
                _currentUser.update {
                    it.copy(
                        name = nickname,
                        bio = bio,
                        totalScore = totalScore,
                        streakDays = streak,
                        matchesPlayed = matches,
                        avatarUrl = avatar
                    )
                }
            } else {
                val newProfile = FirestoreUser(
                    userId = uid,
                    nickname = displayName,
                    email = email,
                    bio = "Jogador oficial RanKUs"
                )
                db?.collection("users")?.document(uid)?.set(newProfile.toMap())
                    ?.addOnFailureListener { e ->
                        handleFirestoreError(e, OperationType.CREATE, "users/$uid")
                    }
            }
        }
    }

    fun login(emailOrUser: String) {
        _isLoggedIn.value = true
        _currentUser.update {
            if (emailOrUser.contains("@")) {
                val cleanHandle = emailOrUser.substringBefore("@")
                it.copy(name = cleanHandle.replaceFirstChar { c -> c.uppercase() }, handle = emailOrUser)
            } else if (emailOrUser.isNotBlank()) {
                it.copy(name = emailOrUser, handle = "@$emailOrUser")
            } else it
        }
    }

    fun logout() {
        Firebase.auth.signOut()
        _isLoggedIn.value = false
    }

    fun toggleDailyScores(hasScores: Boolean) {
        _hasDailyScores.value = hasScores
    }

    /**
     * Checks if a user is allowed to submit a result for the given game today.
     * Rule: 
     * - Termo: Can register 1 time for Clássico, 1 time for Dueto, and 1 time for Quarteto per day!
     * - Globle and SizeItUp: 1 submission per day.
     */
    fun canSubmitGameToday(gameType: GameType, variant: TermoVariant? = null): Boolean {
        val todayStr = LocalDate.now().toString()
        return if (gameType == GameType.TERMO) {
            val targetVariant = variant ?: TermoVariant.QUARTETO
            val alreadyInHistory = _scoreHistory.value.any {
                it.gameType == GameType.TERMO && (it.variant ?: TermoVariant.QUARTETO) == targetVariant && it.dateKey == todayStr
            }
            !alreadyInHistory
        } else {
            val alreadyInDaily = _dailyGames.value.any { it.gameType == gameType && it.isCompleted }
            val alreadyInHistory = _scoreHistory.value.any { it.gameType == gameType && it.dateKey == todayStr }
            !alreadyInDaily && !alreadyInHistory
        }
    }

    /**
     * Verifies if a nickname is unique (not used by any other player).
     */
    fun isNicknameAvailable(nickname: String, currentUserId: String? = null): Boolean {
        val clean = nickname.trim()
        if (clean.isBlank()) return false
        val myUid = currentUserId ?: _currentUser.value.id
        return _players.value.none { player ->
            player.id != myUid && !player.isCurrentUser && player.name.trim().equals(clean, ignoreCase = true)
        }
    }

    fun reactToActivity(activityId: String, emoji: String) {
        _activities.update { list ->
            list.map { item ->
                if (item.id == activityId) {
                    val isAlreadyReacted = item.userReactions.contains(emoji)
                    val newReactions = if (isAlreadyReacted) item.userReactions - emoji else item.userReactions + emoji
                    val delta = if (isAlreadyReacted) -1 else 1
                    val updated = when (emoji) {
                        "🔥" -> item.copy(fireReactions = (item.fireReactions + delta).coerceAtLeast(0), userReactions = newReactions)
                        "👏" -> item.copy(applauseReactions = (item.applauseReactions + delta).coerceAtLeast(0), userReactions = newReactions)
                        "🎯" -> item.copy(targetReactions = (item.targetReactions + delta).coerceAtLeast(0), userReactions = newReactions)
                        else -> item
                    }
                    db?.collection("activities")?.document(activityId)?.update(
                        when (emoji) {
                            "🔥" -> "fireReactions" to updated.fireReactions.toLong()
                            "👏" -> "applauseReactions" to updated.applauseReactions.toLong()
                            "🎯" -> "targetReactions" to updated.targetReactions.toLong()
                            else -> "fireReactions" to updated.fireReactions.toLong()
                        }.let { mapOf(it.first to it.second) }
                    )
                    updated
                } else item
            }
        }
    }

    /**
     * Submits a game result with strict rules:
     * - Termo: 1 submission per variant (Clássico, Dueto, Quarteto) per day.
     *   Only Quarteto awards points in Season 2026!
     * - Globle & SizeItUp: 1 record per game per day.
     * - Capped at 100 points per game, daily cap at 300 points.
     */
    fun submitScore(result: ParsedScoreResult): Boolean {
        // Enforce 1 record per game & variant per day
        if (!canSubmitGameToday(result.gameType, result.variant)) {
            return false
        }

        // Rule: Termo Clássico and Dueto don't award points in Season 2026
        val isScoringTermo = result.gameType == GameType.TERMO && (result.variant ?: TermoVariant.QUARTETO) == TermoVariant.QUARTETO
        val effectivePoints = if (result.gameType == GameType.TERMO && !isScoringTermo) {
            0
        } else {
            result.score.coerceIn(0, 100)
        }

        val uid = Firebase.auth.currentUser?.uid ?: _currentUser.value.id
        val todayStr = LocalDate.now().toString()
        val effectiveVariant = if (result.gameType == GameType.TERMO) (result.variant ?: TermoVariant.QUARTETO) else null
        val variantSuffix = if (effectiveVariant != null) "_${effectiveVariant.id}" else ""
        val scoreId = "${uid}_${result.gameType.title.lowercase()}${variantSuffix}_$todayStr"

        val historyItem = ScoreHistoryItem(
            id = scoreId,
            userId = uid,
            gameType = result.gameType,
            variant = effectiveVariant,
            dateKey = todayStr,
            score = effectivePoints,
            details = result.details,
            rawGrid = result.rawGrid,
            streak = result.streak
        )

        // Prepend to history
        _scoreHistory.update { listOf(historyItem) + it }

        // Write to Firestore if connected
        db?.let { firestore ->
            val firestoreScore = FirestoreScore(
                userId = uid,
                nickname = _currentUser.value.name,
                avatarUrl = _currentUser.value.avatarUrl,
                gameType = result.gameType.title.lowercase(),
                dateKey = todayStr,
                score = effectivePoints.toLong(),
                rawText = result.rawGrid.joinToString("\n"),
                details = result.details
            )
            firestore.collection("scores").document(scoreId).set(firestoreScore.toMap())
                .addOnFailureListener { e ->
                    handleFirestoreError(e, OperationType.CREATE, "scores/$scoreId")
                }

            if (effectivePoints > 0) {
                val actId = "act_${System.currentTimeMillis()}"
                val firestoreAct = FirestoreActivity(
                    userId = uid,
                    playerName = _currentUser.value.name,
                    avatarUrl = _currentUser.value.avatarUrl,
                    gameName = if (effectiveVariant != null) "${result.gameType.title} (${effectiveVariant.label})" else result.gameType.title,
                    gameLetter = result.gameType.title.first().toString(),
                    pointsEarned = effectivePoints.toLong()
                )
                firestore.collection("activities").document(actId).set(firestoreAct.toMap())
                    .addOnFailureListener { e ->
                        handleFirestoreError(e, OperationType.CREATE, "activities/$actId")
                    }
            }
        }

        // Update daily games status (For Termo, only mark completed if Quarteto)
        _dailyGames.update { games ->
            games.map { g ->
                if (g.gameType == result.gameType) {
                    if (result.gameType == GameType.TERMO) {
                        if (effectiveVariant == TermoVariant.QUARTETO) {
                            g.copy(isCompleted = true, points = effectivePoints)
                        } else g
                    } else {
                        g.copy(isCompleted = true, points = effectivePoints)
                    }
                } else g
            }
        }

        // Update current user points
        if (effectivePoints > 0) {
            _currentUser.update { user ->
                val newToday = (user.todayScore + effectivePoints).coerceAtMost(300)
                val newTotal = user.totalScore + effectivePoints
                user.copy(
                    todayScore = newToday,
                    totalScore = newTotal,
                    matchesPlayed = user.matchesPlayed + 1,
                    streakDays = if (user.streakDays == 0) 1 else user.streakDays
                )
            }
        } else {
            _currentUser.update { user ->
                user.copy(matchesPlayed = user.matchesPlayed + 1)
            }
        }

        // Update in players list
        _players.update { list ->
            val existing = list.find { it.isCurrentUser || it.id == uid }
            val updatedList = if (existing != null) {
                list.map { player ->
                    if (player.id == existing.id) {
                        player.copy(
                            score = player.score + effectivePoints,
                            gamesCount = player.gamesCount + 1,
                            streak = if (player.streak == 0) 1 else player.streak
                        )
                    } else player
                }
            } else {
                list + Player(
                    id = uid,
                    name = "Você (${_currentUser.value.name})",
                    handle = _currentUser.value.handle,
                    avatarUrl = _currentUser.value.avatarUrl,
                    score = _currentUser.value.totalScore,
                    rank = 1,
                    streak = 1,
                    gamesCount = 1,
                    isCurrentUser = true,
                    badge = "VOCÊ"
                )
            }
            updatedList.sortedByDescending { it.score }.mapIndexed { idx, p ->
                p.copy(rank = idx + 1, badge = if (idx == 0) "LÍDER" else if (p.isCurrentUser) "VOCÊ" else null)
            }
        }

        // Add to activities feed
        if (effectivePoints > 0) {
            val newActivity = ActivityFeedItem(
                id = "act-new-${System.currentTimeMillis()}",
                playerName = _currentUser.value.name,
                avatarUrl = _currentUser.value.avatarUrl,
                gameName = result.gameType.title,
                gameLetter = result.gameType.title.first().toString(),
                timeAgo = "agora",
                pointsEarned = effectivePoints,
                fireReactions = 1,
                applauseReactions = 0,
                targetReactions = 0
            )
            _activities.update { listOf(newActivity) + it }
        }

        _hasDailyScores.value = true
        return true
    }

    /**
     * Deletes a score record.
     * If the record is from TODAY, unlocks the game so the user can register again today.
     * If from a PAST DAY, deducts total points without unlocking today's submission.
     */
    fun deleteScore(record: ScoreHistoryItem): Boolean {
        val uid = Firebase.auth.currentUser?.uid ?: _currentUser.value.id
        val todayStr = LocalDate.now().toString()

        // 1. Remove from local history
        _scoreHistory.update { list -> list.filterNot { it.id == record.id } }

        // 2. Delete from Firestore if available
        db?.collection("scores")?.document(record.id)?.delete()
            ?.addOnFailureListener { e ->
                handleFirestoreError(e, OperationType.DELETE, "scores/${record.id}")
            }

        // 3. If today's record: reset daily game and subtract todayScore
        if (record.isToday || record.dateKey == todayStr) {
            _dailyGames.update { list ->
                list.map { g ->
                    if (g.gameType == record.gameType) {
                        g.copy(isCompleted = false, points = 0)
                    } else g
                }
            }
            _currentUser.update { user ->
                val newToday = (user.todayScore - record.score).coerceAtLeast(0)
                val newTotal = (user.totalScore - record.score).coerceAtLeast(0)
                user.copy(
                    todayScore = newToday,
                    totalScore = newTotal,
                    matchesPlayed = (user.matchesPlayed - 1).coerceAtLeast(0)
                )
            }
            // Check if any game is still completed today
            val anyCompletedToday = _dailyGames.value.any { it.isCompleted }
            _hasDailyScores.value = anyCompletedToday
        } else {
            // Past day: only deduct from totalScore
            _currentUser.update { user ->
                val newTotal = (user.totalScore - record.score).coerceAtLeast(0)
                user.copy(
                    totalScore = newTotal,
                    matchesPlayed = (user.matchesPlayed - 1).coerceAtLeast(0)
                )
            }
        }

        // Update in players list
        _players.update { list ->
            list.map { player ->
                if (player.id == uid || player.isCurrentUser) {
                    player.copy(
                        score = (player.score - record.score).coerceAtLeast(0),
                        gamesCount = (player.gamesCount - 1).coerceAtLeast(0)
                    )
                } else player
            }.sortedByDescending { it.score }.mapIndexed { idx, p ->
                p.copy(rank = idx + 1)
            }
        }

        // Update cloud user profile score
        db?.collection("users")?.document(uid)?.update(
            mapOf(
                "totalScore" to _currentUser.value.totalScore.toLong(),
                "matchesPlayed" to _currentUser.value.matchesPlayed.toLong()
            )
        )

        return true
    }

    fun getEvolutionData(gameFilter: String, timeFilter: String): List<WeeklyComparisonDay> {
        val days = when (timeFilter) {
            "Hoje" -> listOf("08h", "11h", "14h", "17h", "20h", "22h", "Agora")
            "Mês" -> listOf("Sem 1", "Sem 2", "Sem 3", "Sem 4")
            "Geral" -> listOf("T. 1", "T. 2", "T. 3", "T. 4", "T. 5", "Atual")
            else -> listOf("Seg", "Ter", "Qua", "Qui", "Sex", "Sáb", "Hoje")
        }

        val multiplier = when (gameFilter) {
            "Termo" -> 0.35f
            "Globle" -> 0.32f
            "SizeItUp" -> 0.33f
            else -> 1.0f
        }

        val userTotal = _currentUser.value.totalScore.toFloat().coerceAtLeast(1f)

        return days.mapIndexed { idx, label ->
            val factor = (idx + 1).toFloat() / days.size.toFloat()
            val userFraction = if (_currentUser.value.totalScore > 0) {
                (factor * multiplier).coerceIn(0.1f, 1.0f)
            } else 0f

            WeeklyComparisonDay(
                dayLabel = label,
                lucasScore = 0f,
                carolScore = 0f,
                guiScore = 0f,
                userScore = userFraction,
                lucasRaw = 0,
                carolRaw = 0,
                guiRaw = 0,
                userRaw = (userFraction * userTotal).toInt()
            )
        }
    }

    fun updateProfile(name: String, bio: String, favGame: GameType, avatarUrl: String? = null): Boolean {
        val trimmedName = name.trim().ifBlank { _currentUser.value.name }
        if (!isNicknameAvailable(trimmedName, _currentUser.value.id)) {
            return false
        }
        _currentUser.update {
            it.copy(
                name = trimmedName,
                bio = bio,
                favoriteGame = favGame,
                avatarUrl = avatarUrl ?: it.avatarUrl
            )
        }
        _players.update { list ->
            list.map {
                if (it.isCurrentUser) {
                    it.copy(
                        name = trimmedName,
                        avatarUrl = avatarUrl ?: it.avatarUrl
                    )
                } else it
            }
        }
        val uid = Firebase.auth.currentUser?.uid
        if (uid != null && db != null) {
            val updates = mutableMapOf<String, Any>(
                "nickname" to trimmedName,
                "bio" to bio,
                "favoriteGame" to favGame.title
            )
            if (avatarUrl != null) {
                updates["avatarUrl"] = avatarUrl
            }
            db.collection("users").document(uid).update(updates)
                .addOnFailureListener { e ->
                    handleFirestoreError(e, OperationType.UPDATE, "users/$uid")
                }
        }
        return true
    }
}
