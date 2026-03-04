package com.brunocodex.kotlinproject.adapters

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.brunocodex.kotlinproject.fragments.VehicleStep1TypeAvailabilityFragment
import com.brunocodex.kotlinproject.fragments.VehicleStep2IdentificationFragment
import com.brunocodex.kotlinproject.fragments.VehicleStep3MediaFragment
import com.brunocodex.kotlinproject.fragments.VehicleStep4ConditionFragment
import com.brunocodex.kotlinproject.fragments.VehicleStep5DocumentationFragment
import com.brunocodex.kotlinproject.fragments.VehicleStep6LocationFragment
import com.brunocodex.kotlinproject.fragments.VehicleStep7ReviewFragment

class VehicleRegisterPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {

    override fun getItemCount(): Int = 7

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> VehicleStep1TypeAvailabilityFragment()
            1 -> VehicleStep2IdentificationFragment()
            2 -> VehicleStep3MediaFragment()
            3 -> VehicleStep4ConditionFragment()
            4 -> VehicleStep5DocumentationFragment()
            5 -> VehicleStep6LocationFragment()
            6 -> VehicleStep7ReviewFragment()
            else -> VehicleStep1TypeAvailabilityFragment()
        }
    }
}
