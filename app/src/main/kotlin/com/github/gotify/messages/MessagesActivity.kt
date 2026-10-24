package com.github.gotify.messages

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.core.net.toUri
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout.SimpleDrawerListener
import androidx.lifecycle.ViewModelProvider
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.request.ImageRequest
import com.github.gotify.BuildConfig
import com.github.gotify.CoilInstance
import com.github.gotify.MissedMessageUtil
import com.github.gotify.R
import com.github.gotify.Settings
import com.github.gotify.Utils
import com.github.gotify.Utils.launchCoroutine
import com.github.gotify.api.Api
import com.github.gotify.api.ApiException
import com.github.gotify.api.Callback
import com.github.gotify.api.ClientFactory
import com.github.gotify.client.ApiClient
import com.github.gotify.client.api.AuthApi
import com.github.gotify.client.api.ClientApi
import com.github.gotify.client.api.MessageApi
import com.github.gotify.client.model.Application
import com.github.gotify.client.model.Message
import com.github.gotify.databinding.ActivityMessagesBinding
import com.github.gotify.fork.EndpointResolver
import com.github.gotify.fork.ServerAddressesDialog
import com.github.gotify.fork.normalizeEndpoint
import com.github.gotify.init.InitializationActivity
import com.github.gotify.log.LogsActivity
import com.github.gotify.login.LoginActivity
import com.github.gotify.messages.provider.MessageState
import com.github.gotify.messages.provider.MessageWithImage
import com.github.gotify.service.WebSocketService
import com.github.gotify.settings.SettingsActivity
import com.github.gotify.sharing.ShareActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.navigation.NavigationView
import com.google.android.material.snackbar.BaseTransientBottomBar.BaseCallback
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tinylog.kotlin.Logger

internal class MessagesActivity :
    AppCompatActivity(),
    NavigationView.OnNavigationItemSelectedListener {
    private lateinit var binding: ActivityMessagesBinding
    private lateinit var viewModel: MessagesModel
    private var isLoadMore = false
    private var updateAppOnDrawerClose: Long? = null
    private var offlineNotified = false // FORK
    private var sessionDialogShown = false // FORK
    private var receiverRegistered = false // FORK
    private var networkCallbackRegistered = false // FORK
    private var reloadRequested = false // FORK
    private lateinit var listMessageAdapter: ListMessageAdapter
    private lateinit var onBackPressedCallback: OnBackPressedCallback

    // FORK: turning Wi-Fi off does not always trigger a websocket failure immediately. React to the
    // network going away here as well, so the endpoint (and the pinned Retrofit client) is
    // re-evaluated without waiting for the next failed request.
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(network: Network) {
            super.onLost(network)
            EndpointResolver.onNetworkChanged()
        }
    }

    private val receiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // FORK: the selected endpoint changed (network switch or manual selection).
            if (intent.action == EndpointResolver.ENDPOINT_CHANGED_BROADCAST) {
                updateHeaderEndpoint()
                refreshFromNetwork()
                return
            }
            // FORK: refresh the list as soon as the websocket is connected again
            if (intent.action == WebSocketService.CONNECTED_BROADCAST) {
                refreshFromNetwork()
                return
            }
            val messageJson = intent.getStringExtra("message") ?: return
            val message = Utils.JSON.fromJson(
                messageJson,
                Message::class.java
            )
            launchCoroutine {
                addSingleMessage(message)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // FORK: never build a Retrofit client (below) with a missing/invalid address: it throws
        // and kills the process. Send the user back to the login screen instead.
        val startupSettings = Settings(this)
        if (!startupSettings.tokenExists() || !startupSettings.hasValidUrl) {
            Logger.warn("No valid session/URL, returning to login")
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        binding = ActivityMessagesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        viewModel = ViewModelProvider(this, MessagesModelFactory(this))[MessagesModel::class.java]
        Logger.info("Entering " + javaClass.simpleName)
        initDrawer()

        val layoutManager = LinearLayoutManager(this)
        val messagesView: RecyclerView = binding.messagesView
        val dividerItemDecoration = DividerItemDecoration(
            messagesView.context,
            layoutManager.orientation
        )
        listMessageAdapter = ListMessageAdapter(
            this,
            viewModel.settings,
            CoilInstance.get(this)
        ) { message ->
            scheduleDeletion(message)
        }
        addBackPressCallback()

        messagesView.addItemDecoration(dividerItemDecoration)
        messagesView.setHasFixedSize(true)
        messagesView.layoutManager = layoutManager
        messagesView.addOnScrollListener(MessageListOnScrollListener())
        messagesView.adapter = listMessageAdapter

        val appsHolder = viewModel.appsHolder
        appsHolder.onUpdate { onUpdateApps(appsHolder.get()) }
        if (appsHolder.wasRequested()) onUpdateApps(appsHolder.get()) else appsHolder.request()

        val itemTouchHelper = ItemTouchHelper(SwipeToDeleteCallback(listMessageAdapter))
        itemTouchHelper.attachToRecyclerView(messagesView)

        val swipeRefreshLayout = binding.swipeRefresh
        swipeRefreshLayout.setOnRefreshListener { onRefresh() }
        binding.drawerLayout.addDrawerListener(
            object : SimpleDrawerListener() {
                override fun onDrawerOpened(drawerView: View) {
                    onBackPressedCallback.isEnabled = true
                }

                override fun onDrawerClosed(drawerView: View) {
                    updateAppOnDrawerClose?.let { selectApp ->
                        updateAppOnDrawerClose = null
                        viewModel.appId = selectApp
                        launchCoroutine {
                            updateMessagesForApplication(true, selectApp)
                        }
                        invalidateOptionsMenu()
                    }
                    onBackPressedCallback.isEnabled = false
                }
            }
        )

        swipeRefreshLayout.isEnabled = false
        messagesView
            .viewTreeObserver
            .addOnScrollChangedListener {
                val topChild = messagesView.getChildAt(0)
                if (topChild != null) {
                    swipeRefreshLayout.isEnabled = topChild.top == 0
                } else {
                    swipeRefreshLayout.isEnabled = true
                }
            }

        val excludeFromRecent = PreferenceManager.getDefaultSharedPreferences(this)
            .getBoolean(getString(R.string.setting_key_exclude_from_recent), false)
        Utils.setExcludeFromRecent(this, excludeFromRecent)
        launchCoroutine {
            try {
                // FORK: never block the first screen on the network. Show the cached messages
                // right away, then refresh from the server in the background.
                withContext(Dispatchers.Main) {
                    if (!isFinishing && !isDestroyed) startLoading()
                }
                val cached = viewModel.messages.hydrateFromCache(viewModel.appId)
                if (cached.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        updateMessagesAndStopLoading(cached)
                    }
                }
                updateMessagesForApplication(cached.isEmpty(), viewModel.appId)
            } catch (e: Exception) {
                Logger.error(e, "Could not load the initial message list")
                withContext(Dispatchers.Main) {
                    updateMessagesAndStopLoading(viewModel.messages[viewModel.appId])
                }
            }
        }
        validateSession() // FORK
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        // FORK: onCreate may have finished early without inflating the layout.
        if (!::binding.isInitialized) return
        binding.learnGotify.setOnClickListener { openDocumentation() }
    }

    private fun refreshAll() {
        CoilInstance.evict(this)
        startActivity(Intent(this, InitializationActivity::class.java))
        finish()
    }

    private fun onRefresh() {
        CoilInstance.evict(this)
        viewModel.messages.clear()
        launchCoroutine {
            loadMore(viewModel.appId).forEachIndexed { index, message ->
                if (message.image != null) {
                    listMessageAdapter.notifyItemChanged(index)
                }
            }
        }
    }

    private fun openDocumentation() {
        val browserIntent = Intent(Intent.ACTION_VIEW, "https://gotify.net/docs/pushmsg".toUri())
        startActivity(browserIntent)
    }

    private fun onUpdateApps(applications: List<Application>) {
        if (isFinishing || isDestroyed) return
        val menu: Menu = binding.navView.menu
        menu.removeGroup(R.id.apps)
        viewModel.targetReferences.clear()
        // FORK: do not stop the initial loading spinner just because the app list arrived first.
        if (viewModel.messages.isLoaded(viewModel.appId)) {
            updateMessagesAndStopLoading(viewModel.messages[viewModel.appId])
        }
        var selectedItem = menu.findItem(R.id.nav_all_messages)
        applications.indices.forEach { index ->
            val app = applications[index]
            val item = menu.add(R.id.apps, index, APPLICATION_ORDER, app.name)
            item.isCheckable = true
            if (app.id == viewModel.appId) selectedItem = item
            val t = Utils.toDrawable { icon -> item.icon = icon }
            viewModel.targetReferences.add(t)
            val request = ImageRequest.Builder(this)
                .data(Utils.resolveAbsoluteUrl(viewModel.settings.url + "/", app.image))
                .error(R.drawable.ic_alarm)
                .placeholder(R.drawable.ic_placeholder)
                .size(100, 100)
                .target(t)
                .build()
            CoilInstance.get(this).enqueue(request)
        }
        selectAppInMenu(selectedItem)
    }

    private fun initDrawer() {
        setSupportActionBar(binding.appBarDrawer.toolbar)
        binding.navView.itemIconTintList = null
        val toggle = ActionBarDrawerToggle(
            this,
            binding.drawerLayout,
            binding.appBarDrawer.toolbar,
            R.string.navigation_drawer_open,
            R.string.navigation_drawer_close
        )
        binding.drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        binding.navView.setNavigationItemSelectedListener(this)
        val headerView = binding.navView.getHeaderView(0)

        val settings = viewModel.settings

        val user = headerView.findViewById<TextView>(R.id.header_user)
        user.text = settings.user?.name

        val connection = headerView.findViewById<TextView>(R.id.header_connection)
        connection.text = settings.url
        connection.setOnClickListener { showEndpointDialog() } // FORK
        val version = headerView.findViewById<TextView>(R.id.header_version)
        version.text =
            getString(R.string.versions, BuildConfig.VERSION_NAME, settings.serverVersion)

        val refreshAll = headerView.findViewById<ImageButton>(R.id.refresh_all)
        refreshAll.setOnClickListener { refreshAll() }
    }

    private fun addBackPressCallback() {
        onBackPressedCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, onBackPressedCallback)
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        // Handle navigation view item clicks here.
        val id = item.itemId
        if (item.groupId == R.id.apps) {
            val app = viewModel.appsHolder.get()[id]
            updateAppOnDrawerClose = app.id
            startLoading()
            binding.appBarDrawer.toolbar.subtitle = item.title
        } else if (id == R.id.nav_all_messages) {
            updateAppOnDrawerClose = MessageState.ALL_MESSAGES
            startLoading()
            binding.appBarDrawer.toolbar.subtitle = ""
        } else if (id == R.id.logout) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.logout)
                .setMessage(getString(R.string.logout_confirm))
                .setPositiveButton(R.string.yes) { _, _ -> doLogout() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else if (id == R.id.nav_logs) {
            startActivity(Intent(this, LogsActivity::class.java))
        } else if (id == R.id.nav_server_addresses) {
            // FORK: first-class entry point for the public/internal server addresses
            showServerAddressesDialog()
        } else if (id == R.id.settings) {
            startActivity(Intent(this, SettingsActivity::class.java))
        } else if (id == R.id.push_message) {
            val intent = Intent(this@MessagesActivity, ShareActivity::class.java)
            startActivity(intent)
        }
        binding.drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }

    private fun doLogout() {
        setContentView(R.layout.splash)
        launchCoroutine {
            deleteClientAndNavigateToLogin()
        }
    }

    private fun startLoading() {
        binding.swipeRefresh.isRefreshing = true
        binding.messagesView.visibility = View.GONE
    }

    private fun stopLoading() {
        binding.swipeRefresh.isRefreshing = false
        binding.messagesView.visibility = View.VISIBLE
    }

    override fun onResume() {
        // FORK: onCreate can finish early (invalid session/URL) before the ViewModel exists.
        if (!::viewModel.isInitialized) {
            super.onResume()
            return
        }
        // FORK: the endpoint can change while this activity is in the background (automatic probe
        // or a new internal address in the settings), the pinned Retrofit client would go stale.
        if (normalizeEndpoint(viewModel.clientBaseUrl) !=
            normalizeEndpoint(viewModel.settings.url)
        ) {
            super.onResume()
            reloadForEndpoint()
            return
        }
        if (!viewModel.settings.hasValidUrl) {
            super.onResume()
            reloadForEndpoint()
            return
        }
        Logger.info("OnResume " + javaClass.simpleName)
        updateHeaderEndpoint()
        val context = applicationContext
        val nManager = context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nManager.cancelAll()
        val filter = IntentFilter()
        filter.addAction(WebSocketService.NEW_MESSAGE_BROADCAST)
        filter.addAction(WebSocketService.CONNECTED_BROADCAST) // FORK
        filter.addAction(EndpointResolver.ENDPOINT_CHANGED_BROADCAST) // FORK
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, RECEIVER_EXPORTED)
        } else {
            @SuppressLint("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
        receiverRegistered = true // FORK
        registerNetworkCallback()
        EndpointResolver.probeForCurrentNetwork() // FORK: try the intranet address on Wi-Fi
        launchCoroutine {
            try {
                // FORK: onResume can run before the startup coroutine finished hydrating the
                // cache. Make sure the newest cached id is known before asking for missed
                // messages.
                var lastReceived = viewModel.messages.getLastReceivedMessage()
                if (lastReceived == -1L) {
                    viewModel.messages.hydrateFromCache(viewModel.appId)
                    lastReceived = viewModel.messages.getLastReceivedMessage()
                }
                updateMissedMessages(lastReceived)
            } catch (e: Exception) {
                Logger.error(e, "Could not fetch missed messages")
            }
        }
        var selectedIndex = R.id.nav_all_messages
        val appId = viewModel.appId
        if (appId != MessageState.ALL_MESSAGES) {
            val apps = viewModel.appsHolder.get()
            apps.indices.forEach { index ->
                if (apps[index].id == appId) {
                    selectedIndex = index
                }
            }
        }
        // Force re-render of all items to update relative date-times on app resume.
        listMessageAdapter.notifyDataSetChanged()
        selectAppInMenu(binding.navView.menu.findItem(selectedIndex))
        super.onResume()
    }

    override fun onPause() {
        // FORK: onResume can return early (endpoint changed) before registering the receiver
        if (receiverRegistered) {
            unregisterReceiver(receiver)
            receiverRegistered = false
        }
        if (networkCallbackRegistered) {
            runCatching {
                (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager)
                    .unregisterNetworkCallback(networkCallback)
            }
            networkCallbackRegistered = false
        }
        super.onPause()
    }

    private fun registerNetworkCallback() {
        if (networkCallbackRegistered) return
        runCatching {
            val manager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            manager.registerDefaultNetworkCallback(networkCallback)
            networkCallbackRegistered = true
        }.onFailure {
            Logger.error(it, "Could not register the network callback")
        }
    }

    private fun updateHeaderEndpoint() {
        if (!::viewModel.isInitialized || isFinishing || isDestroyed) return
        binding.navView.getHeaderView(0)
            .findViewById<TextView>(R.id.header_connection)
            ?.text = viewModel.settings.url
    }

    private fun selectAppInMenu(appItem: MenuItem?) {
        if (appItem != null) {
            appItem.isChecked = true
            if (appItem.itemId != R.id.nav_all_messages) {
                binding.appBarDrawer.toolbar.subtitle = appItem.title
            }
        }
    }

    private fun scheduleDeletion(message: Message) {
        val adapter = binding.messagesView.adapter as ListMessageAdapter
        val messages = viewModel.messages
        messages.deleteLocal(message)
        adapter.updateList(messages[viewModel.appId])
        showDeletionSnackbar()
    }

    private fun undoDelete() {
        val messages = viewModel.messages
        val deletion = messages.undoDeleteLocal()
        if (deletion != null) {
            val adapter = binding.messagesView.adapter as ListMessageAdapter
            val appId = viewModel.appId
            adapter.updateList(messages[appId])
        }
    }

    private fun showDeletionSnackbar() {
        val view: View = binding.swipeRefresh
        val snackbar = Snackbar.make(view, R.string.snackbar_deleted, Snackbar.LENGTH_LONG)
        snackbar.setAction(R.string.snackbar_undo) { undoDelete() }
        snackbar.addCallback(SnackbarCallback())
        snackbar.show()
    }

    private inner class SnackbarCallback : BaseCallback<Snackbar?>() {
        override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
            super.onDismissed(transientBottomBar, event)
            if (event != DISMISS_EVENT_ACTION && event != DISMISS_EVENT_CONSECUTIVE) {
                // Execute deletion when the snackbar disappeared without pressing the undo button
                // DISMISS_EVENT_CONSECUTIVE should be excluded as well, because it would cause the
                // deletion to be sent to the server twice, since the deletion is sent to the server
                // in MessageFacade if a message is deleted while another message was already
                // waiting for deletion.
                launchCoroutine {
                    commitDeleteMessage()
                }
            }
        }
    }

    private inner class SwipeToDeleteCallback(private val adapter: ListMessageAdapter) :
        ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
        private var icon: Drawable?
        private val background: ColorDrawable

        init {
            val backgroundColorId =
                ContextCompat.getColor(this@MessagesActivity, R.color.swipeBackground)
            val iconColorId = ContextCompat.getColor(this@MessagesActivity, R.color.swipeIcon)
            val drawable = ContextCompat.getDrawable(this@MessagesActivity, R.drawable.ic_delete)
            icon = null
            if (drawable != null) {
                icon = DrawableCompat.wrap(drawable.mutate())
                DrawableCompat.setTint(icon!!, iconColorId)
            }
            background = backgroundColorId.toDrawable()
        }

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ) = false

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            val position = viewHolder.adapterPosition
            val message = adapter.currentList[position]
            scheduleDeletion(message.message)
        }

        override fun onChildDraw(
            c: Canvas,
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            dX: Float,
            dY: Float,
            actionState: Int,
            isCurrentlyActive: Boolean
        ) {
            icon?.let {
                val itemView = viewHolder.itemView
                val iconHeight = itemView.height / 3
                val scale = iconHeight / it.intrinsicHeight.toDouble()
                val iconWidth = (it.intrinsicWidth * scale).toInt()
                val iconMarginLeftRight = 50
                val iconMarginTopBottom = (itemView.height - iconHeight) / 2
                val iconTop = itemView.top + iconMarginTopBottom
                val iconBottom = itemView.bottom - iconMarginTopBottom
                if (dX > 0) {
                    // Swiping to the right
                    val iconLeft = itemView.left + iconMarginLeftRight
                    val iconRight = itemView.left + iconMarginLeftRight + iconWidth
                    it.setBounds(iconLeft, iconTop, iconRight, iconBottom)
                    background.setBounds(
                        itemView.left,
                        itemView.top,
                        itemView.left + dX.toInt(),
                        itemView.bottom
                    )
                } else if (dX < 0) {
                    // Swiping to the left
                    val iconLeft = itemView.right - iconMarginLeftRight - iconWidth
                    val iconRight = itemView.right - iconMarginLeftRight
                    it.setBounds(iconLeft, iconTop, iconRight, iconBottom)
                    background.setBounds(
                        itemView.right + dX.toInt(),
                        itemView.top,
                        itemView.right,
                        itemView.bottom
                    )
                } else {
                    // View is unswiped
                    it.setBounds(0, 0, 0, 0)
                    background.setBounds(0, 0, 0, 0)
                }
                background.draw(c)
                it.draw(c)
            }
            super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
        }
    }

    private inner class MessageListOnScrollListener : RecyclerView.OnScrollListener() {
        override fun onScrollStateChanged(view: RecyclerView, scrollState: Int) {}

        override fun onScrolled(view: RecyclerView, dx: Int, dy: Int) {
            val linearLayoutManager = view.layoutManager as LinearLayoutManager?
            if (linearLayoutManager != null) {
                val lastVisibleItem = linearLayoutManager.findLastVisibleItemPosition()
                val totalItemCount = view.adapter!!.itemCount
                if (lastVisibleItem > totalItemCount - 15 &&
                    totalItemCount != 0 &&
                    viewModel.messages.canLoadMore(viewModel.appId)
                ) {
                    if (!isLoadMore) {
                        isLoadMore = true
                        launchCoroutine {
                            loadMore(viewModel.appId)
                        }
                    }
                }
            }
        }
    }

    private suspend fun updateMissedMessages(id: Long, withLoadingSpinner: Boolean = false) {
        if (id == -1L) return

        val newMessages = MissedMessageUtil(viewModel.client.createService(MessageApi::class.java))
            .missingMessages(id).filterNotNull()
        viewModel.messages.addMessages(newMessages)

        if (newMessages.isNotEmpty()) {
            updateMessagesForApplication(withLoadingSpinner, viewModel.appId)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (!::viewModel.isInitialized) return false
        menuInflater.inflate(R.menu.messages_action, menu)
        menu.findItem(R.id.action_delete_app).isVisible =
            viewModel.appId != MessageState.ALL_MESSAGES
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (!::viewModel.isInitialized) return false
        if (item.itemId == R.id.action_delete_all) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.delete_all)
                .setMessage(R.string.ack)
                .setPositiveButton(R.string.yes) { _, _ ->
                    launchCoroutine {
                        deleteMessages(viewModel.appId)
                    }
                }
                .setNegativeButton(R.string.no, null)
                .show()
        }
        if (item.itemId == R.id.action_delete_app) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.delete_app)
                .setMessage(R.string.delete_app_not_supported)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
        return super.onContextItemSelected(item)
    }

    private suspend fun loadMore(appId: Long): List<MessageWithImage> {
        val messagesWithImages = viewModel.messages.loadMore(appId)
        withContext(Dispatchers.Main) {
            updateMessagesAndStopLoading(messagesWithImages)
        }
        return messagesWithImages
    }

    private suspend fun updateMessagesForApplication(withLoadingSpinner: Boolean, appId: Long) {
        if (withLoadingSpinner) {
            withContext(Dispatchers.Main) {
                startLoading()
            }
            viewModel.messages.loadMoreIfNotPresent(appId)
        }
        // FORK: without the spinner this is a pure UI refresh. Loading another page here would
        // delay already available (cached / just received) messages behind a network round trip.
        withContext(Dispatchers.Main) {
            updateMessagesAndStopLoading(viewModel.messages[appId])
        }
    }

    private suspend fun addSingleMessage(message: Message) {
        viewModel.messages.addMessages(listOf(message))
        updateMessagesForApplication(false, viewModel.appId)
    }

    private suspend fun commitDeleteMessage() {
        viewModel.messages.commitDelete()
        updateMessagesForApplication(false, viewModel.appId)
    }

    private suspend fun deleteMessages(appId: Long) {
        withContext(Dispatchers.Main) {
            startLoading()
        }
        val success = viewModel.messages.deleteAll(appId)
        if (success) {
            updateMessagesForApplication(false, viewModel.appId)
        } else {
            withContext(Dispatchers.Main) {
                Utils.showSnackBar(this@MessagesActivity, "Delete failed :(")
            }
        }
    }

    private fun deleteClientAndNavigateToLogin() {
        val settings = viewModel.settings
        if (!settings.hasValidUrl) {
            stopService(Intent(this@MessagesActivity, WebSocketService::class.java))
            settings.clear()
            startActivity(Intent(this@MessagesActivity, LoginActivity::class.java))
            finish()
            return
        }
        val tokenClient = ClientFactory.clientToken(settings)
        stopService(Intent(this@MessagesActivity, WebSocketService::class.java))
        try {
            Logger.info("Logging out...")
            val authApi = tokenClient.createService(AuthApi::class.java)
            Api.execute(authApi.logout())
        } catch (e: ApiException) {
            if (e.code == 404) {
                Logger.info("Logout endpoint not available, falling back to client deletion")
                deleteCurrentClient(tokenClient, settings)
            } else {
                Logger.error(e, "Could not logout")
            }
        }

        viewModel.settings.clear()
        startActivity(Intent(this@MessagesActivity, LoginActivity::class.java))
        finish()
    }

    private fun deleteCurrentClient(tokenClient: ApiClient, settings: Settings) {
        val api = tokenClient.createService(ClientApi::class.java)
        try {
            val clients = Api.execute(api.clients)
            val currentClient = clients.firstOrNull { it.token == settings.token }
            if (currentClient != null) {
                Logger.info("Delete client with id " + currentClient.id)
                Api.execute(api.deleteClient(currentClient.id))
            } else {
                Logger.error("Could not delete client, client does not exist.")
            }
        } catch (e: ApiException) {
            Logger.error(e, "Could not delete client")
        }
    }

    private fun updateMessagesAndStopLoading(messageWithImages: List<MessageWithImage>) {
        if (isFinishing || isDestroyed) return
        isLoadMore = false
        stopLoading()
        if (viewModel.messages.offline && !offlineNotified) { // FORK
            offlineNotified = true
            Utils.showSnackBar(this, getString(R.string.fork_offline_snackbar))
        }
        if (messageWithImages.isEmpty()) {
            binding.flipper.displayedChild = 1
            // FORK: do not claim "no messages" when the list is simply empty while offline
            binding.root.findViewById<TextView>(R.id.textView2)?.setText(
                if (viewModel.messages.offline) {
                    R.string.fork_offline_empty
                } else {
                    R.string.no_messages_yet
                }
            )
        } else {
            binding.flipper.displayedChild = 0
        }
        val adapter = binding.messagesView.adapter as ListMessageAdapter
        adapter.updateList(messageWithImages)
    }

    // FORK-BEGIN: offline mode, background session validation and endpoint switching
    private fun refreshFromNetwork() {
        if (!::viewModel.isInitialized || isFinishing || isDestroyed) return
        // The Retrofit client is bound to the URL it was created with, so a switched endpoint
        // needs a fresh activity/model instead of a simple reload.
        if (normalizeEndpoint(viewModel.clientBaseUrl) !=
            normalizeEndpoint(viewModel.settings.url)
        ) {
            reloadForEndpoint()
            return
        }
        if (!viewModel.settings.hasValidUrl) {
            reloadForEndpoint()
            return
        }
        launchCoroutine {
            // FORK: do not clear the hydrated cache here. On a reconnect only the missing (newer)
            // messages are fetched, so the cached history stays on screen and no loading spinner
            // is shown over data the user can already read.
            val lastReceived = viewModel.messages.getLastReceivedMessage()
            if (lastReceived == -1L || !viewModel.messages.isLoaded(viewModel.appId)) {
                updateMessagesForApplication(true, viewModel.appId)
            } else {
                updateMissedMessages(lastReceived)
            }
        }
    }

    // Same as refreshAll(), but keeps the image cache: both addresses serve the same server.
    private fun reloadForEndpoint() {
        if (reloadRequested || isFinishing || isDestroyed) return
        reloadRequested = true
        startActivity(Intent(this, InitializationActivity::class.java))
        finish()
    }

    private fun validateSession() {
        if (isFinishing || isDestroyed) return
        try {
            ClientFactory.userApiWithToken(viewModel.settings).currentUser().enqueue(
                Callback.callInUI(
                    this,
                    onSuccess = Callback.SuccessBody { user ->
                        if (!isFinishing && !isDestroyed) {
                            viewModel.settings.setUser(user.name, user.admin)
                            updateHeaderUser()
                            fetchServerVersion()
                        }
                    },
                    onError = { exception ->
                        if (!isFinishing && !isDestroyed && exception.code == 401) {
                            showSessionExpired()
                        }
                    }
                )
            )
        } catch (e: Exception) {
            Logger.error(e, "Could not validate the session")
        }
    }

    private fun fetchServerVersion() {
        if (isFinishing || isDestroyed) return
        try {
            ClientFactory.infoApi(viewModel.settings).version.enqueue(
                Callback.callInUI(
                    this,
                    onSuccess = Callback.SuccessBody { version ->
                        if (!isFinishing && !isDestroyed) {
                            viewModel.settings.serverVersion = version.version
                            updateHeaderVersion()
                        }
                    },
                    onError = {}
                )
            )
        } catch (e: Exception) {
            Logger.error(e, "Could not fetch the server version")
        }
    }

    private fun updateHeaderUser() {
        if (isFinishing || isDestroyed) return
        binding.navView.getHeaderView(0)
            .findViewById<TextView>(R.id.header_user)
            ?.text = viewModel.settings.user?.name
    }

    private fun updateHeaderVersion() {
        if (isFinishing || isDestroyed) return
        val version = getString(
            R.string.versions,
            BuildConfig.VERSION_NAME,
            viewModel.settings.serverVersion
        )
        binding.navView.getHeaderView(0)
            .findViewById<TextView>(R.id.header_version)
            ?.text = version
    }

    private fun showSessionExpired() {
        if (sessionDialogShown || isFinishing || isDestroyed) return
        sessionDialogShown = true
        try {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.fork_session_expired_title)
                .setMessage(R.string.fork_session_expired)
                .setPositiveButton(R.string.login) { _, _ -> doLogout() }
                .setCancelable(false)
                .show()
        } catch (e: Exception) {
            Logger.error(e, "Could not show the session dialog")
        }
    }

    private fun showEndpointDialog() {
        if (isFinishing || isDestroyed) return
        val settings = viewModel.settings
        val endpoints = listOfNotNull(
            normalizeEndpoint(settings.storedUrl),
            normalizeEndpoint(settings.backupUrl)
        ).distinct()
        val labels = mutableListOf(getString(R.string.fork_endpoint_auto))
        labels.addAll(endpoints)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.fork_endpoint_title)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == 0) {
                    EndpointResolver.override(null)
                } else {
                    EndpointResolver.override(endpoints[which - 1])
                }
                refreshFromNetwork()
            }
            .setNeutralButton(R.string.fork_edit_addresses) { _, _ ->
                showServerAddressesDialog()
            }
            .show()
    }

    private fun showServerAddressesDialog() {
        if (isFinishing || isDestroyed) return
        ServerAddressesDialog.show(this, viewModel.settings) {
            if (!isFinishing && !isDestroyed) {
                if (normalizeEndpoint(viewModel.clientBaseUrl) !=
                    normalizeEndpoint(viewModel.settings.url)
                ) {
                    reloadForEndpoint()
                } else {
                    refreshFromNetwork()
                }
            }
        }
    }
    // FORK-END

    private fun ListMessageAdapter.updateList(list: List<MessageWithImage>) {
        this.submitList(if (this.currentList == list) list.toList() else list) {
            val topChild = binding.messagesView.getChildAt(0)
            if (topChild != null && topChild.top == 0) {
                binding.messagesView.scrollToPosition(0)
            }
        }
    }

    companion object {
        private const val APPLICATION_ORDER = 1
    }
}
