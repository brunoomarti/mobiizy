package com.brunocodex.kotlinproject.activities

import android.os.Bundle
import android.view.View
import com.brunocodex.kotlinproject.R
import com.brunocodex.kotlinproject.fragments.ProfileFragment
import com.brunocodex.kotlinproject.fragments.RenterDiscoverFragment
import com.brunocodex.kotlinproject.fragments.RenterHomeFragment
import com.brunocodex.kotlinproject.fragments.RenterRentalsFragment

class RenterHomeActivity : BaseHomeActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        initializeBaseHome(
            contentLayoutRes = R.layout.activity_dashboard,
            rootViewId = R.id.main,
            savedInstanceState = savedInstanceState
        )

        findViewById<View>(R.id.navWalletItem)?.visibility = View.GONE
    }

    override fun defaultNavItemId(): Int = R.id.navHomeItem

    override fun buildNavDestinations(): List<NavDestination> {
        return listOf(
            NavDestination(
                itemId = R.id.navHomeItem,
                tag = "renter_home",
                title = getString(R.string.nav_home),
                subtitle = getString(R.string.renter_home_fragment_subtitle),
                usePersonalGreetingTitle = true,
                iconName = "home",
                contentDescription = getString(R.string.nav_home)
            ) {
                RenterHomeFragment()
            },
            NavDestination(
                itemId = R.id.navGarageItem,
                tag = "renter_discover",
                title = getString(R.string.nav_renter_discover),
                iconName = "search",
                contentDescription = getString(R.string.nav_renter_discover)
            ) {
                RenterDiscoverFragment()
            },
            NavDestination(
                itemId = R.id.navTripsItem,
                tag = "renter_rentals",
                title = getString(R.string.nav_renter_rentals),
                iconName = "calendar_month",
                contentDescription = getString(R.string.nav_renter_rentals)
            ) {
                RenterRentalsFragment()
            },
            NavDestination(
                itemId = R.id.navProfileItem,
                tag = "renter_profile",
                title = getString(R.string.nav_profile),
                iconName = "person",
                contentDescription = getString(R.string.nav_profile)
            ) {
                ProfileFragment()
            }
        )
    }
}
