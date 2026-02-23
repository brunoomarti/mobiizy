package com.brunocodex.kotlinproject.adapters

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.brunocodex.kotlinproject.fragments.AddressInfoFragment
import com.brunocodex.kotlinproject.fragments.BasicInfoFragment
import com.brunocodex.kotlinproject.fragments.CredentialsInfoFragment
import com.brunocodex.kotlinproject.fragments.ProfileSelectionFragment

class ViewPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {

    override fun getItemCount(): Int = 4

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> ProfileSelectionFragment()
            1 -> BasicInfoFragment()
            2 -> AddressInfoFragment()
            3 -> CredentialsInfoFragment()
            else -> ProfileSelectionFragment()
        }
    }
}