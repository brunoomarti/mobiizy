package com.brunocodex.kotlinproject.fragments

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import com.brunocodex.kotlinproject.R
import com.brunocodex.kotlinproject.activities.ProviderHomeActivity

class ProviderHomeFragment : Fragment(R.layout.fragment_provider_home) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<View>(R.id.btnGoToVehicles).setOnClickListener {
            (activity as? ProviderHomeActivity)?.openVehiclesTabFromHome()
        }
    }
}
