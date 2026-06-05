package labx.lockdown

actual fun createActivityLogSessionHook(assignmentBase: String, studentEmail: String): ActivityLogSessionHook =
    DesktopActivityLogSessionHook(assignmentBase, studentEmail)
