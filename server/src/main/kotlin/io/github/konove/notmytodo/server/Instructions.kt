package io.github.konove.notmytodo.server

/** What the server tells an agent on connecting, so that it does not find the ids and the projects by trial and error. */
object Instructions {
    fun of(workspace: Workspace): String {
        val here = try {
            workspace.here()
        } catch (_: Exception) {
            null
        }
        val record = here?.record
        val where = when {
            record != null -> "The current project is ${record.key}, the repository at ${here.root}."
            here?.root?.let(workspace::unimported) != null ->
                "The items of ${here.root} are still in .todos/items.json, kept by the Not My TODO plugin: use the IDE's todo tools there " +
                    "until the plugin imports them into the store. todo_list with project=\"*\" lists every other project."
            here?.root != null -> "${here.root} has no project yet: todo_list shows nothing here until the first todo_create makes one; pass project=\"*\" to list every project."
            else -> "The folder this session runs in is not a project: todo_list lists every project, and todo_create files into inbox."
        }
        return listOf(
            "not-my-todo-store keeps TODO items for every repository on this machine, outside the repositories.",
            where,
            "Ids are T-1, T-2, ... within a project; write key/T-12, for example infra/T-12, for an item of another project. " +
                "Every tool takes project to work in another project: its key, inbox, or a folder.",
            "inbox holds items that belong to no repository; todo_move moves an item into a project. todo_projects lists the projects.",
        ).joinToString("\n")
    }
}
