package io.legado.app.base

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.appcompat.view.SupportMenuInflater
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import io.legado.app.R
import io.legado.app.ui.widget.TitleBar
import io.legado.app.utils.applyTint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel

@Suppress("MemberVisibilityCanBePrivate")
abstract class BaseFragment(layoutID: Int) : Fragment(layoutID),
    CoroutineScope by MainScope() {

    var supportToolbar: Toolbar? = null
        private set

    val menuInflater: MenuInflater
        @SuppressLint("RestrictedApi")
        get() = SupportMenuInflater(requireContext())

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        onMultiWindowModeChanged()
        onFragmentCreated(view, savedInstanceState)
        observeLiveBus()
    }

    abstract fun onFragmentCreated(view: View, savedInstanceState: Bundle?)

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean) {
        super.onMultiWindowModeChanged(isInMultiWindowMode)
        onMultiWindowModeChanged()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        onMultiWindowModeChanged()
    }

    private fun onMultiWindowModeChanged() {
        (activity as? BaseActivity<*>)?.let {
            view?.findViewById<TitleBar>(R.id.title_bar)
                ?.onMultiWindowModeChanged(it.isInMultiWindow, it.fullScreen)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cancel()
    }

    fun setSupportToolbar(toolbar: Toolbar) {
        supportToolbar = toolbar
        supportToolbar?.let {
            it.menu.apply {
                onCompatCreateOptionsMenu(this)
                applyTint(requireContext())
            }

            it.setOnMenuItemClickListener { item ->
                onCompatOptionsItemSelected(item)
                true
            }
        }
    }

    open fun observeLiveBus() {
    }

    open fun onCompatCreateOptionsMenu(menu: Menu) {
    }

    open fun onCompatOptionsItemSelected(item: MenuItem) {
    }

}
