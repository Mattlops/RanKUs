package com.example.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
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
import com.example.data.models.UserAccount
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate

class RankusRepository(
    private val db: FirebaseFirestore? = null,
    private val context: Context? = null
) {

    constructor(context: Context) : this(
        try {
            FirebaseFirestore.getInstance(
                context.applicationContext.getString(R.string.firestore_database_id)
            )
        } catch (_: Exception) {
            null
        },
        context.applicationContext
    )

    private val prefs: SharedPreferences? = context?.getSharedPreferences("rankus_storage_v2", Context.MODE_PRIVATE)

    // Auth & Active User State
    private val _isLoggedIn = MutableStateFlow(false)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    // Current User Profile State
    private val _currentUser = MutableStateFlow(
        UserProfile(
            id = "gamer_temp",
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

    // Daily Scores State (true when at least 1 score was recorded today)
    private val _hasDailyScores = MutableStateFlow(false)
    val hasDailyScores: StateFlow<Boolean> = _hasDailyScores.asStateFlow()

    // Daily Games Status (Termo, Globle, SizeItUp)
    private val _dailyGames = MutableStateFlow(
        listOf(
            DailyGameStatus(GameType.TERMO, isCompleted = false, points = 0, friendsCount = 0),
            DailyGameStatus(GameType.GLOBLE, isCompleted = false, points = 0, friendsCount = 0),
            DailyGameStatus(GameType.SIZEITUP, isCompleted = false, points = 0, friendsCount = 0)
        )
    )
    val dailyGames: StateFlow<List<DailyGameStatus>> = _dailyGames.asStateFlow()

    // Activity Feed (Recent scores across players)
    private val _activities = MutableStateFlow<List<ActivityFeedItem>>(emptyList())
    val activities: StateFlow<List<ActivityFeedItem>> = _activities.asStateFlow()

    // Leaderboard Players
    private val _players = MutableStateFlow<List<Player>>(emptyList())
    val players: StateFlow<List<Player>> = _players.asStateFlow()

    // User's Score History (Strictly for current logged-in user)
    private val _scoreHistory = MutableStateFlow<List<ScoreHistoryItem>>(emptyList())
    val scoreHistory: StateFlow<List<ScoreHistoryItem>> = _scoreHistory.asStateFlow()

    // Registered Accounts (Local Store + Cloud users)
    private val _registeredAccounts = mutableListOf<UserAccount>()

    // Password Recovery State: Email -> (Code, ExpiryTimestamp)
    private var activeRecoveryCode: String? = null
    private var activeRecoveryEmail: String? = null
    private var recoveryCodeExpiry: Long = 0L

    init {
        loadAccountsFromStorage()
        loadScoresFromStorage()
        attachCloudListeners()

        // Auto restore previous session if available
        val savedUserId = prefs?.getString("active_user_id", null)
        if (!savedUserId.isNullOrBlank()) {
            val account = _registeredAccounts.find { it.id == savedUserId }
            if (account != null) {
                activateUserSession(account)
            }
        }
    }

    // -------------------------------------------------------------
    // ACCOUNT STORAGE & AUTHENTICATION
    // -------------------------------------------------------------

    private fun hashPassword(password: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(password.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun loadAccountsFromStorage() {
        val accountsJson = prefs?.getString("accounts_json", null) ?: return
        try {
            val array = JSONArray(accountsJson)
            _registeredAccounts.clear()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                _registeredAccounts.add(
                    UserAccount(
                        id = obj.getString("id"),
                        nickname = obj.getString("nickname"),
                        email = obj.getString("email"),
                        passwordHash = obj.getString("passwordHash"),
                        avatarUrl = obj.optString("avatarUrl", _currentUser.value.avatarUrl),
                        bio = obj.optString("bio", "Competidor oficial RanKUs"),
                        favoriteGame = try {
                            GameType.valueOf(obj.optString("favoriteGame", "TERMO"))
                        } catch (_: Exception) {
                            GameType.TERMO
                        },
                        createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                    )
                )
            }
        } catch (_: Exception) {
            // Ignore parse error
        }
    }

    private fun saveAccountsToStorage() {
        try {
            val array = JSONArray()
            _registeredAccounts.forEach { acc ->
                val obj = JSONObject()
                obj.put("id", acc.id)
                obj.put("nickname", acc.nickname)
                obj.put("email", acc.email)
                obj.put("passwordHash", acc.passwordHash)
                obj.put("avatarUrl", acc.avatarUrl)
                obj.put("bio", acc.bio)
                obj.put("favoriteGame", acc.favoriteGame.name)
                obj.put("createdAt", acc.createdAt)
                array.put(obj)
            }
            prefs?.edit()?.putString("accounts_json", array.toString())?.apply()
        } catch (_: Exception) {
            // Ignore
        }
    }

    fun isNicknameAvailable(nickname: String, currentUserId: String? = null): Boolean {
        val clean = nickname.trim()
        if (clean.isBlank()) return false
        val myUid = currentUserId ?: if (_isLoggedIn.value) _currentUser.value.id else null

        // Check local accounts
        val takenInLocal = _registeredAccounts.any { acc ->
            acc.id != myUid && acc.nickname.trim().equals(clean, ignoreCase = true)
        }
        if (takenInLocal) return false

        // Check players list
        return _players.value.none { player ->
            player.id != myUid && !player.isCurrentUser && player.name.trim().equals(clean, ignoreCase = true)
        }
    }

    /**
     * Registers a new account with unique nickname, valid email, and secure password.
     */
    fun register(nickname: String, email: String, password: String): Result<UserProfile> {
        val cleanNick = nickname.trim()
        val cleanEmail = email.trim().lowercase()

        if (cleanNick.length < 2) {
            return Result.failure(Exception("O apelido deve ter pelo menos 2 caracteres."))
        }
        if (!isNicknameAvailable(cleanNick)) {
            return Result.failure(Exception("O apelido '$cleanNick' já está em uso por outro usuário."))
        }
        if (!cleanEmail.contains("@") || !cleanEmail.contains(".")) {
            return Result.failure(Exception("Informe um e-mail válido (ex: seu.nome@dominio.com)."))
        }
        if (_registeredAccounts.any { it.email.equals(cleanEmail, ignoreCase = true) }) {
            return Result.failure(Exception("Este e-mail já está cadastrado. Faça login ou recupere sua senha."))
        }
        if (password.length < 6) {
            return Result.failure(Exception("A senha deve conter no mínimo 6 caracteres."))
        }

        val newId = "user_${System.currentTimeMillis()}_${(1000..9999).random()}"
        val hashed = hashPassword(password)
        val defaultAvatar = "https://lh3.googleusercontent.com/aida-public/AB6AXuArX2Naz8deCUE-iK-e43qANrfcLUYgmrDcshFaoSdcgNxv26A0dOXusFi8nsBhQz5jD_wxS_q2Z0DRMIHQWTLWGQLGZUbL6vROHg8445-FoSGtS-c7wP-abafkJGrquQJc3SerLYwlLMZOq1JejFWQwgZlnejCcdhdfN09-qc3lONtDKzOtFgUNE7CDOI8eHMmFCQ0fx5g5FWB_mTzCWVA4nHbC6dF26RexBB-aoxC2JwR2vjRwT2naQ"

        val account = UserAccount(
            id = newId,
            nickname = cleanNick,
            email = cleanEmail,
            passwordHash = hashed,
            avatarUrl = defaultAvatar,
            bio = "Competidor oficial RanKUs",
            favoriteGame = GameType.TERMO
        )

        _registeredAccounts.add(account)
        saveAccountsToStorage()

        // Sync new user to Firestore
        db?.collection("users")?.document(newId)?.set(
            FirestoreUser(
                userId = newId,
                nickname = cleanNick,
                email = cleanEmail,
                bio = account.bio,
                avatarUrl = defaultAvatar
            ).toMap()
        )?.addOnFailureListener { e ->
            handleFirestoreError(e, OperationType.CREATE, "users/$newId")
        }

        activateUserSession(account)
        return Result.success(_currentUser.value)
    }

    /**
     * Authenticates with Email or Nickname and Password.
     * Prevents login with non-existent users or wrong credentials.
     */
    fun login(emailOrUser: String, password: String): Result<UserProfile> {
        val cleanInput = emailOrUser.trim()
        if (cleanInput.isBlank()) {
            return Result.failure(Exception("Digite seu e-mail ou apelido."))
        }
        if (password.isBlank()) {
            return Result.failure(Exception("Digite sua senha."))
        }

        val account = _registeredAccounts.find { acc ->
            acc.email.equals(cleanInput, ignoreCase = true) ||
                acc.nickname.equals(cleanInput, ignoreCase = true)
        }

        if (account == null) {
            return Result.failure(Exception("Usuário ou e-mail não cadastrado. Verifique os dados ou crie uma conta."))
        }

        val hashed = hashPassword(password)
        if (account.passwordHash != hashed) {
            return Result.failure(Exception("Senha incorreta. Tente novamente ou use 'Esqueci minha senha'."))
        }

        activateUserSession(account)
        return Result.success(_currentUser.value)
    }

    private fun activateUserSession(account: UserAccount) {
        prefs?.edit()?.putString("active_user_id", account.id)?.apply()

        _currentUser.update {
            it.copy(
                id = account.id,
                name = account.nickname,
                handle = account.email,
                bio = account.bio,
                avatarUrl = account.avatarUrl,
                favoriteGame = account.favoriteGame
            )
        }
        _isLoggedIn.value = true

        // Refresh scores for this active user
        refreshActiveUserScores(account.id)
        syncScoresFromCloud(account.id)
    }

    fun onGoogleSignInSuccess(displayName: String) {
        val user = Firebase.auth.currentUser
        val uid = user?.uid ?: "google_${System.currentTimeMillis()}"
        val email = user?.email ?: "$displayName@gmail.com"
        val existing = _registeredAccounts.find { it.id == uid || it.email.equals(email, ignoreCase = true) }
        val account = existing ?: UserAccount(
            id = uid,
            nickname = displayName,
            email = email,
            passwordHash = "",
            bio = "Competidor oficial RanKUs via Google"
        ).also {
            _registeredAccounts.add(it)
            saveAccountsToStorage()
        }
        activateUserSession(account)
    }

    fun logout() {
        Firebase.auth.signOut()
        prefs?.edit()?.remove("active_user_id")?.apply()
        _isLoggedIn.value = false
        _hasDailyScores.value = false
        _dailyGames.update { list -> list.map { it.copy(isCompleted = false, points = 0) } }
        _scoreHistory.value = emptyList()
    }

    // -------------------------------------------------------------
    // FORGOT PASSWORD FLOW
    // -------------------------------------------------------------

    /**
     * Step 1: Generates a 6-digit random code for registered email.
     */
    fun sendRecoveryCode(email: String): Result<String> {
        val cleanEmail = email.trim().lowercase()
        val account = _registeredAccounts.find { it.email.equals(cleanEmail, ignoreCase = true) }
        if (account == null) {
            return Result.failure(Exception("E-mail não encontrado no sistema. Verifique o endereço digitado."))
        }

        val code = (100000..999999).random().toString()
        activeRecoveryCode = code
        activeRecoveryEmail = cleanEmail
        recoveryCodeExpiry = System.currentTimeMillis() + 15 * 60 * 1000 // 15 minutes

        return Result.success(code)
    }

    /**
     * Step 2: Validates the 6-digit code.
     */
    fun verifyRecoveryCode(email: String, code: String): Boolean {
        val cleanEmail = email.trim().lowercase()
        val cleanCode = code.trim()

        if (activeRecoveryCode == null || activeRecoveryEmail == null) return false
        if (System.currentTimeMillis() > recoveryCodeExpiry) return false
        return activeRecoveryEmail == cleanEmail && activeRecoveryCode == cleanCode
    }

    /**
     * Step 3: Replaces user password with the new one.
     */
    fun resetPassword(email: String, newPassword: String): Result<Unit> {
        val cleanEmail = email.trim().lowercase()
        if (newPassword.length < 6) {
            return Result.failure(Exception("A nova senha deve conter no mínimo 6 caracteres."))
        }

        val index = _registeredAccounts.indexOfFirst { it.email.equals(cleanEmail, ignoreCase = true) }
        if (index == -1) {
            return Result.failure(Exception("Conta não encontrada."))
        }

        val newHashed = hashPassword(newPassword)
        val updated = _registeredAccounts[index].copy(passwordHash = newHashed)
        _registeredAccounts[index] = updated
        saveAccountsToStorage()

        activeRecoveryCode = null
        activeRecoveryEmail = null
        return Result.success(Unit)
    }

    // -------------------------------------------------------------
    // AVATAR IMAGE PERSISTENCE IN APP INTERNAL STORAGE
    // -------------------------------------------------------------

    /**
     * Copies a chosen image URI into the app's internal filesDir permanently.
     * This ensures the avatar NEVER disappears upon closing and reopening the app.
     */
    fun saveAvatarLocally(uri: Uri): String {
        if (context == null) return uri.toString()
        return try {
            val avatarsDir = File(context.filesDir, "avatars").apply { mkdirs() }
            val destFile = File(avatarsDir, "avatar_${_currentUser.value.id}.jpg")

            context.contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            Uri.fromFile(destFile).toString()
        } catch (_: Exception) {
            uri.toString()
        }
    }

    // -------------------------------------------------------------
    // SCORES & STREAK MANAGEMENT
    // -------------------------------------------------------------

    private fun loadScoresFromStorage() {
        val scoresJson = prefs?.getString("scores_json", null) ?: return
        try {
            val array = JSONArray(scoresJson)
            val list = mutableListOf<ScoreHistoryItem>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val gType = try { GameType.valueOf(obj.getString("gameType")) } catch (_: Exception) { GameType.TERMO }
                val variant = try {
                    if (obj.has("variant")) TermoVariant.valueOf(obj.getString("variant")) else null
                } catch (_: Exception) { null }

                list.add(
                    ScoreHistoryItem(
                        id = obj.getString("id"),
                        userId = obj.getString("userId"),
                        gameType = gType,
                        variant = variant,
                        dateKey = obj.getString("dateKey"),
                        score = obj.getInt("score"),
                        details = obj.optString("details", ""),
                        rawGrid = try {
                            val gridArr = obj.getJSONArray("rawGrid")
                            List(gridArr.length()) { idx -> gridArr.getString(idx) }
                        } catch (_: Exception) { emptyList() },
                        streak = obj.optInt("streak", 0).let { if (it > 0) it else null },
                        timestampMs = obj.optLong("timestampMs", System.currentTimeMillis())
                    )
                )
            }
            _scoreHistory.value = list
        } catch (_: Exception) {
            // Ignore
        }
    }

    private fun saveScoresToStorage() {
        try {
            val array = JSONArray()
            _scoreHistory.value.forEach { item ->
                val obj = JSONObject()
                obj.put("id", item.id)
                obj.put("userId", item.userId)
                obj.put("gameType", item.gameType.name)
                if (item.variant != null) obj.put("variant", item.variant.name)
                obj.put("dateKey", item.dateKey)
                obj.put("score", item.score)
                obj.put("details", item.details)
                obj.put("timestampMs", item.timestampMs)
                if (item.streak != null) obj.put("streak", item.streak)
                val gridArr = JSONArray()
                item.rawGrid.forEach { gridArr.put(it) }
                obj.put("rawGrid", gridArr)
                array.put(obj)
            }
            prefs?.edit()?.putString("scores_json", array.toString())?.apply()
        } catch (_: Exception) {
            // Ignore
        }
    }

    /**
     * Strict streak calculation:
     * - Counts consecutive days backwards.
     * - If yesterday was missed and today hasn't been played, streak is 0.
     * - If yesterday was missed and today was played, streak is 1.
     */
    private fun calculateConsecutiveStreak(userScores: List<ScoreHistoryItem>): Int {
        val distinctDates = userScores.map { it.dateKey }.distinct().toSet()
        val today = LocalDate.now()
        val todayStr = today.toString()
        val yesterdayStr = today.minusDays(1).toString()

        return if (distinctDates.contains(todayStr)) {
            var streak = 1
            var checkDate = today.minusDays(1)
            while (distinctDates.contains(checkDate.toString())) {
                streak++
                checkDate = checkDate.minusDays(1)
            }
            streak
        } else if (distinctDates.contains(yesterdayStr)) {
            var streak = 1
            var checkDate = today.minusDays(2)
            while (distinctDates.contains(checkDate.toString())) {
                streak++
                checkDate = checkDate.minusDays(1)
            }
            streak
        } else {
            0
        }
    }

    private fun refreshActiveUserScores(uid: String) {
        val userScores = _scoreHistory.value.filter { it.userId == uid }
        val todayStr = LocalDate.now().toString()
        val todayScores = userScores.filter { it.dateKey == todayStr }

        val totalPoints = userScores.sumOf { it.score }
        val todayPoints = todayScores.sumOf { it.score }
        val streak = calculateConsecutiveStreak(userScores)

        _hasDailyScores.value = todayScores.isNotEmpty()

        _dailyGames.update { list ->
            list.map { g ->
                val record = todayScores.find { it.gameType == g.gameType }
                if (record != null) {
                    g.copy(isCompleted = true, points = record.score)
                } else {
                    g.copy(isCompleted = false, points = 0)
                }
            }
        }

        _currentUser.update {
            it.copy(
                totalScore = totalPoints,
                todayScore = todayPoints,
                streakDays = streak,
                matchesPlayed = userScores.size
            )
        }

        rebuildPlayersLeaderboard()
    }

    fun canSubmitGameToday(gameType: GameType, variant: TermoVariant? = null): Boolean {
        val uid = _currentUser.value.id
        val todayStr = LocalDate.now().toString()
        val userScores = _scoreHistory.value.filter { it.userId == uid && it.dateKey == todayStr }

        return if (gameType == GameType.TERMO) {
            val targetVariant = variant ?: TermoVariant.QUARTETO
            val alreadySubmitted = userScores.any {
                it.gameType == GameType.TERMO && (it.variant ?: TermoVariant.QUARTETO) == targetVariant
            }
            !alreadySubmitted
        } else {
            val alreadySubmitted = userScores.any { it.gameType == gameType }
            !alreadySubmitted
        }
    }

    fun submitScore(result: ParsedScoreResult): Boolean {
        if (!canSubmitGameToday(result.gameType, result.variant)) {
            return false
        }

        // Rule: Termo Clássico and Dueto don't award points in Season 2026, only Quarteto
        val isScoringTermo = result.gameType == GameType.TERMO && (result.variant ?: TermoVariant.QUARTETO) == TermoVariant.QUARTETO
        val effectivePoints = if (result.gameType == GameType.TERMO && !isScoringTermo) {
            0
        } else {
            result.score.coerceIn(0, 100)
        }

        val uid = _currentUser.value.id
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

        // Prepend to history & save locally
        _scoreHistory.update { listOf(historyItem) + it.filterNot { item -> item.id == scoreId } }
        saveScoresToStorage()

        // Write score to Firestore
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

        // Recalculate user totals directly from scores (the single source of truth!)
        refreshActiveUserScores(uid)

        // Update totalScore in Firestore users/$uid
        db?.collection("users")?.document(uid)?.update(
            mapOf(
                "totalScore" to _currentUser.value.totalScore.toLong(),
                "streakDays" to _currentUser.value.streakDays.toLong(),
                "matchesPlayed" to _currentUser.value.matchesPlayed.toLong()
            )
        )

        return true
    }

    fun deleteScore(record: ScoreHistoryItem): Boolean {
        val uid = _currentUser.value.id
        if (record.userId != uid) return false

        _scoreHistory.update { list -> list.filterNot { it.id == record.id } }
        saveScoresToStorage()

        db?.collection("scores")?.document(record.id)?.delete()
            ?.addOnFailureListener { e ->
                handleFirestoreError(e, OperationType.DELETE, "scores/${record.id}")
            }

        refreshActiveUserScores(uid)

        db?.collection("users")?.document(uid)?.update(
            mapOf(
                "totalScore" to _currentUser.value.totalScore.toLong(),
                "streakDays" to _currentUser.value.streakDays.toLong(),
                "matchesPlayed" to _currentUser.value.matchesPlayed.toLong()
            )
        )

        return true
    }

    // -------------------------------------------------------------
    // RANKINGS FILTERING & LEADERBOARD
    // -------------------------------------------------------------

    private fun rebuildPlayersLeaderboard() {
        val allUsers = _registeredAccounts.associateBy { it.id }
        val currentUid = _currentUser.value.id

        // Compute totalScore per user from score history
        val userTotals = mutableMapOf<String, Int>()
        _scoreHistory.value.forEach { item ->
            userTotals[item.userId] = (userTotals[item.userId] ?: 0) + item.score
        }

        val playersList = mutableListOf<Player>()

        // 1. Current user
        val myUser = _currentUser.value
        val myStreak = myUser.streakDays
        val myMatches = myUser.matchesPlayed
        playersList.add(
            Player(
                id = currentUid,
                name = myUser.name,
                handle = myUser.handle,
                avatarUrl = myUser.avatarUrl,
                score = myUser.totalScore,
                rank = 1,
                streak = myStreak,
                gamesCount = myMatches,
                isCurrentUser = true,
                badge = "VOCÊ"
            )
        )

        // 2. Other registered users
        allUsers.values.filterNot { it.id == currentUid }.forEach { acc ->
            val accScores = _scoreHistory.value.filter { it.userId == acc.id }
            val accStreak = calculateConsecutiveStreak(accScores)
            playersList.add(
                Player(
                    id = acc.id,
                    name = acc.nickname,
                    handle = acc.email,
                    avatarUrl = acc.avatarUrl,
                    score = userTotals[acc.id] ?: 0,
                    rank = 2,
                    streak = accStreak,
                    gamesCount = accScores.size,
                    isCurrentUser = false
                )
            )
        }

        _players.value = playersList.sortedByDescending { it.score }.mapIndexed { idx, p ->
            p.copy(
                rank = idx + 1,
                badge = if (idx == 0 && p.score > 0) "LÍDER" else if (p.isCurrentUser) "VOCÊ" else null
            )
        }
    }

    /**
     * Filters players dynamically based on selected Game pill and Time range.
     */
    fun getRankingPlayers(gameFilter: String, timeFilter: String): List<Player> {
        val today = LocalDate.now()
        val todayStr = today.toString()
        val weekStartStr = today.minusDays(6).toString()
        val monthStartStr = today.minusDays(29).toString()

        val matchingScores = _scoreHistory.value.filter { item ->
            val matchGame = when (gameFilter) {
                "Termo" -> item.gameType == GameType.TERMO
                "Globle" -> item.gameType == GameType.GLOBLE
                "SizeItUp" -> item.gameType == GameType.SIZEITUP
                else -> true
            }
            val matchTime = when (timeFilter) {
                "Hoje" -> item.dateKey == todayStr
                "Semana" -> item.dateKey >= weekStartStr
                "Mês" -> item.dateKey >= monthStartStr
                else -> true
            }
            matchGame && matchTime
        }

        val scoresByUser = matchingScores.groupBy { it.userId }
        val allCompetitors = _players.value

        return allCompetitors.map { player ->
            val userRecords = scoresByUser[player.id] ?: emptyList()
            val filteredScore = userRecords.sumOf { it.score }
            val games = userRecords.size
            player.copy(
                score = filteredScore,
                gamesCount = games
            )
        }.sortedByDescending { it.score }.mapIndexed { idx, p ->
            p.copy(
                rank = idx + 1,
                badge = if (idx == 0 && p.score > 0) "LÍDER" else if (p.isCurrentUser) "VOCÊ" else null
            )
        }
    }

    fun getEvolutionData(gameFilter: String, timeFilter: String): List<WeeklyComparisonDay> {
        val today = LocalDate.now()
        val todayStr = today.toString()
        val currentUid = _currentUser.value.id

        val userFilteredScores = _scoreHistory.value.filter { item ->
            item.userId == currentUid && when (gameFilter) {
                "Termo" -> item.gameType == GameType.TERMO
                "Globle" -> item.gameType == GameType.GLOBLE
                "SizeItUp" -> item.gameType == GameType.SIZEITUP
                else -> true
            }
        }

        val totalUserPoints = userFilteredScores.sumOf { it.score }.coerceAtLeast(1)

        return when (timeFilter) {
            "Hoje" -> {
                val slots = listOf("08h", "11h", "14h", "17h", "20h", "22h", "Agora")
                val todayScores = userFilteredScores.filter { it.dateKey == todayStr }
                val sumToday = todayScores.sumOf { it.score }
                slots.mapIndexed { idx, label ->
                    val fraction = if (sumToday > 0) ((idx + 1).toFloat() / slots.size).coerceIn(0.1f, 1f) else 0f
                    val raw = (fraction * sumToday).toInt()
                    WeeklyComparisonDay(
                        dayLabel = label,
                        userScore = fraction,
                        userRaw = raw
                    )
                }
            }
            "Mês" -> {
                val weeks = listOf("Sem 1", "Sem 2", "Sem 3", "Sem 4")
                weeks.mapIndexed { idx, label ->
                    val daysBackStart = (3 - idx) * 7
                    val daysBackEnd = daysBackStart - 7
                    val start = today.minusDays(daysBackStart.toLong()).toString()
                    val end = today.minusDays(daysBackEnd.coerceAtLeast(0).toLong()).toString()
                    val weekPts = userFilteredScores.filter { it.dateKey in start..end }.sumOf { it.score }
                    val fraction = if (totalUserPoints > 0) (weekPts.toFloat() / 300f).coerceIn(0f, 1f) else 0f
                    WeeklyComparisonDay(
                        dayLabel = label,
                        userScore = fraction,
                        userRaw = weekPts
                    )
                }
            }
            "Geral" -> {
                val seasons = listOf("Ciclo 1", "Ciclo 2", "Ciclo 3", "Ciclo 4", "Ciclo 5", "Atual")
                seasons.mapIndexed { idx, label ->
                    val fraction = if (_currentUser.value.totalScore > 0) ((idx + 1).toFloat() / seasons.size) else 0f
                    WeeklyComparisonDay(
                        dayLabel = label,
                        userScore = fraction,
                        userRaw = (fraction * _currentUser.value.totalScore).toInt()
                    )
                }
            }
            else -> {
                // "Semana" (Last 7 days: Seg, Ter, Qua, Qui, Sex, Sáb, Hoje)
                val dayNames = listOf("Seg", "Ter", "Qua", "Qui", "Sex", "Sáb", "Hoje")
                dayNames.mapIndexed { idx, label ->
                    val targetDate = today.minusDays((6 - idx).toLong()).toString()
                    val dayPoints = userFilteredScores.filter { it.dateKey == targetDate }.sumOf { it.score }
                    val fraction = if (dayPoints > 0) (dayPoints.toFloat() / 100f).coerceIn(0.1f, 1f) else 0f
                    WeeklyComparisonDay(
                        dayLabel = label,
                        userScore = fraction,
                        userRaw = dayPoints
                    )
                }
            }
        }
    }

    // -------------------------------------------------------------
    // PROFILE EDITING
    // -------------------------------------------------------------

    fun updateProfile(name: String, bio: String, favGame: GameType, avatarUrl: String? = null): Boolean {
        val trimmedName = name.trim().ifBlank { _currentUser.value.name }
        if (!isNicknameAvailable(trimmedName, _currentUser.value.id)) {
            return false
        }
        val effectiveAvatar = avatarUrl ?: _currentUser.value.avatarUrl

        _currentUser.update {
            it.copy(
                name = trimmedName,
                bio = bio,
                favoriteGame = favGame,
                avatarUrl = effectiveAvatar
            )
        }

        // Update local registered accounts
        val idx = _registeredAccounts.indexOfFirst { it.id == _currentUser.value.id }
        if (idx != -1) {
            _registeredAccounts[idx] = _registeredAccounts[idx].copy(
                nickname = trimmedName,
                bio = bio,
                favoriteGame = favGame,
                avatarUrl = effectiveAvatar
            )
            saveAccountsToStorage()
        }

        rebuildPlayersLeaderboard()

        // Sync with Firestore
        val uid = _currentUser.value.id
        db?.collection("users")?.document(uid)?.update(
            mapOf(
                "nickname" to trimmedName,
                "bio" to bio,
                "favoriteGame" to favGame.title,
                "avatarUrl" to effectiveAvatar
            )
        )?.addOnFailureListener { e ->
            handleFirestoreError(e, OperationType.UPDATE, "users/$uid")
        }

        return true
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
    }

    private fun syncScoresFromCloud(uid: String) {
        db?.collection("scores")
            ?.whereEqualTo("userId", uid)
            ?.get()
            ?.addOnSuccessListener { snapshot ->
                if (snapshot != null && !snapshot.isEmpty) {
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
                    }

                    // Merge with existing local scores
                    val currentList = _scoreHistory.value.filterNot { it.userId == uid }
                    _scoreHistory.value = (cloudScores + currentList).sortedByDescending { it.dateKey }
                    saveScoresToStorage()
                    refreshActiveUserScores(uid)
                }
            }
    }
}
