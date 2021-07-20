package io.legado.app.ui.rss.source.manage

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.SubMenu
import androidx.activity.viewModels
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SearchView
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.ItemTouchHelper
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppPattern
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssSource
import io.legado.app.databinding.ActivityRssSourceBinding
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.help.IntentDataHelp
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.ATH
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.ui.association.ImportRssSourceActivity
import io.legado.app.ui.document.FilePicker
import io.legado.app.ui.document.FilePickerParam
import io.legado.app.ui.qrcode.QrCodeResult
import io.legado.app.ui.rss.source.edit.RssSourceEditActivity
import io.legado.app.ui.widget.SelectActionBar
import io.legado.app.ui.widget.dialog.TextDialog
import io.legado.app.ui.widget.recycler.DragSelectTouchHelper
import io.legado.app.ui.widget.recycler.ItemTouchCallback
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.utils.*
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.File

/**
 * 订阅源管理
 */
class RssSourceActivity : VMBaseActivity<ActivityRssSourceBinding, RssSourceViewModel>(),
    PopupMenu.OnMenuItemClickListener,
    SelectActionBar.CallBack,
    RssSourceAdapter.CallBack {

    override val binding by viewBinding(ActivityRssSourceBinding::inflate)
    override val viewModel by viewModels<RssSourceViewModel>()
    private val importRecordKey = "rssSourceRecordKey"
    private lateinit var adapter: RssSourceAdapter
    private var sourceFlowJob: Job? = null
    private var groups = hashSetOf<String>()
    private var groupMenu: SubMenu? = null
    private val qrCodeResult = registerForActivityResult(QrCodeResult()) {
        it ?: return@registerForActivityResult
        startActivity<ImportRssSourceActivity> {
            putExtra("source", it)
        }
    }
    private val importDoc = registerForActivityResult(FilePicker()) { uri ->
        kotlin.runCatching {
            uri?.readText(this)?.let {
                val dataKey = IntentDataHelp.putData(it)
                startActivity<ImportRssSourceActivity> {
                    putExtra("dataKey", dataKey)
                }
            }
        }.onFailure {
            toastOnUi("readTextError:${it.localizedMessage}")
        }
    }
    private val exportDir = registerForActivityResult(FilePicker()) { uri ->
        uri ?: return@registerForActivityResult
        if (uri.isContentScheme()) {
            DocumentFile.fromTreeUri(this, uri)?.let {
                viewModel.exportSelection(adapter.getSelection(), it)
            }
        } else {
            uri.path?.let {
                viewModel.exportSelection(adapter.getSelection(), File(it))
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initRecyclerView()
        initSearchView()
        initGroupFlow()
        upSourceFlow()
        initViewEvent()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.rss_source, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
        groupMenu = menu?.findItem(R.id.menu_group)?.subMenu
        upGroupMenu()
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_add -> startActivity<RssSourceEditActivity>()
            R.id.menu_import_local -> importDoc.launch(
                FilePickerParam(
                    mode = FilePicker.FILE,
                    allowExtensions = arrayOf("txt", "json")
                )
            )
            R.id.menu_import_onLine -> showImportDialog()
            R.id.menu_import_qr -> qrCodeResult.launch(null)
            R.id.menu_group_manage -> GroupManageDialog()
                .show(supportFragmentManager, "rssGroupManage")
            R.id.menu_share_source -> viewModel.shareSelection(adapter.getSelection()) {
                startActivity(Intent.createChooser(it, getString(R.string.share_selected_source)))
            }
            R.id.menu_import_default -> viewModel.importDefault()
            R.id.menu_help -> showHelp()
            else -> if (item.groupId == R.id.source_group) {
                binding.titleBar.findViewById<SearchView>(R.id.search_view)
                    .setQuery("group:${item.title}", true)
            }
        }
        return super.onCompatOptionsItemSelected(item)
    }

    override fun onMenuItemClick(item: MenuItem?): Boolean {
        when (item?.itemId) {
            R.id.menu_enable_selection -> viewModel.enableSelection(adapter.getSelection())
            R.id.menu_disable_selection -> viewModel.disableSelection(adapter.getSelection())
            R.id.menu_del_selection -> viewModel.delSelection(adapter.getSelection())
            R.id.menu_export_selection -> exportDir.launch(null)
            R.id.menu_top_sel -> viewModel.topSource(*adapter.getSelection().toTypedArray())
            R.id.menu_bottom_sel -> viewModel.bottomSource(*adapter.getSelection().toTypedArray())
        }
        return true
    }

    private fun initRecyclerView() {
        ATH.applyEdgeEffectColor(binding.recyclerView)
        binding.recyclerView.addItemDecoration(VerticalDivider(this))
        adapter = RssSourceAdapter(this, this)
        binding.recyclerView.adapter = adapter
        // When this page is opened, it is in selection mode
        val dragSelectTouchHelper: DragSelectTouchHelper =
            DragSelectTouchHelper(adapter.dragSelectCallback).setSlideArea(16, 50)
        dragSelectTouchHelper.attachToRecyclerView(binding.recyclerView)
        dragSelectTouchHelper.activeSlideSelect()
        // Note: need judge selection first, so add ItemTouchHelper after it.
        val itemTouchCallback = ItemTouchCallback(adapter)
        itemTouchCallback.isCanDrag = true
        ItemTouchHelper(itemTouchCallback).attachToRecyclerView(binding.recyclerView)
    }

    private fun initSearchView() {
        binding.titleBar.findViewById<SearchView>(R.id.search_view).let {
            ATH.setTint(it, primaryTextColor)
            it.onActionViewExpanded()
            it.queryHint = getString(R.string.search_rss_source)
            it.clearFocus()
            it.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean {
                    return false
                }

                override fun onQueryTextChange(newText: String?): Boolean {
                    upSourceFlow(newText)
                    return false
                }
            })
        }
    }

    private fun initGroupFlow() {
        launch {
            appDb.rssSourceDao.liveGroup().collect {
                groups.clear()
                it.map { group ->
                    groups.addAll(group.splitNotBlank(AppPattern.splitGroupRegex))
                }
                upGroupMenu()
            }
        }
    }

    override fun selectAll(selectAll: Boolean) {
        if (selectAll) {
            adapter.selectAll()
        } else {
            adapter.revertSelection()
        }
    }

    override fun revertSelection() {
        adapter.revertSelection()
    }

    override fun onClickMainAction() {
        delSourceDialog()
    }

    private fun initViewEvent() {
        binding.selectActionBar.setMainActionText(R.string.delete)
        binding.selectActionBar.inflateMenu(R.menu.rss_source_sel)
        binding.selectActionBar.setOnMenuItemClickListener(this)
        binding.selectActionBar.setCallBack(this)
    }

    private fun delSourceDialog() {
        alert(titleResource = R.string.draw, messageResource = R.string.sure_del) {
            okButton { viewModel.delSelection(adapter.getSelection()) }
            noButton()
        }.show()
    }

    private fun upGroupMenu() = groupMenu?.let { menu ->
        menu.removeGroup(R.id.source_group)
        groups.sortedWith { o1, o2 ->
            o1.cnCompare(o2)
        }.map {
            menu.add(R.id.source_group, Menu.NONE, Menu.NONE, it)
        }
    }

    private fun upSourceFlow(searchKey: String? = null) {
        sourceFlowJob?.cancel()
        sourceFlowJob = launch {
            when {
                searchKey.isNullOrBlank() -> {
                    appDb.rssSourceDao.liveAll()
                }
                searchKey.startsWith("group:") -> {
                    val key = searchKey.substringAfter("group:")
                    appDb.rssSourceDao.liveGroupSearch("%$key%")
                }
                else -> {
                    appDb.rssSourceDao.liveSearch("%$searchKey%")
                }
            }.collect {
                adapter.setItems(it, adapter.diffItemCallback)
            }
        }
    }

    private fun showHelp() {
        val text = String(assets.open("help/SourceMRssHelp.md").readBytes())
        TextDialog.show(supportFragmentManager, text, TextDialog.MD)
    }

    override fun upCountView() {
        binding.selectActionBar.upCountView(
            adapter.getSelection().size,
            adapter.itemCount
        )
    }

    @SuppressLint("InflateParams")
    private fun showImportDialog() {
        val aCache = ACache.get(this, cacheDir = false)
        val cacheUrls: MutableList<String> = aCache
            .getAsString(importRecordKey)
            ?.splitNotBlank(",")
            ?.toMutableList() ?: mutableListOf()
        alert(titleResource = R.string.import_on_line) {
            val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.setFilterValues(cacheUrls)
                editView.delCallBack = {
                    cacheUrls.remove(it)
                    aCache.put(importRecordKey, cacheUrls.joinToString(","))
                }
            }
            customView { alertBinding.root }
            okButton {
                val text = alertBinding.editView.text?.toString()
                text?.let {
                    if (!cacheUrls.contains(it)) {
                        cacheUrls.add(0, it)
                        aCache.put(importRecordKey, cacheUrls.joinToString(","))
                    }
                    startActivity<ImportRssSourceActivity> {
                        putExtra("source", it)
                    }
                }
            }
            cancelButton()
        }.show()
    }

    override fun del(source: RssSource) {
        viewModel.del(source)
    }

    override fun edit(source: RssSource) {
        startActivity<RssSourceEditActivity> {
            putExtra("data", source.sourceUrl)
        }
    }

    override fun update(vararg source: RssSource) {
        viewModel.update(*source)
    }

    override fun toTop(source: RssSource) {
        viewModel.topSource(source)
    }

    override fun toBottom(source: RssSource) {
        viewModel.bottomSource(source)
    }

    override fun upOrder() {
        viewModel.upOrder()
    }

}