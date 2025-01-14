package com.github.scroogemcfawk.mastodon.client

import com.github.scroogemcfawk.util.IDebuggable
import com.github.scroogemcfawk.mastodon.storage.IStorage
import com.github.scroogemcfawk.mastodon.storage.SimpleStorage
import social.bigbone.MastodonClient
import social.bigbone.MastodonRequest
import social.bigbone.api.Pageable
import social.bigbone.api.Scope
import social.bigbone.api.entity.Account
import social.bigbone.api.entity.Application
import social.bigbone.api.entity.Status
import social.bigbone.api.entity.Token
import social.bigbone.api.exception.BigBoneRequestException
import java.io.File
import java.time.Duration
import java.time.Instant

class Mastodon(
    val hostname: String,
    override val debug: Boolean = false,
    override val timer: Boolean = true
) : IMastodon, IDebuggable {

    // generic parameters
    companion object {
        private const val NO_REDIRECT = "urn:ietf:wg:oauth:2.0:oob"
        private val FULL_SCOPE = Scope(Scope.READ.ALL, Scope.WRITE.ALL, Scope.PUSH.ALL)
        private const val CLIENT_NAME = "LUWRAIN Mastodon Client"
    }

    private val forceRequests = true

    private lateinit var client: MastodonClient
    private lateinit var application: Application
    // if produced by an app -> request token
    // if produced by a user -> access token
    private var requestToken: Token? = null
    private var accessToken: Token? = null

    // current user login or null
    private var login: String? = null
    private var userSeenTheRules: Boolean = false

    private val storage: IStorage = SimpleStorage(File(".mastodon.json"), debug)

    init {
        deb("Initializing Mastodon")
        initClient()
        initApplication()
        deb("Initialized Mastodon")
    }

    private fun initClient() {
        client = MastodonClient.Builder(hostname).build()
        deb("Initialized client: hostname=$hostname")
    }

    private fun authorizeClient(accessToken: Token? = null) {
        val token = accessToken ?: getRequestToken()
        client = MastodonClient.Builder(hostname).accessToken(token.accessToken).build()
        deb("Authorized client: hostname=$hostname, token=$token")
    }

    private fun getRequestToken(): Token {
        requestToken = client.oauth.getAccessTokenWithClientCredentialsGrant(application.clientId!!, application.clientSecret!!, NO_REDIRECT, FULL_SCOPE).executeOrDescribe()
        storage.saveRequestToken(application.clientId!!, requestToken!!)
        return requestToken!!
    }

    private fun ensureLogin() {
        if (login == null) {
            throw IllegalStateException("User is not logged in.")
        }
    }

    private fun checkStatusText(text: String) {
        when {
            text.isEmpty() -> throw IllegalArgumentException("Text is too short. (0 / 1000)")
            text.length > 1000 -> throw IllegalArgumentException("Text is too long. (${text.length} / 1000)")
        }
    }

    fun verifyAppCred(): Application {
        if (requestToken == null) {
            throw IllegalStateException("Application is not authorized.")
        }
        return client.apps.verifyCredentials().executeOrDescribe()
    }

    private fun initApplication() {
        // todo add secret storage
        deb("Initializing application: name=$CLIENT_NAME, redirect=$NO_REDIRECT, scope=$FULL_SCOPE")
        try {
            if (forceRequests || tryInitApplicationFromStorage() == null) {
                application = client.apps.createApp(
                    CLIENT_NAME,
                    NO_REDIRECT,
                    null,
                    FULL_SCOPE
                ).executeOrDescribe()
                storage.saveApplication(hostname, application)
            }
        } catch (e: BigBoneRequestException) {
            System.err.println("Application initialization failed. Status code: ${e.httpStatusCode}")
        }
        deb("Initialized application: id=${application.clientId}, secret=${application.clientSecret}")
    }

    private fun tryInitApplicationFromStorage(): Application? {
        storage.getApplication(hostname)?.let {
            application = it
            deb("Initialized application from storage.")
            return it
        }
        return null
    }

    private fun tryInitClientFromStorage(clientId: String, username: String): Token? {
        storage.getAccessToken(clientId, username)?.let {
            accessToken = it
            deb("Initialized access token from storage.")
            return it
        }
        return null
    }

    override fun getRules(): String {
        userSeenTheRules = true
        return client.instances.getRules().executeOrDescribe().joinToString("\n")
    }

    override fun register(username: String, email: String, password: String, agreement: Boolean, locale: String, autologin: Boolean, reason: String?) {
        deb("Register user: username=$username email=$email password=$password agreement=$agreement")
        if (!userSeenTheRules) {
            throw IllegalStateException("User has not seen the rules yet. Call getRules() and show rules to the user first.")
        }
        if (requestToken == null) {
            authorizeClient()
        }
        try {
            accessToken = client.accounts.registerAccount(
                username,
                email,
                password,
                agreement,
                "en-US",
                null
            ).executeOrDescribe()
            storage.saveAccessToken(application.clientId!!, username, accessToken!!)
            deb("User has been registered.")
            if (autologin) {
                deb("Auto-login.")
                login(username, password)
            }
        } catch (e: BigBoneRequestException) {
            System.err.println("User register failed. Status code: ${e.httpStatusCode}")
        }
    }

    override fun login(username: String, password: String) {
        if (application.clientId == null || application.clientSecret == null) {
            throw IllegalStateException("Application is not initialized.")
        }
        try {
            if (tryInitClientFromStorage(application.clientId!!, username) == null) {
                accessToken = client.oauth.getUserAccessTokenWithPasswordGrant(
                    application.clientId!!,
                    application.clientSecret!!,
                    NO_REDIRECT,
                    username,
                    password,
                    FULL_SCOPE
                ).executeOrDescribe()
                storage.saveAccessToken(application.clientId!!, username, accessToken!!)
            }
            login = username
            deb("Logged in as: $login")
            // reinit client with user's access token
            authorizeClient(accessToken)
        } catch (e: BigBoneRequestException) {
            System.err.println("User login failed. Status code: ${e.httpStatusCode}")
        }
    }

    override fun logout() {
        login = null
        accessToken = null
    }

    override fun getHomeTimeline(): Pageable<Status> {
        ensureLogin()
        return client.timelines.getHomeTimeline().executeOrDescribe()
    }

    override fun getPublicTimeline(): Pageable<Status> {
        return client.timelines.getPublicTimeline().executeOrDescribe()
    }

    override fun searchUser(query: String): List<Account> {
        ensureLogin()
        val users = ArrayList<Account>()
        users.addAll(
            client.accounts.searchAccounts(query = query, limit = 5).executeOrDescribe()
        )
        return users
    }

    override fun getUserByUsername(username: String, hostname: String?): Account? {
        ensureLogin()
        val query = "${username.replace("@", "")}@${hostname?.replace("@", "") ?: this.hostname}"
        val result = client.accounts.searchAccounts(query = query, limit = 20).executeOrDescribe()
        return if (result.isEmpty()) null else result[0]
    }


    override fun getMe(): Account {
        ensureLogin()
        return client.accounts.verifyCredentials().executeOrDescribe()
    }

    override fun postStatus(text: String) {
        ensureLogin()
        checkStatusText(text)
        client.statuses.postStatus(text).executeOrDescribe()
    }

    override fun scheduleStatusAfterDelay(text: String, delayPattern: String) {
        ensureLogin()
        checkStatusText(text)
        val now = Instant.now()
        val scheduled = now.plusSeconds(Duration.parse(delayPattern).seconds)
        deb("Status is scheduled at: $scheduled")
        client.statuses.scheduleStatus(text, scheduledAt = scheduled).executeOrDescribe()
    }
}

private inline fun <reified T> MastodonRequest<T>.executeOrDescribe(): T {
    try {
        return this.execute()
    } catch (e: BigBoneRequestException) {
        System.err.println(this.javaClass.simpleName + "<${T::class.java.simpleName}>" + " failed: " + e.httpStatusCode)
        throw e
    }
}
