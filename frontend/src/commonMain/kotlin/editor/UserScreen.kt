package editor

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import backend.BackendService
import data.LabProblemInfo
import data.ProblemRepository
import data.Student
import theme.AppTheme

enum class Tab {
    CODE_EDITOR, SUBMISSIONS
}

@Composable
fun UserScreen(
    student: Student,
    problem: LabProblemInfo,
    backend: BackendService,
    repository: ProblemRepository,
    autosaveService: AutosaveService = NoOpAutosaveService,
    currentTheme: AppTheme = AppTheme.LIGHT,
    onThemeChange: (AppTheme) -> Unit = {},
    onSubmitExit: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Tab bar
        var selectedTab: Tab? = null
        TabRow(selectedTabIndex = 0) {
            Tab.entries.forEach { tab ->
                Tab(
                    selected = selectedTab == tab,
                    onClick = { selectedTab = tab },
                    text = { Text(tab.name) }
                )
            }
        }

        // Content area
        Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            when (selectedTab) {
                Tab.CODE_EDITOR, null -> CodeEditorScreen(
                    student,
                    problem,
                    backend,
                    repository,
                    autosaveService,
                    currentTheme,
                    onThemeChange,
                    onSubmitExit
                )
                Tab.SUBMISSIONS -> SubmissionScreen()
            }
        }
    }
}