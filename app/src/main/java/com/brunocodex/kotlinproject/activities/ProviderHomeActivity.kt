package com.brunocodex.kotlinproject.activities

import android.os.Bundle
import android.view.View
import com.brunocodex.kotlinproject.R
import com.brunocodex.kotlinproject.fragments.ProfileFragment
import com.brunocodex.kotlinproject.fragments.ProviderHomeFragment
import com.brunocodex.kotlinproject.fragments.ProviderRentalsFragment
import com.brunocodex.kotlinproject.fragments.ProviderVehiclesFragment

class ProviderHomeActivity : BaseHomeActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        initializeBaseHome(
            contentLayoutRes = R.layout.activity_dashboard,
            rootViewId = R.id.main,
            savedInstanceState = savedInstanceState
        )

        findViewById<View>(R.id.navWalletItem)?.visibility = View.GONE
    }

    override fun defaultNavItemId(): Int = R.id.navHomeItem

    fun openVehiclesTabFromHome() {
        selectNavItem(R.id.navGarageItem)
    }

    override fun buildNavDestinations(): List<NavDestination> {
        return listOf(
            NavDestination(
                itemId = R.id.navHomeItem,
                tag = "provider_home",
                title = getString(R.string.nav_home),
                subtitle = getString(R.string.provider_home_fragment_subtitle),
                usePersonalGreetingTitle = true,
                iconName = "home",
                contentDescription = getString(R.string.nav_home)
            ) {
                ProviderHomeFragment()
            },
            NavDestination(
                itemId = R.id.navGarageItem,
                tag = "provider_vehicles",
                title = getString(R.string.nav_provider_vehicles),
                iconName = "directions_car",
                contentDescription = getString(R.string.nav_provider_vehicles)
            ) {
                ProviderVehiclesFragment()
            },
            NavDestination(
                itemId = R.id.navTripsItem,
                tag = "provider_rentals",
                title = getString(R.string.nav_provider_rentals),
                iconName = "calendar_month",
                contentDescription = getString(R.string.nav_provider_rentals)
            ) {
                ProviderRentalsFragment()
            },
            NavDestination(
                itemId = R.id.navProfileItem,
                tag = "provider_profile",
                title = getString(R.string.nav_profile),
                iconName = "person",
                contentDescription = getString(R.string.nav_profile)
            ) {
                ProfileFragment()
            }
        )
    }
}
