package com.brunocodex.kotlinproject.activities

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.exceptions.ClearCredentialException
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.brunocodex.kotlinproject.R
import com.google.android.material.color.MaterialColors
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch

abstract class BaseHomeActivity : AppCompatActivity() {

    data class NavDestination(
        val itemId: Int,
        val tag: String,
        val title: String,
        val subtitle: String? = null,
        val usePersonalGreetingTitle: Boolean = false,
        val iconName: String,
        val contentDescription: String,
        val fragmentFactory: () -> Fragment
    )

    private val prefs by lazy { getSharedPreferences("app_prefs", MODE_PRIVATE) }
    private val credentialManager by lazy { CredentialManager.create(this) }
    private val db by lazy { FirebaseFirestore.getInstance() }

    private var selectedItemId: Int = View.NO_ID
    private var destinationByItemId: Map<Int, NavDestination> = emptyMap()
    private var navItems: Map<Int, View> = emptyMap()
    private var navIcons: Map<Int, TextView> = emptyMap()
    private var navSelectionIndicator: View? = null
    private var navItemsContainer: View? = null
    private var dashboardTitleView: TextView? = null
    private var dashboardSubtitleView: TextView? = null
    private var currentUserFirstName: String? = null

    private val permissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val denied = it.filterValues { granted -> !granted }.keys
            if (denied.isEmpty()) {
                prefs.edit().putBoolean(KEY_PERMISSIONS_PROMPTED, true).apply()
                return@registerForActivityResult
            }

            val shouldReRequest = denied.any { permission ->
                shouldShowRequestPermissionRationale(permission)
            }
            prefs.edit()
                .putBoolean(KEY_PERMISSIONS_PROMPTED, !shouldReRequest)
                .apply()
        }

    protected fun initializeBaseHome(contentLayoutRes: Int, rootViewId: Int, savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(contentLayoutRes)

        val rootView = findViewById<View>(rootViewId)
        val initialPaddingLeft = rootView.paddingLeft
        val initialPaddingTop = rootView.paddingTop
        val initialPaddingRight = rootView.paddingRight
        val initialPaddingBottom = rootView.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(rootView) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                initialPaddingLeft + systemBars.left,
                initialPaddingTop + systemBars.top,
                initialPaddingRight + systemBars.right,
                initialPaddingBottom + systemBars.bottom
            )
            insets
        }

        dashboardTitleView = findViewById(R.id.tvDashboardTitle)
        dashboardSubtitleView = findViewById(R.id.tvDashboardSubtitle)
        seedFirstNameFromFirebaseAuth()
        anchorBottomNavToScreenBottom()
        setupBottomNav()
        restoreInitialDestination(savedInstanceState)
        loadFirstNameFromUserProfile()
        maybeRequestRuntimePermissions()
    }

    protected abstract fun buildNavDestinations(): List<NavDestination>
    protected abstract fun defaultNavItemId(): Int

    protected open fun onNavItemSelected(itemId: Int) = Unit

    fun onLogoutClicked(view: View) {
        lifecycleScope.launch {
            FirebaseAuth.getInstance().signOut()

            try {
                credentialManager.clearCredentialState(ClearCredentialStateRequest())
            } catch (_: ClearCredentialException) {
                // Nao bloqueia logout local se limpar estado global falhar.
            }

            val intent = Intent(this@BaseHomeActivity, LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (selectedItemId != View.NO_ID) {
            outState.putInt(KEY_SELECTED_NAV_ITEM, selectedItemId)
        }
    }

    protected fun setBottomNavIcon(itemId: Int, iconName: String, contentDescription: String) {
        val icon = navIcons[itemId] ?: return
        icon.text = iconName
        icon.contentDescription = contentDescription
    }

    protected fun selectNavItem(itemId: Int) {
        openDestination(itemId, forceReplace = false)
    }

    private fun restoreInitialDestination(savedInstanceState: Bundle?) {
        val preferredItemId = savedInstanceState?.getInt(KEY_SELECTED_NAV_ITEM)
            ?: defaultNavItemId()
        val initialItemId = if (destinationByItemId.containsKey(preferredItemId)) {
            preferredItemId
        } else {
            defaultNavItemId()
        }

        openDestination(initialItemId, forceReplace = supportFragmentManager.findFragmentById(R.id.dashboardFragmentContainer) == null)
    }

    private fun setupBottomNav() {
        navSelectionIndicator = findViewById(R.id.navSelectionIndicator)
        navItemsContainer = findViewById(R.id.navItemsContainer)

        navItems = NAV_ITEM_IDS.mapNotNull { id ->
            findViewById<View>(id)?.let { id to it }
        }.toMap()
        if (navItems.size != NAV_ITEM_IDS.size) return

        navIcons = navItems.mapNotNull { (id, item) ->
            val icon = item.findViewById<TextView>(R.id.navItemIcon)
            icon?.let { id to it }
        }.toMap()

        destinationByItemId = buildNavDestinations().associateBy { it.itemId }
        destinationByItemId.values.forEach { destination ->
            setBottomNavIcon(destination.itemId, destination.iconName, destination.contentDescription)
        }

        navItems.forEach { (id, item) ->
            bindNavItemState(id, selected = false)
            item.setOnClickListener { openDestination(id, forceReplace = false) }
        }
    }

    private fun openDestination(itemId: Int, forceReplace: Boolean) {
        val destination = destinationByItemId[itemId] ?: return

        if (!forceReplace && selectedItemId == itemId) return

        val current = supportFragmentManager.findFragmentById(R.id.dashboardFragmentContainer)
        val fragment = supportFragmentManager.findFragmentByTag(destination.tag) ?: destination.fragmentFactory()

        if (forceReplace || current?.tag != destination.tag) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.dashboardFragmentContainer, fragment, destination.tag)
                .commit()
        }

        val shouldAnimateIndicator = selectedItemId != View.NO_ID && !forceReplace
        selectedItemId = itemId
        dashboardTitleView?.text = resolveDashboardTitle(destination)
        updateDashboardSubtitle(destination.subtitle)
        navItems.keys.forEach { id -> bindNavItemState(id, selected = id == itemId) }
        moveSelectionIndicator(itemId, animate = shouldAnimateIndicator)
        onNavItemSelected(itemId)
    }

    private fun resolveDashboardTitle(destination: NavDestination): String {
        if (!destination.usePersonalGreetingTitle) return destination.title
        return getString(R.string.dashboard_title_greeting, resolveUserFirstName())
    }

    private fun updateDashboardSubtitle(subtitle: String?) {
        val subtitleView = dashboardSubtitleView ?: return
        val text = subtitle?.trim().orEmpty()
        if (text.isBlank()) {
            subtitleView.text = ""
            subtitleView.visibility = View.GONE
            return
        }

        subtitleView.text = text
        subtitleView.visibility = View.VISIBLE
    }

    private fun seedFirstNameFromFirebaseAuth() {
        val user = FirebaseAuth.getInstance().currentUser ?: return
        currentUserFirstName = extractFirstName(user.displayName)
            ?: extractFirstName(user.email?.substringBefore("@"))
    }

    private fun loadFirstNameFromUserProfile() {
        val user = FirebaseAuth.getInstance().currentUser ?: return
        db.collection("users").document(user.uid).get()
            .addOnSuccessListener { doc ->
                val profileFirstName = extractFirstName(doc.getString("name")) ?: return@addOnSuccessListener
                currentUserFirstName = profileFirstName
                refreshGreetingHeaderIfNeeded()
            }
    }

    private fun refreshGreetingHeaderIfNeeded() {
        val currentDestination = destinationByItemId[selectedItemId] ?: return
        if (!currentDestination.usePersonalGreetingTitle) return
        dashboardTitleView?.text = resolveDashboardTitle(currentDestination)
    }

    private fun resolveUserFirstName(): String {
        return currentUserFirstName ?: getString(R.string.dashboard_user_fallback_name)
    }

    private fun extractFirstName(rawName: String?): String? {
        val cleaned = rawName?.trim().orEmpty()
        if (cleaned.isBlank()) return null

        return cleaned
            .replace(Regex("[._-]+"), " ")
            .split(Regex("\\s+"))
            .firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun bindNavItemState(itemId: Int, selected: Boolean) {
        val item = navItems[itemId] ?: return
        val icon = navIcons[itemId]

        val selectedTint = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary, 0x05091F)
        val unselectedTint = MaterialColors.getColor(this, R.attr.textMuted, 0x9AA3AF)
        icon?.setTextColor(if (selected) selectedTint else unselectedTint)
    }

    private fun moveSelectionIndicator(itemId: Int, animate: Boolean) {
        val indicator = navSelectionIndicator ?: return
        val item = navItems[itemId] ?: return
        val container = navItemsContainer ?: return

        indicator.post {
            val targetX = container.left + item.left + ((item.width - indicator.width) / 2f)

            if (animate) {
                indicator.animate()
                    .x(targetX)
                    .setDuration(230L)
                    .start()
            } else {
                indicator.x = targetX
            }
        }
    }

    private fun maybeRequestRuntimePermissions() {
        if (prefs.getBoolean(KEY_PERMISSIONS_PROMPTED, false)) return

        val missing = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
                missing += Manifest.permission.ACCESS_FINE_LOCATION
            }
            if (!hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                missing += Manifest.permission.ACCESS_COARSE_LOCATION
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
                missing += Manifest.permission.POST_NOTIFICATIONS
            }
        }

        if (missing.isEmpty()) {
            prefs.edit().putBoolean(KEY_PERMISSIONS_PROMPTED, true).apply()
            return
        }

        permissionsLauncher.launch(missing.toTypedArray())
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun anchorBottomNavToScreenBottom() {
        val nav = findViewById<View>(R.id.floatingBottomNav) ?: findViewById(R.id.bottomNavRoot) ?: return
        val parent = nav.parent as? FrameLayout ?: return

        val params = (nav.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                nav.layoutParams?.height ?: FrameLayout.LayoutParams.WRAP_CONTENT
            )

        params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        params.marginStart = 0
        params.marginEnd = 0
        params.bottomMargin = dpToPx(20)
        nav.layoutParams = params
        parent.bringChildToFront(nav)
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    companion object {
        private const val KEY_PERMISSIONS_PROMPTED = "runtime_permissions_prompted"
        private const val KEY_SELECTED_NAV_ITEM = "selected_nav_item"
        private val NAV_ITEM_IDS = listOf(
            R.id.navHomeItem,
            R.id.navGarageItem,
            R.id.navTripsItem,
            R.id.navWalletItem,
            R.id.navProfileItem
        )
    }
}
