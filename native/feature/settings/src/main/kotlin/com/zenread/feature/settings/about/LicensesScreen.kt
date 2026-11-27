package com.zenread.feature.settings.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import com.zenread.feature.settings.R
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/** Settings › About › Open-source licenses; with [componentId], that component's license text. */
@Serializable
data class AboutLicensesKey(val componentId: String? = null) : NavKey

/** Entry point for [AboutLicensesKey]. */
@Composable
fun AboutLicensesScreen(
    componentId: String?,
    onBack: () -> Unit,
    onOpenComponent: (String) -> Unit,
) {
    val component = componentId?.let(OpenSourceComponents::find)
    if (component == null) {
        OpenSourceLicensesScreen(onBack, onOpenComponent)
    } else {
        LicenseTextScreen(component, onBack)
    }
}

@Composable
private fun OpenSourceLicensesScreen(onBack: () -> Unit, onOpenComponent: (String) -> Unit) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.licenses_title)) },
                navigationIcon = { BackButton(onBack) },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                ComponentGroup(stringResource(R.string.licenses_section_ported), OpenSourceComponents.ported, onOpenComponent)
            }
            item {
                ComponentGroup(stringResource(R.string.licenses_section_libraries), OpenSourceComponents.libraries, onOpenComponent)
            }
        }
    }
}

@Composable
private fun ComponentGroup(title: String, components: List<OpenSourceComponent>, onOpen: (String) -> Unit) {
    Group(title) {
        components.forEach { component ->
            AboutRow(
                title = component.name,
                summary = stringResource(component.role) + "\n" + component.license.spdx,
                onClick = { onOpen(component.id) },
            )
        }
    }
}

@Composable
private fun LicenseTextScreen(component: OpenSourceComponent, onBack: () -> Unit) {
    val context = LocalContext.current
    // null while loading, empty when this build does not carry the text (the page links to it instead).
    val paragraphs by produceState<List<String>?>(initialValue = null, component.textAsset) {
        val asset = component.textAsset
        value = if (asset == null) {
            emptyList()
        } else {
            withContext(Dispatchers.IO) {
                try {
                    reflowLicenseText(context.assets.open(asset).bufferedReader().use { it.readText() })
                } catch (_: IOException) {
                    emptyList()
                }
            }
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(component.name) },
                navigationIcon = { BackButton(onBack) },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(component.license.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        component.copyright,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                Group(component.license.spdx) {
                    AboutRow(
                        icon = { Icon(Icons.Rounded.Public, contentDescription = null) },
                        title = stringResource(R.string.licenses_homepage),
                        summary = component.homepage.removePrefix("https://"),
                        trailing = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, stringResource(R.string.about_open_link)) },
                        onClick = { openInBrowser(context, component.homepage) },
                    )
                    AboutRow(
                        icon = { Icon(Icons.Rounded.Public, contentDescription = null) },
                        title = stringResource(R.string.licenses_view_online),
                        summary = if (paragraphs?.isEmpty() == true) stringResource(R.string.licenses_not_bundled) else null,
                        trailing = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, stringResource(R.string.about_open_link)) },
                        onClick = { openInBrowser(context, component.license.url) },
                    )
                }
            }
            paragraphs?.let { text ->
                items(text) { paragraph ->
                    SelectionContainer {
                        Text(
                            paragraph,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.settings_back))
    }
}
