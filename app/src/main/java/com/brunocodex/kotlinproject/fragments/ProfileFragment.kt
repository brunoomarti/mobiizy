package com.brunocodex.kotlinproject.fragments

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import com.brunocodex.kotlinproject.R
import com.brunocodex.kotlinproject.activities.BaseHomeActivity

class ProfileFragment : Fragment(R.layout.fragment_profile) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<View>(R.id.btnProfileLogout).setOnClickListener { clicked ->
            (activity as? BaseHomeActivity)?.onLogoutClicked(clicked)
        }
    }
}
