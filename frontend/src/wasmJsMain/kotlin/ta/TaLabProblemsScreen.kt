package ta

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import data.TaLabInfo
import data.TaLabProblem
import data.TaProblemDetail
import data.TaTestCase
import editor.ProblemPanel
import html.HtmlRenderer

private val PROBLEM_LIST_WIDTH = 220.dp
private const val DIALOG_WIDTH_FRACTION = 0.9f
private const val DIALOG_HEIGHT_FRACTION = 0.85f
private val DIALOG_WIDTH = 720.dp
private val DIALOG_HEIGHT = 560.dp
private const val BYTES_PER_TENTH_MB = 100_000L

/**
 * A lab's problems for the TA: pick one to see its description and every test case, hidden
 * (data/secret) ones included. Each problem is fetched only when selected.
 */
@Composable
fun TaLabProblemsScreen(
    lab: TaLabInfo,
    service: TaBackendService
) {
    var selectedSlug by remember { mutableStateOf(lab.problems.firstOrNull()?.slug) }
    var detail by remember { mutableStateOf<TaProblemDetail?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var openTestCase by remember { mutableStateOf<TaTestCase?>(null) }
    var testCasesExpanded by remember { mutableStateOf(false) }
    val renderer = remember { HtmlRenderer() }

    LaunchedEffect(selectedSlug) {
        openTestCase = null
        val slug = selectedSlug ?: return@LaunchedEffect
        isLoading = true
        loadError = null
        detail = null
        // getLabProblem returns failures as values; only cancellation (a newer selection) escapes,
        // ending this load before it can touch the new one's state
        when (val result = service.getLabProblem(lab.labId, slug)) {
            is TaLoadResult.Success -> detail = result.value
            is TaLoadResult.Failure -> loadError = result.error.message
        }
        isLoading = false
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "${lab.courseCode} Section ${lab.section} - Lab ${lab.labNumber} Problems",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )

            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Card(modifier = Modifier.width(PROBLEM_LIST_WIDTH).fillMaxHeight()) {
                    LazyColumn {
                        items(lab.problems.map { it.slug }) { slug ->
                            ProblemListItem(
                                slug = slug,
                                isSelected = slug == selectedSlug,
                                onClick = { selectedSlug = slug }
                            )
                            HorizontalDivider()
                        }
                    }
                }

                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    when {
                        isLoading -> LoadingProblem(lab.problems.find { it.slug == selectedSlug })
                        loadError != null -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(loadError!!, color = MaterialTheme.colorScheme.error)
                        }
                        detail != null -> {
                            TitledSection("Problem Description", Modifier.weight(1f)) {
                                // The description is a DOM iframe drawn above the Compose canvas, so it
                                // would cover the dialog; leaving it out while a test case is open hides it.
                                if (openTestCase == null) {
                                    ProblemPanel(
                                        html = detail!!.html,
                                        css = detail!!.css,
                                        renderer = renderer,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                            // Collapsed, the section shrinks to its heading and the description takes the space.
                            CollapsibleSection(
                                title = testCasesTitle(detail!!.testCases),
                                isExpanded = testCasesExpanded,
                                onToggle = { testCasesExpanded = !testCasesExpanded },
                                modifier = if (testCasesExpanded) Modifier.weight(1f) else Modifier
                            ) {
                                TestCaseList(detail!!.testCases, onOpen = { openTestCase = it })
                            }
                        }
                    }
                }
            }
        }

        openTestCase?.let { testCase ->
            TestCaseDialog(
                testCase = testCase,
                renderer = renderer,
                maxWidth = maxWidth * DIALOG_WIDTH_FRACTION,
                maxHeight = maxHeight * DIALOG_HEIGHT_FRACTION,
                onDismiss = { openTestCase = null }
            )
        }
    }
}

/** A heading above a card that fills the rest of the section. */
@Composable
private fun TitledSection(title: String, modifier: Modifier, content: @Composable () -> Unit) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = TaGreen)
        Card(modifier = Modifier.fillMaxWidth().weight(1f)) {
            content()
        }
    }
}

/** A [TitledSection] whose heading is a dropdown toggle that shows or hides the card. */
@Composable
private fun CollapsibleSection(
    title: String,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier,
    content: @Composable () -> Unit
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.clickable(onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                if (isExpanded) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                contentDescription = if (isExpanded) "Collapse" else "Expand",
                tint = TaGreen
            )
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = TaGreen)
        }
        if (isExpanded) {
            Card(modifier = Modifier.fillMaxWidth().weight(1f)) {
                content()
            }
        }
    }
}

private fun testCasesTitle(testCases: List<TaTestCase>): String {
    val hiddenCount = testCases.count { it.hidden }
    return "Test Cases · ${testCases.size - hiddenCount} sample · $hiddenCount hidden"
}

/** Spinner, plus a heads-up when the backend flagged this problem's test data as large. */
@Composable
private fun LoadingProblem(problem: TaLabProblem?) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator()
        if (problem?.isLarge == true) {
            Text(
                "This is a large problem (${formatMegabytes(problem.testDataBytes)} of test data) and may take a while to load.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Bytes as megabytes with one decimal, e.g. 33.4 MB (no String.format on wasm). */
private fun formatMegabytes(bytes: Long): String {
    val tenths = bytes / BYTES_PER_TENTH_MB
    return "${tenths / 10}.${tenths % 10} MB"
}

@Composable
private fun ProblemListItem(slug: String, isSelected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Text(
            slug,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun TestCaseList(testCases: List<TaTestCase>, onOpen: (TaTestCase) -> Unit) {
    if (testCases.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "No test cases found",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    LazyColumn {
        items(testCases, key = { it.name }) { testCase ->
            TestCaseRow(testCase, onClick = { onOpen(testCase) })
            HorizontalDivider()
        }
    }
}

@Composable
private fun TestCaseRow(testCase: TaTestCase, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TestCaseBadge(testCase.hidden)
        Text(testCase.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text("Open", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun TestCaseBadge(hidden: Boolean) {
    Surface(
        color = if (hidden) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            if (hidden) "Hidden" else "Sample",
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * Input and expected output side by side, shown through the HTML bridge (see [TestCaseHtml]) so every
 * script displays and scrolling/selection are the browser's own. A standard fixed size, shrunk to
 * [maxWidth] x [maxHeight] on small windows. Uses the screen's [renderer]: the description is not
 * composed while the dialog is open, so the single shared iframe is free.
 */
@Composable
private fun TestCaseDialog(
    testCase: TaTestCase,
    renderer: HtmlRenderer,
    maxWidth: Dp,
    maxHeight: Dp,
    onDismiss: () -> Unit
) {
    val html = remember(testCase) { TestCaseHtml.body(testCase.input, testCase.expected) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.width(minOf(DIALOG_WIDTH, maxWidth)).height(minOf(DIALOG_HEIGHT, maxHeight)),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TestCaseBadge(testCase.hidden)
                Text(testCase.name)
            }
        },
        text = {
            ProblemPanel(
                html = html,
                css = TestCaseHtml.CSS,
                renderer = renderer,
                modifier = Modifier.fillMaxSize()
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}
